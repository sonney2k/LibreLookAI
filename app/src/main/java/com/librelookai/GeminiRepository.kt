package com.librelookai

import android.util.Base64
import android.util.Log
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
        .connectTimeout(30, TimeUnit.SECONDS)
        .writeTimeout(60, TimeUnit.SECONDS)  // uploading the image bytes
        .readTimeout(120, TimeUnit.SECONDS)  // waiting for Gemini to process
        .callTimeout(300, TimeUnit.SECONDS)  // hard ceiling for the whole call
        .build()
    private val gson = Gson()

    companion object {
        private const val TAG = "GeminiRepository"
        private const val MODEL = "gemini-3.1-flash-image-preview"
        private const val API_URL =
            "https://generativelanguage.googleapis.com/v1beta/models/$MODEL:generateContent"
        private const val PROMPT =
            "Remove the background from this clothing item. Keep only the garment " +
                "and return it as a high-quality cutout with a transparent background."
    }

    /**
     * Sends [imageFile] to Gemini and returns a PNG with the background removed.
     * Returns null on any failure — callers should fall back to the original.
     * All failure reasons are logged to Logcat under tag "GeminiRepository".
     */
    suspend fun removeBackground(imageFile: File, outputDir: File): File? =
        withContext(Dispatchers.IO) {
            val apiKey = BuildConfig.GEMINI_API_KEY
            if (apiKey.isBlank() || apiKey == "YOUR_GEMINI_API_KEY_HERE") {
                Log.w(TAG, "API key not set — skipping background removal")
                return@withContext null
            }

            Log.d(TAG, "Sending ${imageFile.length() / 1024}KB image to Gemini ($MODEL)")

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
                val responseBody = response.body!!.string()

                Log.d(TAG, "HTTP ${response.code}")
                // Log full body (may be large; truncate for readability)
                Log.d(TAG, "Response: ${responseBody.take(2000)}")

                if (!response.isSuccessful) {
                    Log.e(TAG, "Non-2xx response — falling back to original")
                    return@withContext null
                }

                val parsed = gson.fromJson(responseBody, GeminiResponse::class.java)
                val imagePart = parsed.candidates
                    ?.firstOrNull()
                    ?.content
                    ?.parts
                    ?.firstOrNull { it.inlineData?.mimeType?.startsWith("image/") == true }

                if (imagePart == null) {
                    val textParts = parsed.candidates
                        ?.firstOrNull()?.content?.parts
                        ?.mapNotNull { it.text }?.joinToString(" ")
                    Log.w(TAG, "No image part in response. Text parts: $textParts")
                    Log.w(TAG, "Finish reason: ${parsed.candidates?.firstOrNull()?.finishReason}")
                    return@withContext null
                }

                val outFile = File(outputDir, "${imageFile.nameWithoutExtension}_cutout.png")
                outFile.writeBytes(Base64.decode(imagePart.inlineData!!.data!!, Base64.NO_WRAP))
                Log.d(TAG, "Background removed — saved ${outFile.length() / 1024}KB PNG")
                outFile
            } catch (e: Exception) {
                Log.e(TAG, "Exception during Gemini call: ${e.message}", e)
                null
            }
        }
}

// ---------- Response DTOs ----------

private data class GeminiResponse(
    val candidates: List<GeminiCandidate>? = null,
    val error: GeminiError? = null,
)

private data class GeminiError(
    val code: Int? = null,
    val message: String? = null,
    val status: String? = null,
)

private data class GeminiCandidate(
    val content: GeminiContent? = null,
    val finishReason: String? = null,
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
