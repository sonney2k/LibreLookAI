package com.librelookai.wardrobe
import com.librelookai.util.localized

import android.app.Application
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Matrix
import android.util.Log
import androidx.lifecycle.AndroidViewModel
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import androidx.lifecycle.viewModelScope
import com.google.gson.Gson
import com.librelookai.data.local.PendingMutationStore
import com.librelookai.data.local.WardrobeItemStore
import com.librelookai.data.session.UserPreferencesRepository
import com.librelookai.settings.AppLanguage
import com.librelookai.R
import com.librelookai.data.drive.DriveRepository
import com.librelookai.data.drive.SyncEngine
import com.librelookai.gemini.ClothingTags
import com.librelookai.gemini.GeminiRepository
import com.librelookai.gemini.classifyClothing
import com.librelookai.service.JobLock
import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

internal val FUZZY_TOKEN_SPLIT = Regex("[^\\p{L}\\p{Nd}]+")

/**
 * The wardrobe grid's UI surface (refactor § 5 slice 9): the load core — scope, two-phase
 * Drive load, sync flags, derived item flows, prefetch, recently-moved markers — lives on the
 * shared [WardrobeRepository]; this VM mirrors the repo flows into [WardrobeUiState] and keeps
 * the per-instance UI state (selection, view, find-by-photo) plus the per-item mutation entry
 * points (all store-first, § 5 slice 4a — the derived view follows via Room invalidation).
 */
@HiltViewModel
class WardrobeViewModel @Inject constructor(
    app: Application,
    internal val drive: DriveRepository,
    internal val gemini: GeminiRepository,
    internal val itemStore: WardrobeItemStore,
    private val mutationStore: PendingMutationStore,
    private val syncEngine: SyncEngine,
    private val moveSync: WardrobeMoveSyncHandler,
    internal val pipeline: ItemIngestionPipeline,
    private val jobLock: JobLock,
    private val itemVersions: ItemVersions,
    internal val repo: WardrobeRepository,
    private val retagUseCase: RetagAllUseCase,
    private val removeBgUseCase: RemoveAllBackgroundsUseCase,
    internal val webpConvertUseCase: WebpConvertUseCase,
    internal val cutoutFixUseCase: CutoutBgFixUseCase,
    prefsRepo: UserPreferencesRepository,
) : AndroidViewModel(app) {

    companion object {
        internal const val TAG = "RepairAndSync"
    }
    internal val gson = Gson()

    // Process keepalive moved to the shared [JobLock] singleton (refactor § 5 slice 5) so the
    // ingestion pipeline and the VM's remaining bulk workflows share one refcount. These thin
    // delegates keep the extension-file call sites (bg-fix / convert) intact.
    internal fun acquireJobWakeLock() = jobLock.acquire()

    internal fun releaseJobWakeLock() = jobLock.release()

    fun dismissBatteryExemptionWarning() = jobLock.dismissBatteryWarning()

    internal val _state = MutableStateFlow(WardrobeUiState())
    val state: StateFlow<WardrobeUiState> = _state.asStateFlow()

    // ---- Progress passthroughs (§ 5 slice 7 prune) ----
    // The pipeline / bulk-op progress used to be mirrored into [WardrobeUiState]; surfaces now
    // read the owning singleton's StateFlow directly through these (no copy, no collectors).
    // [RemoveAllBackgroundsUseCase]'s progress has no UI reader, so it isn't exposed.
    val ingestionProgress: StateFlow<IngestionProgress> = pipeline.progress
    val retagProgress: StateFlow<BulkAiProgress> = retagUseCase.progress
    val convertProgress: StateFlow<ConvertProgress> = webpConvertUseCase.progress
    val cutoutBgFixProgress: StateFlow<CutoutBgFixProgress?> = cutoutFixUseCase.progress

    // ---- Load-core delegates (§ 5 slice 9) ----
    // Scope + import target live on the shared [WardrobeRepository]; the extension files and
    // the per-item ops read them through these.
    /** The active closet's folder id, or null in All-locations mode. */
    internal val folderId: String? get() = repo.folderId
    /** When non-null, all new photo imports target this folder instead of the active view folder. */
    internal val defaultImportFolderId: String? get() = repo.importTargetFolderId.value

    /** Gemini-facing language name (e.g. "English", "German") for label generation. */
    internal var geminiLanguage: String = "English"
    /** Mirrors UserPreferences.debugSimilarityPreview — when on, similarity searches return up to
     *  50 matches so the debug breakdown has more candidates to scroll through. */
    internal var debugSimilarityPreview: Boolean = false

    // The Coil-version overlay is a shared singleton ([ItemVersions], § 5 slice 6) so the
    // bulk use-cases can write it; the repo's derivations merge it in.
    internal fun bumpImageVersion(vararg driveIds: String) = itemVersions.bump(*driveIds)

    init {
        // The pipeline's own progress fields are read straight off [ingestionProgress] since
        // the § 5 slice 7 prune; only the fields *shared* with the VM's own per-item ops
        // (`isUploading` / `processingImageId`) plus the grid-return flip still land in
        // [WardrobeUiState], applied as *transitions* — each transition corresponds to exactly
        // one pre-extraction in-VM write, including the guarded swap/clear semantics the queue
        // worker used.
        viewModelScope.launch {
            var prev = IngestionProgress()
            pipeline.progress.collect { p ->
                _state.update { s ->
                    s.copy(
                        // Return to the grid exactly when the old code flipped it (dedupe
                        // pass / upload start — not gallery batches, which may run under
                        // the capture screen).
                        view = if (p.gridReturnTick != prev.gridReturnTick) WardrobeView.GRID else s.view,
                        isUploading = if (p.isUploading != prev.isUploading) p.isUploading else s.isUploading,
                        processingImageId = when {
                            // No pipeline transition — leave whatever a per-item op set.
                            p.processingImageId == prev.processingImageId -> s.processingImageId
                            // Queue finished its item: guarded clear (old loop-end / step-8 clear).
                            p.processingImageId == null ->
                                if (s.processingImageId == prev.processingImageId) null else s.processingImageId
                            // Queue picked up a job: unconditional (old worker-start write).
                            prev.processingImageId == null -> p.processingImageId
                            // Raw → cutout id swap: guarded (old step-6 write).
                            else ->
                                if (s.processingImageId == prev.processingImageId) p.processingImageId else s.processingImageId
                        },
                    )
                }
                prev = p
            }
        }
        // Pipeline failures land in the same error slot the in-VM writes used (null = clear).
        viewModelScope.launch {
            pipeline.errors.collect { msg -> _state.update { it.copy(error = msg) } }
        }
        viewModelScope.launch {
            jobLock.needsBatteryExemption.collect { needs ->
                _state.update { it.copy(needsBatteryExemption = needs) }
            }
        }
        // Derived read path (§ 5 slice 4a, on the repo since slice 9): mirror the repo's
        // store-derived flows into this instance's UI state.
        viewModelScope.launch {
            repo.images.collect { images -> _state.update { it.copy(images = images) } }
        }
        viewModelScope.launch {
            repo.allLocationImages.collect { merged -> _state.update { it.copy(allLocationImages = merged) } }
        }
        viewModelScope.launch {
            repo.importTargetFolderId.collect { id -> _state.update { it.copy(importTargetFolderId = id) } }
        }
        // Phase-2 sync flags. The repo's load error lands in the shared `error` slot as a
        // *transition* (new failures only), so it can't resurrect a banner a per-item op or
        // clearError already dismissed.
        viewModelScope.launch {
            var prevError: String? = null
            repo.syncStatus.collect { st ->
                _state.update { s ->
                    s.copy(
                        isLoading = st.isLoading,
                        isSyncing = st.isSyncing,
                        syncTotal = st.syncTotal,
                        syncDone = st.syncDone,
                        syncPhase = st.syncPhase,
                        error = if (st.error != null && st.error != prevError) st.error else s.error,
                    )
                }
                prevError = st.error
            }
        }
        // A closet switch resets the per-scope UI state (selection, find-by-photo, capture
        // view, transient flags) — the VM half of the old wholesale `setLocation` state reset;
        // the mirrored repo fields are carried over (the repo blanks/repaints them itself).
        viewModelScope.launch {
            repo.scopeChanges.drop(1).collect {
                _state.update { s ->
                    WardrobeUiState(
                        images = s.images,
                        allLocationImages = s.allLocationImages,
                        isLoading = s.isLoading,
                        isSyncing = s.isSyncing,
                        syncTotal = s.syncTotal,
                        syncDone = s.syncDone,
                        syncPhase = s.syncPhase,
                        needsBatteryExemption = s.needsBatteryExemption,
                        importTargetFolderId = s.importTargetFolderId,
                    )
                }
            }
        }
        // Mirror the UserPreferences-derived knobs (tagging language, similarity debug) from the
        // shared preferences repository — replaces the per-pref AppContent mirrors (§ 5 slice 2).
        // The dedupe / on-device-bg-review knobs live on [ItemIngestionPipeline] (slice 5).
        viewModelScope.launch {
            prefsRepo.preferences.collect { p ->
                geminiLanguage = AppLanguage.toGeminiName(p.language)
                debugSimilarityPreview = p.debugSimilarityPreview
            }
        }
        // SyncEngine feedback: a queued move exhausted its retries and the handler re-homed the
        // Room row back to its source — the derived view restores/prunes the item by itself, so
        // only the recently-moved marker and the error banner remain to undo here. Also covers
        // shopping→closet moves (the shopping VM restores its own list and shows its own error).
        viewModelScope.launch {
            moveSync.moveRolledBack.collect { rollback ->
                repo.forgetRecentlyMoved(listOf(rollback.driveId))
                if (rollback.sourceFolderId != repo.shoppingFolderId) {
                    _state.update {
                        it.copy(error = getApplication<Application>().localized().getString(R.string.wardrobe_move_failed))
                    }
                }
            }
        }
    }

    /** Set the folder that new photo imports target. Null = fall back to the active view folder. */
    fun setDefaultImportFolderId(folderId: String?) {
        repo.setDefaultImportFolderId(folderId)
        _state.update { it.copy(importTargetFolderId = folderId) }
    }

    // ---------- Cache ----------

    /** Ensures the pre-cutout original for [cutoutDriveId] is cached at the canonical
     *  `${cutoutDriveId}_original.jpg` path and returns its absolute path, or null if no
     *  original exists or the download failed. */
    suspend fun ensureOriginalCached(cutoutDriveId: String): String? = withContext(Dispatchers.IO) {
        val image = _state.value.images.firstOrNull { it.driveId == cutoutDriveId } ?: return@withContext null
        val origId = image.originalDriveId ?: return@withContext null
        val local = File(drive.cacheDir, "${cutoutDriveId}_original.jpg")
        if (local.exists()) return@withContext local.absolutePath
        val downloaded = runCatching { drive.downloadToCache(origId) }.getOrNull() ?: return@withContext null
        runCatching { downloaded.copyTo(local, overwrite = true) }
        local.absolutePath
    }

    // ---------- Sidecar / tag writes ----------

    /**
     * Saves a per-item sidecar JSON for [driveId]: writes the edit to Room (the source of
     * truth), then enqueues a [SIDECAR_SYNC_KIND] mutation the SyncEngine drains to Drive
     * (refactor § 2 — the first converted write path). The handler re-reads the row at apply
     * time, so a retried/deferred drain always writes the *current* tags into the item's
     * *current* folder; transient Drive failures retry instead of silently losing the edit,
     * and the queued row survives process death.
     */
    internal fun saveSidecar(driveId: String) {
        viewModelScope.launch(Dispatchers.IO) { enqueueSidecarSync(driveId) }
    }

    /**
     * Queues the [SIDECAR_SYNC_KIND] mutation for [driveId]'s *current* Room row. Callers must
     * have written their edit to the store first — the row is what the handler (and the
     * derived view) read; there is no state rebuild here any more.
     */
    private suspend fun enqueueSidecarSync(driveId: String) {
        val fid = itemStore.find(driveId)?.first ?: return
        mutationStore.enqueue(SIDECAR_SYNC_KIND, targetId = driveId, folderId = fid, payload = "{}")
        syncEngine.drain()
    }

    /** Store-first tag write for [driveId] (the derived view follows via invalidation). */
    internal suspend fun persistItemTags(driveId: String, tags: ClothingTags) {
        val (fid, item) = itemStore.find(driveId) ?: return
        runCatching { itemStore.upsert(fid, item.copy(tags = tags)) }
    }

    // ---------- Navigation ----------

    /** One-shot events the grid consumes (scroll-to-item); buffered so a cross-tab request fired
     *  before the grid mounts (Similarity Finder / shopping "Show in wardrobe") still lands. */
    private val _events = Channel<WardrobeEvent>(Channel.BUFFERED)
    val events = _events.receiveAsFlow()

    fun requestScrollToImage(driveId: String) {
        _events.trySend(WardrobeEvent.ScrollToItem(driveId))
    }

    // ---------- Per-item AI ops ----------

    fun reprocessBackground(driveId: String) {
        viewModelScope.launch {
            _state.update { it.copy(isProcessing = true, processingImageId = driveId, error = null) }
            val source = resolveOriginalFile(driveId)
                ?: run {
                    _state.update { it.copy(isProcessing = false, error = getApplication<Application>().localized().getString(R.string.error_original_unavailable)) }
                    return@launch
                }
            val processedFile = try {
                gemini.removeBackground(source, drive.cacheDir)
            } catch (e: com.librelookai.billing.InsufficientCreditsException) {
                _state.update { it.copy(isProcessing = false, processingImageId = null) }
                return@launch
            } ?: run { _state.update { it.copy(isProcessing = false, processingImageId = null) }; return@launch }

            _state.update { it.copy(isProcessing = false, isUploading = true) }
            runCatching {
                drive.updateImage(driveId, processedFile)
                val displayCache = File(drive.cacheDir, "$driveId.png")
                processedFile.copyTo(displayCache, overwrite = true)
                displayCache.absolutePath
            }.onSuccess {
                // Same driveId, new bytes — bump the Coil version; the derived view re-emits.
                bumpImageVersion(driveId)
                _state.update { it.copy(isUploading = false, processingImageId = null) }
            }.onFailure { e ->
                _state.update { it.copy(isUploading = false, processingImageId = null, error = e.message) }
            }
        }
    }

    /**
     * Returns the best available original file for [driveId]:
     * 1. Local cache `${driveId}_original.jpg`
     * 2. Drive download via `originalDriveId` (cached locally for future use)
     * 3. The current cached (processed) image as last resort
     */
    private suspend fun resolveOriginalFile(driveId: String): File? {
        val localOriginal = File(drive.cacheDir, "${driveId}_original.jpg")
        if (localOriginal.exists()) return localOriginal

        val originalDriveId = _state.value.images.find { it.driveId == driveId }?.originalDriveId
        if (originalDriveId != null) {
            val downloaded = drive.downloadToCache(originalDriveId)
            if (downloaded != null) {
                downloaded.copyTo(localOriginal, overwrite = true)
                return localOriginal
            }
        }

        return drive.cachedFile(driveId)
    }

    /** Delegates to the [RemoveAllBackgroundsUseCase] (progress has no UI reader). */
    fun removeAllBackgrounds() = removeBgUseCase.start(_state.value.images, folderId)

    fun tagImage(driveId: String) {
        viewModelScope.launch {
            _state.update { it.copy(processingImageId = driveId) }
            val cachedFile = drive.cachedFile(driveId)
                ?: run { _state.update { it.copy(processingImageId = null) }; return@launch }
            val tags = try {
                gemini.classifyClothing(cachedFile, geminiLanguage)
            } catch (e: com.librelookai.billing.InsufficientCreditsException) {
                _state.update { it.copy(processingImageId = null) }
                return@launch
            } ?: run { _state.update { it.copy(processingImageId = null) }; return@launch }
            _state.update { it.copy(processingImageId = null) }
            withContext(Dispatchers.IO) {
                persistItemTags(driveId, tags)
                enqueueSidecarSync(driveId)
            }
        }
    }

    fun updateTags(driveId: String, tags: ClothingTags) {
        viewModelScope.launch(Dispatchers.IO) {
            persistItemTags(driveId, tags)
            enqueueSidecarSync(driveId)
        }
    }

    /** Delegates to the [RetagAllUseCase]; surfaces read its progress via [retagProgress]. */
    fun retagAll() = retagUseCase.start(_state.value.images)

    // ---------- Move to another location ----------

    fun moveItemsToLocation(driveIds: Set<String>, targetFolderId: String) {
        // Skip no-op moves (item already in the target closet) but still clear the selection.
        val toMove = _state.value.images
            .filter { it.driveId in driveIds }
            .filter { (it.folderId.ifEmpty { folderId }) != targetFolderId }
        if (toMove.isEmpty()) { _state.update { it.copy(selectedIds = emptySet()) }; return }

        // ---- Optimistic local update (before any network call) ----
        // Re-home the items into the target closet's store rows immediately (the primary key
        // pulls them out of their source folders atomically), so the derived view drops them
        // from the source closet and the target shows them instantly — no wait on Drive's
        // eventually-consistent listing.
        _state.update { it.copy(isMoving = true, selectedIds = emptySet(), error = null) }
        notifyItemsMovedTo(targetFolderId, toMove.map { it.copy(folderId = targetFolderId) })

        // ---- Queue the Drive moves (refactor § 2): the store re-home above survives a restart;
        // the [ITEM_MOVE_KIND] mutations then retry transient failures instead of rolling back
        // on the first error, and only give up — via the handler's rollback + the
        // [WardrobeMoveSyncHandler.moveRolledBack] collector in init — after the attempts cap. ----
        viewModelScope.launch {
            withContext(Dispatchers.IO) {
                toMove.forEach { item ->
                    val sourceFolderId = item.folderId.ifEmpty { folderId } ?: return@forEach
                    mutationStore.enqueue(
                        ITEM_MOVE_KIND,
                        targetId = item.driveId,
                        folderId = targetFolderId,
                        payload = gson.toJson(
                            MoveItemPayload(sourceFolderId, targetFolderId, item.originalDriveId, item.sidecarDriveId),
                        ),
                    )
                }
                syncEngine.drain()
            }
            _state.update { it.copy(isMoving = false) }
        }
    }

    /**
     * Records that [items] have been moved into [targetFolderId] — delegates to the shared
     * [WardrobeRepository] (store re-home + eventual-consistency suppression marker).
     * Callable from other view models (e.g. shopping move-to-closet).
     */
    fun notifyItemsMovedTo(targetFolderId: String, items: List<DriveImage>) =
        repo.notifyItemsMovedTo(targetFolderId, items)

    fun clearError() = _state.update { it.copy(error = null) }

    // ---------- Selection & Delete ----------

    fun toggleSelection(driveId: String) {
        _state.update { s ->
            val next = s.selectedIds.toMutableSet()
            if (!next.add(driveId)) next.remove(driveId)
            s.copy(selectedIds = next)
        }
    }

    fun selectAll(ids: List<String>) = _state.update { it.copy(selectedIds = it.selectedIds + ids) }

    fun clearSelection() = _state.update { it.copy(selectedIds = emptySet()) }

    fun rotateImage(driveId: String) {
        val img = _state.value.images.find { it.driveId == driveId } ?: return
        viewModelScope.launch {
            acquireJobWakeLock()
            try {
                // Rotate local cache files immediately so the UI can refresh without waiting.
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
                // Bump version so Coil reloads from the already-rotated local cache (no
                // metadata changed, so no store write is needed).
                bumpImageVersion(driveId)
                // Upload rotated files to Drive silently (no processingImageId = no overlay).
                withContext(Dispatchers.IO) {
                    drive.cachedFile(img.driveId)?.let { drive.updateImage(img.driveId, it) }
                    img.originalDriveId?.let { origId ->
                        drive.cachedFile(origId)?.let { drive.updateImage(origId, it) }
                    }
                }
            } catch (e: Exception) {
                Log.e("WardrobeVM", "rotateImage failed", e)
                _state.update { it.copy(error = e.message) }
            } finally {
                releaseJobWakeLock()
            }
        }
    }

    fun deleteSelected() = deleteItems(_state.value.selectedIds)

    fun deleteItems(driveIds: Set<String>) {
        if (driveIds.isEmpty()) return
        val items = _state.value.images.filter { it.driveId in driveIds }
        if (items.isEmpty()) { _state.update { it.copy(selectedIds = emptySet()) }; return }
        // Resolve each item's owning closet (folderId is null in All-locations mode, so fall
        // back to the active folder) — a multi-closet selection can span several caches.
        val affectedFolderIds = items.mapNotNull { it.folderId.ifEmpty { folderId } }.toSet()

        // ---- Optimistic local update: the store removes below vanish the items from every
        // affected closet's derived view at once. ----
        repo.forgetRecentlyMoved(driveIds)
        _state.update { it.copy(selectedIds = emptySet()) }

        // ---- Queue the Drive deletes (refactor § 2): the cache writes come first, still before
        // any Drive call, so the deletion survives a restart — and unlike the old fire-and-forget
        // per-file deletes, a queued [ITEM_DELETE_KIND] mutation retries transient failures
        // instead of silently orphaning the files on Drive. File ids ride in the payload because
        // the Room row is gone before the drain runs. ----
        viewModelScope.launch {
            withContext(Dispatchers.IO) {
                affectedFolderIds.forEach { fid -> runCatching { itemStore.remove(fid, driveIds) } }
                items.forEach { img ->
                    val fileIds = listOfNotNull(img.driveId, img.originalDriveId, img.sidecarDriveId)
                    mutationStore.enqueue(
                        ITEM_DELETE_KIND,
                        targetId = img.driveId,
                        folderId = img.folderId.ifEmpty { folderId },
                        payload = gson.toJson(DeleteItemPayload(fileIds)),
                    )
                }
            }
            withContext(Dispatchers.IO) { syncEngine.drain() }
        }
    }
}

// ---------- Bitmap rotation helper ----------

internal fun rotateBitmapFileBy90(file: File) {
    val bmp = BitmapFactory.decodeFile(file.absolutePath) ?: return
    val matrix = Matrix().apply { postRotate(90f) }
    val rotated = Bitmap.createBitmap(bmp, 0, 0, bmp.width, bmp.height, matrix, true)
    // Re-encode as WebP (alpha-preserving) regardless of the cache file's extension — the
    // rotated bytes are re-uploaded via DriveRepository.updateImage, which sends image/webp.
    com.librelookai.util.ImageEncoding.compressCutout(rotated, file)
}
