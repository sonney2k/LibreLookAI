package com.librelookai.billing

import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.ui.platform.LocalContext
import com.librelookai.gemini.CostTokens
import com.librelookai.gemini.PromptKey
import com.librelookai.gemini.PromptStore
import com.librelookai.gemini.TokenEstimator
import com.librelookai.gemini.buildTryOnPrompt
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

/**
 * Composable helpers that pre-compute the **exact upcoming payload** for [CostBadge] in BYOK mode —
 * the real prompt text plus the actual input images, sized by [TokenEstimator]. Image bounds are
 * decoded off the main thread via [produceState]; until the first result lands (and whenever no
 * input is available yet) they return `null`, so the badge transparently falls back to its coarse
 * per-action table. Each helper mirrors how `GeminiRepository` assembles that call so the estimate
 * never drifts from the request that is actually sent.
 */

/** Classify returns a compact tag JSON — label + type + the several tag arrays. */
private const val CLASSIFY_OUTPUT_TOKENS = 400

/** Exact tokens for re-tagging the item at [imagePath] (fixed CLASSIFY prompt + one image input). */
@Composable
fun rememberClassifyCostTokens(imagePath: String?, language: String = "English"): CostTokens? {
    val ctx = LocalContext.current
    val tokens by produceState<CostTokens?>(null, imagePath, language) {
        val path = imagePath
        value = if (path.isNullOrEmpty()) null else withContext(Dispatchers.IO) {
            val prompt = PromptStore.get(ctx, PromptKey.CLASSIFY).replace("{LANGUAGE}", language)
            CostTokens(
                inputTokens = TokenEstimator.textTokens(prompt) + TokenEstimator.imageInputTokens(File(path)),
                outputTokens = CLASSIFY_OUTPUT_TOKENS,
                outputIsImage = false,
            )
        }
    }
    return tokens
}

/** Exact tokens for background removal on [imagePath] (fixed prompt + one image in, one image out). */
@Composable
fun rememberRemoveBgCostTokens(imagePath: String?): CostTokens? {
    val ctx = LocalContext.current
    val tokens by produceState<CostTokens?>(null, imagePath) {
        val path = imagePath
        value = if (path.isNullOrEmpty()) null else withContext(Dispatchers.IO) {
            val prompt = PromptStore.get(ctx, PromptKey.BG_REMOVAL)
            CostTokens(
                inputTokens = TokenEstimator.textTokens(prompt) + TokenEstimator.imageInputTokens(File(path)),
                outputTokens = TokenEstimator.IMAGE_OUTPUT_TOKENS,
                outputIsImage = true,
            )
        }
    }
    return tokens
}

/** Exact tokens for background removal sized directly from a known bitmap [width]/[height]. */
@Composable
fun rememberRemoveBgCostTokens(width: Int, height: Int): CostTokens? {
    val ctx = LocalContext.current
    val tokens by produceState<CostTokens?>(null, width, height) {
        value = if (width <= 0 || height <= 0) null else withContext(Dispatchers.Default) {
            val prompt = PromptStore.get(ctx, PromptKey.BG_REMOVAL)
            CostTokens(
                inputTokens = TokenEstimator.textTokens(prompt) + TokenEstimator.imageInputTokens(width, height),
                outputTokens = TokenEstimator.IMAGE_OUTPUT_TOKENS,
                outputIsImage = true,
            )
        }
    }
    return tokens
}

/** Exact tokens for a try-on (count-aware prompt + all person & item images in, one image out). */
@Composable
fun rememberTryOnCostTokens(
    personPaths: List<String>,
    itemPaths: List<String>,
    preferences: String = "",
): CostTokens? {
    val tokens by produceState<CostTokens?>(null, personPaths, itemPaths, preferences) {
        value = if (itemPaths.isEmpty()) null else withContext(Dispatchers.IO) {
            val prompt = buildTryOnPrompt(personPaths.size, itemPaths.size, preferences)
            CostTokens(
                inputTokens = TokenEstimator.textTokens(prompt) +
                    TokenEstimator.imageInputTokens(personPaths.map { File(it) }) +
                    TokenEstimator.imageInputTokens(itemPaths.map { File(it) }),
                outputTokens = TokenEstimator.IMAGE_OUTPUT_TOKENS,
                outputIsImage = true,
            )
        }
    }
    return tokens
}
