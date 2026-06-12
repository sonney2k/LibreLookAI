package com.librelookai.wardrobe
import com.librelookai.util.localized

import android.app.Application
import android.net.Uri
import androidx.lifecycle.viewModelScope
import com.librelookai.R
import java.io.File
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

// The upload / dedupe / local-bg-review machinery lives in [ItemIngestionPipeline]
// (§ 5 slice 5). These extensions keep the screens' existing VM-facing API: they resolve
// the target folder (VM state) and delegate; progress comes back via the init collector.

internal fun WardrobeViewModel.uploadPhoto(rawFile: File) {
        val id = (defaultImportFolderId ?: folderId) ?: run {
            _state.update { it.copy(view = WardrobeView.GRID) }
            return
        }
        pipeline.ingest(rawFile, id, skippableLocalReview = true, source = AddSource.CAMERA)
    }

    /** User confirmed they want to import despite a similarity match. */
internal fun WardrobeViewModel.confirmDuplicateImport() = pipeline.confirmDuplicateImport()

    /** User cancelled the import — discard the raw file and clear the check. */
internal fun WardrobeViewModel.cancelDuplicateImport() = pipeline.cancelDuplicateImport()

    // ---------- Local background-removal review ----------

    /** User accepted the on-device cutout for the head item of the review queue. */
internal fun WardrobeViewModel.applyLocalBgCutout(cutoutFile: File) = pipeline.applyLocalBgCutout(cutoutFile)

    /** User declined the on-device cutout — fall back to the regular Gemini path. */
internal fun WardrobeViewModel.skipLocalBgReview() = pipeline.skipLocalBgReview()

    /** User cancelled this import entirely — discard the raw file and advance the queue. */
internal fun WardrobeViewModel.cancelLocalBgReview() = pipeline.cancelLocalBgReview()

    // ---------- Upload from URL ----------

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
                        error = getApplication<Application>().localized().getString(R.string.url_import_failed),
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
                        error = getApplication<Application>().localized().getString(R.string.url_import_failed),
                    )
                }
                return@launch
            }
            _state.update { it.copy(urlImportPicker = null) }
            // URL imports always go through on-device review so the user can refine the seed.
            pipeline.ingest(
                rawFile = image,
                folderId = targetId,
                skippableLocalReview = false,
                forceLocalReview = true,
                source = AddSource.URL,
            )
        }
    }

internal fun WardrobeViewModel.cancelUrlImport() {
        _state.update { it.copy(urlImportPicker = null) }
    }

    // ---------- Upload from gallery ----------

internal fun WardrobeViewModel.uploadGalleryPhotos(uris: List<Uri>) {
        if (uris.isEmpty()) return
        val id = (defaultImportFolderId ?: folderId) ?: return
        pipeline.uploadGalleryPhotos(uris, id)
    }
