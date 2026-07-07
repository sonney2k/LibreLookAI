package com.librelookai.wardrobe

import com.librelookai.gemini.ClothingTags
import com.librelookai.settings.UserPreferences
import com.librelookai.util.Analytics

enum class WardrobeView { GRID, CAPTURE, FIND_BY_PHOTO_CAPTURE }

/**
 * Which step of the Phase-2 Drive sync is currently running, so progress UI can label the work
 * instead of stalling at "196/196" while sidecars download and caches are written.
 * - [DOWNLOADING] — fetching the (uncached) image files; counts via syncDone/syncTotal.
 * - [DETAILS] — loading each item's tag/metadata sidecar; counts via syncDone/syncTotal.
 * - [FINISHING] — building the item list and writing local caches (no per-item count).
 */
enum class WardrobeSyncPhase { NONE, DOWNLOADING, DETAILS, FINISHING }

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

// DriveImage moved to :core:model (core/model/…/wardrobe/DriveImage.kt), same package.

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
    /**
     * The items in the current view scope — **derived** from the Room-backed
     * [WardrobeItemStore] (refactor § 5 slice 4a): a store-observing collector in the VM's
     * `init` is the only writer. Mutations write the store; the view follows via invalidation.
     */
    val images: List<DriveImage> = emptyList(),
    /**
     * Cross-closet snapshot of every cached wardrobe item across all configured closets
     * (incl. shopping), regardless of the active location filter applied to [images] —
     * **derived** from the same store over the configured-closets scope, so it is always
     * current. Used by every similarity-search call site so matches span all wardrobes.
     */
    val allLocationImages: List<DriveImage> = emptyList(),
    val isLoading: Boolean = false,
    val isSyncing: Boolean = false,
    // The pipeline / bulk-op progress clusters that used to sit here (batch counts, retag /
    // remove-bg / convert progress, pendingJobs, duplicateCheck, localBgReviewQueue,
    // cutoutBgFix) were pruned in refactor § 5 slice 7/9: surfaces read the owning
    // singleton's StateFlow through the VM's passthrough properties (`ingestionProgress`,
    // `retagProgress`, `convertProgress`, `cutoutBgFixProgress`). Only the fields shared
    // with the VM's own per-item ops stay (isProcessing / isUploading / processingImageId).
    val isProcessing: Boolean = false,
    val isUploading: Boolean = false,
    val isMoving: Boolean = false,
    val error: String? = null,
    /** Total items in the current Phase 2 sync sub-step (0 = not syncing or unknown). */
    val syncTotal: Int = 0,
    /** Items processed so far in the current Phase 2 sync sub-step. */
    val syncDone: Int = 0,
    /** Which sub-step of the Phase 2 Drive sync is running (for progress labelling). */
    val syncPhase: WardrobeSyncPhase = WardrobeSyncPhase.NONE,
    /** driveId of the image currently being processed by an AI operation, or null. */
    val processingImageId: String? = null,
    val selectedIds: Set<String> = emptySet(),
    /** True when a job is starting and the app is not exempt from battery optimization. */
    val needsBatteryExemption: Boolean = false,
    /** Drive folder ID that new photos will be uploaded to. */
    val importTargetFolderId: String? = null,
    /** Non-null while the user is reviewing matches from a "find item by photo" capture. */
    val findByPhoto: FindByPhoto? = null,
    /** Non-null while the user is choosing an image from a pasted shopping URL. */
    val urlImportPicker: UrlImportPickerState? = null,
)

/**
 * One-shot events the wardrobe VM fires at the grid (refactor § 5 slice 7). Delivered over a
 * buffered `Channel` (consumed exactly once, survives a cross-tab `goToTab` because the buffer
 * holds the event until the grid's collector subscribes) — replaces the old `pendingScrollDriveId`
 * state field + explicit `consumePendingScroll`, including its preservation across location loads.
 */
sealed interface WardrobeEvent {
    /** Scroll the grid to [driveId] and pulse a highlight ring once it is in the displayed list. */
    data class ScrollToItem(val driveId: String) : WardrobeEvent
}

// UrlImportPickerState moved to :core:model (core/model/…/wardrobe/), same package.

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

// ItemSidecar moved to :core:model, DriveImage.toCachedItem to :core:database (same package,
// § 1 slice 2 — the § 2 handlers and both ingestion workers consume them from below).

// ---------- Legacy bulk metadata (read-only, migration fallback) ----------

internal data class WardrobeItemMeta(val name: String, val tags: ClothingTags?, val originalDriveId: String? = null)
internal data class WardrobeMetadata(val items: List<WardrobeItemMeta> = emptyList())

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

