package com.librelookai.gemini

import android.graphics.BitmapFactory
import java.io.File
import kotlin.math.ceil
import kotlin.math.roundToInt

/**
 * Local, no-network token estimates for the pre-tap BYOK cost badge. Mirrors how
 * [GeminiRepository] assembles each request so the € badge reflects the *actual* payload the
 * user is about to send, not a fixed per-action guess. Never billed — these are display-only
 * approximations; the ledger always records Gemini's real `usageMetadata` after the call.
 *
 * Heuristics (good enough for a glance):
 *  - **Text**: ~4 chars / token — Gemini's average for Latin scripts.
 *  - **Image input**: Gemini tiles an image into 768² crops billed at [IMAGE_TILE_TOKENS] each;
 *    an image whose longest side is ≤ [SMALL_IMAGE_DIM] costs a single tile. [GeminiRepository]
 *    caps the longest side at [UPLOAD_MAX_DIM] before upload, so we apply the same cap first.
 *  - **Image output**: a generated image bills a flat [IMAGE_OUTPUT_TOKENS].
 */
object TokenEstimator {
    /** Matches `GeminiRepository.readAndResizeBase64`'s longest-side cap. */
    const val UPLOAD_MAX_DIM = 1280
    /** A generated image's flat output-token cost (Gemini image models). */
    const val IMAGE_OUTPUT_TOKENS = 1290

    private const val TILE = 768
    private const val IMAGE_TILE_TOKENS = 258
    private const val SMALL_IMAGE_DIM = 384

    /** ~4 chars per token. Returns 0 for empty text. */
    fun textTokens(text: String): Int =
        if (text.isEmpty()) 0 else ceil(text.length / 4.0).toInt()

    /** Input tokens for an image of the given source dimensions (cap + tiling applied here). */
    fun imageInputTokens(width: Int, height: Int): Int {
        if (width <= 0 || height <= 0) return IMAGE_TILE_TOKENS
        var w = width
        var h = height
        val longest = maxOf(w, h)
        if (longest > UPLOAD_MAX_DIM) {
            val scale = UPLOAD_MAX_DIM.toDouble() / longest
            w = (w * scale).roundToInt()
            h = (h * scale).roundToInt()
        }
        if (maxOf(w, h) <= SMALL_IMAGE_DIM) return IMAGE_TILE_TOKENS
        val tilesW = ceil(w / TILE.toDouble()).toInt().coerceAtLeast(1)
        val tilesH = ceil(h / TILE.toDouble()).toInt().coerceAtLeast(1)
        return tilesW * tilesH * IMAGE_TILE_TOKENS
    }

    /** Decodes only the bounds of [file] (no full bitmap) to size its input tokens. */
    fun imageInputTokens(file: File): Int {
        if (!file.exists()) return IMAGE_TILE_TOKENS
        val opts = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(file.absolutePath, opts)
        return imageInputTokens(opts.outWidth, opts.outHeight)
    }

    /** Sum of input tokens for several image [files]. */
    fun imageInputTokens(files: List<File>): Int = files.sumOf { imageInputTokens(it) }
}

/**
 * Pre-computed token counts for one upcoming Gemini call, handed to [com.librelookai.billing.CostBadge]
 * so the BYOK € badge prices the real payload. `outputIsImage` selects the image-out vs text-out rate.
 */
data class CostTokens(
    val inputTokens: Int,
    val outputTokens: Int,
    val outputIsImage: Boolean,
)
