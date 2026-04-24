package com.librelookai

import android.content.Context
import android.graphics.BitmapFactory
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
 * On-device image embedder (MediaPipe Tasks Vision, MobileNetV3-Small).
 *
 * Single shared instance per process. The underlying [ImageEmbedder] is lazily constructed on first
 * use and reused thereafter. L2-normalization is enabled so cosine similarity reduces to a plain
 * dot product in [EmbeddingIndex.search].
 *
 * The model asset lives at `assets/embedder/mobilenet_v3_small.tflite`. If it is missing (e.g. dev
 * build without the downloaded model), [embed] returns null and [isAvailable] is false — the Shop
 * screen surfaces a one-time dev hint in that case.
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
     * Embed the image at [file]. Returns a L2-normalized `FloatArray` (length = [EMBED_DIM]) on
     * success, or null if the model is missing or decoding/embedding failed.
     */
    suspend fun embed(file: File): FloatArray? = withContext(Dispatchers.IO) {
        val emb = ensureEmbedder() ?: return@withContext null
        val bitmap = runCatching {
            BitmapFactory.decodeFile(file.absolutePath)
        }.getOrNull() ?: run {
            Log.w(TAG, "embed: decode failed for ${file.absolutePath}")
            return@withContext null
        }
        try {
            val mpImage = BitmapImageBuilder(bitmap).build()
            val result = emb.embed(mpImage)
            val embedding: Embedding? = result.embeddingResult().embeddings().firstOrNull()
            embedding?.floatEmbedding()
        } catch (t: Throwable) {
            Log.w(TAG, "embed failed", t)
            null
        } finally {
            if (!bitmap.isRecycled) bitmap.recycle()
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
        const val MODEL_PATH = "embedder/mobilenet_v3_small.tflite"
        /** MobileNetV3-Small output width. Used for sanity checks only — runtime reads dim from each vector. */
        const val EMBED_DIM = 1024
    }
}
