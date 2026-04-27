package com.librelookai

import android.app.Application
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.util.Log
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

/** One wardrobe item ranked against the live query, with its cosine score. */
data class ShopMatch(
    val image: DriveImage,
    val score: Float,
)

data class ShoppingHelperUiState(
    /** User is on the camera screen. */
    val isCapturing: Boolean = false,
    /** Absolute path of the most recent query photo (kept in cacheDir). */
    val queryPath: String? = null,
    /** Absolute path of the post-segmentation, composited-on-white, center-cropped query
     *  bitmap — the exact pixels fed to the embedder. Used by the debug preview. */
    val queryProcessedPath: String? = null,
    /** L1-normalized HSV histogram for the query (same one mixed into the score). */
    val queryHist: FloatArray? = null,
    /** L2-normalized embedding for the query, kept so the debug view can recompute
     *  per-match embedding-cosine vs histogram-cosine. */
    val queryVec: FloatArray? = null,
    /** True when on-device segmentation isolated a foreground object for the current query;
     *  false when it failed (or hadn't run yet) and we fell back to embedding the raw frame. */
    val querySegmented: Boolean = false,
    /** True while the query image is being embedded and searched. */
    val isMatching: Boolean = false,
    /** Ranked matches from the last query, or empty if no query yet. */
    val matches: List<ShopMatch> = emptyList(),
    /** True while syncing new wardrobe items into the embedding index. */
    val isIndexing: Boolean = false,
    val indexTotal: Int = 0,
    val indexDone: Int = 0,
    /** Total items currently in the persistent embedding index. */
    val indexedCount: Int = 0,
    /** False when the MediaPipe model asset is missing from the APK. */
    val modelAvailable: Boolean = true,
    val error: String? = null,
) {
    val hasQuery: Boolean get() = queryPath != null
}

class ShoppingHelperViewModel(app: Application) : AndroidViewModel(app) {

    private val _state = MutableStateFlow(
        ShoppingHelperUiState(modelAvailable = EmbeddingService.isModelAvailable())
    )
    val state = _state.asStateFlow()

    private var indexJob: Job? = null

    private val cacheDir: File get() = File(getApplication<Application>().filesDir, "wardrobe")

    init {
        viewModelScope.launch {
            EmbeddingService.index.load()
            _state.update { it.copy(indexedCount = EmbeddingService.indexSize) }
        }
    }

    fun beginCapture() {
        _state.update { it.copy(isCapturing = true, error = null) }
    }

    fun cancelCapture() {
        _state.update { it.copy(isCapturing = false) }
    }

    fun clearResults() {
        val s = _state.value
        listOfNotNull(s.queryPath, s.queryProcessedPath).forEach { path ->
            runCatching { File(path).takeIf { it.parentFile?.name == QUERY_DIR }?.delete() }
        }
        _state.update {
            it.copy(
                queryPath = null,
                queryProcessedPath = null,
                queryHist = null,
                queryVec = null,
                querySegmented = false,
                matches = emptyList(),
                error = null,
            )
        }
    }

    /**
     * Walk the current wardrobe: embed any item with a local cutout that isn't in the index yet,
     * and drop index entries for items that no longer have a local cutout. Safe to call repeatedly;
     * overlapping calls are coalesced.
     */
    fun syncIndex(images: List<DriveImage>) {
        if (!EmbeddingService.isModelAvailable()) return
        if (indexJob?.isActive == true) return
        indexJob = viewModelScope.launch {
            val total = images.count { img ->
                val f = File(cacheDir, "${img.driveId}.png").exists() ||
                        File(cacheDir, "${img.driveId}.jpg").exists()
                f
            }
            _state.update { it.copy(isIndexing = total > 0, indexTotal = total, indexDone = 0) }
            EmbeddingService.syncIndex(images, cacheDir) { done, t ->
                _state.update { it.copy(isIndexing = true, indexTotal = t, indexDone = done) }
            }
            _state.update {
                it.copy(
                    isIndexing = false,
                    indexTotal = 0,
                    indexDone = 0,
                    indexedCount = EmbeddingService.indexSize,
                )
            }
        }
    }

    /**
     * Called after [CaptureScreen] hands us a cropped photo. Moves the file into our own cache
     * subdir, runs segmentation + prep manually so the debug preview can show the same pixels
     * we hand to the embedder, embeds, searches the index, and publishes [matches].
     */
    fun onCapturedFile(file: File, images: List<DriveImage>) {
        viewModelScope.launch {
            if (!EmbeddingService.isModelAvailable()) {
                _state.update {
                    it.copy(
                        isCapturing = false,
                        error = getApplication<Application>().getString(R.string.shop_error_model_missing),
                    )
                }
                return@launch
            }

            // Discard any previous processed query so disk doesn't grow.
            _state.value.queryProcessedPath?.let { path ->
                runCatching { File(path).takeIf { it.parentFile?.name == QUERY_DIR }?.delete() }
            }

            val queryFile = adoptQueryFile(file)
            _state.update {
                it.copy(
                    isCapturing = false,
                    isMatching = true,
                    queryPath = queryFile.absolutePath,
                    queryProcessedPath = null,
                    queryHist = null,
                    queryVec = null,
                    querySegmented = false,
                    matches = emptyList(),
                    error = null,
                )
            }

            EmbeddingService.syncIndex(images, cacheDir)

            val preparedResult = prepareQueryBitmap(queryFile)
            if (preparedResult == null) {
                _state.update {
                    it.copy(
                        isMatching = false,
                        error = getApplication<Application>().getString(R.string.shop_error_embed_failed),
                    )
                }
                return@launch
            }
            val (prepared, segmented) = preparedResult

            val processedFile = saveProcessedQuery(prepared)
            val emb = EmbeddingService.embedder.embedBitmap(prepared)
            if (!prepared.isRecycled) prepared.recycle()
            if (emb == null) {
                _state.update {
                    it.copy(
                        isMatching = false,
                        error = getApplication<Application>().getString(R.string.shop_error_embed_failed),
                    )
                }
                return@launch
            }

            val rawMatches = EmbeddingService.searchVector(
                vec = emb.vec,
                hist = emb.hist,
                threshold = -1f,
                topK = TOP_K,
            )
            val byId = images.associateBy { it.driveId }
            val resolved = rawMatches.mapNotNull { m ->
                val img = byId[m.driveId] ?: return@mapNotNull null
                ShopMatch(image = img, score = m.score)
            }

            _state.update {
                it.copy(
                    isMatching = false,
                    matches = resolved,
                    queryProcessedPath = processedFile?.absolutePath,
                    queryHist = emb.hist,
                    queryVec = emb.vec,
                    querySegmented = segmented,
                    indexedCount = EmbeddingService.indexSize,
                )
            }
        }
    }

    /**
     * Run the same prep [EmbeddingService.embedQuery] would (segment → composite-on-black →
     * center-crop) but keep the resulting bitmap so the debug view can show it. Returns the
     * prepared bitmap together with a flag that's true iff segmentation succeeded — when it
     * fails, we still embed the raw frame so the user gets matches, but the debug UI labels the
     * thumbnail as a fallback.
     */
    private suspend fun prepareQueryBitmap(file: File): Pair<Bitmap, Boolean>? = withContext(Dispatchers.IO) {
        val raw = EmbeddingService.decodeUpright(file)
            ?: return@withContext null
        val segmentedBm = try {
            EmbeddingService.segmenter.segmentForegroundOnBlack(raw)
        } catch (t: Throwable) {
            Log.w(TAG, "segmentation threw, falling back", t)
            null
        }
        val source: Bitmap = if (segmentedBm != null) {
            if (!raw.isRecycled) raw.recycle()
            segmentedBm
        } else {
            raw
        }
        val prepared = EmbeddingRepository.prepareForEmbedding(source)
        if (prepared !== source && !source.isRecycled) source.recycle()
        prepared?.let { it to (segmentedBm != null) }
    }

    private suspend fun saveProcessedQuery(bm: Bitmap): File? = withContext(Dispatchers.IO) {
        val dir = File(getApplication<Application>().cacheDir, QUERY_DIR).also { it.mkdirs() }
        val out = File(dir, "query_processed_${System.currentTimeMillis()}.png")
        val ok = runCatching {
            out.outputStream().buffered().use { bm.compress(Bitmap.CompressFormat.PNG, 100, it) }
        }.isSuccess
        if (ok) out else null
    }

    fun clearError() {
        _state.update { it.copy(error = null) }
    }

    // ---------- helpers ----------

    /** Move (or copy, if cross-filesystem) [source] into our private query cache. */
    private suspend fun adoptQueryFile(source: File): File = withContext(Dispatchers.IO) {
        val dir = File(getApplication<Application>().cacheDir, QUERY_DIR).also { it.mkdirs() }
        val dest = File(dir, "query_${System.currentTimeMillis()}.jpg")
        runCatching {
            if (!source.renameTo(dest)) {
                source.copyTo(dest, overwrite = true)
                source.delete()
            }
        }.onFailure { Log.w(TAG, "adoptQueryFile failed", it) }
        dest
    }

    companion object {
        private const val TAG = "ShoppingHelperVM"
        private const val QUERY_DIR = "shop_queries"
        private const val TOP_K = 10
    }
}
