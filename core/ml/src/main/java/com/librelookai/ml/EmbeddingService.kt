package com.librelookai.ml
import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Matrix
import android.util.Log
import android.media.ExifInterface
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import com.librelookai.wardrobe.DriveImage

/**
 * Process-wide singleton for the on-device similarity stack.
 *
 * Wraps a single [EmbeddingRepository] (MediaPipe ImageEmbedder), [SegmentationRepository]
 * (Magic Touch interactive segmenter), and persistent [EmbeddingIndex]. Every wardrobe
 * similarity feature — Shopping helper, capture-time dedupe, find-by-photo, Repair & Sync
 * duplicates, folder-import preview — funnels through this object so the underlying models
 * are loaded once per process.
 *
 * Call [init] once from `MainActivity.onCreate` (or any boot path) before any feature uses
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
            // Always dump the last segmentation input + mask for debugging. We write to the
            // *external* cache so `adb pull` works without root:
            //   adb pull /sdcard/Android/data/com.librelookai/cache/segmenter_debug/
            // — then upload `segmenter_input_last.png` to the public Magic Touch demo to verify
            // whether the model itself can segment the exact frame we hand MediaPipe.
            segmenterImpl.debugDumpDir = (app.externalCacheDir ?: app.cacheDir)
                .let { File(it, "segmenter_debug") }
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
     * Full result of a similarity search, including the intermediate artifacts the debug
     * preview needs (processed bitmap path, segmentation flag, histogram, embedding vector).
     * All call sites — Shopping similarity, find-by-photo, import dedupe — go through
     * [findSimilarWithDebug] so they produce identical embeddings and can render the same
     * debug breakdown.
     */
    data class SimilarityResult(
        val matches: List<Match>,
        /** Absolute path of the processed PNG actually fed to the embedder, or null when
         *  no [processedOutputDir] was supplied. */
        val processedPath: String?,
        /** True when on-device segmentation isolated a foreground object; false on fallback. */
        val segmented: Boolean,
        /** L1-normalized HSV histogram of the processed bitmap. */
        val hist: FloatArray,
        /** L2-normalized embedding vector of the processed bitmap. */
        val vec: FloatArray,
        /** Canonical (0°) perceptual hash of the processed bitmap. The debug preview reports its
         *  best-rotation Hamming similarity against each indexed match. */
        val pHash: Long,
    )

    /**
     * Decode [file] and apply any EXIF rotation so the returned bitmap is in the orientation a
     * human would see it (upright portrait when the photo was taken in portrait mode). Critical
     * for the segmenter path: feeding a sideways bitmap to MediaPipe means the centre seed lands
     * on the wrong pixel and the segmentation tracks a random patch of the background instead of
     * the user's framed object. Returns null on decode failure.
     */
    fun decodeUpright(file: File): Bitmap? {
        val raw = runCatching { BitmapFactory.decodeFile(file.absolutePath) }.getOrNull() ?: return null
        val orientation = runCatching {
            ExifInterface(file.absolutePath).getAttributeInt(
                ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_NORMAL,
            )
        }.getOrDefault(ExifInterface.ORIENTATION_NORMAL)
        if (orientation == ExifInterface.ORIENTATION_NORMAL || orientation == ExifInterface.ORIENTATION_UNDEFINED) return raw
        val matrix = Matrix()
        when (orientation) {
            ExifInterface.ORIENTATION_ROTATE_90 -> matrix.postRotate(90f)
            ExifInterface.ORIENTATION_ROTATE_180 -> matrix.postRotate(180f)
            ExifInterface.ORIENTATION_ROTATE_270 -> matrix.postRotate(270f)
            ExifInterface.ORIENTATION_FLIP_HORIZONTAL -> matrix.preScale(-1f, 1f)
            ExifInterface.ORIENTATION_FLIP_VERTICAL -> matrix.preScale(1f, -1f)
            ExifInterface.ORIENTATION_TRANSPOSE -> { matrix.preScale(-1f, 1f); matrix.postRotate(90f) }
            ExifInterface.ORIENTATION_TRANSVERSE -> { matrix.preScale(-1f, 1f); matrix.postRotate(-90f) }
            else -> return raw
        }
        Log.d(TAG, "decodeUpright: applied EXIF orientation $orientation to ${file.name} (${raw.width}x${raw.height})")
        return Bitmap.createBitmap(raw, 0, 0, raw.width, raw.height, matrix, true)
            .also { if (it !== raw) raw.recycle() }
    }

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
            embedderImpl.embedFile(f)?.let { indexImpl.upsert(img.driveId, it.vec, it.hist, it.pHashes) }
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
        val q = embedQuery(file, segment) ?: return emptyList()
        return searchVector(q.vec, q.hist, q.pHashes.firstOrNull() ?: 0L, threshold, excludeIds, topK)
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
        val q = embedderImpl.embedBitmap(bitmap) ?: return emptyList()
        return searchVector(q.vec, q.hist, q.pHashes.firstOrNull() ?: 0L, threshold, excludeIds, topK)
    }

    /**
     * Embed [file] without searching. Useful when a caller wants to embed once and use the
     * vector for both indexing and ad-hoc comparisons (e.g. Repair & Sync flagging duplicates
     * within the wardrobe itself).
     */
    suspend fun embedQuery(file: File, segment: Boolean = true): EmbeddingRepository.EmbedResult? = withContext(Dispatchers.IO) {
        if (!isModelAvailable()) return@withContext null
        if (!segment) return@withContext embedderImpl.embedFile(file)
        val raw = decodeUpright(file) ?: return@withContext null
        try {
            val masked = segmenterImpl.segmentForegroundOnBlack(raw)
            if (masked != null) {
                try { embedderImpl.embedBitmap(masked) }
                finally { if (!masked.isRecycled) masked.recycle() }
            } else {
                Log.w(TAG, "embedQuery: segmentation unavailable, falling back to raw image")
                embedderImpl.embedFile(file)
            }
        } finally {
            if (!raw.isRecycled) raw.recycle()
        }
    }

    /**
     * Unified entry point for off-disk similarity queries that need the same intermediate
     * artifacts the debug preview consumes. Decodes [file] upright, runs interactive
     * segmentation (with raw-frame fallback), composites onto white + center-crops via
     * [EmbeddingRepository.prepareForEmbedding], embeds, and searches the index. When
     * [processedOutputDir] is non-null the processed bitmap is also written there so the
     * caller can show it in the debug breakdown.
     */
    suspend fun findSimilarWithDebug(
        file: File,
        threshold: Float,
        excludeIds: Set<String> = emptySet(),
        topK: Int = 20,
        processedOutputDir: File? = null,
    ): SimilarityResult? = withContext(Dispatchers.IO) {
        if (!isModelAvailable()) return@withContext null
        val raw = decodeUpright(file) ?: return@withContext null
        val segmentedBm = try {
            segmenterImpl.segmentForegroundOnBlack(raw)
        } catch (t: Throwable) {
            Log.w(TAG, "findSimilarWithDebug: segmentation threw, falling back", t)
            null
        }
        val source: Bitmap = if (segmentedBm != null) {
            if (!raw.isRecycled) raw.recycle()
            segmentedBm
        } else raw
        val prepared = EmbeddingRepository.prepareForEmbedding(source)
        if (prepared !== source && !source.isRecycled) source.recycle()
        if (prepared == null) return@withContext null
        val processedPath = processedOutputDir?.let { dir ->
            runCatching {
                dir.mkdirs()
                val out = File(dir, "query_processed_${System.currentTimeMillis()}.png")
                out.outputStream().buffered().use {
                    prepared.compress(Bitmap.CompressFormat.PNG, 100, it)
                }
                out.absolutePath
            }.getOrNull()
        }
        val emb = embedderImpl.embedBitmap(prepared)
        if (!prepared.isRecycled) prepared.recycle()
        if (emb == null) return@withContext null
        val matches = searchVector(emb.vec, emb.hist, emb.pHashes.firstOrNull() ?: 0L, threshold, excludeIds, topK)
        SimilarityResult(
            matches = matches,
            processedPath = processedPath,
            segmented = segmentedBm != null,
            hist = emb.hist,
            vec = emb.vec,
            pHash = emb.pHashes.firstOrNull() ?: 0L,
        )
    }

    /** Search the index with an already-computed embedding + histogram pair. */
    suspend fun searchVector(
        vec: FloatArray,
        hist: FloatArray,
        pHash: Long,
        threshold: Float,
        excludeIds: Set<String> = emptySet(),
        topK: Int = 20,
    ): List<Match> {
        if (!isModelAvailable()) return emptyList()
        indexImpl.load()
        return indexImpl.search(vec, hist, pHash, topK + excludeIds.size)
            .asSequence()
            .filter { it.id !in excludeIds }
            .filter { it.score >= threshold }
            .take(topK)
            .map { Match(it.id, it.score) }
            .toList()
    }

    /**
     * Pairwise scan over the index: for each indexed item, return its peers with cosine score
     * ≥ [threshold]. Restrict to [restrictToIds] when non-empty (the most common case is
     * "only consider items still on Drive"). Returns a map keyed by anchor Drive ID.
     */
    suspend fun findDuplicateClusters(
        threshold: Float,
        restrictToIds: Set<String> = emptySet(),
    ): Map<String, List<Match>> {
        if (!isModelAvailable()) return emptyMap()
        indexImpl.load()
        val ids = indexImpl.ids().let { all ->
            if (restrictToIds.isEmpty()) all else all.intersect(restrictToIds)
        }
        if (ids.isEmpty()) return emptyMap()
        val out = HashMap<String, List<Match>>()
        for (id in ids) {
            val e = indexImpl.entry(id) ?: continue
            val similar = indexImpl.search(e.vec, e.hist, e.pHashes.firstOrNull() ?: 0L, topK = 8)
                .filter { it.id != id && it.score >= threshold && it.id in ids }
                .map { Match(it.id, it.score) }
            if (similar.isNotEmpty()) out[id] = similar
        }
        return out
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
