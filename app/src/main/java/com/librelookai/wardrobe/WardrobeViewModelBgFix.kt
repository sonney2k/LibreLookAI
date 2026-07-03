package com.librelookai.wardrobe

import android.util.Log
import androidx.lifecycle.viewModelScope
import com.librelookai.gemini.CutoutFixActions
import com.librelookai.gemini.fixCutoutBackground
import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

// The scan → review → apply state machine lives in [CutoutBgFixUseCase] now (§ 5 slice 6); these
// extensions stay as the public surface so the `FixCutoutBgDialog` host's `viewModel::method`
// wiring is unchanged, delegating to the use-case. The host reads progress straight off the
// VM's `cutoutBgFixProgress` passthrough (§ 5 slice 7 prune).

internal fun WardrobeViewModel.startCutoutBgFixScan(folderIds: List<String>) =
    cutoutFixUseCase.startScan(folderIds)

/** Apply fixes for the currently selected entries, or just clear state if [process] is false. */
internal fun WardrobeViewModel.continueCutoutBgFix(process: Boolean) =
    cutoutFixUseCase.continueFix(process)

internal fun WardrobeViewModel.toggleCutoutFixSelection(driveId: String) =
    cutoutFixUseCase.toggleSelection(driveId)

internal fun WardrobeViewModel.setCutoutFixSelection(ids: Set<String>) =
    cutoutFixUseCase.setSelection(ids)

internal fun WardrobeViewModel.setCutoutFixAction(
    blackToAlpha: Boolean? = null,
    despillGreen: Boolean? = null,
    feather: Boolean? = null,
    tightCrop: Boolean? = null,
) = cutoutFixUseCase.setAction(blackToAlpha, despillGreen, feather, tightCrop)

internal fun WardrobeViewModel.setCutoutFixShowAll(v: Boolean) =
    cutoutFixUseCase.setShowAll(v)

internal fun WardrobeViewModel.dismissCutoutBgFix() =
    cutoutFixUseCase.dismiss()

internal suspend fun WardrobeViewModel.fetchCutoutFixThumbnail(entry: CutoutFixEntry): File? =
    cutoutFixUseCase.fetchThumbnail(entry)

/** Detect + repair black-bg / green-halo / interior-hole issues on a single cutout, then
 *  re-upload preserving the Drive ID. Surfaces a status message via [_state.error]; UI
 *  treats it as a transient banner. Stays on the VM (touches the shared `processingImageId`/
 *  `error` slots, like `reprocessBackground`) rather than the cutout-fix state machine. */
internal fun WardrobeViewModel.fixCutoutBgForItem(
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
            bumpImageVersion(driveId)
        } catch (e: Exception) {
            Log.w(WardrobeViewModel.TAG, "Single-item cutout bg-fix failed for $driveId: ${e.message}", e)
            _state.update { it.copy(error = e.message) }
        } finally {
            _state.update { it.copy(processingImageId = null) }
            releaseJobWakeLock()
        }
    }
}
