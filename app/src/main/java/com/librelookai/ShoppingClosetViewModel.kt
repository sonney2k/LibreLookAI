package com.librelookai

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
)

private data class ShoppingPendingJob(val driveId: String)

/** Shopping wishlist counterpart to [WardrobeViewModel]. */
class ShoppingClosetViewModel(app: Application) : AndroidViewModel(app) {

    companion object { private const val TAG = "ShoppingClosetVM" }

    private val drive = DriveRepository(app, GoogleAuthManager(app))
    private val gemini = GeminiRepository(app)
    private val gson = Gson()

    private val _state = MutableStateFlow(ShoppingClosetUiState())
    val state: StateFlow<ShoppingClosetUiState> = _state.asStateFlow()

    /** Gemini-facing language name for classifyClothing. Pushed in by MainActivity. */
    private var geminiLanguage: String = "English"

    /** Resolved once on first load. */
    private var rootFolderId: String? = null
    private var shoppingFolderId: String? = null

    /** Background queue for bg-removal + tagging on newly uploaded items. */
    private val workQueue = Channel<ShoppingPendingJob>(Channel.UNLIMITED)

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

    fun addFromCamera(rawFile: File) {
        viewModelScope.launch {
            val folderId = ensureFolder() ?: run {
                runCatching { rawFile.delete() }
                return@launch
            }
            uploadRaw(rawFile, folderId)
        }
    }

    fun addFromGallery(uris: List<Uri>) {
        if (uris.isEmpty()) return
        viewModelScope.launch {
            val folderId = ensureFolder() ?: return@launch
            val cr = getApplication<Application>().contentResolver
            uris.forEach { uri ->
                val tempFile = File(drive.cacheDir, "shop_gallery_${System.currentTimeMillis()}.jpg")
                runCatching {
                    cr.openInputStream(uri)?.use { it.copyTo(tempFile.outputStream()) }
                    uploadRaw(tempFile, folderId)
                }.onFailure { e ->
                    Log.w(TAG, "gallery import failed", e)
                    _state.update { it.copy(error = "Upload failed: ${e.message}") }
                    runCatching { tempFile.delete() }
                }
            }
        }
    }

    fun addFromUrl(url: String) {
        viewModelScope.launch {
            val folderId = ensureFolder() ?: return@launch
            _state.update { it.copy(isUploading = true, error = null) }
            val image = WebProductFetcher.fetchProductImage(url, drive.cacheDir)
            if (image == null) {
                _state.update {
                    it.copy(
                        isUploading = false,
                        error = getApplication<Application>().getString(R.string.url_import_failed),
                    )
                }
                return@launch
            }
            uploadRaw(image, folderId)
        }
    }

    /** Common path: upload [rawFile] to Drive, queue for bg removal + tagging. */
    private suspend fun uploadRaw(rawFile: File, folderId: String) {
        _state.update { it.copy(isUploading = true, error = null) }
        runCatching {
            val uploaded = drive.uploadImage(folderId, rawFile)
            val ext = if (rawFile.extension == "png") "png" else "jpg"
            val displayCache = File(drive.cacheDir, "${uploaded.id}.$ext")
            if (rawFile.absolutePath != displayCache.absolutePath) {
                rawFile.copyTo(displayCache, overwrite = true)
            }
            rawFile.copyTo(File(drive.cacheDir, "${uploaded.id}_original.jpg"), overwrite = true)
            DriveImage(uploaded.id, displayCache.absolutePath, uploaded.name, tags = null, folderId = folderId)
        }.onSuccess { newImage ->
            _state.update { it.copy(
                isUploading = false,
                items = listOf(newImage) + it.items,
                pendingJobs = it.pendingJobs + 1,
            ) }
            saveLocalCache(folderId, _state.value.items)
            workQueue.send(ShoppingPendingJob(newImage.driveId))
        }.onFailure { e ->
            Log.w(TAG, "shopping upload failed", e)
            _state.update { it.copy(isUploading = false, error = e.message) }
            runCatching { rawFile.delete() }
        }
    }

    // ---------- Background processing ----------

    private suspend fun processQueue() {
        for (job in workQueue) {
            runCatching { processQueuedItem(job) }
                .onFailure { e -> _state.update { it.copy(error = e.message) } }
            _state.update { it.copy(pendingJobs = maxOf(0, it.pendingJobs - 1)) }
            shoppingFolderId?.let { fid -> saveLocalCache(fid, _state.value.items) }
        }
    }

    private suspend fun processQueuedItem(job: ShoppingPendingJob) {
        val folderId = shoppingFolderId ?: return
        val rawFile = File(drive.cacheDir, "${job.driveId}_original.jpg")
        if (!rawFile.exists()) return

        // Step 1 — bg removal (fall back to raw on failure).
        val processedFile = gemini.removeBackground(rawFile, drive.cacheDir) ?: rawFile

        // Step 2 — upload cutout, rename to "{cutoutId}_cutout.png".
        val cutoutDrive = runCatching {
            val uploaded = drive.uploadImage(folderId, processedFile)
            drive.renameFile(uploaded.id, "${uploaded.id}${DriveRepository.CUTOUT_SUFFIX}")
            uploaded.copy(name = "${uploaded.id}${DriveRepository.CUTOUT_SUFFIX}")
        }.getOrNull() ?: return

        // Step 3 — upload original as "{cutoutId}_original.jpg" (best effort).
        val originalDriveId = runCatching {
            drive.uploadImageWithName(
                folderId, rawFile, "${cutoutDrive.id}${DriveRepository.ORIGINAL_SUFFIX}",
            ).id
        }.getOrNull()

        // Step 4 — local caches: cutout + original (must precede deleteFile, which clears the local _original.jpg).
        val localCutout = File(drive.cacheDir, "${cutoutDrive.id}.png")
        if (processedFile.absolutePath != localCutout.absolutePath) {
            processedFile.copyTo(localCutout, overwrite = true)
        }
        rawFile.copyTo(File(drive.cacheDir, "${cutoutDrive.id}_original.jpg"), overwrite = true)

        // Step 5 — delete the temporary raw upload.
        runCatching { drive.deleteFile(job.driveId) }

        // Step 6 — replace the in-memory entry (match raw or cutout id, like wardrobe does).
        _state.update { s ->
            s.copy(items = s.items.map { img ->
                if (img.driveId == job.driveId || img.driveId == cutoutDrive.id) img.copy(
                    driveId = cutoutDrive.id,
                    name = cutoutDrive.name,
                    localPath = localCutout.absolutePath,
                    version = System.currentTimeMillis(),
                    originalDriveId = originalDriveId,
                ) else img
            })
        }

        // Step 7 — classify tags.
        val tags = gemini.classifyClothing(localCutout, geminiLanguage)
        if (tags != null) {
            _state.update { s ->
                s.copy(items = s.items.map { img ->
                    if (img.driveId == cutoutDrive.id) img.copy(tags = tags) else img
                })
            }
        }

        // Step 8 — sidecar.
        val sidecarJson = gson.toJson(ItemSidecar(tags, originalDriveId))
        runCatching {
            drive.upsertSidecar(
                folderId, "${cutoutDrive.id}${DriveRepository.SIDECAR_SUFFIX}", sidecarJson,
            )
        }.onSuccess { sidecarId ->
            _state.update { s ->
                s.copy(items = s.items.map { img ->
                    if (img.driveId == cutoutDrive.id) img.copy(sidecarDriveId = sidecarId) else img
                })
            }
        }
    }

    // ---------- Move + delete ----------

    /**
     * Moves [driveIds] from `_shopping/` to [targetFolderId] (a regular closet). Tags + cutout +
     * original + sidecar are preserved verbatim — Drive only changes parents, no re-upload, no
     * re-tagging. [onMoved] fires once Drive moves complete (called even for partial success); the
     * caller is responsible for telling [WardrobeViewModel] to reload the destination closet so
     * the items appear there.
     */
    fun moveToCloset(driveIds: Set<String>, targetFolderId: String, onMoved: (Set<String>) -> Unit) {
        if (driveIds.isEmpty()) { onMoved(emptySet()); return }
        val sourceFolderId = shoppingFolderId ?: run { onMoved(emptySet()); return }
        if (sourceFolderId == targetFolderId) { onMoved(driveIds); return }

        val toMove = _state.value.items.filter { it.driveId in driveIds }
        if (toMove.isEmpty()) { onMoved(emptySet()); return }

        viewModelScope.launch {
            _state.update { it.copy(isMoving = true, selectedIds = emptySet(), error = null) }
            val movedIds = coroutineScope {
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
                        }.onFailure { e ->
                            Log.w(TAG, "move failed for ${item.driveId}", e)
                            _state.update { it.copy(error = e.message) }
                        }.getOrNull()?.let { item.driveId }
                    }
                }.awaitAll().filterNotNull().toSet()
            }
            if (movedIds.isNotEmpty()) {
                _state.update { s -> s.copy(items = s.items.filter { it.driveId !in movedIds }) }
                saveLocalCache(sourceFolderId, _state.value.items)
            }
            _state.update { it.copy(isMoving = false) }
            onMoved(movedIds)
        }
    }

    fun deleteItems(driveIds: Set<String>) {
        if (driveIds.isEmpty()) return
        val folderId = shoppingFolderId ?: return
        val toDelete = _state.value.items.filter { it.driveId in driveIds }
        if (toDelete.isEmpty()) return
        viewModelScope.launch {
            _state.update { it.copy(selectedIds = emptySet(), error = null) }
            toDelete.forEach { item ->
                runCatching {
                    drive.deleteFile(item.driveId)
                    item.originalDriveId?.let { drive.deleteFile(it) }
                    item.sidecarDriveId?.let { drive.deleteFile(it) }
                }.onFailure { Log.w(TAG, "delete failed for ${item.driveId}", it) }
            }
            _state.update { s -> s.copy(items = s.items.filter { it.driveId !in driveIds }) }
            saveLocalCache(folderId, _state.value.items)
        }
    }

    // ---------- Helpers ----------

    private suspend fun ensureFolder(): String? = withContext(Dispatchers.IO) {
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

    private fun saveLocalCache(id: String, items: List<DriveImage>) {
        runCatching {
            val cache = LocalCache(items.map {
                LocalCacheEntry(it.driveId, it.name, it.tags, it.originalDriveId, it.sidecarDriveId)
            })
            localCacheFile(id).writeText(gson.toJson(cache))
        }
    }
}
