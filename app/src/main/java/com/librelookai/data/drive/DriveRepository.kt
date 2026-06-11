package com.librelookai.data.drive
import android.content.Context
import android.util.Log
import com.librelookai.BuildConfig
import com.google.gson.Gson
import com.google.gson.annotations.SerializedName
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.io.ByteArrayOutputStream
import java.io.File
import java.net.URLEncoder
import com.librelookai.auth.GoogleAuthManager
import com.librelookai.util.ImageEncoding
import com.librelookai.data.model.Location
import com.librelookai.data.model.Outfit
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton

// ---------- DTOs ----------

data class DriveFileDto(
    val id: String = "",
    val name: String = "",
    val mimeType: String? = null,
    val appProperties: Map<String, String>? = null,
    /** Raw Drive API size field (returned as a string); -1 if not requested or unavailable. */
    private val size: String? = null,
    /** ISO-8601 createdTime from Drive (only populated when requested in `fields=`). */
    val createdTime: String? = null,
    /** ISO-8601 modifiedTime from Drive (only populated when requested in `fields=`). */
    val modifiedTime: String? = null,
    /** Whether the file is in the trash (only populated when `trashed` is requested in `fields=`). */
    val trashed: Boolean? = null,
) {
    val sizeBytes: Long get() = size?.toLongOrNull() ?: -1L
    val createdTimeMs: Long get() = createdTime?.let {
        runCatching { java.time.Instant.parse(it).toEpochMilli() }.getOrNull()
    } ?: 0L
}

internal data class FilesListDto(
    val files: List<DriveFileDto> = emptyList(),
    val nextPageToken: String? = null,
)

// ---------- Repository ----------

private const val TAG = "DriveRepository"

@Singleton
class DriveRepository @Inject constructor(
    @param:ApplicationContext private val context: Context,
    private val auth: GoogleAuthManager,
) {
    companion object {
        internal const val API = "https://www.googleapis.com/drive/v3"
        internal const val UPLOAD_API = "https://www.googleapis.com/upload/drive/v3"
        internal const val FOLDER_NAME = "LibreLookAI"
        internal const val FOLDER_MIME = "application/vnd.google-apps.folder"
        /**
         * appProperties marker stamped on a root folder THIS app created (fresh-create or migration
         * target). Lets us tell our own root apart from a legacy/foreign "LibreLookAI" folder, so we
         * can re-adopt it on reinstall / new device (recovery) and never re-migrate it. See
         * [getOrCreateFolder] / [findLegacyMigrationSource] and archive § "Drive access model".
         */
        internal const val APP_ROOT_PROP = "llaiAppRoot"
        internal const val OUTFITS_FILE_NAME = "_outfits.json"
        internal const val OUTFIT_EVENTS_FILE_NAME = "_outfit_events.json"
        /** Legacy filename from before the Style→Outfit rename. Read-only fallback. */
        internal const val LEGACY_STYLES_FILE_NAME = "_styles_metadata.json"
        /** Legacy filename from before the outfit-events rename. Read-only fallback. */
        internal const val LEGACY_OUTFIT_EVENTS_FILE_NAME = "_outfits_metadata.json"
        internal const val PREFERENCES_FILE_NAME = "_user_preferences.json"
        internal const val WARDROBE_METADATA_FILE_NAME = "_wardrobe_metadata.json"
        internal const val LOCATIONS_FILE_NAME = "_locations.json"

        /**
         * Region-keyed cache of fetched fashion trends, lives at the root Drive folder.
         * Shared across devices so the weekly Gemini trend lookup is amortised per account.
         */
        internal const val TRENDS_CACHE_FILE_NAME = "_trends_cache.json"

        /**
         * Subfolder of the root Drive folder used to hold the user's try-on photos
         * (front/side/back). Kept separate from all location/closet folders so these
         * images are never listed as wardrobe items or touched by Repair & Sync.
         */
        const val PROFILE_FOLDER_NAME = "_profile"

        /**
         * Subfolder of the root Drive folder used to hold generated try-on PNGs.
         * Kept out of every location/closet folder so these images are never treated
         * as wardrobe items and never scanned by Repair & Sync.
         */
        const val TRYONS_FOLDER_NAME = "_tryons"

        /**
         * Subfolder of the root Drive folder used to hold the user's shopping wishlist.
         * Items here have the same cutout / original / sidecar layout as a regular closet
         * so they can be moved to a real closet by file-rename only. The folder is **not**
         * a [Location] — it never appears in `_locations.json`, the closet picker, or the
         * outfits flow.
         */
        const val SHOPPING_FOLDER_NAME = "_shopping"

        /**
         * Subfolder of the root Drive folder used to hold the user's saved travel trips.
         * Each trip is persisted as `{tripId}.json`. Not a closet — never appears in
         * `_locations.json` or the closet picker.
         */
        const val TRIPS_FOLDER_NAME = "_trips"

        /** Names of root-level subfolders that are NOT user closets (filter from listSubfolders). */
        val NON_CLOSET_SUBFOLDER_NAMES = setOf(
            PROFILE_FOLDER_NAME,
            TRYONS_FOLDER_NAME,
            SHOPPING_FOLDER_NAME,
            TRIPS_FOLDER_NAME,
        )

        /** Index JSON listing all saved try-ons; lives at the root Drive folder. */
        const val TRYONS_FILE_NAME = "_tryons.json"

        /**
         * Canonical suffix for NEW cutout uploads (WebP). Files are treated as wardrobe items
         * when [ImageEncoding.isCutoutName] matches (covers this and the legacy `.png` suffix);
         * originals and raw uploads are excluded from [listFiles] and never shown in the app.
         */
        const val CUTOUT_SUFFIX = ImageEncoding.CUTOUT_SUFFIX

        /** Canonical suffix for NEW archived originals (WebP); legacy `.jpg` still read. */
        const val ORIGINAL_SUFFIX = ImageEncoding.ORIGINAL_SUFFIX

        /** Suffix for per-item metadata sidecar files (named "{cutoutDriveId}.json"). */
        const val SIDECAR_SUFFIX = ".json"

        /** System-level JSON files that must never be treated as item sidecars. */
        internal val SYSTEM_JSON_NAMES = setOf(
            WARDROBE_METADATA_FILE_NAME,
            OUTFITS_FILE_NAME,
            OUTFIT_EVENTS_FILE_NAME,
            LEGACY_STYLES_FILE_NAME,
            LEGACY_OUTFIT_EVENTS_FILE_NAME,
            PREFERENCES_FILE_NAME,
            LOCATIONS_FILE_NAME,
            TRENDS_CACHE_FILE_NAME,
            TRYONS_FILE_NAME,
        )
    }

    internal val http = OkHttpClient()
    internal val gson = Gson()

    /** App-private persistent cache for wardrobe images. */
    val cacheDir: File = File(context.filesDir, "wardrobe").also { it.mkdirs() }

    internal suspend fun token() = auth.getAccessToken()

    // ---------- Root folder (drive.file) ----------
    // Under the drive.file scope the app can only see folders it created or that the user handed
    // it via the Google Picker. A name search for "LibreLookAI" therefore returns nothing for a
    // pre-existing folder, so we persist the resolved/picked root ID and prefer it.
    private fun rootPrefs() = context.getSharedPreferences("drive_root", Context.MODE_PRIVATE)

    // Set once the stored root has been confirmed reachable this session, so we don't pay a HEAD
    // round-trip on every getOrCreateFolder. Reset implicitly per repo instance.
    @Volatile private var rootVerified = false

    /** User-picked (or previously resolved) root folder ID, or null if not chosen yet. */
    fun pickedRootFolderId(): String? = rootPrefs().getString("folder_id", null)?.takeIf { it.isNotBlank() }

    /** Persist the root folder ID (called by the onboarding picker, or after creating a fresh one). */
    fun setPickedRootFolder(id: String) { rootPrefs().edit().putString("folder_id", id).apply() }

    fun clearPickedRootFolder() { rootPrefs().edit().remove("folder_id").apply() }

    /**
     * Fetches all pages from a Drive files.list [baseUrl] (must include all params except
     * pageToken) and returns the concatenated file list.
     */
    internal suspend fun fetchAllPages(baseUrl: String, tok: String): List<DriveFileDto> {
        val result = mutableListOf<DriveFileDto>()
        var pageToken: String? = null
        do {
            val url = if (pageToken != null) "$baseUrl&pageToken=${URLEncoder.encode(pageToken, "UTF-8")}" else baseUrl
            val page = gson.fromJson(
                http.newCall(Request.Builder().url(url).header("Authorization", "Bearer $tok").build()).await().body!!.string(),
                FilesListDto::class.java,
            )
            result += page.files
            pageToken = page.nextPageToken
        } while (pageToken != null)
        return result
    }

    /**
     * Returns the Drive root folder ID, self-healing across reinstalls / lost access:
     *   1. A stored (picked/resolved) root is reused — but first verified reachable; a stranded
     *      root (deleted / trashed / wrong identity → 404/403) is dropped so we re-resolve instead
     *      of silently showing an empty wardrobe (archive § "Drive access model").
     *   2. Otherwise a name search for [FOLDER_NAME] re-adopts a root THIS app created before
     *      (carries the [APP_ROOT_PROP] marker) — reinstall / new-device recovery. Under `drive.file`
     *      any name match is necessarily app-created (the scope can't see foreign files), so an
     *      unmarked match is back-filled with the marker and adopted too.
     *   3. A still-unmarked match under full `drive` may be a legacy/foreign folder, so it's returned
     *      for reads but NOT claimed as the active root (migration must decide — see
     *      [findLegacyMigrationSource]).
     *   4. Brand-new account → create a fresh, marked root.
     * The resolved ID is persisted whenever we're sure it's ours, so subsequent calls short-circuit.
     */
    suspend fun getOrCreateFolder(): String = withContext(Dispatchers.IO) {
        val tok = token()
        pickedRootFolderId()?.let { stored ->
            if (rootVerified || folderAccessible(stored, tok)) {
                rootVerified = true
                return@withContext stored
            }
            Log.w(TAG, "stored root $stored is inaccessible — clearing and re-resolving")
            clearPickedRootFolder()
        }
        val q = URLEncoder.encode(
            "mimeType='$FOLDER_MIME' and name='$FOLDER_NAME' and trashed=false",
            "UTF-8",
        )
        val matches = fetchAllPages("$API/files?q=$q&fields=files(id,appProperties),nextPageToken", tok)
        // Re-adopt a root we created earlier (marker present) — survives reinstall / new device.
        matches.firstOrNull { it.appProperties?.get(APP_ROOT_PROP) == "1" }?.let {
            setPickedRootFolder(it.id); rootVerified = true
            return@withContext it.id
        }
        matches.firstOrNull()?.let { match ->
            if (!BuildConfig.DRIVE_FULL_SCOPE) {
                // drive.file can only see files this app created, so an unmarked name match is ours
                // (a pre-marker migrated root). Back-fill the marker and adopt it.
                runCatching { updateAppProperties(match.id, mapOf(APP_ROOT_PROP to "1")) }
                setPickedRootFolder(match.id); rootVerified = true
                return@withContext match.id
            }
            // Full drive: could be a legacy/foreign folder → readable, but leave migration to claim it.
            return@withContext match.id
        }
        createMarkedRootFolder(FOLDER_NAME, tok).also { setPickedRootFolder(it); rootVerified = true }
    }

    /** True if [folderId] resolves and isn't trashed. A 404/403/trashed means lost or stranded access. */
    private suspend fun folderAccessible(folderId: String, tok: String): Boolean {
        val resp = http.newCall(
            Request.Builder()
                .url("$API/files/$folderId?fields=id,trashed")
                .header("Authorization", "Bearer $tok")
                .build(),
        ).await()
        if (!resp.isSuccessful) return false
        val dto = runCatching { gson.fromJson(resp.body!!.string(), DriveFileDto::class.java) }.getOrNull()
        return dto != null && dto.id.isNotBlank() && dto.trashed != true
    }

    /** Creates a fresh top-level [name] folder stamped with the [APP_ROOT_PROP] marker. Returns its ID. */
    private suspend fun createMarkedRootFolder(name: String, tok: String): String {
        val meta = """{"name":${gson.toJson(name)},"mimeType":"$FOLDER_MIME","appProperties":{"$APP_ROOT_PROP":"1"}}"""
        return gson.fromJson(
            http.newCall(
                Request.Builder()
                    .url("$API/files?fields=id")
                    .header("Authorization", "Bearer $tok")
                    .post(meta.toRequestBody("application/json".toMediaType()))
                    .build(),
            ).await().body!!.string(),
            DriveFileDto::class.java,
        ).id
    }

    /**
     * Migration build only (full `drive`): finds an existing LibreLookAI data folder eligible for the
     * one-time legacy→app-folder copy, or null if there's nothing to migrate. "Eligible" = a
     * non-trashed [FOLDER_NAME] folder that holds genuine LibreLookAI data (a signature metadata JSON
     * or a known non-closet subfolder) and is NOT one of our own marked roots. Returns null the moment
     * a root has been picked (migration already ran, or a fresh root was created). Oldest match wins —
     * the real legacy data predates any stray copies. See [migrateLegacyInto].
     */
    suspend fun findLegacyMigrationSource(): String? = withContext(Dispatchers.IO) {
        if (pickedRootFolderId() != null) return@withContext null
        val tok = token()
        val q = URLEncoder.encode(
            "mimeType='$FOLDER_MIME' and name='$FOLDER_NAME' and trashed=false",
            "UTF-8",
        )
        val candidates = fetchAllPages(
            "$API/files?q=$q&fields=files(id,appProperties,createdTime),nextPageToken&pageSize=100",
            tok,
        ).filter { it.appProperties?.get(APP_ROOT_PROP) != "1" }
            .sortedBy { it.createdTimeMs }
        candidates.firstOrNull { holdsLibreLookData(it.id) }?.id
    }

    /** True if [folderId] contains LibreLookAI's signature data (a system JSON or a known subfolder). */
    private suspend fun holdsLibreLookData(folderId: String): Boolean =
        listAllChildren(folderId).any {
            it.name in SYSTEM_JSON_NAMES ||
                (it.mimeType == FOLDER_MIME && it.name in NON_CLOSET_SUBFOLDER_NAMES)
        }

    /**
     * Lists wardrobe image files in the given Drive folder, newest first.
     * Only returns files whose name ends with [CUTOUT_SUFFIX] — originals and raw uploads
     * are excluded so they never appear as wardrobe items.
     */
    suspend fun listFiles(folderId: String): List<DriveFileDto> = withContext(Dispatchers.IO) {
        val tok = token()
        val q = URLEncoder.encode(
            "'$folderId' in parents and mimeType contains 'image/' and trashed=false",
            "UTF-8",
        )
        val baseUrl = "$API/files?q=$q&fields=files(id,name,appProperties,createdTime),nextPageToken&orderBy=createdTime+desc&pageSize=1000"
        fetchAllPages(baseUrl, tok).filter { ImageEncoding.isCutoutName(it.name) }
    }

    /** Uploads a JPEG file to the given Drive folder via multipart/related. */
    suspend fun uploadImage(folderId: String, imageFile: File): DriveFileDto = withContext(Dispatchers.IO) {
        val tok = token()
        val boundary = "llai_${System.currentTimeMillis()}"
        val meta = """{"name":"${imageFile.name}","parents":["$folderId"]}"""
        val body = buildMultipartRelated(boundary, meta, imageFile)
        val req = Request.Builder()
            .url("$UPLOAD_API/files?uploadType=multipart&fields=id,name")
            .header("Authorization", "Bearer $tok")
            .post(body.toRequestBody("multipart/related; boundary=$boundary".toMediaType()))
            .build()
        gson.fromJson(
            http.newCall(req).await().body!!.string(),
            DriveFileDto::class.java,
        )
    }

    /** Like [uploadImage] but uses [name] as the Drive filename instead of the local file name. */
    suspend fun uploadImageWithName(folderId: String, imageFile: File, name: String): DriveFileDto =
        withContext(Dispatchers.IO) {
            val tok = token()
            val boundary = "llai_${System.currentTimeMillis()}"
            val meta = """{"name":"$name","parents":["$folderId"]}"""
            val body = buildMultipartRelated(boundary, meta, imageFile)
            val req = Request.Builder()
                .url("$UPLOAD_API/files?uploadType=multipart&fields=id,name")
                .header("Authorization", "Bearer $tok")
                .post(body.toRequestBody("multipart/related; boundary=$boundary".toMediaType()))
                .build()
            gson.fromJson(http.newCall(req).await().body!!.string(), DriveFileDto::class.java)
        }

    /**
     * Moves [fileId] from [fromFolderId] to [toFolderId] via a single PATCH (changes parents).
     * Drive ID, content, and name are all preserved — no re-upload.
     */
    suspend fun moveFile(fileId: String, fromFolderId: String, toFolderId: String) =
        withContext(Dispatchers.IO) {
            val tok = token()
            http.newCall(
                Request.Builder()
                    .url("$API/files/$fileId?addParents=$toFolderId&removeParents=$fromFolderId&fields=id")
                    .header("Authorization", "Bearer $tok")
                    .method("PATCH", "{}".toRequestBody("application/json".toMediaType()))
                    .build()
            ).await()
        }

    /**
     * Renames a Drive file in-place (PATCH metadata only — no content re-upload, Drive ID unchanged).
     */
    suspend fun renameFile(fileId: String, newName: String) = withContext(Dispatchers.IO) {
        val tok = token()
        val body = """{"name":"$newName"}"""
        http.newCall(
            Request.Builder()
                .url("$API/files/$fileId?fields=id")
                .header("Authorization", "Bearer $tok")
                .method("PATCH", body.toRequestBody("application/json".toMediaType()))
                .build()
        ).await()
    }

    /** Replaces the content of an existing Drive file in-place (preserves ID and metadata). */
    suspend fun updateImage(fileId: String, imageFile: File) = withContext(Dispatchers.IO) {
        val tok = token()
        val req = Request.Builder()
            .url("$UPLOAD_API/files/$fileId?uploadType=media&fields=id")
            .header("Authorization", "Bearer $tok")
            .method("PATCH", imageFile.readBytes().toRequestBody("image/webp".toMediaType()))
            .build()
        http.newCall(req).await()
    }

    /** Merges [props] into the file's appProperties (PATCH — does not erase existing keys). */
    suspend fun updateAppProperties(fileId: String, props: Map<String, String>) = withContext(Dispatchers.IO) {
        val tok = token()
        val body = gson.toJson(mapOf("appProperties" to props))
        val req = Request.Builder()
            .url("$API/files/$fileId?fields=id")
            .header("Authorization", "Bearer $tok")
            .method("PATCH", body.toRequestBody("application/json".toMediaType()))
            .build()
        http.newCall(req).await()
    }

    /** Permanently deletes a file from Drive and local cache. */
    suspend fun deleteFile(fileId: String) = withContext(Dispatchers.IO) {
        val tok = token()
        val req = Request.Builder()
            .url("$API/files/$fileId")
            .header("Authorization", "Bearer $tok")
            .delete()
            .build()
        http.newCall(req).await()
        cachedFile(fileId)?.delete()
        File(cacheDir, "${fileId}_original.jpg").takeIf { it.exists() }?.delete()
    }

    /**
     * Downloads a Drive file to the local cache directory.
     * Pass [driveName] (the Drive filename) to preserve the correct extension — cutout files
     * end with [CUTOUT_SUFFIX] and are cached as `.png`; everything else as `.jpg`.
     */
    suspend fun downloadToCache(driveId: String, driveName: String = ""): File? = withContext(Dispatchers.IO) {
        val ext = if (ImageEncoding.isCutoutName(driveName)) "png" else "jpg"
        val dest = File(cacheDir, "$driveId.$ext")
        if (dest.exists()) return@withContext dest
        val tmp = File(cacheDir, "$driveId.tmp")
        return@withContext try {
            val tok = token()
            val req = Request.Builder()
                .url("$API/files/$driveId?alt=media")
                .header("Authorization", "Bearer $tok")
                .build()
            val resp = http.newCall(req).await()
            if (!resp.isSuccessful) return@withContext null
            tmp.outputStream().use { resp.body!!.byteStream().copyTo(it) }
            tmp.renameTo(dest)
            dest
        } catch (e: Exception) {
            tmp.delete()
            null
        }
    }

    /** Finds a file by exact [name] within [folderId]; null if missing. */
    internal suspend fun findFileIdByName(folderId: String, name: String, tok: String): String? {
        val q = URLEncoder.encode("'$folderId' in parents and name='$name' and trashed=false", "UTF-8")
        return gson.fromJson(
            http.newCall(Request.Builder()
                .url("$API/files?q=$q&fields=files(id)")
                .header("Authorization", "Bearer $tok")
                .build()).await().body!!.string(),
            FilesListDto::class.java,
        ).files.firstOrNull()?.id
    }

    /** Downloads the raw content of [fileId] as a string, or null on HTTP failure. */
    internal suspend fun downloadFileText(fileId: String, tok: String): String? {
        val resp = http.newCall(Request.Builder()
            .url("$API/files/$fileId?alt=media")
            .header("Authorization", "Bearer $tok")
            .build()).await()
        return if (resp.isSuccessful) resp.body?.string() else null
    }

    /**
     * Loads the saved-outfits JSON from Drive. Reads the current filename [OUTFITS_FILE_NAME];
     * falls back to the pre-rename [LEGACY_STYLES_FILE_NAME] so users who haven't re-saved yet
     * still see their outfits. Returns null if neither file exists.
     */
    suspend fun listSubfolders(parentFolderId: String): List<DriveFileDto> = withContext(Dispatchers.IO) {
        val tok = token()
        val q = URLEncoder.encode(
            "mimeType='application/vnd.google-apps.folder' and '$parentFolderId' in parents and trashed=false",
            "UTF-8",
        )
        val req = Request.Builder()
            .url("$API/files?q=$q&fields=files(id,name)&orderBy=name")
            .header("Authorization", "Bearer $tok")
            .build()
        gson.fromJson(
            http.newCall(req).await().body!!.string(),
            FilesListDto::class.java,
        ).files
    }

    /** Lists every non-trashed child (files and folders) of [folderId], with metadata for copying. */
    suspend fun listAllChildren(folderId: String): List<DriveFileDto> = withContext(Dispatchers.IO) {
        val tok = token()
        val q = URLEncoder.encode("'$folderId' in parents and trashed=false", "UTF-8")
        fetchAllPages(
            "$API/files?q=$q&fields=files(id,name,mimeType,createdTime,modifiedTime,appProperties),nextPageToken&pageSize=1000",
            tok,
        )
    }

    /**
     * Server-side copy of [fileId] into [parentFolderId] under [name]. Returns the new file ID.
     * Carries [createdTime] / [modifiedTime] / [appProperties] so the copy preserves the original's
     * dates (wardrobe sorts by createdTime) and legacy tag properties — `files.copy` otherwise stamps
     * the copy with "now" and drops appProperties.
     */
    suspend fun copyFile(
        fileId: String,
        name: String,
        parentFolderId: String,
        createdTime: String? = null,
        modifiedTime: String? = null,
        appProperties: Map<String, String>? = null,
    ): String? = withContext(Dispatchers.IO) {
        val tok = token()
        val body = buildString {
            append("{\"name\":").append(gson.toJson(name))
            append(",\"parents\":[\"").append(parentFolderId).append("\"]")
            createdTime?.let { append(",\"createdTime\":").append(gson.toJson(it)) }
            modifiedTime?.let { append(",\"modifiedTime\":").append(gson.toJson(it)) }
            appProperties?.takeIf { it.isNotEmpty() }?.let { append(",\"appProperties\":").append(gson.toJson(it)) }
            append("}")
        }
        val resp = http.newCall(
            Request.Builder()
                .url("$API/files/$fileId/copy?fields=id")
                .header("Authorization", "Bearer $tok")
                .post(body.toRequestBody("application/json".toMediaType()))
                .build(),
        ).await()
        if (!resp.isSuccessful) return@withContext null
        gson.fromJson(resp.body!!.string(), DriveFileDto::class.java).id
    }

    /** Creates a new file [name] containing [content] in [parentFolderId]. Returns the new ID. */
    suspend fun uploadTextFile(parentFolderId: String, name: String, mimeType: String, content: String): String? =
        withContext(Dispatchers.IO) {
            val tok = token()
            val meta = """{"name":${gson.toJson(name)},"parents":["$parentFolderId"],"mimeType":"$mimeType"}"""
            val createResp = http.newCall(
                Request.Builder().url("$API/files?fields=id")
                    .header("Authorization", "Bearer $tok")
                    .post(meta.toRequestBody("application/json".toMediaType()))
                    .build(),
            ).await()
            if (!createResp.isSuccessful) return@withContext null
            val id = gson.fromJson(createResp.body!!.string(), DriveFileDto::class.java).id
            http.newCall(
                Request.Builder().url("$UPLOAD_API/files/$id?uploadType=media")
                    .header("Authorization", "Bearer $tok")
                    .method("PATCH", content.toRequestBody(mimeType.toMediaType()))
                    .build(),
            ).await()
            id
        }

    /** Overwrites the content of an existing file [fileId] (PATCH media). */
    suspend fun overwriteFileText(fileId: String, mimeType: String, content: String) =
        withContext(Dispatchers.IO) {
            val tok = token()
            http.newCall(
                Request.Builder().url("$UPLOAD_API/files/$fileId?uploadType=media")
                    .header("Authorization", "Bearer $tok")
                    .method("PATCH", content.toRequestBody(mimeType.toMediaType()))
                    .build(),
            ).await()
            Unit
        }

    /** Returns the number of image files directly inside [folderId]. */
    suspend fun countImages(folderId: String): Int = withContext(Dispatchers.IO) {
        val tok = token()
        val q = URLEncoder.encode(
            "'$folderId' in parents and mimeType contains 'image/' and trashed=false",
            "UTF-8",
        )
        val req = Request.Builder()
            .url("$API/files?q=$q&fields=files(id)&pageSize=1000")
            .header("Authorization", "Bearer $tok")
            .build()
        gson.fromJson(
            http.newCall(req).await().body!!.string(),
            FilesListDto::class.java,
        ).files.size
    }

    /**
     * Downloads Drive file [fileId] directly to [dest] (bypasses the standard [cacheDir] naming).
     * Returns null on error.
     */
    suspend fun downloadFileTo(fileId: String, dest: File): File? = withContext(Dispatchers.IO) {
        val tmp = File(dest.parent, "${dest.name}.tmp")
        return@withContext try {
            val tok = token()
            val req = Request.Builder()
                .url("$API/files/$fileId?alt=media")
                .header("Authorization", "Bearer $tok")
                .build()
            val resp = http.newCall(req).await()
            if (!resp.isSuccessful) return@withContext null
            tmp.outputStream().use { resp.body!!.byteStream().copyTo(it) }
            tmp.renameTo(dest)
            dest
        } catch (e: Exception) {
            tmp.delete()
            null
        }
    }

    /**
     * Always creates a NEW top-level folder named [name] in My Drive (no name-dedupe), stamped with
     * the [APP_ROOT_PROP] marker so it's recognised as our own root on later reinstall / recovery.
     * Used as the migration target ([migrateLegacyInto]).
     */
    suspend fun createRootFolderForced(name: String): String = withContext(Dispatchers.IO) {
        createMarkedRootFolder(name, token())
    }

    /** Creates a subfolder with [name] inside [parentFolderId] and returns its Drive ID. */
    suspend fun createSubfolder(parentFolderId: String, name: String): String = withContext(Dispatchers.IO) {
        val tok = token()
        val escapedName = name.replace("\\", "\\\\").replace("'", "\\'")
        val q = URLEncoder.encode(
            "mimeType='application/vnd.google-apps.folder' and name='$escapedName' and '$parentFolderId' in parents and trashed=false",
            "UTF-8",
        )
        val existing = gson.fromJson(
            http.newCall(Request.Builder()
                .url("$API/files?q=$q&fields=files(id)")
                .header("Authorization", "Bearer $tok")
                .build()).await().body!!.string(),
            FilesListDto::class.java,
        ).files.firstOrNull()?.id
        if (existing != null) return@withContext existing

        val meta = """{"name":${gson.toJson(name)},"parents":["$parentFolderId"],"mimeType":"application/vnd.google-apps.folder"}"""
        gson.fromJson(
            http.newCall(Request.Builder()
                .url("$API/files?fields=id")
                .header("Authorization", "Bearer $tok")
                .post(meta.toRequestBody("application/json".toMediaType()))
                .build()).await().body!!.string(),
            DriveFileDto::class.java,
        ).id
    }

    /**
     * Returns the Drive folder ID of the [PROFILE_FOLDER_NAME] subfolder inside [rootFolderId],
     * creating it if it does not yet exist.
     */
    fun cachedFile(driveId: String): File? =
        sequenceOf("$driveId.png", "$driveId.jpg")
            .map { File(cacheDir, it) }
            .firstOrNull { it.exists() }

    // ---------- Helpers ----------

    /**
     * Builds a multipart/related body as required by the Drive upload API.
     * Part 1: JSON metadata, Part 2: WebP image bytes.
     */
    private fun buildMultipartRelated(boundary: String, metaJson: String, imageFile: File): ByteArray {
        val out = ByteArrayOutputStream()
        out.write(
            "--$boundary\r\nContent-Type: application/json; charset=UTF-8\r\n\r\n" +
                "$metaJson\r\n--$boundary\r\nContent-Type: image/webp\r\n\r\n",
        )
        imageFile.inputStream().use { it.copyTo(out) }
        out.write("\r\n--$boundary--")
        return out.toByteArray()
    }

    internal fun ByteArrayOutputStream.write(s: String) = write(s.toByteArray(Charsets.UTF_8))
}

// The OkHttp coroutine bridge (Call.await) moved to :core:common
// (core/common/…/data/drive/OkHttpAwait.kt), same package.
