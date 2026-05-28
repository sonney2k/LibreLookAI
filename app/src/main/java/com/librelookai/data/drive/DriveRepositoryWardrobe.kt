package com.librelookai.data.drive

import java.net.URLEncoder
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody

internal suspend fun DriveRepository.listAllImageFiles(folderId: String): List<DriveFileDto> = withContext(Dispatchers.IO) {
        val tok = token()
        val q = URLEncoder.encode(
            "'$folderId' in parents and mimeType contains 'image/' and trashed=false", "UTF-8",
        )
        fetchAllPages("${DriveRepository.API}/files?q=$q&fields=files(id,name),nextPageToken&pageSize=1000", tok)
    }

    /** Lists per-item sidecar JSON files in [folderId], excluding system metadata files. */
internal suspend fun DriveRepository.listSidecarFiles(folderId: String): List<DriveFileDto> = withContext(Dispatchers.IO) {
        val tok = token()
        val q = URLEncoder.encode(
            "'$folderId' in parents and mimeType='application/json' and trashed=false",
            "UTF-8",
        )
        fetchAllPages("${DriveRepository.API}/files?q=$q&fields=files(id,name,size),nextPageToken&pageSize=1000", tok)
            .filter { it.name !in DriveRepository.SYSTEM_JSON_NAMES }
    }

    /** Downloads and returns the text content of Drive file [fileId], or null on failure. */
internal suspend fun DriveRepository.loadFileContent(fileId: String): String? = withContext(Dispatchers.IO) {
        val tok = token()
        val resp = http.newCall(
            Request.Builder()
                .url("${DriveRepository.API}/files/$fileId?alt=media")
                .header("Authorization", "Bearer $tok")
                .build()
        ).await()
        if (resp.isSuccessful) resp.body?.string() else null
    }

    /**
     * Creates or updates a JSON sidecar file named [name] in [folderId] with content [json].
     * Returns the Drive file ID of the sidecar.
     */
internal suspend fun DriveRepository.upsertSidecar(folderId: String, name: String, json: String): String =
        withContext(Dispatchers.IO) {
            val tok = token()
            val escapedName = name.replace("\\", "\\\\").replace("'", "\\'")
            val q = URLEncoder.encode(
                "'$folderId' in parents and name='$escapedName' and trashed=false",
                "UTF-8",
            )
            val queryResp = http.newCall(Request.Builder()
                .url("${DriveRepository.API}/files?q=$q&fields=files(id)")
                .header("Authorization", "Bearer $tok")
                .build()).await()
            val queryBody = queryResp.body?.string().orEmpty()
            if (!queryResp.isSuccessful) error("upsertSidecar query failed ${queryResp.code}: $queryBody")
            val existingId = gson.fromJson(queryBody, FilesListDto::class.java).files.firstOrNull()?.id

            val fileId = existingId ?: run {
                val meta = """{"name":${gson.toJson(name)},"parents":["$folderId"],"mimeType":"application/json"}"""
                val createResp = http.newCall(Request.Builder()
                    .url("${DriveRepository.API}/files?fields=id")
                    .header("Authorization", "Bearer $tok")
                    .post(meta.toRequestBody("application/json".toMediaType()))
                    .build()).await()
                val createBody = createResp.body?.string().orEmpty()
                if (!createResp.isSuccessful) error("upsertSidecar create failed ${createResp.code}: $createBody")
                gson.fromJson(createBody, DriveFileDto::class.java).id
                    .ifEmpty { error("upsertSidecar create returned empty id: $createBody") }
            }
            val patchResp = http.newCall(Request.Builder()
                .url("${DriveRepository.UPLOAD_API}/files/$fileId?uploadType=media")
                .header("Authorization", "Bearer $tok")
                .method("PATCH", json.toRequestBody("application/json".toMediaType()))
                .build()).await()
            if (!patchResp.isSuccessful) {
                val body = patchResp.body?.string().orEmpty()
                patchResp.close()
                error("upsertSidecar PATCH failed ${patchResp.code}: $body")
            }
            patchResp.close()
            fileId
        }

    /** Lists direct subfolders of [parentFolderId]. Pass "root" for My Drive root. */
