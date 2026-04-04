package com.librelookai

import android.util.Base64
import com.google.gson.Gson
import com.google.gson.annotations.SerializedName
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.io.File
import java.util.concurrent.TimeUnit

class GeminiRepository {

    private val http = OkHttpClient.Builder()
        .callTimeout(120, TimeUnit.SECONDS) // image generation can be slow
        .build()
    private val gson = Gson()

    companion object {
        private const val MODEL = "gemini-2.0-flash-preview-image-generation"
        private const val API_URL =
            "https://generativelanguage.googleapis.com/v1beta/models/$MODEL:generateContent"
        private const val PROMPT =
            "Remove the background from this clothing item. Keep only the garment " +
                "and return it as a high-quality cutout with a transparent background."
    }

    /**
     * Sends [imageFile] to Gemini and returns a PNG with the background removed.
     * Returns null (silently) if the API key is missing, the call fails, or the
     * response contains no image — callers should fall back to the original.
     */
    suspend fun removeBackground(imageFile: File, outputDir: File): File? =
        withContext(Dispatchers.IO) {
            val apiKey = BuildConfig.GEMINI_API_KEY
            if (apiKey.isBlank() || apiKey == "YOUR_GEMINI_API_KEY_HERE") return@withContext null

            val imageBase64 = Base64.encodeToString(imageFile.readBytes(), Base64.NO_WRAP)

            val body = gson.toJson(
                mapOf(
                    "contents" to listOf(
                        mapOf(
                            "role" to "user",
                            "parts" to listOf(
                                mapOf("text" to PROMPT),
                                mapOf(
                                    "inline_data" to mapOf(
                                        "mime_type" to "image/jpeg",
                                        "data" to imageBase64,
                                    ),
                                ),
                            ),
                        ),
                    ),
                    "generationConfig" to mapOf(
                        "responseModalities" to listOf("IMAGE", "TEXT"),
                    ),
                ),
            )

            val request = Request.Builder()
                .url("$API_URL?key=$apiKey")
                .post(body.toRequestBody("application/json".toMediaType()))
                .build()

            return@withContext try {
                val response = http.newCall(request).await()
                if (!response.isSuccessful) return@withContext null

                val parsed = gson.fromJson(response.body!!.string(), GeminiResponse::class.java)
                val imageData = parsed.candidates
                    ?.firstOrNull()
                    ?.content
                    ?.parts
                    ?.firstOrNull { it.inlineData?.mimeType?.startsWith("image/") == true }
                    ?.inlineData
                    ?.data
                    ?: return@withContext null

                val outFile = File(outputDir, "${imageFile.nameWithoutExtension}_cutout.png")
                outFile.writeBytes(Base64.decode(imageData, Base64.NO_WRAP))
                outFile
            } catch (e: Exception) {
                null
            }
        }
}

// ---------- Response DTOs ----------

private data class GeminiResponse(
    val candidates: List<GeminiCandidate>? = null,
)

private data class GeminiCandidate(
    val content: GeminiContent? = null,
)

private data class GeminiContent(
    val parts: List<GeminiPart>? = null,
)

private data class GeminiPart(
    val text: String? = null,
    @SerializedName("inline_data") val inlineData: GeminiInlineData? = null,
)

private data class GeminiInlineData(
    @SerializedName("mime_type") val mimeType: String? = null,
    val data: String? = null,
)
