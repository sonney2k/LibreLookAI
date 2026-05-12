package com.librelookai

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
import androidx.lifecycle.viewModelScope
import com.google.gson.Gson
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.io.File
import java.util.concurrent.atomic.AtomicInteger

private val FUZZY_TOKEN_SPLIT = Regex("[^\\p{L}\\p{Nd}]+")

enum class WardrobeView { GRID, CAPTURE, FIND_BY_PHOTO_CAPTURE }

/** A wardrobe item ranked against a "find item by photo" query, with cosine score. */
data class FindByPhotoMatch(val image: DriveImage, val score: Float)

/** State for the "find item" overlay rendered on top of the wardrobe grid. Used by both
 *  the legacy "find by photo" flow (image-based similarity) and the on-device semantic
 *  text search — exactly one of [queryPath] / [textQuery] is populated. */
data class FindByPhoto(
    val queryPath: String? = null,
    val textQuery: String? = null,
    val matches: List<FindByPhotoMatch>,
    val isSearching: Boolean = false,
    /** Debug artifacts from the same similarity helper used by Shopping's Similarity Finder.
     *  Populated when the search finishes; null while still searching. */
    val processedPath: String? = null,
    val segmented: Boolean = false,
    val hist: FloatArray? = null,
    val vec: FloatArray? = null,
    val pHash: Long? = null,
)

data class DriveImage(
    val driveId: String,
    val localPath: String,
    val name: String,
    val tags: ClothingTags? = null,
    /** Bumped on every local reprocess so Coil knows to reload from disk. */
    val version: Long = 0L,
    /** Drive file ID of the unprocessed original, if one was saved to Drive. */
    val originalDriveId: String? = null,
    /** Drive file ID of the per-item sidecar JSON (named "{driveId}.json"). */
    val sidecarDriveId: String? = null,
    /** Drive folder ID this item actually lives in. */
    val folderId: String = "",
    /** Drive's `createdTime` for the cutout file, in millis. 0 when unknown (legacy cache). */
    val createdTimeMs: Long = 0L,
)

/** One wardrobe item ranked above the dedupe threshold against an incoming import. */
data class DuplicateMatch(val image: DriveImage, val score: Float)

/**
 * Pending capture/upload waiting for the user to confirm or cancel after the on-device
 * similarity check surfaced one or more close matches in the existing wardrobe.
 */
data class DuplicateCheck(
    /** Absolute path of the raw file the user just captured. */
    val rawFilePath: String,
    /** Drive folder ID the file would be uploaded to once the user confirms. */
    val targetFolderId: String,
    /** Wardrobe items above the configured similarity threshold, ordered most-similar first. */
    val matches: List<DuplicateMatch>,
    /** Debug artifacts from the same similarity helper used by Shopping's Similarity Finder. */
    val processedPath: String? = null,
    val segmented: Boolean = false,
    val hist: FloatArray? = null,
    val vec: FloatArray? = null,
    val pHash: Long? = null,
)

data class WardrobeUiState(
    val view: WardrobeView = WardrobeView.GRID,
    val images: List<DriveImage> = emptyList(),
    /**
     * Cross-closet snapshot of every cached wardrobe item across all configured closets,
     * regardless of the active location filter applied to [images]. Refreshed from per-folder
     * `wardrobe_cache_{folderId}.json` files whenever [images] changes or
     * `setAllConfiguredLocations` is called. Used by every similarity-search call site so
     * matches always span all wardrobes.
     */
    val allLocationImages: List<DriveImage> = emptyList(),
    val isLoading: Boolean = false,
    val isSyncing: Boolean = false,
    val isProcessing: Boolean = false,
    val isUploading: Boolean = false,
    val isMoving: Boolean = false,
    /** Number of items in the current batch (0 = single-item flow). */
    val batchTotal: Int = 0,
    val batchDone: Int = 0,
    val isRetagging: Boolean = false,
    val retagDone: Int = 0,
    val retagTotal: Int = 0,
    val isImporting: Boolean = false,
    val importDone: Int = 0,
    val importTotal: Int = 0,
    val isRemovingAllBg: Boolean = false,
    val removeBgDone: Int = 0,
    val removeBgTotal: Int = 0,
    val error: String? = null,
    /** Total images to download during Phase 2 Drive sync (0 = not syncing or unknown). */
    val syncTotal: Int = 0,
    /** Images downloaded so far during Phase 2 Drive sync. */
    val syncDone: Int = 0,
    /** driveId of the image currently being processed by an AI operation, or null. */
    val processingImageId: String? = null,
    val selectedIds: Set<String> = emptySet(),
    /** Number of photos queued or actively running background processing (bg removal + tagging). */
    val pendingJobs: Int = 0,
    /** Non-null while a repair-and-sync audit is in progress or awaiting user input. */
    val auditProgress: AuditProgress? = null,
    /** Non-null while the "Fix cutout backgrounds" flow is scanning, awaiting confirmation, or processing. */
    val cutoutBgFix: CutoutBgFixProgress? = null,
    /** True when a job is starting and the app is not exempt from battery optimization. */
    val needsBatteryExemption: Boolean = false,
    /** Drive folder ID that new photos will be uploaded to. */
    val importTargetFolderId: String? = null,
    /** Non-null while a captured photo is paused for duplicate confirmation. */
    val duplicateCheck: DuplicateCheck? = null,
    /** Non-null while the user is reviewing matches from a "find item by photo" capture. */
    val findByPhoto: FindByPhoto? = null,
    /** Non-null when something outside the wardrobe screen (e.g. Similarity Finder's
     *  "Show in wardrobe") has asked the grid to scroll to and pulse a specific item. The
     *  wardrobe screen consumes this in a LaunchedEffect once the item is in the displayed list. */
    val pendingScrollDriveId: String? = null,
    /** Non-null while a folder import is paused awaiting the user's review of pre-scanned candidates. */
    val importPreview: ImportPreview? = null,
    /** Queue of imports waiting for the on-device background-removal review screen. The head item
     *  is shown; on Apply / Skip / Cancel it is popped and the next (if any) is shown. Camera and
     *  URL imports always enqueue a single item; gallery imports enqueue every selected URI when
     *  [UserPreferences.preferLocalBgRemoval] is on. */
    val localBgReviewQueue: List<LocalBgReviewItem> = emptyList(),
    /** Non-null while the user is choosing an image from a pasted shopping URL. */
    val urlImportPicker: UrlImportPickerState? = null,
)

/** State for the URL-import picker dialog (candidate grid + WebView fallback). */
data class UrlImportPickerState(
    val pageUrl: String,
    val candidates: List<String>,
    val targetFolderId: String? = null,
    val isDownloading: Boolean = false,
)

/** One pending entry in the on-device bg-removal review queue. */
data class LocalBgReviewItem(
    /** Local file path to the raw (pre-cutout) image. Owned by the VM until applied/skipped/cancelled. */
    val rawFilePath: String,
    /** Drive folder the eventual cutout will be uploaded to. */
    val targetFolderId: String,
    /** True when the review can be skipped (camera/gallery). URL imports must complete the review. */
    val skippable: Boolean,
)

/** One candidate file in a paused folder-import preview. */
data class ImportPreviewEntry(
    /** Stable key within this preview (used for selection state). */
    val key: String,
    /** Display name for the preview tile. */
    val displayName: String,
    /** Local file path the source has been copied / downloaded into; reused for the actual upload. */
    val cachedFilePath: String,
    /** Wardrobe items above the dedupe threshold against this candidate, most-similar first. */
    val similar: List<DuplicateMatch> = emptyList(),
    /** Tags carried over from a `_wardrobe_metadata.json` sidecar in the source folder, if any. */
    val srcMetaTags: ClothingTags? = null,
    /** `originalDriveId` carried over from a `_wardrobe_metadata.json` sidecar, if any. */
    val srcMetaOriginalDriveId: String? = null,
)

/** Options the user picked in the import dialog, captured at preview time so resume uses the same flags. */
data class ImportOptions(
    val removeBackground: Boolean,
    val autoTag: Boolean,
    val replaceExisting: Boolean,
    val overwriteDuplicates: Boolean,
)

/** State of a folder-import scan paused for user confirmation. */
data class ImportPreview(
    val entries: List<ImportPreviewEntry>,
    val selectedKeys: Set<String>,
    val isScanning: Boolean = false,
    val scanDone: Int = 0,
    val scanTotal: Int = 0,
    val targetFolderId: String,
    val options: ImportOptions,
)

// ---------- Audit / repair progress ----------

data class AuditProgress(
    val isScanning: Boolean = false,
    val scannedFolders: Int = 0,
    val totalFolders: Int = 0,
    /** Files renamed on Drive during the scan to match the expected naming scheme. */
    val renamedCount: Int = 0,
    /** Scan finished — waiting for user confirmation before processing. */
    val awaitingConfirmation: Boolean = false,
    /** Originals with no matching cutout that need full AI processing. */
    val orphanedOriginals: Int = 0,
    /** Unrecognised raw images (non-cutout, non-original) needing full AI processing. */
    val rawImages: Int = 0,
    /** Cutouts that are missing a tag sidecar and need tagging. */
    val sidecarNeeded: Int = 0,
    /** Cutouts that have at least one visually-similar peer in the wardrobe. */
    val duplicates: Int = 0,
    /** True while the duplicate-detection pass is still running after the file scan. */
    val isScanningDuplicates: Boolean = false,
    /** All audit findings as preview-grid entries; same items counted in the fields above. */
    val items: List<AuditFileEntry> = emptyList(),
    /** Drive IDs the user has selected for processing. Defaults to all items when scan completes. */
    val selectedAuditIds: Set<String> = emptySet(),
    val isProcessing: Boolean = false,
    val processDone: Int = 0,
    val processTotal: Int = 0,
    val isDone: Boolean = false,
)

/** Kind of finding for a single audited file — drives processing path + section grouping in the UI. */
enum class AuditKind { ORPHANED_ORIGINAL, RAW, NEEDS_SIDECAR, DUPLICATE }

/**
 * One entry in the Repair & Sync preview grid. For [AuditKind.NEEDS_SIDECAR] the [driveId] points
 * at the existing cutout; for [AuditKind.DUPLICATE] it points at a cutout that has at least one
 * visually-similar peer (listed in [similarTo]); otherwise it's the orphaned original / raw image
 * awaiting processing.
 *
 * Selecting a [AuditKind.DUPLICATE] entry means "delete it from Drive" — duplicates do not consume
 * credits.
 */
data class AuditFileEntry(
    val driveId: String,
    val name: String,
    val folderId: String,
    val kind: AuditKind,
    /** For [AuditKind.DUPLICATE], wardrobe Drive IDs the entry is similar to (best match first). */
    val similarTo: List<String> = emptyList(),
    /** For [AuditKind.DUPLICATE], cosine score against the closest peer in [similarTo]. */
    val topScore: Float = 0f,
)

// ---------- Cutout background fix progress ----------

/** Scan + apply state for Settings → Data → "Fix cutout backgrounds". Local-only processing
 *  (no Gemini calls) — re-uploads fixed PNGs to Drive preserving the cutout's Drive ID. */
data class CutoutBgFixProgress(
    val isScanning: Boolean = false,
    val totalFolders: Int = 0,
    val scannedFolders: Int = 0,
    val totalCutouts: Int = 0,
    val scannedCutouts: Int = 0,
    val items: List<CutoutFixEntry> = emptyList(),
    val flaggedIds: Set<String> = emptySet(),
    val selectedIds: Set<String> = emptySet(),
    val showAll: Boolean = false,
    val awaitingConfirmation: Boolean = false,
    val isProcessing: Boolean = false,
    val processTotal: Int = 0,
    val processDone: Int = 0,
    val isDone: Boolean = false,
    // Per-action toggles. Defaults set in startCutoutBgFix once detection results are known
    // (any flagged issue → that action defaults ON). Feather + tight-crop default ON.
    val applyBlackToAlpha: Boolean = false,
    val applyDespillGreen: Boolean = false,
    val applyFeather: Boolean = true,
    val applyTightCrop: Boolean = true,
)

data class CutoutFixEntry(
    val driveId: String,
    val name: String,
    val folderId: String,
    val hasBlackBackground: Boolean,
    val hasGreenHalo: Boolean,
)

// ---------- Per-item sidecar metadata ----------

internal data class ItemSidecar(val tags: ClothingTags? = null, val originalDriveId: String? = null)

// ---------- Legacy bulk metadata (read-only, migration fallback) ----------

private data class WardrobeItemMeta(val name: String, val tags: ClothingTags?, val originalDriveId: String? = null)
private data class WardrobeMetadata(val items: List<WardrobeItemMeta> = emptyList())

// ---------- Local disk cache (instant startup, no network) ----------

/** Full DriveImage snapshot stored on device for zero-latency startup. */
data class LocalCacheEntry(
    val driveId: String,
    val name: String,
    val tags: ClothingTags?,
    val originalDriveId: String? = null,
    val sidecarDriveId: String? = null,
    val createdTimeMs: Long = 0L,
)
data class LocalCache(val items: List<LocalCacheEntry> = emptyList())

private data class PendingJob(
    val driveId: String,
    val folderId: String,
    /** When non-null, points to a locally-produced cutout PNG that [processQueuedImage] should
     *  use directly instead of calling Gemini. The file lives in `drive.cacheDir` and is owned by
     *  the worker once enqueued (it gets copied into `${cutoutId}.png` and then deleted). */
    val prebuiltCutoutPath: String? = null,
)

// Internal audit helpers
private data class AuditItem(val folderId: String, val driveId: String, val name: String)
private data class AuditCutoutItem(val folderId: String, val cutoutDriveId: String, val cutoutName: String)
private data class AuditDuplicateItem(
    val folderId: String,
    val cutoutDriveId: String,
    val cutoutName: String,
    val similarTo: List<String>,
    val topScore: Float,
)
private data class AuditIntermediate(
    val folderIds: List<String>,
    val orphanedOriginals: List<AuditItem>,
    val rawImages: List<AuditItem>,
    val cutoutsNeedingSidecar: List<AuditCutoutItem>,
    val duplicates: List<AuditDuplicateItem> = emptyList(),
)

class WardrobeViewModel(app: Application) : AndroidViewModel(app) {

    companion object {
        private const val TAG = "RepairAndSync"
        /** Stricter floor for the Repair & Sync duplicate scan. */
        private const val REPAIR_DUPE_THRESHOLD = 0.97f
        /** Sidecars at or below this size are {} or {"tags":null} — definitely no tags. */
        private const val SIDECAR_EMPTY_MAX = 20L
        /** Sidecars at or above this size always contain a ClothingTags object. */
        private const val SIDECAR_FULL_MIN  = 100L
        /** Cache subdir for the processed-query bitmaps fed to the similarity debug preview. */
        private const val QUERY_DEBUG_DIR = "wardrobe_query_debug"
    }

    private val drive = DriveRepository(app, GoogleAuthManager(app))
    private val gemini = GeminiRepository(app)
    private val gson = Gson()

    private var jobWakeLock: PowerManager.WakeLock? = null
    private val activeJobCount = AtomicInteger(0)

    private fun acquireJobWakeLock() {
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

    private fun releaseJobWakeLock() {
        if (activeJobCount.decrementAndGet() == 0) {
            jobWakeLock?.let { if (it.isHeld) it.release() }
            JobForegroundService.release(getApplication())
            Log.d(TAG, "Wake lock released")
        }
    }

    private val _state = MutableStateFlow(WardrobeUiState())
    val state: StateFlow<WardrobeUiState> = _state.asStateFlow()

    private var folderId: String? = null
    private var allFolderIds: List<String>? = null
    /**
     * Every closet folderId the user has configured, regardless of which one is currently
     * displayed. Driven by [setAllConfiguredLocations] from MainActivity. Used to build the
     * cross-closet [WardrobeUiState.allLocationImages] snapshot for similarity search.
     */
    private var allConfiguredFolderIds: List<String> = emptyList()
    /** Gemini-facing language name (e.g. "English", "German") for label generation. */
    private var geminiLanguage: String = "English"
    /** When non-null, all new photo imports target this folder instead of the active view folder. */
    private var defaultImportFolderId: String? = null
    /** Mirrors UserPreferences.dedupeOnImport — flips capture/import similarity gate on/off. */
    private var dedupeOnImport: Boolean = false
    /** Mirrors UserPreferences.dedupeThreshold — cosine cutoff for "this is probably already in your wardrobe". */
    private var dedupeThreshold: Float = 0.88f
    /** Mirrors UserPreferences.preferLocalBgRemoval — when true, camera/gallery imports route through
     *  the on-device segmenter review screen. URL imports always do. */
    private var preferLocalBgRemoval: Boolean = false
    /** Mirrors UserPreferences.debugSimilarityPreview — when on, similarity searches return up to
     *  50 matches so the debug breakdown has more candidates to scroll through. */
    private var debugSimilarityPreview: Boolean = false

    /** Serializes all Drive metadata writes to prevent concurrent saves overwriting each other. */
    private val metaMutex = Mutex()

    /** Holds the audit scan results while waiting for the user to confirm processing. */
    private var pendingAudit: AuditIntermediate? = null

    /** Active loadImages coroutine — cancelled when a new location is selected. */
    private var loadJob: Job? = null

    /**
     * Tracks items recently moved into another closet. Drive's `q='<folder>' in parents` query
     * is eventually consistent — after a move PATCH the destination folder's listing can lag
     * by up to a minute. We use this map to (a) re-inject moved items into Phase 2 results
     * while Drive catches up, so they don't briefly disappear after [setLocation] to the target.
     * Keyed by cutout driveId → (targetFolderId, expiresAtMs).
     */
    private val recentlyMovedItems = java.util.concurrent.ConcurrentHashMap<String, Pair<String, Long>>()
    private val recentlyMovedTtlMs: Long = 2 * 60 * 1000L

    private fun pruneRecentlyMoved() {
        val now = System.currentTimeMillis()
        recentlyMovedItems.entries.removeAll { it.value.second < now }
    }

    /**
     * Background processing queue. Each item represents one uploaded photo that needs
     * bg removal + classification. Processed serially so metadata writes are consistent.
     */
    private val workQueue = Channel<PendingJob>(Channel.UNLIMITED)

    init {
        viewModelScope.launch { processQueue() }
    }

    fun setLanguage(geminiName: String) {
        geminiLanguage = geminiName
    }

    /** Push UserPreferences-derived similarity settings into the VM. Called from MainActivity. */
    fun setDedupeSettings(enabled: Boolean, threshold: Float) {
        dedupeOnImport = enabled
        dedupeThreshold = threshold
    }

    /** Push UserPreferences.preferLocalBgRemoval into the VM. Called from MainActivity. */
    fun setPreferLocalBgRemoval(enabled: Boolean) { preferLocalBgRemoval = enabled }

    /** Push UserPreferences.debugSimilarityPreview into the VM. Called from MainActivity. */
    fun setDebugSimilarityPreview(enabled: Boolean) { debugSimilarityPreview = enabled }

    /** Set the folder that new photo imports target. Null = fall back to the active view folder. */
    fun setDefaultImportFolderId(folderId: String?) {
        defaultImportFolderId = folderId
        _state.update { it.copy(importTargetFolderId = folderId) }
    }

    fun setLocation(newFolderId: String) {
        if (folderId == newFolderId && allFolderIds == null) return
        folderId = newFolderId
        allFolderIds = null
        // Preserve [allLocationImages] across the location switch so similarity search keeps
        // working immediately — it is independent of the active filter.
        _state.update { WardrobeUiState(isLoading = true, allLocationImages = it.allLocationImages, pendingScrollDriveId = it.pendingScrollDriveId) }
        loadImages()
    }

    fun setAllLocations(folderIds: List<String>) {
        if (folderId == null && allFolderIds?.toSet() == folderIds.toSet()) return
        folderId = null
        allFolderIds = folderIds.toList()
        _state.update { WardrobeUiState(isLoading = true, allLocationImages = it.allLocationImages, pendingScrollDriveId = it.pendingScrollDriveId) }
        loadImages()
    }

    /**
     * Tell the VM about every configured closet so it can keep [WardrobeUiState.allLocationImages]
     * in sync. Called by `MainActivity` whenever the locations list changes. The snapshot is
     * read from each per-folder cache file (no Drive calls); folders not yet downloaded simply
     * contribute zero items until the user visits them.
     */
    fun setAllConfiguredLocations(folderIds: List<String>) {
        if (allConfiguredFolderIds.toSet() == folderIds.toSet()) return
        allConfiguredFolderIds = folderIds.toList()
        refreshAllLocationImagesState()
        prefetchUncachedClosets()
    }

    private var prefetchJob: Job? = null

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
        val targets = allConfiguredFolderIds.filter { fid -> !localCacheFile(fid).exists() }
        if (targets.isEmpty()) return
        prefetchJob = viewModelScope.launch(Dispatchers.IO) {
            targets.forEach { fid ->
                runCatching {
                    val images = loadFolderImages(fid)
                    saveLocalCache(fid, images)
                }
            }
            refreshAllLocationImagesState()
        }
    }

    /** Re-read every configured folder's cache and publish the merged snapshot. */
    private fun refreshAllLocationImagesState() {
        val merged = allConfiguredFolderIds.flatMap { readCacheAsImages(it) }
        _state.update { it.copy(allLocationImages = merged) }
    }

    private fun readCacheAsImages(fid: String): List<DriveImage> {
        val cacheFile = localCacheFile(fid)
        if (!cacheFile.exists()) return emptyList()
        return runCatching {
            val cache = gson.fromJson(cacheFile.readText(), LocalCache::class.java)
            cache.items.mapNotNull { entry ->
                drive.cachedFile(entry.driveId)?.let { f ->
                    DriveImage(
                        entry.driveId, f.absolutePath, entry.name, entry.tags,
                        originalDriveId = entry.originalDriveId,
                        sidecarDriveId = entry.sidecarDriveId,
                        folderId = fid,
                        createdTimeMs = entry.createdTimeMs,
                    )
                }
            }
        }.getOrDefault(emptyList())
    }

    /**
     * Moves the given wardrobe items (cutout + original + sidecar) from the current location
     * folder to [toFolderId]. Removed items are dropped from the in-memory state immediately.
     */
    fun moveItemsToFolder(
        itemIds: List<String>,
        toFolderId: String,
        onDone: (success: Boolean) -> Unit = {},
    ) {
        val fromFolderId = folderId ?: run { onDone(false); return }
        if (fromFolderId == toFolderId) { onDone(true); return }
        viewModelScope.launch {
            val idsSet = itemIds.toSet()
            val toMove = _state.value.images.filter { it.driveId in idsSet }
            runCatching {
                toMove.forEach { item ->
                    drive.moveFile(item.driveId, fromFolderId, toFolderId)
                    item.originalDriveId?.let { drive.moveFile(it, fromFolderId, toFolderId) }
                    item.sidecarDriveId?.let { drive.moveFile(it, fromFolderId, toFolderId) }
                }
            }.onSuccess {
                _state.update { s -> s.copy(images = s.images.filter { it.driveId !in idsSet }) }
                onDone(true)
            }.onFailure { e ->
                _state.update { it.copy(error = e.message) }
                onDone(false)
            }
        }
    }

    // ---------- Cache ----------

    /** Deletes all locally-cached image files and the JSON index, then re-fetches from Drive. */
    fun clearCacheAndRefresh() {
        val id = folderId ?: return
        viewModelScope.launch(Dispatchers.IO) {
            localCacheFile(id).delete()
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
    fun startRepairAndRefresh(folderIds: List<String>) {
        if (folderIds.isEmpty()) return
        acquireJobWakeLock()
        viewModelScope.launch(Dispatchers.IO) {
            try {
                Log.d(TAG, "Scan started — ${folderIds.size} folder(s): $folderIds")
                _state.update { it.copy(auditProgress = AuditProgress(isScanning = true, totalFolders = folderIds.size)) }

                var totalRenamed = 0
                val orphaned = mutableListOf<AuditItem>()
                val rawImages = mutableListOf<AuditItem>()
                val needSidecar = mutableListOf<AuditCutoutItem>()

                folderIds.forEachIndexed { idx, fid ->
                    runCatching {
                        val allImages = drive.listAllImageFiles(fid)
                        val sidecars  = drive.listSidecarFiles(fid)

                        val cutouts   = allImages.filter { it.name.endsWith(DriveRepository.CUTOUT_SUFFIX) }
                        val originals = allImages.filter { it.name.endsWith(DriveRepository.ORIGINAL_SUFFIX) }
                        val cutoutIds = cutouts.map { it.id }.toSet()

                        // Map cutoutId → sidecar DriveFileDto for content inspection
                        val sidecarByItemId = sidecars
                            .associateBy { it.name.removeSuffix(DriveRepository.SIDECAR_SUFFIX) }

                        Log.d(TAG, "Folder $fid: ${cutouts.size} cutout(s), ${originals.size} original(s), ${sidecars.size} sidecar(s)")

                        // Ensure every cutout is named "{id}_cutout.png"
                        cutouts.forEach { cutout ->
                            val expected = "${cutout.id}${DriveRepository.CUTOUT_SUFFIX}"
                            if (cutout.name != expected) {
                                Log.d(TAG, "Rename ${cutout.name} → $expected")
                                runCatching { drive.renameFile(cutout.id, expected) }
                                totalRenamed++
                            }
                        }

                        // Check originals: prefix must match a cutout Drive ID
                        originals.forEach { original ->
                            val prefix = original.name.removeSuffix(DriveRepository.ORIGINAL_SUFFIX)
                            if (prefix !in cutoutIds) {
                                Log.d(TAG, "Orphaned original: ${original.name} (${original.id})")
                                orphaned.add(AuditItem(fid, original.id, original.name))
                            }
                        }

                        // Collect raw/unknown images: not a cutout, not an original
                        allImages.forEach { img ->
                            if (!img.name.endsWith(DriveRepository.CUTOUT_SUFFIX) &&
                                !img.name.endsWith(DriveRepository.ORIGINAL_SUFFIX)) {
                                Log.d(TAG, "Raw/unknown image: ${img.name} (${img.id})")
                                rawImages.add(AuditItem(fid, img.id, img.name))
                            }
                        }

                        // Every cutout needs a sidecar with non-empty tags.
                        // Flag missing sidecars AND sidecars whose content is just "{}".
                        // Use file size to avoid downloading where possible:
                        //   size ≤ 20 bytes  → definitely empty ({} or {"tags":null}), no download needed
                        //   size ≥ 100 bytes → definitely has ClothingTags, skip download entirely
                        //   otherwise        → download and parse to be sure
                        cutouts.forEach { cutout ->
                            val sidecar = sidecarByItemId[cutout.id]
                            if (sidecar == null) {
                                Log.d(TAG, "Missing sidecar for cutout ${cutout.id} (${cutout.name})")
                                needSidecar.add(AuditCutoutItem(fid, cutout.id, "${cutout.id}${DriveRepository.CUTOUT_SUFFIX}"))
                            } else {
                                val bytes = sidecar.sizeBytes
                                when {
                                    bytes in 1..SIDECAR_EMPTY_MAX -> {
                                        // Too small to contain tags — skip download
                                        Log.d(TAG, "Empty sidecar for cutout ${cutout.id} (size=${bytes}B)")
                                        needSidecar.add(AuditCutoutItem(fid, cutout.id, "${cutout.id}${DriveRepository.CUTOUT_SUFFIX}"))
                                    }
                                    bytes >= SIDECAR_FULL_MIN -> {
                                        // Large enough to hold ClothingTags — skip download
                                        Log.d(TAG, "OK: cutout ${cutout.id} sidecar size=${bytes}B")
                                    }
                                    else -> {
                                        // Unknown size or borderline — download and parse
                                        val content = runCatching { drive.loadFileContent(sidecar.id) }.getOrNull()
                                        val hasTags = content?.let {
                                            runCatching {
                                                gson.fromJson(it, ItemSidecar::class.java).tags != null
                                            }.getOrDefault(false)
                                        } ?: false
                                        if (!hasTags) {
                                            Log.d(TAG, "Empty sidecar for cutout ${cutout.id} — content: $content")
                                            needSidecar.add(AuditCutoutItem(fid, cutout.id, "${cutout.id}${DriveRepository.CUTOUT_SUFFIX}"))
                                        } else {
                                            Log.d(TAG, "OK: cutout ${cutout.id} has sidecar with tags")
                                        }
                                    }
                                }
                            }
                        }
                    }.onFailure { e ->
                        Log.e(TAG, "Error scanning folder $fid: ${e.message}", e)
                    }
                    _state.update { s ->
                        s.copy(auditProgress = s.auditProgress?.copy(scannedFolders = idx + 1))
                    }
                }

                Log.d(TAG, "Scan complete — renamed=$totalRenamed orphaned=${orphaned.size} rawImages=${rawImages.size} needSidecar=${needSidecar.size}")

                // ---- Phase 1b: detect visually-similar cutouts using the on-device index. ----
                _state.update { it.copy(auditProgress = it.auditProgress?.copy(isScanningDuplicates = true)) }
                val duplicates = detectDuplicateCutouts(folderIds)
                _state.update { it.copy(auditProgress = it.auditProgress?.copy(isScanningDuplicates = false)) }
                Log.d(TAG, "Duplicate-detection complete — ${duplicates.size} cutout(s) flagged")

                pendingAudit = AuditIntermediate(folderIds, orphaned, rawImages, needSidecar, duplicates)
                val previewItems = buildList<AuditFileEntry> {
                    orphaned.forEach { add(AuditFileEntry(it.driveId, it.name, it.folderId, AuditKind.ORPHANED_ORIGINAL)) }
                    rawImages.forEach { add(AuditFileEntry(it.driveId, it.name, it.folderId, AuditKind.RAW)) }
                    needSidecar.forEach { add(AuditFileEntry(it.cutoutDriveId, it.cutoutName, it.folderId, AuditKind.NEEDS_SIDECAR)) }
                    duplicates.forEach { d ->
                        add(AuditFileEntry(d.cutoutDriveId, d.cutoutName, d.folderId, AuditKind.DUPLICATE,
                            similarTo = d.similarTo, topScore = d.topScore))
                    }
                }
                // Default selection: every fix-needed item (process), but no duplicates (delete is destructive).
                val defaultSelection = previewItems.filter { it.kind != AuditKind.DUPLICATE }.map { it.driveId }.toSet()
                _state.update { it.copy(
                    auditProgress = AuditProgress(
                        awaitingConfirmation = true,
                        renamedCount = totalRenamed,
                        orphanedOriginals = orphaned.size,
                        rawImages = rawImages.size,
                        sidecarNeeded = needSidecar.size,
                        duplicates = duplicates.size,
                        items = previewItems,
                        selectedAuditIds = defaultSelection,
                    )
                )}
            } finally {
                // Wake lock held until user responds to the confirmation dialog;
                // continueRepairProcessing re-acquires for the processing phase.
                releaseJobWakeLock()
            }
        }
    }

    /**
     * Called after the user responds to the repair confirmation dialog.
     *
     * If [process] is true, runs AI bg-removal + tagging for orphaned originals and
     * tagging-only for cutouts missing sidecars. When [clearCache] is true, also
     * wipes the local image cache so everything is re-downloaded from Drive on reload;
     * otherwise the existing cache is kept and only the metadata snapshot is refreshed.
     */
    fun continueRepairProcessing(process: Boolean, clearCache: Boolean = false) {
        val audit = pendingAudit ?: run {
            _state.update { it.copy(auditProgress = null) }
            if (clearCache) clearCacheAndRefresh() else loadImages()
            return
        }
        pendingAudit = null

        // Filter the scan results down to whatever the user left checked in the preview grid.
        // When invoked with process=false, treat as "skip everything" regardless of selection.
        val selected: Set<String> =
            if (process) _state.value.auditProgress?.selectedAuditIds.orEmpty() else emptySet()
        val filteredOrphaned = audit.orphanedOriginals.filter { it.driveId in selected }
        val filteredRaw      = audit.rawImages.filter        { it.driveId in selected }
        val filteredSidecar  = audit.cutoutsNeedingSidecar.filter { it.cutoutDriveId in selected }
        val filteredDuplicates = audit.duplicates.filter      { it.cutoutDriveId in selected }
        val anySelected = filteredOrphaned.isNotEmpty() || filteredRaw.isNotEmpty() ||
            filteredSidecar.isNotEmpty() || filteredDuplicates.isNotEmpty()

        if (!process || !anySelected) {
            Log.d(TAG, "User skipped processing (process=$process, selected=${selected.size}, clearCache=$clearCache) — reloading")
            _state.update { it.copy(auditProgress = null) }
            viewModelScope.launch(Dispatchers.IO) {
                if (clearCache) {
                    audit.folderIds.forEach { localCacheFile(it).delete() }
                    getApplication<Application>().filesDir.resolve("wardrobe")
                        .listFiles()?.forEach { it.delete() }
                }
                withContext(Dispatchers.Main) { loadImages() }
            }
            return
        }

        val processTotal = filteredOrphaned.size + filteredRaw.size + filteredSidecar.size + filteredDuplicates.size
        Log.d(TAG, "Processing started — ${filteredOrphaned.size} orphaned original(s), ${filteredRaw.size} raw image(s), ${filteredSidecar.size} cutout(s) needing tagging, ${filteredDuplicates.size} duplicate(s) to delete (selection-filtered)")
        _state.update { it.copy(auditProgress = AuditProgress(isProcessing = true, processTotal = processTotal)) }
        acquireJobWakeLock()

        viewModelScope.launch(Dispatchers.IO) {
            try {
                var done = 0

                // --- Orphaned originals: bg removal + cutout upload + tag + sidecar ---
                filteredOrphaned.forEach { item ->
                    Log.d(TAG, "Processing orphaned original: ${item.name} (${item.driveId})")
                    runCatching {
                        val localOriginal = drive.downloadToCache(item.driveId, item.name)
                            ?: run { Log.w(TAG, "Could not download original ${item.driveId}"); return@runCatching }
                        Log.d(TAG, "Downloaded original to ${localOriginal.absolutePath}")
                        val cutoutFile = gemini.removeBackground(localOriginal, drive.cacheDir)
                            ?: localOriginal.also { Log.w(TAG, "BG removal failed for ${item.driveId} — using original") }
                        Log.d(TAG, "Cutout file: ${cutoutFile.absolutePath}")
                        val cutoutDrive = uploadAsCutout(item.folderId, cutoutFile)
                        Log.d(TAG, "Cutout uploaded as ${cutoutDrive.id}")
                        // Upload original to Drive with correct name before deleteFile, which also
                        // removes the local cache file for item.driveId (= localOriginal).
                        val newOrigId = runCatching {
                            drive.uploadImageWithName(
                                item.folderId, localOriginal,
                                "${cutoutDrive.id}${DriveRepository.ORIGINAL_SUFFIX}",
                            ).id
                        }.onFailure { Log.w(TAG, "Original re-upload failed: ${it.message}") }.getOrNull()
                        Log.d(TAG, "Original re-uploaded as $newOrigId")
                        // Cache both files locally before deleting the raw Drive file.
                        val localCutout = File(drive.cacheDir, "${cutoutDrive.id}.png")
                        if (cutoutFile.absolutePath != localCutout.absolutePath) {
                            cutoutFile.copyTo(localCutout, overwrite = true)
                        }
                        localOriginal.copyTo(
                            File(drive.cacheDir, "${cutoutDrive.id}_original.jpg"), overwrite = true,
                        )
                        runCatching { drive.deleteFile(item.driveId) }
                        val tags = gemini.classifyClothing(localCutout, geminiLanguage)
                        Log.d(TAG, "Tags for ${cutoutDrive.id}: $tags")
                        drive.upsertSidecar(
                            item.folderId,
                            "${cutoutDrive.id}${DriveRepository.SIDECAR_SUFFIX}",
                            gson.toJson(ItemSidecar(tags, newOrigId)),
                        )
                        Log.d(TAG, "Sidecar written for ${cutoutDrive.id}")
                    }.onFailure { e ->
                        Log.e(TAG, "Failed processing orphaned original ${item.driveId}: ${e.message}", e)
                    }
                    done++
                    _state.update { s -> s.copy(auditProgress = s.auditProgress?.copy(processDone = done)) }
                }

                // --- Raw/unknown images: full AI processing (same as orphaned originals) ---
                filteredRaw.forEach { item ->
                    Log.d(TAG, "Processing raw image: ${item.name} (${item.driveId})")
                    runCatching {
                        val localOriginal = drive.downloadToCache(item.driveId, item.name)
                            ?: run { Log.w(TAG, "Could not download raw image ${item.driveId}"); return@runCatching }
                        Log.d(TAG, "Downloaded raw image to ${localOriginal.absolutePath}")
                        val cutoutFile = gemini.removeBackground(localOriginal, drive.cacheDir)
                            ?: localOriginal.also { Log.w(TAG, "BG removal failed for ${item.driveId} — using original") }
                        Log.d(TAG, "Cutout file: ${cutoutFile.absolutePath}")
                        val cutoutDrive = uploadAsCutout(item.folderId, cutoutFile)
                        Log.d(TAG, "Cutout uploaded as ${cutoutDrive.id}")
                        // Upload original to Drive with correct name before deleteFile, which also
                        // removes the local cache file for item.driveId (= localOriginal).
                        val newOrigId = runCatching {
                            drive.uploadImageWithName(
                                item.folderId, localOriginal,
                                "${cutoutDrive.id}${DriveRepository.ORIGINAL_SUFFIX}",
                            ).id
                        }.onFailure { Log.w(TAG, "Original re-upload failed: ${it.message}") }.getOrNull()
                        Log.d(TAG, "Original re-uploaded as $newOrigId")
                        // Cache both files locally before deleting the raw Drive file.
                        val localCutout = File(drive.cacheDir, "${cutoutDrive.id}.png")
                        if (cutoutFile.absolutePath != localCutout.absolutePath) {
                            cutoutFile.copyTo(localCutout, overwrite = true)
                        }
                        localOriginal.copyTo(
                            File(drive.cacheDir, "${cutoutDrive.id}_original.jpg"), overwrite = true,
                        )
                        runCatching { drive.deleteFile(item.driveId) }
                        val tags = gemini.classifyClothing(localCutout, geminiLanguage)
                        Log.d(TAG, "Tags for ${cutoutDrive.id}: $tags")
                        drive.upsertSidecar(
                            item.folderId,
                            "${cutoutDrive.id}${DriveRepository.SIDECAR_SUFFIX}",
                            gson.toJson(ItemSidecar(tags, newOrigId)),
                        )
                        Log.d(TAG, "Sidecar written for ${cutoutDrive.id}")
                    }.onFailure { e ->
                        Log.e(TAG, "Failed processing raw image ${item.driveId}: ${e.message}", e)
                    }
                    done++
                    _state.update { s -> s.copy(auditProgress = s.auditProgress?.copy(processDone = done)) }
                }

                // --- Cutouts missing sidecars or with empty tags: tag from cutout + write sidecar ---
                filteredSidecar.forEach { item ->
                    Log.d(TAG, "Tagging cutout: ${item.cutoutName} (${item.cutoutDriveId})")
                    runCatching {
                        val localCutout = drive.cachedFile(item.cutoutDriveId)
                            ?: drive.downloadToCache(item.cutoutDriveId, item.cutoutName)
                            ?: run { Log.w(TAG, "Could not get local cutout ${item.cutoutDriveId}"); return@runCatching }
                        Log.d(TAG, "Cutout local path: ${localCutout.absolutePath}")
                        val tags = gemini.classifyClothing(localCutout, geminiLanguage)
                        Log.d(TAG, "Tags for ${item.cutoutDriveId}: $tags")
                        drive.upsertSidecar(
                            item.folderId,
                            "${item.cutoutDriveId}${DriveRepository.SIDECAR_SUFFIX}",
                            gson.toJson(ItemSidecar(tags, null)),
                        )
                        Log.d(TAG, "Sidecar written for ${item.cutoutDriveId}")
                    }.onFailure { e ->
                        Log.e(TAG, "Failed tagging cutout ${item.cutoutDriveId}: ${e.message}", e)
                    }
                    done++
                    _state.update { s -> s.copy(auditProgress = s.auditProgress?.copy(processDone = done)) }
                }

                // --- Duplicates: delete the cutout + original + sidecar from Drive ---
                filteredDuplicates.forEach { item ->
                    Log.d(TAG, "Deleting duplicate cutout: ${item.cutoutName} (${item.cutoutDriveId})")
                    runCatching {
                        // Find the in-memory item to grab its original + sidecar Drive IDs
                        val existing = _state.value.images.find { it.driveId == item.cutoutDriveId }
                        existing?.originalDriveId?.let { runCatching { drive.deleteFile(it) } }
                        existing?.sidecarDriveId?.let { runCatching { drive.deleteFile(it) } }
                        drive.deleteFile(item.cutoutDriveId)
                        EmbeddingService.index.remove(item.cutoutDriveId)
                    }.onFailure { e ->
                        Log.e(TAG, "Failed deleting duplicate ${item.cutoutDriveId}: ${e.message}", e)
                    }
                    done++
                    _state.update { s -> s.copy(auditProgress = s.auditProgress?.copy(processDone = done)) }
                }
                if (filteredDuplicates.isNotEmpty()) {
                    runCatching { EmbeddingService.index.save() }
                }

                Log.d(TAG, "Processing complete (clearCache=$clearCache) — reloading")
                if (clearCache) {
                    audit.folderIds.forEach { localCacheFile(it).delete() }
                    getApplication<Application>().filesDir.resolve("wardrobe")
                        .listFiles()?.forEach { it.delete() }
                }

                _state.update { it.copy(auditProgress = AuditProgress(isDone = true)) }
                withContext(Dispatchers.Main) { loadImages() }
            } finally {
                releaseJobWakeLock()
            }
        }
    }

    /**
     * Embed every cached cutout across [folderIds] (skipping those already in the index) and
     * search the index for above-threshold peers, excluding the cutout itself. Each entry in the
     * returned list represents one cutout that has at least one visually-similar peer.
     *
     * Cutouts not present in the local cache are skipped — embedding requires the cutout file to
     * be downloaded, which would be expensive during a scan and is not needed: those cutouts will
     * be handled the next time the user opens the wardrobe.
     */
    private suspend fun detectDuplicateCutouts(folderIds: List<String>): List<AuditDuplicateItem> {
        if (!EmbeddingService.isModelAvailable()) return emptyList()
        // Use the cross-closet snapshot so the duplicate scan covers every configured closet
        // (matches the contract with [startRepairAndRefresh], which passes every folderId).
        refreshAllLocationImagesState()
        val crossClosetImages = _state.value.allLocationImages
            .filter { folderIds.isEmpty() || it.folderId in folderIds }
        EmbeddingService.syncIndex(crossClosetImages, drive.cacheDir)
        val byDriveId = crossClosetImages.associateBy { it.driveId }
        val scope = byDriveId.keys
        // Repair & Sync uses a stricter threshold than capture/import dedupe: an unattended scan
        // over the entire wardrobe surfaces far more near-collisions than a one-shot capture, so
        // we want only very confident matches to appear in the delete-candidates list.
        val repairThreshold = maxOf(dedupeThreshold, REPAIR_DUPE_THRESHOLD)
        val clusters = EmbeddingService.findDuplicateClusters(repairThreshold, restrictToIds = scope)
        if (clusters.isEmpty()) return emptyList()
        // For each anchor with similar peers, build an audit entry. Anchors with the same set of
        // peers are equally valid candidates for deletion — surface them all so the user picks.
        return clusters.entries.mapNotNull { (anchorId, peers) ->
            val img = byDriveId[anchorId] ?: return@mapNotNull null
            AuditDuplicateItem(
                folderId = img.folderId.ifEmpty { folderIds.firstOrNull().orEmpty() },
                cutoutDriveId = anchorId,
                cutoutName = img.name,
                similarTo = peers.map { it.driveId },
                topScore = peers.firstOrNull()?.score ?: 0f,
            )
        }.sortedByDescending { it.topScore }
    }

    /** Clears the audit result state (call after user dismisses the "done" message). */
    fun dismissAuditResult() {
        _state.update { it.copy(auditProgress = null) }
    }

    /** Toggle whether [driveId] will be processed by Repair & Sync. No-op outside the confirmation step. */
    fun toggleAuditSelection(driveId: String) {
        _state.update { s ->
            val a = s.auditProgress ?: return@update s
            if (!a.awaitingConfirmation) return@update s
            val next = a.selectedAuditIds.toMutableSet()
            if (!next.add(driveId)) next.remove(driveId)
            s.copy(auditProgress = a.copy(selectedAuditIds = next))
        }
    }

    /** Replace the audit selection (used by Select all / Deselect all). */
    fun setAuditSelection(driveIds: Set<String>) {
        _state.update { s ->
            val a = s.auditProgress ?: return@update s
            if (!a.awaitingConfirmation) return@update s
            s.copy(auditProgress = a.copy(selectedAuditIds = driveIds))
        }
    }

    /**
     * Lazily fetches a local thumbnail file for one [AuditFileEntry]. Falls back to
     * the existing wardrobe cache for cutouts before downloading from Drive. Returns null
     * on failure — callers should show a placeholder.
     */
    suspend fun fetchAuditThumbnail(entry: AuditFileEntry): File? = withContext(Dispatchers.IO) {
        // Prefer an already-cached file (especially for NEEDS_SIDECAR cutouts, which the
        // wardrobe load may have downloaded into cache as `{id}.png`).
        drive.cachedFile(entry.driveId)?.let { return@withContext it }
        runCatching { drive.downloadToCache(entry.driveId, entry.name) }.getOrNull()
    }

    // ---------- Fix cutout backgrounds (Settings → Data) ----------

    /** Scans every cutout in [folderIds] for black-background / green-halo issues and pauses
     *  with awaitingConfirmation=true. The user reviews the preview grid (flagged-only by
     *  default; toggle to show all) and selects which to fix. */
    fun startCutoutBgFixScan(folderIds: List<String>) {
        if (folderIds.isEmpty()) return
        acquireJobWakeLock()
        viewModelScope.launch(Dispatchers.IO) {
            try {
                _state.update {
                    it.copy(cutoutBgFix = CutoutBgFixProgress(
                        isScanning = true,
                        totalFolders = folderIds.size,
                    ))
                }
                val items = mutableListOf<CutoutFixEntry>()
                val flagged = mutableSetOf<String>()

                folderIds.forEachIndexed { idx, fid ->
                    val cutouts = runCatching {
                        drive.listAllImageFiles(fid)
                            .filter { it.name.endsWith(DriveRepository.CUTOUT_SUFFIX) }
                    }.getOrDefault(emptyList())
                    _state.update { s ->
                        s.copy(cutoutBgFix = s.cutoutBgFix?.copy(
                            totalCutouts = s.cutoutBgFix.totalCutouts + cutouts.size,
                        ))
                    }
                    cutouts.forEach { cf ->
                        val local = drive.cachedFile(cf.id)
                            ?: runCatching { drive.downloadToCache(cf.id, cf.name) }.getOrNull()
                        if (local != null) {
                            val issues = runCatching { detectCutoutIssues(local) }
                                .getOrDefault(CutoutIssues(false, false))
                            val entry = CutoutFixEntry(
                                driveId = cf.id,
                                name = cf.name,
                                folderId = fid,
                                hasBlackBackground = issues.hasBlackBackground,
                                hasGreenHalo = issues.hasGreenHalo,
                            )
                            items.add(entry)
                            if (issues.any) flagged.add(cf.id)
                        }
                        _state.update { s ->
                            s.copy(cutoutBgFix = s.cutoutBgFix?.copy(
                                scannedCutouts = s.cutoutBgFix.scannedCutouts + 1,
                            ))
                        }
                    }
                    _state.update { s ->
                        s.copy(cutoutBgFix = s.cutoutBgFix?.copy(scannedFolders = idx + 1))
                    }
                }

                val anyBlack = items.any { it.hasBlackBackground }
                val anyGreen = items.any { it.hasGreenHalo }
                _state.update {
                    it.copy(cutoutBgFix = CutoutBgFixProgress(
                        awaitingConfirmation = true,
                        items = items.toList(),
                        flaggedIds = flagged.toSet(),
                        selectedIds = flagged.toSet(),
                        // When nothing is flagged, default to "show all" so the dialog stays
                        // useful — the user can still hand-pick clean cutouts to re-process.
                        showAll = flagged.isEmpty(),
                        applyBlackToAlpha = anyBlack,
                        applyDespillGreen = anyGreen,
                        totalCutouts = items.size,
                        scannedCutouts = items.size,
                    ))
                }
            } finally {
                releaseJobWakeLock()
            }
        }
    }

    /** Apply fixes for the currently selected entries, or just clear state if [process] is false. */
    fun continueCutoutBgFix(process: Boolean) {
        val cur = _state.value.cutoutBgFix
        if (cur == null || !cur.awaitingConfirmation) return
        if (!process || cur.selectedIds.isEmpty()) {
            _state.update { it.copy(cutoutBgFix = null) }
            return
        }
        val byId = cur.items.associateBy { it.driveId }
        val toFix = cur.selectedIds.mapNotNull { byId[it] }
        val actions = CutoutFixActions(
            blackToAlpha = cur.applyBlackToAlpha,
            despillGreen = cur.applyDespillGreen,
            feather = cur.applyFeather,
            tightCrop = cur.applyTightCrop,
        )
        if (toFix.isEmpty() || !actions.any) {
            _state.update { it.copy(cutoutBgFix = null) }
            return
        }
        acquireJobWakeLock()
        _state.update {
            it.copy(cutoutBgFix = CutoutBgFixProgress(
                isProcessing = true,
                processTotal = toFix.size,
            ))
        }
        viewModelScope.launch(Dispatchers.IO) {
            try {
                var done = 0
                toFix.forEach { entry ->
                    runCatching {
                        val local = drive.cachedFile(entry.driveId)
                            ?: drive.downloadToCache(entry.driveId, entry.name)
                            ?: return@runCatching
                        val tmp = File(drive.cacheDir, "${entry.driveId}_fix.png")
                        fixCutoutBackground(local, tmp, actions)
                        drive.updateImage(entry.driveId, tmp)
                        val cached = File(drive.cacheDir, "${entry.driveId}.png")
                        tmp.copyTo(cached, overwrite = true)
                        tmp.delete()
                    }.onFailure { e ->
                        Log.w(TAG, "Cutout bg-fix failed for ${entry.driveId}: ${e.message}")
                    }
                    done++
                    _state.update { s ->
                        s.copy(cutoutBgFix = s.cutoutBgFix?.copy(processDone = done))
                    }
                }
                // Bump version on every fixed item so Coil reloads from the updated local cache.
                val fixedIds = toFix.map { it.driveId }.toSet()
                _state.update { s ->
                    s.copy(images = s.images.map {
                        if (it.driveId in fixedIds) it.copy(version = it.version + 1) else it
                    })
                }
                _state.update { s ->
                    s.copy(cutoutBgFix = s.cutoutBgFix?.copy(
                        isProcessing = false,
                        isDone = true,
                    ))
                }
            } finally {
                releaseJobWakeLock()
            }
        }
    }

    fun toggleCutoutFixSelection(driveId: String) {
        _state.update { s ->
            val c = s.cutoutBgFix ?: return@update s
            if (!c.awaitingConfirmation) return@update s
            val next = c.selectedIds.toMutableSet()
            if (!next.add(driveId)) next.remove(driveId)
            s.copy(cutoutBgFix = c.copy(selectedIds = next))
        }
    }

    fun setCutoutFixSelection(ids: Set<String>) {
        _state.update { s ->
            val c = s.cutoutBgFix ?: return@update s
            if (!c.awaitingConfirmation) return@update s
            s.copy(cutoutBgFix = c.copy(selectedIds = ids))
        }
    }

    fun setCutoutFixAction(
        blackToAlpha: Boolean? = null,
        despillGreen: Boolean? = null,
        feather: Boolean? = null,
        tightCrop: Boolean? = null,
    ) {
        _state.update { s ->
            val c = s.cutoutBgFix ?: return@update s
            if (!c.awaitingConfirmation) return@update s
            s.copy(cutoutBgFix = c.copy(
                applyBlackToAlpha = blackToAlpha ?: c.applyBlackToAlpha,
                applyDespillGreen = despillGreen ?: c.applyDespillGreen,
                applyFeather = feather ?: c.applyFeather,
                applyTightCrop = tightCrop ?: c.applyTightCrop,
            ))
        }
    }

    fun setCutoutFixShowAll(v: Boolean) {
        _state.update { s ->
            val c = s.cutoutBgFix ?: return@update s
            s.copy(cutoutBgFix = c.copy(showAll = v))
        }
    }

    fun dismissCutoutBgFix() {
        _state.update { it.copy(cutoutBgFix = null) }
    }

    /** Detect + repair black-bg / green-halo / interior-hole issues on a single cutout, then
     *  re-upload preserving the Drive ID. Surfaces a status message via [_state.error]; UI
     *  treats it as a transient banner. */
    fun fixCutoutBgForItem(
        driveId: String,
        actions: CutoutFixActions = CutoutFixActions(
            blackToAlpha = true,
            despillGreen = true,
            feather = true,
            tightCrop = true,
        ),
    ) {
        if (!actions.any) return
        val image = _state.value.images.firstOrNull { it.driveId == driveId } ?: return
        acquireJobWakeLock()
        _state.update { it.copy(processingImageId = driveId) }
        viewModelScope.launch(Dispatchers.IO) {
            try {
                val local = drive.cachedFile(driveId)
                    ?: drive.downloadToCache(driveId, image.name)
                    ?: return@launch
                val tmp = File(drive.cacheDir, "${driveId}_fix.png")
                fixCutoutBackground(local, tmp, actions)
                drive.updateImage(driveId, tmp)
                val cached = File(drive.cacheDir, "${driveId}.png")
                tmp.copyTo(cached, overwrite = true)
                tmp.delete()
                _state.update { s ->
                    s.copy(images = s.images.map {
                        if (it.driveId == driveId) it.copy(version = it.version + 1) else it
                    })
                }
            } catch (e: Exception) {
                Log.w(TAG, "Single-item cutout bg-fix failed for $driveId: ${e.message}", e)
                _state.update { it.copy(error = e.message) }
            } finally {
                _state.update { it.copy(processingImageId = null) }
                releaseJobWakeLock()
            }
        }
    }

    suspend fun fetchCutoutFixThumbnail(entry: CutoutFixEntry): File? = withContext(Dispatchers.IO) {
        drive.cachedFile(entry.driveId)?.let { return@withContext it }
        runCatching { drive.downloadToCache(entry.driveId, entry.name) }.getOrNull()
    }

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

    private fun localCacheFile(id: String) =
        File(getApplication<Application>().filesDir, "wardrobe_cache_$id.json")

    private fun saveLocalCache(id: String, images: List<DriveImage>) {
        runCatching {
            val cache = LocalCache(images.map {
                LocalCacheEntry(it.driveId, it.name, it.tags, it.originalDriveId, it.sidecarDriveId, it.createdTimeMs)
            })
            localCacheFile(id).writeText(gson.toJson(cache))
        }
        // Keep the cross-closet snapshot in sync so similarity search reflects the latest items.
        refreshAllLocationImagesState()
    }

    // ---------- Load ----------

    fun loadImages() {
        loadJob?.cancel()
        pruneRecentlyMoved()
        loadJob = viewModelScope.launch {
            val ids = allFolderIds
            if (ids != null) {
                _state.update { it.copy(isLoading = true, error = null) }
                // Phase 1 — instant: merge caches from all folders
                val cachedAll = ids.flatMap { fid ->
                    val cacheFile = localCacheFile(fid)
                    if (!cacheFile.exists()) return@flatMap emptyList()
                    runCatching {
                        val cache = gson.fromJson(cacheFile.readText(), LocalCache::class.java)
                        cache.items.mapNotNull { entry ->
                            drive.cachedFile(entry.driveId)?.let { f ->
                                DriveImage(entry.driveId, f.absolutePath, entry.name, entry.tags,
                                    originalDriveId = entry.originalDriveId,
                                    sidecarDriveId = entry.sidecarDriveId,
                                    folderId = fid,
                                    createdTimeMs = entry.createdTimeMs)
                            }
                        }
                    }.getOrDefault(emptyList())
                }
                if (cachedAll.isNotEmpty()) _state.update { it.copy(images = cachedAll, isLoading = false) }
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
                    if (uncachedCount > 0) _state.update { it.copy(syncTotal = uncachedCount, syncDone = 0) }
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

                    // Load all sidecar content in parallel
                    val sidecarContent: Map<String, ItemSidecar> = folderMeta.flatMap { fd ->
                        fd.sidecarFiles.map { sf ->
                            async {
                                val itemId = sf.name.removeSuffix(DriveRepository.SIDECAR_SUFFIX)
                                val content = drive.loadFileContent(sf.id)
                                itemId to content?.let {
                                    runCatching { gson.fromJson(it, ItemSidecar::class.java) }.getOrNull()
                                }
                            }
                        }
                    }.awaitAll().mapNotNull { (k, v) -> v?.let { k to it } }.toMap()

                    // Build DriveImage list per folder
                    val allFresh = folderMeta.flatMap { fd ->
                        val sidecarIdByItemId = fd.sidecarFiles.associate { sf ->
                            sf.name.removeSuffix(DriveRepository.SIDECAR_SUFFIX) to sf.id
                        }
                        fd.files.mapNotNull { file ->
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
                    val pendingRaw = _state.value.images.filter { img ->
                        img.driveId !in freshIds && (
                            !img.name.endsWith(DriveRepository.CUTOUT_SUFFIX) ||
                                recentlyMovedItems[img.driveId]?.first == img.folderId
                            )
                    }
                    allFresh + pendingRaw
                }.onSuccess { images ->
                    _state.update { it.copy(images = images, isLoading = false, syncTotal = 0, syncDone = 0, isSyncing = false) }
                }.onFailure { e ->
                    _state.update { s ->
                        s.copy(isLoading = false, syncTotal = 0, syncDone = 0, isSyncing = false, error = if (s.images.isEmpty()) e.message else null)
                    }
                }
                return@launch
            }

            val id = folderId ?: return@launch
            _state.update { it.copy(isLoading = true, error = null) }

            // Phase 1 — instant: show whatever is already on disk (zero network calls)
            val cacheFile = localCacheFile(id)
            if (cacheFile.exists()) {
                runCatching {
                    val cache = gson.fromJson(cacheFile.readText(), LocalCache::class.java)
                    cache.items.mapNotNull { entry ->
                        drive.cachedFile(entry.driveId)?.let { f ->
                            DriveImage(entry.driveId, f.absolutePath, entry.name, entry.tags,
                                originalDriveId = entry.originalDriveId,
                                sidecarDriveId = entry.sidecarDriveId,
                                folderId = id,
                                createdTimeMs = entry.createdTimeMs)
                        }
                    }
                }.onSuccess { items ->
                    if (items.isNotEmpty()) {
                        _state.update { it.copy(images = items, isLoading = false) }
                    }
                }
            }

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
                if (uncachedCount > 0) _state.update { it.copy(syncTotal = uncachedCount, syncDone = 0) }
                val doneCount = AtomicInteger(0)
                files.map { file ->
                    async {
                        val cached = drive.cachedFile(file.id)
                        val r = cached ?: drive.downloadToCache(file.id, file.name)
                        if (cached == null) _state.update { it.copy(syncDone = doneCount.incrementAndGet()) }
                        r
                    }
                }.awaitAll()

                // Load sidecar content in parallel
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

                // Legacy metadata fallback — only fetch if no sidecars exist yet (migration)
                val legacyMeta: Map<String, WardrobeItemMeta> = if (sidecarFiles.isEmpty()) {
                    drive.loadWardrobeMetadataJson(id)?.let { json ->
                        runCatching {
                            gson.fromJson(json, WardrobeMetadata::class.java).items.associateBy { it.name }
                        }.getOrDefault(emptyMap())
                    } ?: emptyMap()
                } else emptyMap()

                val freshImages = files.mapNotNull { file ->
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
                val pendingRaw = _state.value.images.filter { img ->
                    img.driveId !in freshIds && (
                        !img.name.endsWith(DriveRepository.CUTOUT_SUFFIX) ||
                            recentlyMovedItems[img.driveId]?.first == id
                        )
                }

                // Migrate legacy items to sidecars fire-and-forget
                if (legacyMeta.isNotEmpty()) {
                    viewModelScope.launch(Dispatchers.IO) {
                        freshImages.filter { it.sidecarDriveId == null }.forEach { img ->
                            runCatching {
                                val sidecar = ItemSidecar(img.tags, img.originalDriveId)
                                val sidecarFileId = drive.upsertSidecar(
                                    id, "${img.driveId}${DriveRepository.SIDECAR_SUFFIX}",
                                    gson.toJson(sidecar),
                                )
                                _state.update { s ->
                                    s.copy(images = s.images.map { i ->
                                        if (i.driveId == img.driveId) i.copy(sidecarDriveId = sidecarFileId) else i
                                    })
                                }
                            }
                        }
                        saveLocalCache(id, _state.value.images)
                    }
                }

                freshImages + pendingRaw
            }.onSuccess { images ->
                _state.update { it.copy(images = images, isLoading = false, syncTotal = 0, syncDone = 0, isSyncing = false) }
                saveLocalCache(id, images)
            }.onFailure { e ->
                // Don't overwrite cached items already shown with an error banner
                _state.update { s ->
                    s.copy(isLoading = false, syncTotal = 0, syncDone = 0, isSyncing = false, error = if (s.images.isEmpty()) e.message else null)
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
     * Saves a per-item sidecar JSON for [driveId] to Drive, and updates local disk cache.
     * Each item has its own file ("${driveId}.json") — no mutex needed; writes are independent.
     */
    private fun saveSidecar(driveId: String) {
        val id = folderId ?: return
        viewModelScope.launch(Dispatchers.IO) {
            val img = _state.value.images.find { it.driveId == driveId } ?: return@launch
            val sidecarJson = gson.toJson(ItemSidecar(img.tags, img.originalDriveId))
            val sidecarFileId = runCatching {
                drive.upsertSidecar(id, "$driveId${DriveRepository.SIDECAR_SUFFIX}", sidecarJson)
            }.getOrNull() ?: return@launch
            metaMutex.withLock {
                _state.update { s ->
                    s.copy(images = s.images.map { i ->
                        if (i.driveId == driveId) i.copy(sidecarDriveId = sidecarFileId) else i
                    })
                }
                saveLocalCache(id, _state.value.images)
            }
        }
    }

    // ---------- Naming helpers ----------

    /**
     * Uploads [imageFile] to [folderId], then immediately renames it to
     * "{driveId}_cutout.png" (where driveId is the Drive-assigned ID).
     * Returns the DriveFileDto with [name] already set to the final name.
     */
    private suspend fun uploadAsCutout(folderId: String, imageFile: File): DriveFileDto {
        val uploaded = drive.uploadImage(folderId, imageFile)
        val finalName = "${uploaded.id}${DriveRepository.CUTOUT_SUFFIX}"
        runCatching { drive.renameFile(uploaded.id, finalName) }
        return uploaded.copy(name = finalName)
    }

    /**
     * Uploads [imageFile] to [folderId] with filename "{cutoutDriveId}_original.jpg".
     * Returns the new Drive file ID.
     */
    private suspend fun uploadAsOriginal(folderId: String, imageFile: File, cutoutDriveId: String): String =
        drive.uploadImageWithName(folderId, imageFile, "$cutoutDriveId${DriveRepository.ORIGINAL_SUFFIX}").id

    /**
     * Resolves the Drive ID of a cutout item given its [metaName] (possibly old-format).
     * Checks by Drive ID directly (new format "{id}_cutout.png") or by filename (old formats).
     */
    private fun resolveCutoutDriveId(
        metaName: String,
        fileByName: Map<String, DriveFileDto>,
        fileById: Map<String, DriveFileDto>,
    ): String? {
        if (metaName.endsWith(DriveRepository.CUTOUT_SUFFIX)) {
            val possibleId = metaName.removeSuffix(DriveRepository.CUTOUT_SUFFIX)
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
                    .onFailure { e -> _state.update { it.copy(error = e.message) } }
                _state.update { s ->
                    s.copy(
                        pendingJobs = maxOf(0, s.pendingJobs - 1),
                        processingImageId = if (s.processingImageId == job.driveId)
                            null else s.processingImageId,
                    )
                }
                // Sidecar is saved inside processQueuedImage; update local cache here
                folderId?.let { id -> saveLocalCache(id, _state.value.images) }
            } finally {
                releaseJobWakeLock()
            }
        }
    }

    private suspend fun processQueuedImage(job: PendingJob) {
        val rawFile = File(drive.cacheDir, "${job.driveId}_original.jpg")
        if (!rawFile.exists()) return
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
        val cutoutDrive = runCatching { uploadAsCutout(job.folderId, processedFile) }.getOrNull() ?: return

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

        // Step 6 — update state. Match by rawId OR cutoutId: if loadImages() ran concurrently
        // it may have already placed the item in state with cutoutDrive.id.
        _state.update { s ->
            s.copy(
                images = s.images.map { img ->
                    if (img.driveId == job.driveId || img.driveId == cutoutDrive.id) img.copy(
                        driveId    = cutoutDrive.id,
                        name       = cutoutDrive.name,          // "{cutoutId}_cutout.png"
                        localPath  = localCutout.absolutePath,
                        version    = System.currentTimeMillis(),
                        originalDriveId = originalDriveId,
                    ) else img
                },
                processingImageId = if (s.processingImageId == job.driveId)
                    cutoutDrive.id else s.processingImageId,
            )
        }

        // Step 7 — classify clothing tags
        val tags = gemini.classifyClothing(localCutout, geminiLanguage)
        if (tags != null) {
            _state.update { s ->
                s.copy(images = s.images.map { img ->
                    if (img.driveId == cutoutDrive.id) img.copy(tags = tags) else img
                })
            }
        }

        // Step 8 — save sidecar (includes tags even if null, so item is discoverable on next load)
        val sidecarJson = gson.toJson(ItemSidecar(tags, originalDriveId))
        runCatching {
            drive.upsertSidecar(
                job.folderId, "${cutoutDrive.id}${DriveRepository.SIDECAR_SUFFIX}", sidecarJson,
            )
        }.onSuccess { sidecarId ->
            _state.update { s ->
                s.copy(images = s.images.map { img ->
                    if (img.driveId == cutoutDrive.id) img.copy(sidecarDriveId = sidecarId) else img
                })
            }
        }
        _state.update { s ->
            if (s.processingImageId == cutoutDrive.id || s.processingImageId == job.driveId)
                s.copy(processingImageId = null) else s
        }
    }

    // ---------- Navigation ----------

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
            // limited by the active location filter.
            refreshAllLocationImagesState()
            val crossClosetImages = _state.value.allLocationImages
            EmbeddingService.syncIndex(crossClosetImages, drive.cacheDir)
            val sim = EmbeddingService.findSimilarWithDebug(
                file = rawFile,
                threshold = -1f,
                topK = if (debugSimilarityPreview) 50 else 12,
                processedOutputDir = File(getApplication<Application>().cacheDir, QUERY_DEBUG_DIR),
            )
            val byId = crossClosetImages.associateBy { it.driveId }
            val resolved = sim?.matches.orEmpty().mapNotNull { m ->
                byId[m.driveId]?.let { FindByPhotoMatch(it, m.score) }
            }
            _state.update {
                it.copy(findByPhoto = FindByPhoto(
                    queryPath = rawFile.absolutePath,
                    matches = resolved,
                    isSearching = false,
                    processedPath = sim?.processedPath,
                    segmented = sim?.segmented ?: false,
                    hist = sim?.hist,
                    vec = sim?.vec,
                    pHash = sim?.pHash,
                ))
            }
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

    // ---------- Fuzzy text search ----------

    /** Search wardrobe tags by literal substring match with a small Levenshtein tolerance
     *  for typos. Surfaces results through the shared find-by-photo bottom sheet. */
    fun searchByText(query: String) {
        val q = query.trim()
        if (q.isEmpty()) return
        viewModelScope.launch {
            _state.update { it.copy(
                view = WardrobeView.GRID,
                findByPhoto = FindByPhoto(textQuery = q, matches = emptyList(), isSearching = true),
            ) }
            refreshAllLocationImagesState()
            val pool = _state.value.allLocationImages
            val matches = withContext(Dispatchers.Default) { fuzzyMatchByTags(q, pool) }
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
        return fuzzyMatchByTags(q, items).map { it.image }
    }

    private fun fuzzyMatchByTags(query: String, items: List<DriveImage>): List<FindByPhotoMatch> {
        val tokens = normalizeForSearch(query).split(FUZZY_TOKEN_SPLIT).filter { it.isNotEmpty() }
        if (tokens.isEmpty()) return emptyList()
        val localeCtx = localizedResourceContext()
        return items.mapNotNull { item ->
            val canonical = item.tags?.let { tagsToStrings(it) } ?: return@mapNotNull null
            // Stored tags are canonical English (e.g. "red", "white"); the UI displays them
            // through `tagValueResId` → localized string ("Rot", "Weiß"). Match against both
            // forms so users can search in either language.
            val tagStrings = canonical.flatMap { v ->
                val resId = tagValueResId(v)
                if (resId != null) listOf(v, localeCtx.getString(resId)) else listOf(v)
            }
            if (tagStrings.isEmpty()) return@mapNotNull null
            var totalScore = 0f
            var allMatched = true
            for (tok in tokens) {
                val best = bestTokenScore(tok, tagStrings)
                if (best <= 0f) { allMatched = false; break }
                totalScore += best
            }
            if (!allMatched) null else FindByPhotoMatch(item, totalScore / tokens.size)
        }.sortedByDescending { it.score }
    }

    private fun bestTokenScore(token: String, tagStrings: List<String>): Float {
        var best = 0f
        for (raw in tagStrings) {
            val tag = normalizeForSearch(raw)
            if (tag.isEmpty()) continue
            if (tag.contains(token)) {
                // Whole-word/exact matches outrank substring matches.
                val score = if (tag == token) 1.0f else 0.9f
                if (score > best) best = score
                continue
            }
            val tol = when {
                token.length <= 3 -> 0
                token.length <= 5 -> 1
                else -> 2
            }
            if (tol == 0) continue
            // Compare token against each whitespace-separated word in the tag.
            for (word in tag.split(FUZZY_TOKEN_SPLIT)) {
                if (word.isEmpty()) continue
                if (kotlin.math.abs(word.length - token.length) > tol) continue
                val d = levenshtein(token, word)
                if (d <= tol) {
                    val score = 0.75f - 0.1f * d
                    if (score > best) best = score
                }
            }
        }
        return best
    }

    /** Lowercase + collapse German umlauts/ß into ASCII so "weiss" and "weiß" compare equal. */
    private fun normalizeForSearch(s: String): String = s.lowercase()
        .replace('ä', 'a').replace('ö', 'o').replace('ü', 'u').replace("ß", "ss")

    /** Resource-loading context whose locale follows the active app language, so
     *  `getString` returns the same localized tag values the UI shows. */
    private fun localizedResourceContext(): Context {
        val locale = if (geminiLanguage == "German") java.util.Locale("de") else java.util.Locale.ENGLISH
        val app = getApplication<Application>()
        val cfg = android.content.res.Configuration(app.resources.configuration)
        cfg.setLocale(locale)
        return app.createConfigurationContext(cfg)
    }

    private fun tagsToStrings(t: ClothingTags): List<String> = buildList {
        if (t.label.isNotBlank()) add(t.label)
        if (t.type.isNotBlank()) add(t.type)
        if (t.category.isNotBlank()) add(t.category)
        addAll(t.uses); addAll(t.colors); addAll(t.seasonality)
        addAll(t.aesthetic); addAll(t.fit); addAll(t.material); addAll(t.pattern)
    }.filter { it.isNotBlank() }

    private fun levenshtein(a: String, b: String): Int {
        val costs = IntArray(b.length + 1) { it }
        for (i in 1..a.length) {
            var nw = i - 1
            costs[0] = i
            for (j in 1..b.length) {
                val cj = minOf(
                    1 + minOf(costs[j], costs[j - 1]),
                    if (a[i - 1] == b[j - 1]) nw else nw + 1,
                )
                nw = costs[j]
                costs[j] = cj
            }
        }
        return costs[b.length]
    }

    /** Request that the wardrobe grid scroll to and pulse the given item once it's loaded. */
    fun requestScrollToImage(driveId: String) =
        _state.update { it.copy(pendingScrollDriveId = driveId) }

    /** Consumed by the wardrobe screen after it has scrolled to the pending target. */
    fun consumePendingScroll() = _state.update { it.copy(pendingScrollDriveId = null) }

    // ---------- Upload from camera ----------

    fun uploadPhoto(rawFile: File) {
        val id = (defaultImportFolderId ?: folderId) ?: run {
            _state.update { it.copy(view = WardrobeView.GRID) }
            return
        }
        uploadPhotoInternal(rawFile, id, skippableLocalReview = true)
    }

    /**
     * Shared body for camera + gallery + URL imports. Runs the dedupe gate, then either opens the
     * local-bg review queue (when wired) or proceeds straight to upload.
     *
     * @param skippableLocalReview true for camera/gallery (user can decline local cutout and fall
     *   back to Gemini); false for URL imports, where the review is mandatory.
     * @param forceLocalReview true to always enqueue the review even when [preferLocalBgRemoval]
     *   is off — set by URL imports.
     */
    private fun uploadPhotoInternal(
        rawFile: File,
        id: String,
        skippableLocalReview: Boolean,
        forceLocalReview: Boolean = false,
    ) {
        if (dedupeOnImport && EmbeddingService.isModelAvailable()) {
            viewModelScope.launch {
                _state.update { it.copy(view = WardrobeView.GRID, isUploading = true, error = null) }
                refreshAllLocationImagesState()
                val crossClosetImages = _state.value.allLocationImages
                EmbeddingService.syncIndex(crossClosetImages, drive.cacheDir)
                val sim = EmbeddingService.findSimilarWithDebug(
                    file = rawFile,
                    threshold = dedupeThreshold,
                    topK = if (debugSimilarityPreview) 50 else 8,
                    processedOutputDir = File(getApplication<Application>().cacheDir, QUERY_DEBUG_DIR),
                )
                val byId = crossClosetImages.associateBy { it.driveId }
                val resolved = sim?.matches.orEmpty().mapNotNull { m ->
                    byId[m.driveId]?.let { DuplicateMatch(it, m.score) }
                }
                if (resolved.isEmpty()) {
                    sim?.processedPath?.let { runCatching { File(it).delete() } }
                    routeImportAfterDedupe(rawFile, id, skippableLocalReview, forceLocalReview)
                } else {
                    _state.update { it.copy(
                        isUploading = false,
                        duplicateCheck = DuplicateCheck(
                            rawFilePath = rawFile.absolutePath,
                            targetFolderId = id,
                            matches = resolved,
                            processedPath = sim?.processedPath,
                            segmented = sim?.segmented ?: false,
                            hist = sim?.hist,
                            vec = sim?.vec,
                            pHash = sim?.pHash,
                        ),
                    ) }
                    // Stash routing for confirmDuplicateImport to resume.
                    pendingDedupeRouting = DedupeRouting(skippableLocalReview, forceLocalReview)
                }
            }
            return
        }
        routeImportAfterDedupe(rawFile, id, skippableLocalReview, forceLocalReview)
    }

    private data class DedupeRouting(val skippable: Boolean, val force: Boolean)
    private var pendingDedupeRouting: DedupeRouting? = null

    /** Either enqueue the local-bg review or proceed straight to upload. */
    private fun routeImportAfterDedupe(
        rawFile: File,
        id: String,
        skippable: Boolean,
        force: Boolean,
    ) {
        val shouldReview = (force || preferLocalBgRemoval) &&
            EmbeddingService.segmenter.isAvailable()
        if (shouldReview) {
            _state.update { it.copy(
                isUploading = false,
                localBgReviewQueue = it.localBgReviewQueue + LocalBgReviewItem(
                    rawFilePath = rawFile.absolutePath,
                    targetFolderId = id,
                    skippable = skippable,
                ),
            ) }
        } else {
            proceedWithCameraUpload(rawFile, id)
        }
    }

    /** User confirmed they want to import despite a similarity match. */
    fun confirmDuplicateImport() {
        val dc = _state.value.duplicateCheck ?: return
        dc.processedPath?.let { runCatching { File(it).delete() } }
        _state.update { it.copy(duplicateCheck = null) }
        val routing = pendingDedupeRouting ?: DedupeRouting(skippable = true, force = false)
        pendingDedupeRouting = null
        routeImportAfterDedupe(File(dc.rawFilePath), dc.targetFolderId, routing.skippable, routing.force)
    }

    /** User cancelled the import — discard the raw file and clear the check. */
    fun cancelDuplicateImport() {
        val dc = _state.value.duplicateCheck ?: return
        runCatching { File(dc.rawFilePath).delete() }
        dc.processedPath?.let { runCatching { File(it).delete() } }
        pendingDedupeRouting = null
        _state.update { it.copy(duplicateCheck = null) }
    }

    /**
     * The original [uploadPhoto] body, extracted so the dedupe gate can call it once cleared.
     * When [prebuiltCutout] is non-null, it is treated as a pre-rendered cutout PNG produced by
     * the on-device segmenter; the worker will use it instead of running Gemini.
     */
    private fun proceedWithCameraUpload(rawFile: File, id: String, prebuiltCutout: File? = null) {
        viewModelScope.launch {
            _state.update { it.copy(view = WardrobeView.GRID, isUploading = true, error = null) }
            runCatching {
                val uploaded = drive.uploadImage(id, rawFile)
                val ext = if (rawFile.extension == "png") "png" else "jpg"
                val displayCache = File(drive.cacheDir, "${uploaded.id}.$ext")
                if (rawFile.absolutePath != displayCache.absolutePath) {
                    rawFile.copyTo(displayCache, overwrite = true)
                }
                rawFile.copyTo(File(drive.cacheDir, "${uploaded.id}_original.jpg"), overwrite = true)
                Triple(uploaded.id, uploaded.name, displayCache)
            }.onSuccess { (uploadedId, uploadedName, displayCache) ->
                // Stash the local cutout under a stable per-job filename so processQueuedImage can
                // find it after the raw upload completes — the final destination filename uses the
                // *cutout's* drive ID, which we don't know yet.
                val cutoutForJob = prebuiltCutout?.let { src ->
                    val dest = File(drive.cacheDir, "${uploadedId}_local_cutout.png")
                    runCatching { src.copyTo(dest, overwrite = true) }.getOrNull()
                    runCatching { src.delete() }
                    dest.takeIf { it.exists() }
                }
                val newImage = DriveImage(uploadedId, displayCache.absolutePath, uploadedName, tags = null, folderId = id, createdTimeMs = System.currentTimeMillis())
                _state.update { it.copy(
                    isUploading = false,
                    images = listOf(newImage) + it.images,
                    pendingJobs = it.pendingJobs + 1,
                ) }
                // No sidecar yet — the item is raw; sidecar is written by processQueuedImage
                saveLocalCache(id, _state.value.images)
                workQueue.send(PendingJob(newImage.driveId, id, cutoutForJob?.absolutePath))
            }.onFailure { e ->
                _state.update { it.copy(isUploading = false, error = e.message) }
            }
        }
    }

    // ---------- Local background-removal review ----------

    /**
     * User accepted the on-device cutout for the head item of the review queue. The cutout file
     * has already been written to [cutoutFile] by the review screen; we hand both raw + cutout off
     * to the regular upload pipeline, then advance the queue.
     */
    fun applyLocalBgCutout(cutoutFile: File) {
        val head = _state.value.localBgReviewQueue.firstOrNull() ?: return
        val raw = File(head.rawFilePath)
        _state.update { it.copy(localBgReviewQueue = it.localBgReviewQueue.drop(1)) }
        proceedWithCameraUpload(raw, head.targetFolderId, prebuiltCutout = cutoutFile)
    }

    /** User declined the on-device cutout — fall back to the regular Gemini path. Only valid
     *  for entries with `skippable = true` (camera + gallery; not URL). */
    fun skipLocalBgReview() {
        val head = _state.value.localBgReviewQueue.firstOrNull() ?: return
        if (!head.skippable) return
        val raw = File(head.rawFilePath)
        _state.update { it.copy(localBgReviewQueue = it.localBgReviewQueue.drop(1)) }
        proceedWithCameraUpload(raw, head.targetFolderId)
    }

    /** User cancelled this import entirely — discard the raw file and advance the queue. */
    fun cancelLocalBgReview() {
        val head = _state.value.localBgReviewQueue.firstOrNull() ?: return
        runCatching { File(head.rawFilePath).delete() }
        _state.update { it.copy(localBgReviewQueue = it.localBgReviewQueue.drop(1)) }
    }

    // ---------- Upload from gallery ----------

    /**
     * Fetches the hero product image from a shopping URL and runs it through the standard
     * camera-import pipeline so the resulting wardrobe item gets bg removal + tagging + sidecar
     * exactly like a captured photo. See [WebProductFetcher] for the parser strategy. Surfaces
     * a clear error in [WardrobeUiState.error] when no image can be found.
     */
    fun importFromUrl(url: String) {
        if (url.isBlank()) return
        val targetId = (defaultImportFolderId ?: folderId) ?: return
        viewModelScope.launch {
            _state.update { it.copy(isUploading = true, error = null) }
            val result = WebProductFetcher.fetchImageCandidates(url)
            if (result == null) {
                _state.update {
                    it.copy(
                        isUploading = false,
                        error = getApplication<Application>().getString(R.string.url_import_failed),
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
                        error = getApplication<Application>().getString(R.string.url_import_failed),
                    )
                }
                return@launch
            }
            _state.update { it.copy(urlImportPicker = null) }
            // URL imports always go through on-device review so the user can refine the seed.
            uploadPhotoInternal(
                rawFile = image,
                id = targetId,
                skippableLocalReview = false,
                forceLocalReview = true,
            )
        }
    }

    fun cancelUrlImport() {
        _state.update { it.copy(urlImportPicker = null) }
    }

    fun uploadGalleryPhotos(uris: List<Uri>) {
        if (uris.isEmpty()) return
        val id = (defaultImportFolderId ?: folderId) ?: return
        // When local-bg review is enabled, route each gallery item through the same uploadPhoto
        // pipeline so it is enqueued for the review screen one at a time. Otherwise stick with
        // the original direct-upload path (faster, no per-item user interaction).
        if (preferLocalBgRemoval && EmbeddingService.segmenter.isAvailable()) {
            viewModelScope.launch {
                _state.update { it.copy(batchTotal = uris.size, batchDone = 0, isUploading = true, error = null) }
                val cr = getApplication<Application>().contentResolver
                uris.forEachIndexed { index, uri ->
                    _state.update { it.copy(batchDone = index) }
                    val tempFile = File(drive.cacheDir, "gallery_${System.currentTimeMillis()}_$index.jpg")
                    runCatching {
                        cr.openInputStream(uri)?.use { it.copyTo(tempFile.outputStream()) }
                        tempFile
                    }.onSuccess { f ->
                        // Enqueue for review; uploadPhotoInternal handles dedupe + queue routing.
                        uploadPhotoInternal(f, id, skippableLocalReview = true)
                    }.onFailure { e ->
                        _state.update { it.copy(error = "Upload failed: ${e.message}") }
                        runCatching { tempFile.delete() }
                    }
                }
                _state.update { it.copy(batchTotal = 0, batchDone = 0, isUploading = false) }
            }
            return
        }

        viewModelScope.launch {
            _state.update { it.copy(batchTotal = uris.size, batchDone = 0, isUploading = true, error = null) }
            val cr = getApplication<Application>().contentResolver
            uris.forEachIndexed { index, uri ->
                _state.update { it.copy(batchDone = index) }
                val tempFile = File(drive.cacheDir, "gallery_${System.currentTimeMillis()}.jpg")
                runCatching {
                    cr.openInputStream(uri)?.use { it.copyTo(tempFile.outputStream()) }
                    val uploaded = drive.uploadImage(id, tempFile)
                    val displayCache = File(drive.cacheDir, "${uploaded.id}.jpg")
                    tempFile.copyTo(displayCache, overwrite = true)
                    tempFile.copyTo(File(drive.cacheDir, "${uploaded.id}_original.jpg"), overwrite = true)
                    DriveImage(uploaded.id, displayCache.absolutePath, uploaded.name, tags = null, folderId = id, createdTimeMs = System.currentTimeMillis())
                }.onSuccess { newImage ->
                    _state.update { it.copy(
                        images = listOf(newImage) + it.images,
                        pendingJobs = it.pendingJobs + 1,
                    ) }
                    workQueue.send(PendingJob(newImage.driveId, id))
                }.onFailure { e ->
                    _state.update { it.copy(error = "Upload failed: ${e.message}") }
                }
                tempFile.delete()
            }
            _state.update { it.copy(batchTotal = 0, batchDone = 0, isUploading = false) }
            // Sidecars are written per item by processQueuedImage; just save local cache
            saveLocalCache(id, _state.value.images)
        }
    }

    fun reprocessBackground(driveId: String) {
        viewModelScope.launch {
            _state.update { it.copy(isProcessing = true, processingImageId = driveId, error = null) }
            val source = resolveOriginalFile(driveId)
                ?: run {
                    _state.update { it.copy(isProcessing = false, error = "Original not available") }
                    return@launch
                }
            val processedFile = gemini.removeBackground(source, drive.cacheDir)
                ?: run { _state.update { it.copy(isProcessing = false, processingImageId = null) }; return@launch }

            _state.update { it.copy(isProcessing = false, isUploading = true) }
            runCatching {
                drive.updateImage(driveId, processedFile)
                val displayCache = File(drive.cacheDir, "$driveId.png")
                processedFile.copyTo(displayCache, overwrite = true)
                displayCache.absolutePath
            }.onSuccess { newPath ->
                _state.update { s ->
                    s.copy(
                        isUploading = false,
                        processingImageId = null,
                        images = s.images.map {
                            if (it.driveId == driveId) it.copy(localPath = newPath, version = System.currentTimeMillis())
                            else it
                        },
                    )
                }
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
                        _state.update { s ->
                            s.copy(images = s.images.map {
                                if (it.driveId == image.driveId) it.copy(
                                    localPath = displayCache.absolutePath,
                                    version = System.currentTimeMillis(),
                                    originalDriveId = originalDriveId ?: it.originalDriveId,
                                ) else it
                            })
                        }
                    }
                }
                _state.update { it.copy(isRemovingAllBg = false, removeBgDone = 0, removeBgTotal = 0) }
                images.forEach { img -> saveSidecar(img.driveId) }
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
            val tags = gemini.classifyClothing(cachedFile, geminiLanguage)
                ?: run { _state.update { it.copy(processingImageId = null) }; return@launch }
            _state.update { s ->
                s.copy(
                    processingImageId = null,
                    images = s.images.map { if (it.driveId == driveId) it.copy(tags = tags) else it },
                )
            }
            saveSidecar(driveId)
        }
    }

    fun updateTags(driveId: String, tags: ClothingTags) {
        _state.update { s ->
            s.copy(images = s.images.map { if (it.driveId == driveId) it.copy(tags = tags) else it })
        }
        saveSidecar(driveId)
    }

    fun retagAll() {
        val images = _state.value.images
        if (images.isEmpty() || _state.value.isRetagging) return
        viewModelScope.launch {
            acquireJobWakeLock()
            try {
            _state.update { it.copy(isRetagging = true, retagDone = 0, retagTotal = images.size) }
            images.forEachIndexed { index, image ->
                _state.update { it.copy(retagDone = index) }
                val cachedFile = drive.cachedFile(image.driveId) ?: return@forEachIndexed
                val tags = gemini.classifyClothing(cachedFile, geminiLanguage) ?: return@forEachIndexed
                _state.update { s ->
                    s.copy(images = s.images.map { if (it.driveId == image.driveId) it.copy(tags = tags) else it })
                }
            }
            _state.update { it.copy(isRetagging = false, retagDone = 0, retagTotal = 0) }
            images.forEach { img -> saveSidecar(img.driveId) }
            } finally {
                releaseJobWakeLock()
            }
        }
    }

    // ---------- Move to another location ----------

    fun moveItemsToLocation(driveIds: Set<String>, targetFolderId: String) {
        val toMove = _state.value.images.filter { it.driveId in driveIds }
        if (toMove.isEmpty()) return
        viewModelScope.launch {
            _state.update { it.copy(isMoving = true, selectedIds = emptySet(), error = null) }

            // Move all items in parallel; within each item move cutout + original + sidecar in parallel
            val successfulIds = coroutineScope {
                toMove.map { item ->
                    async {
                        val sourceFolderId = item.folderId.ifEmpty { folderId } ?: return@async null
                        if (sourceFolderId == targetFolderId) return@async item.driveId
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
                            _state.update { it.copy(error = e.message) }
                        }.getOrNull()?.let { item.driveId }
                    }
                }.awaitAll().filterNotNull().toSet()
            }

            if (successfulIds.isNotEmpty()) {
                val movedItems = toMove.filter { it.driveId in successfulIds }
                val affectedFolderIds = movedItems.map { it.folderId }.filter { it.isNotEmpty() }.toSet()
                _state.update { s -> s.copy(images = s.images.filter { it.driveId !in successfulIds }) }
                affectedFolderIds.forEach { fid ->
                    saveLocalCache(fid, _state.value.images.filter { it.folderId == fid })
                }
                notifyItemsMovedTo(targetFolderId, movedItems.map { it.copy(folderId = targetFolderId) })
            }
            _state.update { it.copy(isMoving = false) }
        }
    }

    /**
     * Records that [items] have been moved into [targetFolderId] (their cutout/original/sidecar
     * Drive IDs are unchanged, so the local cache files are still valid). Merges them into the
     * target folder's `wardrobe_cache_*.json` so [setLocation] to that folder shows them in
     * Phase 1 instantly, and remembers them so Phase 2 won't drop them while Drive's listing
     * is still propagating. Callable from other view models (e.g. shopping move-to-closet).
     */
    fun notifyItemsMovedTo(targetFolderId: String, items: List<DriveImage>) {
        if (items.isEmpty() || targetFolderId.isEmpty()) return
        val expiry = System.currentTimeMillis() + recentlyMovedTtlMs
        items.forEach { recentlyMovedItems[it.driveId] = targetFolderId to expiry }
        runCatching {
            val cacheFile = localCacheFile(targetFolderId)
            val existing: List<LocalCacheEntry> = if (cacheFile.exists()) {
                runCatching { gson.fromJson(cacheFile.readText(), LocalCache::class.java).items }
                    .getOrDefault(emptyList())
            } else emptyList()
            val existingIds = existing.map { it.driveId }.toSet()
            val additions = items.filter { it.driveId !in existingIds }.map { img ->
                LocalCacheEntry(img.driveId, img.name, img.tags, img.originalDriveId, img.sidecarDriveId, img.createdTimeMs)
            }
            if (additions.isNotEmpty()) {
                cacheFile.writeText(gson.toJson(LocalCache(existing + additions)))
            }
        }
        // If the target closet is the one currently shown, splice the items in immediately.
        if (folderId == targetFolderId || allFolderIds?.contains(targetFolderId) == true) {
            val currentIds = _state.value.images.map { it.driveId }.toSet()
            val toAdd = items.filter { it.driveId !in currentIds }
            if (toAdd.isNotEmpty()) {
                _state.update { s -> s.copy(images = s.images + toAdd) }
            }
        }
        refreshAllLocationImagesState()
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
    fun importFromFolder(
        treeUri: Uri,
        removeBackground: Boolean = false,
        autoTag: Boolean = false,
        replaceExisting: Boolean = false,
        overwriteDuplicates: Boolean = false,
    ) {
        val id = (defaultImportFolderId ?: folderId) ?: return
        val options = ImportOptions(removeBackground, autoTag, replaceExisting, overwriteDuplicates)
        viewModelScope.launch {
            acquireJobWakeLock()
            try {
                val entries = enumerateSafSources(treeUri) ?: return@launch
                if (entries.isEmpty()) return@launch
                kickoffImport(id, options, entries)
            } finally {
                // For the preview path the lock is released in confirm/cancel; for the fast path
                // runImportEntries() runs synchronously here so we release after it returns.
                if (_state.value.importPreview == null) releaseJobWakeLock()
            }
        }
    }

    // ---------- Drive folder browser helpers (called from composable via LaunchedEffect) ----------

    suspend fun listDriveSubfolders(folderId: String): List<DriveFileDto> =
        drive.listSubfolders(folderId)

    suspend fun countDriveImages(folderId: String): Int =
        drive.countImages(folderId)

    // ---------- Drive Import ----------

    /**
     * Imports all images from a Google Drive folder ([sourceFolderId]) into the current wardrobe.
     * Mirror of [importFromFolder] but uses the Drive REST API instead of SAF.
     */
    fun importFromDriveFolder(
        sourceFolderId: String,
        removeBackground: Boolean = false,
        autoTag: Boolean = false,
        replaceExisting: Boolean = false,
        overwriteDuplicates: Boolean = false,
    ) {
        val id = (defaultImportFolderId ?: folderId) ?: return
        val options = ImportOptions(removeBackground, autoTag, replaceExisting, overwriteDuplicates)
        viewModelScope.launch {
            acquireJobWakeLock()
            try {
                val entries = enumerateDriveSources(sourceFolderId)
                if (entries.isEmpty()) return@launch
                kickoffImport(id, options, entries)
            } finally {
                if (_state.value.importPreview == null) releaseJobWakeLock()
            }
        }
    }

    // ---------- Shared import implementation (SAF + Drive sources go through these) ----------

    /**
     * Phase A.1 — enumerate every image in [treeUri], copy each into a stable per-entry cache
     * file, and return the resulting [ImportPreviewEntry] list (ready for similarity scanning or
     * direct upload). Returns null on enumeration failure.
     */
    private suspend fun enumerateSafSources(treeUri: Uri): List<ImportPreviewEntry>? {
        val cr = getApplication<Application>().contentResolver
        val treeDocId = DocumentsContract.getTreeDocumentId(treeUri)
        val childrenUri = DocumentsContract.buildChildDocumentsUriUsingTree(treeUri, treeDocId)
        data class Src(val docId: String, val displayName: String, val mimeType: String)
        val srcFiles = mutableListOf<Src>()
        var metaDocId: String? = null
        cr.query(
            childrenUri,
            arrayOf(
                DocumentsContract.Document.COLUMN_DOCUMENT_ID,
                DocumentsContract.Document.COLUMN_DISPLAY_NAME,
                DocumentsContract.Document.COLUMN_MIME_TYPE,
            ),
            null, null, null,
        )?.use { cursor ->
            while (cursor.moveToNext()) {
                val docId = cursor.getString(0)
                val name  = cursor.getString(1) ?: continue
                val mime  = cursor.getString(2) ?: ""
                when {
                    name == "_wardrobe_metadata.json" -> metaDocId = docId
                    mime.startsWith("image/")         -> srcFiles.add(Src(docId, name, mime))
                }
            }
        }
        val srcMetaByName: Map<String, WardrobeItemMeta> = metaDocId?.let { docId ->
            runCatching {
                val metaUri = DocumentsContract.buildDocumentUriUsingTree(treeUri, docId)
                val json = cr.openInputStream(metaUri)?.use { it.readBytes().toString(Charsets.UTF_8) }
                if (json != null) gson.fromJson(json, WardrobeMetadata::class.java).items.associateBy { it.name }
                else emptyMap()
            }.getOrDefault(emptyMap())
        } ?: emptyMap()
        val ts = System.currentTimeMillis()
        return srcFiles.mapIndexedNotNull { idx, src ->
            val ext = if (src.mimeType == "image/png") "png" else "jpg"
            val cached = File(drive.cacheDir, "import_${ts}_$idx.$ext")
            val ok = runCatching {
                val srcUri = DocumentsContract.buildDocumentUriUsingTree(treeUri, src.docId)
                cr.openInputStream(srcUri)?.use { it.copyTo(cached.outputStream()) } != null
            }.getOrDefault(false)
            if (!ok) return@mapIndexedNotNull null
            val meta = srcMetaByName[src.displayName]
            ImportPreviewEntry(
                key = "saf_$idx",
                displayName = src.displayName,
                cachedFilePath = cached.absolutePath,
                srcMetaTags = meta?.tags,
                srcMetaOriginalDriveId = meta?.originalDriveId,
            )
        }
    }

    /** Phase A.1 for a Drive source — list files, download each into a per-entry cache file. */
    private suspend fun enumerateDriveSources(sourceFolderId: String): List<ImportPreviewEntry> {
        val srcFiles = runCatching { drive.listFiles(sourceFolderId) }.getOrDefault(emptyList())
        if (srcFiles.isEmpty()) return emptyList()
        val ts = System.currentTimeMillis()
        return srcFiles.mapIndexedNotNull { idx, src ->
            val ext = if (src.name.endsWith(".png", ignoreCase = true)) "png" else "jpg"
            val cached = File(drive.cacheDir, "import_drive_${ts}_$idx.$ext")
            if (drive.downloadFileTo(src.id, cached) == null) return@mapIndexedNotNull null
            ImportPreviewEntry(
                key = "drive_$idx",
                displayName = src.name,
                cachedFilePath = cached.absolutePath,
            )
        }
    }

    /**
     * Phase A.2 — branch on whether the user has dedupe-on-import enabled. With it on, embed each
     * candidate, find above-threshold matches, and pause with [ImportPreview] state for the UI to
     * render a review grid. Without it, run the import directly on every entry.
     */
    private suspend fun kickoffImport(
        targetFolderId: String,
        options: ImportOptions,
        entries: List<ImportPreviewEntry>,
    ) {
        if (dedupeOnImport && EmbeddingService.isModelAvailable()) {
            // Show empty preview with a scanning indicator
            _state.update { it.copy(importPreview = ImportPreview(
                entries = emptyList(),
                selectedKeys = emptySet(),
                isScanning = true,
                scanDone = 0,
                scanTotal = entries.size,
                targetFolderId = targetFolderId,
                options = options,
            )) }
            refreshAllLocationImagesState()
            val crossClosetImages = _state.value.allLocationImages
            EmbeddingService.syncIndex(crossClosetImages, drive.cacheDir)
            val byId = crossClosetImages.associateBy { it.driveId }
            val scanned = mutableListOf<ImportPreviewEntry>()
            entries.forEachIndexed { idx, entry ->
                val matches = EmbeddingService.findSimilar(
                    file = File(entry.cachedFilePath),
                    threshold = dedupeThreshold,
                    topK = if (debugSimilarityPreview) 50 else 4,
                    segment = true,
                )
                val resolved = matches.mapNotNull { m ->
                    byId[m.driveId]?.let { DuplicateMatch(it, m.score) }
                }
                scanned.add(entry.copy(similar = resolved))
                _state.update { it.copy(importPreview = it.importPreview?.copy(
                    entries = scanned.toList(),
                    scanDone = idx + 1,
                    isScanning = idx < entries.lastIndex,
                )) }
            }
            // Default selection: only candidates without a similarity hit. The user can opt back in.
            val defaultSel = scanned.filter { it.similar.isEmpty() }.map { it.key }.toSet()
            _state.update { it.copy(importPreview = it.importPreview?.copy(
                entries = scanned.toList(),
                selectedKeys = defaultSel,
                isScanning = false,
            )) }
            return
        }
        // Fast path — import every entry without a preview.
        runImportEntries(targetFolderId, options, entries)
    }

    /** Toggle a single entry's selection inside the import preview. */
    fun toggleImportPreviewSelection(key: String) {
        _state.update { s ->
            val p = s.importPreview ?: return@update s
            val next = p.selectedKeys.toMutableSet()
            if (!next.add(key)) next.remove(key)
            s.copy(importPreview = p.copy(selectedKeys = next))
        }
    }

    /** Replace the import-preview selection (used by Select all / Deselect all). */
    fun setImportPreviewSelection(keys: Set<String>) {
        _state.update { s ->
            val p = s.importPreview ?: return@update s
            s.copy(importPreview = p.copy(selectedKeys = keys))
        }
    }

    /** Run the import on every selected entry; discard cache files for the deselected ones. */
    fun confirmImportPreview() {
        val preview = _state.value.importPreview ?: return
        _state.update { it.copy(importPreview = null) }
        val (selected, dropped) = preview.entries.partition { it.key in preview.selectedKeys }
        viewModelScope.launch {
            try {
                if (selected.isNotEmpty()) {
                    runImportEntries(preview.targetFolderId, preview.options, selected)
                }
                dropped.forEach { runCatching { File(it.cachedFilePath).delete() } }
            } finally {
                releaseJobWakeLock()
            }
        }
    }

    /** Cancel the import without uploading anything; discard every cached candidate file. */
    fun cancelImportPreview() {
        val preview = _state.value.importPreview ?: return
        _state.update { it.copy(importPreview = null) }
        preview.entries.forEach { runCatching { File(it.cachedFilePath).delete() } }
        releaseJobWakeLock()
    }

    /**
     * Phase B — the actual upload + AI loop. Operates on entries that already have a
     * pre-cached file on disk (regardless of source). Mirrors the original per-entry logic from
     * `importFromFolder` so behavior is unchanged when the preview gate is disabled.
     */
    private suspend fun runImportEntries(
        id: String,
        options: ImportOptions,
        entries: List<ImportPreviewEntry>,
    ) {
        if (options.replaceExisting) {
            val toDelete = _state.value.images.map { it.driveId }
            _state.update { it.copy(isImporting = true, importDone = 0, importTotal = entries.size, images = emptyList(), error = null) }
            toDelete.forEach { driveId -> runCatching { drive.deleteFile(driveId) } }
        } else {
            _state.update { it.copy(isImporting = true, importDone = 0, importTotal = entries.size, error = null) }
        }
        val existingByName: Map<String, DriveImage> = _state.value.images.associateBy { it.name }
        entries.forEachIndexed { index, entry ->
            _state.update { it.copy(importDone = index) }
            val tempFile = File(entry.cachedFilePath)
            val duplicate = existingByName[entry.displayName]
            if (duplicate != null && !options.overwriteDuplicates) {
                runCatching { tempFile.delete() }
                return@forEachIndexed
            }
            val placeholderId: String? = if (duplicate == null)
                "__importing_${index}_${System.nanoTime()}" else null
            if (placeholderId != null) {
                _state.update { s ->
                    s.copy(
                        images = s.images + DriveImage(
                            driveId = placeholderId,
                            localPath = entry.cachedFilePath,
                            name = entry.displayName,
                            folderId = id,
                            createdTimeMs = System.currentTimeMillis(),
                        ),
                        processingImageId = placeholderId,
                    )
                }
            } else {
                _state.update { it.copy(processingImageId = duplicate!!.driveId) }
            }
            runCatching {
                if (!tempFile.exists()) error("Cached file missing for ${entry.displayName}")
                var imageToUpload = tempFile
                var rawOriginalFile: File? = null
                var originalDriveId: String? = entry.srcMetaOriginalDriveId ?: duplicate?.originalDriveId
                if (options.removeBackground && entry.srcMetaTags == null) {
                    val processed = gemini.removeBackground(tempFile, drive.cacheDir)
                    if (processed != null) {
                        rawOriginalFile = tempFile
                        imageToUpload = processed
                    }
                }
                val tags = when {
                    entry.srcMetaTags != null -> entry.srcMetaTags
                    duplicate != null         -> duplicate.tags
                    options.autoTag           -> gemini.classifyClothing(imageToUpload, geminiLanguage)
                    else                      -> null
                }
                if (duplicate != null) {
                    drive.updateImage(duplicate.driveId, imageToUpload)
                    val displayCache = File(drive.cacheDir, "${duplicate.driveId}.png")
                    imageToUpload.copyTo(displayCache, overwrite = true)
                    val finalOriginalId = rawOriginalFile?.let { orig ->
                        val oid = runCatching { uploadAsOriginal(id, orig, duplicate.driveId) }.getOrNull()
                        orig.copyTo(File(drive.cacheDir, "${duplicate.driveId}_original.jpg"), overwrite = true)
                        oid
                    } ?: originalDriveId ?: duplicate.originalDriveId
                    _state.update { s ->
                        s.copy(images = s.images.map { img ->
                            if (img.driveId == duplicate.driveId) img.copy(
                                localPath = displayCache.absolutePath,
                                tags = tags,
                                version = System.currentTimeMillis(),
                                originalDriveId = finalOriginalId,
                            ) else img
                        })
                    }
                } else {
                    val cutoutUploaded = uploadAsCutout(id, imageToUpload)
                    val displayCache = File(drive.cacheDir, "${cutoutUploaded.id}.png")
                    imageToUpload.copyTo(displayCache, overwrite = true)
                    val finalOriginalId = rawOriginalFile?.let { orig ->
                        val oid = runCatching { uploadAsOriginal(id, orig, cutoutUploaded.id) }.getOrNull()
                        orig.copyTo(File(drive.cacheDir, "${cutoutUploaded.id}_original.jpg"), overwrite = true)
                        oid
                    } ?: originalDriveId
                    val newImage = DriveImage(
                        driveId = cutoutUploaded.id,
                        localPath = displayCache.absolutePath,
                        name = cutoutUploaded.name,
                        tags = tags,
                        originalDriveId = finalOriginalId,
                        folderId = id,
                        createdTimeMs = System.currentTimeMillis(),
                    )
                    _state.update { s ->
                        s.copy(images = if (placeholderId != null)
                            s.images.map { if (it.driveId == placeholderId) newImage else it }
                        else s.images + newImage)
                    }
                }
            }.onFailure { e ->
                _state.update { s ->
                    s.copy(
                        error = "Import failed for ${entry.displayName}: ${e.message}",
                        images = if (placeholderId != null)
                            s.images.filterNot { it.driveId == placeholderId }
                        else s.images,
                    )
                }
            }
            _state.update { it.copy(processingImageId = null) }
            runCatching { tempFile.delete() }
        }
        _state.update { it.copy(isImporting = false, importDone = 0, importTotal = 0) }
        _state.value.images.forEach { img -> if (img.sidecarDriveId == null) saveSidecar(img.driveId) }
    }

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
                // Bump version so Coil reloads from the already-rotated local cache.
                _state.update { s ->
                    s.copy(images = s.images.map {
                        if (it.driveId == driveId) it.copy(version = it.version + 1) else it
                    })
                }
                // Upload rotated files to Drive silently (no processingImageId = no overlay).
                withContext(Dispatchers.IO) {
                    drive.cachedFile(img.driveId)?.let { drive.updateImage(img.driveId, it) }
                    img.originalDriveId?.let { origId ->
                        drive.cachedFile(origId)?.let { drive.updateImage(origId, it) }
                    }
                }
                val id = folderId
                if (id != null) saveLocalCache(id, _state.value.images)
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
        val toDelete = driveIds
        if (toDelete.isEmpty()) return
        val items = _state.value.images.filter { it.driveId in toDelete }
        viewModelScope.launch {
            val id = folderId
            _state.update { it.copy(isUploading = true, selectedIds = emptySet()) }
            items.forEach { img ->
                runCatching { drive.deleteFile(img.driveId) }
                img.originalDriveId?.let { origId -> runCatching { drive.deleteFile(origId) } }
                img.sidecarDriveId?.let { sId -> runCatching { drive.deleteFile(sId) } }
            }
            _state.update { s ->
                s.copy(
                    isUploading = false,
                    images = s.images.filter { it.driveId !in toDelete }
                )
            }
            if (id != null) saveLocalCache(id, _state.value.images)
        }
    }
}

// ---------- Bitmap rotation helper ----------

internal fun rotateBitmapFileBy90(file: File) {
    val bmp = BitmapFactory.decodeFile(file.absolutePath) ?: return
    val matrix = Matrix().apply { postRotate(90f) }
    val rotated = Bitmap.createBitmap(bmp, 0, 0, bmp.width, bmp.height, matrix, true)
    val format = if (file.extension == "png") Bitmap.CompressFormat.PNG else Bitmap.CompressFormat.JPEG
    file.outputStream().use { rotated.compress(format, 95, it) }
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
