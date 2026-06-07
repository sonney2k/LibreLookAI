package com.librelookai.shopping
import com.librelookai.util.localized
import android.app.Application
import android.net.Uri
import android.util.Log
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.google.gson.Gson
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import com.librelookai.auth.GoogleAuthManager
import com.librelookai.data.drive.DriveRepository
import com.librelookai.gemini.ClothingTags
import com.librelookai.gemini.GeminiRepository
import com.librelookai.gemini.classifyClothing
import com.librelookai.util.isNetworkAvailable
import com.librelookai.wardrobe.DriveImage
import com.librelookai.wardrobe.ItemSidecar
import com.librelookai.wardrobe.LocalCache
import com.librelookai.wardrobe.LocalCacheEntry
import com.librelookai.wardrobe.UrlImportPickerState
import com.librelookai.wardrobe.WebProductFetcher
import com.librelookai.wardrobe.rotateBitmapFileBy90
import com.librelookai.R
import com.librelookai.data.drive.await
import com.librelookai.data.drive.getOrCreateShoppingFolder
import com.librelookai.data.drive.listSidecarFiles
import com.librelookai.data.drive.loadFileContent
import com.librelookai.data.drive.upsertSidecar
import com.librelookai.data.model.Location
import com.librelookai.MainActivity
import com.librelookai.wardrobe.WardrobeViewModel

/**
 * State of the user's shopping wishlist (separate Drive folder `_shopping/`).
 * The wishlist is NOT a [Location] — see CLAUDE.md "Shopping closet" for the rationale.
 */
data class ShoppingClosetUiState(
    val items: List<DriveImage> = emptyList(),
    val isLoading: Boolean = false,
    val isSyncing: Boolean = false,
    val isUploading: Boolean = false,
    val isMoving: Boolean = false,
    val pendingJobs: Int = 0,
    val selectedIds: Set<String> = emptySet(),
    val error: String? = null,
    /** Drive folder ID of `_shopping/` once resolved (null while still loading the root folder). */
    val folderId: String? = null,
    /** driveId currently being re-tagged / re-bg-removed; drives the AI overlay in the viewer. */
    val processingImageId: String? = null,
    /** Non-null while the user is choosing an image from a pasted shopping URL. */
    val urlImportPicker: UrlImportPickerState? = null,
)

internal data class ShoppingPendingJob(val driveId: String)

/** Shopping wishlist counterpart to [WardrobeViewModel]. */
class ShoppingClosetViewModel(app: Application) : AndroidViewModel(app) {

    companion object { internal const val TAG = "ShoppingClosetVM" }

    internal val drive = DriveRepository(app, GoogleAuthManager(app))
    internal val gemini = GeminiRepository(app)
    internal val gson = Gson()

    internal val _state = MutableStateFlow(ShoppingClosetUiState())
    val state: StateFlow<ShoppingClosetUiState> = _state.asStateFlow()

    /** Gemini-facing language name for classifyClothing. Pushed in by MainActivity. */
    internal var geminiLanguage: String = "English"

    /** Resolved once on first load. */
    private var rootFolderId: String? = null
    internal var shoppingFolderId: String? = null

    /** Background queue for bg-removal + tagging on newly uploaded items. */
    internal val workQueue = Channel<ShoppingPendingJob>(Channel.UNLIMITED)

    init {
        viewModelScope.launch { processQueue() }
    }

    fun setLanguage(geminiName: String) { geminiLanguage = geminiName }

    fun toggleSelection(driveId: String) {
        _state.update {
            val sel = it.selectedIds.toMutableSet()
            if (driveId in sel) sel.remove(driveId) else sel.add(driveId)
            it.copy(selectedIds = sel)
        }
    }

    fun clearSelection() = _state.update { it.copy(selectedIds = emptySet()) }

    fun clearError() = _state.update { it.copy(error = null) }

    // ---------- Load ----------

    /** Resolves the shopping folder and loads its items. Two-phase like wardrobe. */
    fun loadItems() {
        viewModelScope.launch {
            _state.update { it.copy(isLoading = true, error = null) }

            // Resolve folder (uses Drive — needs network on first run; cached after that).
            val folderId = runCatching {
                val rootId = rootFolderId ?: drive.getOrCreateFolder().also { rootFolderId = it }
                shoppingFolderId ?: drive.getOrCreateShoppingFolder(rootId).also { shoppingFolderId = it }
            }.onFailure {
                Log.w(TAG, "shopping folder resolve failed", it)
                _state.update { s -> s.copy(isLoading = false, error = it.message) }
            }.getOrNull() ?: return@launch

            _state.update { it.copy(folderId = folderId) }

            // Phase 1 — instant: read local cache.
            val cacheFile = localCacheFile(folderId)
            if (cacheFile.exists()) {
                runCatching {
                    val cache = gson.fromJson(cacheFile.readText(), LocalCache::class.java)
                    cache.items.mapNotNull { entry ->
                        drive.cachedFile(entry.driveId)?.let { f ->
                            DriveImage(
                                entry.driveId, f.absolutePath, entry.name, entry.tags,
                                originalDriveId = entry.originalDriveId,
                                sidecarDriveId = entry.sidecarDriveId,
                                folderId = folderId,
                                createdTimeMs = entry.createdTimeMs,
                            )
                        }
                    }
                }.onSuccess { items ->
                    if (items.isNotEmpty()) _state.update { it.copy(items = items, isLoading = false) }
                }
            }

            // Phase 2 — refresh from Drive.
            _state.update { it.copy(isSyncing = true) }
            if (!getApplication<Application>().isNetworkAvailable()) {
                _state.update { it.copy(isLoading = false, isSyncing = false) }
                return@launch
            }

            runCatching {
                val files = drive.listFiles(folderId)
                val sidecarFiles = drive.listSidecarFiles(folderId)
                val sidecarIdByItemId = sidecarFiles.associate { sf ->
                    sf.name.removeSuffix(DriveRepository.SIDECAR_SUFFIX) to sf.id
                }

                // Download uncached cutouts in parallel.
                files.map { file ->
                    async {
                        drive.cachedFile(file.id) ?: drive.downloadToCache(file.id, file.name)
                    }
                }.awaitAll()

                // Load sidecar contents in parallel.
                val sidecarContent: Map<String, ItemSidecar> = sidecarFiles.map { sf ->
                    async {
                        val itemId = sf.name.removeSuffix(DriveRepository.SIDECAR_SUFFIX)
                        val content = drive.loadFileContent(sf.id)
                        itemId to content?.let { runCatching { gson.fromJson(it, ItemSidecar::class.java) }.getOrNull() }
                    }
                }.awaitAll().mapNotNull { (k, v) -> v?.let { k to it } }.toMap()

                files.mapNotNull { file ->
                    drive.cachedFile(file.id)?.let { cached ->
                        val sidecar = sidecarContent[file.id]
                        DriveImage(
                            driveId = file.id,
                            localPath = cached.absolutePath,
                            name = file.name,
                            tags = sidecar?.tags,
                            originalDriveId = sidecar?.originalDriveId,
                            sidecarDriveId = sidecarIdByItemId[file.id],
                            folderId = folderId,
                            createdTimeMs = file.createdTimeMs,
                        )
                    }
                }
            }.onSuccess { items ->
                _state.update { it.copy(items = items, isLoading = false, isSyncing = false) }
                saveLocalCache(folderId, items)
            }.onFailure { e ->
                Log.w(TAG, "shopping load phase 2 failed", e)
                _state.update { s ->
                    s.copy(isLoading = false, isSyncing = false, error = if (s.items.isEmpty()) e.message else null)
                }
            }
        }
    }

    // ---------- Add ----------

    /**
     * Moves wishlist items into a real closet. Optimistic: removes them from the shopping view +
     * cache and hands the moved items to [onMoved] (which registers them in the destination
     * wardrobe cache) *before* any Drive call, so they appear in the target closet instantly.
     * Drive moves run in the background; on failure the items are restored to shopping and
     * [onMoveFailed] is invoked with their ids so the caller can undo the wardrobe registration.
     */
    fun moveToCloset(
        driveIds: Set<String>,
        targetFolderId: String,
        onMoved: (List<DriveImage>) -> Unit,
        onMoveFailed: (Set<String>) -> Unit = {},
    ) {
        if (driveIds.isEmpty()) { onMoved(emptyList()); return }
        val sourceFolderId = shoppingFolderId ?: run { onMoved(emptyList()); return }
        if (sourceFolderId == targetFolderId) {
            onMoved(_state.value.items.filter { it.driveId in driveIds })
            return
        }

        val toMove = _state.value.items.filter { it.driveId in driveIds }
        if (toMove.isEmpty()) { onMoved(emptyList()); return }
        val movingIds = toMove.map { it.driveId }.toSet()

        // ---- Optimistic local update (synchronous, before any network call) ----
        _state.update { s ->
            s.copy(isMoving = true, selectedIds = emptySet(), error = null,
                items = s.items.filter { it.driveId !in movingIds })
        }
        saveLocalCache(sourceFolderId, _state.value.items)
        onMoved(toMove.map { it.copy(folderId = targetFolderId) })

        // ---- Drive moves in the background; restore items that fail ----
        viewModelScope.launch {
            val failed = coroutineScope {
                toMove.map { item ->
                    async {
                        runCatching {
                            val moveOriginal = item.originalDriveId?.let { origId ->
                                async { runCatching { drive.moveFile(origId, sourceFolderId, targetFolderId) } }
                            }
                            val moveSidecar = item.sidecarDriveId?.let { sId ->
                                async { runCatching { drive.moveFile(sId, sourceFolderId, targetFolderId) } }
                            }
                            drive.moveFile(item.driveId, sourceFolderId, targetFolderId)
                            moveOriginal?.await()
                            moveSidecar?.await()
                        }.fold(onSuccess = { null }, onFailure = { e ->
                            Log.w(TAG, "move failed for ${item.driveId}", e); item
                        })
                    }
                }.awaitAll().filterNotNull()
            }
            if (failed.isNotEmpty()) {
                val failedIds = failed.map { it.driveId }.toSet()
                _state.update { s ->
                    val present = s.items.map { it.driveId }.toSet()
                    s.copy(items = s.items + failed.filter { it.driveId !in present },
                        error = getApplication<Application>().localized().getString(R.string.wardrobe_move_failed))
                }
                saveLocalCache(sourceFolderId, _state.value.items)
                onMoveFailed(failedIds)
            }
            _state.update { it.copy(isMoving = false) }
        }
    }

    fun deleteItems(driveIds: Set<String>) {
        if (driveIds.isEmpty()) return
        val folderId = shoppingFolderId ?: return
        val toDelete = _state.value.items.filter { it.driveId in driveIds }
        if (toDelete.isEmpty()) { _state.update { it.copy(selectedIds = emptySet()) }; return }
        // Optimistic: vanish from the view + cache immediately, then delete from Drive in the
        // background (best-effort, matching the previous fault tolerance).
        _state.update { s -> s.copy(selectedIds = emptySet(), error = null, items = s.items.filter { it.driveId !in driveIds }) }
        saveLocalCache(folderId, _state.value.items)
        viewModelScope.launch {
            toDelete.forEach { item ->
                runCatching {
                    drive.deleteFile(item.driveId)
                    item.originalDriveId?.let { drive.deleteFile(it) }
                    item.sidecarDriveId?.let { drive.deleteFile(it) }
                }.onFailure { Log.w(TAG, "delete failed for ${item.driveId}", it) }
            }
        }
    }

    // ---------- Per-item AI ops (parity with WardrobeViewModel) ----------

    fun tagImage(driveId: String) {
        viewModelScope.launch {
            _state.update { it.copy(processingImageId = driveId) }
            val cachedFile = drive.cachedFile(driveId)
                ?: run { _state.update { it.copy(processingImageId = null) }; return@launch }
            val tags = gemini.classifyClothing(cachedFile, geminiLanguage)
                ?: run { _state.update { it.copy(processingImageId = null) }; return@launch }
            _state.update { s ->
                s.copy(
                    processingImageId = null,
                    items = s.items.map { if (it.driveId == driveId) it.copy(tags = tags) else it },
                )
            }
            saveSidecar(driveId)
        }
    }

    fun updateTags(driveId: String, tags: ClothingTags) {
        _state.update { s ->
            s.copy(items = s.items.map { if (it.driveId == driveId) it.copy(tags = tags) else it })
        }
        saveSidecar(driveId)
    }

    fun reprocessBackground(driveId: String) {
        viewModelScope.launch {
            _state.update { it.copy(processingImageId = driveId, error = null) }
            val source = resolveOriginalFile(driveId)
                ?: run { _state.update { it.copy(processingImageId = null, error = getApplication<Application>().localized().getString(R.string.error_original_unavailable)) }; return@launch }
            val processedFile = gemini.removeBackground(source, drive.cacheDir)
                ?: run { _state.update { it.copy(processingImageId = null) }; return@launch }
            runCatching {
                drive.updateImage(driveId, processedFile)
                val displayCache = File(drive.cacheDir, "$driveId.png")
                processedFile.copyTo(displayCache, overwrite = true)
                displayCache.absolutePath
            }.onSuccess { newPath ->
                _state.update { s ->
                    s.copy(
                        processingImageId = null,
                        items = s.items.map {
                            if (it.driveId == driveId) it.copy(localPath = newPath, version = System.currentTimeMillis())
                            else it
                        },
                    )
                }
                shoppingFolderId?.let { fid -> saveLocalCache(fid, _state.value.items) }
            }.onFailure { e ->
                _state.update { it.copy(processingImageId = null, error = e.message) }
            }
        }
    }

    fun rotateImage(driveId: String) {
        val img = _state.value.items.find { it.driveId == driveId } ?: return
        viewModelScope.launch {
            try {
                withContext(Dispatchers.IO) {
                    val cutoutFile = drive.cachedFile(img.driveId)
                        ?: drive.downloadToCache(img.driveId, "${img.driveId}${DriveRepository.CUTOUT_SUFFIX}")
                    if (cutoutFile != null) rotateBitmapFileBy90(cutoutFile)
                    img.originalDriveId?.let { origId ->
                        val origFile = drive.cachedFile(origId)
                            ?: drive.downloadToCache(origId, "${img.driveId}${DriveRepository.ORIGINAL_SUFFIX}")
                        if (origFile != null) rotateBitmapFileBy90(origFile)
                    }
                }
                _state.update { s ->
                    s.copy(items = s.items.map {
                        if (it.driveId == driveId) it.copy(version = it.version + 1) else it
                    })
                }
                withContext(Dispatchers.IO) {
                    drive.cachedFile(img.driveId)?.let { drive.updateImage(img.driveId, it) }
                    img.originalDriveId?.let { origId ->
                        drive.cachedFile(origId)?.let { drive.updateImage(origId, it) }
                    }
                }
                shoppingFolderId?.let { fid -> saveLocalCache(fid, _state.value.items) }
            } catch (e: Exception) {
                Log.w(TAG, "rotateImage failed", e)
                _state.update { it.copy(error = e.message) }
            }
        }
    }

    /** Public hook for the wardrobe viewer's "view original" toggle: returns the cached
     *  pre-cutout image path, downloading from Drive if needed. */
    suspend fun ensureOriginalCached(cutoutDriveId: String): String? = withContext(Dispatchers.IO) {
        val item = _state.value.items.find { it.driveId == cutoutDriveId } ?: return@withContext null
        if (item.originalDriveId == null) return@withContext null
        val local = File(drive.cacheDir, "${cutoutDriveId}_original.jpg")
        if (local.exists()) return@withContext local.absolutePath
        val downloaded = runCatching { drive.downloadToCache(item.originalDriveId) }.getOrNull() ?: return@withContext null
        runCatching { downloaded.copyTo(local, overwrite = true) }
        local.absolutePath
    }

    private suspend fun resolveOriginalFile(driveId: String): File? {
        val localOriginal = File(drive.cacheDir, "${driveId}_original.jpg")
        if (localOriginal.exists()) return localOriginal
        val originalDriveId = _state.value.items.find { it.driveId == driveId }?.originalDriveId
        if (originalDriveId != null) {
            val downloaded = drive.downloadToCache(originalDriveId)
            if (downloaded != null) {
                downloaded.copyTo(localOriginal, overwrite = true)
                return localOriginal
            }
        }
        return drive.cachedFile(driveId)
    }

    private fun saveSidecar(driveId: String) {
        val id = shoppingFolderId ?: return
        viewModelScope.launch(Dispatchers.IO) {
            val img = _state.value.items.find { it.driveId == driveId } ?: return@launch
            val sidecarJson = gson.toJson(ItemSidecar(img.tags, img.originalDriveId))
            val sidecarFileId = runCatching {
                drive.upsertSidecar(id, "$driveId${DriveRepository.SIDECAR_SUFFIX}", sidecarJson)
            }.getOrNull() ?: return@launch
            _state.update { s ->
                s.copy(items = s.items.map { i ->
                    if (i.driveId == driveId) i.copy(sidecarDriveId = sidecarFileId) else i
                })
            }
            saveLocalCache(id, _state.value.items)
        }
    }

    // ---------- Helpers ----------

    internal suspend fun ensureFolder(): String? = withContext(Dispatchers.IO) {
        runCatching {
            val rootId = rootFolderId ?: drive.getOrCreateFolder().also { rootFolderId = it }
            val resolved = shoppingFolderId ?: drive.getOrCreateShoppingFolder(rootId)
            shoppingFolderId = resolved
            _state.update { it.copy(folderId = resolved) }
            resolved
        }.onFailure { e ->
            Log.w(TAG, "ensureFolder failed", e)
            _state.update { it.copy(error = e.message) }
        }.getOrNull()
    }

    private fun localCacheFile(id: String) =
        File(getApplication<Application>().filesDir, "wardrobe_cache_$id.json")

    internal fun saveLocalCache(id: String, items: List<DriveImage>) {
        runCatching {
            val cache = LocalCache(items.map {
                LocalCacheEntry(it.driveId, it.name, it.tags, it.originalDriveId, it.sidecarDriveId, it.createdTimeMs)
            })
            localCacheFile(id).writeText(gson.toJson(cache))
        }
    }
}
