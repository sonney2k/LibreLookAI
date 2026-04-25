package com.librelookai

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

    /**
     * Embed the image at [file]. The image is composited onto opaque white (collapsing any alpha
     * channel) and center-cropped to a square before being passed to MediaPipe. Returns a
     * L2-normalized `FloatArray` on success, or null if the model is missing or decoding/embedding
     * failed.
     */
    suspend fun embedFile(file: File): FloatArray? = withContext(Dispatchers.IO) {
        val bitmap = runCatching {
            BitmapFactory.decodeFile(file.absolutePath)
        }.getOrNull() ?: run {
            Log.w(TAG, "embedFile: decode failed for ${file.absolutePath}")
            return@withContext null
        }
        try {
            embedBitmapInternal(bitmap, compositeAlpha = bitmap.hasAlpha())
        } finally {
            if (!bitmap.isRecycled) bitmap.recycle()
        }
    }

    /**
     * Embed an in-memory bitmap. Caller is responsible for any prior preparation (e.g. running
     * [SegmentationRepository] over a query). The bitmap is still center-cropped to a square here.
     * The input bitmap is NOT recycled; the caller owns its lifecycle.
     *
     * Set [compositeAlpha] to true if the input may have transparent pixels that should be
     * composited onto white before embedding.
     */
    suspend fun embedBitmap(bitmap: Bitmap, compositeAlpha: Boolean = false): FloatArray? =
        withContext(Dispatchers.IO) {
            embedBitmapInternal(bitmap, compositeAlpha)
        }

    private suspend fun embedBitmapInternal(src: Bitmap, compositeAlpha: Boolean): FloatArray? {
        val emb = ensureEmbedder() ?: return null
        val prepared = prepareForEmbedding(src, compositeAlpha) ?: return null
        return try {
            val mpImage = BitmapImageBuilder(prepared).build()
            val result = emb.embed(mpImage)
            val embedding: Embedding? = result.embeddingResult().embeddings().firstOrNull()
            embedding?.floatEmbedding()
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
         * Prepare a bitmap for the embedder: optionally composite alpha onto opaque white, then
         * center-crop to a square. Returns the input unchanged when no work was needed (caller
         * must check identity before recycling).
         */
        fun prepareForEmbedding(src: Bitmap, compositeAlpha: Boolean): Bitmap? {
            if (src.isRecycled || src.width <= 0 || src.height <= 0) return null

            val composited: Bitmap = if (compositeAlpha) {
                val out = Bitmap.createBitmap(src.width, src.height, Bitmap.Config.ARGB_8888)
                val c = Canvas(out)
                c.drawColor(Color.WHITE)
                c.drawBitmap(src, 0f, 0f, null)
                out
            } else {
                src
            }

            val side = minOf(composited.width, composited.height)
            val x = (composited.width - side) / 2
            val y = (composited.height - side) / 2
            val cropped = if (composited.width == side && composited.height == side) {
                composited
            } else {
                val cb = Bitmap.createBitmap(composited, x, y, side, side)
                if (composited !== src && composited !== cb) composited.recycle()
                cb
            }
            return cropped
        }
    }
}
