package com.librelookai

import android.graphics.Bitmap
import android.graphics.Color
import kotlin.math.exp
import kotlin.math.sqrt

/**
 * HSV color histogram used as a perceptual color channel for similarity scoring.
 *
 * The CNN embedder is great at shape/texture but tends to wash out hue, so red and black items
 * frequently collide in embedding space. Mixing in a hue-aware histogram fixes that without
 * derailing the rest of the score; see `EmbeddingIndex.search` for the combined formula.
 *
 * Bins: H=12 × S=4 × V=4 = 192 floats, L1-normalized. We deliberately skip pixels that look like
 * the composite background — fully transparent, near-black (the backdrop `prepareForEmbedding`
 * fills in), or near-white (legacy cutouts that were composited on white) — so the histogram
 * describes the garment, not the shared backdrop.
 */
const val HIST_H_BINS = 12
const val HIST_S_BINS = 4
const val HIST_V_BINS = 4
const val HIST_DIM = HIST_H_BINS * HIST_S_BINS * HIST_V_BINS

object ColorHistogram {

    /**
     * Compute the L1-normalized HSV histogram of [bitmap]. Returns a uniform distribution if no
     * usable pixels remain after filtering — that way callers don't have to special-case empty.
     */
    fun compute(bitmap: Bitmap): FloatArray {
        val w = bitmap.width
        val h = bitmap.height
        if (w <= 0 || h <= 0) return uniform()
        val pixels = IntArray(w * h)
        bitmap.getPixels(pixels, 0, w, 0, 0, w, h)
        val hist = FloatArray(HIST_DIM)
        var kept = 0
        val hsv = FloatArray(3)
        for (p in pixels) {
            val a = (p ushr 24) and 0xFF
            if (a < 200) continue
            val r = (p ushr 16) and 0xFF
            val g = (p ushr 8) and 0xFF
            val b = p and 0xFF
            // Drop the composite-on-black backdrop that `prepareForEmbedding` paints in. Without
            // this every histogram is dominated by the (h=0,s=0,v=0) bin and unrelated garments
            // score >0.9 against each other.
            if (r <= 4 && g <= 4 && b <= 4) continue
            Color.RGBToHSV(r, g, b, hsv)
            // Also drop near-white in case we ever hit a legacy composite-on-white path.
            if (hsv[1] < 0.08f && hsv[2] > 0.92f) continue
            val hb = ((hsv[0] / 360f) * HIST_H_BINS).toInt().coerceIn(0, HIST_H_BINS - 1)
            val sb = (hsv[1] * HIST_S_BINS).toInt().coerceIn(0, HIST_S_BINS - 1)
            val vb = (hsv[2] * HIST_V_BINS).toInt().coerceIn(0, HIST_V_BINS - 1)
            hist[(hb * HIST_S_BINS + sb) * HIST_V_BINS + vb] += 1f
            kept++
        }
        if (kept == 0) return uniform()
        val inv = 1f / kept
        for (i in hist.indices) hist[i] *= inv
        return hist
    }

    /**
     * Convolve the H axis with a circular Gaussian (σ in bins). Lets near-hues count as similar
     * under cosine — without this, hue bin 0 and hue bin 1 are orthogonal and a slight color shift
     * tanks the score.
     */
    fun smoothH(hist: FloatArray, sigma: Float = 1f): FloatArray {
        if (sigma <= 0f || hist.size != HIST_DIM) return hist
        val radius = (sigma * 3f).toInt().coerceAtLeast(1)
        val kernel = FloatArray(2 * radius + 1)
        var ksum = 0f
        for (i in -radius..radius) {
            val v = exp(-(i * i) / (2f * sigma * sigma))
            kernel[i + radius] = v
            ksum += v
        }
        for (i in kernel.indices) kernel[i] /= ksum
        val out = FloatArray(HIST_DIM)
        for (sb in 0 until HIST_S_BINS) {
            for (vb in 0 until HIST_V_BINS) {
                for (hb in 0 until HIST_H_BINS) {
                    var acc = 0f
                    for (k in -radius..radius) {
                        val src = ((hb + k) % HIST_H_BINS + HIST_H_BINS) % HIST_H_BINS
                        acc += hist[(src * HIST_S_BINS + sb) * HIST_V_BINS + vb] * kernel[k + radius]
                    }
                    out[(hb * HIST_S_BINS + sb) * HIST_V_BINS + vb] = acc
                }
            }
        }
        return out
    }

    /** Cosine similarity. Returns 0 if either vector is all-zero. */
    fun cosine(a: FloatArray, b: FloatArray): Float {
        if (a.size != b.size) return 0f
        var dot = 0f; var na = 0f; var nb = 0f
        for (i in a.indices) {
            dot += a[i] * b[i]; na += a[i] * a[i]; nb += b[i] * b[i]
        }
        val denom = sqrt(na) * sqrt(nb)
        return if (denom <= 0f) 0f else dot / denom
    }

    /**
     * Histogram intersection ∈ [0,1] for L1-normalized histograms (sum of per-bin minimums).
     * Unlike cosine, this directly measures shared probability mass and is robust against the
     * sparse-bin pattern these HSV histograms produce.
     */
    fun intersection(a: FloatArray, b: FloatArray): Float {
        if (a.size != b.size) return 0f
        var s = 0f
        for (i in a.indices) s += if (a[i] < b[i]) a[i] else b[i]
        return s.coerceIn(0f, 1f)
    }

    /**
     * Bounding box of the pixels [compute] would keep — i.e. the foreground/garment region after
     * the same alpha + near-black + near-white filters. Returns null if no pixel survives. Used
     * by the similarity debug overlay to show exactly which area drives the histogram.
     */
    fun consideredBbox(bitmap: Bitmap): android.graphics.Rect? {
        val w = bitmap.width
        val h = bitmap.height
        if (w <= 0 || h <= 0) return null
        val pixels = IntArray(w * h)
        bitmap.getPixels(pixels, 0, w, 0, 0, w, h)
        var minX = Int.MAX_VALUE; var minY = Int.MAX_VALUE
        var maxX = Int.MIN_VALUE; var maxY = Int.MIN_VALUE
        val hsv = FloatArray(3)
        for (y in 0 until h) {
            val rowStart = y * w
            for (x in 0 until w) {
                val p = pixels[rowStart + x]
                val a = (p ushr 24) and 0xFF
                if (a < 200) continue
                val r = (p ushr 16) and 0xFF
                val g = (p ushr 8) and 0xFF
                val b = p and 0xFF
                if (r <= 4 && g <= 4 && b <= 4) continue
                Color.RGBToHSV(r, g, b, hsv)
                if (hsv[1] < 0.08f && hsv[2] > 0.92f) continue
                if (x < minX) minX = x
                if (y < minY) minY = y
                if (x > maxX) maxX = x
                if (y > maxY) maxY = y
            }
        }
        if (minX == Int.MAX_VALUE) return null
        return android.graphics.Rect(minX, minY, maxX + 1, maxY + 1)
    }

    private fun uniform(): FloatArray = FloatArray(HIST_DIM) { 1f / HIST_DIM }
}
