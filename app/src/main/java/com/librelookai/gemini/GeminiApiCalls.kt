package com.librelookai.gemini

import android.graphics.Bitmap
import android.util.Base64
import android.util.Log
import com.librelookai.data.drive.await
import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.tasks.await
import kotlinx.coroutines.withContext

internal suspend fun GeminiRepository.tryOnOutfit(
        personFiles: List<File>,
        itemFiles: List<File>,
        outputDir: File,
        preferences: String = "",
    ): File? = withContext(Dispatchers.IO) {
        if (!isConfigured()) {
            Log.w(GeminiRepository.TAG, "API key not set — skipping try-on")
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

        val request = buildRequest(GeminiRepository.BG_URL, GeminiRepository.BG_MODEL, body, GeminiAction.TRY_ON_OUTFIT)

        return@withContext try {
            val response = http.newCall(request).await()
            val responseBody = response.body!!.string()
            Log.d(GeminiRepository.TAG, "Try-on HTTP ${response.code}: ${responseBody.take(500)}")
            throwIf402(response.code, responseBody)
            if (!response.isSuccessful) return@withContext null

            val parsed = gson.fromJson(responseBody, GeminiResponse::class.java)
            recordUsage(parsed, GeminiRepository.BG_MODEL, UsageCategory.TRY_ON)
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
                    return@withContext null
                }

            val outFile = File(outputDir, "tryon_${System.currentTimeMillis()}.png")
            outFile.writeBytes(Base64.decode(imagePart.inlineData!!.data!!, Base64.NO_WRAP))
            Log.d(GeminiRepository.TAG, "Try-on image saved (${outFile.length() / 1024}KB)")
            outFile
        } catch (e: com.librelookai.billing.InsufficientCreditsException) {
            throw e
        } catch (e: Exception) {
            Log.e(GeminiRepository.TAG, "tryOnOutfit failed: ${e.message}", e)
            null
        }
    }

    /**
     * Classifies the clothing item in [imageFile] using Gemini vision.
     * [language] should be a Gemini-friendly language name such as "English" or "German";
     * the generated `label` field will be written in that language.
     * Returns [ClothingTags] or null on failure.
     */
internal suspend fun GeminiRepository.classifyClothing(imageFile: File, language: String = "English"): ClothingTags? =
        withContext(Dispatchers.IO) {
            if (!isConfigured()) return@withContext null

            Log.d(GeminiRepository.TAG, "Classifying clothing in ${imageFile.name} via $GeminiRepository.CLASSIFY_MODEL (lang=$language)")
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

            val request = buildRequest(GeminiRepository.CLASSIFY_URL, GeminiRepository.CLASSIFY_MODEL, body, GeminiAction.CLASSIFY_CLOTHING)

            return@withContext try {
                val response = http.newCall(request).await()
                val responseBody = response.body!!.string()
                Log.d(GeminiRepository.TAG, "Classify HTTP ${response.code}: ${responseBody.take(500)}")
                throwIf402(response.code, responseBody)
                if (!response.isSuccessful) return@withContext null

                val parsed = gson.fromJson(responseBody, GeminiResponse::class.java)
                recordUsage(parsed, GeminiRepository.CLASSIFY_MODEL, UsageCategory.TAGGING)
                val text = parsed.candidates
                    ?.firstOrNull()?.content?.parts
                    ?.firstOrNull { it.text != null }?.text
                    ?: run { Log.w(GeminiRepository.TAG, "No text part in classify response"); return@withContext null }

                val json = text.trim()
                    .removePrefix("```json").removePrefix("```").removeSuffix("```").trim()
                gson.fromJson(json, ClothingTags::class.java)
                    .normalize()
                    .also { Log.d(GeminiRepository.TAG, "Tags: $it") }
            } catch (e: com.librelookai.billing.InsufficientCreditsException) {
                throw e
            } catch (e: Exception) {
                Log.e(GeminiRepository.TAG, "Classification failed: ${e.message}", e)
                null
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
            throwIf402(response.code, responseBody)
            if (!response.isSuccessful) return@withContext null

            val parsed = gson.fromJson(responseBody, GeminiResponse::class.java)
            recordUsage(parsed, GeminiRepository.CLASSIFY_MODEL, category)
            val text = parsed.candidates
                ?.firstOrNull()?.content?.parts
                ?.firstOrNull { it.text != null }?.text
                ?: return@withContext null

            val json = text.trim()
                .removePrefix("```json").removePrefix("```").removeSuffix("```").trim()
            gson.fromJson(json, FashionTrends::class.java)
                .also { Log.d(GeminiRepository.TAG, "FashionTrends for $region: $it") }
        } catch (e: com.librelookai.billing.InsufficientCreditsException) {
            throw e
        } catch (e: Exception) {
            Log.w(GeminiRepository.TAG, "searchFashionTrends failed (non-fatal): ${e.message}")
            null
        }
    }

    /**
     * Sends a text-only prompt to Gemini and returns the raw text response, or null on failure.
     */
internal suspend fun GeminiRepository.generateText(
        prompt: String,
        category: UsageCategory = UsageCategory.OTHER,
        bulkItems: Int = 1,
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
                buildRequest(GeminiRepository.PREDICT_URL, GeminiRepository.CLASSIFY_MODEL, body, GeminiAction.GENERATE_TEXT, bulkItems = bulkItems),
            ).await()
            val responseBody = response.body!!.string()
            Log.d(GeminiRepository.TAG, "generateText HTTP ${response.code}: ${responseBody.take(500)}")
            throwIf402(response.code, responseBody)
            if (!response.isSuccessful) return@withContext null

            val parsed = gson.fromJson(responseBody, GeminiResponse::class.java)
            recordUsage(parsed, GeminiRepository.CLASSIFY_MODEL, category)
            parsed.candidates
                ?.firstOrNull()?.content?.parts
                ?.firstOrNull { it.text != null }?.text
        } catch (e: com.librelookai.billing.InsufficientCreditsException) {
            throw e
        } catch (e: Exception) {
            Log.e(GeminiRepository.TAG, "generateText failed: ${e.message}", e)
            null
        }
    }

    // ---------- Image processing ----------

