package com.librelookai.shopping
import com.librelookai.util.localized

import android.app.Application
import android.net.Uri
import android.util.Log
import androidx.lifecycle.viewModelScope
import com.librelookai.feature.shopping.R
import com.librelookai.core.designsystem.R as DsR
import com.librelookai.data.drive.DriveService
import com.librelookai.util.Analytics
import com.librelookai.wardrobe.DriveImage
import com.librelookai.wardrobe.UrlImportPickerState
import com.librelookai.wardrobe.WebProductFetcher
import com.librelookai.wardrobe.toCachedItem
import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

fun ShoppingClosetViewModel.addFromCamera(rawFile: File) {
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
fun ShoppingClosetViewModel.importQuery(queryRawPath: String) {
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

fun ShoppingClosetViewModel.addFromGallery(uris: List<Uri>) {
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
                    _state.update { it.copy(error = getApplication<Application>().localized().getString(DsR.string.error_upload_failed, e.message ?: "")) }
                    runCatching { tempFile.delete() }
                }
            }
        }
    }

fun ShoppingClosetViewModel.addFromUrl(url: String) {
        if (url.isBlank()) return
        viewModelScope.launch {
            val folderId = ensureFolder() ?: return@launch
            _state.update { it.copy(isUploading = true, error = null) }
            val result = WebProductFetcher.fetchImageCandidates(url)
            if (result == null) {
                _state.update {
                    it.copy(
                        isUploading = false,
                        error = getApplication<Application>().localized().getString(DsR.string.url_import_failed),
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

fun ShoppingClosetViewModel.confirmUrlImportPick(absoluteImageUrl: String) {
        val picker = _state.value.urlImportPicker ?: return
        val folderId = picker.targetFolderId ?: return
        viewModelScope.launch {
            _state.update { it.copy(urlImportPicker = picker.copy(isDownloading = true)) }
            val image = WebProductFetcher.downloadImage(absoluteImageUrl, picker.pageUrl, drive.cacheDir)
            if (image == null) {
                _state.update {
                    it.copy(
                        urlImportPicker = picker.copy(isDownloading = false),
                        error = getApplication<Application>().localized().getString(DsR.string.url_import_failed),
                    )
                }
                return@launch
            }
            _state.update { it.copy(urlImportPicker = null) }
            uploadRaw(image, folderId, "url")
        }
    }

fun ShoppingClosetViewModel.cancelUrlImport() {
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
            // Raw placeholder row into the store — the derived wishlist shows it via
            // invalidation; the queue swaps it for the finished cutout entry.
            runCatching { itemStore.upsert(folderId, newImage.toCachedItem()) }
            _state.update { it.copy(isUploading = false) }
            ingestionQueue.enqueue(newImage.driveId, folderId)
        }.onFailure { e ->
            Log.w(ShoppingClosetViewModel.TAG, "shopping upload failed", e)
            _state.update { it.copy(isUploading = false, error = e.message) }
            runCatching { rawFile.delete() }
        }
    }

    // (The background bg-removal + tagging worker is the [ShoppingIngestionQueue] singleton —
    // § 5 slice 9; [uploadRaw] enqueues, the VM mirrors `pendingJobs`/`errors` in init.)

    // ---------- Move + delete ----------

    /**
     * Moves [driveIds] from `_shopping/` to [targetFolderId] (a regular closet). Tags + cutout +
     * original + sidecar are preserved verbatim — Drive only changes parents, no re-upload, no
     * re-tagging. [onMoved] fires once Drive moves complete (called even for partial success); the
     * caller is responsible for telling the wardrobe to reload the destination closet so
     * the items appear there.
     */
