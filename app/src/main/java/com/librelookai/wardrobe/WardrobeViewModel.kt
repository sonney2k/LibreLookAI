package com.librelookai.wardrobe
import com.librelookai.util.localized

import android.app.Application
import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Matrix
import android.net.Uri
import android.os.PowerManager
import android.provider.DocumentsContract
import android.util.Log
import androidx.lifecycle.AndroidViewModel
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import androidx.lifecycle.viewModelScope
import com.google.gson.Gson
import com.librelookai.MainActivity
import com.librelookai.data.local.CachedWardrobeItem
import com.librelookai.data.local.PendingMutationStore
import com.librelookai.data.local.WardrobeItemStore
import com.librelookai.data.session.ClosetSession
import com.librelookai.data.session.ClosetSessionHolder
import com.librelookai.data.session.UserPreferencesRepository
import com.librelookai.settings.AppLanguage
import com.librelookai.R
import com.librelookai.data.drive.DriveFileDto
import com.librelookai.data.drive.DriveRepository
import com.librelookai.data.drive.SyncEngine
import com.librelookai.data.drive.await
import com.librelookai.data.drive.loadWardrobeMetadataJson
import com.librelookai.data.drive.listSidecarFiles
import com.librelookai.data.drive.loadFileContent
import com.librelookai.data.drive.upsertSidecar
import com.librelookai.gemini.ClothingTags
import com.librelookai.gemini.CutoutFixActions
import com.librelookai.gemini.CutoutIssues
import com.librelookai.gemini.GeminiRepository
import com.librelookai.gemini.detectCutoutIssues
import com.librelookai.gemini.fixCutoutBackground
import com.librelookai.gemini.classifyClothing
import com.librelookai.ml.EmbeddingService
import com.librelookai.service.JobForegroundService
import com.librelookai.settings.UserPreferences
import com.librelookai.util.ImageEncoding
import com.librelookai.util.isNetworkAvailable
import java.io.File
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

internal val FUZZY_TOKEN_SPLIT = Regex("[^\\p{L}\\p{Nd}]+")

@OptIn(ExperimentalCoroutinesApi::class)
@HiltViewModel
class WardrobeViewModel @Inject constructor(
    app: Application,
    internal val drive: DriveRepository,
    internal val gemini: GeminiRepository,
    internal val itemStore: WardrobeItemStore,
    private val mutationStore: PendingMutationStore,
    private val syncEngine: SyncEngine,
    private val moveSync: WardrobeMoveSyncHandler,
    session: ClosetSessionHolder,
    prefsRepo: UserPreferencesRepository,
) : AndroidViewModel(app) {

    companion object {
        internal const val TAG = "RepairAndSync"
        /** Stricter floor for the Repair & Sync duplicate scan. */
        internal const val REPAIR_DUPE_THRESHOLD = 0.97f
        /** Sidecars at or below this size are {} or {"tags":null} — definitely no tags. */
        internal const val SIDECAR_EMPTY_MAX = 20L
        /** Sidecars at or above this size always contain a ClothingTags object. */
        internal const val SIDECAR_FULL_MIN  = 100L
        /** Cache subdir for the processed-query bitmaps fed to the similarity debug preview. */
        internal const val QUERY_DEBUG_DIR = "wardrobe_query_debug"
    }
    internal val gson = Gson()

    private var jobWakeLock: PowerManager.WakeLock? = null
    private val activeJobCount = AtomicInteger(0)

    internal fun acquireJobWakeLock() {
        if (activeJobCount.getAndIncrement() == 0) {
            // Foreground service keeps the process alive while any job is running
            JobForegroundService.acquire(getApplication())
            // Wake lock keeps the CPU awake if the screen turns off mid-job
            val pm = getApplication<Application>().getSystemService(Context.POWER_SERVICE) as PowerManager
            if (jobWakeLock == null) {
                jobWakeLock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "LibreLookAI:Jobs")
                    .also { it.setReferenceCounted(false) }
            }
            jobWakeLock!!.acquire(30 * 60 * 1000L) // 30-minute safety timeout
            Log.d(TAG, "Wake lock acquired")
            // Warn the user if battery optimization is not disabled — on many devices (especially
            // OEM-customized ROMs) the OS will kill even foreground services unless the app is
            // explicitly exempted from battery optimization.
            if (!pm.isIgnoringBatteryOptimizations(getApplication<Application>().packageName)) {
                _state.update { it.copy(needsBatteryExemption = true) }
            }
        }
    }

    fun dismissBatteryExemptionWarning() {
        _state.update { it.copy(needsBatteryExemption = false) }
    }

    internal fun releaseJobWakeLock() {
        if (activeJobCount.decrementAndGet() == 0) {
            jobWakeLock?.let { if (it.isHeld) it.release() }
            JobForegroundService.release(getApplication())
            Log.d(TAG, "Wake lock released")
        }
    }

    internal val _state = MutableStateFlow(WardrobeUiState())
    val state: StateFlow<WardrobeUiState> = _state.asStateFlow()

    internal var folderId: String? = null
    private var allFolderIds: List<String>? = null

    // ---- Derived read path (refactor § 5 slice 4a) ----
    // [WardrobeUiState.images] and [WardrobeUiState.allLocationImages] are *derived*: the two
    // scope flows below flatMap into [WardrobeItemStore.observeItems], so every store write —
    // by this VM, the shopping VM or a SyncEngine handler — lands in the UI via Room
    // invalidation. Mutations write the store; nothing splices `state.images` any more.
    /** The folder ids the grid currently shows (active closet, or every closet in All mode). */
    private val viewScope = MutableStateFlow<List<String>>(emptyList())
    /** Every configured closet + shopping — the cross-closet similarity-snapshot scope. */
    private val configuredScope = MutableStateFlow<List<String>>(emptyList())
    /**
     * In-view items that are deliberately NOT store rows: import placeholders with synthetic
     * `__importing_*` ids (no Drive file, no cacheable bytes). Appended to the derived view
     * while their folder is in scope; the import loop swaps them for real persisted items.
     */
    internal val transientItems = MutableStateFlow<List<DriveImage>>(emptyList())
    /**
     * Coil cache-buster overlay: bumped whenever an item's image *bytes* change under an
     * unchanged driveId (rotate, reprocess, cutout-fix, WebP convert). The store doesn't hold
     * versions — they are per-process display state — so the derivation merges this map in.
     */
    internal val imageVersions = MutableStateFlow<Map<String, Long>>(emptyMap())

    internal fun bumpImageVersion(vararg driveIds: String) {
        val now = System.currentTimeMillis()
        imageVersions.update { it + driveIds.associateWith { now } }
    }

    /**
     * Every closet folderId the user has configured, regardless of which one is currently
     * displayed. Driven by the [ClosetSessionHolder] collector. Used to scope the cross-closet
     * [WardrobeUiState.allLocationImages] snapshot for similarity search.
     */
    private var allConfiguredFolderIds: List<String> = emptyList()
    /** The `_shopping` folder id within [allConfiguredFolderIds], if any — for diagnostic labelling only. */
    private var shoppingFolderId: String? = null
    /** Gemini-facing language name (e.g. "English", "German") for label generation. */
    internal var geminiLanguage: String = "English"
    /** When non-null, all new photo imports target this folder instead of the active view folder. */
    internal var defaultImportFolderId: String? = null
    /** Mirrors UserPreferences.dedupeOnImport — flips capture/import similarity gate on/off. */
    internal var dedupeOnImport: Boolean = false
    /** Mirrors UserPreferences.dedupeThreshold — cosine cutoff for "this is probably already in your wardrobe". */
    internal var dedupeThreshold: Float = 0.88f
    /** Mirrors UserPreferences.preferLocalBgRemoval — when true, camera/gallery imports route through
     *  the on-device segmenter review screen. URL imports always do. */
    internal var preferLocalBgRemoval: Boolean = false
    /** Mirrors UserPreferences.debugSimilarityPreview — when on, similarity searches return up to
     *  50 matches so the debug breakdown has more candidates to scroll through. */
    internal var debugSimilarityPreview: Boolean = false

    /** Holds the audit scan results while waiting for the user to confirm processing. */
    internal var pendingAudit: AuditIntermediate? = null

    /** Active loadImages coroutine — cancelled when a new location is selected. */
    private var loadJob: Job? = null

    /**
     * Tracks items recently moved into another closet. Drive's `q='<folder>' in parents` query
     * is eventually consistent — after a move PATCH both the destination and the source folder
     * listings can lag for minutes. We use this map to (a) re-inject moved items into the TARGET
     * folder's Phase 2 results while Drive catches up, so they don't briefly disappear after
     * [setLocation] to the target, and (b) suppress them from the SOURCE folder's Phase 2 results,
     * so the just-moved item doesn't flicker back into the closet it left (or appear in both).
     * Keyed by cutout driveId → (targetFolderId, expiresAtMs).
     */
    private val recentlyMovedItems = java.util.concurrent.ConcurrentHashMap<String, Pair<String, Long>>()
    // Drive's `'<folder>' in parents` listing can lag well past a minute — testers have seen a
    // moved item still listed in its old parent for ~5 min. Keep the suppression window generous
    // so the item never flickers back into the source closet before Drive catches up.
    private val recentlyMovedTtlMs: Long = 10 * 60 * 1000L

    private fun pruneRecentlyMoved() {
        val now = System.currentTimeMillis()
        recentlyMovedItems.entries.removeAll { it.value.second < now }
    }

    /**
     * Background processing queue. Each item represents one uploaded photo that needs
     * bg removal + classification. Processed serially so metadata writes are consistent.
     */
    internal val workQueue = Channel<PendingJob>(Channel.UNLIMITED)

    init {
        viewModelScope.launch { processQueue() }
        // Derived view (§ 5 slice 4a): the grid's items are the store rows in the current view
        // scope (with a cached image file), plus any in-scope import placeholders, stamped with
        // the Coil version overlay. File stats run on IO via flowOn; redundant emissions are
        // free (StateFlow suppresses equal states).
        viewModelScope.launch {
            combine(
                viewScope.flatMapLatest { ids -> itemStore.observeItems(ids).map { ids to it } },
                transientItems,
                imageVersions,
            ) { (scopeIds, byFolder), transient, versions ->
                val stored = byFolder.flatMap { (fid, items) ->
                    items.mapNotNull { it.toDriveImage(fid, versions) }
                }
                val storedIds = stored.mapTo(HashSet()) { it.driveId }
                stored + transient.filter { it.folderId in scopeIds && it.driveId !in storedIds }
            }
                .flowOn(Dispatchers.IO)
                .collect { images -> _state.update { it.copy(images = images) } }
        }
        // Derived cross-closet snapshot: same pipeline over every configured closet + shopping.
        // Replaces the refreshAllLocationImagesState()/snapshotJob rebuilds — any store write
        // (incl. the shopping VM's and the sync handlers') refreshes it via invalidation.
        viewModelScope.launch {
            combine(
                configuredScope.flatMapLatest { ids -> itemStore.observeItems(ids) },
                imageVersions,
            ) { byFolder, versions ->
                byFolder.flatMap { (fid, items) -> items.mapNotNull { it.toDriveImage(fid, versions) } }
            }
                .flowOn(Dispatchers.IO)
                .collect { merged -> _state.update { it.copy(allLocationImages = merged) } }
        }
        // Derive the load scope, cross-closet snapshot and import target from the shared closet
        // session (replaces the AppContent fan-out bridge; same call order it used). When the
        // active id is neither "All" nor a known closet, the current scope is kept — matching
        // the old bridge's no-op arm.
        viewModelScope.launch {
            session.session.collect { s ->
                setAllConfiguredLocations(s.snapshotFolderIds, s.shoppingFolderId)
                val active = s.activeFolderId
                when {
                    s.activeLocationId == ClosetSession.ALL_LOCATIONS_ID -> setAllLocations(s.closetFolderIds)
                    active != null -> setLocation(active)
                }
                setDefaultImportFolderId(s.defaultImportFolderId)
            }
        }
        // Mirror the UserPreferences-derived knobs (tagging language, dedupe, bg-removal
        // routing, similarity debug) from the shared preferences repository — replaces the
        // per-pref AppContent mirrors (refactor § 5 slice 2).
        viewModelScope.launch {
            prefsRepo.preferences.collect { p ->
                geminiLanguage = AppLanguage.toGeminiName(p.language)
                dedupeOnImport = p.dedupeOnImport
                dedupeThreshold = p.dedupeThreshold
                preferLocalBgRemoval = p.preferLocalBgRemoval
                debugSimilarityPreview = p.debugSimilarityPreview
            }
        }
        // (The old sidecarSaved collector is gone: the handler stamps the new sidecar id onto
        // the Room row, and the derived view picks it up via invalidation.)
        // SyncEngine feedback: a queued move exhausted its retries and the handler re-homed the
        // Room row back to its source — the derived view restores/prunes the item by itself, so
        // only the recently-moved marker and the error banner remain to undo here. Also covers
        // shopping→closet moves (the shopping VM restores its own list and shows its own error).
        viewModelScope.launch {
            moveSync.moveRolledBack.collect { rollback ->
                recentlyMovedItems.remove(rollback.driveId)
                if (rollback.sourceFolderId != shoppingFolderId) {
                    _state.update {
                        it.copy(error = getApplication<Application>().localized().getString(R.string.wardrobe_move_failed))
                    }
                }
            }
        }
    }

    /** Set the folder that new photo imports target. Null = fall back to the active view folder. */
    fun setDefaultImportFolderId(folderId: String?) {
        defaultImportFolderId = folderId
        _state.update { it.copy(importTargetFolderId = folderId) }
    }

    private fun setLocation(newFolderId: String) {
        if (folderId == newFolderId && allFolderIds == null) return
        folderId = newFolderId
        allFolderIds = null
        // Preserve [allLocationImages] across the location switch so similarity search keeps
        // working immediately — it is independent of the active filter.
        _state.update { WardrobeUiState(isLoading = true, allLocationImages = it.allLocationImages, pendingScrollDriveId = it.pendingScrollDriveId) }
        viewScope.value = listOf(newFolderId)
        loadImages()
    }

    private fun setAllLocations(folderIds: List<String>) {
        if (folderId == null && allFolderIds?.toSet() == folderIds.toSet()) return
        folderId = null
        allFolderIds = folderIds.toList()
        _state.update { WardrobeUiState(isLoading = true, allLocationImages = it.allLocationImages, pendingScrollDriveId = it.pendingScrollDriveId) }
        viewScope.value = folderIds.toList()
        loadImages()
    }

    /**
     * Tell the VM about every configured closet so it can keep [WardrobeUiState.allLocationImages]
     * in sync. Driven by the [ClosetSessionHolder] collector whenever the locations list changes.
     * The snapshot is read from each per-folder cache file (no Drive calls); folders not yet
     * downloaded simply contribute zero items until the user visits them.
     */
    private fun setAllConfiguredLocations(folderIds: List<String>, shoppingFolderId: String? = null) {
        if (allConfiguredFolderIds.toSet() == folderIds.toSet() && this.shoppingFolderId == shoppingFolderId) return
        allConfiguredFolderIds = folderIds.toList()
        this.shoppingFolderId = shoppingFolderId
        configuredScope.value = folderIds.toList()
        prefetchUncachedClosets()
    }

    private var prefetchJob: Job? = null

    /**
     * Re-attempt the cross-closet prefetch when any configured closet still lacks a local cache.
     * Safe to call repeatedly (app foreground, connectivity returning) — it no-ops when everything
     * is already cached, when offline, or when a prefetch is in flight. Without this retry an
     * initial prefetch that bailed on a momentary network blip would never run again until the
     * closet list changed, leaving the cross-closet snapshot (and thus outfits/trips) empty.
     */
    fun retryPrefetchIfNeeded() {
        viewModelScope.launch(Dispatchers.IO) {
            if (allConfiguredFolderIds.any { fid -> !itemStore.hasFolder(fid) }) {
                prefetchUncachedClosets()
            }
        }
    }

    /**
     * For every configured closet that doesn't have a local cache yet, fetch its files +
     * sidecars from Drive in the background and write the per-folder cache. This ensures
     * similarity search covers all closets on first run, not just the one the user has visited.
     * Skips folders that already have a cache file (those will refresh via normal loadImages).
     */
    private fun prefetchUncachedClosets() {
        if (prefetchJob?.isActive == true) return
        val app = getApplication<Application>()
        if (!app.isNetworkAvailable()) return
        prefetchJob = viewModelScope.launch(Dispatchers.IO) {
            val targets = allConfiguredFolderIds.filter { fid -> !itemStore.hasFolder(fid) }
            Log.d(TAG, "prefetch: configured=${allConfiguredFolderIds.size} uncached=${targets.size}")
            if (targets.isEmpty()) return@launch
            targets.forEach { fid ->
                runCatching {
                    val images = loadFolderImages(fid)
                    saveLocalCache(fid, images)
                    Log.d(TAG, "prefetch: folder=$fid downloaded=${images.size}")
                }.onFailure { Log.w(TAG, "prefetch: folder=$fid FAILED", it) }
            }
        }
    }

    /**
     * Maps a store row to its displayable [DriveImage], or null when the image bytes aren't in
     * the local Drive cache yet (the row reappears with the next store write after download).
     * The derivation collectors in `init` run this for every row on each invalidation.
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

    /** Reads a folder's currently-paintable items once (Phase-1 cold-load probes). */
    private suspend fun readCacheAsImages(fid: String): List<DriveImage> =
        itemStore.itemsFor(fid).mapNotNull { it.toDriveImage(fid) }


    // ---------- Cache ----------

    /** Deletes all locally-cached image files and the JSON index, then re-fetches from Drive. */
    fun clearCacheAndRefresh() {
        val id = folderId ?: return
        viewModelScope.launch(Dispatchers.IO) {
            itemStore.clearFolder(id)
            val dir = getApplication<Application>().filesDir.resolve("wardrobe")
            dir.listFiles()?.forEach { it.delete() }
            withContext(Dispatchers.Main) { loadImages() }
        }
    }

    /**
     * Scans [folderIds] on Drive:
     *  1. Renames cutout/original files that don't match the expected naming scheme.
     *  2. Collects originals with no matching cutout (need full AI processing).
     *  3. Collects cutouts missing a tag sidecar (need tagging only).
     * After scanning, pauses with [AuditProgress.awaitingConfirmation] so the UI can
     * show the user what was found before proceeding. Call [continueRepairProcessing]
     * to either process the findings or just reload.
     */
    suspend fun ensureOriginalCached(cutoutDriveId: String): String? = withContext(Dispatchers.IO) {
        val image = _state.value.images.firstOrNull { it.driveId == cutoutDriveId } ?: return@withContext null
        val origId = image.originalDriveId ?: return@withContext null
        val local = File(drive.cacheDir, "${cutoutDriveId}_original.jpg")
        if (local.exists()) return@withContext local.absolutePath
        val downloaded = runCatching { drive.downloadToCache(origId) }.getOrNull() ?: return@withContext null
        runCatching { downloaded.copyTo(local, overwrite = true) }
        local.absolutePath
    }

    /**
     * Writes [images] as [id]'s cached folder snapshot. The derived view and cross-closet
     * snapshot follow via Room invalidation — there is no separate refresh step any more.
     */
    internal suspend fun saveLocalCache(id: String, images: List<DriveImage>) {
        runCatching { itemStore.replaceFolder(id, images.map { it.toCachedItem() }) }
    }

    // ---------- Load ----------

    fun loadImages() {
        loadJob?.cancel()
        pruneRecentlyMoved()
        loadJob = viewModelScope.launch {
            val ids = allFolderIds
            if (ids != null) {
                _state.update { it.copy(isLoading = true, error = null) }
                // Phase 1 — instant: merge caches from all folders. Read + parse off the main
                // thread (viewModelScope defaults to Main) so parsing every closet's JSON doesn't
                // jank the All-locations switch.
                val cachedAll = withContext(Dispatchers.IO) { ids.flatMap { fid -> readCacheAsImages(fid) } }
                // The derived view paints the cache by itself; this read only settles the flags.
                if (cachedAll.isNotEmpty()) _state.update { it.copy(isLoading = false) }
                // A cold restore (nothing cached to paint) is the only time we want the verbose
                // download → details → finishing progress; routine warm-cache reconciles re-fetch
                // sidecars too but must stay silent (no per-load "loading details" bar).
                val coldLoad = cachedAll.isEmpty()
                // Phase 2 — network: skip when offline
                _state.update { it.copy(isSyncing = true) }
                if (!getApplication<android.app.Application>().isNetworkAvailable()) {
                    _state.update { it.copy(isLoading = false, isSyncing = false) }
                    return@launch
                }
                runCatching {
                    // Fetch file + sidecar lists for all folders in parallel
                    data class FolderMeta(
                        val id: String,
                        val files: List<DriveFileDto>,
                        val sidecarFiles: List<DriveFileDto>,
                    )
                    val folderMeta = ids.map { fid ->
                        async {
                            val files = async { drive.listFiles(fid) }
                            val sidecars = async { drive.listSidecarFiles(fid) }
                            FolderMeta(fid, files.await(), sidecars.await())
                        }
                    }.awaitAll()

                    val uncachedCount = folderMeta.sumOf { fd -> fd.files.count { drive.cachedFile(it.id) == null } }
                    if (uncachedCount > 0) _state.update {
                        it.copy(syncTotal = uncachedCount, syncDone = 0, syncPhase = WardrobeSyncPhase.DOWNLOADING)
                    }
                    val doneCount = AtomicInteger(0)

                    // Download all uncached images in parallel across all folders
                    folderMeta.flatMap { fd ->
                        fd.files.map { file ->
                            async {
                                val cached = drive.cachedFile(file.id)
                                val r = cached ?: drive.downloadToCache(file.id, file.name)
                                if (cached == null) _state.update { it.copy(syncDone = doneCount.incrementAndGet()) }
                                r
                            }
                        }
                    }.awaitAll()

                    // Load all sidecar content in parallel. Each sidecar is a separate Drive fetch,
                    // so on a fresh restore this is the slow tail after images finish — surface it as
                    // its own counted "details" step (cold load only) rather than letting the bar sit
                    // full and idle.
                    val sidecarTotal = folderMeta.sumOf { it.sidecarFiles.size }
                    _state.update {
                        if (coldLoad) it.copy(syncPhase = WardrobeSyncPhase.DETAILS, syncTotal = sidecarTotal, syncDone = 0)
                        else it.copy(syncPhase = WardrobeSyncPhase.NONE, syncTotal = 0, syncDone = 0)
                    }
                    val sidecarDone = AtomicInteger(0)
                    val sidecarContent: Map<String, ItemSidecar> = folderMeta.flatMap { fd ->
                        fd.sidecarFiles.map { sf ->
                            async {
                                val itemId = sf.name.removeSuffix(DriveRepository.SIDECAR_SUFFIX)
                                val content = drive.loadFileContent(sf.id)
                                if (coldLoad) _state.update { it.copy(syncDone = sidecarDone.incrementAndGet()) }
                                itemId to content?.let {
                                    runCatching { gson.fromJson(it, ItemSidecar::class.java) }.getOrNull()
                                }
                            }
                        }
                    }.awaitAll().mapNotNull { (k, v) -> v?.let { k to it } }.toMap()

                    if (coldLoad) _state.update { it.copy(syncPhase = WardrobeSyncPhase.FINISHING) }
                    // Build DriveImage list per folder
                    val allFresh = folderMeta.flatMap { fd ->
                        val sidecarIdByItemId = fd.sidecarFiles.associate { sf ->
                            sf.name.removeSuffix(DriveRepository.SIDECAR_SUFFIX) to sf.id
                        }
                        fd.files.mapNotNull { file ->
                            // Suppress an item that was just moved to a DIFFERENT folder — Drive's
                            // per-folder listing is eventually consistent and may still return it
                            // from its old parent for minutes, which would otherwise resurrect the
                            // stale copy in the source closet (and duplicate it across closets).
                            val movedTo = recentlyMovedItems[file.id]?.first
                            if (movedTo != null && movedTo != fd.id) return@mapNotNull null
                            drive.cachedFile(file.id)?.let { cached ->
                                val sidecar = sidecarContent[file.id]
                                DriveImage(
                                    driveId = file.id,
                                    localPath = cached.absolutePath,
                                    name = file.name,
                                    tags = sidecar?.tags ?: file.appProperties?.toClothingTags(),
                                    originalDriveId = sidecar?.originalDriveId,
                                    sidecarDriveId = sidecarIdByItemId[file.id],
                                    folderId = fd.id,
                                    createdTimeMs = file.createdTimeMs,
                                )
                            }
                        }
                    }
                    val freshIds = allFresh.map { it.driveId }.toSet()
                    val transientIds = transientItems.value.mapTo(HashSet()) { it.driveId }
                    val pendingRaw = _state.value.images.filter { img ->
                        img.driveId !in freshIds && img.driveId !in transientIds && (
                            !ImageEncoding.isCutoutName(img.name) ||
                                recentlyMovedItems[img.driveId]?.first == img.folderId
                            )
                    }
                    allFresh + pendingRaw
                }.onSuccess { images ->
                    // Persist each closet's reconciled items to its own per-folder store rows —
                    // the derived view repaints from them, and a later switch from All → one
                    // closet paints instantly from disk (Phase 1) instead of re-listing + re-
                    // fetching every sidecar. Raw in-flight uploads ride along (non-cutout rows
                    // survive the next reconcile via pendingRaw, exactly like the single-folder
                    // path always cached them). Store writes land before the flags clear so a
                    // cold load never flashes an empty grid.
                    withContext(Dispatchers.IO) {
                        val byFolder = images.groupBy { it.folderId }
                        ids.forEach { fid -> saveLocalCache(fid, byFolder[fid].orEmpty()) }
                    }
                    _state.update { it.copy(isLoading = false, syncTotal = 0, syncDone = 0, syncPhase = WardrobeSyncPhase.NONE, isSyncing = false) }
                }.onFailure { e ->
                    _state.update { s ->
                        s.copy(isLoading = false, syncTotal = 0, syncDone = 0, syncPhase = WardrobeSyncPhase.NONE, isSyncing = false, error = if (s.images.isEmpty()) e.message else null)
                    }
                }
                return@launch
            }

            val id = folderId ?: return@launch
            _state.update { it.copy(isLoading = true, error = null) }

            // Phase 1 — instant: show whatever is already on disk (zero network calls). The cache
            // read + JSON parse runs off the main thread so switching into a large (warmed) closet
            // doesn't jank the UI — viewModelScope launches on Main by default.
            val cachedItems = withContext(Dispatchers.IO) { readCacheAsImages(id) }
            // The derived view paints the cache by itself; this read only settles the flags.
            if (cachedItems.isNotEmpty()) {
                _state.update { it.copy(isLoading = false) }
            }
            // Only a cold restore (empty cache) shows the verbose details/finishing progress; warm
            // reconciles re-fetch sidecars silently. See the multi-folder path for rationale.
            val coldLoad = cachedItems.isEmpty()

            // Phase 2 — background sync: skip when offline
            _state.update { it.copy(isSyncing = true) }
            if (!getApplication<android.app.Application>().isNetworkAvailable()) {
                _state.update { it.copy(isLoading = false, isSyncing = false) }
                return@launch
            }

            runCatching {
                val filesDeferred = async { drive.listFiles(id) }
                val sidecarFilesDeferred = async { drive.listSidecarFiles(id) }
                val files = filesDeferred.await()
                val sidecarFiles = sidecarFilesDeferred.await()

                // Map cutout Drive ID → sidecar file ID (sidecar is named "{cutoutId}.json")
                val sidecarIdByItemId: Map<String, String> = sidecarFiles.associate { sf ->
                    sf.name.removeSuffix(DriveRepository.SIDECAR_SUFFIX) to sf.id
                }

                // Download any uncached image files in parallel, tracking progress
                val uncachedCount = files.count { drive.cachedFile(it.id) == null }
                if (uncachedCount > 0) _state.update {
                    it.copy(syncTotal = uncachedCount, syncDone = 0, syncPhase = WardrobeSyncPhase.DOWNLOADING)
                }
                val doneCount = AtomicInteger(0)
                files.map { file ->
                    async {
                        val cached = drive.cachedFile(file.id)
                        val r = cached ?: drive.downloadToCache(file.id, file.name)
                        if (cached == null) _state.update { it.copy(syncDone = doneCount.incrementAndGet()) }
                        r
                    }
                }.awaitAll()

                // Load sidecar content in parallel — each is its own Drive fetch, so report it as a
                // counted "details" step (cold load only) instead of leaving the bar full while it runs.
                _state.update {
                    if (coldLoad) it.copy(syncPhase = WardrobeSyncPhase.DETAILS, syncTotal = sidecarFiles.size, syncDone = 0)
                    else it.copy(syncPhase = WardrobeSyncPhase.NONE, syncTotal = 0, syncDone = 0)
                }
                val sidecarDone = AtomicInteger(0)
                val sidecarContent: Map<String, ItemSidecar> = sidecarFiles
                    .map { sf ->
                        async {
                            val itemId = sf.name.removeSuffix(DriveRepository.SIDECAR_SUFFIX)
                            val content = drive.loadFileContent(sf.id)
                            if (coldLoad) _state.update { it.copy(syncDone = sidecarDone.incrementAndGet()) }
                            itemId to content?.let {
                                runCatching { gson.fromJson(it, ItemSidecar::class.java) }.getOrNull()
                            }
                        }
                    }
                    .awaitAll()
                    .mapNotNull { (k, v) -> v?.let { k to it } }
                    .toMap()

                if (coldLoad) _state.update { it.copy(syncPhase = WardrobeSyncPhase.FINISHING) }

                // Legacy metadata fallback — only fetch if no sidecars exist yet (migration)
                val legacyMeta: Map<String, WardrobeItemMeta> = if (sidecarFiles.isEmpty()) {
                    drive.loadWardrobeMetadataJson(id)?.let { json ->
                        runCatching {
                            gson.fromJson(json, WardrobeMetadata::class.java).items.associateBy { it.name }
                        }.getOrDefault(emptyMap())
                    } ?: emptyMap()
                } else emptyMap()

                val freshImages = files.mapNotNull { file ->
                    // Suppress an item just moved to a different folder (see All-locations note):
                    // Drive may still list it here until removeParents propagates.
                    val movedTo = recentlyMovedItems[file.id]?.first
                    if (movedTo != null && movedTo != id) return@mapNotNull null
                    drive.cachedFile(file.id)?.let { cached ->
                        val sidecar = sidecarContent[file.id]
                        val legacy = legacyMeta[file.name]
                        val tags = sidecar?.tags ?: legacy?.tags ?: file.appProperties?.toClothingTags()
                        val originalId = sidecar?.originalDriveId ?: legacy?.originalDriveId
                        DriveImage(
                            driveId = file.id,
                            localPath = cached.absolutePath,
                            name = file.name,
                            tags = tags,
                            originalDriveId = originalId,
                            sidecarDriveId = sidecarIdByItemId[file.id],
                            folderId = id,
                            createdTimeMs = file.createdTimeMs,
                        )
                    }
                }

                // Preserve raw/pending uploads that are in the queue but not yet on Drive as
                // cutouts, plus items recently moved into this folder whose new parent Drive
                // hasn't propagated to `q='<id>' in parents` yet (eventual consistency).
                val freshIds = freshImages.map { it.driveId }.toSet()
                val transientIds = transientItems.value.mapTo(HashSet()) { it.driveId }
                val pendingRaw = _state.value.images.filter { img ->
                    img.driveId !in freshIds && img.driveId !in transientIds && (
                        !ImageEncoding.isCutoutName(img.name) ||
                            recentlyMovedItems[img.driveId]?.first == id
                        )
                }

                // Migrate legacy items to sidecars fire-and-forget; the new sidecar id is
                // stamped onto the Room row and reaches the view via invalidation.
                if (legacyMeta.isNotEmpty()) {
                    viewModelScope.launch(Dispatchers.IO) {
                        freshImages.filter { it.sidecarDriveId == null }.forEach { img ->
                            runCatching {
                                val sidecar = ItemSidecar(img.tags, img.originalDriveId)
                                val sidecarFileId = drive.upsertSidecar(
                                    id, "${img.driveId}${DriveRepository.SIDECAR_SUFFIX}",
                                    gson.toJson(sidecar),
                                )
                                itemStore.setSidecarId(img.driveId, sidecarFileId)
                            }
                        }
                    }
                }

                freshImages + pendingRaw
            }.onSuccess { images ->
                // Store write before the flags clear, so a cold load never flashes empty.
                withContext(Dispatchers.IO) { saveLocalCache(id, images) }
                _state.update { it.copy(isLoading = false, syncTotal = 0, syncDone = 0, syncPhase = WardrobeSyncPhase.NONE, isSyncing = false) }
            }.onFailure { e ->
                // Don't overwrite cached items already shown with an error banner
                _state.update { s ->
                    s.copy(isLoading = false, syncTotal = 0, syncDone = 0, syncPhase = WardrobeSyncPhase.NONE, isSyncing = false, error = if (s.images.isEmpty()) e.message else null)
                }
            }
        }
    }

    /** Loads images from a single Drive folder (Phase 2 network only, no legacy migration). */
    private suspend fun loadFolderImages(id: String): List<DriveImage> = coroutineScope {
        val filesDeferred = async { drive.listFiles(id) }
        val sidecarFilesDeferred = async { drive.listSidecarFiles(id) }
        val files = filesDeferred.await()
        val sidecarFiles = sidecarFilesDeferred.await()

        val sidecarIdByItemId: Map<String, String> = sidecarFiles.associate { sf ->
            sf.name.removeSuffix(DriveRepository.SIDECAR_SUFFIX) to sf.id
        }

        files.map { file ->
            async { drive.cachedFile(file.id) ?: drive.downloadToCache(file.id, file.name) }
        }.awaitAll()

        val sidecarContent: Map<String, ItemSidecar> = sidecarFiles
            .map { sf ->
                async {
                    val itemId = sf.name.removeSuffix(DriveRepository.SIDECAR_SUFFIX)
                    val content = drive.loadFileContent(sf.id)
                    itemId to content?.let {
                        runCatching { gson.fromJson(it, ItemSidecar::class.java) }.getOrNull()
                    }
                }
            }
            .awaitAll()
            .mapNotNull { (k, v) -> v?.let { k to it } }
            .toMap()

        files.mapNotNull { file ->
            val movedTo = recentlyMovedItems[file.id]?.first
            if (movedTo != null && movedTo != id) return@mapNotNull null
            drive.cachedFile(file.id)?.let { cached ->
                val sidecar = sidecarContent[file.id]
                val tags = sidecar?.tags ?: file.appProperties?.toClothingTags()
                val originalId = sidecar?.originalDriveId
                DriveImage(
                    driveId = file.id,
                    localPath = cached.absolutePath,
                    name = file.name,
                    tags = tags,
                    originalDriveId = originalId,
                    sidecarDriveId = sidecarIdByItemId[file.id],
                    folderId = id,
                )
            }
        }
    }

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

    // ---------- Naming helpers ----------

    /**
     * Uploads [imageFile] to [folderId], then immediately renames it to
     * "{driveId}_cutout.png" (where driveId is the Drive-assigned ID).
     * Returns the DriveFileDto with [name] already set to the final name.
     */
    internal suspend fun uploadAsCutout(folderId: String, imageFile: File): DriveFileDto {
        val uploaded = drive.uploadImage(folderId, imageFile)
        val finalName = "${uploaded.id}${DriveRepository.CUTOUT_SUFFIX}"
        runCatching { drive.renameFile(uploaded.id, finalName) }
        return uploaded.copy(name = finalName)
    }

    /**
     * Uploads [imageFile] to [folderId] with filename "{cutoutDriveId}_original.jpg".
     * Returns the new Drive file ID.
     */
    internal suspend fun uploadAsOriginal(folderId: String, imageFile: File, cutoutDriveId: String): String =
        drive.uploadImageWithName(folderId, imageFile, "$cutoutDriveId${DriveRepository.ORIGINAL_SUFFIX}").id

    /**
     * Resolves the Drive ID of a cutout item given its [metaName] (possibly old-format).
     * Checks by Drive ID directly (format "{id}_cutout.webp" or legacy "{id}_cutout.png")
     * or by filename (old formats).
     */
    private fun resolveCutoutDriveId(
        metaName: String,
        fileByName: Map<String, DriveFileDto>,
        fileById: Map<String, DriveFileDto>,
    ): String? {
        ImageEncoding.cutoutIdFromName(metaName)?.let { possibleId ->
            if (fileById.containsKey(possibleId)) return possibleId
        }
        return fileByName[metaName]?.id
    }

    // ---------- Background processing queue ----------

    /** Drains [workQueue] serially — bg removal + tagging for each newly uploaded photo. */
    private suspend fun processQueue() {
        for (job in workQueue) {
            acquireJobWakeLock()
            try {
                runCatching { processQueuedImage(job) }
                    .onFailure { e ->
                        logWardrobeAdd("failed", job.source, mapOf("reason" to "exception"))
                        _state.update { it.copy(error = e.message) }
                    }
                _state.update { s ->
                    s.copy(
                        pendingJobs = maxOf(0, s.pendingJobs - 1),
                        processingImageId = if (s.processingImageId == job.driveId)
                            null else s.processingImageId,
                    )
                }
                // The finished item is persisted to its own closet cache inside
                // processQueuedImage (keyed off job.folderId), so nothing to do here.
            } finally {
                releaseJobWakeLock()
            }
        }
    }

    private suspend fun processQueuedImage(job: PendingJob) {
        val rawFile = File(drive.cacheDir, "${job.driveId}_original.jpg")
        if (!rawFile.exists()) {
            logWardrobeAdd("failed", job.source, mapOf("reason" to "raw_missing"))
            return
        }
        _state.update { it.copy(processingImageId = job.driveId) }
        // NOTE: we intentionally do NOT check if job.driveId is still in state — loadImages()
        // may have replaced it with the cutout ID already (race window). We always process.

        // Step 1 — background removal. If the user produced a local cutout via the on-device
        // segmenter review (LocalBgRemovalScreen), use it as-is and skip the paid Gemini call.
        val processedFile: File = job.prebuiltCutoutPath
            ?.let { File(it) }
            ?.takeIf { it.exists() }
            ?: (gemini.removeBackground(rawFile, drive.cacheDir) ?: rawFile)

        // Step 2 — upload cutout, then rename to "{cutoutId}_cutout.png"
        val cutoutDrive = runCatching { uploadAsCutout(job.folderId, processedFile) }.getOrNull()
            ?: run {
                logWardrobeAdd("failed", job.source, mapOf("reason" to "cutout_upload"))
                return
            }

        // Step 3 — upload original as "{cutoutId}_original.jpg" (best-effort)
        val originalDriveId = runCatching {
            uploadAsOriginal(job.folderId, rawFile, cutoutDrive.id)
        }.getOrNull()

        // Step 4 — write cutout to local cache; also cache original for fast future reprocessing
        // (must happen before deleteFile, which also removes the local _original.jpg)
        val localCutout = File(drive.cacheDir, "${cutoutDrive.id}.png")
        if (processedFile.absolutePath != localCutout.absolutePath) {
            processedFile.copyTo(localCutout, overwrite = true)
        }
        rawFile.copyTo(File(drive.cacheDir, "${cutoutDrive.id}_original.jpg"), overwrite = true)

        // Step 5 — delete the temporary raw upload
        runCatching { drive.deleteFile(job.driveId) }

        // Step 6 — make the finished cutout live: replace the raw-id store row with the final
        // cutout entry, keyed off job.folderId (correct even if the user switched closets
        // mid-job). The derived view swaps raw → cutout via invalidation. Keep the raw row's
        // createdTimeMs so the item holds its sort position.
        val rawCreated = itemStore.find(job.driveId)?.second?.createdTimeMs
            ?: System.currentTimeMillis()
        var finished = DriveImage(
            driveId = cutoutDrive.id,
            localPath = localCutout.absolutePath,
            name = cutoutDrive.name,          // "{cutoutId}_cutout.png"
            tags = null,
            originalDriveId = originalDriveId,
            folderId = job.folderId,
            createdTimeMs = rawCreated,
        )
        persistItemToCache(job.folderId, finished, staleDriveId = job.driveId)
        _state.update { s ->
            s.copy(
                processingImageId = if (s.processingImageId == job.driveId)
                    cutoutDrive.id else s.processingImageId,
            )
        }

        // Step 7 — classify clothing tags
        val tags = gemini.classifyClothing(localCutout, geminiLanguage)
        if (tags != null) {
            finished = finished.copy(tags = tags)
            persistItemToCache(job.folderId, finished)
        }

        // Step 8 — save sidecar (includes tags even if null, so item is discoverable on next load)
        val sidecarJson = gson.toJson(ItemSidecar(tags, originalDriveId))
        runCatching {
            drive.upsertSidecar(
                job.folderId, "${cutoutDrive.id}${DriveRepository.SIDECAR_SUFFIX}", sidecarJson,
            )
        }.onSuccess { sidecarId ->
            itemStore.setSidecarId(cutoutDrive.id, sidecarId)
        }
        _state.update { s ->
            if (s.processingImageId == cutoutDrive.id || s.processingImageId == job.driveId)
                s.copy(processingImageId = null) else s
        }

        // Funnel terminal: the item is fully processed and live in the wardrobe.
        logWardrobeAdd("item_live", job.source, mapOf("tagged" to (tags != null).toString()))
    }

    // ---------- Navigation ----------

    fun requestScrollToImage(driveId: String) =
        _state.update { it.copy(pendingScrollDriveId = driveId) }

    /** Consumed by the wardrobe screen after it has scrolled to the pending target. */
    fun consumePendingScroll() = _state.update { it.copy(pendingScrollDriveId = null) }

    // ---------- Upload from camera ----------

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

    fun removeAllBackgrounds() {
        val images = _state.value.images
        if (images.isEmpty() || _state.value.isRemovingAllBg) return
        viewModelScope.launch {
            acquireJobWakeLock()
            try {
                _state.update { it.copy(isRemovingAllBg = true, removeBgDone = 0, removeBgTotal = images.size) }
                try {
                images.forEachIndexed { index, image ->
                    _state.update { it.copy(removeBgDone = index) }
                    val source = resolveOriginalFile(image.driveId) ?: return@forEachIndexed
                    val processedFile = gemini.removeBackground(source, drive.cacheDir) ?: return@forEachIndexed

                    // Upload original to Drive if not already stored there
                    val id = folderId ?: return@forEachIndexed
                    val originalDriveId = image.originalDriveId ?: runCatching {
                        drive.uploadImage(id, source).id
                    }.getOrNull()

                    runCatching {
                        drive.updateImage(image.driveId, processedFile)
                        val displayCache = File(drive.cacheDir, "${image.driveId}.png")
                        processedFile.copyTo(displayCache, overwrite = true)
                        // Store-first: stamp the (possibly new) original id onto the row, then
                        // bust the Coil cache — the derived view re-emits both.
                        withContext(Dispatchers.IO) {
                            itemStore.find(image.driveId)?.let { (fid, row) ->
                                itemStore.upsert(fid, row.copy(originalDriveId = originalDriveId ?: row.originalDriveId))
                            }
                        }
                        bumpImageVersion(image.driveId)
                    }
                }
                images.forEach { img -> saveSidecar(img.driveId) }
                } catch (e: com.librelookai.billing.InsufficientCreditsException) {
                    // Global dialog appears via CreditsEvents; just abort the bulk.
                }
                _state.update { it.copy(isRemovingAllBg = false, removeBgDone = 0, removeBgTotal = 0) }
            } finally {
                releaseJobWakeLock()
            }
        }
    }

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

    fun retagAll() {
        val images = _state.value.images
        if (images.isEmpty() || _state.value.isRetagging) return
        viewModelScope.launch {
            acquireJobWakeLock()
            try {
            _state.update { it.copy(isRetagging = true, retagDone = 0, retagTotal = images.size) }
            try {
                images.forEachIndexed { index, image ->
                    _state.update { it.copy(retagDone = index) }
                    val cachedFile = drive.cachedFile(image.driveId) ?: return@forEachIndexed
                    val tags = gemini.classifyClothing(cachedFile, geminiLanguage) ?: return@forEachIndexed
                    withContext(Dispatchers.IO) { persistItemTags(image.driveId, tags) }
                }
                images.forEach { img -> saveSidecar(img.driveId) }
            } catch (e: com.librelookai.billing.InsufficientCreditsException) {
                // Global dialog appears via CreditsEvents; abort the bulk.
            }
            _state.update { it.copy(isRetagging = false, retagDone = 0, retagTotal = 0) }
            } finally {
                releaseJobWakeLock()
            }
        }
    }

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

    /** Removes the given driveIds from a folder's cache in place (no-op for ids homed elsewhere). */
    private suspend fun removeFromCacheFile(fid: String, ids: Set<String>) {
        runCatching { itemStore.remove(fid, ids) }
    }

    /**
     * Upserts a single finished item into its own closet's cache [fid], editing the store
     * directly (not rebuilding from `state.images`) so it is correct even if the user switched
     * closets while the item was being processed. Drops any stale entry under [staleDriveId]
     * (the pre-cutout raw id) before upserting the fresh entry.
     */
    internal suspend fun persistItemToCache(fid: String, item: DriveImage, staleDriveId: String? = null) {
        runCatching { itemStore.upsert(fid, item.toCachedItem(), staleDriveId) }
    }

    /** Upserts the given items into a folder's cache (each item is re-homed there if elsewhere). */
    private suspend fun addToCacheFile(fid: String, items: List<DriveImage>) {
        runCatching { itemStore.addAll(fid, items.map { it.toCachedItem() }) }
    }

    /**
     * Records that [items] have been moved into [targetFolderId] (their cutout/original/sidecar
     * Drive IDs are unchanged, so the cached image bytes are still valid). Re-homes them into the
     * target folder in the [WardrobeItemStore] so [setLocation] to that folder shows them in
     * Phase 1 instantly, and remembers them so Phase 2 won't drop them while Drive's listing
     * is still propagating. Callable from other view models (e.g. shopping move-to-closet).
     */
    fun notifyItemsMovedTo(targetFolderId: String, items: List<DriveImage>) {
        if (items.isEmpty() || targetFolderId.isEmpty()) return
        val expiry = System.currentTimeMillis() + recentlyMovedTtlMs
        items.forEach { recentlyMovedItems[it.driveId] = targetFolderId to expiry }
        // The store re-home is the whole local move: the derived view drops the items from
        // their source closet and shows them in the target via invalidation.
        viewModelScope.launch(Dispatchers.IO) {
            addToCacheFile(targetFolderId, items)
        }
    }

    // ---------- SAF Import ----------

    /**
     * Imports all images from [treeUri] into the current wardrobe folder.
     *
     * Images are always uploaded/updated and immediately visible — AI processing is optional.
     *
     * [removeBackground]   — run Gemini BG removal on each image (5 credits/item).
     * [autoTag]            — classify clothing tags with Gemini (2 credits/item).
     * [replaceExisting]    — delete all current wardrobe items before importing (fresh start).
     * [overwriteDuplicates]— when false (default) images whose filename already exists are
     *                        skipped; when true the existing Drive file is replaced in-place.
     *                        Ignored when [replaceExisting] is true.
     */
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
        driveIds.forEach { recentlyMovedItems.remove(it) }
        _state.update { it.copy(selectedIds = emptySet()) }

        // ---- Queue the Drive deletes (refactor § 2): the cache writes come first, still before
        // any Drive call, so the deletion survives a restart — and unlike the old fire-and-forget
        // per-file deletes, a queued [ITEM_DELETE_KIND] mutation retries transient failures
        // instead of silently orphaning the files on Drive. File ids ride in the payload because
        // the Room row is gone before the drain runs. ----
        viewModelScope.launch {
            withContext(Dispatchers.IO) {
                affectedFolderIds.forEach { fid -> removeFromCacheFile(fid, driveIds) }
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

// ---------- Legacy appProperties → ClothingTags (migration read-path only) ----------

private fun Map<String, String>.toClothingTags(): ClothingTags? {
    val type = getOrDefault("clothing_type", "")
    if (type.isEmpty()) return null
    return ClothingTags(
        type        = type,
        category    = getOrDefault("clothing_category", ""),
        uses        = getOrDefault("clothing_uses",        "").split(",").filter { it.isNotBlank() },
        colors      = getOrDefault("clothing_colors",      "").split(",").filter { it.isNotBlank() },
        seasonality = getOrDefault("clothing_seasonality", "").split(",").filter { it.isNotBlank() },
        aesthetic   = getOrDefault("clothing_aesthetic",   "").split(",").filter { it.isNotBlank() },
        fit         = getOrDefault("clothing_fit",         "").split(",").filter { it.isNotBlank() },
        material    = getOrDefault("clothing_material",    "").split(",").filter { it.isNotBlank() },
        pattern     = getOrDefault("clothing_pattern",     "").split(",").filter { it.isNotBlank() },
    )
}
