package com.librelookai.wardrobe

import android.app.Application
import android.net.Uri
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.librelookai.R
import com.librelookai.data.drive.DriveRepository
import com.librelookai.data.session.UserPreferencesRepository
import com.librelookai.gemini.ClothingTags
import com.librelookai.gemini.CutoutFixActions
import com.librelookai.ml.EmbeddingService
import com.librelookai.service.JobLock
import com.librelookai.settings.AppLanguage
import com.librelookai.util.localized
import dagger.hilt.android.lifecycle.HiltViewModel
import java.io.File
import javax.inject.Inject
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

/**
 * The wardrobe grid's UI surface (refactor § 5 slice 9): every long-lived concern lives on a
 * shared singleton — the load core on [WardrobeRepository], photo ingestion on
 * [ItemIngestionPipeline], the per-item ops on [WardrobeItemOps], the bulk maintenance ops on
 * their use-cases — and this VM mirrors their flows into [WardrobeUiState], keeps the
 * per-instance UI state (selection, view, find-by-photo, URL-import picker) and delegates the
 * entry points. Mutations are store-first (§ 5 slice 4a) — the derived view follows via Room
 * invalidation.
 */
@HiltViewModel
class WardrobeViewModel @Inject constructor(
    app: Application,
    private val drive: DriveRepository,
    private val pipeline: ItemIngestionPipeline,
    private val itemOps: WardrobeItemOps,
    private val repo: WardrobeRepository,
    private val jobLock: JobLock,
    private val moveSync: WardrobeMoveSyncHandler,
    private val retagUseCase: RetagAllUseCase,
    private val removeBgUseCase: RemoveAllBackgroundsUseCase,
    private val webpConvertUseCase: WebpConvertUseCase,
    private val cutoutFixUseCase: CutoutBgFixUseCase,
    prefsRepo: UserPreferencesRepository,
) : AndroidViewModel(app) {

    private val _state = MutableStateFlow(WardrobeUiState())
    val state: StateFlow<WardrobeUiState> = _state.asStateFlow()

    // ---- Progress passthroughs (§ 5 slice 7 prune) ----
    // Surfaces read the owning singleton's StateFlow directly through these (no copy, no
    // collectors). [RemoveAllBackgroundsUseCase]'s progress has no UI reader, so it isn't exposed.
    val ingestionProgress: StateFlow<IngestionProgress> = pipeline.progress
    val retagProgress: StateFlow<BulkAiProgress> = retagUseCase.progress
    val convertProgress: StateFlow<ConvertProgress> = webpConvertUseCase.progress
    val cutoutBgFixProgress: StateFlow<CutoutBgFixProgress?> = cutoutFixUseCase.progress

    /** The active closet's folder id, or null in All-locations mode. */
    private val folderId: String? get() = repo.folderId
    /** When non-null, all new photo imports target this folder instead of the active view folder. */
    private val importFolderId: String? get() = repo.importTargetFolderId.value

    /** Gemini-facing language name (e.g. "English", "German") for localized tag search. */
    private var geminiLanguage: String = "English"
    /** Mirrors UserPreferences.debugSimilarityPreview — when on, similarity searches return up to
     *  50 matches so the debug breakdown has more candidates to scroll through. */
    private var debugSimilarityPreview: Boolean = false

    init {
        // The pipeline's own progress fields are read straight off [ingestionProgress] since
        // the § 5 slice 7 prune; only the fields *shared* with the per-item ops
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
        // The per-item ops' shared slots mirror as transitions too — each singleton write is
        // one pre-extraction in-VM write, which was last-writer-wins against the pipeline's.
        viewModelScope.launch {
            var prev = ItemOpProgress()
            itemOps.progress.collect { p ->
                _state.update { s ->
                    s.copy(
                        isProcessing = if (p.isProcessing != prev.isProcessing) p.isProcessing else s.isProcessing,
                        isUploading = if (p.isUploading != prev.isUploading) p.isUploading else s.isUploading,
                        processingImageId = if (p.processingImageId != prev.processingImageId) p.processingImageId
                            else s.processingImageId,
                    )
                }
                prev = p
            }
        }
        // Pipeline / per-item-op failures land in the same error slot the in-VM writes used
        // (null = clear).
        viewModelScope.launch {
            pipeline.errors.collect { msg -> _state.update { it.copy(error = msg) } }
        }
        viewModelScope.launch {
            itemOps.errors.collect { msg -> _state.update { it.copy(error = msg) } }
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

    fun dismissBatteryExemptionWarning() = jobLock.dismissBatteryWarning()

    /** Set the folder that new photo imports target. Null = fall back to the active view folder. */
    fun setDefaultImportFolderId(folderId: String?) {
        repo.setDefaultImportFolderId(folderId)
        _state.update { it.copy(importTargetFolderId = folderId) }
    }

    fun clearError() = _state.update { it.copy(error = null) }

    // ---------- Navigation ----------

    /** One-shot events the grid consumes (scroll-to-item); buffered so a cross-tab request fired
     *  before the grid mounts (Similarity Finder / shopping "Show in wardrobe") still lands. */
    private val _events = Channel<WardrobeEvent>(Channel.BUFFERED)
    val events = _events.receiveAsFlow()

    fun requestScrollToImage(driveId: String) {
        _events.trySend(WardrobeEvent.ScrollToItem(driveId))
    }

    // ---------- Photo import (delegates to [ItemIngestionPipeline], § 5 slice 5) ----------

    fun uploadPhoto(rawFile: File) {
        val id = (importFolderId ?: folderId) ?: run {
            _state.update { it.copy(view = WardrobeView.GRID) }
            return
        }
        pipeline.ingest(rawFile, id, skippableLocalReview = true, source = AddSource.CAMERA)
    }

    fun uploadGalleryPhotos(uris: List<Uri>) {
        if (uris.isEmpty()) return
        val id = (importFolderId ?: folderId) ?: return
        pipeline.uploadGalleryPhotos(uris, id)
    }

    /** User confirmed they want to import despite a similarity match. */
    fun confirmDuplicateImport() = pipeline.confirmDuplicateImport()

    /** User cancelled the import — discard the raw file and clear the check. */
    fun cancelDuplicateImport() = pipeline.cancelDuplicateImport()

    /** User accepted the on-device cutout for the head item of the review queue. */
    fun applyLocalBgCutout(cutoutFile: File) = pipeline.applyLocalBgCutout(cutoutFile)

    /** User declined the on-device cutout — fall back to the regular Gemini path. */
    fun skipLocalBgReview() = pipeline.skipLocalBgReview()

    /** User cancelled this import entirely — discard the raw file and advance the queue. */
    fun cancelLocalBgReview() = pipeline.cancelLocalBgReview()

    // ---------- Upload from URL ----------

    /**
     * Fetches the hero product image from a shopping URL and runs it through the standard
     * camera-import pipeline so the resulting wardrobe item gets bg removal + tagging + sidecar
     * exactly like a captured photo. See [WebProductFetcher] for the parser strategy. Surfaces
     * a clear error in [WardrobeUiState.error] when no image can be found.
     */
    fun importFromUrl(url: String) {
        if (url.isBlank()) return
        val targetId = (importFolderId ?: folderId) ?: return
        viewModelScope.launch {
            _state.update { it.copy(isUploading = true, error = null) }
            val result = WebProductFetcher.fetchImageCandidates(url)
            if (result == null) {
                _state.update {
                    it.copy(
                        isUploading = false,
                        error = getApplication<Application>().localized().getString(R.string.url_import_failed),
                    )
                }
                return@launch
            }
            // Always present the picker (even on empty candidates the WebView fallback opens).
            _state.update {
                it.copy(
                    isUploading = false,
                    urlImportPicker = UrlImportPickerState(
                        pageUrl = result.pageUrl,
                        candidates = result.candidates,
                        targetFolderId = targetId,
                    ),
                )
            }
        }
    }

    /** Picker callback: download [absoluteImageUrl] and run the standard URL-import pipeline. */
    fun confirmUrlImportPick(absoluteImageUrl: String) {
        val picker = _state.value.urlImportPicker ?: return
        val targetId = picker.targetFolderId ?: return
        viewModelScope.launch {
            _state.update { it.copy(urlImportPicker = picker.copy(isDownloading = true)) }
            val image = WebProductFetcher.downloadImage(absoluteImageUrl, picker.pageUrl, drive.cacheDir)
            if (image == null) {
                _state.update {
                    it.copy(
                        urlImportPicker = picker.copy(isDownloading = false),
                        error = getApplication<Application>().localized().getString(R.string.url_import_failed),
                    )
                }
                return@launch
            }
            _state.update { it.copy(urlImportPicker = null) }
            // URL imports always go through on-device review so the user can refine the seed.
            pipeline.ingest(
                rawFile = image,
                folderId = targetId,
                skippableLocalReview = false,
                forceLocalReview = true,
                source = AddSource.URL,
            )
        }
    }

    fun cancelUrlImport() = _state.update { it.copy(urlImportPicker = null) }

    // ---------- Capture / find-by-photo / text search ----------

    fun openCapture() = _state.update { it.copy(view = WardrobeView.CAPTURE) }
    fun closeCapture() = _state.update { it.copy(view = WardrobeView.GRID) }

    /** Open the camera in "find item by photo" mode. */
    fun openFindByPhoto() = _state.update { it.copy(view = WardrobeView.FIND_BY_PHOTO_CAPTURE) }

    /** User cancelled the find-by-photo capture without taking a photo. */
    fun closeFindByPhoto() = _state.update { it.copy(view = WardrobeView.GRID) }

    /**
     * Run the on-device similarity search after the user captured a photo for "find item by
     * photo". Returns to GRID immediately and populates [WardrobeUiState.findByPhoto] so the
     * results sheet appears once the search resolves.
     */
    fun onFindByPhotoCaptured(rawFile: File) {
        viewModelScope.launch {
            _state.update { it.copy(
                view = WardrobeView.GRID,
                findByPhoto = FindByPhoto(queryPath = rawFile.absolutePath, matches = emptyList(), isSearching = true),
            ) }
            // Always search across every configured closet so matches are not artificially
            // limited by the active location filter. The snapshot is store-derived and
            // always current (§ 5 slice 4a).
            val result = findByPhotoSearch(
                rawFile = rawFile,
                pool = _state.value.allLocationImages,
                cacheDir = drive.cacheDir,
                debugOutputDir = File(getApplication<Application>().cacheDir, QUERY_DEBUG_DIR),
                topK = if (debugSimilarityPreview) 50 else 12,
            )
            _state.update { it.copy(findByPhoto = result) }
        }
    }

    /** Dismiss the find-by-photo results sheet and discard the query thumbnail. */
    fun dismissFindByPhoto() {
        _state.value.findByPhoto?.let { fbp ->
            fbp.queryPath?.let { runCatching { File(it).delete() } }
            fbp.processedPath?.let { runCatching { File(it).delete() } }
        }
        _state.update { it.copy(findByPhoto = null) }
    }

    /** Embed [rawFile] and score it against the index restricted to [candidates] — the composer
     *  slot pickers' in-flow "find by photo" filter. Does not mutate VM state. */
    suspend fun findSimilarInCandidates(
        rawFile: File,
        candidates: List<DriveImage>,
        topK: Int = 50,
    ): List<EmbeddingService.Match> = searchSimilarInCandidates(rawFile, candidates, drive.cacheDir, topK)

    /** Search wardrobe tags by fuzzy text match (localized + canonical forms). Surfaces
     *  results through the shared find-by-photo bottom sheet. */
    fun searchByText(query: String) {
        val q = query.trim()
        if (q.isEmpty()) return
        viewModelScope.launch {
            _state.update { it.copy(
                view = WardrobeView.GRID,
                findByPhoto = FindByPhoto(textQuery = q, matches = emptyList(), isSearching = true),
            ) }
            // The cross-closet snapshot is store-derived and always current (§ 5 slice 4a).
            val pool = _state.value.allLocationImages
            val localeCtx = localizedSearchContext(getApplication(), geminiLanguage)
            val matches = withContext(Dispatchers.Default) { fuzzyMatchByTags(q, pool, localeCtx) }
            _state.update {
                it.copy(findByPhoto = FindByPhoto(
                    textQuery = q,
                    matches = matches,
                    isSearching = false,
                ))
            }
        }
    }

    /** Synchronous variant exposed to the wardrobe grid so a text query can act as an
     *  inline filter (alongside tag chips) instead of opening the find-by-photo sheet. */
    fun fuzzyFilterByText(query: String, items: List<DriveImage>): List<DriveImage> {
        val q = query.trim()
        if (q.isEmpty()) return items
        val localeCtx = localizedSearchContext(getApplication(), geminiLanguage)
        return fuzzyMatchByTags(q, items, localeCtx).map { it.image }
    }

    // ---------- Per-item ops (delegates to [WardrobeItemOps]) ----------

    suspend fun ensureOriginalCached(cutoutDriveId: String): String? = itemOps.ensureOriginalCached(cutoutDriveId)

    fun reprocessBackground(driveId: String) = itemOps.reprocessBackground(driveId)

    fun tagImage(driveId: String) = itemOps.tagImage(driveId)

    fun updateTags(driveId: String, tags: ClothingTags) = itemOps.updateTags(driveId, tags)

    fun rotateImage(driveId: String) = itemOps.rotateImage(driveId)

    fun fixCutoutBgForItem(driveId: String, actions: CutoutFixActions) =
        itemOps.fixCutoutBgForItem(driveId, actions)

    // ---------- Bulk maintenance (delegates to the § 5 slice 6 use-cases) ----------

    /** Delegates to the [RetagAllUseCase]; surfaces read its progress via [retagProgress]. */
    fun retagAll() = retagUseCase.start(_state.value.images)

    /** Delegates to the [RemoveAllBackgroundsUseCase] (progress has no UI reader). */
    fun removeAllBackgrounds() = removeBgUseCase.start(_state.value.images, folderId)

    /** Settings ▸ "Convert images to WebP" — hands the use-case the currently-displayed items
     *  so it can stamp their renamed filenames onto the store rows. */
    fun convertImagesToWebp(closetFolderIds: List<String>) =
        webpConvertUseCase.start(closetFolderIds, _state.value.images)

    fun startCutoutBgFixScan(folderIds: List<String>) = cutoutFixUseCase.startScan(folderIds)

    /** Apply fixes for the currently selected entries, or just clear state if [process] is false. */
    fun continueCutoutBgFix(process: Boolean) = cutoutFixUseCase.continueFix(process)

    fun toggleCutoutFixSelection(driveId: String) = cutoutFixUseCase.toggleSelection(driveId)

    fun setCutoutFixSelection(ids: Set<String>) = cutoutFixUseCase.setSelection(ids)

    fun setCutoutFixAction(
        blackToAlpha: Boolean? = null,
        despillGreen: Boolean? = null,
        feather: Boolean? = null,
        tightCrop: Boolean? = null,
    ) = cutoutFixUseCase.setAction(blackToAlpha, despillGreen, feather, tightCrop)

    fun setCutoutFixShowAll(v: Boolean) = cutoutFixUseCase.setShowAll(v)

    fun dismissCutoutBgFix() = cutoutFixUseCase.dismiss()

    suspend fun fetchCutoutFixThumbnail(entry: CutoutFixEntry): File? = cutoutFixUseCase.fetchThumbnail(entry)

    // ---------- Move to another location ----------

    fun moveItemsToLocation(driveIds: Set<String>, targetFolderId: String) {
        // Skip no-op moves (item already in the target closet) but still clear the selection.
        val toMove = _state.value.images
            .filter { it.driveId in driveIds }
            .filter { (it.folderId.ifEmpty { folderId }) != targetFolderId }
        if (toMove.isEmpty()) { _state.update { it.copy(selectedIds = emptySet()) }; return }

        // Optimistic local update (before any network call): the repo re-homes the items into
        // the target closet's store rows immediately, so the derived view drops them from the
        // source closet and the target shows them instantly.
        _state.update { it.copy(isMoving = true, selectedIds = emptySet(), error = null) }
        notifyItemsMovedTo(targetFolderId, toMove.map { it.copy(folderId = targetFolderId) })

        // Queue the Drive moves (refactor § 2) — see [WardrobeRepository.enqueueMoves]; a move
        // that exhausts its retries comes back via the `moveRolledBack` collector in init.
        viewModelScope.launch {
            repo.enqueueMoves(toMove, targetFolderId)
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

    fun deleteItems(driveIds: Set<String>) {
        if (driveIds.isEmpty()) return
        val items = _state.value.images.filter { it.driveId in driveIds }
        _state.update { it.copy(selectedIds = emptySet()) }
        if (items.isEmpty()) return
        // Optimistic local delete + queued Drive deletes (refactor § 2) — see
        // [WardrobeRepository.deleteItems].
        viewModelScope.launch { repo.deleteItems(items) }
    }
}
