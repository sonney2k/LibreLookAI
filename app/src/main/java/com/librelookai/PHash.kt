package com.librelookai

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Matrix
import android.graphics.Paint
import kotlin.math.cos

/**
 * 64-bit perceptual image hash (pHash, DCT variant), with rotation-aware variants.
 *
 * Used as a third channel in the similarity stack alongside the CNN embedding and the HSV
 * histogram. CNN embeddings tend to drift on rotated/flipped silhouettes (a tilted hanger photo
 * vs. the same garment shot upright), so we precompute pHashes for every 15° rotation of each
 * cutout. A query's pHash is then compared against the best-matching rotation, and that score is
 * folded multiplicatively into the final ranking in [EmbeddingIndex.search].
 */
object PHash {

    /** DCT input resolution. */
    private const val SIZE = 32
    /** Top-left DCT coefficient block — 64 values yields a 64-bit hash. */
    private const val LOW = 8
    /** Bits compared per hash (DC at index 0 is dropped, leaving 63). */
    private const val COMPARE_BITS = LOW * LOW - 1

    /** Rotated pHashes at every [stepDeg] (default 15° → 24 hashes). */
    fun computeRotations(src: Bitmap, stepDeg: Int = 15): LongArray {
        val n = 360 / stepDeg
        val out = LongArray(n)
        for (i in 0 until n) out[i] = compute(src, (i * stepDeg).toFloat())
        return out
    }

    /** pHash of [src] after rotating by [rotationDeg] degrees and resampling to [SIZE]×[SIZE]. */
    fun compute(src: Bitmap, rotationDeg: Float = 0f): Long {
        val gray = renderGrayscale(src, rotationDeg)
        val coeffs = dctTopLeft(gray)
        // Skip DC (coeffs[0]) — it carries average brightness and would dominate the median.
        val sorted = FloatArray(COMPARE_BITS)
        System.arraycopy(coeffs, 1, sorted, 0, COMPARE_BITS)
        java.util.Arrays.sort(sorted)
        val mid = COMPARE_BITS / 2
        val median = if (COMPARE_BITS % 2 == 1) sorted[mid] else (sorted[mid - 1] + sorted[mid]) / 2f
        var hash = 0L
        for (i in 1..COMPARE_BITS) {
            if (coeffs[i] > median) hash = hash or (1L shl (i - 1))
        }
        return hash
    }

    /** Hamming similarity in [0,1]: 1 when identical, 0 when every bit differs. */
    fun similarity(a: Long, b: Long): Float =
        1f - java.lang.Long.bitCount(a xor b).toFloat() / COMPARE_BITS

    /** Highest similarity between [query] and any hash in [gallery]. */
    fun bestSimilarity(query: Long, gallery: LongArray): Float {
        if (gallery.isEmpty()) return 0f
        var bestDist = Int.MAX_VALUE
        for (g in gallery) {
            val d = java.lang.Long.bitCount(query xor g)
            if (d < bestDist) bestDist = d
        }
        return 1f - bestDist.toFloat() / COMPARE_BITS
    }

    private fun renderGrayscale(src: Bitmap, rotationDeg: Float): FloatArray {
        val bm = Bitmap.createBitmap(SIZE, SIZE, Bitmap.Config.ARGB_8888)
        val c = Canvas(bm)
        c.drawColor(Color.BLACK)
        val sw = src.width.toFloat()
        val sh = src.height.toFloat()
        val side = maxOf(sw, sh)
        val scale = SIZE / side
        val m = Matrix().apply {
            postTranslate(-sw / 2f, -sh / 2f)
            postRotate(rotationDeg)
            postScale(scale, scale)
            postTranslate(SIZE / 2f, SIZE / 2f)
        }
        c.drawBitmap(src, m, Paint(Paint.FILTER_BITMAP_FLAG))
        val pixels = IntArray(SIZE * SIZE)
        bm.getPixels(pixels, 0, SIZE, 0, 0, SIZE, SIZE)
        bm.recycle()
        val out = FloatArray(SIZE * SIZE)
        for (i in pixels.indices) {
            val p = pixels[i]
            val r = (p ushr 16) and 0xFF
            val g = (p ushr 8) and 0xFF
            val b = p and 0xFF
            out[i] = 0.299f * r + 0.587f * g + 0.114f * b
        }
        return out
    }

    private val cosTable: Array<FloatArray> = Array(LOW) { u ->
        FloatArray(SIZE) { n -> cos(Math.PI * (n + 0.5) * u / SIZE).toFloat() }
    }

    /** Top-left LOW×LOW coefficients of the 2D DCT-II of a SIZE×SIZE input (separable). */
    private fun dctTopLeft(input: FloatArray): FloatArray {
        val temp = FloatArray(SIZE * LOW) // [y * LOW + u]
        for (y in 0 until SIZE) {
            val row = y * SIZE
            for (u in 0 until LOW) {
                val cu = cosTable[u]
                var s = 0f
                for (n in 0 until SIZE) s += input[row + n] * cu[n]
                temp[y * LOW + u] = s
            }
        }
        val out = FloatArray(LOW * LOW)
        for (v in 0 until LOW) {
            val cv = cosTable[v]
            for (u in 0 until LOW) {
                var s = 0f
                for (n in 0 until SIZE) s += temp[n * LOW + u] * cv[n]
                out[v * LOW + u] = s
            }
        }
        return out
    }
}
