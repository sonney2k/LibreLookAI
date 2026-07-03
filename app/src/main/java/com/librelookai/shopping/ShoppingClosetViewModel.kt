package com.librelookai.shopping
import com.librelookai.util.localized
import android.app.Application
import android.util.Log
import androidx.lifecycle.AndroidViewModel
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import androidx.lifecycle.viewModelScope
import com.google.gson.Gson
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import com.librelookai.data.drive.DriveRepository
import com.librelookai.gemini.ClothingTags
import com.librelookai.gemini.GeminiRepository
import com.librelookai.gemini.classifyClothing
import com.librelookai.util.isNetworkAvailable
import com.librelookai.data.drive.SyncEngine
import com.librelookai.data.local.CachedWardrobeItem
import com.librelookai.data.local.PendingMutationStore
import com.librelookai.data.local.WardrobeItemStore
import com.librelookai.data.session.ClosetSessionHolder
import com.librelookai.data.session.UserPreferencesRepository
import com.librelookai.settings.AppLanguage
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import com.librelookai.wardrobe.DeleteItemPayload
import com.librelookai.wardrobe.DriveImage
import com.librelookai.wardrobe.ITEM_DELETE_KIND
import com.librelookai.wardrobe.ITEM_MOVE_KIND
import com.librelookai.wardrobe.ItemSidecar
import com.librelookai.wardrobe.MoveItemPayload
import com.librelookai.wardrobe.WardrobeMoveSyncHandler
import com.librelookai.wardrobe.UrlImportPickerState
import com.librelookai.wardrobe.toCachedItem
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
@OptIn(ExperimentalCoroutinesApi::class)
@HiltViewModel
class ShoppingClosetViewModel @Inject constructor(
    app: Application,
    internal val drive: DriveRepository,
    internal val gemini: GeminiRepository,
    internal val itemStore: WardrobeItemStore,
    private val mutationStore: PendingMutationStore,
    private val syncEngine: SyncEngine,
    moveSync: WardrobeMoveSyncHandler,
    session: ClosetSessionHolder,
    prefsRepo: UserPreferencesRepository,
) : AndroidViewModel(app) {

    companion object { internal const val TAG = "ShoppingClosetVM" }
    internal val gson = Gson()

    internal val _state = MutableStateFlow(ShoppingClosetUiState())
    val state: StateFlow<ShoppingClosetUiState> = _state.asStateFlow()

    /** Gemini-facing language name for classifyClothing. Pushed in by MainActivity. */
    internal var geminiLanguage: String = "English"

    /** Resolved once on first load. */
    private var rootFolderId: String? = null
    internal var shoppingFolderId: String? = null

    // ---- Derived read path (refactor § 5 slice 4c) ----
    // [ShoppingClosetUiState.items] is *derived*: the resolved `_shopping/` folder id flatMaps
    // into [WardrobeItemStore.observeItems], so every store write — by this VM, a wardrobe-side
    // re-home or a SyncEngine handler — lands in the UI via Room invalidation. Mutations write
    // the store; nothing splices `state.items` any more.
    private val folderScope = MutableStateFlow<String?>(null)
    /**
     * Coil cache-buster overlay: bumped whenever an item's image *bytes* change under an
     * unchanged driveId (rotate, reprocess). Mirrors the wardrobe VM's overlay (§ 5 slice 4a).
     */
    internal val imageVersions = MutableStateFlow<Map<String, Long>>(emptyMap())

    internal fun bumpImageVersion(vararg driveIds: String) {
        val now = System.currentTimeMillis()
        imageVersions.update { it + driveIds.associateWith { now } }
    }

    /** Background queue for bg-removal + tagging on newly uploaded items. */
    internal val workQueue = Channel<ShoppingPendingJob>(Channel.UNLIMITED)

    init {
        viewModelScope.launch { processQueue() }
        // Derived view (§ 5 slice 4c): the wishlist is the store rows under `_shopping/` (with
        // a cached image file), newest first — matching Drive's createdTime-desc listing —
        // stamped with the Coil version overlay. File stats run on IO via flowOn; redundant
        // emissions are free (StateFlow suppresses equal states).
        viewModelScope.launch {
            combine(
                folderScope.flatMapLatest { id ->
                    if (id == null) flowOf(emptyMap()) else itemStore.observeItems(listOf(id))
                },
                imageVersions,
            ) { byFolder, versions ->
                byFolder.flatMap { (fid, items) -> items.mapNotNull { it.toDriveImage(fid, versions) } }
                    .sortedByDescending { it.createdTimeMs }
            }
                .flowOn(Dispatchers.IO)
                .collect { items -> _state.update { it.copy(items = items) } }
        }
        // Publish the resolved `_shopping/` folder id into the shared closet session so the
        // wardrobe VM's cross-closet snapshot covers wishlist items (replaces the AppContent
        // fan-out bridge keyed on this state field); the same id scopes the derived view above.
        viewModelScope.launch {
            state.map { it.folderId }.distinctUntilChanged().collect { id ->
                folderScope.value = id
                session.setShoppingFolder(id)
            }
        }
        // Mirror the Gemini tagging language from the shared preferences repository —
        // replaces the AppContent language mirror (refactor § 5 slice 2).
        viewModelScope.launch {
            prefsRepo.preferences.collect { geminiLanguage = AppLanguage.toGeminiName(it.language) }
        }
        // SyncEngine feedback: a queued shopping→closet move exhausted its retries and the
        // handler re-homed the Room row back to the shopping folder — the derived view
        // restores the item by itself via invalidation, so only the error banner remains to
        // raise here (the wardrobe VM's own collector undoes the optimistic registration on
        // its side).
        viewModelScope.launch {
            moveSync.moveRolledBack.collect { rollback ->
                if (rollback.sourceFolderId != shoppingFolderId) return@collect
                _state.update {
                    it.copy(error = getApplication<Application>().localized().getString(R.string.wardrobe_move_failed))
                }
            }
        }
        // Pre-warm on creation (the VM is activity-scoped, created at app start) so the Shopping
        // tab paints instantly — replaces the AppContent pre-warm bridge (§ 5). Two-phase: the
        // derivation above paints the store, loadItems resolves the folder + runs the reconcile.
        loadItems()
    }

    /**
     * Maps a store row to its displayable [DriveImage], or null when the image bytes aren't in
     * the local Drive cache yet (the row reappears with the next store write after download).
     * The derivation collector in `init` runs this for every row on each invalidation.
     */
    private fun CachedWardrobeItem.toDriveImage(
        fid: String,
        versions: Map<String, Long> = emptyMap(),
    ): DriveImage? =
        drive.cachedFile(driveId)?.let { f ->
            DriveImage(
                driveId, f.absolutePath, name, tags,
                originalDriveId = originalDriveId,
                sidecarDriveId = sidecarDriveId,
                folderId = fid,
                createdTimeMs = createdTimeMs,
                version = versions[driveId] ?: 0L,
            )
        }

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

            val online = getApplication<Application>().isNetworkAvailable()

            // Resolve the shopping folder ID. Online: via Drive (cached per process after first run),
            // then persisted so a later offline launch can still find the local cache. Offline: fall
            // back to the in-memory id or the persisted one — the `_shopping/` folder ID is otherwise
            // unobtainable without the network, which would leave the cache (keyed by it) unreachable.
            val folderId = if (online) {
                runCatching {
                    val rootId = rootFolderId ?: drive.getOrCreateFolder().also { rootFolderId = it }
                    shoppingFolderId ?: drive.getOrCreateShoppingFolder(rootId).also { shoppingFolderId = it }
                }.onFailure {
                    Log.w(TAG, "shopping folder resolve failed", it)
                }.getOrNull() ?: shoppingFolderId ?: persistedShoppingFolderId()
            } else {
                shoppingFolderId ?: persistedShoppingFolderId()
            }

            if (folderId == null) {
                // No way to locate the cache (offline + never resolved). Degrade to empty, no error.
                _state.update { it.copy(isLoading = false, isSyncing = false) }
                return@launch
            }
            shoppingFolderId = folderId
            persistShoppingFolderId(folderId)
            _state.update { it.copy(folderId = folderId) }

            // Phase 1 — instant: the derived view paints from the store by itself; this probe
            // only decides whether the cold-load spinner can clear before Drive answers.
            val hasCache = runCatching { itemStore.itemsFor(folderId).isNotEmpty() }.getOrDefault(false)
            if (hasCache) _state.update { it.copy(isLoading = false) }

            // Phase 2 — refresh from Drive.
            _state.update { it.copy(isSyncing = true) }
            if (!online) {
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
                // Store write first (the derived view follows via invalidation), then clear
                // the flags — so a cold load never flashes empty.
                saveLocalCache(folderId, items)
                _state.update { it.copy(isLoading = false, isSyncing = false) }
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
     * Moves wishlist items into a real closet. Optimistic: re-homes the store rows into the
     * target folder *before* any Drive call — the derived wishlist drops them and the target
     * closet shows them instantly — and hands the moved items to [onMoved] (which sets the
     * wardrobe VM's recently-moved markers). The Drive moves are queued [ITEM_MOVE_KIND]
     * mutations; a permanently failed move is undone event-style — the handler re-homes the
     * Room row back (restoring the wishlist via invalidation) and both this VM and the
     * wardrobe VM react to its `moveRolledBack` flow (no failure callback can survive a queued
     * retry that may complete in a later session).
     */
    fun moveToCloset(
        driveIds: Set<String>,
        targetFolderId: String,
        onMoved: (List<DriveImage>) -> Unit,
    ) {
        if (driveIds.isEmpty()) { onMoved(emptyList()); return }
        val sourceFolderId = shoppingFolderId ?: run { onMoved(emptyList()); return }
        if (sourceFolderId == targetFolderId) {
            onMoved(_state.value.items.filter { it.driveId in driveIds })
            return
        }

        val toMove = _state.value.items.filter { it.driveId in driveIds }
        if (toMove.isEmpty()) { onMoved(emptyList()); return }

        // ---- Optimistic local update (before any network call) ----
        // Re-home the moved items into the target closet's store rows (the primary key pulls
        // them out of `_shopping/` atomically) — the derived wishlist drops them and the
        // target closet shows them via invalidation. [onMoved] still tells the wardrobe VM,
        // which sets its recently-moved markers (its own addAll is an idempotent repeat).
        _state.update { s -> s.copy(isMoving = true, selectedIds = emptySet(), error = null) }
        val moved = toMove.map { it.copy(folderId = targetFolderId) }
        onMoved(moved)

        // ---- Queue the Drive moves (refactor § 2): store write first — still before any
        // Drive call — so the move survives a restart; the [ITEM_MOVE_KIND] mutations retry
        // transient failures, and a permanent failure comes back through the handler's
        // rollback + the `moveRolledBack` collector in init (the wardrobe VM's own collector
        // undoes the registration that [onMoved] performed on its side). ----
        viewModelScope.launch {
            runCatching { itemStore.addAll(targetFolderId, moved.map { it.toCachedItem() }) }
            toMove.forEach { item ->
                mutationStore.enqueue(
                    ITEM_MOVE_KIND,
                    targetId = item.driveId,
                    folderId = targetFolderId,
                    payload = gson.toJson(
                        MoveItemPayload(sourceFolderId, targetFolderId, item.originalDriveId, item.sidecarDriveId),
                    ),
                )
            }
            withContext(Dispatchers.IO) { syncEngine.drain() }
            _state.update { it.copy(isMoving = false) }
        }
    }

    fun deleteItems(driveIds: Set<String>) {
        if (driveIds.isEmpty()) return
        val folderId = shoppingFolderId ?: return
        val toDelete = _state.value.items.filter { it.driveId in driveIds }
        if (toDelete.isEmpty()) { _state.update { it.copy(selectedIds = emptySet()) }; return }
        // Optimistic: drop the store rows immediately (the derived view vanishes them via
        // invalidation), then queue the Drive deletes (refactor § 2 — [ITEM_DELETE_KIND]
        // retries instead of orphaning the files on failure).
        _state.update { s -> s.copy(selectedIds = emptySet(), error = null) }
        viewModelScope.launch {
            // Store write first — still before any Drive call — so the delete survives a restart.
            runCatching { itemStore.remove(folderId, driveIds) }
            toDelete.forEach { item ->
                val fileIds = listOfNotNull(item.driveId, item.originalDriveId, item.sidecarDriveId)
                mutationStore.enqueue(
                    ITEM_DELETE_KIND,
                    targetId = item.driveId,
                    folderId = folderId,
                    payload = gson.toJson(DeleteItemPayload(fileIds)),
                )
            }
            withContext(Dispatchers.IO) { syncEngine.drain() }
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
            withContext(Dispatchers.IO) {
                persistItemTags(driveId, tags)
                saveSidecar(driveId)
            }
            _state.update { it.copy(processingImageId = null) }
        }
    }

    fun updateTags(driveId: String, tags: ClothingTags) {
        viewModelScope.launch(Dispatchers.IO) {
            persistItemTags(driveId, tags)
            saveSidecar(driveId)
        }
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
            }.onSuccess {
                // Same driveId, new bytes — bump the Coil version; the derived view re-emits.
                bumpImageVersion(driveId)
                _state.update { it.copy(processingImageId = null) }
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
                // Same driveId, new bytes — bump the Coil version; the derived view re-emits.
                bumpImageVersion(driveId)
                withContext(Dispatchers.IO) {
                    drive.cachedFile(img.driveId)?.let { drive.updateImage(img.driveId, it) }
                    img.originalDriveId?.let { origId ->
                        drive.cachedFile(origId)?.let { drive.updateImage(origId, it) }
                    }
                }
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
        val originalDriveId = item.originalDriveId ?: return@withContext null
        val local = File(drive.cacheDir, "${cutoutDriveId}_original.jpg")
        if (local.exists()) return@withContext local.absolutePath
        val downloaded = runCatching { drive.downloadToCache(originalDriveId) }.getOrNull() ?: return@withContext null
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

    /** Store-first tag write for [driveId] (the derived view follows via invalidation). */
    private suspend fun persistItemTags(driveId: String, tags: ClothingTags) {
        val (fid, item) = itemStore.find(driveId) ?: return
        runCatching { itemStore.upsert(fid, item.copy(tags = tags)) }
    }

    /**
     * Writes the item's sidecar to Drive from its *store row* (callers must write the row
     * first — [persistItemTags]), then stamps the resulting sidecar id back onto the row.
     * Still a direct Drive write, not a § 2 queued mutation — unchanged by slice 4c.
     */
    private suspend fun saveSidecar(driveId: String) = withContext(Dispatchers.IO) {
        val (fid, entry) = itemStore.find(driveId) ?: return@withContext
        val sidecarJson = gson.toJson(ItemSidecar(entry.tags, entry.originalDriveId))
        val sidecarFileId = runCatching {
            drive.upsertSidecar(fid, "$driveId${DriveRepository.SIDECAR_SUFFIX}", sidecarJson)
        }.getOrNull() ?: return@withContext
        runCatching { itemStore.setSidecarId(driveId, sidecarFileId) }
    }

    // ---------- Helpers ----------

    internal suspend fun ensureFolder(): String? = withContext(Dispatchers.IO) {
        runCatching {
            val rootId = rootFolderId ?: drive.getOrCreateFolder().also { rootFolderId = it }
            val resolved = shoppingFolderId ?: drive.getOrCreateShoppingFolder(rootId)
            shoppingFolderId = resolved
            persistShoppingFolderId(resolved)
            _state.update { it.copy(folderId = resolved) }
            resolved
        }.onFailure { e ->
            Log.w(TAG, "ensureFolder failed", e)
            _state.update { it.copy(error = e.message) }
        }.getOrNull()
    }

    /**
     * The `_shopping/` Drive folder ID is persisted across launches so an offline cold start can
     * locate the local cache (which is keyed by this ID). The ID itself is otherwise only
     * obtainable from Drive, which needs the network.
     */
    private fun shoppingPrefs() =
        getApplication<Application>().getSharedPreferences("shopping_closet", Application.MODE_PRIVATE)

    private fun persistedShoppingFolderId(): String? =
        shoppingPrefs().getString("folder_id", null)?.takeIf { it.isNotBlank() }

    private fun persistShoppingFolderId(id: String) {
        shoppingPrefs().edit().putString("folder_id", id).apply()
    }

    internal suspend fun saveLocalCache(id: String, items: List<DriveImage>) {
        runCatching { itemStore.replaceFolder(id, items.map { it.toCachedItem() }) }
    }
}
