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
     * with foreground pixels preserved and background pixels set to opaque white.
     *
     * Returns null if the model is missing or segmentation fails — callers can fall back to the
     * source bitmap.
     */
    suspend fun segmentForegroundOnWhite(src: Bitmap): Bitmap? = withContext(Dispatchers.IO) {
        val seg = ensureSegmenter() ?: return@withContext null
        // MediaPipe requires ARGB_8888.
        val rgb = if (src.config == Bitmap.Config.ARGB_8888) src
            else src.copy(Bitmap.Config.ARGB_8888, false)
        val mp = BitmapImageBuilder(rgb).build()
        val roi = InteractiveSegmenter.RegionOfInterest.create(
            NormalizedKeypoint.create(0.5f, 0.5f)
        )
        val maskBytes = try {
            val result = seg.segment(mp, roi)
            val mask = result.categoryMask().get()
            ByteBufferExtractor.extract(mask)
        } catch (t: Throwable) {
            Log.w(TAG, "segment failed", t)
            if (rgb !== src) rgb.recycle()
            return@withContext null
        }

        val w = rgb.width
        val h = rgb.height
        val pixels = IntArray(w * h)
        rgb.getPixels(pixels, 0, w, 0, 0, w, h)

        // Magic Touch produces a category mask: 0 = foreground, 1 = background (per MP docs).
        // We tolerate either polarity: treat the value at the center keypoint as "foreground".
        maskBytes.rewind()
        val centerIdx = (h / 2) * w + (w / 2)
        if (centerIdx < 0 || centerIdx >= maskBytes.limit()) {
            if (rgb !== src) rgb.recycle()
            return@withContext null
        }
        val fgValue = maskBytes.get(centerIdx).toInt() and 0xFF
        maskBytes.rewind()

        val white = Color.WHITE
        var fgPixelCount = 0
        for (i in 0 until pixels.size) {
            val v = maskBytes.get(i).toInt() and 0xFF
            if (v == fgValue) fgPixelCount++ else pixels[i] = white
        }

        if (rgb !== src) rgb.recycle()

        // Sanity check: if the mask claims <2% foreground, it's almost certainly bogus
        // (e.g. the model couldn't find anything at the center). Bail out so the caller falls
        // back to the un-segmented image.
        val totalPixels = w * h
        if (fgPixelCount * 100 < totalPixels * 2) {
            Log.w(TAG, "segmentation produced ${fgPixelCount}/$totalPixels foreground pixels — discarding")
            return@withContext null
        }

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
                val options = InteractiveSegmenter.InteractiveSegmenterOptions.builder()
                    .setBaseOptions(base)
                    .setOutputCategoryMask(true)
                    .setOutputConfidenceMasks(false)
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
