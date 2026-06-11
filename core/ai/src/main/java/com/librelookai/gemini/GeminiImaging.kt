package com.librelookai.gemini

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import java.io.File
import kotlin.math.roundToInt

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

data class CutoutIssues(
    val hasBlackBackground: Boolean,
    val hasGreenHalo: Boolean,
) {
    val any: Boolean get() = hasBlackBackground || hasGreenHalo
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
fun detectCutoutIssues(file: File): CutoutIssues {
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

    return CutoutIssues(hasBlackBg, hasGreenHalo)
}

/** Per-action toggles for [fixCutoutBackground]. Each pixel transformation is independently
 *  gated so the UI can expose them as separate switches. */
data class CutoutFixActions(
    val blackToAlpha: Boolean,
    val despillGreen: Boolean,
    val feather: Boolean,
    val tightCrop: Boolean,
    val clearAlpha: Boolean = false,
) {
    val any: Boolean get() = clearAlpha || blackToAlpha || despillGreen || feather || tightCrop
}

/** Applies the enabled passes from [actions]. Output is written as PNG. */
fun fixCutoutBackground(input: File, output: File, actions: CutoutFixActions) {
    val src = BitmapFactory.decodeFile(input.absolutePath)
        ?: run { input.copyTo(output, overwrite = true); return }
    val mutable = src.copy(Bitmap.Config.ARGB_8888, true)
    if (mutable !== src) src.recycle()
    val w = mutable.width
    val h = mutable.height
    val px = IntArray(w * h)
    mutable.getPixels(px, 0, w, 0, 0, w, h)
    if (actions.clearAlpha) {
        for (i in px.indices) px[i] = px[i] or 0xFF000000.toInt()
    }
    if (actions.blackToAlpha) blackBackgroundToAlphaInPlace(px, w, h)
    if (actions.despillGreen) despillGreenInPlace(px)
    if (actions.feather) featherAlphaEdgesInPlace(px, w, h)
    mutable.setPixels(px, 0, w, 0, 0, w, h)
    mutable.setHasAlpha(true)
    val finalBmp = if (actions.tightCrop) cropAndCap(mutable) else mutable
    com.librelookai.util.ImageEncoding.compressCutout(finalBmp, output)
    finalBmp.recycle()
}
