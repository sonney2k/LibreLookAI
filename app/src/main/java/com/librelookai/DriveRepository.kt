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
)

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

    /** Lists image files in the given Drive folder, newest first. */
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
        ).files
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

    /** Downloads a Drive file to the local cache directory. Returns null on error. */
    suspend fun downloadToCache(driveId: String): File? = withContext(Dispatchers.IO) {
        val dest = File(cacheDir, "$driveId.jpg")
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
