package com.librelookai

import android.content.Context
import com.google.gson.Gson
import com.google.gson.annotations.SerializedName
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import okhttp3.Call
import okhttp3.Callback
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.IOException
import java.net.URLEncoder
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

// ---------- DTOs ----------

data class DriveFileDto(
    val id: String = "",
    val name: String = "",
    val appProperties: Map<String, String>? = null,
    /** Raw Drive API size field (returned as a string); -1 if not requested or unavailable. */
    private val size: String? = null,
) {
    val sizeBytes: Long get() = size?.toLongOrNull() ?: -1L
}

private data class FilesListDto(
    val files: List<DriveFileDto> = emptyList(),
)

// ---------- Repository ----------

class DriveRepository(
    context: Context,
    private val auth: GoogleAuthManager,
) {
    companion object {
        private const val API = "https://www.googleapis.com/drive/v3"
        private const val UPLOAD_API = "https://www.googleapis.com/upload/drive/v3"
        private const val FOLDER_NAME = "LibreLookAI"
        private const val STYLES_FILE_NAME = "_styles_metadata.json"
        private const val OUTFITS_FILE_NAME = "_outfits_metadata.json"
        private const val PREFERENCES_FILE_NAME = "_user_preferences.json"
        private const val WARDROBE_METADATA_FILE_NAME = "_wardrobe_metadata.json"
        private const val LOCATIONS_FILE_NAME = "_locations.json"

        /**
         * Suffix that marks a file as the background-removed cutout variant.
         * Only files with this suffix are treated as wardrobe items; originals and
         * raw uploads are excluded from [listFiles] and therefore never shown in the app.
         */
        const val CUTOUT_SUFFIX = "_cutout.png"

        /** Suffix used when archiving the unprocessed original on Drive. */
        const val ORIGINAL_SUFFIX = "_original.jpg"

        /** Suffix for per-item metadata sidecar files (named "{cutoutDriveId}.json"). */
        const val SIDECAR_SUFFIX = ".json"

        /** System-level JSON files that must never be treated as item sidecars. */
        internal val SYSTEM_JSON_NAMES = setOf(
            WARDROBE_METADATA_FILE_NAME,
            STYLES_FILE_NAME,
            OUTFITS_FILE_NAME,
            PREFERENCES_FILE_NAME,
            LOCATIONS_FILE_NAME,
        )
    }

    private val http = OkHttpClient()
    private val gson = Gson()

    /** App-private persistent cache for wardrobe images. */
    val cacheDir: File = File(context.filesDir, "wardrobe").also { it.mkdirs() }

    private suspend fun token() = auth.getAccessToken()

    /** Returns the Drive folder ID, creating the folder if it doesn't exist. */
    suspend fun getOrCreateFolder(): String = withContext(Dispatchers.IO) {
        val tok = token()
        val q = URLEncoder.encode(
            "mimeType='application/vnd.google-apps.folder' and name='$FOLDER_NAME' and trashed=false",
            "UTF-8",
        )
        val listReq = Request.Builder()
            .url("$API/files?q=$q&fields=files(id)")
            .header("Authorization", "Bearer $tok")
            .build()
        val listed = gson.fromJson(
            http.newCall(listReq).await().body!!.string(),
            FilesListDto::class.java,
        )
        if (listed.files.isNotEmpty()) return@withContext listed.files[0].id

        val meta = """{"name":"$FOLDER_NAME","mimeType":"application/vnd.google-apps.folder"}"""
        val createReq = Request.Builder()
            .url("$API/files?fields=id")
            .header("Authorization", "Bearer $tok")
            .post(meta.toRequestBody("application/json".toMediaType()))
            .build()
        gson.fromJson(
            http.newCall(createReq).await().body!!.string(),
            DriveFileDto::class.java,
        ).id
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
        val req = Request.Builder()
            .url("$API/files?q=$q&fields=files(id,name,appProperties)&orderBy=createdTime+desc")
            .header("Authorization", "Bearer $tok")
            .build()
        gson.fromJson(
            http.newCall(req).await().body!!.string(),
            FilesListDto::class.java,
        ).files.filter { it.name.endsWith(CUTOUT_SUFFIX) }
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
        val mimeType = if (imageFile.extension == "png") "image/png" else "image/jpeg"
        val req = Request.Builder()
            .url("$UPLOAD_API/files/$fileId?uploadType=media&fields=id")
            .header("Authorization", "Bearer $tok")
            .method("PATCH", imageFile.readBytes().toRequestBody(mimeType.toMediaType()))
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
        val ext = if (driveName.endsWith(CUTOUT_SUFFIX)) "png" else "jpg"
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

    /** Loads the styles JSON string from Drive, or null if not yet created. */
    suspend fun loadStylesJson(folderId: String): String? = withContext(Dispatchers.IO) {
        val tok = token()
        val q = URLEncoder.encode(
            "'$folderId' in parents and name='$STYLES_FILE_NAME' and trashed=false", "UTF-8",
        )
        val fileId = gson.fromJson(
            http.newCall(Request.Builder()
                .url("$API/files?q=$q&fields=files(id)")
                .header("Authorization", "Bearer $tok")
                .build()).await().body!!.string(),
            FilesListDto::class.java,
        ).files.firstOrNull()?.id ?: return@withContext null

        val resp = http.newCall(Request.Builder()
            .url("$API/files/$fileId?alt=media")
            .header("Authorization", "Bearer $tok")
            .build()).await()
        if (resp.isSuccessful) resp.body?.string() else null
    }

    /** Creates or overwrites the styles JSON file in Drive. */
    suspend fun saveStylesJson(folderId: String, json: String) = withContext(Dispatchers.IO) {
        val tok = token()
        val q = URLEncoder.encode(
            "'$folderId' in parents and name='$STYLES_FILE_NAME' and trashed=false", "UTF-8",
        )
        val existingId = gson.fromJson(
            http.newCall(Request.Builder()
                .url("$API/files?q=$q&fields=files(id)")
                .header("Authorization", "Bearer $tok")
                .build()).await().body!!.string(),
            FilesListDto::class.java,
        ).files.firstOrNull()?.id

        val fileId = existingId ?: run {
            val meta = """{"name":"$STYLES_FILE_NAME","parents":["$folderId"],"mimeType":"application/json"}"""
            gson.fromJson(
                http.newCall(Request.Builder()
                    .url("$API/files?fields=id")
                    .header("Authorization", "Bearer $tok")
                    .post(meta.toRequestBody("application/json".toMediaType()))
                    .build()).await().body!!.string(),
                DriveFileDto::class.java,
            ).id
        }
        http.newCall(Request.Builder()
            .url("$UPLOAD_API/files/$fileId?uploadType=media")
            .header("Authorization", "Bearer $tok")
            .method("PATCH", json.toRequestBody("application/json".toMediaType()))
            .build()).await()
    }

    /** Loads the outfits JSON string from Drive, or null if not yet created. */
    suspend fun loadOutfitsJson(folderId: String): String? = withContext(Dispatchers.IO) {
        val tok = token()
        val q = URLEncoder.encode(
            "'$folderId' in parents and name='$OUTFITS_FILE_NAME' and trashed=false", "UTF-8",
        )
        val fileId = gson.fromJson(
            http.newCall(Request.Builder()
                .url("$API/files?q=$q&fields=files(id)")
                .header("Authorization", "Bearer $tok")
                .build()).await().body!!.string(),
            FilesListDto::class.java,
        ).files.firstOrNull()?.id ?: return@withContext null

        val resp = http.newCall(Request.Builder()
            .url("$API/files/$fileId?alt=media")
            .header("Authorization", "Bearer $tok")
            .build()).await()
        if (resp.isSuccessful) resp.body?.string() else null
    }

    /** Creates or overwrites the outfits JSON file in Drive. */
    suspend fun saveOutfitsJson(folderId: String, json: String) = withContext(Dispatchers.IO) {
        val tok = token()
        val q = URLEncoder.encode(
            "'$folderId' in parents and name='$OUTFITS_FILE_NAME' and trashed=false", "UTF-8",
        )
        val existingId = gson.fromJson(
            http.newCall(Request.Builder()
                .url("$API/files?q=$q&fields=files(id)")
                .header("Authorization", "Bearer $tok")
                .build()).await().body!!.string(),
            FilesListDto::class.java,
        ).files.firstOrNull()?.id

        val fileId = existingId ?: run {
            val meta = """{"name":"$OUTFITS_FILE_NAME","parents":["$folderId"],"mimeType":"application/json"}"""
            gson.fromJson(
                http.newCall(Request.Builder()
                    .url("$API/files?fields=id")
                    .header("Authorization", "Bearer $tok")
                    .post(meta.toRequestBody("application/json".toMediaType()))
                    .build()).await().body!!.string(),
                DriveFileDto::class.java,
            ).id
        }
        http.newCall(Request.Builder()
            .url("$UPLOAD_API/files/$fileId?uploadType=media")
            .header("Authorization", "Bearer $tok")
            .method("PATCH", json.toRequestBody("application/json".toMediaType()))
            .build()).await()
    }

    /** Loads the user preferences JSON from Drive, or null if not yet saved. */
    suspend fun loadPreferencesJson(folderId: String): String? = withContext(Dispatchers.IO) {
        val tok = token()
        val q = URLEncoder.encode(
            "'$folderId' in parents and name='$PREFERENCES_FILE_NAME' and trashed=false", "UTF-8",
        )
        val fileId = gson.fromJson(
            http.newCall(Request.Builder()
                .url("$API/files?q=$q&fields=files(id)")
                .header("Authorization", "Bearer $tok")
                .build()).await().body!!.string(),
            FilesListDto::class.java,
        ).files.firstOrNull()?.id ?: return@withContext null

        val resp = http.newCall(Request.Builder()
            .url("$API/files/$fileId?alt=media")
            .header("Authorization", "Bearer $tok")
            .build()).await()
        if (resp.isSuccessful) resp.body?.string() else null
    }

    /** Creates or overwrites the user preferences JSON file in Drive. */
    suspend fun savePreferencesJson(folderId: String, json: String) = withContext(Dispatchers.IO) {
        val tok = token()
        val q = URLEncoder.encode(
            "'$folderId' in parents and name='$PREFERENCES_FILE_NAME' and trashed=false", "UTF-8",
        )
        val existingId = gson.fromJson(
            http.newCall(Request.Builder()
                .url("$API/files?q=$q&fields=files(id)")
                .header("Authorization", "Bearer $tok")
                .build()).await().body!!.string(),
            FilesListDto::class.java,
        ).files.firstOrNull()?.id

        val fileId = existingId ?: run {
            val meta = """{"name":"$PREFERENCES_FILE_NAME","parents":["$folderId"],"mimeType":"application/json"}"""
            gson.fromJson(
                http.newCall(Request.Builder()
                    .url("$API/files?fields=id")
                    .header("Authorization", "Bearer $tok")
                    .post(meta.toRequestBody("application/json".toMediaType()))
                    .build()).await().body!!.string(),
                DriveFileDto::class.java,
            ).id
        }
        http.newCall(Request.Builder()
            .url("$UPLOAD_API/files/$fileId?uploadType=media")
            .header("Authorization", "Bearer $tok")
            .method("PATCH", json.toRequestBody("application/json".toMediaType()))
            .build()).await()
    }

    /** Loads the wardrobe metadata JSON string from Drive, or null if not yet created. */
    suspend fun loadWardrobeMetadataJson(folderId: String): String? = withContext(Dispatchers.IO) {
        val tok = token()
        val q = URLEncoder.encode(
            "'$folderId' in parents and name='$WARDROBE_METADATA_FILE_NAME' and trashed=false", "UTF-8",
        )
        val fileId = gson.fromJson(
            http.newCall(Request.Builder()
                .url("$API/files?q=$q&fields=files(id)")
                .header("Authorization", "Bearer $tok")
                .build()).await().body!!.string(),
            FilesListDto::class.java,
        ).files.firstOrNull()?.id ?: return@withContext null

        val resp = http.newCall(Request.Builder()
            .url("$API/files/$fileId?alt=media")
            .header("Authorization", "Bearer $tok")
            .build()).await()
        if (resp.isSuccessful) resp.body?.string() else null
    }

    /** Creates or overwrites the wardrobe metadata JSON file in Drive. */
    suspend fun saveWardrobeMetadataJson(folderId: String, json: String) = withContext(Dispatchers.IO) {
        val tok = token()
        val q = URLEncoder.encode(
            "'$folderId' in parents and name='$WARDROBE_METADATA_FILE_NAME' and trashed=false", "UTF-8",
        )
        val existingId = gson.fromJson(
            http.newCall(Request.Builder()
                .url("$API/files?q=$q&fields=files(id)")
                .header("Authorization", "Bearer $tok")
                .build()).await().body!!.string(),
            FilesListDto::class.java,
        ).files.firstOrNull()?.id

        val fileId = existingId ?: run {
            val meta = """{"name":"$WARDROBE_METADATA_FILE_NAME","parents":["$folderId"],"mimeType":"application/json"}"""
            gson.fromJson(
                http.newCall(Request.Builder()
                    .url("$API/files?fields=id")
                    .header("Authorization", "Bearer $tok")
                    .post(meta.toRequestBody("application/json".toMediaType()))
                    .build()).await().body!!.string(),
                DriveFileDto::class.java,
            ).id
        }
        http.newCall(Request.Builder()
            .url("$UPLOAD_API/files/$fileId?uploadType=media")
            .header("Authorization", "Bearer $tok")
            .method("PATCH", json.toRequestBody("application/json".toMediaType()))
            .build()).await()
    }

    /** Loads the locations JSON string from the root Drive folder, or null if not yet created. */
    suspend fun loadLocationsJson(rootFolderId: String): String? = withContext(Dispatchers.IO) {
        val tok = token()
        val q = URLEncoder.encode(
            "'$rootFolderId' in parents and name='$LOCATIONS_FILE_NAME' and trashed=false", "UTF-8",
        )
        val fileId = gson.fromJson(
            http.newCall(Request.Builder()
                .url("$API/files?q=$q&fields=files(id)")
                .header("Authorization", "Bearer $tok")
                .build()).await().body!!.string(),
            FilesListDto::class.java,
        ).files.firstOrNull()?.id ?: return@withContext null

        val resp = http.newCall(Request.Builder()
            .url("$API/files/$fileId?alt=media")
            .header("Authorization", "Bearer $tok")
            .build()).await()
        if (resp.isSuccessful) resp.body?.string() else null
    }

    /** Creates or overwrites the locations JSON file in the root Drive folder. */
    suspend fun saveLocationsJson(rootFolderId: String, json: String) = withContext(Dispatchers.IO) {
        val tok = token()
        val q = URLEncoder.encode(
            "'$rootFolderId' in parents and name='$LOCATIONS_FILE_NAME' and trashed=false", "UTF-8",
        )
        val existingId = gson.fromJson(
            http.newCall(Request.Builder()
                .url("$API/files?q=$q&fields=files(id)")
                .header("Authorization", "Bearer $tok")
                .build()).await().body!!.string(),
            FilesListDto::class.java,
        ).files.firstOrNull()?.id

        val fileId = existingId ?: run {
            val meta = """{"name":"$LOCATIONS_FILE_NAME","parents":["$rootFolderId"],"mimeType":"application/json"}"""
            gson.fromJson(
                http.newCall(Request.Builder()
                    .url("$API/files?fields=id")
                    .header("Authorization", "Bearer $tok")
                    .post(meta.toRequestBody("application/json".toMediaType()))
                    .build()).await().body!!.string(),
                DriveFileDto::class.java,
            ).id
        }
        http.newCall(Request.Builder()
            .url("$UPLOAD_API/files/$fileId?uploadType=media")
            .header("Authorization", "Bearer $tok")
            .method("PATCH", json.toRequestBody("application/json".toMediaType()))
            .build()).await()
    }

    /**
     * Lists ALL image files in [folderId] (originals, cutouts, and raw uploads).
     * Used by the repair-and-sync audit to examine every image regardless of suffix.
     */
    suspend fun listAllImageFiles(folderId: String): List<DriveFileDto> = withContext(Dispatchers.IO) {
        val tok = token()
        val q = URLEncoder.encode(
            "'$folderId' in parents and mimeType contains 'image/' and trashed=false", "UTF-8",
        )
        val req = Request.Builder()
            .url("$API/files?q=$q&fields=files(id,name)&pageSize=1000")
            .header("Authorization", "Bearer $tok")
            .build()
        gson.fromJson(
            http.newCall(req).await().body!!.string(),
            FilesListDto::class.java,
        ).files
    }

    /** Lists per-item sidecar JSON files in [folderId], excluding system metadata files. */
    suspend fun listSidecarFiles(folderId: String): List<DriveFileDto> = withContext(Dispatchers.IO) {
        val tok = token()
        val q = URLEncoder.encode(
            "'$folderId' in parents and mimeType='application/json' and trashed=false",
            "UTF-8",
        )
        val req = Request.Builder()
            .url("$API/files?q=$q&fields=files(id,name,size)&pageSize=1000")
            .header("Authorization", "Bearer $tok")
            .build()
        gson.fromJson(
            http.newCall(req).await().body!!.string(),
            FilesListDto::class.java,
        ).files.filter { it.name !in SYSTEM_JSON_NAMES }
    }

    /** Downloads and returns the text content of Drive file [fileId], or null on failure. */
    suspend fun loadFileContent(fileId: String): String? = withContext(Dispatchers.IO) {
        val tok = token()
        val resp = http.newCall(
            Request.Builder()
                .url("$API/files/$fileId?alt=media")
                .header("Authorization", "Bearer $tok")
                .build()
        ).await()
        if (resp.isSuccessful) resp.body?.string() else null
    }

    /**
     * Creates or updates a JSON sidecar file named [name] in [folderId] with content [json].
     * Returns the Drive file ID of the sidecar.
     */
    suspend fun upsertSidecar(folderId: String, name: String, json: String): String =
        withContext(Dispatchers.IO) {
            val tok = token()
            val escapedName = name.replace("\\", "\\\\").replace("'", "\\'")
            val q = URLEncoder.encode(
                "'$folderId' in parents and name='$escapedName' and trashed=false",
                "UTF-8",
            )
            val existingId = gson.fromJson(
                http.newCall(Request.Builder()
                    .url("$API/files?q=$q&fields=files(id)")
                    .header("Authorization", "Bearer $tok")
                    .build()).await().body!!.string(),
                FilesListDto::class.java,
            ).files.firstOrNull()?.id

            val fileId = existingId ?: run {
                val meta = """{"name":${gson.toJson(name)},"parents":["$folderId"],"mimeType":"application/json"}"""
                gson.fromJson(
                    http.newCall(Request.Builder()
                        .url("$API/files?fields=id")
                        .header("Authorization", "Bearer $tok")
                        .post(meta.toRequestBody("application/json".toMediaType()))
                        .build()).await().body!!.string(),
                    DriveFileDto::class.java,
                ).id
            }
            http.newCall(Request.Builder()
                .url("$UPLOAD_API/files/$fileId?uploadType=media")
                .header("Authorization", "Bearer $tok")
                .method("PATCH", json.toRequestBody("application/json".toMediaType()))
                .build()).await()
            fileId
        }

    /** Lists direct subfolders of [parentFolderId]. Pass "root" for My Drive root. */
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

    /** Returns the locally cached file for a Drive ID, or null if not yet downloaded. */
    fun cachedFile(driveId: String): File? =
        sequenceOf("$driveId.png", "$driveId.jpg")
            .map { File(cacheDir, it) }
            .firstOrNull { it.exists() }

    // ---------- Helpers ----------

    /**
     * Builds a multipart/related body as required by the Drive upload API.
     * Part 1: JSON metadata, Part 2: JPEG image bytes.
     */
    private fun buildMultipartRelated(boundary: String, metaJson: String, imageFile: File): ByteArray {
        val out = ByteArrayOutputStream()
        out.write(
            "--$boundary\r\nContent-Type: application/json; charset=UTF-8\r\n\r\n" +
                "$metaJson\r\n--$boundary\r\nContent-Type: image/jpeg\r\n\r\n",
        )
        imageFile.inputStream().use { it.copyTo(out) }
        out.write("\r\n--$boundary--")
        return out.toByteArray()
    }

    private fun ByteArrayOutputStream.write(s: String) = write(s.toByteArray(Charsets.UTF_8))
}

// ---------- OkHttp coroutine bridge ----------

suspend fun Call.await(): Response = suspendCancellableCoroutine { cont ->
    enqueue(object : Callback {
        override fun onResponse(call: Call, response: Response) = cont.resume(response)
        override fun onFailure(call: Call, e: IOException) = cont.resumeWithException(e)
    })
    cont.invokeOnCancellation { cancel() }
}
