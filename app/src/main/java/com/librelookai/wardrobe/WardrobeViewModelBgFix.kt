package com.librelookai.wardrobe

import android.util.Log
import androidx.lifecycle.viewModelScope
import com.librelookai.data.drive.DriveRepository
import com.librelookai.data.drive.listAllImageFiles
import com.librelookai.gemini.CutoutFixActions
import com.librelookai.gemini.CutoutIssues
import com.librelookai.gemini.detectCutoutIssues
import com.librelookai.gemini.fixCutoutBackground
import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

internal fun WardrobeViewModel.startCutoutBgFixScan(folderIds: List<String>) {
        if (folderIds.isEmpty()) return
        acquireJobWakeLock()
        viewModelScope.launch(Dispatchers.IO) {
            try {
                _state.update {
                    it.copy(cutoutBgFix = CutoutBgFixProgress(
                        isScanning = true,
                        totalFolders = folderIds.size,
                    ))
                }
                val items = mutableListOf<CutoutFixEntry>()
                val flagged = mutableSetOf<String>()

                folderIds.forEachIndexed { idx, fid ->
                    val cutouts = runCatching {
                        drive.listAllImageFiles(fid)
                            .filter { it.name.endsWith(DriveRepository.CUTOUT_SUFFIX) }
                    }.getOrDefault(emptyList())
                    _state.update { s ->
                        s.copy(cutoutBgFix = s.cutoutBgFix?.copy(
                            totalCutouts = s.cutoutBgFix.totalCutouts + cutouts.size,
                        ))
                    }
                    cutouts.forEach { cf ->
                        val local = drive.cachedFile(cf.id)
                            ?: runCatching { drive.downloadToCache(cf.id, cf.name) }.getOrNull()
                        if (local != null) {
                            val issues = runCatching { detectCutoutIssues(local) }
                                .getOrDefault(CutoutIssues(false, false))
                            val entry = CutoutFixEntry(
                                driveId = cf.id,
                                name = cf.name,
                                folderId = fid,
                                hasBlackBackground = issues.hasBlackBackground,
                                hasGreenHalo = issues.hasGreenHalo,
                            )
                            items.add(entry)
                            if (issues.any) flagged.add(cf.id)
                        }
                        _state.update { s ->
                            s.copy(cutoutBgFix = s.cutoutBgFix?.copy(
                                scannedCutouts = s.cutoutBgFix.scannedCutouts + 1,
                            ))
                        }
                    }
                    _state.update { s ->
                        s.copy(cutoutBgFix = s.cutoutBgFix?.copy(scannedFolders = idx + 1))
                    }
                }

                val anyBlack = items.any { it.hasBlackBackground }
                val anyGreen = items.any { it.hasGreenHalo }
                _state.update {
                    it.copy(cutoutBgFix = CutoutBgFixProgress(
                        awaitingConfirmation = true,
                        items = items.toList(),
                        flaggedIds = flagged.toSet(),
                        selectedIds = flagged.toSet(),
                        // When nothing is flagged, default to "show all" so the dialog stays
                        // useful — the user can still hand-pick clean cutouts to re-process.
                        showAll = flagged.isEmpty(),
                        applyBlackToAlpha = anyBlack,
                        applyDespillGreen = anyGreen,
                        totalCutouts = items.size,
                        scannedCutouts = items.size,
                    ))
                }
            } finally {
                releaseJobWakeLock()
            }
        }
    }

    /** Apply fixes for the currently selected entries, or just clear state if [process] is false. */
internal fun WardrobeViewModel.continueCutoutBgFix(process: Boolean) {
        val cur = _state.value.cutoutBgFix
        if (cur == null || !cur.awaitingConfirmation) return
        if (!process || cur.selectedIds.isEmpty()) {
            _state.update { it.copy(cutoutBgFix = null) }
            return
        }
        val byId = cur.items.associateBy { it.driveId }
        val toFix = cur.selectedIds.mapNotNull { byId[it] }
        val actions = CutoutFixActions(
            blackToAlpha = cur.applyBlackToAlpha,
            despillGreen = cur.applyDespillGreen,
            feather = cur.applyFeather,
            tightCrop = cur.applyTightCrop,
        )
        if (toFix.isEmpty() || !actions.any) {
            _state.update { it.copy(cutoutBgFix = null) }
            return
        }
        acquireJobWakeLock()
        _state.update {
            it.copy(cutoutBgFix = CutoutBgFixProgress(
                isProcessing = true,
                processTotal = toFix.size,
            ))
        }
        viewModelScope.launch(Dispatchers.IO) {
            try {
                var done = 0
                toFix.forEach { entry ->
                    runCatching {
                        val local = drive.cachedFile(entry.driveId)
                            ?: drive.downloadToCache(entry.driveId, entry.name)
                            ?: return@runCatching
                        val tmp = File(drive.cacheDir, "${entry.driveId}_fix.png")
                        fixCutoutBackground(local, tmp, actions)
                        drive.updateImage(entry.driveId, tmp)
                        val cached = File(drive.cacheDir, "${entry.driveId}.png")
                        tmp.copyTo(cached, overwrite = true)
                        tmp.delete()
                    }.onFailure { e ->
                        Log.w(WardrobeViewModel.TAG, "Cutout bg-fix failed for ${entry.driveId}: ${e.message}")
                    }
                    done++
                    _state.update { s ->
                        s.copy(cutoutBgFix = s.cutoutBgFix?.copy(processDone = done))
                    }
                }
                // Bump version on every fixed item so Coil reloads from the updated local cache.
                val fixedIds = toFix.map { it.driveId }.toSet()
                _state.update { s ->
                    s.copy(images = s.images.map {
                        if (it.driveId in fixedIds) it.copy(version = it.version + 1) else it
                    })
                }
                _state.update { s ->
                    s.copy(cutoutBgFix = s.cutoutBgFix?.copy(
                        isProcessing = false,
                        isDone = true,
                    ))
                }
            } finally {
                releaseJobWakeLock()
            }
        }
    }

internal fun WardrobeViewModel.toggleCutoutFixSelection(driveId: String) {
        _state.update { s ->
            val c = s.cutoutBgFix ?: return@update s
            if (!c.awaitingConfirmation) return@update s
            val next = c.selectedIds.toMutableSet()
            if (!next.add(driveId)) next.remove(driveId)
            s.copy(cutoutBgFix = c.copy(selectedIds = next))
        }
    }

internal fun WardrobeViewModel.setCutoutFixSelection(ids: Set<String>) {
        _state.update { s ->
            val c = s.cutoutBgFix ?: return@update s
            if (!c.awaitingConfirmation) return@update s
            s.copy(cutoutBgFix = c.copy(selectedIds = ids))
        }
    }

internal fun WardrobeViewModel.setCutoutFixAction(
        blackToAlpha: Boolean? = null,
        despillGreen: Boolean? = null,
        feather: Boolean? = null,
        tightCrop: Boolean? = null,
    ) {
        _state.update { s ->
            val c = s.cutoutBgFix ?: return@update s
            if (!c.awaitingConfirmation) return@update s
            s.copy(cutoutBgFix = c.copy(
                applyBlackToAlpha = blackToAlpha ?: c.applyBlackToAlpha,
                applyDespillGreen = despillGreen ?: c.applyDespillGreen,
                applyFeather = feather ?: c.applyFeather,
                applyTightCrop = tightCrop ?: c.applyTightCrop,
            ))
        }
    }

internal fun WardrobeViewModel.setCutoutFixShowAll(v: Boolean) {
        _state.update { s ->
            val c = s.cutoutBgFix ?: return@update s
            s.copy(cutoutBgFix = c.copy(showAll = v))
        }
    }

internal fun WardrobeViewModel.dismissCutoutBgFix() {
        _state.update { it.copy(cutoutBgFix = null) }
    }

    /** Detect + repair black-bg / green-halo / interior-hole issues on a single cutout, then
     *  re-upload preserving the Drive ID. Surfaces a status message via [_state.error]; UI
     *  treats it as a transient banner. */
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
                _state.update { s ->
                    s.copy(images = s.images.map {
                        if (it.driveId == driveId) it.copy(version = it.version + 1) else it
                    })
                }
            } catch (e: Exception) {
                Log.w(WardrobeViewModel.TAG, "Single-item cutout bg-fix failed for $driveId: ${e.message}", e)
                _state.update { it.copy(error = e.message) }
            } finally {
                _state.update { it.copy(processingImageId = null) }
                releaseJobWakeLock()
            }
        }
    }

internal suspend fun WardrobeViewModel.fetchCutoutFixThumbnail(entry: CutoutFixEntry): File? = withContext(Dispatchers.IO) {
        drive.cachedFile(entry.driveId)?.let { return@withContext it }
        runCatching { drive.downloadToCache(entry.driveId, entry.name) }.getOrNull()
    }

    /** Ensures the pre-cutout original for [cutoutDriveId] is cached at the canonical
     *  `${cutoutDriveId}_original.jpg` path and returns its absolute path, or null if no
     *  original exists or the download failed. */
