package com.librelookai.wardrobe
import com.librelookai.util.localized

import android.content.Context
import android.net.Uri
import android.util.Log
import com.google.gson.Gson
import com.librelookai.R
import com.librelookai.data.drive.DriveFileDto
import com.librelookai.data.drive.DriveRepository
import com.librelookai.data.drive.upsertSidecar
import com.librelookai.data.local.WardrobeItemStore
import com.librelookai.data.session.ClosetSessionHolder
import com.librelookai.data.session.UserPreferencesRepository
import com.librelookai.gemini.GeminiRepository
import com.librelookai.gemini.classifyClothing
import com.librelookai.ml.EmbeddingService
import com.librelookai.service.JobLock
import com.librelookai.settings.AppLanguage
import dagger.hilt.android.qualifiers.ApplicationContext
import java.io.File
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/** Cache subdir for the processed-query bitmaps fed to the similarity debug preview. */
internal const val QUERY_DEBUG_DIR = "wardrobe_query_debug"

/**
 * Live progress of the photo-ingestion pipeline. The wardrobe VM mirrors it into
 * [WardrobeUiState] (the existing UI contract); slice 7 will let surfaces read it directly.
 */
data class IngestionProgress(
    /** Photos queued or actively running background processing (bg removal + tagging). */
    val pendingJobs: Int = 0,
    /** Gallery batch progress (0 total = single-item flow). */
    val batchDone: Int = 0,
    val batchTotal: Int = 0,
    /** True while a raw upload / dedupe pass is in flight. */
    val isUploading: Boolean = false,
    /** driveId the queue worker is currently processing (the raw id, then the cutout id). */
    val processingImageId: String? = null,
    /** Non-null while a captured photo is paused for duplicate confirmation. */
    val duplicateCheck: DuplicateCheck? = null,
    /** Queue of imports waiting for the on-device background-removal review screen. */
    val localBgReviewQueue: List<LocalBgReviewItem> = emptyList(),
    /**
     * Bumped whenever the wardrobe UI should return to the grid view — exactly the moments
     * the pre-extraction code flipped `view = GRID` (a dedupe pass or an upload starting;
     * NOT a gallery batch, which may legitimately run under the capture screen).
     */
    val gridReturnTick: Int = 0,
)

/**
 * Uploads [imageFile] to [folderId], then immediately renames it to "{driveId}_cutout.png"
 * (where driveId is the Drive-assigned ID). Returns the [DriveFileDto] with the final name.
 */
internal suspend fun DriveRepository.uploadAsCutout(folderId: String, imageFile: File): DriveFileDto {
    val uploaded = uploadImage(folderId, imageFile)
    val finalName = "${uploaded.id}${DriveRepository.CUTOUT_SUFFIX}"
    runCatching { renameFile(uploaded.id, finalName) }
    return uploaded.copy(name = finalName)
}

/**
 * Uploads [imageFile] to [folderId] with filename "{cutoutDriveId}_original.jpg".
 * Returns the new Drive file ID.
 */
internal suspend fun DriveRepository.uploadAsOriginal(folderId: String, imageFile: File, cutoutDriveId: String): String =
    uploadImageWithName(folderId, imageFile, "$cutoutDriveId${DriveRepository.ORIGINAL_SUFFIX}").id

/**
 * The photo-ingestion use-case (refactor § 5 slice 5): dedupe gate → optional on-device
 * bg-removal review → raw upload → serial background queue (bg removal + tagging + sidecar),
 * extracted verbatim from `WardrobeViewModel`(+`…Upload.kt`). Finished items land in the
 * [WardrobeItemStore], so the derived grids update without VM involvement; the VM only
 * delegates the entry points and mirrors [progress]/[errors] into its UI state.
 *
 * Process-scoped (`@Singleton` + own scope) rather than ViewModel-scoped: the [JobLock]
 * foreground service + wake lock already promise the user that an ingestion survives leaving
 * the screen — now the coroutines actually do live that long.
 */
@Singleton
class ItemIngestionPipeline @Inject constructor(
    @ApplicationContext private val context: Context,
    private val drive: DriveRepository,
    private val gemini: GeminiRepository,
    private val itemStore: WardrobeItemStore,
    private val session: ClosetSessionHolder,
    prefsRepo: UserPreferencesRepository,
    private val jobLock: JobLock,
) {
    companion object { private const val TAG = "ItemIngestion" }

    private val gson = Gson()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    private val _progress = MutableStateFlow(IngestionProgress())
    val progress: StateFlow<IngestionProgress> = _progress.asStateFlow()

    /** Error funnel for [WardrobeUiState.error]; a `null` emission clears it (upload start). */
    private val _errors = MutableSharedFlow<String?>(extraBufferCapacity = 16)
    val errors: SharedFlow<String?> = _errors.asSharedFlow()

    // Preference-derived knobs, collected from the shared repository (§ 5 slice 2 pattern).
    private var geminiLanguage: String = "English"
    private var dedupeOnImport: Boolean = false
    private var dedupeThreshold: Float = 0.88f
    private var preferLocalBgRemoval: Boolean = false
    private var debugSimilarityPreview: Boolean = false

    /**
     * Background processing queue. Each item represents one uploaded photo that needs
     * bg removal + classification. Processed serially so metadata writes are consistent.
     */
    private val workQueue = Channel<PendingJob>(Channel.UNLIMITED)

    private data class DedupeRouting(val skippable: Boolean, val force: Boolean, val source: AddSource)
    private var pendingDedupeRouting: DedupeRouting? = null

    init {
        scope.launch { processQueue() }
        scope.launch {
            prefsRepo.preferences.collect { p ->
                geminiLanguage = AppLanguage.toGeminiName(p.language)
                dedupeOnImport = p.dedupeOnImport
                dedupeThreshold = p.dedupeThreshold
                preferLocalBgRemoval = p.preferLocalBgRemoval
                debugSimilarityPreview = p.debugSimilarityPreview
            }
        }
    }

    // ---------- Entry points ----------

    /**
     * Shared entry for camera + gallery + URL imports. Runs the dedupe gate, then either opens
     * the local-bg review queue (when wired) or proceeds straight to upload.
     *
     * @param skippableLocalReview true for camera/gallery (user can decline the local cutout and
     *   fall back to Gemini); false for URL imports, where the review is mandatory.
     * @param forceLocalReview true to always enqueue the review even when [preferLocalBgRemoval]
     *   is off — set by URL imports.
     */
    fun ingest(
        rawFile: File,
        folderId: String,
        skippableLocalReview: Boolean,
        forceLocalReview: Boolean = false,
        source: AddSource = AddSource.CAMERA,
    ) {
        if (dedupeOnImport && EmbeddingService.isModelAvailable()) {
            scope.launch {
                _progress.update { it.copy(isUploading = true, gridReturnTick = it.gridReturnTick + 1) }
                _errors.emit(null)
                // The cross-closet snapshot is a store read over the session's snapshot scope
                // (§ 5 slice 4a made the store always current).
                val crossClosetImages = crossClosetImages()
                EmbeddingService.syncIndex(crossClosetImages, drive.cacheDir)
                val sim = EmbeddingService.findSimilarWithDebug(
                    file = rawFile,
                    threshold = dedupeThreshold,
                    topK = if (debugSimilarityPreview) 50 else 8,
                    processedOutputDir = File(context.cacheDir, QUERY_DEBUG_DIR),
                )
                val byId = crossClosetImages.associateBy { it.driveId }
                val resolved = sim?.matches.orEmpty().mapNotNull { m ->
                    byId[m.driveId]?.let { DuplicateMatch(it, m.score) }
                }
                if (resolved.isEmpty()) {
                    sim?.processedPath?.let { runCatching { File(it).delete() } }
                    routeAfterDedupe(rawFile, folderId, skippableLocalReview, forceLocalReview, source)
                } else {
                    _progress.update { it.copy(
                        isUploading = false,
                        duplicateCheck = DuplicateCheck(
                            rawFilePath = rawFile.absolutePath,
                            targetFolderId = folderId,
                            matches = resolved,
                            processedPath = sim?.processedPath,
                            segmented = sim?.segmented ?: false,
                            hist = sim?.hist,
                            vec = sim?.vec,
                            pHash = sim?.pHash,
                        ),
                    ) }
                    logWardrobeAdd("dedupe_prompt", source, mapOf("matches" to resolved.size.toString()))
                    // Stash routing for confirmDuplicateImport to resume.
                    pendingDedupeRouting = DedupeRouting(skippableLocalReview, forceLocalReview, source)
                }
            }
            return
        }
        routeAfterDedupe(rawFile, folderId, skippableLocalReview, forceLocalReview, source)
    }

    /** Either enqueue the local-bg review or proceed straight to upload. */
    private fun routeAfterDedupe(
        rawFile: File,
        folderId: String,
        skippable: Boolean,
        force: Boolean,
        source: AddSource,
    ) {
        val shouldReview = (force || preferLocalBgRemoval) &&
            EmbeddingService.segmenter.isAvailable()
        if (shouldReview) {
            _progress.update { it.copy(
                isUploading = false,
                localBgReviewQueue = it.localBgReviewQueue + LocalBgReviewItem(
                    rawFilePath = rawFile.absolutePath,
                    targetFolderId = folderId,
                    skippable = skippable,
                    source = source,
                ),
            ) }
        } else {
            proceedWithUpload(rawFile, folderId, source = source)
        }
    }

    /** User confirmed they want to import despite a similarity match. */
    fun confirmDuplicateImport() {
        val dc = _progress.value.duplicateCheck ?: return
        dc.processedPath?.let { runCatching { File(it).delete() } }
        _progress.update { it.copy(duplicateCheck = null) }
        val routing = pendingDedupeRouting ?: DedupeRouting(skippable = true, force = false, source = AddSource.CAMERA)
        pendingDedupeRouting = null
        logWardrobeAdd("dedupe_confirm", routing.source)
        routeAfterDedupe(File(dc.rawFilePath), dc.targetFolderId, routing.skippable, routing.force, routing.source)
    }

    /** User cancelled the import — discard the raw file and clear the check. */
    fun cancelDuplicateImport() {
        val dc = _progress.value.duplicateCheck ?: return
        runCatching { File(dc.rawFilePath).delete() }
        dc.processedPath?.let { runCatching { File(it).delete() } }
        logWardrobeAdd("dedupe_cancel", pendingDedupeRouting?.source)
        pendingDedupeRouting = null
        _progress.update { it.copy(duplicateCheck = null) }
    }

    // ---------- Local background-removal review ----------

    /**
     * User accepted the on-device cutout for the head item of the review queue. The cutout file
     * has already been written to [cutoutFile] by the review screen; we hand both raw + cutout
     * off to the regular upload pipeline, then advance the queue.
     */
    fun applyLocalBgCutout(cutoutFile: File) {
        val head = _progress.value.localBgReviewQueue.firstOrNull() ?: return
        val raw = File(head.rawFilePath)
        _progress.update { it.copy(localBgReviewQueue = it.localBgReviewQueue.drop(1)) }
        proceedWithUpload(raw, head.targetFolderId, prebuiltCutout = cutoutFile, source = head.source)
    }

    /** User declined the on-device cutout — fall back to the regular Gemini path. Only valid
     *  for entries with `skippable = true` (camera + gallery; not URL). */
    fun skipLocalBgReview() {
        val head = _progress.value.localBgReviewQueue.firstOrNull() ?: return
        if (!head.skippable) return
        val raw = File(head.rawFilePath)
        _progress.update { it.copy(localBgReviewQueue = it.localBgReviewQueue.drop(1)) }
        proceedWithUpload(raw, head.targetFolderId, source = head.source)
    }

    /** User cancelled this import entirely — discard the raw file and advance the queue. */
    fun cancelLocalBgReview() {
        val head = _progress.value.localBgReviewQueue.firstOrNull() ?: return
        runCatching { File(head.rawFilePath).delete() }
        _progress.update { it.copy(localBgReviewQueue = it.localBgReviewQueue.drop(1)) }
    }

    // ---------- Upload ----------

    /**
     * Uploads the raw file and enqueues the background job. When [prebuiltCutout] is non-null,
     * it is treated as a pre-rendered cutout PNG produced by the on-device segmenter; the
     * worker will use it instead of running Gemini.
     */
    private fun proceedWithUpload(
        rawFile: File,
        folderId: String,
        prebuiltCutout: File? = null,
        source: AddSource = AddSource.CAMERA,
    ) {
        scope.launch {
            _progress.update { it.copy(isUploading = true, gridReturnTick = it.gridReturnTick + 1) }
            _errors.emit(null)
            logWardrobeAdd("upload_start", source)
            runCatching {
                val uploaded = drive.uploadImage(folderId, rawFile)
                val ext = if (rawFile.extension == "png") "png" else "jpg"
                val displayCache = File(drive.cacheDir, "${uploaded.id}.$ext")
                if (rawFile.absolutePath != displayCache.absolutePath) {
                    rawFile.copyTo(displayCache, overwrite = true)
                }
                rawFile.copyTo(File(drive.cacheDir, "${uploaded.id}_original.jpg"), overwrite = true)
                Triple(uploaded.id, uploaded.name, displayCache)
            }.onSuccess { (uploadedId, uploadedName, displayCache) ->
                // Stash the local cutout under a stable per-job filename so processQueuedImage
                // can find it after the raw upload completes — the final destination filename
                // uses the *cutout's* drive ID, which we don't know yet.
                val cutoutForJob = prebuiltCutout?.let { src ->
                    val dest = File(drive.cacheDir, "${uploadedId}_local_cutout.png")
                    runCatching { src.copyTo(dest, overwrite = true) }.getOrNull()
                    runCatching { src.delete() }
                    dest.takeIf { it.exists() }
                }
                val newImage = DriveImage(uploadedId, displayCache.absolutePath, uploadedName, tags = null, folderId = folderId, createdTimeMs = System.currentTimeMillis())
                // Raw row into the store — the derived view shows it immediately; no sidecar
                // yet (processQueuedImage swaps it for the finished cutout entry).
                runCatching { itemStore.upsert(folderId, newImage.toCachedItem()) }
                _progress.update { it.copy(isUploading = false, pendingJobs = it.pendingJobs + 1) }
                workQueue.send(PendingJob(newImage.driveId, folderId, cutoutForJob?.absolutePath, source))
            }.onFailure { e ->
                logWardrobeAdd("failed", source, mapOf("reason" to "upload"))
                _progress.update { it.copy(isUploading = false) }
                _errors.emit(e.message)
            }
        }
    }

    /** Gallery import batch. Routes through the review pipeline when local bg removal is on. */
    fun uploadGalleryPhotos(uris: List<Uri>, folderId: String) {
        if (uris.isEmpty()) return
        // When local-bg review is enabled, route each gallery item through the same ingest
        // pipeline so it is enqueued for the review screen one at a time. Otherwise stick with
        // the original direct-upload path (faster, no per-item user interaction).
        if (preferLocalBgRemoval && EmbeddingService.segmenter.isAvailable()) {
            scope.launch {
                _progress.update { it.copy(batchTotal = uris.size, batchDone = 0, isUploading = true) }
                _errors.emit(null)
                val cr = context.contentResolver
                uris.forEachIndexed { index, uri ->
                    _progress.update { it.copy(batchDone = index) }
                    val tempFile = File(drive.cacheDir, "gallery_${System.currentTimeMillis()}_$index.jpg")
                    runCatching {
                        cr.openInputStream(uri)?.use { it.copyTo(tempFile.outputStream()) }
                        tempFile
                    }.onSuccess { f ->
                        // Enqueue for review; ingest handles dedupe + queue routing.
                        ingest(f, folderId, skippableLocalReview = true, source = AddSource.GALLERY)
                    }.onFailure { e ->
                        _errors.emit(context.localized().getString(R.string.error_upload_failed, e.message ?: ""))
                        runCatching { tempFile.delete() }
                    }
                }
                _progress.update { it.copy(batchTotal = 0, batchDone = 0, isUploading = false) }
            }
            return
        }

        scope.launch {
            _progress.update { it.copy(batchTotal = uris.size, batchDone = 0, isUploading = true) }
            _errors.emit(null)
            val cr = context.contentResolver
            uris.forEachIndexed { index, uri ->
                _progress.update { it.copy(batchDone = index) }
                val tempFile = File(drive.cacheDir, "gallery_${System.currentTimeMillis()}.jpg")
                logWardrobeAdd("upload_start", AddSource.GALLERY)
                runCatching {
                    cr.openInputStream(uri)?.use { it.copyTo(tempFile.outputStream()) }
                    val uploaded = drive.uploadImage(folderId, tempFile)
                    val displayCache = File(drive.cacheDir, "${uploaded.id}.jpg")
                    tempFile.copyTo(displayCache, overwrite = true)
                    tempFile.copyTo(File(drive.cacheDir, "${uploaded.id}_original.jpg"), overwrite = true)
                    DriveImage(uploaded.id, displayCache.absolutePath, uploaded.name, tags = null, folderId = folderId, createdTimeMs = System.currentTimeMillis())
                }.onSuccess { newImage ->
                    // Raw row into the store — sidecars are written per item by processQueuedImage.
                    runCatching { itemStore.upsert(folderId, newImage.toCachedItem()) }
                    _progress.update { it.copy(pendingJobs = it.pendingJobs + 1) }
                    workQueue.send(PendingJob(newImage.driveId, folderId, source = AddSource.GALLERY))
                }.onFailure { e ->
                    logWardrobeAdd("failed", AddSource.GALLERY, mapOf("reason" to "upload"))
                    _errors.emit(context.localized().getString(R.string.error_upload_failed, e.message ?: ""))
                }
                tempFile.delete()
            }
            _progress.update { it.copy(batchTotal = 0, batchDone = 0, isUploading = false) }
        }
    }

    // ---------- Background processing queue ----------

    /** Drains [workQueue] serially — bg removal + tagging for each newly uploaded photo. */
    private suspend fun processQueue() {
        for (job in workQueue) {
            jobLock.acquire()
            try {
                runCatching { processQueuedImage(job) }
                    .onFailure { e ->
                        logWardrobeAdd("failed", job.source, mapOf("reason" to "exception"))
                        _errors.emit(e.message)
                    }
                _progress.update { s ->
                    s.copy(
                        pendingJobs = maxOf(0, s.pendingJobs - 1),
                        processingImageId = if (s.processingImageId == job.driveId)
                            null else s.processingImageId,
                    )
                }
                // The finished item is upserted into the store inside processQueuedImage
                // (keyed off job.folderId), so the derived grids update by themselves.
            } finally {
                jobLock.release()
            }
        }
    }

    private suspend fun processQueuedImage(job: PendingJob) {
        val rawFile = File(drive.cacheDir, "${job.driveId}_original.jpg")
        if (!rawFile.exists()) {
            logWardrobeAdd("failed", job.source, mapOf("reason" to "raw_missing"))
            return
        }
        _progress.update { it.copy(processingImageId = job.driveId) }
        // NOTE: we intentionally do NOT check if job.driveId is still in the store — a reload
        // may have replaced it with the cutout ID already (race window). We always process.

        // Step 1 — background removal. If the user produced a local cutout via the on-device
        // segmenter review (LocalBgRemovalScreen), use it as-is and skip the paid Gemini call.
        val processedFile: File = job.prebuiltCutoutPath
            ?.let { File(it) }
            ?.takeIf { it.exists() }
            ?: (gemini.removeBackground(rawFile, drive.cacheDir) ?: rawFile)

        // Step 2 — upload cutout, then rename to "{cutoutId}_cutout.png"
        val cutoutDrive = runCatching { drive.uploadAsCutout(job.folderId, processedFile) }.getOrNull()
            ?: run {
                logWardrobeAdd("failed", job.source, mapOf("reason" to "cutout_upload"))
                return
            }

        // Step 3 — upload original as "{cutoutId}_original.jpg" (best-effort)
        val originalDriveId = runCatching {
            drive.uploadAsOriginal(job.folderId, rawFile, cutoutDrive.id)
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
        runCatching { itemStore.upsert(job.folderId, finished.toCachedItem(), staleDriveId = job.driveId) }
        _progress.update { s ->
            s.copy(
                processingImageId = if (s.processingImageId == job.driveId)
                    cutoutDrive.id else s.processingImageId,
            )
        }

        // Step 7 — classify clothing tags
        val tags = gemini.classifyClothing(localCutout, geminiLanguage)
        if (tags != null) {
            finished = finished.copy(tags = tags)
            runCatching { itemStore.upsert(job.folderId, finished.toCachedItem()) }
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
        _progress.update { s ->
            if (s.processingImageId == cutoutDrive.id || s.processingImageId == job.driveId)
                s.copy(processingImageId = null) else s
        }

        // Funnel terminal: the item is fully processed and live in the wardrobe.
        logWardrobeAdd("item_live", job.source, mapOf("tagged" to (tags != null).toString()))
    }

    // ---------- Helpers ----------

    /**
     * The cross-closet snapshot for the dedupe gate (every closet + shopping), read from the
     * store the same way the wardrobe VM derives `allLocationImages`.
     */
    private suspend fun crossClosetImages(): List<DriveImage> {
        val ids = session.session.value.snapshotFolderIds
        return ids.flatMap { fid ->
            itemStore.itemsFor(fid).mapNotNull { e ->
                drive.cachedFile(e.driveId)?.let { f ->
                    DriveImage(
                        e.driveId, f.absolutePath, e.name, e.tags,
                        originalDriveId = e.originalDriveId,
                        sidecarDriveId = e.sidecarDriveId,
                        folderId = fid,
                        createdTimeMs = e.createdTimeMs,
                    )
                }
            }
        }
    }
}
