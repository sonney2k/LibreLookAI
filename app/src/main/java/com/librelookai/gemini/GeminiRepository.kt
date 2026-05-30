package com.librelookai.gemini

import android.app.Application
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.util.Base64
import android.util.Log
import com.google.firebase.FirebaseApp
import com.google.firebase.auth.FirebaseAuth
import com.google.gson.Gson
import com.librelookai.BuildConfig
import com.librelookai.R
import com.librelookai.data.drive.await
import java.io.ByteArrayOutputStream
import java.io.File
import java.util.concurrent.TimeUnit
import kotlin.math.roundToInt
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.tasks.await
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody

internal enum class GeminiAction(val header: String) {
    REMOVE_BACKGROUND("removeBackground"),
    CLASSIFY_CLOTHING("classifyClothing"),
    GENERATE_TEXT("generateText"),
    SEARCH_TRENDS("searchFashionTrends"),
    TRY_ON_OUTFIT("tryOnOutfit"),
}

class GeminiRepository(internal val app: Application) {

    internal val http = OkHttpClient.Builder()
        .connectTimeout(30, TimeUnit.SECONDS)
        .writeTimeout(60, TimeUnit.SECONDS)  // uploading the image bytes
        .readTimeout(120, TimeUnit.SECONDS)  // waiting for Gemini to process
        .callTimeout(300, TimeUnit.SECONDS)  // hard ceiling for the whole call
        .build()
    internal val gson = Gson()
    private val usage = TokenUsageRepository.get(app)

    /**
     * If the proxy returned HTTP 402, parse `{ needed, have }` and raise
     * [com.librelookai.billing.InsufficientCreditsException] so the caller
     * can route the user to the buy-credits screen. No-op for any other code.
     */
    internal fun throwIf402(code: Int, body: String) {
        if (code != 402) return
        val obj = try { gson.fromJson(body, Map::class.java) as? Map<*, *> } catch (_: Exception) { null }
        val needed = (obj?.get("needed") as? Number)?.toInt() ?: 0
        val have = (obj?.get("have") as? Number)?.toInt() ?: 0
        val ex = com.librelookai.billing.InsufficientCreditsException(needed, have)
        // Emit on the global bus first so the top-level observer renders the
        // InsufficientCreditsDialog even when the caller's generic
        // `catch (Exception)` block swallows the throw.
        com.librelookai.billing.CreditsEvents.emitTopUp(ex)
        throw ex
    }

    /** Pulls usageMetadata out of a parsed Gemini response and records one event. */
    internal fun recordUsage(parsed: GeminiResponse, model: String, category: UsageCategory) {
        val meta = parsed.usageMetadata ?: return
        usage.record(
            category = category,
            model = model,
            inputTokens = meta.promptTokenCount ?: 0,
            outputTokens = meta.candidatesTokenCount ?: 0,
        )
    }

    companion object {
        internal const val TAG = "GeminiRepository"

        internal const val BG_MODEL = "gemini-3.1-flash-image-preview"
        internal const val BG_URL =
            "https://generativelanguage.googleapis.com/v1beta/models/$BG_MODEL:generateContent"

        internal const val CLASSIFY_MODEL = "gemini-3-flash-preview"
        internal const val CLASSIFY_URL =
            "https://generativelanguage.googleapis.com/v1beta/models/$CLASSIFY_MODEL:generateContent"

        // Text-only model for style prediction (reuses classify endpoint)
        internal const val PREDICT_URL = CLASSIFY_URL
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
    internal suspend fun buildRequest(
        directUrl: String,
        model: String,
        body: String,
        action: GeminiAction,
        bulkItems: Int = 1,
    ): Request {
        val localKey = resolveApiKey()
        if (localKey.isNotBlank()) {
            return Request.Builder()
                .url("$directUrl?key=$localKey")
                .post(body.toRequestBody("application/json".toMediaType()))
                .build()
        }
        val proxyBase = BuildConfig.PROXY_BASE_URL
        if (proxyBase.isBlank()) {
            AiEvents.emitUnavailable(R.string.ai_unavailable)
            throw AiUnavailableException(R.string.ai_unavailable)
        }
        val token = getFirebaseIdToken() ?: run {
            AiEvents.emitUnavailable(R.string.ai_unavailable)
            throw AiUnavailableException(R.string.ai_unavailable)
        }
        val builder = Request.Builder()
            .url("$proxyBase/geminiProxy")
            .post(body.toRequestBody("application/json".toMediaType()))
            .addHeader("Authorization", "Bearer $token")
            .addHeader("X-AI-Action", action.header)
            .addHeader("X-Gemini-Model", model)
        if (bulkItems > 1) builder.addHeader("X-Bulk-Items", bulkItems.toString())
        return builder.build()
    }

    /** Returns false when neither BYOK key nor proxy is configured. */
    internal fun isConfigured(): Boolean =
        resolveApiKey().isNotBlank() || BuildConfig.PROXY_BASE_URL.isNotBlank()

    /**
     * Read [imageFile], downscale so max(width, height) ≤ 1280 if needed,
     * and return the result as Base64. The [format] determines the compression
     * used when re-encoding is necessary (JPEG for photos, PNG for cutouts).
     */
    internal fun readAndResizeBase64(imageFile: File, format: Bitmap.CompressFormat): String {
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

            return@withContext try {
                val request = buildRequest(BG_URL, BG_MODEL, body, GeminiAction.REMOVE_BACKGROUND)
                val response = http.newCall(request).await()
                val responseBody = response.body!!.string()

                Log.d(TAG, "HTTP ${response.code}")
                // Log full body (may be large; truncate for readability)
                Log.d(TAG, "Response: ${responseBody.take(2000)}")
                throwIf402(response.code, responseBody)

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
            } catch (e: com.librelookai.billing.InsufficientCreditsException) {
                throw e
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
