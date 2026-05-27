package com.librelookai.data.drive

import java.io.ByteArrayOutputStream
import java.io.File
import java.net.URLEncoder
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody

internal suspend fun DriveRepository.getOrCreateProfileFolder(rootFolderId: String): String =
        createSubfolder(rootFolderId, DriveRepository.PROFILE_FOLDER_NAME)

    /**
     * Uploads [imageFile] as a JPEG into the profile subfolder of [rootFolderId] with name [name].
     * If a file with the same [name] already exists it is replaced in-place (Drive ID preserved).
     * Returns the final Drive file ID.
     */
internal suspend fun DriveRepository.uploadProfilePhoto(rootFolderId: String, name: String, imageFile: File): String =
        withContext(Dispatchers.IO) {
            val profileFolderId = getOrCreateProfileFolder(rootFolderId)
            val tok = token()
            val escapedName = name.replace("\\", "\\\\").replace("'", "\\'")
            val q = URLEncoder.encode(
                "'$profileFolderId' in parents and name='$escapedName' and trashed=false", "UTF-8",
            )
            val existingId = gson.fromJson(
                http.newCall(Request.Builder()
                    .url("$DriveRepository.API/files?q=$q&fields=files(id)")
                    .header("Authorization", "Bearer $tok")
                    .build()).await().body!!.string(),
                FilesListDto::class.java,
            ).files.firstOrNull()?.id

            if (existingId != null) {
                updateImage(existingId, imageFile)
                existingId
            } else {
                uploadImageWithName(profileFolderId, imageFile, name).id
            }
        }

    /**
     * Returns the Drive folder ID of the [DriveRepository.TRYONS_FOLDER_NAME] subfolder inside [rootFolderId],
     * creating it if needed.
     */
internal suspend fun DriveRepository.getOrCreateTryOnsFolder(rootFolderId: String): String =
        createSubfolder(rootFolderId, DriveRepository.TRYONS_FOLDER_NAME)

    /**
     * Returns the Drive folder ID of the [DriveRepository.SHOPPING_FOLDER_NAME] subfolder inside [rootFolderId],
     * creating it if needed. Items in this folder share the regular closet layout (cutout +
     * original + sidecar) so they can be moved to a real closet by file-move only.
     */
internal suspend fun DriveRepository.getOrCreateShoppingFolder(rootFolderId: String): String =
        createSubfolder(rootFolderId, DriveRepository.SHOPPING_FOLDER_NAME)

    /**
     * Returns the Drive folder ID of the [DriveRepository.TRIPS_FOLDER_NAME] subfolder inside [rootFolderId],
     * creating it if needed. Each trip is `{tripId}.json` inside this folder.
     */
internal suspend fun DriveRepository.getOrCreateTripsFolder(rootFolderId: String): String =
        createSubfolder(rootFolderId, DriveRepository.TRIPS_FOLDER_NAME)

    /** Lists trip JSON files directly inside [tripsFolderId]. Returns (driveFileId, name). */
internal suspend fun DriveRepository.listTripFiles(tripsFolderId: String): List<DriveFileDto> = withContext(Dispatchers.IO) {
        val tok = token()
        val q = URLEncoder.encode(
            "'$tripsFolderId' in parents and mimeType='application/json' and trashed=false",
            "UTF-8",
        )
        gson.fromJson(
            http.newCall(
                Request.Builder()
                    .url("$DriveRepository.API/files?q=$q&fields=files(id,name)&pageSize=1000")
                    .header("Authorization", "Bearer $tok")
                    .build(),
            ).await().body!!.string(),
            FilesListDto::class.java,
        ).files
    }

    /** Downloads a single trip's JSON by Drive file ID. */
internal suspend fun DriveRepository.loadTripJson(fileId: String): String? = withContext(Dispatchers.IO) {
        downloadFileText(fileId, token())
    }

    /**
     * Creates or overwrites `{tripId}.json` inside [tripsFolderId]. Returns the Drive file ID.
     */
internal suspend fun DriveRepository.saveTripJson(tripsFolderId: String, tripId: String, json: String): String =
        withContext(Dispatchers.IO) {
            val tok = token()
            val name = "$tripId.json"
            val existingId = findFileIdByName(tripsFolderId, name, tok)
            val fileId = existingId ?: run {
                val meta = """{"name":"$name","parents":["$tripsFolderId"],"mimeType":"application/json"}"""
                gson.fromJson(
                    http.newCall(
                        Request.Builder()
                            .url("$DriveRepository.API/files?fields=id")
                            .header("Authorization", "Bearer $tok")
                            .post(meta.toRequestBody("application/json".toMediaType()))
                            .build(),
                    ).await().body!!.string(),
                    DriveFileDto::class.java,
                ).id
            }
            http.newCall(
                Request.Builder()
                    .url("$DriveRepository.UPLOAD_API/files/$fileId?uploadType=media")
                    .header("Authorization", "Bearer $tok")
                    .method("PATCH", json.toRequestBody("application/json".toMediaType()))
                    .build(),
            ).await()
            fileId
        }

    /** Deletes a trip JSON by Drive file ID. */
internal suspend fun DriveRepository.deleteTripJson(fileId: String) = deleteFile(fileId)

    /**
     * Uploads [imageFile] (PNG) into the try-ons subfolder with [name]. Returns the new Drive ID.
     */
internal suspend fun DriveRepository.uploadTryOnImage(rootFolderId: String, name: String, imageFile: File): String =
        withContext(Dispatchers.IO) {
            val folderId = getOrCreateTryOnsFolder(rootFolderId)
            val tok = token()
            val boundary = "llai_${System.currentTimeMillis()}"
            val meta = """{"name":"$name","parents":["$folderId"],"mimeType":"image/png"}"""
            val out = ByteArrayOutputStream()
            out.write(
                "--$boundary\r\nContent-Type: application/json; charset=UTF-8\r\n\r\n" +
                    "$meta\r\n--$boundary\r\nContent-Type: image/png\r\n\r\n",
            )
            imageFile.inputStream().use { it.copyTo(out) }
            out.write("\r\n--$boundary--")
            val req = Request.Builder()
                .url("$DriveRepository.UPLOAD_API/files?uploadType=multipart&fields=id,name")
                .header("Authorization", "Bearer $tok")
                .post(out.toByteArray().toRequestBody("multipart/related; boundary=$boundary".toMediaType()))
                .build()
            gson.fromJson(http.newCall(req).await().body!!.string(), DriveFileDto::class.java).id
        }

    /** Loads the try-ons index JSON from the root Drive folder, or null if not yet created. */
internal suspend fun DriveRepository.loadTryOnsJson(rootFolderId: String): String? = withContext(Dispatchers.IO) {
        val tok = token()
        val id = findFileIdByName(rootFolderId, DriveRepository.TRYONS_FILE_NAME, tok) ?: return@withContext null
        downloadFileText(id, tok)
    }

    /** Creates or overwrites the try-ons index JSON in the root Drive folder. */
internal suspend fun DriveRepository.saveTryOnsJson(rootFolderId: String, json: String) = withContext(Dispatchers.IO) {
        val tok = token()
        val existingId = findFileIdByName(rootFolderId, DriveRepository.TRYONS_FILE_NAME, tok)
        val fileId = existingId ?: run {
            val meta = """{"name":"$DriveRepository.TRYONS_FILE_NAME","parents":["$rootFolderId"],"mimeType":"application/json"}"""
            gson.fromJson(
                http.newCall(Request.Builder()
                    .url("$DriveRepository.API/files?fields=id")
                    .header("Authorization", "Bearer $tok")
                    .post(meta.toRequestBody("application/json".toMediaType()))
                    .build()).await().body!!.string(),
                DriveFileDto::class.java,
            ).id
        }
        http.newCall(Request.Builder()
            .url("$DriveRepository.UPLOAD_API/files/$fileId?uploadType=media")
            .header("Authorization", "Bearer $tok")
            .method("PATCH", json.toRequestBody("application/json".toMediaType()))
            .build()).await()
    }

    /** Returns the locally cached file for a Drive ID, or null if not yet downloaded. */
