package com.librelookai

import android.graphics.Bitmap
import android.graphics.BitmapFactory
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

        private const val BG_MODEL = "gemini-3.1-flash-image-preview"
        private const val BG_URL =
            "https://generativelanguage.googleapis.com/v1beta/models/$BG_MODEL:generateContent"
        private const val BG_PROMPT =
            "Extract the clothing item from the background. Place the clothing item on a pure, " +
                "solid neon green background (Hex #00FF00). " +
                "Macro product photography, studio lighting, no text, no UI elements. " +
                "Do not add any shadows, gradients, or checkerboard patterns. " +
                "Do not include any phones, apps, text, or website interfaces."

        private const val CLASSIFY_MODEL = "gemini-3-flash-preview"
        private const val CLASSIFY_URL =
            "https://generativelanguage.googleapis.com/v1beta/models/$CLASSIFY_MODEL:generateContent"

        // Text-only model for style prediction (reuses classify endpoint)
        private const val PREDICT_URL = CLASSIFY_URL
        private const val CLASSIFY_PROMPT =
            "Analyze this clothing item and return ONLY a JSON object (no markdown, no explanation) " +
                "with these fields: " +
                "\"type\" (specific item name, e.g. \"T-shirt\", \"Chinos\", \"Puffer jacket\"), " +
                "\"category\" (one of: tops, bottoms, outerwear, footwear, accessories, dress, suit), " +
                "\"uses\" (array from: casual, formal, business, sport, outdoor, beach, evening), " +
                "\"colors\" (array of main colors as lowercase English words), " +
                "\"seasonality\" (array from: spring, summer, fall, winter), " +
                "\"aesthetic\" (array from: minimalist, streetwear, preppy, bohemian, classic, sporty, romantic, edgy, business-casual, luxury), " +
                "\"fit\" (array from: slim, regular, relaxed, oversized, tailored), " +
                "\"material\" (array of detected/inferred materials, e.g. cotton, denim, wool, leather, polyester, linen, silk, knit), " +
                "\"pattern\" (array from: solid, stripes, plaid, floral, geometric, animal-print, graphic, camo, abstract). " +
                "Use empty arrays for fields that cannot be determined."
    }

    /**
     * Sends [imageFile] to Gemini and returns a PNG with the background removed.
     * Returns null on any failure — callers should fall back to the original.
     */
    suspend fun removeBackground(imageFile: File, outputDir: File): File? =
        withContext(Dispatchers.IO) {
            val apiKey = BuildConfig.GEMINI_API_KEY
            if (apiKey.isBlank() || apiKey == "YOUR_GEMINI_API_KEY_HERE") {
                Log.w(TAG, "API key not set — skipping background removal")
                return@withContext null
            }

            Log.d(TAG, "Sending ${imageFile.length() / 1024}KB image to Gemini ($BG_MODEL)")

            val imageBase64 = Base64.encodeToString(imageFile.readBytes(), Base64.NO_WRAP)

            val body = gson.toJson(
                mapOf(
                    "contents" to listOf(
                        mapOf(
                            "role" to "user",
                            "parts" to listOf(
                                mapOf("text" to BG_PROMPT),
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
                .url("$BG_URL?key=$apiKey")
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

                val rawFile = File(outputDir, "${imageFile.nameWithoutExtension}_cutout_raw.png")
                rawFile.writeBytes(Base64.decode(imagePart.inlineData!!.data!!, Base64.NO_WRAP))
                val outFile = File(outputDir, "${imageFile.nameWithoutExtension}_cutout.png")
                removeGreenScreen(rawFile, outFile)
                rawFile.delete()
                Log.d(TAG, "Background removed — saved ${outFile.length() / 1024}KB PNG")
                outFile
            } catch (e: Exception) {
                Log.e(TAG, "Exception during Gemini call: ${e.message}", e)
                null
            }
        }

    /**
     * Classifies the clothing item in [imageFile] using Gemini vision.
     * Returns [ClothingTags] or null on failure.
     */
    suspend fun classifyClothing(imageFile: File): ClothingTags? =
        withContext(Dispatchers.IO) {
            val apiKey = BuildConfig.GEMINI_API_KEY
            if (apiKey.isBlank() || apiKey == "YOUR_GEMINI_API_KEY_HERE") return@withContext null

            Log.d(TAG, "Classifying clothing in ${imageFile.name} via $CLASSIFY_MODEL")
            val mimeType = if (imageFile.extension == "png") "image/png" else "image/jpeg"
            val imageBase64 = Base64.encodeToString(imageFile.readBytes(), Base64.NO_WRAP)

            val body = gson.toJson(
                mapOf(
                    "contents" to listOf(
                        mapOf(
                            "role" to "user",
                            "parts" to listOf(
                                mapOf("text" to CLASSIFY_PROMPT),
                                mapOf(
                                    "inline_data" to mapOf(
                                        "mime_type" to mimeType,
                                        "data" to imageBase64,
                                    ),
                                ),
                            ),
                        ),
                    ),
                ),
            )

            val request = Request.Builder()
                .url("$CLASSIFY_URL?key=$apiKey")
                .post(body.toRequestBody("application/json".toMediaType()))
                .build()

            return@withContext try {
                val response = http.newCall(request).await()
                val responseBody = response.body!!.string()
                Log.d(TAG, "Classify HTTP ${response.code}: ${responseBody.take(500)}")
                if (!response.isSuccessful) return@withContext null

                val parsed = gson.fromJson(responseBody, GeminiResponse::class.java)
                val text = parsed.candidates
                    ?.firstOrNull()?.content?.parts
                    ?.firstOrNull { it.text != null }?.text
                    ?: run { Log.w(TAG, "No text part in classify response"); return@withContext null }

                val json = text.trim()
                    .removePrefix("```json").removePrefix("```").removeSuffix("```").trim()
                gson.fromJson(json, ClothingTags::class.java)
                    .also { Log.d(TAG, "Tags: $it") }
            } catch (e: Exception) {
                Log.e(TAG, "Classification failed: ${e.message}", e)
                null
            }
        }

    /**
     * Uses Gemini with Google Search grounding to fetch current street-fashion trends for [region].
     * Returns null on failure so callers can proceed without trend data.
     */
    suspend fun searchFashionTrends(region: String): FashionTrends? = withContext(Dispatchers.IO) {
        val apiKey = BuildConfig.GEMINI_API_KEY
        if (apiKey.isBlank() || apiKey == "YOUR_GEMINI_API_KEY_HERE") return@withContext null

        val prompt = """
            Search the web for the current street fashion and clothing trends happening right now in $region.
            Cover all of the following clothing categories: tops, bottoms, outerwear, footwear, accessories, dresses, suits.
            For each category note what is trending and what is outdated where relevant.
            Synthesize the search results and return ONLY a valid JSON object — no markdown, no explanation:
            {"region":"$region","trending_colors":["color1","color2"],"trending_aesthetics":["aesthetic1","aesthetic2"],"must_have_items":["category: item description","..."],"outdated_items":["category: item description","..."]}
        """.trimIndent()

        val body = gson.toJson(
            mapOf(
                "contents" to listOf(
                    mapOf("role" to "user", "parts" to listOf(mapOf("text" to prompt))),
                ),
                "tools" to listOf(mapOf("google_search" to emptyMap<String, Any>())),
            ),
        )
        return@withContext try {
            val response = http.newCall(
                Request.Builder()
                    .url("$PREDICT_URL?key=$apiKey")
                    .post(body.toRequestBody("application/json".toMediaType()))
                    .build(),
            ).await()
            val responseBody = response.body!!.string()
            Log.d(TAG, "searchFashionTrends HTTP ${response.code}: ${responseBody.take(500)}")
            if (!response.isSuccessful) return@withContext null

            val parsed = gson.fromJson(responseBody, GeminiResponse::class.java)
            val text = parsed.candidates
                ?.firstOrNull()?.content?.parts
                ?.firstOrNull { it.text != null }?.text
                ?: return@withContext null

            val json = text.trim()
                .removePrefix("```json").removePrefix("```").removeSuffix("```").trim()
            gson.fromJson(json, FashionTrends::class.java)
                .also { Log.d(TAG, "FashionTrends for $region: $it") }
        } catch (e: Exception) {
            Log.w(TAG, "searchFashionTrends failed (non-fatal): ${e.message}")
            null
        }
    }

    /**
     * Searches Google Custom Search for product images matching [query].
     *
     * Requires `google.cse.id` in local.properties. The CSE must be configured to
     * "Search the entire web" with Image search enabled. Uses the same gemini.api.key.
     * Create one at https://programmablesearchengine.google.com/
     *
     * Returns a Pair of (thumbnail URL list, human-readable debug string).
     */
    suspend fun searchProductImages(query: String): Pair<List<String>, String> = withContext(Dispatchers.IO) {
        val apiKey = BuildConfig.GEMINI_API_KEY
        val cseId  = BuildConfig.GOOGLE_CSE_ID
        if (apiKey.isBlank() || cseId.isBlank()) {
            return@withContext emptyList<String>() to "CSE not configured"
        }

        val encodedQuery = java.net.URLEncoder.encode(query, "UTF-8")
        val url = "https://www.googleapis.com/customsearch/v1" +
            "?key=$apiKey&cx=$cseId&q=$encodedQuery&searchType=image&num=4" +
            "&safe=active&imgType=photo"

        Log.d(TAG, "searchProductImages query=\"$query\" cx=$cseId")

        return@withContext try {
            val response = http.newCall(Request.Builder().url(url).build()).await()
            val body = response.body?.string() ?: return@withContext emptyList<String>() to "empty body"
            Log.d(TAG, "searchProductImages HTTP ${response.code} — full body:\n$body")

            if (!response.isSuccessful) {
                return@withContext emptyList<String>() to "HTTP ${response.code}: $body"
            }

            val root = gson.fromJson(body, CseResponse::class.java)
            val items = root.items.orEmpty()
            Log.d(TAG, "searchProductImages: ${items.size} items")
            items.forEachIndexed { i, item ->
                Log.d(TAG, "  [$i] link=${item.link} | thumb=${item.image?.thumbnailLink}")
            }

            val thumbs = items.mapNotNull { it.image?.thumbnailLink }.filter { it.isNotBlank() }
            thumbs to "Query: \"$query\" • ${items.size} items, ${thumbs.size} thumbs"
        } catch (e: Exception) {
            Log.w(TAG, "searchProductImages failed: ${e.message}", e)
            emptyList<String>() to "Exception: ${e.message}"
        }
    }

    /**
     * Sends a text-only prompt to Gemini and returns the raw text response, or null on failure.
     */
    suspend fun generateText(prompt: String): String? = withContext(Dispatchers.IO) {
        val apiKey = BuildConfig.GEMINI_API_KEY
        if (apiKey.isBlank() || apiKey == "YOUR_GEMINI_API_KEY_HERE") return@withContext null

        val body = gson.toJson(
            mapOf(
                "contents" to listOf(
                    mapOf(
                        "role" to "user",
                        "parts" to listOf(mapOf("text" to prompt)),
                    ),
                ),
            ),
        )
        return@withContext try {
            val response = http.newCall(
                Request.Builder()
                    .url("$PREDICT_URL?key=$apiKey")
                    .post(body.toRequestBody("application/json".toMediaType()))
                    .build(),
            ).await()
            val responseBody = response.body!!.string()
            Log.d(TAG, "generateText HTTP ${response.code}: ${responseBody.take(500)}")
            if (!response.isSuccessful) return@withContext null

            val parsed = gson.fromJson(responseBody, GeminiResponse::class.java)
            parsed.candidates
                ?.firstOrNull()?.content?.parts
                ?.firstOrNull { it.text != null }?.text
        } catch (e: Exception) {
            Log.e(TAG, "generateText failed: ${e.message}", e)
            null
        }
    }

    // ---------- Image processing ----------

    private fun removeGreenScreen(inputFile: File, outputFile: File) {
    val bmp = BitmapFactory.decodeFile(inputFile.absolutePath)
        ?: run { inputFile.copyTo(outputFile, overwrite = true); return }
    val mutable = bmp.copy(Bitmap.Config.ARGB_8888, true)
    val w = mutable.width
    val h = mutable.height
    val pixels = IntArray(w * h)
    mutable.getPixels(pixels, 0, w, 0, 0, w, h)
    for (i in pixels.indices) {
        val c = pixels[i]
        val r = (c shr 16) and 0xFF
        val g = (c shr 8) and 0xFF
        val b = c and 0xFF
        // Match neon green (#00FF00) with tolerance for JPEG compression artifacts
        if (r < 80 && g > 180 && b < 80) pixels[i] = 0
    }
    mutable.setPixels(pixels, 0, w, 0, 0, w, h)
    outputFile.outputStream().use { mutable.compress(Bitmap.CompressFormat.PNG, 100, it) }
    Log.d(TAG, "Green screen removed: ${pixels.count { it == 0 }} transparent pixels")
    }
}

// ---------- Public data classes ----------

data class FashionTrends(
    val region: String = "",
    @com.google.gson.annotations.SerializedName("trending_colors")
    val trendingColors: List<String> = emptyList(),
    @com.google.gson.annotations.SerializedName("trending_aesthetics")
    val trendingAesthetics: List<String> = emptyList(),
    @com.google.gson.annotations.SerializedName("must_have_items")
    val mustHaveItems: List<String> = emptyList(),
    @com.google.gson.annotations.SerializedName("outdated_items")
    val outdatedItems: List<String> = emptyList(),
)

data class ClothingTags(
    val type: String = "",
    val category: String = "",
    val uses: List<String> = emptyList(),
    val colors: List<String> = emptyList(),
    val seasonality: List<String> = emptyList(),
    val aesthetic: List<String> = emptyList(),
    val fit: List<String> = emptyList(),
    val material: List<String> = emptyList(),
    val pattern: List<String> = emptyList(),
)

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
    @SerializedName("inlineData") val inlineData: GeminiInlineData? = null,
)

private data class GeminiInlineData(
    @SerializedName("mimeType") val mimeType: String? = null,
    val data: String? = null,
)

// ---------- Google Custom Search API DTOs ----------

private data class CseResponse(
    val items: List<CseItem>? = null,
)

private data class CseItem(
    val link: String? = null,
    val image: CseImage? = null,
)

private data class CseImage(
    val thumbnailLink: String? = null,
    val contextLink: String? = null,
)
