package com.librelookai.wardrobe

import android.app.Application
import android.net.Uri
import androidx.lifecycle.viewModelScope
import com.librelookai.R
import com.librelookai.ml.EmbeddingService
import java.io.File
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

internal fun WardrobeViewModel.uploadPhoto(rawFile: File) {
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
internal fun WardrobeViewModel.uploadPhotoInternal(
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
                    processedOutputDir = File(getApplication<Application>().cacheDir, WardrobeViewModel.QUERY_DEBUG_DIR),
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
internal fun WardrobeViewModel.routeImportAfterDedupe(
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
internal fun WardrobeViewModel.confirmDuplicateImport() {
        val dc = _state.value.duplicateCheck ?: return
        dc.processedPath?.let { runCatching { File(it).delete() } }
        _state.update { it.copy(duplicateCheck = null) }
        val routing = pendingDedupeRouting ?: DedupeRouting(skippable = true, force = false)
        pendingDedupeRouting = null
        routeImportAfterDedupe(File(dc.rawFilePath), dc.targetFolderId, routing.skippable, routing.force)
    }

    /** User cancelled the import — discard the raw file and clear the check. */
internal fun WardrobeViewModel.cancelDuplicateImport() {
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
internal fun WardrobeViewModel.proceedWithCameraUpload(rawFile: File, id: String, prebuiltCutout: File? = null) {
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
internal fun WardrobeViewModel.applyLocalBgCutout(cutoutFile: File) {
        val head = _state.value.localBgReviewQueue.firstOrNull() ?: return
        val raw = File(head.rawFilePath)
        _state.update { it.copy(localBgReviewQueue = it.localBgReviewQueue.drop(1)) }
        proceedWithCameraUpload(raw, head.targetFolderId, prebuiltCutout = cutoutFile)
    }

    /** User declined the on-device cutout — fall back to the regular Gemini path. Only valid
     *  for entries with `skippable = true` (camera + gallery; not URL). */
internal fun WardrobeViewModel.skipLocalBgReview() {
        val head = _state.value.localBgReviewQueue.firstOrNull() ?: return
        if (!head.skippable) return
        val raw = File(head.rawFilePath)
        _state.update { it.copy(localBgReviewQueue = it.localBgReviewQueue.drop(1)) }
        proceedWithCameraUpload(raw, head.targetFolderId)
    }

    /** User cancelled this import entirely — discard the raw file and advance the queue. */
internal fun WardrobeViewModel.cancelLocalBgReview() {
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
internal fun WardrobeViewModel.importFromUrl(url: String) {
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
internal fun WardrobeViewModel.confirmUrlImportPick(absoluteImageUrl: String) {
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

internal fun WardrobeViewModel.cancelUrlImport() {
        _state.update { it.copy(urlImportPicker = null) }
    }

internal fun WardrobeViewModel.uploadGalleryPhotos(uris: List<Uri>) {
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

