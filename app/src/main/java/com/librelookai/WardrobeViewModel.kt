package com.librelookai

import android.app.Application
import android.net.Uri
import android.provider.DocumentsContract
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.google.gson.Gson
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
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
)

data class WardrobeUiState(
    val view: WardrobeView = WardrobeView.GRID,
    val images: List<DriveImage> = emptyList(),
    val isLoading: Boolean = false,
    val isProcessing: Boolean = false,
    val isUploading: Boolean = false,
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
    /** driveId of the image currently being processed by an AI operation, or null. */
    val processingImageId: String? = null,
    val selectedIds: Set<String> = emptySet(),
    /** Number of photos queued or actively running background processing (bg removal + tagging). */
    val pendingJobs: Int = 0,
    /** Non-null while a repair-and-sync audit is in progress or awaiting user input. */
    val auditProgress: AuditProgress? = null,
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
    /** Cutouts that are missing a tag sidecar and need tagging. */
    val sidecarNeeded: Int = 0,
    val isProcessing: Boolean = false,
    val processDone: Int = 0,
    val processTotal: Int = 0,
    val isDone: Boolean = false,
)

// ---------- Per-item sidecar metadata ----------

private data class ItemSidecar(val tags: ClothingTags? = null, val originalDriveId: String? = null)

// ---------- Legacy bulk metadata (read-only, migration fallback) ----------

private data class WardrobeItemMeta(val name: String, val tags: ClothingTags?, val originalDriveId: String? = null)
private data class WardrobeMetadata(val items: List<WardrobeItemMeta> = emptyList())

// ---------- Local disk cache (instant startup, no network) ----------

/** Full DriveImage snapshot stored on device for zero-latency startup. */
private data class LocalCacheEntry(
    val driveId: String,
    val name: String,
    val tags: ClothingTags?,
    val originalDriveId: String? = null,
    val sidecarDriveId: String? = null,
)
private data class LocalCache(val items: List<LocalCacheEntry> = emptyList())

private data class PendingJob(val driveId: String, val folderId: String)

// Internal audit helpers
private data class AuditItem(val folderId: String, val driveId: String, val name: String)
private data class AuditCutoutItem(val folderId: String, val cutoutDriveId: String, val cutoutName: String)
private data class AuditIntermediate(
    val folderIds: List<String>,
    val orphanedOriginals: List<AuditItem>,
    val cutoutsNeedingSidecar: List<AuditCutoutItem>,
)

class WardrobeViewModel(app: Application) : AndroidViewModel(app) {

    private val drive = DriveRepository(app, GoogleAuthManager(app))
    private val gemini = GeminiRepository(app)
    private val gson = Gson()

    private val _state = MutableStateFlow(WardrobeUiState())
    val state: StateFlow<WardrobeUiState> = _state.asStateFlow()

    private var folderId: String? = null
    /** Gemini-facing language name (e.g. "English", "German") for label generation. */
    private var geminiLanguage: String = "English"

    /** Serializes all Drive metadata writes to prevent concurrent saves overwriting each other. */
    private val metaMutex = Mutex()

    /** Holds the audit scan results while waiting for the user to confirm processing. */
    private var pendingAudit: AuditIntermediate? = null

    /**
     * Background processing queue. Each item represents one uploaded photo that needs
     * bg removal + classification. Processed serially so metadata writes are consistent.
     */
    private val workQueue = Channel<PendingJob>(Channel.UNLIMITED)

    init {
        viewModelScope.launch { processQueue() }
    }

    fun setLanguage(geminiName: String) { geminiLanguage = geminiName }

    fun setLocation(newFolderId: String) {
        if (folderId == newFolderId) return
        folderId = newFolderId
        _state.update { WardrobeUiState(isLoading = true) }
        loadImages()
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
        viewModelScope.launch(Dispatchers.IO) {
            _state.update { it.copy(auditProgress = AuditProgress(isScanning = true, totalFolders = folderIds.size)) }

            var totalRenamed = 0
            val orphaned = mutableListOf<AuditItem>()
            val needSidecar = mutableListOf<AuditCutoutItem>()

            folderIds.forEachIndexed { idx, fid ->
                runCatching {
                    val allImages = drive.listAllImageFiles(fid)
                    val sidecars  = drive.listSidecarFiles(fid)

                    val cutouts   = allImages.filter { it.name.endsWith(DriveRepository.CUTOUT_SUFFIX) }
                    val originals = allImages.filter { it.name.endsWith(DriveRepository.ORIGINAL_SUFFIX) }
                    val cutoutIds = cutouts.map { it.id }.toSet()
                    val sidecarItemIds = sidecars
                        .map { it.name.removeSuffix(DriveRepository.SIDECAR_SUFFIX) }
                        .toSet()

                    // Ensure every cutout is named "{id}_cutout.png"
                    cutouts.forEach { cutout ->
                        val expected = "${cutout.id}${DriveRepository.CUTOUT_SUFFIX}"
                        if (cutout.name != expected) {
                            runCatching { drive.renameFile(cutout.id, expected) }
                            totalRenamed++
                        }
                    }

                    // Check originals: prefix must match a cutout Drive ID
                    originals.forEach { original ->
                        val prefix = original.name.removeSuffix(DriveRepository.ORIGINAL_SUFFIX)
                        if (prefix !in cutoutIds) {
                            // Orphaned original — no matching cutout exists
                            orphaned.add(AuditItem(fid, original.id, original.name))
                        }
                    }

                    // Ensure every cutout has a sidecar
                    cutouts.forEach { cutout ->
                        if (cutout.id !in sidecarItemIds) {
                            needSidecar.add(AuditCutoutItem(fid, cutout.id, "${cutout.id}${DriveRepository.CUTOUT_SUFFIX}"))
                        }
                    }
                }
                _state.update { s ->
                    s.copy(auditProgress = s.auditProgress?.copy(scannedFolders = idx + 1))
                }
            }

            pendingAudit = AuditIntermediate(folderIds, orphaned, needSidecar)
            _state.update { it.copy(
                auditProgress = AuditProgress(
                    awaitingConfirmation = true,
                    renamedCount = totalRenamed,
                    orphanedOriginals = orphaned.size,
                    sidecarNeeded = needSidecar.size,
                )
            )}
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

        if (!process) {
            _state.update { it.copy(auditProgress = null) }
            viewModelScope.launch(Dispatchers.IO) {
                audit.folderIds.forEach { localCacheFile(it).delete() }
                getApplication<Application>().filesDir.resolve("wardrobe")
                    .listFiles()?.forEach { it.delete() }
                withContext(Dispatchers.Main) { loadImages() }
            }
            return
        }

        val processTotal = audit.orphanedOriginals.size + audit.cutoutsNeedingSidecar.size
        _state.update { it.copy(auditProgress = AuditProgress(isProcessing = true, processTotal = processTotal)) }

        viewModelScope.launch(Dispatchers.IO) {
            var done = 0

            // --- Orphaned originals: bg removal + cutout upload + tag + sidecar ---
            audit.orphanedOriginals.forEach { item ->
                runCatching {
                    val localOriginal = drive.downloadToCache(item.driveId, item.name)
                        ?: return@runCatching
                    val cutoutFile = gemini.removeBackground(localOriginal, drive.cacheDir) ?: localOriginal
                    val cutoutDrive = uploadAsCutout(item.folderId, cutoutFile)
                    val newOrigId = runCatching {
                        drive.deleteFile(item.driveId)
                        drive.uploadImageWithName(
                            item.folderId, localOriginal,
                            "${cutoutDrive.id}${DriveRepository.ORIGINAL_SUFFIX}",
                        ).id
                    }.getOrNull()
                    val localCutout = File(drive.cacheDir, "${cutoutDrive.id}.png")
                    if (cutoutFile.absolutePath != localCutout.absolutePath) {
                        cutoutFile.copyTo(localCutout, overwrite = true)
                    }
                    localOriginal.copyTo(
                        File(drive.cacheDir, "${cutoutDrive.id}_original.jpg"), overwrite = true,
                    )
                    val tags = gemini.classifyClothing(localCutout, geminiLanguage)
                    drive.upsertSidecar(
                        item.folderId,
                        "${cutoutDrive.id}${DriveRepository.SIDECAR_SUFFIX}",
                        gson.toJson(ItemSidecar(tags, newOrigId)),
                    )
                }
                done++
                _state.update { s -> s.copy(auditProgress = s.auditProgress?.copy(processDone = done)) }
            }

            // --- Cutouts missing sidecars: tag + sidecar ---
            audit.cutoutsNeedingSidecar.forEach { item ->
                runCatching {
                    val localCutout = drive.cachedFile(item.cutoutDriveId)
                        ?: drive.downloadToCache(item.cutoutDriveId, item.cutoutName)
                        ?: return@runCatching
                    val tags = gemini.classifyClothing(localCutout, geminiLanguage)
                    drive.upsertSidecar(
                        item.folderId,
                        "${item.cutoutDriveId}${DriveRepository.SIDECAR_SUFFIX}",
                        gson.toJson(ItemSidecar(tags, null)),
                    )
                }
                done++
                _state.update { s -> s.copy(auditProgress = s.auditProgress?.copy(processDone = done)) }
            }

            // Clear all local caches and reload
            audit.folderIds.forEach { localCacheFile(it).delete() }
            getApplication<Application>().filesDir.resolve("wardrobe")
                .listFiles()?.forEach { it.delete() }

            _state.update { it.copy(auditProgress = AuditProgress(isDone = true)) }
            withContext(Dispatchers.Main) { loadImages() }
        }
    }

    /** Clears the audit result state (call after user dismisses the "done" message). */
    fun dismissAuditResult() {
        _state.update { it.copy(auditProgress = null) }
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
        viewModelScope.launch {
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
                                sidecarDriveId = entry.sidecarDriveId)
                        }
                    }
                }.onSuccess { items ->
                    if (items.isNotEmpty()) {
                        _state.update { it.copy(images = items, isLoading = false) }
                    }
                }
            }

            // Phase 2 — background sync: fetch Drive cutouts + sidecars in parallel
            runCatching {
                val filesDeferred = async { drive.listFiles(id) }
                val sidecarFilesDeferred = async { drive.listSidecarFiles(id) }
                val files = filesDeferred.await()
                val sidecarFiles = sidecarFilesDeferred.await()

                // Map cutout Drive ID → sidecar file ID (sidecar is named "{cutoutId}.json")
                val sidecarIdByItemId: Map<String, String> = sidecarFiles.associate { sf ->
                    sf.name.removeSuffix(DriveRepository.SIDECAR_SUFFIX) to sf.id
                }

                // Download any uncached image files in parallel
                files.map { file ->
                    async { drive.cachedFile(file.id) ?: drive.downloadToCache(file.id, file.name) }
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
                _state.update { it.copy(images = images, isLoading = false) }
                saveLocalCache(id, images)
            }.onFailure { e ->
                // Don't overwrite cached items already shown with an error banner
                _state.update { s ->
                    s.copy(isLoading = false, error = if (s.images.isEmpty()) e.message else null)
                }
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
            runCatching { processQueuedImage(job) }
                .onFailure { e -> _state.update { it.copy(error = e.message) } }
            _state.update { it.copy(pendingJobs = maxOf(0, it.pendingJobs - 1)) }
            // Sidecar is saved inside processQueuedImage; update local cache here
            folderId?.let { id -> saveLocalCache(id, _state.value.images) }
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
        val id = folderId ?: run {
            _state.update { it.copy(view = WardrobeView.GRID) }
            return
        }
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
                DriveImage(uploaded.id, displayCache.absolutePath, uploaded.name, tags = null)
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
        val id = folderId ?: return
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
                    DriveImage(uploaded.id, displayCache.absolutePath, uploaded.name, tags = null)
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
        }
    }

    // ---------- Move to another location ----------

    fun moveItemsToLocation(driveIds: Set<String>, targetFolderId: String) {
        val toMove = _state.value.images.filter { it.driveId in driveIds }
        if (toMove.isEmpty()) return
        viewModelScope.launch {
            val sourceFolderId = folderId ?: return@launch
            _state.update { it.copy(isUploading = true, selectedIds = emptySet(), error = null) }

            val successfulIds = mutableListOf<String>()
            for (item in toMove) {
                runCatching {
                    // Move cutout — Drive parent-change: keeps same ID, name, and content
                    drive.moveFile(item.driveId, sourceFolderId, targetFolderId)
                    // Move original (best-effort)
                    item.originalDriveId?.let { origId ->
                        runCatching { drive.moveFile(origId, sourceFolderId, targetFolderId) }
                    }
                    // Move sidecar (best-effort; it travels with the cutout)
                    item.sidecarDriveId?.let { sId ->
                        runCatching { drive.moveFile(sId, sourceFolderId, targetFolderId) }
                    }
                    successfulIds.add(item.driveId)
                }.onFailure { e ->
                    _state.update { it.copy(error = e.message) }
                }
            }

            if (successfulIds.isNotEmpty()) {
                _state.update { s -> s.copy(images = s.images.filter { it.driveId !in successfulIds }) }
                saveLocalCache(sourceFolderId, _state.value.images)
            }
            _state.update { it.copy(isUploading = false) }
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
        val id = folderId ?: return
        viewModelScope.launch {
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
        val id = folderId ?: return
        viewModelScope.launch {
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

    fun deleteSelected() {
        val toDelete = _state.value.selectedIds
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
