package com.librelookai

import android.app.Application
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.util.Base64
import android.util.Log
import com.google.firebase.FirebaseApp
import com.google.firebase.auth.FirebaseAuth
import com.google.gson.Gson
import com.google.gson.annotations.SerializedName
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.tasks.await
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.io.ByteArrayOutputStream
import java.io.File
import java.util.concurrent.TimeUnit
import kotlin.math.roundToInt

/** Identifies the AI action sent to the proxy for credit-cost accounting. */
private enum class GeminiAction(val header: String) {
    REMOVE_BACKGROUND("removeBackground"),
    CLASSIFY_CLOTHING("classifyClothing"),
    GENERATE_TEXT("generateText"),
    SEARCH_TRENDS("searchFashionTrends"),
    TRY_ON_OUTFIT("tryOnOutfit"),
}

class GeminiRepository(private val app: Application) {

    private val http = OkHttpClient.Builder()
        .connectTimeout(30, TimeUnit.SECONDS)
        .writeTimeout(60, TimeUnit.SECONDS)  // uploading the image bytes
        .readTimeout(120, TimeUnit.SECONDS)  // waiting for Gemini to process
        .callTimeout(300, TimeUnit.SECONDS)  // hard ceiling for the whole call
        .build()
    private val gson = Gson()
    private val usage = TokenUsageRepository.get(app)

    /** Pulls usageMetadata out of a parsed Gemini response and records one event. */
    private fun recordUsage(parsed: GeminiResponse, model: String, category: UsageCategory) {
        val meta = parsed.usageMetadata ?: return
        usage.record(
            category = category,
            model = model,
            inputTokens = meta.promptTokenCount ?: 0,
            outputTokens = meta.candidatesTokenCount ?: 0,
        )
    }

    companion object {
        private const val TAG = "GeminiRepository"

        private const val BG_MODEL = "gemini-3.1-flash-image-preview"
        private const val BG_URL =
            "https://generativelanguage.googleapis.com/v1beta/models/$BG_MODEL:generateContent"

        private const val CLASSIFY_MODEL = "gemini-3-flash-preview"
        private const val CLASSIFY_URL =
            "https://generativelanguage.googleapis.com/v1beta/models/$CLASSIFY_MODEL:generateContent"

        // Text-only model for style prediction (reuses classify endpoint)
        private const val PREDICT_URL = CLASSIFY_URL
    }

    /** Returns the active API key: user-supplied key takes precedence over the build-time key. */
    private fun resolveApiKey(): String {
        val userKey = ApiKeyStore.get(app)
        if (userKey.isNotBlank()) return userKey
        return BuildConfig.GEMINI_API_KEY
    }

    private fun isProxyMode(): Boolean =
        resolveApiKey().isBlank() && BuildConfig.PROXY_BASE_URL.isNotBlank()

    private suspend fun getFirebaseIdToken(): String? = try {
        if (FirebaseApp.getApps(app).isEmpty()) null
        else FirebaseAuth.getInstance().currentUser?.getIdToken(false)?.await()?.token
    } catch (e: Exception) { null }

    /**
     * Builds the OkHttp [Request] for a Gemini call.
     * BYOK mode: attaches the API key as a query parameter to the direct Gemini URL.
     * Proxy mode: routes to the Firebase Cloud Function with auth + action headers.
     */
    private suspend fun buildRequest(
        directUrl: String,
        model: String,
        body: String,
        action: GeminiAction,
    ): Request {
        val localKey = resolveApiKey()
        if (localKey.isNotBlank()) {
            return Request.Builder()
                .url("$directUrl?key=$localKey")
                .post(body.toRequestBody("application/json".toMediaType()))
                .build()
        }
        val proxyBase = BuildConfig.PROXY_BASE_URL
        if (proxyBase.isBlank()) error("No API key or proxy URL configured")
        val token = getFirebaseIdToken() ?: error("Firebase not signed in — cannot use managed mode")
        return Request.Builder()
            .url("$proxyBase/geminiProxy")
            .post(body.toRequestBody("application/json".toMediaType()))
            .addHeader("Authorization", "Bearer $token")
            .addHeader("X-AI-Action", action.header)
            .addHeader("X-Gemini-Model", model)
            .build()
    }

    /** Returns false when neither BYOK key nor proxy is configured. */
    private fun isConfigured(): Boolean =
        resolveApiKey().isNotBlank() || BuildConfig.PROXY_BASE_URL.isNotBlank()

    /**
     * Read [imageFile], downscale so max(width, height) ≤ 1280 if needed,
     * and return the result as Base64. The [format] determines the compression
     * used when re-encoding is necessary (JPEG for photos, PNG for cutouts).
     */
    private fun readAndResizeBase64(imageFile: File, format: Bitmap.CompressFormat): String {
        val opts = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(imageFile.absolutePath, opts)
        val maxDim = maxOf(opts.outWidth, opts.outHeight)
        if (maxDim <= 1280) {
            // Already small enough — skip decode/re-encode
            return Base64.encodeToString(imageFile.readBytes(), Base64.NO_WRAP)
        }
        val bitmap = BitmapFactory.decodeFile(imageFile.absolutePath)
            ?: return Base64.encodeToString(imageFile.readBytes(), Base64.NO_WRAP)
        val scale = 1280f / maxDim
        val newW = (bitmap.width * scale).roundToInt()
        val newH = (bitmap.height * scale).roundToInt()
        val resized = Bitmap.createScaledBitmap(bitmap, newW, newH, true)
        val quality = if (format == Bitmap.CompressFormat.PNG) 100 else 95
        val baos = ByteArrayOutputStream()
        resized.compress(format, quality, baos)
        Log.d(TAG, "Resized ${opts.outWidth}x${opts.outHeight} → ${newW}x${newH} for Gemini")
        return Base64.encodeToString(baos.toByteArray(), Base64.NO_WRAP)
    }

    /**
     * Sends [imageFile] to Gemini and returns a PNG with the background removed.
     * Returns null on any failure — callers should fall back to the original.
     */
    suspend fun removeBackground(imageFile: File, outputDir: File): File? =
        withContext(Dispatchers.IO) {
            if (!isConfigured()) {
                Log.w(TAG, "API key not set — skipping background removal")
                return@withContext null
            }

            Log.d(TAG, "Sending ${imageFile.length() / 1024}KB image to Gemini ($BG_MODEL)")

            val imageBase64 = readAndResizeBase64(imageFile, Bitmap.CompressFormat.JPEG)

            val body = gson.toJson(
                mapOf(
                    "contents" to listOf(
                        mapOf(
                            "role" to "user",
                            "parts" to listOf(
                                mapOf("text" to PromptStore.get(app, PromptKey.BG_REMOVAL)),
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

            val request = buildRequest(BG_URL, BG_MODEL, body, GeminiAction.REMOVE_BACKGROUND)

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
                recordUsage(parsed, BG_MODEL, UsageCategory.BG_REMOVAL)
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
     * Sends the user's person photos in [personFiles] together with the clothing items in
     * [itemFiles] to the Gemini image-generation model and returns a single composited image
     * that shows the person wearing those items. Returns null on any failure.
     *
     * [outputDir] is used as the destination for the generated PNG.
     * [preferences] is optional free-text the user has set in their profile; it is woven
     * into the prompt so that generations respect style context (e.g. gender, vibe).
     */
    suspend fun tryOnOutfit(
        personFiles: List<File>,
        itemFiles: List<File>,
        outputDir: File,
        preferences: String = "",
    ): File? = withContext(Dispatchers.IO) {
        if (!isConfigured()) {
            Log.w(TAG, "API key not set — skipping try-on")
            return@withContext null
        }
        if (personFiles.isEmpty() || itemFiles.isEmpty()) return@withContext null

        val prefsHint = preferences.trim().takeIf { it.isNotEmpty() }
            ?.let { " The person's style preferences: $it." } ?: ""
        val personCountHint = when (personFiles.size) {
            1 -> "a single frontal reference photo of the person"
            2 -> "two reference photos of the person — the FIRST is the frontal photo (authoritative for the face), the second is an additional angle"
            else -> "${personFiles.size} reference photos of the person — the FIRST is the frontal photo (authoritative for the face), the rest are additional angles"
        }
        val itemCountHint = if (itemFiles.size == 1) "this clothing item" else "these ${itemFiles.size} clothing items"
        val prompt = """
            You are given $personCountHint, followed by $itemCountHint.
            Generate a single photorealistic full-body image of the same person wearing $itemCountHint layered realistically as an outfit.

            CRITICAL GARMENT INSTRUCTION: The clothing items must remain completely distinct and separate. Do NOT merge, blend, or fuse different items into a single hybrid garment (e.g., do not fuse an outerwear zipper or jacket with an underlying t-shirt). Maintain distinct layers, physical boundaries, and textures for each individual piece. Dress the person so all provided garments are visible and layered logically.

            CRITICAL IDENTITY INSTRUCTION: The generated face must be as close as possible to the face in the FIRST (frontal) reference photo — match facial structure, features, proportions, eye shape and color, nose, mouth, jawline, eyebrows, skin tone, and hair exactly. Treat the frontal photo as the ground truth for identity; the other photos are only for recovering body shape and side/back details. Do not invent, beautify, age, or restyle the face. Preserve hair, skin tone, and body proportions exactly.

            Use a clean, neutral studio background, soft even lighting, and sharp focus.
            No text, watermarks, UI, extra people, or extra clothing that was not provided. $prefsHint
        """.trimIndent()




        val parts = mutableListOf<Map<String, Any>>(mapOf("text" to prompt))
        personFiles.forEach { f ->
            val mime = if (f.extension.equals("png", ignoreCase = true)) "image/png" else "image/jpeg"
            val fmt = if (f.extension.equals("png", ignoreCase = true)) Bitmap.CompressFormat.PNG else Bitmap.CompressFormat.JPEG
            parts += mapOf(
                "inline_data" to mapOf(
                    "mime_type" to mime,
                    "data" to readAndResizeBase64(f, fmt),
                ),
            )
        }
        itemFiles.forEach { f ->
            val mime = if (f.extension.equals("png", ignoreCase = true)) "image/png" else "image/jpeg"
            val fmt = if (f.extension.equals("png", ignoreCase = true)) Bitmap.CompressFormat.PNG else Bitmap.CompressFormat.JPEG
            parts += mapOf(
                "inline_data" to mapOf(
                    "mime_type" to mime,
                    "data" to readAndResizeBase64(f, fmt),
                ),
            )
        }

        val body = gson.toJson(
            mapOf(
                "contents" to listOf(mapOf("role" to "user", "parts" to parts)),
                "generationConfig" to mapOf(
                    "responseModalities" to listOf("IMAGE", "TEXT"),
                ),
            ),
        )

        val request = buildRequest(BG_URL, BG_MODEL, body, GeminiAction.TRY_ON_OUTFIT)

        return@withContext try {
            val response = http.newCall(request).await()
            val responseBody = response.body!!.string()
            Log.d(TAG, "Try-on HTTP ${response.code}: ${responseBody.take(500)}")
            if (!response.isSuccessful) return@withContext null

            val parsed = gson.fromJson(responseBody, GeminiResponse::class.java)
            recordUsage(parsed, BG_MODEL, UsageCategory.TRY_ON)
            val imagePart = parsed.candidates
                ?.firstOrNull()
                ?.content
                ?.parts
                ?.firstOrNull { it.inlineData?.mimeType?.startsWith("image/") == true }
                ?: run {
                    val textParts = parsed.candidates
                        ?.firstOrNull()?.content?.parts
                        ?.mapNotNull { it.text }?.joinToString(" ")
                    Log.w(TAG, "No image part in try-on response. Text: $textParts")
                    return@withContext null
                }

            val outFile = File(outputDir, "tryon_${System.currentTimeMillis()}.png")
            outFile.writeBytes(Base64.decode(imagePart.inlineData!!.data!!, Base64.NO_WRAP))
            Log.d(TAG, "Try-on image saved (${outFile.length() / 1024}KB)")
            outFile
        } catch (e: Exception) {
            Log.e(TAG, "tryOnOutfit failed: ${e.message}", e)
            null
        }
    }

    /**
     * Classifies the clothing item in [imageFile] using Gemini vision.
     * [language] should be a Gemini-friendly language name such as "English" or "German";
     * the generated `label` field will be written in that language.
     * Returns [ClothingTags] or null on failure.
     */
    suspend fun classifyClothing(imageFile: File, language: String = "English"): ClothingTags? =
        withContext(Dispatchers.IO) {
            if (!isConfigured()) return@withContext null

            Log.d(TAG, "Classifying clothing in ${imageFile.name} via $CLASSIFY_MODEL (lang=$language)")
            val mimeType = if (imageFile.extension == "png") "image/png" else "image/jpeg"
            val format = if (imageFile.extension == "png") Bitmap.CompressFormat.PNG else Bitmap.CompressFormat.JPEG
            val imageBase64 = readAndResizeBase64(imageFile, format)
            val prompt = PromptStore.get(app, PromptKey.CLASSIFY).replace("{LANGUAGE}", language)

            val body = gson.toJson(
                mapOf(
                    "contents" to listOf(
                        mapOf(
                            "role" to "user",
                            "parts" to listOf(
                                mapOf("text" to prompt),
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

            val request = buildRequest(CLASSIFY_URL, CLASSIFY_MODEL, body, GeminiAction.CLASSIFY_CLOTHING)

            return@withContext try {
                val response = http.newCall(request).await()
                val responseBody = response.body!!.string()
                Log.d(TAG, "Classify HTTP ${response.code}: ${responseBody.take(500)}")
                if (!response.isSuccessful) return@withContext null

                val parsed = gson.fromJson(responseBody, GeminiResponse::class.java)
                recordUsage(parsed, CLASSIFY_MODEL, UsageCategory.TAGGING)
                val text = parsed.candidates
                    ?.firstOrNull()?.content?.parts
                    ?.firstOrNull { it.text != null }?.text
                    ?: run { Log.w(TAG, "No text part in classify response"); return@withContext null }

                val json = text.trim()
                    .removePrefix("```json").removePrefix("```").removeSuffix("```").trim()
                gson.fromJson(json, ClothingTags::class.java)
                    .normalize()
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
    suspend fun searchFashionTrends(
        region: String,
        category: UsageCategory = UsageCategory.TRENDS,
    ): FashionTrends? = withContext(Dispatchers.IO) {
        if (!isConfigured()) return@withContext null

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
                buildRequest(PREDICT_URL, CLASSIFY_MODEL, body, GeminiAction.SEARCH_TRENDS),
            ).await()
            val responseBody = response.body!!.string()
            Log.d(TAG, "searchFashionTrends HTTP ${response.code}: ${responseBody.take(500)}")
            if (!response.isSuccessful) return@withContext null

            val parsed = gson.fromJson(responseBody, GeminiResponse::class.java)
            recordUsage(parsed, CLASSIFY_MODEL, category)
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
     * Sends a text-only prompt to Gemini and returns the raw text response, or null on failure.
     */
    suspend fun generateText(
        prompt: String,
        category: UsageCategory = UsageCategory.OTHER,
    ): String? = withContext(Dispatchers.IO) {
        if (!isConfigured()) return@withContext null

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
                buildRequest(PREDICT_URL, CLASSIFY_MODEL, body, GeminiAction.GENERATE_TEXT),
            ).await()
            val responseBody = response.body!!.string()
            Log.d(TAG, "generateText HTTP ${response.code}: ${responseBody.take(500)}")
            if (!response.isSuccessful) return@withContext null

            val parsed = gson.fromJson(responseBody, GeminiResponse::class.java)
            recordUsage(parsed, CLASSIFY_MODEL, category)
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
    // Skia's PNG encoder writes a 24-bit RGB PNG when hasAlpha() is false, baking the cleared
    // green-screen pixels in as solid black. Force the flag so we get a 32-bit RGBA PNG with
    // genuine transparency.
    mutable.setHasAlpha(true)
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
    val label: String = "",
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
    @SerializedName("usageMetadata") val usageMetadata: GeminiUsageMetadata? = null,
)

private data class GeminiUsageMetadata(
    @SerializedName("promptTokenCount") val promptTokenCount: Int? = null,
    @SerializedName("candidatesTokenCount") val candidatesTokenCount: Int? = null,
    @SerializedName("totalTokenCount") val totalTokenCount: Int? = null,
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

