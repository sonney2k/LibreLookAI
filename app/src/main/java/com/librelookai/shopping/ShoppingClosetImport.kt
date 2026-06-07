package com.librelookai.shopping
import com.librelookai.util.localized

import android.app.Application
import android.net.Uri
import android.util.Log
import androidx.lifecycle.viewModelScope
import com.librelookai.R
import com.librelookai.data.drive.DriveRepository
import com.librelookai.data.drive.upsertSidecar
import com.librelookai.gemini.classifyClothing
import com.librelookai.util.Analytics
import com.librelookai.wardrobe.DriveImage
import com.librelookai.wardrobe.ItemSidecar
import com.librelookai.wardrobe.UrlImportPickerState
import com.librelookai.wardrobe.WardrobeViewModel
import com.librelookai.wardrobe.WebProductFetcher
import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

internal fun ShoppingClosetViewModel.addFromCamera(rawFile: File) {
        viewModelScope.launch {
            val folderId = ensureFolder() ?: run {
                runCatching { rawFile.delete() }
                return@launch
            }
            uploadRaw(rawFile, folderId, "camera")
        }
    }

    /**
     * Adopt a Similarity Finder query photo (lives under `cacheDir/shop_queries/` and is owned by
     * [ShoppingHelperViewModel]) into the shopping wishlist. Copies the file out of the query
     * cache before handing it to [uploadRaw] so the caller can keep using the original to display
     * the active query.
     */
internal fun ShoppingClosetViewModel.importQuery(queryRawPath: String) {
        viewModelScope.launch {
            val source = File(queryRawPath)
            if (!source.exists()) {
                _state.update { it.copy(error = getApplication<Application>().localized().getString(R.string.error_query_photo_missing)) }
                return@launch
            }
            val folderId = ensureFolder() ?: return@launch
            val staged = withContext(Dispatchers.IO) {
                val tempFile = File(drive.cacheDir, "shop_query_${System.currentTimeMillis()}.jpg")
                runCatching { source.copyTo(tempFile, overwrite = true) }.getOrNull()
            } ?: run {
                _state.update { it.copy(error = getApplication<Application>().localized().getString(R.string.error_import_query_failed)) }
                return@launch
            }
            uploadRaw(staged, folderId, "similarity")
        }
    }

internal fun ShoppingClosetViewModel.addFromGallery(uris: List<Uri>) {
        if (uris.isEmpty()) return
        viewModelScope.launch {
            val folderId = ensureFolder() ?: return@launch
            val cr = getApplication<Application>().contentResolver
            uris.forEach { uri ->
                val tempFile = File(drive.cacheDir, "shop_gallery_${System.currentTimeMillis()}.jpg")
                runCatching {
                    cr.openInputStream(uri)?.use { it.copyTo(tempFile.outputStream()) }
                    uploadRaw(tempFile, folderId, "gallery")
                }.onFailure { e ->
                    Log.w(ShoppingClosetViewModel.TAG, "gallery import failed", e)
                    _state.update { it.copy(error = getApplication<Application>().localized().getString(R.string.error_upload_failed, e.message ?: "")) }
                    runCatching { tempFile.delete() }
                }
            }
        }
    }

internal fun ShoppingClosetViewModel.addFromUrl(url: String) {
        if (url.isBlank()) return
        viewModelScope.launch {
            val folderId = ensureFolder() ?: return@launch
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
            _state.update {
                it.copy(
                    isUploading = false,
                    urlImportPicker = UrlImportPickerState(
                        pageUrl = result.pageUrl,
                        candidates = result.candidates,
                        targetFolderId = folderId,
                    ),
                )
            }
        }
    }

internal fun ShoppingClosetViewModel.confirmUrlImportPick(absoluteImageUrl: String) {
        val picker = _state.value.urlImportPicker ?: return
        val folderId = picker.targetFolderId ?: return
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
            uploadRaw(image, folderId, "url")
        }
    }

internal fun ShoppingClosetViewModel.cancelUrlImport() {
        _state.update { it.copy(urlImportPicker = null) }
    }

    /** Common path: upload [rawFile] to Drive, queue for bg removal + tagging. [source] attributes
     *  the origin (camera/gallery/url/similarity) for the shopping-add funnel. */
internal suspend fun ShoppingClosetViewModel.uploadRaw(rawFile: File, folderId: String, source: String = "camera") {
        _state.update { it.copy(isUploading = true, error = null) }
        runCatching {
            val uploaded = drive.uploadImage(folderId, rawFile)
            val ext = if (rawFile.extension == "png") "png" else "jpg"
            val displayCache = File(drive.cacheDir, "${uploaded.id}.$ext")
            if (rawFile.absolutePath != displayCache.absolutePath) {
                rawFile.copyTo(displayCache, overwrite = true)
            }
            rawFile.copyTo(File(drive.cacheDir, "${uploaded.id}_original.jpg"), overwrite = true)
            DriveImage(uploaded.id, displayCache.absolutePath, uploaded.name, tags = null, folderId = folderId, createdTimeMs = System.currentTimeMillis())
        }.onSuccess { newImage ->
            Analytics.event("shopping_item_added", mapOf("source" to source))
            _state.update { it.copy(
                isUploading = false,
                items = listOf(newImage) + it.items,
                pendingJobs = it.pendingJobs + 1,
            ) }
            saveLocalCache(folderId, _state.value.items)
            workQueue.send(ShoppingPendingJob(newImage.driveId))
        }.onFailure { e ->
            Log.w(ShoppingClosetViewModel.TAG, "shopping upload failed", e)
            _state.update { it.copy(isUploading = false, error = e.message) }
            runCatching { rawFile.delete() }
        }
    }

    // ---------- Background processing ----------

internal suspend fun ShoppingClosetViewModel.processQueue() {
        for (job in workQueue) {
            runCatching { processQueuedItem(job) }
                .onFailure { e -> _state.update { it.copy(error = e.message) } }
            _state.update { it.copy(pendingJobs = maxOf(0, it.pendingJobs - 1)) }
            shoppingFolderId?.let { fid -> saveLocalCache(fid, _state.value.items) }
        }
    }

internal suspend fun ShoppingClosetViewModel.processQueuedItem(job: ShoppingPendingJob) {
        val folderId = shoppingFolderId ?: return
        val rawFile = File(drive.cacheDir, "${job.driveId}_original.jpg")
        if (!rawFile.exists()) return

        // Step 1 — bg removal (fall back to raw on failure).
        val processedFile = gemini.removeBackground(rawFile, drive.cacheDir) ?: rawFile

        // Step 2 — upload cutout, rename to "{cutoutId}_cutout.png".
        val cutoutDrive = runCatching {
            val uploaded = drive.uploadImage(folderId, processedFile)
            drive.renameFile(uploaded.id, "${uploaded.id}${DriveRepository.CUTOUT_SUFFIX}")
            uploaded.copy(name = "${uploaded.id}${DriveRepository.CUTOUT_SUFFIX}")
        }.getOrNull() ?: return

        // Step 3 — upload original as "{cutoutId}_original.jpg" (best effort).
        val originalDriveId = runCatching {
            drive.uploadImageWithName(
                folderId, rawFile, "${cutoutDrive.id}${DriveRepository.ORIGINAL_SUFFIX}",
            ).id
        }.getOrNull()

        // Step 4 — local caches: cutout + original (must precede deleteFile, which clears the local _original.jpg).
        val localCutout = File(drive.cacheDir, "${cutoutDrive.id}.png")
        if (processedFile.absolutePath != localCutout.absolutePath) {
            processedFile.copyTo(localCutout, overwrite = true)
        }
        rawFile.copyTo(File(drive.cacheDir, "${cutoutDrive.id}_original.jpg"), overwrite = true)

        // Step 5 — delete the temporary raw upload.
        runCatching { drive.deleteFile(job.driveId) }

        // Step 6 — replace the in-memory entry (match raw or cutout id, like wardrobe does).
        _state.update { s ->
            s.copy(items = s.items.map { img ->
                if (img.driveId == job.driveId || img.driveId == cutoutDrive.id) img.copy(
                    driveId = cutoutDrive.id,
                    name = cutoutDrive.name,
                    localPath = localCutout.absolutePath,
                    version = System.currentTimeMillis(),
                    originalDriveId = originalDriveId,
                ) else img
            })
        }

        // Step 7 — classify tags.
        val tags = gemini.classifyClothing(localCutout, geminiLanguage)
        if (tags != null) {
            _state.update { s ->
                s.copy(items = s.items.map { img ->
                    if (img.driveId == cutoutDrive.id) img.copy(tags = tags) else img
                })
            }
        }

        // Step 8 — sidecar.
        val sidecarJson = gson.toJson(ItemSidecar(tags, originalDriveId))
        runCatching {
            drive.upsertSidecar(
                folderId, "${cutoutDrive.id}${DriveRepository.SIDECAR_SUFFIX}", sidecarJson,
            )
        }.onSuccess { sidecarId ->
            _state.update { s ->
                s.copy(items = s.items.map { img ->
                    if (img.driveId == cutoutDrive.id) img.copy(sidecarDriveId = sidecarId) else img
                })
            }
        }
    }

    // ---------- Move + delete ----------

    /**
     * Moves [driveIds] from `_shopping/` to [targetFolderId] (a regular closet). Tags + cutout +
     * original + sidecar are preserved verbatim — Drive only changes parents, no re-upload, no
     * re-tagging. [onMoved] fires once Drive moves complete (called even for partial success); the
     * caller is responsible for telling [WardrobeViewModel] to reload the destination closet so
     * the items appear there.
     */
