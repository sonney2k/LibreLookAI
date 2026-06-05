package com.librelookai.wardrobe

import com.librelookai.gemini.ClothingTags
import com.librelookai.settings.UserPreferences
import com.librelookai.util.Analytics

enum class WardrobeView { GRID, CAPTURE, FIND_BY_PHOTO_CAPTURE }

/** Where a wardrobe-add originated. Threaded through the import pipeline so the funnel's terminal
 *  events ([logWardrobeAdd]) can report which source converts. See docs/FLOW_ANALYTICS.md (Flow 1). */
enum class AddSource(val tag: String) { CAMERA("camera"), GALLERY("gallery"), URL("url") }

/** Funnel event for the wardrobe-add flow (`wardrobe_add` with a `stage` discriminator). Stages:
 *  `upload_start`, `dedupe_prompt`/`dedupe_confirm`/`dedupe_cancel`, `item_live`, `failed`. */
internal fun logWardrobeAdd(
    stage: String,
    source: AddSource? = null,
    extra: Map<String, String> = emptyMap(),
) {
    Analytics.event(
        "wardrobe_add",
        buildMap {
            put("stage", stage)
            source?.let { put("source", it.tag) }
            putAll(extra)
        },
    )
}

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

/**
 * True when the item has no AI classification yet — tagging failed (e.g. an AI/credits outage at
 * import time) or never ran. Such an item has no `tags.category`, so [com.librelookai.outfit.layerFor]
 * can't assign it to any outfit slot and it silently can't be used in outfits. The grid surfaces
 * these with a "needs tagging" badge so the user knows to re-tag them.
 */
internal val DriveImage.needsTagging: Boolean
    get() = tags?.category?.isNotBlank() != true

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
    /** Origin of this import, carried through the review queue for funnel attribution. */
    val source: AddSource = AddSource.CAMERA,
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

internal data class WardrobeItemMeta(val name: String, val tags: ClothingTags?, val originalDriveId: String? = null)
internal data class WardrobeMetadata(val items: List<WardrobeItemMeta> = emptyList())

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

internal data class PendingJob(
    val driveId: String,
    val folderId: String,
    /** When non-null, points to a locally-produced cutout PNG that [processQueuedImage] should
     *  use directly instead of calling Gemini. The file lives in `drive.cacheDir` and is owned by
     *  the worker once enqueued (it gets copied into `${cutoutId}.png` and then deleted). */
    val prebuiltCutoutPath: String? = null,
    /** Origin of this import, carried so the queue's terminal funnel events can attribute source. */
    val source: AddSource = AddSource.CAMERA,
)

// Internal audit helpers
internal data class AuditItem(val folderId: String, val driveId: String, val name: String)
internal data class AuditCutoutItem(val folderId: String, val cutoutDriveId: String, val cutoutName: String)
internal data class AuditDuplicateItem(
    val folderId: String,
    val cutoutDriveId: String,
    val cutoutName: String,
    val similarTo: List<String>,
    val topScore: Float,
)
internal data class AuditIntermediate(
    val folderIds: List<String>,
    val orphanedOriginals: List<AuditItem>,
    val rawImages: List<AuditItem>,
    val cutoutsNeedingSidecar: List<AuditCutoutItem>,
    val duplicates: List<AuditDuplicateItem> = emptyList(),
)

