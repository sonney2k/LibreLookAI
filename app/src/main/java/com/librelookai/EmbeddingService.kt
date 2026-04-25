package com.librelookai

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

/**
 * Process-wide singleton for the on-device similarity stack.
 *
 * Wraps a single [EmbeddingRepository] (MediaPipe ImageEmbedder), [SegmentationRepository]
 * (Magic Touch interactive segmenter), and persistent [EmbeddingIndex]. Every wardrobe
 * similarity feature — Shopping helper, capture-time dedupe, find-by-photo, Repair & Sync
 * duplicates, folder-import preview — funnels through this object so the underlying models
 * are loaded once per process.
 *
 * Call [init] once from [MainActivity.onCreate] (or any boot path) before any feature uses
 * the service. After that, use [findSimilar] for off-disk queries (segments + embeds) and
 * [syncIndex] to keep the wardrobe index up to date.
 */
object EmbeddingService {

    private lateinit var embedderImpl: EmbeddingRepository
    private lateinit var segmenterImpl: SegmentationRepository
    private lateinit var indexImpl: EmbeddingIndex
    @Volatile private var initialized = false

    fun init(context: Context) {
        if (initialized) return
        synchronized(this) {
            if (initialized) return
            val app = context.applicationContext
            embedderImpl = EmbeddingRepository(app)
            segmenterImpl = SegmentationRepository(app)
            indexImpl = EmbeddingIndex(app)
            initialized = true
        }
    }

    val embedder: EmbeddingRepository get() { check(initialized) { "EmbeddingService.init() not called" }; return embedderImpl }
    val segmenter: SegmentationRepository get() { check(initialized) { "EmbeddingService.init() not called" }; return segmenterImpl }
    val index: EmbeddingIndex get() { check(initialized) { "EmbeddingService.init() not called" }; return indexImpl }

    fun isModelAvailable(): Boolean = initialized && embedderImpl.isAvailable()

    val indexSize: Int get() = if (initialized) indexImpl.size else 0

    /** One ranked match: a wardrobe Drive ID with its cosine similarity in [-1, 1]. */
    data class Match(val driveId: String, val score: Float)

    /**
     * Ensures every cutout in [images] that has a local file has an embedding in the index.
     * Drops index entries for items not in [images]. Safe to call repeatedly.
     */
    suspend fun syncIndex(
        images: List<DriveImage>,
        cacheDir: File,
        onProgress: ((done: Int, total: Int) -> Unit)? = null,
    ): Int {
        if (!isModelAvailable()) return 0
        indexImpl.load()
        val keep = images.map { it.driveId }.toSet()
        indexImpl.retainIds(keep)
        val todo = withContext(Dispatchers.IO) {
            images.filter { img -> cutoutFile(img, cacheDir) != null && !indexImpl.contains(img.driveId) }
        }
        if (todo.isEmpty()) return 0
        var done = 0
        for (img in todo) {
            val f = cutoutFile(img, cacheDir) ?: continue
            embedderImpl.embedFile(f)?.let { indexImpl.upsert(img.driveId, it) }
            done++
            onProgress?.invoke(done, todo.size)
        }
        indexImpl.save()
        return done
    }

    /**
     * Embed [file] (segmenting first when [segment] is true, to isolate the foreground item)
     * and return all index matches with score ≥ [threshold] minus [excludeIds], capped at
     * [topK] entries.
     *
     * Set [segment] to false when the source image is already a transparent cutout — running
     * the segmenter over a clothes-on-white silhouette tends to over-crop and is unnecessary
     * since [EmbeddingRepository] composites alpha onto white anyway.
     */
    suspend fun findSimilar(
        file: File,
        threshold: Float,
        excludeIds: Set<String> = emptySet(),
        topK: Int = 20,
        segment: Boolean = true,
    ): List<Match> {
        val vec = embedQuery(file, segment) ?: return emptyList()
        return searchVector(vec, threshold, excludeIds, topK)
    }

    /**
     * Like [findSimilar] but operates on an in-memory bitmap (no segmentation). Caller owns
     * the bitmap's lifecycle.
     */
    suspend fun findSimilarBitmap(
        bitmap: Bitmap,
        threshold: Float,
        excludeIds: Set<String> = emptySet(),
        topK: Int = 20,
    ): List<Match> {
        if (!isModelAvailable()) return emptyList()
        val vec = embedderImpl.embedBitmap(bitmap, compositeAlpha = bitmap.hasAlpha()) ?: return emptyList()
        return searchVector(vec, threshold, excludeIds, topK)
    }

    /**
     * Embed [file] without searching. Useful when a caller wants to embed once and use the
     * vector for both indexing and ad-hoc comparisons (e.g. Repair & Sync flagging duplicates
     * within the wardrobe itself).
     */
    suspend fun embedQuery(file: File, segment: Boolean = true): FloatArray? = withContext(Dispatchers.IO) {
        if (!isModelAvailable()) return@withContext null
        if (!segment) return@withContext embedderImpl.embedFile(file)
        val raw = runCatching { BitmapFactory.decodeFile(file.absolutePath) }.getOrNull() ?: return@withContext null
        try {
            val masked = segmenterImpl.segmentForegroundOnWhite(raw)
            if (masked != null) {
                try { embedderImpl.embedBitmap(masked, compositeAlpha = false) }
                finally { if (!masked.isRecycled) masked.recycle() }
            } else {
                Log.w(TAG, "embedQuery: segmentation unavailable, falling back to raw image")
                embedderImpl.embedFile(file)
            }
        } finally {
            if (!raw.isRecycled) raw.recycle()
        }
    }

    /** Search the index with an already-computed embedding [vec]. */
    suspend fun searchVector(
        vec: FloatArray,
        threshold: Float,
        excludeIds: Set<String> = emptySet(),
        topK: Int = 20,
    ): List<Match> {
        if (!isModelAvailable()) return emptyList()
        indexImpl.load()
        return indexImpl.search(vec, topK + excludeIds.size)
            .asSequence()
            .filter { it.id !in excludeIds }
            .filter { it.score >= threshold }
            .take(topK)
            .map { Match(it.id, it.score) }
            .toList()
    }

    suspend fun close() {
        if (!initialized) return
        embedderImpl.close()
        segmenterImpl.close()
    }

    private fun cutoutFile(img: DriveImage, cacheDir: File): File? {
        File(cacheDir, "${img.driveId}.png").takeIf { it.exists() }?.let { return it }
        File(cacheDir, "${img.driveId}.jpg").takeIf { it.exists() }?.let { return it }
        return null
    }

    private const val TAG = "EmbeddingService"
}
