package com.librelookai.ml
import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.util.Log
import com.google.mediapipe.framework.image.BitmapImageBuilder
import com.google.mediapipe.tasks.components.containers.Embedding
import com.google.mediapipe.tasks.core.BaseOptions
import com.google.mediapipe.tasks.vision.core.RunningMode
import com.google.mediapipe.tasks.vision.imageembedder.ImageEmbedder
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.File

/**
 * On-device image embedder (MediaPipe Tasks Vision, EfficientNet Lite0).
 *
 * Single shared instance per process. The underlying [ImageEmbedder] is lazily constructed on first
 * use and reused thereafter. L2-normalization is enabled so cosine similarity reduces to a plain
 * dot product in [EmbeddingIndex.search].
 *
 * Inputs are normalized before embedding: any alpha is composited onto opaque white (so cached
 * cutouts and segmented query images share the same backdrop) and the bitmap is center-cropped to
 * a square so MediaPipe's internal resize-to-224 doesn't squish portrait shots.
 *
 * The model asset lives at `assets/embedder/efficientnet_lite0.tflite`. If it is missing (e.g. dev
 * build without the downloaded model), [embedFile] returns null and [isAvailable] is false — the
 * Shop screen surfaces a one-time dev hint in that case.
 */
class EmbeddingRepository(private val context: Context) {

    private val mutex = Mutex()
    @Volatile private var embedder: ImageEmbedder? = null
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

    /** Result of embedding a bitmap: the L2-normalized CNN embedding and the L1-normalized HSV histogram. */
    data class EmbedResult(val vec: FloatArray, val hist: FloatArray, val pHashes: LongArray)

    /**
     * Embed the image at [file]. The image is composited onto opaque white (collapsing any alpha
     * channel) and center-cropped to a square before being passed to MediaPipe. Returns a
     * [EmbedResult] on success, or null if the model is missing or decoding/embedding failed.
     */
    suspend fun embedFile(file: File): EmbedResult? = withContext(Dispatchers.IO) {
        val bitmap = runCatching {
            BitmapFactory.decodeFile(file.absolutePath)
        }.getOrNull() ?: run {
            Log.w(TAG, "embedFile: decode failed for ${file.absolutePath}")
            return@withContext null
        }
        try {
            embedBitmapInternal(bitmap)
        } finally {
            if (!bitmap.isRecycled) bitmap.recycle()
        }
    }

    /**
     * Embed an in-memory bitmap. The bitmap is composited onto opaque white and center-cropped
     * to a square inside [prepareForEmbedding] before being passed to MediaPipe. The input
     * bitmap is NOT recycled; the caller owns its lifecycle.
     */
    suspend fun embedBitmap(bitmap: Bitmap): EmbedResult? =
        withContext(Dispatchers.IO) {
            embedBitmapInternal(bitmap)
        }

    private suspend fun embedBitmapInternal(src: Bitmap): EmbedResult? {
        val emb = ensureEmbedder() ?: return null
        val prepared = prepareForEmbedding(src) ?: return null
        return try {
            val mpImage = BitmapImageBuilder(prepared).build()
            val result = emb.embed(mpImage)
            val embedding: Embedding? = result.embeddingResult().embeddings().firstOrNull()
            val vec = embedding?.floatEmbedding() ?: return null
            // Histogram is computed on the same prepared bitmap so query and gallery share
            // identical preprocessing (composite-on-white + center-crop).
            val rawHist = ColorHistogram.compute(prepared)
            val hist = ColorHistogram.smoothH(rawHist, sigma = 1f)
            // Rotation-aware perceptual hashes (every 15° → 24 longs). Used as a third channel
            // in similarity scoring so a tilted/upside-down photo still matches its upright twin.
            val pHashes = PHash.computeRotations(prepared)
            EmbedResult(vec, hist, pHashes)
        } catch (t: Throwable) {
            Log.w(TAG, "embed failed", t)
            null
        } finally {
            if (prepared !== src && !prepared.isRecycled) prepared.recycle()
        }
    }

    /** Releases native resources. Safe to call multiple times. */
    suspend fun close() {
        mutex.withLock {
            runCatching { embedder?.close() }
            embedder = null
            initTried = false
        }
    }

    private suspend fun ensureEmbedder(): ImageEmbedder? {
        embedder?.let { return it }
        return mutex.withLock {
            embedder?.let { return@withLock it }
            if (initTried) return@withLock null
            initTried = true
            if (!isAvailable()) {
                Log.w(TAG, "Embedder model missing at assets/$MODEL_PATH")
                return@withLock null
            }
            runCatching {
                val base = BaseOptions.builder()
                    .setModelAssetPath(MODEL_PATH)
                    .build()
                val options = ImageEmbedder.ImageEmbedderOptions.builder()
                    .setBaseOptions(base)
                    .setRunningMode(RunningMode.IMAGE)
                    .setL2Normalize(true)
                    .setQuantize(false)
                    .build()
                ImageEmbedder.createFromOptions(context, options)
            }.onFailure {
                Log.e(TAG, "Failed to create ImageEmbedder", it)
            }.getOrNull().also { embedder = it }
        }
    }

    companion object {
        private const val TAG = "EmbeddingRepository"
        /** Path inside `assets/`. */
        const val MODEL_PATH = "embedder/efficientnet_lite0.tflite"

        /**
         * Prepare a bitmap for the embedder: composite onto opaque white, then center-crop to a
         * square. We composite unconditionally — `Bitmap.hasAlpha()` is unreliable across the
         * decode/encode cycle (PNGs round-tripped through Android's encoder may report `false`
         * even when the file contains transparent pixels), and the operation is idempotent for
         * fully-opaque bitmaps. Returns null only on degenerate inputs.
         *
         * Self-heal for legacy cutouts saved without alpha: walks the pixel buffer and treats any
         * pixel that is either fully transparent OR pure-black (RGB ≤ 4 with alpha == 255) as
         * background. Freshly-segmented items are already opaque black on background pixels;
         * this ensures the backdrop stays uniform for the embedder.
         */
        fun prepareForEmbedding(src: Bitmap): Bitmap? {
            if (src.isRecycled || src.width <= 0 || src.height <= 0) return null

            val w = src.width
            val h = src.height
            val srcPixels = IntArray(w * h)
            try {
                src.getPixels(srcPixels, 0, w, 0, 0, w, h)
            } catch (_: Throwable) {
                // hardware-config bitmap or similar — fall back to a Canvas composite.
                val out = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
                val c = Canvas(out)
                c.drawColor(Color.BLACK)
                c.drawBitmap(src, 0f, 0f, null)
                return centerCropSquare(out)
            }
            // Decide which signal indicates "background" for this image. If any pixel is partially
            // transparent we trust the alpha channel; otherwise we look for pure-black pixels
            // which are used as the background filler.
            var transparentCount = 0
            var pureBlackOpaqueCount = 0
            for (p in srcPixels) {
                val a = (p ushr 24) and 0xFF
                if (a < 250) transparentCount++
                else if ((p and 0x00FFFFFF) == 0) pureBlackOpaqueCount++
            }
            val sawAlpha = transparentCount > 0
            Log.d(TAG, "prepareForEmbedding: ${w}x${h} hasAlpha=${src.hasAlpha()} config=${src.config} transparent=$transparentCount pureBlackOpaque=$pureBlackOpaqueCount → mode=${if (sawAlpha) "alpha" else "blackKey"}")
            val blackARGB = Color.BLACK
            if (sawAlpha) {
                for (i in srcPixels.indices) {
                    if (((srcPixels[i] ushr 24) and 0xFF) < 250) srcPixels[i] = blackARGB
                }
            } else {
                // Background is already black, but this handles any near-black or alpha edge cases.
                for (i in srcPixels.indices) {
                    val p = srcPixels[i]
                    if ((p and 0x00FFFFFF) == 0) srcPixels[i] = blackARGB
                }
            }
            val composited = Bitmap.createBitmap(srcPixels, w, h, Bitmap.Config.ARGB_8888)
            return centerCropSquare(composited)
        }

        private fun centerCropSquare(bm: Bitmap): Bitmap {
            val side = minOf(bm.width, bm.height)
            if (bm.width == side && bm.height == side) return bm
            val x = (bm.width - side) / 2
            val y = (bm.height - side) / 2
            val cb = Bitmap.createBitmap(bm, x, y, side, side)
            if (bm !== cb) bm.recycle()
            return cb
        }
    }
}
