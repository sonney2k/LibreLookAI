package com.librelookai

import android.app.Application
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

    private val embedder = EmbeddingRepository(app)
    private val segmenter = SegmentationRepository(app)
    private val index = EmbeddingIndex(app)

    private val _state = MutableStateFlow(ShoppingHelperUiState(modelAvailable = embedder.isAvailable()))
    val state = _state.asStateFlow()

    private var indexJob: Job? = null

    init {
        viewModelScope.launch {
            index.load()
            _state.update { it.copy(indexedCount = index.size) }
        }
    }

    fun beginCapture() {
        _state.update { it.copy(isCapturing = true, error = null) }
    }

    fun cancelCapture() {
        _state.update { it.copy(isCapturing = false) }
    }

    fun clearResults() {
        // Drop the old query file if it still lives in our cache dir
        _state.value.queryPath?.let { path ->
            runCatching { File(path).takeIf { it.parentFile?.name == QUERY_DIR }?.delete() }
        }
        _state.update { it.copy(queryPath = null, matches = emptyList(), error = null) }
    }

    /**
     * Walk the current wardrobe: embed any item with a local cutout that isn't in the index yet,
     * and drop index entries for items that no longer have a local cutout. Safe to call repeatedly;
     * overlapping calls are coalesced.
     */
    fun syncIndex(images: List<DriveImage>) {
        if (!_state.value.modelAvailable) return
        if (indexJob?.isActive == true) return
        indexJob = viewModelScope.launch {
            index.load()
            val currentIds = images.map { it.driveId }.toSet()
            val dropped = index.retainIds(currentIds)

            val todo = withContext(Dispatchers.IO) {
                images.filter { img ->
                    val cached = cutoutFile(img) ?: return@filter false
                    cached.exists() && !index.contains(img.driveId)
                }
            }

            if (todo.isEmpty() && dropped == 0) {
                _state.update { it.copy(indexedCount = index.size) }
                return@launch
            }

            _state.update {
                it.copy(isIndexing = todo.isNotEmpty(), indexTotal = todo.size, indexDone = 0)
            }

            var done = 0
            for (img in todo) {
                val file = cutoutFile(img) ?: continue
                val vec = embedder.embedFile(file)
                if (vec != null) index.upsert(img.driveId, vec)
                done++
                _state.update { it.copy(indexDone = done) }
            }
            index.save()
            _state.update {
                it.copy(
                    isIndexing = false,
                    indexTotal = 0,
                    indexDone = 0,
                    indexedCount = index.size,
                )
            }
        }
    }

    /**
     * Called after [CaptureScreen] hands us a cropped photo. Moves the file into our own cache
     * subdir, embeds it, searches the index, and publishes [matches].
     */
    fun onCapturedFile(file: File, images: List<DriveImage>) {
        viewModelScope.launch {
            if (!_state.value.modelAvailable) {
                _state.update {
                    it.copy(
                        isCapturing = false,
                        error = getApplication<Application>().getString(R.string.shop_error_model_missing),
                    )
                }
                return@launch
            }

            val queryFile = adoptQueryFile(file)
            _state.update {
                it.copy(
                    isCapturing = false,
                    isMatching = true,
                    queryPath = queryFile.absolutePath,
                    matches = emptyList(),
                    error = null,
                )
            }

            // Make sure the index reflects the current wardrobe before we search
            index.load()
            val currentIds = images.map { it.driveId }.toSet()
            index.retainIds(currentIds)
            for (img in images) {
                val cached = cutoutFile(img) ?: continue
                if (!cached.exists()) continue
                if (index.contains(img.driveId)) continue
                val vec = embedder.embedFile(cached) ?: continue
                index.upsert(img.driveId, vec)
            }

            val queryVec = embedQuery(queryFile)
            if (queryVec == null) {
                _state.update {
                    it.copy(
                        isMatching = false,
                        error = getApplication<Application>().getString(R.string.shop_error_embed_failed),
                    )
                }
                return@launch
            }

            val rawMatches = index.search(queryVec, TOP_K)
            val byId = images.associateBy { it.driveId }
            val resolved = rawMatches.mapNotNull { m ->
                val img = byId[m.id] ?: return@mapNotNull null
                ShopMatch(image = img, score = m.score)
            }

            _state.update {
                it.copy(
                    isMatching = false,
                    matches = resolved,
                    indexedCount = index.size,
                )
            }
            index.save()
        }
    }

    fun clearError() {
        _state.update { it.copy(error = null) }
    }

    override fun onCleared() {
        super.onCleared()
        viewModelScope.launch {
            embedder.close()
            segmenter.close()
        }
    }

    // ---------- helpers ----------

    /**
     * Embed the live query: decode the JPEG, run interactive segmentation seeded at the image
     * center to isolate the item from its background, then composite onto white and embed. Falls
     * back to embedding the raw file (with white-bg compositing only) if segmentation is
     * unavailable or fails.
     */
    private suspend fun embedQuery(queryFile: File): FloatArray? = withContext(Dispatchers.IO) {
        val raw = runCatching { BitmapFactory.decodeFile(queryFile.absolutePath) }.getOrNull()
            ?: return@withContext null
        try {
            val masked = segmenter.segmentForegroundOnWhite(raw)
            if (masked != null) {
                try {
                    embedder.embedBitmap(masked, compositeAlpha = false)
                } finally {
                    if (!masked.isRecycled) masked.recycle()
                }
            } else {
                Log.w(TAG, "embedQuery: segmentation unavailable, falling back to raw query")
                embedder.embedFile(queryFile)
            }
        } finally {
            if (!raw.isRecycled) raw.recycle()
        }
    }

    private fun cutoutFile(img: DriveImage): File? {
        val cacheDir = File(getApplication<Application>().filesDir, "wardrobe")
        val png = File(cacheDir, "${img.driveId}.png")
        if (png.exists()) return png
        val jpg = File(cacheDir, "${img.driveId}.jpg")
        if (jpg.exists()) return jpg
        return null
    }

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
