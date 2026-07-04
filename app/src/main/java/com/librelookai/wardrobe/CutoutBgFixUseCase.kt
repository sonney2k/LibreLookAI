package com.librelookai.wardrobe

import android.util.Log
import com.librelookai.data.drive.DriveService
import com.librelookai.gemini.CutoutFixActions
import com.librelookai.gemini.CutoutIssues
import com.librelookai.gemini.detectCutoutIssues
import com.librelookai.gemini.fixCutoutBackground
import com.librelookai.service.JobLock
import com.librelookai.util.ImageEncoding
import java.io.File
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * The scan → review → apply state machine behind `FixCutoutBgDialog` (refactor § 5 slice 6,
 * extracted verbatim from the `WardrobeViewModel*BgFix` extensions). Owns the
 * [CutoutBgFixProgress] state directly; the VM mirrors it into [WardrobeUiState.cutoutBgFix] and
 * its same-named extensions are thin delegates so the dialog host's `viewModel::method` wiring is
 * unchanged. The single-item `fixCutoutBgForItem` stays on the VM — it writes the shared
 * `processingImageId`/`error` slots, like `reprocessBackground`.
 */
@Singleton
class CutoutBgFixUseCase @Inject constructor(
    private val drive: DriveService,
    private val versions: ItemVersions,
    private val jobLock: JobLock,
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    private val _progress = MutableStateFlow<CutoutBgFixProgress?>(null)
    val progress: StateFlow<CutoutBgFixProgress?> = _progress.asStateFlow()

    fun startScan(folderIds: List<String>) {
        if (folderIds.isEmpty()) return
        jobLock.acquire()
        scope.launch(Dispatchers.IO) {
            try {
                _progress.value = CutoutBgFixProgress(
                    isScanning = true,
                    totalFolders = folderIds.size,
                )
                val items = mutableListOf<CutoutFixEntry>()
                val flagged = mutableSetOf<String>()

                folderIds.forEachIndexed { idx, fid ->
                    val cutouts = runCatching {
                        drive.listAllImageFiles(fid)
                            .filter { ImageEncoding.isCutoutName(it.name) }
                    }.getOrDefault(emptyList())
                    _progress.update { s ->
                        s?.copy(totalCutouts = s.totalCutouts + cutouts.size)
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
                        _progress.update { s ->
                            s?.copy(scannedCutouts = s.scannedCutouts + 1)
                        }
                    }
                    _progress.update { s -> s?.copy(scannedFolders = idx + 1) }
                }

                val anyBlack = items.any { it.hasBlackBackground }
                val anyGreen = items.any { it.hasGreenHalo }
                _progress.value = CutoutBgFixProgress(
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
                )
            } finally {
                jobLock.release()
            }
        }
    }

    /** Apply fixes for the currently selected entries, or just clear state if [process] is false. */
    fun continueFix(process: Boolean) {
        val cur = _progress.value
        if (cur == null || !cur.awaitingConfirmation) return
        if (!process || cur.selectedIds.isEmpty()) {
            _progress.value = null
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
            _progress.value = null
            return
        }
        jobLock.acquire()
        _progress.value = CutoutBgFixProgress(
            isProcessing = true,
            processTotal = toFix.size,
        )
        scope.launch(Dispatchers.IO) {
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
                        Log.w(TAG, "Cutout bg-fix failed for ${entry.driveId}: ${e.message}")
                    }
                    done++
                    _progress.update { s -> s?.copy(processDone = done) }
                }
                // Bump version on every fixed item so Coil reloads from the updated local cache.
                versions.bump(*toFix.map { it.driveId }.toTypedArray())
                _progress.update { s -> s?.copy(isProcessing = false, isDone = true) }
            } finally {
                jobLock.release()
            }
        }
    }

    fun toggleSelection(driveId: String) {
        _progress.update { c ->
            if (c == null || !c.awaitingConfirmation) return@update c
            val next = c.selectedIds.toMutableSet()
            if (!next.add(driveId)) next.remove(driveId)
            c.copy(selectedIds = next)
        }
    }

    fun setSelection(ids: Set<String>) {
        _progress.update { c ->
            if (c == null || !c.awaitingConfirmation) return@update c
            c.copy(selectedIds = ids)
        }
    }

    fun setAction(
        blackToAlpha: Boolean? = null,
        despillGreen: Boolean? = null,
        feather: Boolean? = null,
        tightCrop: Boolean? = null,
    ) {
        _progress.update { c ->
            if (c == null || !c.awaitingConfirmation) return@update c
            c.copy(
                applyBlackToAlpha = blackToAlpha ?: c.applyBlackToAlpha,
                applyDespillGreen = despillGreen ?: c.applyDespillGreen,
                applyFeather = feather ?: c.applyFeather,
                applyTightCrop = tightCrop ?: c.applyTightCrop,
            )
        }
    }

    fun setShowAll(v: Boolean) {
        _progress.update { c -> c?.copy(showAll = v) }
    }

    fun dismiss() {
        _progress.value = null
    }

    suspend fun fetchThumbnail(entry: CutoutFixEntry): File? = withContext(Dispatchers.IO) {
        drive.cachedFile(entry.driveId)?.let { return@withContext it }
        runCatching { drive.downloadToCache(entry.driveId, entry.name) }.getOrNull()
    }

    private companion object {
        const val TAG = "RepairAndSync"
    }
}
