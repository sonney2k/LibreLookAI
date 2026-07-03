package com.librelookai.data.drive

import java.net.URLEncoder
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody

/** Drive file name of the token-usage JSONL log. Defined drive-side so the sync layer never
 *  imports from `gemini/` (the AI layer references this via `TokenUsageRepository.DRIVE_FILE_NAME`). */
const val TOKEN_USAGE_DRIVE_FILE_NAME = "_token_usage.jsonl"

internal suspend fun DriveRepository.loadOutfitsJsonImpl(folderId: String): String? = withContext(Dispatchers.IO) {
        val tok = token()
        val id = findFileIdByName(folderId, DriveRepository.OUTFITS_FILE_NAME, tok)
            ?: findFileIdByName(folderId, DriveRepository.LEGACY_STYLES_FILE_NAME, tok)
            ?: return@withContext null
        downloadFileText(id, tok)
    }

    /** Creates or overwrites the saved-outfits JSON file in Drive (always writes [DriveRepository.OUTFITS_FILE_NAME]). */
suspend fun DriveRepository.saveOutfitsJson(folderId: String, json: String) = withContext(Dispatchers.IO) {
        val tok = token()
        val existingId = findFileIdByName(folderId, DriveRepository.OUTFITS_FILE_NAME, tok)
        val fileId = existingId ?: run {
            val meta = """{"name":"${DriveRepository.OUTFITS_FILE_NAME}","parents":["$folderId"],"mimeType":"application/json"}"""
            gson.fromJson(
                http.newCall(Request.Builder()
                    .url("${DriveRepository.API}/files?fields=id")
                    .header("Authorization", "Bearer $tok")
                    .post(meta.toRequestBody("application/json".toMediaType()))
                    .build()).await().body!!.string(),
                DriveFileDto::class.java,
            ).id
        }
        http.newCall(Request.Builder()
            .url("${DriveRepository.UPLOAD_API}/files/$fileId?uploadType=media")
            .header("Authorization", "Bearer $tok")
            .method("PATCH", json.toRequestBody("application/json".toMediaType()))
            .build()).await()
    }

    /**
     * Loads the outfit-events (calendar wear history) JSON from Drive. Falls back to
     * [DriveRepository.LEGACY_OUTFIT_EVENTS_FILE_NAME] if the current filename isn't present yet.
     */
suspend fun DriveRepository.loadOutfitEventsJson(folderId: String): String? = withContext(Dispatchers.IO) {
        val tok = token()
        val id = findFileIdByName(folderId, DriveRepository.OUTFIT_EVENTS_FILE_NAME, tok)
            ?: findFileIdByName(folderId, DriveRepository.LEGACY_OUTFIT_EVENTS_FILE_NAME, tok)
            ?: return@withContext null
        downloadFileText(id, tok)
    }

    /** Creates or overwrites the outfit-events JSON in Drive (always writes [DriveRepository.OUTFIT_EVENTS_FILE_NAME]). */
suspend fun DriveRepository.saveOutfitEventsJson(folderId: String, json: String) = withContext(Dispatchers.IO) {
        val tok = token()
        val existingId = findFileIdByName(folderId, DriveRepository.OUTFIT_EVENTS_FILE_NAME, tok)
        val fileId = existingId ?: run {
            val meta = """{"name":"${DriveRepository.OUTFIT_EVENTS_FILE_NAME}","parents":["$folderId"],"mimeType":"application/json"}"""
            gson.fromJson(
                http.newCall(Request.Builder()
                    .url("${DriveRepository.API}/files?fields=id")
                    .header("Authorization", "Bearer $tok")
                    .post(meta.toRequestBody("application/json".toMediaType()))
                    .build()).await().body!!.string(),
                DriveFileDto::class.java,
            ).id
        }
        http.newCall(Request.Builder()
            .url("${DriveRepository.UPLOAD_API}/files/$fileId?uploadType=media")
            .header("Authorization", "Bearer $tok")
            .method("PATCH", json.toRequestBody("application/json".toMediaType()))
            .build()).await()
    }

    /** Loads the user preferences JSON from Drive, or null if not yet saved. */
suspend fun DriveRepository.loadPreferencesJson(folderId: String): String? = withContext(Dispatchers.IO) {
        val tok = token()
        val q = URLEncoder.encode(
            "'$folderId' in parents and name='${DriveRepository.PREFERENCES_FILE_NAME}' and trashed=false", "UTF-8",
        )
        val fileId = gson.fromJson(
            http.newCall(Request.Builder()
                .url("${DriveRepository.API}/files?q=$q&fields=files(id)")
                .header("Authorization", "Bearer $tok")
                .build()).await().body!!.string(),
            FilesListDto::class.java,
        ).files.firstOrNull()?.id ?: return@withContext null

        val resp = http.newCall(Request.Builder()
            .url("${DriveRepository.API}/files/$fileId?alt=media")
            .header("Authorization", "Bearer $tok")
            .build()).await()
        if (resp.isSuccessful) resp.body?.string() else null
    }

    /** Creates or overwrites the user preferences JSON file in Drive. */
suspend fun DriveRepository.savePreferencesJson(folderId: String, json: String) = withContext(Dispatchers.IO) {
        val tok = token()
        val q = URLEncoder.encode(
            "'$folderId' in parents and name='${DriveRepository.PREFERENCES_FILE_NAME}' and trashed=false", "UTF-8",
        )
        val existingId = gson.fromJson(
            http.newCall(Request.Builder()
                .url("${DriveRepository.API}/files?q=$q&fields=files(id)")
                .header("Authorization", "Bearer $tok")
                .build()).await().body!!.string(),
            FilesListDto::class.java,
        ).files.firstOrNull()?.id

        val fileId = existingId ?: run {
            val meta = """{"name":"${DriveRepository.PREFERENCES_FILE_NAME}","parents":["$folderId"],"mimeType":"application/json"}"""
            gson.fromJson(
                http.newCall(Request.Builder()
                    .url("${DriveRepository.API}/files?fields=id")
                    .header("Authorization", "Bearer $tok")
                    .post(meta.toRequestBody("application/json".toMediaType()))
                    .build()).await().body!!.string(),
                DriveFileDto::class.java,
            ).id
        }
        http.newCall(Request.Builder()
            .url("${DriveRepository.UPLOAD_API}/files/$fileId?uploadType=media")
            .header("Authorization", "Bearer $tok")
            .method("PATCH", json.toRequestBody("application/json".toMediaType()))
            .build()).await()
    }

    /** Loads the token-usage JSONL log from Drive (returns null if no file exists yet). */
suspend fun DriveRepository.loadTokenUsageJsonl(folderId: String): String? = withContext(Dispatchers.IO) {
        val tok = token()
        val q = URLEncoder.encode(
            "'$folderId' in parents and name='$TOKEN_USAGE_DRIVE_FILE_NAME' and trashed=false", "UTF-8",
        )
        val fileId = gson.fromJson(
            http.newCall(Request.Builder()
                .url("${DriveRepository.API}/files?q=$q&fields=files(id)")
                .header("Authorization", "Bearer $tok")
                .build()).await().body!!.string(),
            FilesListDto::class.java,
        ).files.firstOrNull()?.id ?: return@withContext null

        val resp = http.newCall(Request.Builder()
            .url("${DriveRepository.API}/files/$fileId?alt=media")
            .header("Authorization", "Bearer $tok")
            .build()).await()
        if (resp.isSuccessful) resp.body?.string() else null
    }

    /** Creates or overwrites the token-usage JSONL file in Drive. */
suspend fun DriveRepository.saveTokenUsageJsonl(folderId: String, content: String) = withContext(Dispatchers.IO) {
        val tok = token()
        val name = TOKEN_USAGE_DRIVE_FILE_NAME
        val q = URLEncoder.encode(
            "'$folderId' in parents and name='$name' and trashed=false", "UTF-8",
        )
        val existingId = gson.fromJson(
            http.newCall(Request.Builder()
                .url("${DriveRepository.API}/files?q=$q&fields=files(id)")
                .header("Authorization", "Bearer $tok")
                .build()).await().body!!.string(),
            FilesListDto::class.java,
        ).files.firstOrNull()?.id

        val fileId = existingId ?: run {
            val meta = """{"name":"$name","parents":["$folderId"],"mimeType":"application/x-ndjson"}"""
            gson.fromJson(
                http.newCall(Request.Builder()
                    .url("${DriveRepository.API}/files?fields=id")
                    .header("Authorization", "Bearer $tok")
                    .post(meta.toRequestBody("application/json".toMediaType()))
                    .build()).await().body!!.string(),
                DriveFileDto::class.java,
            ).id
        }
        http.newCall(Request.Builder()
            .url("${DriveRepository.UPLOAD_API}/files/$fileId?uploadType=media")
            .header("Authorization", "Bearer $tok")
            .method("PATCH", content.toRequestBody("application/x-ndjson".toMediaType()))
            .build()).await()
    }

    /** Loads the wardrobe metadata JSON string from Drive, or null if not yet created. */
internal suspend fun DriveRepository.loadWardrobeMetadataJsonImpl(folderId: String): String? = withContext(Dispatchers.IO) {
        val tok = token()
        val q = URLEncoder.encode(
            "'$folderId' in parents and name='${DriveRepository.WARDROBE_METADATA_FILE_NAME}' and trashed=false", "UTF-8",
        )
        val fileId = gson.fromJson(
            http.newCall(Request.Builder()
                .url("${DriveRepository.API}/files?q=$q&fields=files(id)")
                .header("Authorization", "Bearer $tok")
                .build()).await().body!!.string(),
            FilesListDto::class.java,
        ).files.firstOrNull()?.id ?: return@withContext null

        val resp = http.newCall(Request.Builder()
            .url("${DriveRepository.API}/files/$fileId?alt=media")
            .header("Authorization", "Bearer $tok")
            .build()).await()
        if (resp.isSuccessful) resp.body?.string() else null
    }

    /** Creates or overwrites the wardrobe metadata JSON file in Drive. */
suspend fun DriveRepository.saveWardrobeMetadataJson(folderId: String, json: String) = withContext(Dispatchers.IO) {
        val tok = token()
        val q = URLEncoder.encode(
            "'$folderId' in parents and name='${DriveRepository.WARDROBE_METADATA_FILE_NAME}' and trashed=false", "UTF-8",
        )
        val existingId = gson.fromJson(
            http.newCall(Request.Builder()
                .url("${DriveRepository.API}/files?q=$q&fields=files(id)")
                .header("Authorization", "Bearer $tok")
                .build()).await().body!!.string(),
            FilesListDto::class.java,
        ).files.firstOrNull()?.id

        val fileId = existingId ?: run {
            val meta = """{"name":"${DriveRepository.WARDROBE_METADATA_FILE_NAME}","parents":["$folderId"],"mimeType":"application/json"}"""
            gson.fromJson(
                http.newCall(Request.Builder()
                    .url("${DriveRepository.API}/files?fields=id")
                    .header("Authorization", "Bearer $tok")
                    .post(meta.toRequestBody("application/json".toMediaType()))
                    .build()).await().body!!.string(),
                DriveFileDto::class.java,
            ).id
        }
        http.newCall(Request.Builder()
            .url("${DriveRepository.UPLOAD_API}/files/$fileId?uploadType=media")
            .header("Authorization", "Bearer $tok")
            .method("PATCH", json.toRequestBody("application/json".toMediaType()))
            .build()).await()
    }

    /** Loads the locations JSON string from the root Drive folder, or null if not yet created. */
suspend fun DriveRepository.loadLocationsJson(rootFolderId: String): String? = withContext(Dispatchers.IO) {
        val tok = token()
        val q = URLEncoder.encode(
            "'$rootFolderId' in parents and name='${DriveRepository.LOCATIONS_FILE_NAME}' and trashed=false", "UTF-8",
        )
        val fileId = gson.fromJson(
            http.newCall(Request.Builder()
                .url("${DriveRepository.API}/files?q=$q&fields=files(id)")
                .header("Authorization", "Bearer $tok")
                .build()).await().body!!.string(),
            FilesListDto::class.java,
        ).files.firstOrNull()?.id ?: return@withContext null

        val resp = http.newCall(Request.Builder()
            .url("${DriveRepository.API}/files/$fileId?alt=media")
            .header("Authorization", "Bearer $tok")
            .build()).await()
        if (resp.isSuccessful) resp.body?.string() else null
    }

    /** Creates or overwrites the locations JSON file in the root Drive folder. */
suspend fun DriveRepository.saveLocationsJson(rootFolderId: String, json: String) = withContext(Dispatchers.IO) {
        val tok = token()
        val q = URLEncoder.encode(
            "'$rootFolderId' in parents and name='${DriveRepository.LOCATIONS_FILE_NAME}' and trashed=false", "UTF-8",
        )
        val existingId = gson.fromJson(
            http.newCall(Request.Builder()
                .url("${DriveRepository.API}/files?q=$q&fields=files(id)")
                .header("Authorization", "Bearer $tok")
                .build()).await().body!!.string(),
            FilesListDto::class.java,
        ).files.firstOrNull()?.id

        val fileId = existingId ?: run {
            val meta = """{"name":"${DriveRepository.LOCATIONS_FILE_NAME}","parents":["$rootFolderId"],"mimeType":"application/json"}"""
            gson.fromJson(
                http.newCall(Request.Builder()
                    .url("${DriveRepository.API}/files?fields=id")
                    .header("Authorization", "Bearer $tok")
                    .post(meta.toRequestBody("application/json".toMediaType()))
                    .build()).await().body!!.string(),
                DriveFileDto::class.java,
            ).id
        }
        http.newCall(Request.Builder()
            .url("${DriveRepository.UPLOAD_API}/files/$fileId?uploadType=media")
            .header("Authorization", "Bearer $tok")
            .method("PATCH", json.toRequestBody("application/json".toMediaType()))
            .build()).await()
    }

    /** Reads the region-keyed fashion-trends cache JSON from the root Drive folder, or null if absent. */
suspend fun DriveRepository.loadTrendsCacheJson(rootFolderId: String): String? = withContext(Dispatchers.IO) {
        val tok = token()
        val q = URLEncoder.encode(
            "'$rootFolderId' in parents and name='${DriveRepository.TRENDS_CACHE_FILE_NAME}' and trashed=false", "UTF-8",
        )
        val fileId = gson.fromJson(
            http.newCall(Request.Builder()
                .url("${DriveRepository.API}/files?q=$q&fields=files(id)")
                .header("Authorization", "Bearer $tok")
                .build()).await().body!!.string(),
            FilesListDto::class.java,
        ).files.firstOrNull()?.id ?: return@withContext null

        val resp = http.newCall(Request.Builder()
            .url("${DriveRepository.API}/files/$fileId?alt=media")
            .header("Authorization", "Bearer $tok")
            .build()).await()
        if (resp.isSuccessful) resp.body?.string() else null
    }

    /** Creates or overwrites the fashion-trends cache JSON in the root Drive folder. */
suspend fun DriveRepository.saveTrendsCacheJson(rootFolderId: String, json: String) = withContext(Dispatchers.IO) {
        val tok = token()
        val q = URLEncoder.encode(
            "'$rootFolderId' in parents and name='${DriveRepository.TRENDS_CACHE_FILE_NAME}' and trashed=false", "UTF-8",
        )
        val existingId = gson.fromJson(
            http.newCall(Request.Builder()
                .url("${DriveRepository.API}/files?q=$q&fields=files(id)")
                .header("Authorization", "Bearer $tok")
                .build()).await().body!!.string(),
            FilesListDto::class.java,
        ).files.firstOrNull()?.id

        val fileId = existingId ?: run {
            val meta = """{"name":"${DriveRepository.TRENDS_CACHE_FILE_NAME}","parents":["$rootFolderId"],"mimeType":"application/json"}"""
            gson.fromJson(
                http.newCall(Request.Builder()
                    .url("${DriveRepository.API}/files?fields=id")
                    .header("Authorization", "Bearer $tok")
                    .post(meta.toRequestBody("application/json".toMediaType()))
                    .build()).await().body!!.string(),
                DriveFileDto::class.java,
            ).id
        }
        http.newCall(Request.Builder()
            .url("${DriveRepository.UPLOAD_API}/files/$fileId?uploadType=media")
            .header("Authorization", "Bearer $tok")
            .method("PATCH", json.toRequestBody("application/json".toMediaType()))
            .build()).await()
    }

    /**
     * Lists ALL image files in [folderId] (originals, cutouts, and raw uploads).
     * Used by the repair-and-sync audit to examine every image regardless of suffix.
     */
