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
        if (mutable !== bmp) bmp.recycle()
        val w = mutable.width
        val h = mutable.height
        val pixels = IntArray(w * h)
        mutable.getPixels(pixels, 0, w, 0, 0, w, h)
        // Neon-green (#00FF00) with tolerance for JPEG artifacts → fully transparent. Kept
        // tight so saturated-green or dark-green garments aren't keyed away by accident; any
        // residual shadow halo is left to despill below.
        for (i in pixels.indices) {
            val c = pixels[i]
            val r = (c shr 16) and 0xFF
            val g = (c shr 8) and 0xFF
            val b = c and 0xFF
            if (r < 80 && g > 180 && b < 80) pixels[i] = 0
        }
        despillGreenInPlace(pixels)
        featherAlphaEdgesInPlace(pixels, w, h)
        mutable.setPixels(pixels, 0, w, 0, 0, w, h)
        mutable.setHasAlpha(true)

        val finalBmp = cropAndCap(mutable)
        outputFile.outputStream().use { finalBmp.compress(Bitmap.CompressFormat.PNG, 100, it) }
        Log.d(TAG, "Cutout post-processed: ${w}x${h} → ${finalBmp.width}x${finalBmp.height}")
        finalBmp.recycle()
    }
}

// ---------- Cutout post-processing helpers (reused by Settings → Data "Fix cutout backgrounds") ----------

/** Sets alpha=0 on near-black pixels that are *connected to the image border* via a 4-way
 *  flood fill. This preserves black garment interiors (e.g. a black t-shirt with a white logo,
 *  or a sandal with black straps) while still clearing the black matte Gemini occasionally
 *  returns instead of a transparent background.
 *
 *  Without the connectivity constraint, a flat threshold wipes out every dark pixel in the
 *  image — including the inside of the subject — which is exactly the failure mode this
 *  function is meant to avoid. */
internal fun blackBackgroundToAlphaInPlace(pixels: IntArray, w: Int, h: Int, threshold: Int = 8) {
    if (w <= 0 || h <= 0) return
    fun isBlack(c: Int): Boolean {
        val a = (c ushr 24) and 0xFF
        if (a == 0) return false
        val r = (c shr 16) and 0xFF
        val g = (c shr 8) and 0xFF
        val b = c and 0xFF
        return r <= threshold && g <= threshold && b <= threshold
    }
    val visited = BooleanArray(w * h)
    val stack = ArrayDeque<Int>()
    fun seed(x: Int, y: Int) {
        val i = y * w + x
        if (visited[i]) return
        if (!isBlack(pixels[i])) return
        visited[i] = true
        stack.addLast(i)
    }
    for (x in 0 until w) { seed(x, 0); seed(x, h - 1) }
    for (y in 0 until h) { seed(0, y); seed(w - 1, y) }
    while (stack.isNotEmpty()) {
        val i = stack.removeLast()
        pixels[i] = 0
        val x = i % w
        val y = i / w
        if (x > 0)     { val j = i - 1; if (!visited[j] && isBlack(pixels[j])) { visited[j] = true; stack.addLast(j) } }
        if (x < w - 1) { val j = i + 1; if (!visited[j] && isBlack(pixels[j])) { visited[j] = true; stack.addLast(j) } }
        if (y > 0)     { val j = i - w; if (!visited[j] && isBlack(pixels[j])) { visited[j] = true; stack.addLast(j) } }
        if (y < h - 1) { val j = i + w; if (!visited[j] && isBlack(pixels[j])) { visited[j] = true; stack.addLast(j) } }
    }
}

/** Despill: if green dominates on an opaque pixel, clamp G to max(R,B) to neutralise the halo
 *  left over from a green-screen matte without darkening the pixel. */
internal fun despillGreenInPlace(pixels: IntArray) {
    for (i in pixels.indices) {
        val c = pixels[i]
        val a = (c ushr 24) and 0xFF
        if (a == 0) continue
        val r = (c shr 16) and 0xFF
        val g = (c shr 8) and 0xFF
        val b = c and 0xFF
        if (g > r && g > b) {
            val g2 = maxOf(r, b)
            pixels[i] = (a shl 24) or (r shl 16) or (g2 shl 8) or b
        }
    }
}

/** Soften the alpha boundary by averaging a (2*radius+1)² neighbourhood of alpha on
 *  currently-opaque pixels. Uses a snapshot so the smoothing does not feed back on itself. */
internal fun featherAlphaEdgesInPlace(pixels: IntArray, w: Int, h: Int, radius: Int = 1) {
    val srcAlpha = ByteArray(w * h)
    for (i in pixels.indices) srcAlpha[i] = ((pixels[i] ushr 24) and 0xFF).toByte()
    val side = radius * 2 + 1
    val area = side * side
    for (y in radius until h - radius) {
        val row = y * w
        for (x in radius until w - radius) {
            val idx = row + x
            if ((srcAlpha[idx].toInt() and 0xFF) == 0) continue
            // Only feather pixels that border a transparent pixel — otherwise we erode alpha
            // on solid interior regions and create faint speckle holes.
            val onEdge =
                (srcAlpha[idx - 1].toInt() and 0xFF) == 0 ||
                (srcAlpha[idx + 1].toInt() and 0xFF) == 0 ||
                (srcAlpha[idx - w].toInt() and 0xFF) == 0 ||
                (srcAlpha[idx + w].toInt() and 0xFF) == 0
            if (!onEdge) continue
            var total = 0
            for (ky in -radius..radius) {
                val nr = (y + ky) * w
                for (kx in -radius..radius) {
                    total += srcAlpha[nr + x + kx].toInt() and 0xFF
                }
            }
            val newA = total / area
            val c = pixels[idx]
            pixels[idx] = (newA shl 24) or (c and 0x00FFFFFF)
        }
    }
}

/** Returns [minX, minY, maxX, maxY] over opaque pixels, or null if all transparent. */
internal fun computeOpaqueBBox(pixels: IntArray, w: Int, h: Int): IntArray? {
    var minX = w; var minY = h; var maxX = -1; var maxY = -1
    for (y in 0 until h) {
        val row = y * w
        for (x in 0 until w) {
            if (((pixels[row + x] ushr 24) and 0xFF) != 0) {
                if (x < minX) minX = x
                if (x > maxX) maxX = x
                if (y < minY) minY = y
                if (y > maxY) maxY = y
            }
        }
    }
    return if (maxX >= minX && maxY >= minY) intArrayOf(minX, minY, maxX, maxY) else null
}

/** Tight-crops [bitmap] to its opaque bbox (with [marginPct] padding, min 2px), then caps so
 *  max(width, height) ≤ [maxDim]. Recycles intermediate bitmaps it creates. */
internal fun cropAndCap(
    bitmap: Bitmap,
    marginPct: Float = 0.02f,
    maxDim: Int = 1280,
): Bitmap {
    val w = bitmap.width
    val h = bitmap.height
    val px = IntArray(w * h)
    bitmap.getPixels(px, 0, w, 0, 0, w, h)
    val bbox = computeOpaqueBBox(px, w, h)
    val cropped = if (bbox != null) {
        val (minX, minY, maxX, maxY) = bbox.let { arrayOf(it[0], it[1], it[2], it[3]) }
        val margin = maxOf(2, (maxOf(maxX - minX + 1, maxY - minY + 1) * marginPct).roundToInt())
        val x0 = (minX - margin).coerceAtLeast(0)
        val y0 = (minY - margin).coerceAtLeast(0)
        val x1 = (maxX + margin).coerceAtMost(w - 1)
        val y1 = (maxY + margin).coerceAtMost(h - 1)
        val cw = x1 - x0 + 1
        val ch = y1 - y0 + 1
        if (cw < w || ch < h) {
            Bitmap.createBitmap(bitmap, x0, y0, cw, ch).also { if (it !== bitmap) bitmap.recycle() }
        } else bitmap
    } else bitmap

    val curMax = maxOf(cropped.width, cropped.height)
    return if (curMax > maxDim) {
        val scale = maxDim.toFloat() / curMax
        val nw = (cropped.width * scale).roundToInt()
        val nh = (cropped.height * scale).roundToInt()
        Bitmap.createScaledBitmap(cropped, nw, nh, true).also {
            if (it !== cropped) cropped.recycle()
            it.setHasAlpha(true)
        }
    } else cropped
}

// ---------- Cutout issue detection + repair ----------

internal data class CutoutIssues(
    val hasBlackBackground: Boolean,
    val hasGreenHalo: Boolean,
    val hasInteriorHoles: Boolean = false,
) {
    val any: Boolean get() = hasBlackBackground || hasGreenHalo || hasInteriorHoles
}

/** Detects black background + green halo on an existing cutout PNG.
 *
 *  Black-bg detection uses only the outer 2px border ring so a dark garment surrounded by
 *  transparency doesn't produce a false positive — a real black background almost always
 *  bleeds into the image edges, while a black sleeve doesn't.
 *
 *  Green-halo detection looks at edge pixels (opaque with at least one transparent
 *  neighbour) where green meaningfully dominates the other channels.
 */
internal fun detectCutoutIssues(file: File): CutoutIssues {
    val bmp = BitmapFactory.decodeFile(file.absolutePath) ?: return CutoutIssues(false, false)
    val w = bmp.width
    val h = bmp.height
    if (w < 4 || h < 4) { bmp.recycle(); return CutoutIssues(false, false) }
    val px = IntArray(w * h)
    bmp.getPixels(px, 0, w, 0, 0, w, h)
    bmp.recycle()

    // --- Black-background border-ring scan (outer 2px) ---
    val ring = 2
    var ringOpaqueBlack = 0
    var ringTotal = 0
    fun sample(x: Int, y: Int) {
        val c = px[y * w + x]
        val a = (c ushr 24) and 0xFF
        ringTotal++
        if (a >= 250) {
            val r = (c shr 16) and 0xFF
            val g = (c shr 8) and 0xFF
            val b = c and 0xFF
            if (r <= 8 && g <= 8 && b <= 8) ringOpaqueBlack++
        }
    }
    for (y in 0 until ring) for (x in 0 until w) sample(x, y)
    for (y in (h - ring) until h) for (x in 0 until w) sample(x, y)
    for (x in 0 until ring) for (y in ring until h - ring) sample(x, y)
    for (x in (w - ring) until w) for (y in ring until h - ring) sample(x, y)
    val hasBlackBg = ringTotal > 0 && ringOpaqueBlack.toFloat() / ringTotal > 0.05f

    // --- Green-halo edge scan ---
    var edgeOpaque = 0
    var edgeGreen = 0
    for (y in 1 until h - 1) {
        val row = y * w
        for (x in 1 until w - 1) {
            val c = px[row + x]
            val a = (c ushr 24) and 0xFF
            if (a < 250) continue
            // edge pixel: at least one transparent neighbour
            val n0 = (px[row + x - 1] ushr 24) and 0xFF
            val n1 = (px[row + x + 1] ushr 24) and 0xFF
            val n2 = (px[row - w + x] ushr 24) and 0xFF
            val n3 = (px[row + w + x] ushr 24) and 0xFF
            if (n0 != 0 && n1 != 0 && n2 != 0 && n3 != 0) continue
            edgeOpaque++
            val r = (c shr 16) and 0xFF
            val g = (c shr 8) and 0xFF
            val b = c and 0xFF
            if (g > r + 15 && g > b + 15) edgeGreen++
        }
    }
    val hasGreenHalo = edgeOpaque > 0 && edgeGreen.toFloat() / edgeOpaque > 0.005f

    // --- Interior alpha-hole scan: transparent pixels NOT reachable from the image border via
    //     a 4-way flood fill through transparent pixels are interior holes (e.g. a previous
    //     over-aggressive black→alpha pass that ate a logo or a black t-shirt body). Threshold
    //     filters out one-pixel JPEG noise so we don't flag clean cutouts as needing repair. */
    val reachable = BooleanArray(w * h)
    val stack = ArrayDeque<Int>()
    var totalTransparent = 0
    for (i in px.indices) {
        if (((px[i] ushr 24) and 0xFF) == 0) totalTransparent++
    }
    fun seedHole(x: Int, y: Int) {
        val i = y * w + x
        if (reachable[i]) return
        if (((px[i] ushr 24) and 0xFF) != 0) return
        reachable[i] = true
        stack.addLast(i)
    }
    for (x in 0 until w) { seedHole(x, 0); seedHole(x, h - 1) }
    for (y in 0 until h) { seedHole(0, y); seedHole(w - 1, y) }
    while (stack.isNotEmpty()) {
        val i = stack.removeLast()
        val x = i % w
        val y = i / w
        if (x > 0)     { val j = i - 1; if (!reachable[j] && ((px[j] ushr 24) and 0xFF) == 0) { reachable[j] = true; stack.addLast(j) } }
        if (x < w - 1) { val j = i + 1; if (!reachable[j] && ((px[j] ushr 24) and 0xFF) == 0) { reachable[j] = true; stack.addLast(j) } }
        if (y > 0)     { val j = i - w; if (!reachable[j] && ((px[j] ushr 24) and 0xFF) == 0) { reachable[j] = true; stack.addLast(j) } }
        if (y < h - 1) { val j = i + w; if (!reachable[j] && ((px[j] ushr 24) and 0xFF) == 0) { reachable[j] = true; stack.addLast(j) } }
    }
    var holePixels = 0
    for (i in px.indices) {
        if (((px[i] ushr 24) and 0xFF) == 0 && !reachable[i]) holePixels++
    }
    // Need at least a small region (≥0.05% of image) to count — avoids false positives from
    // antialiased edges that briefly drop to alpha=0 in the interior.
    val holeThreshold = ((w * h) / 2000).coerceAtLeast(8)
    val hasInteriorHoles = holePixels >= holeThreshold

    return CutoutIssues(hasBlackBg, hasGreenHalo, hasInteriorHoles)
}

/** Fills interior alpha holes (transparent regions enclosed by opaque pixels) by morphological
 *  dilation: each iteration, every interior-hole pixel adjacent to an opaque pixel adopts the
 *  average color of those opaque neighbours and becomes opaque. Repeats until stable or
 *  [maxIterations] is hit, which bounds runtime on pathological inputs. */
internal fun fillInteriorAlphaHolesInPlace(pixels: IntArray, w: Int, h: Int, maxIterations: Int = 64) {
    if (w <= 0 || h <= 0) return
    // First, build a "is reachable from border via transparent pixels" mask. Any transparent
    // pixel NOT in this set is an interior hole eligible to be filled.
    val reachable = BooleanArray(w * h)
    val stack = ArrayDeque<Int>()
    fun seed(i: Int) {
        if (reachable[i]) return
        if (((pixels[i] ushr 24) and 0xFF) != 0) return
        reachable[i] = true
        stack.addLast(i)
    }
    for (x in 0 until w) { seed(x); seed((h - 1) * w + x) }
    for (y in 0 until h) { seed(y * w); seed(y * w + w - 1) }
    while (stack.isNotEmpty()) {
        val i = stack.removeLast()
        val x = i % w
        val y = i / w
        if (x > 0)     { val j = i - 1; if (!reachable[j] && ((pixels[j] ushr 24) and 0xFF) == 0) { reachable[j] = true; stack.addLast(j) } }
        if (x < w - 1) { val j = i + 1; if (!reachable[j] && ((pixels[j] ushr 24) and 0xFF) == 0) { reachable[j] = true; stack.addLast(j) } }
        if (y > 0)     { val j = i - w; if (!reachable[j] && ((pixels[j] ushr 24) and 0xFF) == 0) { reachable[j] = true; stack.addLast(j) } }
        if (y < h - 1) { val j = i + w; if (!reachable[j] && ((pixels[j] ushr 24) and 0xFF) == 0) { reachable[j] = true; stack.addLast(j) } }
    }
    val isHole = BooleanArray(w * h)
    var remaining = 0
    for (i in pixels.indices) {
        if (((pixels[i] ushr 24) and 0xFF) == 0 && !reachable[i]) {
            isHole[i] = true
            remaining++
        }
    }
    if (remaining == 0) return

    var iter = 0
    while (remaining > 0 && iter < maxIterations) {
        iter++
        var filledThisPass = 0
        // Snapshot the hole mask so dilation in this pass uses the previous frontier only.
        val snapshot = isHole.copyOf()
        for (y in 0 until h) {
            val row = y * w
            for (x in 0 until w) {
                val i = row + x
                if (!snapshot[i]) continue
                var rs = 0; var gs = 0; var bs = 0; var n = 0
                fun consider(j: Int) {
                    if (snapshot[j]) return
                    val a = (pixels[j] ushr 24) and 0xFF
                    if (a == 0) return
                    rs += (pixels[j] shr 16) and 0xFF
                    gs += (pixels[j] shr 8) and 0xFF
                    bs += pixels[j] and 0xFF
                    n++
                }
                if (x > 0)     consider(i - 1)
                if (x < w - 1) consider(i + 1)
                if (y > 0)     consider(i - w)
                if (y < h - 1) consider(i + w)
                if (n > 0) {
                    val r = rs / n; val g = gs / n; val b = bs / n
                    pixels[i] = (0xFF shl 24) or (r shl 16) or (g shl 8) or b
                    isHole[i] = false
                    filledThisPass++
                }
            }
        }
        remaining -= filledThisPass
        if (filledThisPass == 0) break
    }
}

/** Per-action toggles for [fixCutoutBackground]. Each pixel transformation is independently
 *  gated so the UI can expose them as separate switches. */
data class CutoutFixActions(
    val blackToAlpha: Boolean,
    val despillGreen: Boolean,
    val fillHoles: Boolean,
    val feather: Boolean,
    val tightCrop: Boolean,
) {
    val any: Boolean get() = blackToAlpha || despillGreen || fillHoles || feather || tightCrop
}

/** Applies the enabled passes from [actions]. Output is written as PNG. */
internal fun fixCutoutBackground(input: File, output: File, actions: CutoutFixActions) {
    val src = BitmapFactory.decodeFile(input.absolutePath)
        ?: run { input.copyTo(output, overwrite = true); return }
    val mutable = src.copy(Bitmap.Config.ARGB_8888, true)
    if (mutable !== src) src.recycle()
    val w = mutable.width
    val h = mutable.height
    val px = IntArray(w * h)
    mutable.getPixels(px, 0, w, 0, 0, w, h)
    if (actions.blackToAlpha) blackBackgroundToAlphaInPlace(px, w, h)
    if (actions.despillGreen) despillGreenInPlace(px)
    if (actions.fillHoles) fillInteriorAlphaHolesInPlace(px, w, h)
    if (actions.feather) featherAlphaEdgesInPlace(px, w, h)
    mutable.setPixels(px, 0, w, 0, 0, w, h)
    mutable.setHasAlpha(true)
    val finalBmp = if (actions.tightCrop) cropAndCap(mutable) else mutable
    output.outputStream().use { finalBmp.compress(Bitmap.CompressFormat.PNG, 100, it) }
    finalBmp.recycle()
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

