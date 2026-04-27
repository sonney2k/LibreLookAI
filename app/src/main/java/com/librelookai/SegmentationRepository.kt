package com.librelookai

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Color
import android.util.Log
import com.google.mediapipe.framework.image.BitmapImageBuilder
import com.google.mediapipe.framework.image.ByteBufferExtractor
import com.google.mediapipe.tasks.components.containers.NormalizedKeypoint
import com.google.mediapipe.tasks.core.BaseOptions
import com.google.mediapipe.tasks.vision.interactivesegmenter.InteractiveSegmenter
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.File

/**
 * On-device foreground segmenter (MediaPipe Tasks Vision, Magic Touch model).
 *
 * Used by the Shopping helper to extract just the clothing item from a live camera shot before
 * embedding, so the query image's background does not dominate the similarity score. The seed
 * keypoint is always the image center — the capture UI shows a crosshair to communicate this.
 *
 * Model asset: `assets/segmenter/magic_touch.tflite`. If missing, [segmentForeground] returns null
 * and callers should fall back to using the un-segmented bitmap composited onto a neutral bg.
 */
class SegmentationRepository(private val context: Context) {

    private val mutex = Mutex()
    @Volatile private var segmenter: InteractiveSegmenter? = null
    @Volatile private var initTried = false
    @Volatile private var modelPresent: Boolean? = null

    /**
     * When non-null, [segmentForegroundOnBlack] writes the exact bitmap it hands MediaPipe and a
     * grayscale visualization of the returned mask into this directory as
     * `segmenter_input_last.png` / `segmenter_mask_last.png`. Used for diagnosing model failures —
     * pull via `adb pull` and upload to the public Magic Touch demo to verify whether the model
     * itself can segment that exact frame.
     */
    @Volatile var debugDumpDir: File? = null

    fun isAvailable(): Boolean {
        val cached = modelPresent
        if (cached != null) return cached
        val present = runCatching {
            context.assets.open(MODEL_PATH).use { it.available() > 0 || true }
        }.getOrDefault(false)
        modelPresent = present
        return present
    }

    /**
     * Run interactive segmentation seeded at the center of [src] and return a new ARGB_8888 bitmap
     * with foreground pixels preserved and background pixels set to opaque black.
     *
     * Returns null if the model is missing or segmentation fails — callers can fall back to the
     * source bitmap.
     */
    suspend fun segmentForegroundOnBlack(src: Bitmap): Bitmap? = withContext(Dispatchers.IO) {
        val seg = ensureSegmenter() ?: return@withContext null
        // MediaPipe requires ARGB_8888.
        val rgb = if (src.config == Bitmap.Config.ARGB_8888) src
            else src.copy(Bitmap.Config.ARGB_8888, false)
        val w = rgb.width
        val h = rgb.height
        Log.d(TAG, "segmentForegroundOnBlack: input bitmap size ${w}x${h}")

        // Dump the bitmap we're about to hand MediaPipe so it can be inspected via adb pull and
        // uploaded to the public demo to confirm whether the model itself works on this frame.
        // Overlay a red circle at the centre seed point so it's obvious whether the seed actually
        // lands on the user's framed object.
        debugDumpDir?.let { dir ->
            runCatching {
                if (!dir.exists()) dir.mkdirs()
                val annotated = rgb.copy(Bitmap.Config.ARGB_8888, true)
                val canvas = android.graphics.Canvas(annotated)
                val paint = android.graphics.Paint().apply {
                    color = Color.RED
                    style = android.graphics.Paint.Style.STROKE
                    strokeWidth = (maxOf(annotated.width, annotated.height) / 200f).coerceAtLeast(3f)
                    isAntiAlias = true
                }
                val crossPaint = android.graphics.Paint().apply {
                    color = Color.RED
                    strokeWidth = (maxOf(annotated.width, annotated.height) / 250f).coerceAtLeast(2f)
                    isAntiAlias = true
                }
                val cx = annotated.width / 2f
                val cy = annotated.height / 2f
                val r = (minOf(annotated.width, annotated.height) / 30f).coerceAtLeast(12f)
                canvas.drawCircle(cx, cy, r, paint)
                canvas.drawLine(cx - r * 1.5f, cy, cx + r * 1.5f, cy, crossPaint)
                canvas.drawLine(cx, cy - r * 1.5f, cx, cy + r * 1.5f, crossPaint)
                val out = File(dir, "segmenter_input_last.png")
                out.outputStream().buffered().use { annotated.compress(Bitmap.CompressFormat.PNG, 100, it) }
                annotated.recycle()
                Log.d(TAG, "segment: dumped input (with seed marker at ${cx.toInt()},${cy.toInt()}) to ${out.absolutePath}")
            }.onFailure { Log.w(TAG, "segment: failed to dump input", it) }
        }

        val mp = BitmapImageBuilder(rgb).build()
        // In some MediaPipe Tasks versions, NormalizedKeypoint is incorrectly treated as absolute
        // pixel coordinates. If 0.5f lands in the top-left, we must use absolute center pixels.
        Log.d(TAG, "segment: ROI seed at ${w / 2f}, ${h / 2f}")
        val roi = InteractiveSegmenter.RegionOfInterest.create(
            listOf(NormalizedKeypoint.create(w / 2f, h / 2f))
        )
        // Magic Touch returns confidence masks as a list of MPImages — one float32 mask per class.
        // For binary foreground/background output that's typically [bg, fg]. We extract both,
        // diagnose, and threshold the foreground mask.
        data class FloatMask(val mw: Int, val mh: Int, val data: FloatArray)
        val masks: List<FloatMask> = try {
            val result = seg.segment(mp, roi)
            val list = result.confidenceMasks().get()
            list.map { mask ->
                val mw = mask.width
                val mh = mask.height
                val bytes = ByteBufferExtractor.extract(mask)
                bytes.order(java.nio.ByteOrder.nativeOrder())
                val fb = bytes.asFloatBuffer()
                val data = FloatArray(mw * mh)
                fb.get(data)
                FloatMask(mw, mh, data)
            }
        } catch (t: Throwable) {
            Log.w(TAG, "segment failed", t)
            if (rgb !== src) rgb.recycle()
            return@withContext null
        }
        if (masks.isEmpty()) {
            Log.w(TAG, "segment: confidenceMasks() returned empty list")
            if (rgb !== src) rgb.recycle()
            return@withContext null
        }

        // Diagnose each mask: sum, min, max, value at the center keypoint. Helps us pick the right
        // class index (the demo's "foreground" class) without guessing.
        masks.forEachIndexed { idx, m ->
            var lo = Float.POSITIVE_INFINITY; var hi = Float.NEGATIVE_INFINITY; var s = 0f
            for (v in m.data) { if (v < lo) lo = v; if (v > hi) hi = v; s += v }
            val cx = (m.mw / 2).coerceIn(0, m.mw - 1)
            val cy = (m.mh / 2).coerceIn(0, m.mh - 1)
            Log.d(TAG, "segment: confidence[$idx] ${m.mw}x${m.mh} min=$lo max=$hi mean=${s / m.data.size} center=${m.data[cy * m.mw + cx]}")
        }

        // Pick the class whose center confidence is highest — that's the class the seed-touched
        // pixel belongs to, i.e. foreground. Falls back to the last mask if center values tie.
        val fgIdx = masks.indices.maxByOrNull { idx ->
            val m = masks[idx]
            val cx = (m.mw / 2).coerceIn(0, m.mw - 1)
            val cy = (m.mh / 2).coerceIn(0, m.mh - 1)
            m.data[cy * m.mw + cx]
        } ?: (masks.size - 1)
        val fgMask = masks[fgIdx]
        Log.d(TAG, "segment: input ${w}x${h} chosen fg mask idx=$fgIdx ${fgMask.mw}x${fgMask.mh}")

        // Dump the chosen foreground confidence as a grayscale PNG, with the same seed circle
        // overlaid so we can verify the mask lines up with where we asked the model to look.
        debugDumpDir?.let { dir ->
            runCatching {
                val viz = IntArray(fgMask.mw * fgMask.mh)
                for (i in viz.indices) {
                    val v = (fgMask.data[i].coerceIn(0f, 1f) * 255f).toInt() and 0xFF
                    viz[i] = (0xFF shl 24) or (v shl 16) or (v shl 8) or v
                }
                val maskBm = Bitmap.createBitmap(viz, fgMask.mw, fgMask.mh, Bitmap.Config.ARGB_8888)
                val mutable = maskBm.copy(Bitmap.Config.ARGB_8888, true).also { maskBm.recycle() }
                val canvas = android.graphics.Canvas(mutable)
                val paint = android.graphics.Paint().apply {
                    color = Color.RED
                    style = android.graphics.Paint.Style.STROKE
                    strokeWidth = (maxOf(fgMask.mw, fgMask.mh) / 200f).coerceAtLeast(2f)
                    isAntiAlias = true
                }
                val cx = fgMask.mw / 2f
                val cy = fgMask.mh / 2f
                val rr = (minOf(fgMask.mw, fgMask.mh) / 30f).coerceAtLeast(8f)
                canvas.drawCircle(cx, cy, rr, paint)
                val out = File(dir, "segmenter_mask_last.png")
                out.outputStream().buffered().use { mutable.compress(Bitmap.CompressFormat.PNG, 100, it) }
                mutable.recycle()
                Log.d(TAG, "segment: dumped mask (with seed marker) to ${out.absolutePath}")
            }.onFailure { Log.w(TAG, "segment: failed to dump mask", it) }
        }

        val pixels = IntArray(w * h)
        rgb.getPixels(pixels, 0, w, 0, 0, w, h)

        val black = Color.BLACK
        var fgPixelCount = 0
        var bgPixelCount = 0
        var bgRSum = 0L; var bgGSum = 0L; var bgBSum = 0L
        val isFg = BooleanArray(pixels.size)
        val threshold = 0.3f
        for (y in 0 until h) {
            val my = (y.toLong() * fgMask.mh / h).toInt().coerceIn(0, fgMask.mh - 1)
            val rowBase = my * fgMask.mw
            val outBase = y * w
            for (x in 0 until w) {
                val mx = (x.toLong() * fgMask.mw / w).toInt().coerceIn(0, fgMask.mw - 1)
                val v = fgMask.data[rowBase + mx]
                val fg = (v >= threshold)
                isFg[outBase + x] = fg
                if (fg) {
                    fgPixelCount++
                } else {
                    val p = pixels[outBase + x]
                    bgRSum += (p ushr 16) and 0xFF
                    bgGSum += (p ushr 8) and 0xFF
                    bgBSum += p and 0xFF
                    bgPixelCount++
                }
            }
        }

        // Sanity bounds: a useful segmentation has both classes well-represented. <2% foreground
        // means the model didn't find anything; >95% means it couldn't isolate the object.
        val totalPixels = w * h
        if (fgPixelCount * 100 < totalPixels * 2) {
            if (rgb !== src) rgb.recycle()
            Log.w(TAG, "segmentation produced ${fgPixelCount}/$totalPixels foreground pixels — discarding")
            return@withContext null
        }
        if (fgPixelCount * 100 > totalPixels * 95) {
            if (rgb !== src) rgb.recycle()
            Log.w(TAG, "segmentation labelled ${fgPixelCount}/$totalPixels (>95%) as foreground — model failed to isolate, discarding")
            return@withContext null
        }
        Log.d(TAG, "segment: foreground=${fgPixelCount}/${totalPixels} background=${bgPixelCount}")

        // Gray-world WB: derive per-channel gains from the background mean (the surface the item
        // was photographed on, treated as a neutral reference). Only apply when the background is
        // both large enough and not extreme exposure, otherwise the reference is unreliable.
        var gainR = 1f; var gainG = 1f; var gainB = 1f
        if (bgPixelCount * 100 >= totalPixels * 5) {
            val meanR = bgRSum.toFloat() / bgPixelCount
            val meanG = bgGSum.toFloat() / bgPixelCount
            val meanB = bgBSum.toFloat() / bgPixelCount
            val meanY = (meanR + meanG + meanB) / 3f
            if (meanY in 40f..220f && meanR > 1f && meanG > 1f && meanB > 1f) {
                gainR = (meanY / meanR).coerceIn(0.5f, 2.0f)
                gainG = (meanY / meanG).coerceIn(0.5f, 2.0f)
                gainB = (meanY / meanB).coerceIn(0.5f, 2.0f)
            }
        }
        val applyWB = gainR != 1f || gainG != 1f || gainB != 1f

        // Second pass: replace background with black; apply WB gains to foreground pixels.
        for (i in 0 until pixels.size) {
            if (!isFg[i]) {
                pixels[i] = black
            } else if (applyWB) {
                val p = pixels[i]
                val a = (p ushr 24) and 0xFF
                val r = (((p ushr 16) and 0xFF) * gainR).toInt().coerceIn(0, 255)
                val g = (((p ushr 8) and 0xFF) * gainG).toInt().coerceIn(0, 255)
                val b = ((p and 0xFF) * gainB).toInt().coerceIn(0, 255)
                pixels[i] = (a shl 24) or (r shl 16) or (g shl 8) or b
            }
        }

        if (rgb !== src) rgb.recycle()

        Bitmap.createBitmap(pixels, w, h, Bitmap.Config.ARGB_8888)
    }

    suspend fun close() {
        mutex.withLock {
            runCatching { segmenter?.close() }
            segmenter = null
            initTried = false
        }
    }

    private suspend fun ensureSegmenter(): InteractiveSegmenter? {
        segmenter?.let { return it }
        return mutex.withLock {
            segmenter?.let { return@withLock it }
            if (initTried) return@withLock null
            initTried = true
            if (!isAvailable()) {
                Log.w(TAG, "Segmenter model missing at assets/$MODEL_PATH")
                return@withLock null
            }
            runCatching {
                val base = BaseOptions.builder()
                    .setModelAssetPath(MODEL_PATH)
                    .build()
                // Magic Touch returns two confidence masks (background, foreground). The category
                // mask path returns a uint8 buffer that — for unclear reasons in this build of the
                // tasks-vision lib — comes back as a uniform 255 fill on the same frame the public
                // demo segments cleanly. Confidence masks give per-pixel float probabilities that
                // we threshold ourselves, sidestepping the broken category-mask path.
                val options = InteractiveSegmenter.InteractiveSegmenterOptions.builder()
                    .setBaseOptions(base)
                    .setOutputCategoryMask(false)
                    .setOutputConfidenceMasks(true)
                    .build()
                InteractiveSegmenter.createFromOptions(context, options)
            }.onFailure {
                Log.e(TAG, "Failed to create InteractiveSegmenter", it)
            }.getOrNull().also { segmenter = it }
        }
    }

    companion object {
        private const val TAG = "SegmentationRepository"
        const val MODEL_PATH = "segmenter/magic_touch.tflite"
    }
}
