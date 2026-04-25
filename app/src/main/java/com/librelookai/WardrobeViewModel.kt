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

enum class WardrobeView { GRID, CAPTURE }

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
)

data class WardrobeUiState(
    val view: WardrobeView = WardrobeView.GRID,
    val images: List<DriveImage> = emptyList(),
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
    /** True when a job is starting and the app is not exempt from battery optimization. */
    val needsBatteryExemption: Boolean = false,
    /** Drive folder ID that new photos will be uploaded to. */
    val importTargetFolderId: String? = null,
    /** Non-null while a captured photo is paused for duplicate confirmation. */
    val duplicateCheck: DuplicateCheck? = null,
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
enum class AuditKind { ORPHANED_ORIGINAL, RAW, NEEDS_SIDECAR }

/**
 * One entry in the Repair & Sync preview grid. For [AuditKind.NEEDS_SIDECAR] the [driveId] points
 * at the existing cutout; otherwise it's the orphaned original / raw image awaiting processing.
 */
data class AuditFileEntry(
    val driveId: String,
    val name: String,
    val folderId: String,
    val kind: AuditKind,
)

// ---------- Per-item sidecar metadata ----------

private data class ItemSidecar(val tags: ClothingTags? = null, val originalDriveId: String? = null)

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
)
data class LocalCache(val items: List<LocalCacheEntry> = emptyList())

private data class PendingJob(val driveId: String, val folderId: String)

// Internal audit helpers
private data class AuditItem(val folderId: String, val driveId: String, val name: String)
private data class AuditCutoutItem(val folderId: String, val cutoutDriveId: String, val cutoutName: String)
private data class AuditIntermediate(
    val folderIds: List<String>,
    val orphanedOriginals: List<AuditItem>,
    val rawImages: List<AuditItem>,
    val cutoutsNeedingSidecar: List<AuditCutoutItem>,
)

class WardrobeViewModel(app: Application) : AndroidViewModel(app) {

    companion object {
        private const val TAG = "RepairAndSync"
        /** Sidecars at or below this size are {} or {"tags":null} — definitely no tags. */
        private const val SIDECAR_EMPTY_MAX = 20L
        /** Sidecars at or above this size always contain a ClothingTags object. */
        private const val SIDECAR_FULL_MIN  = 100L
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
    /** Gemini-facing language name (e.g. "English", "German") for label generation. */
    private var geminiLanguage: String = "English"
    /** When non-null, all new photo imports target this folder instead of the active view folder. */
    private var defaultImportFolderId: String? = null
    /** Mirrors UserPreferences.dedupeOnImport — flips capture/import similarity gate on/off. */
    private var dedupeOnImport: Boolean = false
    /** Mirrors UserPreferences.dedupeThreshold — cosine cutoff for "this is probably already in your wardrobe". */
    private var dedupeThreshold: Float = 0.85f

    /** Serializes all Drive metadata writes to prevent concurrent saves overwriting each other. */
    private val metaMutex = Mutex()

    /** Holds the audit scan results while waiting for the user to confirm processing. */
    private var pendingAudit: AuditIntermediate? = null

    /** Active loadImages coroutine — cancelled when a new location is selected. */
    private var loadJob: Job? = null

    /**
     * Background processing queue. Each item represents one uploaded photo that needs
     * bg removal + classification. Processed serially so metadata writes are consistent.
     */
    private val workQueue = Channel<PendingJob>(Channel.UNLIMITED)

    init {
        viewModelScope.launch { processQueue() }
    }

    fun setLanguage(geminiName: String) { geminiLanguage = geminiName }

    /** Push UserPreferences-derived similarity settings into the VM. Called from MainActivity. */
    fun setDedupeSettings(enabled: Boolean, threshold: Float) {
        dedupeOnImport = enabled
        dedupeThreshold = threshold
    }

    /** Set the folder that new photo imports target. Null = fall back to the active view folder. */
    fun setDefaultImportFolderId(folderId: String?) {
        defaultImportFolderId = folderId
        _state.update { it.copy(importTargetFolderId = folderId) }
    }

    fun setLocation(newFolderId: String) {
        if (folderId == newFolderId && allFolderIds == null) return
        folderId = newFolderId
        allFolderIds = null
        _state.update { WardrobeUiState(isLoading = true) }
        loadImages()
    }

    fun setAllLocations(folderIds: List<String>) {
        if (folderId == null && allFolderIds?.toSet() == folderIds.toSet()) return
        folderId = null
        allFolderIds = folderIds.toList()
        _state.update { WardrobeUiState(isLoading = true) }
        loadImages()
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
                pendingAudit = AuditIntermediate(folderIds, orphaned, rawImages, needSidecar)
                val previewItems = buildList<AuditFileEntry> {
                    orphaned.forEach { add(AuditFileEntry(it.driveId, it.name, it.folderId, AuditKind.ORPHANED_ORIGINAL)) }
                    rawImages.forEach { add(AuditFileEntry(it.driveId, it.name, it.folderId, AuditKind.RAW)) }
                    needSidecar.forEach { add(AuditFileEntry(it.cutoutDriveId, it.cutoutName, it.folderId, AuditKind.NEEDS_SIDECAR)) }
                }
                _state.update { it.copy(
                    auditProgress = AuditProgress(
                        awaitingConfirmation = true,
                        renamedCount = totalRenamed,
                        orphanedOriginals = orphaned.size,
                        rawImages = rawImages.size,
                        sidecarNeeded = needSidecar.size,
                        items = previewItems,
                        selectedAuditIds = previewItems.map { e -> e.driveId }.toSet(),
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
     * tagging-only for cutouts missing sidecars. Either way, clears all local caches
     * and reloads from Drive when finished.
     */
    fun continueRepairProcessing(process: Boolean) {
        val audit = pendingAudit ?: run {
            _state.update { it.copy(auditProgress = null) }
            clearCacheAndRefresh()
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
        val anySelected = filteredOrphaned.isNotEmpty() || filteredRaw.isNotEmpty() || filteredSidecar.isNotEmpty()

        if (!process || !anySelected) {
            Log.d(TAG, "User skipped processing (process=$process, selected=${selected.size}) — reloading")
            _state.update { it.copy(auditProgress = null) }
            viewModelScope.launch(Dispatchers.IO) {
                audit.folderIds.forEach { localCacheFile(it).delete() }
                getApplication<Application>().filesDir.resolve("wardrobe")
                    .listFiles()?.forEach { it.delete() }
                withContext(Dispatchers.Main) { loadImages() }
            }
            return
        }

        val processTotal = filteredOrphaned.size + filteredRaw.size + filteredSidecar.size
        Log.d(TAG, "Processing started — ${filteredOrphaned.size} orphaned original(s), ${filteredRaw.size} raw image(s), ${filteredSidecar.size} cutout(s) needing tagging (selection-filtered)")
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

                Log.d(TAG, "Processing complete — clearing caches and reloading")
                // Clear all local caches and reload
                audit.folderIds.forEach { localCacheFile(it).delete() }
                getApplication<Application>().filesDir.resolve("wardrobe")
                    .listFiles()?.forEach { it.delete() }

                _state.update { it.copy(auditProgress = AuditProgress(isDone = true)) }
                withContext(Dispatchers.Main) { loadImages() }
            } finally {
                releaseJobWakeLock()
            }
        }
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

    private fun localCacheFile(id: String) =
        File(getApplication<Application>().filesDir, "wardrobe_cache_$id.json")

    private fun saveLocalCache(id: String, images: List<DriveImage>) {
        runCatching {
            val cache = LocalCache(images.map {
                LocalCacheEntry(it.driveId, it.name, it.tags, it.originalDriveId, it.sidecarDriveId)
            })
            localCacheFile(id).writeText(gson.toJson(cache))
        }
    }

    // ---------- Load ----------

    fun loadImages() {
        loadJob?.cancel()
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
                                    folderId = fid)
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
                                )
                            }
                        }
                    }
                    val freshIds = allFresh.map { it.driveId }.toSet()
                    val pendingRaw = _state.value.images.filter { img ->
                        img.driveId !in freshIds && !img.name.endsWith(DriveRepository.CUTOUT_SUFFIX)
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
                                folderId = id)
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
                        )
                    }
                }

                // Preserve raw/pending uploads that are in the queue but not yet on Drive as cutouts
                val freshIds = freshImages.map { it.driveId }.toSet()
                val pendingRaw = _state.value.images.filter { img ->
                    img.driveId !in freshIds && !img.name.endsWith(DriveRepository.CUTOUT_SUFFIX)
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
                _state.update { it.copy(pendingJobs = maxOf(0, it.pendingJobs - 1)) }
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
        // NOTE: we intentionally do NOT check if job.driveId is still in state — loadImages()
        // may have replaced it with the cutout ID already (race window). We always process.

        // Step 1 — background removal (falls back to raw if Gemini fails)
        val processedFile = gemini.removeBackground(rawFile, drive.cacheDir) ?: rawFile

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
            s.copy(images = s.images.map { img ->
                if (img.driveId == job.driveId || img.driveId == cutoutDrive.id) img.copy(
                    driveId    = cutoutDrive.id,
                    name       = cutoutDrive.name,          // "{cutoutId}_cutout.png"
                    localPath  = localCutout.absolutePath,
                    version    = System.currentTimeMillis(),
                    originalDriveId = originalDriveId,
                ) else img
            })
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
    }

    // ---------- Navigation ----------

    fun openCapture() = _state.update { it.copy(view = WardrobeView.CAPTURE) }
    fun closeCapture() = _state.update { it.copy(view = WardrobeView.GRID) }

    // ---------- Upload from camera ----------

    fun uploadPhoto(rawFile: File) {
        val id = (defaultImportFolderId ?: folderId) ?: run {
            _state.update { it.copy(view = WardrobeView.GRID) }
            return
        }
        if (dedupeOnImport && EmbeddingService.isModelAvailable()) {
            viewModelScope.launch {
                _state.update { it.copy(view = WardrobeView.GRID, isUploading = true, error = null) }
                EmbeddingService.syncIndex(_state.value.images, drive.cacheDir)
                val matches = EmbeddingService.findSimilar(
                    file = rawFile,
                    threshold = dedupeThreshold,
                    topK = 8,
                    segment = true,
                )
                val byId = _state.value.images.associateBy { it.driveId }
                val resolved = matches.mapNotNull { m ->
                    byId[m.driveId]?.let { DuplicateMatch(it, m.score) }
                }
                if (resolved.isEmpty()) {
                    proceedWithCameraUpload(rawFile, id)
                } else {
                    _state.update { it.copy(
                        isUploading = false,
                        duplicateCheck = DuplicateCheck(
                            rawFilePath = rawFile.absolutePath,
                            targetFolderId = id,
                            matches = resolved,
                        ),
                    ) }
                }
            }
            return
        }
        proceedWithCameraUpload(rawFile, id)
    }

    /** User confirmed they want to import despite a similarity match. */
    fun confirmDuplicateImport() {
        val dc = _state.value.duplicateCheck ?: return
        _state.update { it.copy(duplicateCheck = null) }
        proceedWithCameraUpload(File(dc.rawFilePath), dc.targetFolderId)
    }

    /** User cancelled the import — discard the raw file and clear the check. */
    fun cancelDuplicateImport() {
        val dc = _state.value.duplicateCheck ?: return
        runCatching { File(dc.rawFilePath).delete() }
        _state.update { it.copy(duplicateCheck = null) }
    }

    /** The original [uploadPhoto] body, extracted so the dedupe gate can call it once cleared. */
    private fun proceedWithCameraUpload(rawFile: File, id: String) {
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
                DriveImage(uploaded.id, displayCache.absolutePath, uploaded.name, tags = null, folderId = id)
            }.onSuccess { newImage ->
                _state.update { it.copy(
                    isUploading = false,
                    images = listOf(newImage) + it.images,
                    pendingJobs = it.pendingJobs + 1,
                ) }
                // No sidecar yet — the item is raw; sidecar is written by processQueuedImage
                saveLocalCache(id, _state.value.images)
                workQueue.send(PendingJob(newImage.driveId, id))
            }.onFailure { e ->
                _state.update { it.copy(isUploading = false, error = e.message) }
            }
        }
    }

    // ---------- Upload from gallery ----------

    fun uploadGalleryPhotos(uris: List<Uri>) {
        if (uris.isEmpty()) return
        val id = (defaultImportFolderId ?: folderId) ?: return
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
                    DriveImage(uploaded.id, displayCache.absolutePath, uploaded.name, tags = null, folderId = id)
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
            }
            _state.update { it.copy(isMoving = false) }
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
    fun importFromFolder(
        treeUri: Uri,
        removeBackground: Boolean = false,
        autoTag: Boolean = false,
        replaceExisting: Boolean = false,
        overwriteDuplicates: Boolean = false,
    ) {
        val id = (defaultImportFolderId ?: folderId) ?: return
        viewModelScope.launch {
            acquireJobWakeLock()
            try {
            val cr = getApplication<Application>().contentResolver

            // ---- Enumerate source files ----
            val treeDocId = DocumentsContract.getTreeDocumentId(treeUri)
            val childrenUri = DocumentsContract.buildChildDocumentsUriUsingTree(treeUri, treeDocId)

            data class SrcFile(val docId: String, val displayName: String, val mimeType: String)
            val srcFiles = mutableListOf<SrcFile>()
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
                        mime.startsWith("image/")         -> srcFiles.add(SrcFile(docId, name, mime))
                    }
                }
            }

            if (srcFiles.isEmpty()) return@launch

            // ---- Load source metadata (tags/originalDriveId carried over from a prior export) ----
            val srcMetaByName: Map<String, WardrobeItemMeta> = metaDocId?.let { docId ->
                runCatching {
                    val metaUri = DocumentsContract.buildDocumentUriUsingTree(treeUri, docId)
                    val json = cr.openInputStream(metaUri)?.use { it.readBytes().toString(Charsets.UTF_8) }
                    if (json != null) gson.fromJson(json, WardrobeMetadata::class.java).items.associateBy { it.name }
                    else emptyMap()
                }.getOrDefault(emptyMap())
            } ?: emptyMap()

            // ---- Fresh-start: delete all existing wardrobe items first ----
            if (replaceExisting) {
                val toDelete = _state.value.images.map { it.driveId }
                _state.update { it.copy(isImporting = true, importDone = 0, importTotal = srcFiles.size, images = emptyList(), error = null) }
                toDelete.forEach { driveId -> runCatching { drive.deleteFile(driveId) } }
            } else {
                _state.update { it.copy(isImporting = true, importDone = 0, importTotal = srcFiles.size, error = null) }
            }

            // Build name → existing item map for duplicate detection (empty after fresh-start)
            val existingByName: Map<String, DriveImage> = _state.value.images.associateBy { it.name }

            // ---- Process each source image ----
            srcFiles.forEachIndexed { index, src ->
                _state.update { it.copy(importDone = index) }

                val duplicate = existingByName[src.displayName]
                if (duplicate != null && !overwriteDuplicates) {
                    // Skip — item with this name already in wardrobe
                    return@forEachIndexed
                }

                val srcExt = if (src.mimeType == "image/png") "png" else "jpg"
                val tempFile = File(drive.cacheDir, "import_${System.currentTimeMillis()}.$srcExt")
                runCatching {
                    val srcUri = DocumentsContract.buildDocumentUriUsingTree(treeUri, src.docId)
                    cr.openInputStream(srcUri)?.use { it.copyTo(tempFile.outputStream()) }
                        ?: error("Cannot open stream for ${src.displayName}")

                    val srcMeta = srcMetaByName[src.displayName]

                    // Optional background removal — defer original upload until cutout ID is known
                    var imageToUpload = tempFile
                    var rawOriginalFile: File? = null
                    var originalDriveId: String? = srcMeta?.originalDriveId ?: duplicate?.originalDriveId
                    if (removeBackground && srcMeta == null) {
                        val processed = gemini.removeBackground(tempFile, drive.cacheDir)
                        if (processed != null) {
                            rawOriginalFile = tempFile   // uploaded after we know the cutout ID
                            imageToUpload = processed
                        }
                    }

                    // Tags: source metadata → existing item tags → Gemini → none
                    val tags = when {
                        srcMeta != null   -> srcMeta.tags
                        duplicate != null -> duplicate.tags
                        autoTag           -> gemini.classifyClothing(imageToUpload, geminiLanguage)
                        else              -> null
                    }

                    if (duplicate != null) {
                        // Overwrite in-place — keep the same Drive ID and name
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
                        // New item — upload as {cutoutId}_cutout.png
                        val cutoutUploaded = uploadAsCutout(id, imageToUpload)
                        val displayCache = File(drive.cacheDir, "${cutoutUploaded.id}.png")
                        imageToUpload.copyTo(displayCache, overwrite = true)
                        val finalOriginalId = rawOriginalFile?.let { orig ->
                            val oid = runCatching { uploadAsOriginal(id, orig, cutoutUploaded.id) }.getOrNull()
                            orig.copyTo(File(drive.cacheDir, "${cutoutUploaded.id}_original.jpg"), overwrite = true)
                            oid
                        } ?: originalDriveId
                        _state.update { it.copy(images = it.images + DriveImage(
                            driveId = cutoutUploaded.id,
                            localPath = displayCache.absolutePath,
                            name = cutoutUploaded.name,
                            tags = tags,
                            originalDriveId = finalOriginalId,
                        )) }
                    }
                }.onFailure { e ->
                    _state.update { it.copy(error = "Import failed for ${src.displayName}: ${e.message}") }
                }
                tempFile.delete()
            }

            _state.update { it.copy(isImporting = false, importDone = 0, importTotal = 0) }
            // Save sidecar for each newly-created or updated item
            _state.value.images.forEach { img -> if (img.sidecarDriveId == null) saveSidecar(img.driveId) }
            } finally {
                releaseJobWakeLock()
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
        viewModelScope.launch {
            acquireJobWakeLock()
            try {
            val srcFiles = runCatching { drive.listFiles(sourceFolderId) }.getOrDefault(emptyList())
            if (srcFiles.isEmpty()) return@launch

            if (replaceExisting) {
                val toDelete = _state.value.images.map { it.driveId }
                _state.update { it.copy(isImporting = true, importDone = 0, importTotal = srcFiles.size, images = emptyList(), error = null) }
                toDelete.forEach { driveId -> runCatching { drive.deleteFile(driveId) } }
            } else {
                _state.update { it.copy(isImporting = true, importDone = 0, importTotal = srcFiles.size, error = null) }
            }

            val existingByName: Map<String, DriveImage> = _state.value.images.associateBy { it.name }

            srcFiles.forEachIndexed { index, src ->
                _state.update { it.copy(importDone = index) }

                val duplicate = existingByName[src.name]
                if (duplicate != null && !overwriteDuplicates) return@forEachIndexed

                val srcExt = if (src.name.endsWith(".png", ignoreCase = true)) "png" else "jpg"
                val tempFile = File(drive.cacheDir, "import_drive_${System.currentTimeMillis()}.$srcExt")

                runCatching {
                    drive.downloadFileTo(src.id, tempFile)
                        ?: error("Cannot download ${src.name}")

                    var imageToUpload = tempFile
                    var rawOriginalFile: File? = null
                    var originalDriveId: String? = duplicate?.originalDriveId
                    if (removeBackground) {
                        val processed = gemini.removeBackground(tempFile, drive.cacheDir)
                        if (processed != null) {
                            rawOriginalFile = tempFile
                            imageToUpload = processed
                        }
                    }

                    val tags = when {
                        autoTag           -> gemini.classifyClothing(imageToUpload, geminiLanguage)
                        duplicate != null -> duplicate.tags
                        else              -> null
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
                        _state.update { it.copy(images = it.images + DriveImage(
                            driveId = cutoutUploaded.id,
                            localPath = displayCache.absolutePath,
                            name = cutoutUploaded.name,
                            tags = tags,
                            originalDriveId = finalOriginalId,
                        )) }
                    }
                }.onFailure { e ->
                    _state.update { it.copy(error = "Import failed for ${src.name}: ${e.message}") }
                }
                tempFile.delete()
            }

            _state.update { it.copy(isImporting = false, importDone = 0, importTotal = 0) }
            // Save sidecar for each newly-created or updated item
            _state.value.images.forEach { img -> if (img.sidecarDriveId == null) saveSidecar(img.driveId) }
            } finally {
                releaseJobWakeLock()
            }
        }
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

private fun rotateBitmapFileBy90(file: File) {
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
