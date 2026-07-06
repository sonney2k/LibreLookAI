package com.librelookai.gemini

import android.graphics.Bitmap
import android.util.Base64
import android.util.Log
import com.librelookai.data.drive.await
import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.tasks.await
import kotlinx.coroutines.withContext

/**
 * The exact text prompt sent for a try-on, given the person/item photo counts. Extracted so the
 * pre-tap BYOK cost estimate ([com.librelookai.billing.rememberTryOnCostTokens]) can size the real
 * payload without drifting from what [tryOnOutfit] actually sends.
 */
fun buildTryOnPrompt(personCount: Int, itemCount: Int, preferences: String): String {
    val prefsHint = preferences.trim().takeIf { it.isNotEmpty() }
        ?.let { " The person's style preferences: $it." } ?: ""
    val personCountHint = when (personCount) {
        1 -> "a single frontal reference photo of the person"
        2 -> "two reference photos of the person — the FIRST is the frontal photo (authoritative for the face), the second is an additional angle"
        else -> "$personCount reference photos of the person — the FIRST is the frontal photo (authoritative for the face), the rest are additional angles"
    }
    val itemCountHint = if (itemCount == 1) "this clothing item" else "these $itemCount clothing items"
    return """
        You are given $personCountHint, followed by $itemCountHint.
        Generate a single photorealistic full-body image of the same person wearing $itemCountHint layered realistically as an outfit.

        CRITICAL GARMENT INSTRUCTION: The clothing items must remain completely distinct and separate. Do NOT merge, blend, or fuse different items into a single hybrid garment (e.g., do not fuse an outerwear zipper or jacket with an underlying t-shirt). Maintain distinct layers, physical boundaries, and textures for each individual piece. Dress the person so all provided garments are visible and layered logically.

        CRITICAL EXACT-ITEMS INSTRUCTION: Dress the person in EXACTLY and ONLY the provided garments — nothing else. Do NOT add, invent, or imagine any clothing that was not provided: no extra shoes or footwear, no socks, no undershirt or t-shirt under a jacket, no hat, belt, scarf, jewellery, or any other accessory unless it was explicitly provided as one of the items. If no footwear was provided, leave the feet bare or out of frame — never add shoes.

        CRITICAL DECENCY INSTRUCTION: The result must be fully clothed with no exposed torso, chest, or underwear. If an outer layer (vest, waistcoat, gilet, jacket, blazer, cardigan, or coat) is provided and there is NO separate top/shirt underneath it among the provided items, fasten and close that outer layer (button or zip it up) so the chest and torso are fully covered. Never depict nudity, bare chest, or exposed midriff.

        CRITICAL IDENTITY INSTRUCTION: The generated face must be as close as possible to the face in the FIRST (frontal) reference photo — match facial structure, features, proportions, eye shape and color, nose, mouth, jawline, eyebrows, skin tone, and hair exactly. Treat the frontal photo as the ground truth for identity; the other photos are only for recovering body shape and side/back details. Do not invent, beautify, age, or restyle the face. Preserve hair, skin tone, and body proportions exactly.

        Use a clean, neutral studio background, soft even lighting, and sharp focus.
        No text, watermarks, UI, extra people, or extra clothing that was not provided. $prefsHint
    """.trimIndent()
}

internal suspend fun GeminiRepository.tryOnOutfitImpl(
        personFiles: List<File>,
        itemFiles: List<File>,
        outputDir: File,
        preferences: String = "",
        notify: Boolean = false,
    ): AiResult<File> = withContext(Dispatchers.IO) {
        if (!ensureConfigured(notify)) {
            Log.w(GeminiRepository.TAG, "API key not set — skipping try-on")
            return@withContext AiResult.NotConfigured
        }
        if (personFiles.isEmpty() || itemFiles.isEmpty()) {
            return@withContext AiResult.Failure(AiErrorReason.GENERIC, retryable = false)
        }

        val prompt = buildTryOnPrompt(personFiles.size, itemFiles.size, preferences)
        val estimate = CostTokens(
            inputTokens = TokenEstimator.textTokens(prompt) +
                TokenEstimator.imageInputTokens(personFiles) +
                TokenEstimator.imageInputTokens(itemFiles),
            outputTokens = TokenEstimator.expectedOutputTokens(UsageCategory.TRY_ON),
            outputIsImage = true,
        )

        val parts = mutableListOf<Map<String, Any>>(mapOf("text" to prompt))
        personFiles.forEach { f ->
            // Person photos have no alpha — re-encode (only if >1280px) as JPEG; MIME is sniffed.
            val (mime, data) = readAndResizeBase64(f, Bitmap.CompressFormat.JPEG)
            parts += mapOf("inline_data" to mapOf("mime_type" to mime, "data" to data))
        }
        itemFiles.forEach { f ->
            // Item cutouts may carry alpha — re-encode (only if >1280px) as PNG; MIME is sniffed.
            val (mime, data) = readAndResizeBase64(f, Bitmap.CompressFormat.PNG)
            parts += mapOf("inline_data" to mapOf("mime_type" to mime, "data" to data))
        }

        val body = gson.toJson(
            mapOf(
                "contents" to listOf(mapOf("role" to "user", "parts" to parts)),
                "generationConfig" to mapOf(
                    "responseModalities" to listOf("IMAGE", "TEXT"),
                ),
            ),
        )

        return@withContext try {
            val request = buildRequest(GeminiRepository.BG_URL, GeminiRepository.BG_MODEL, body, GeminiAction.TRY_ON_OUTFIT)
            val response = http.newCall(request).await()
            val responseBody = response.body!!.string()
            Log.d(GeminiRepository.TAG, "Try-on HTTP ${response.code}: ${responseBody.take(500)}")
            creditsIf402(response.code, responseBody)?.let { return@withContext it }
            if (!response.isSuccessful) {
                return@withContext failure(response.code, responseBody, notify)
            }

            val parsed = gson.fromJson(responseBody, GeminiResponse::class.java)
            recordUsage(parsed, GeminiRepository.BG_MODEL, UsageCategory.TRY_ON, estimate)
            val imagePart = parsed.candidates
                ?.firstOrNull()
                ?.content
                ?.parts
                ?.firstOrNull { it.inlineData?.mimeType?.startsWith("image/") == true }
                ?: run {
                    val textParts = parsed.candidates
                        ?.firstOrNull()?.content?.parts
                        ?.mapNotNull { it.text }?.joinToString(" ")
                    Log.w(GeminiRepository.TAG, "No image part in try-on response. Text: $textParts")
                    return@withContext failure(200, responseBody, notify)
                }

            val outFile = File(outputDir, "tryon_${System.currentTimeMillis()}.png")
            outFile.writeBytes(Base64.decode(imagePart.inlineData!!.data!!, Base64.NO_WRAP))
            Log.d(GeminiRepository.TAG, "Try-on image saved (${outFile.length() / 1024}KB)")
            AiResult.Success(outFile)
        } catch (e: AiUnavailableException) {
            AiResult.Failure(e.reason) // buildRequest already emitted the notice
        } catch (e: Exception) {
            Log.e(GeminiRepository.TAG, "tryOnOutfit failed: ${e.message}", e)
            failure(0, "", notify)
        }
    }

    /**
     * Classifies the clothing item in [imageFile] using Gemini vision.
     * [language] should be a Gemini-friendly language name such as "English" or "German";
     * the generated `label` field will be written in that language.
     * Classification is always an automatic/bulk call, so failures never notify.
     */
internal suspend fun GeminiRepository.classifyClothingImpl(imageFile: File, language: String = "English"): AiResult<ClothingTags> =
        withContext(Dispatchers.IO) {
            if (!isConfigured()) return@withContext AiResult.NotConfigured

            Log.d(GeminiRepository.TAG, "Classifying clothing in ${imageFile.name} via $GeminiRepository.CLASSIFY_MODEL (lang=$language)")
            // Classify operates on a cutout (may carry alpha) — re-encode (only if >1280px) as
            // PNG; the actual MIME is sniffed from the bytes (cache may be WebP under a .png name).
            val (mimeType, imageBase64) = readAndResizeBase64(imageFile, Bitmap.CompressFormat.PNG)
            val prompt = PromptStore.get(app, PromptKey.CLASSIFY).replace("{LANGUAGE}", language)
            val estimate = CostTokens(
                inputTokens = TokenEstimator.textTokens(prompt) + TokenEstimator.imageInputTokens(imageFile),
                outputTokens = TokenEstimator.expectedOutputTokens(UsageCategory.TAGGING),
                outputIsImage = false,
            )

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

            return@withContext try {
                val request = buildRequest(GeminiRepository.CLASSIFY_URL, GeminiRepository.CLASSIFY_MODEL, body, GeminiAction.CLASSIFY_CLOTHING)
                val response = http.newCall(request).await()
                val responseBody = response.body!!.string()
                Log.d(GeminiRepository.TAG, "Classify HTTP ${response.code}: ${responseBody.take(500)}")
                creditsIf402(response.code, responseBody)?.let { return@withContext it }
                if (!response.isSuccessful) {
                    return@withContext failure(response.code, responseBody, notify = false)
                }

                val parsed = gson.fromJson(responseBody, GeminiResponse::class.java)
                recordUsage(parsed, GeminiRepository.CLASSIFY_MODEL, UsageCategory.TAGGING, estimate)
                val text = parsed.candidates
                    ?.firstOrNull()?.content?.parts
                    ?.firstOrNull { it.text != null }?.text
                    ?: run {
                        Log.w(GeminiRepository.TAG, "No text part in classify response")
                        return@withContext failure(200, responseBody, notify = false)
                    }

                val json = text.trim()
                    .removePrefix("```json").removePrefix("```").removeSuffix("```").trim()
                AiResult.Success(
                    gson.fromJson(json, ClothingTags::class.java)
                        .normalize()
                        .also { Log.d(GeminiRepository.TAG, "Tags: $it") },
                )
            } catch (e: AiUnavailableException) {
                AiResult.Failure(e.reason)
            } catch (e: Exception) {
                Log.e(GeminiRepository.TAG, "Classification failed: ${e.message}", e)
                failure(0, "", notify = false)
            }
        }

    /**
     * Uses Gemini with Google Search grounding to fetch current street-fashion trends for [region].
     * Returns null on failure so callers can proceed without trend data.
     */
internal suspend fun GeminiRepository.searchFashionTrends(
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
        val estimate = CostTokens(
            inputTokens = TokenEstimator.textTokens(prompt),
            outputTokens = TokenEstimator.expectedOutputTokens(category),
            outputIsImage = false,
        )

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
                buildRequest(GeminiRepository.PREDICT_URL, GeminiRepository.CLASSIFY_MODEL, body, GeminiAction.SEARCH_TRENDS),
            ).await()
            val responseBody = response.body!!.string()
            Log.d(GeminiRepository.TAG, "searchFashionTrends HTTP ${response.code}: ${responseBody.take(500)}")
            // A 402 raises the global top-up dialog (creditsIf402 emits) but degrades like any
            // other failure here — trends are a garnish; the caller's main generateText call is
            // what surfaces InsufficientCredits to its control flow (refactor § 6).
            if (creditsIf402(response.code, responseBody) != null) return@withContext null
            if (!response.isSuccessful) return@withContext null

            val parsed = gson.fromJson(responseBody, GeminiResponse::class.java)
            recordUsage(parsed, GeminiRepository.CLASSIFY_MODEL, category, estimate)
            val text = parsed.candidates
                ?.firstOrNull()?.content?.parts
                ?.firstOrNull { it.text != null }?.text
                ?: return@withContext null

            val json = text.trim()
                .removePrefix("```json").removePrefix("```").removeSuffix("```").trim()
            gson.fromJson(json, FashionTrends::class.java)
                .also { Log.d(GeminiRepository.TAG, "FashionTrends for $region: $it") }
        } catch (e: Exception) {
            Log.w(GeminiRepository.TAG, "searchFashionTrends failed (non-fatal): ${e.message}")
            null
        }
    }

    /**
     * Sends a text-only prompt to Gemini and returns the raw text response.
     */
internal suspend fun GeminiRepository.generateTextImpl(
        prompt: String,
        category: UsageCategory = UsageCategory.OTHER,
        bulkItems: Int = 1,
        notify: Boolean = false,
    ): AiResult<String> = withContext(Dispatchers.IO) {
        if (!ensureConfigured(notify)) return@withContext AiResult.NotConfigured

        val estimate = CostTokens(
            inputTokens = TokenEstimator.textTokens(prompt),
            outputTokens = TokenEstimator.expectedOutputTokens(category, bulkItems),
            outputIsImage = false,
        )
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
                buildRequest(GeminiRepository.PREDICT_URL, GeminiRepository.CLASSIFY_MODEL, body, GeminiAction.GENERATE_TEXT, bulkItems = bulkItems),
            ).await()
            val responseBody = response.body!!.string()
            Log.d(GeminiRepository.TAG, "generateText HTTP ${response.code}: ${responseBody.take(500)}")
            creditsIf402(response.code, responseBody)?.let { return@withContext it }
            if (!response.isSuccessful) {
                return@withContext failure(response.code, responseBody, notify)
            }

            val parsed = gson.fromJson(responseBody, GeminiResponse::class.java)
            recordUsage(parsed, GeminiRepository.CLASSIFY_MODEL, category, estimate)
            parsed.candidates
                ?.firstOrNull()?.content?.parts
                ?.firstOrNull { it.text != null }?.text
                ?.let { AiResult.Success(it) }
                ?: failure(200, responseBody, notify)
        } catch (e: AiUnavailableException) {
            AiResult.Failure(e.reason) // buildRequest already emitted the notice
        } catch (e: Exception) {
            Log.e(GeminiRepository.TAG, "generateText failed: ${e.message}", e)
            failure(0, "", notify)
        }
    }

    // ---------- Image processing ----------

