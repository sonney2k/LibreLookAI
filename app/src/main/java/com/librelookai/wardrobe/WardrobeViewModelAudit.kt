package com.librelookai.wardrobe

import android.app.Application
import android.util.Log
import androidx.lifecycle.viewModelScope
import com.librelookai.data.drive.DriveFileDto
import com.librelookai.data.drive.DriveRepository
import com.librelookai.data.drive.listAllImageFiles
import com.librelookai.data.drive.listSidecarFiles
import com.librelookai.data.drive.loadFileContent
import com.librelookai.data.drive.upsertSidecar
import com.librelookai.gemini.ClothingTags
import com.librelookai.gemini.classifyClothing
import com.librelookai.ml.EmbeddingService
import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

private const val TAG = "RepairAndSync"

internal fun WardrobeViewModel.startRepairAndRefresh(folderIds: List<String>) {
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
                                    bytes in 1..WardrobeViewModel.SIDECAR_EMPTY_MAX -> {
                                        // Too small to contain tags — skip download
                                        Log.d(TAG, "Empty sidecar for cutout ${cutout.id} (size=${bytes}B)")
                                        needSidecar.add(AuditCutoutItem(fid, cutout.id, "${cutout.id}${DriveRepository.CUTOUT_SUFFIX}"))
                                    }
                                    bytes >= WardrobeViewModel.SIDECAR_FULL_MIN -> {
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

                // ---- Phase 1b: detect visually-similar cutouts using the on-device index. ----
                _state.update { it.copy(auditProgress = it.auditProgress?.copy(isScanningDuplicates = true)) }
                val duplicates = detectDuplicateCutouts(folderIds)
                _state.update { it.copy(auditProgress = it.auditProgress?.copy(isScanningDuplicates = false)) }
                Log.d(TAG, "Duplicate-detection complete — ${duplicates.size} cutout(s) flagged")

                pendingAudit = AuditIntermediate(folderIds, orphaned, rawImages, needSidecar, duplicates)
                val previewItems = buildList<AuditFileEntry> {
                    orphaned.forEach { add(AuditFileEntry(it.driveId, it.name, it.folderId, AuditKind.ORPHANED_ORIGINAL)) }
                    rawImages.forEach { add(AuditFileEntry(it.driveId, it.name, it.folderId, AuditKind.RAW)) }
                    needSidecar.forEach { add(AuditFileEntry(it.cutoutDriveId, it.cutoutName, it.folderId, AuditKind.NEEDS_SIDECAR)) }
                    duplicates.forEach { d ->
                        add(AuditFileEntry(d.cutoutDriveId, d.cutoutName, d.folderId, AuditKind.DUPLICATE,
                            similarTo = d.similarTo, topScore = d.topScore))
                    }
                }
                // Default selection: every fix-needed item (process), but no duplicates (delete is destructive).
                val defaultSelection = previewItems.filter { it.kind != AuditKind.DUPLICATE }.map { it.driveId }.toSet()
                _state.update { it.copy(
                    auditProgress = AuditProgress(
                        awaitingConfirmation = true,
                        renamedCount = totalRenamed,
                        orphanedOriginals = orphaned.size,
                        rawImages = rawImages.size,
                        sidecarNeeded = needSidecar.size,
                        duplicates = duplicates.size,
                        items = previewItems,
                        selectedAuditIds = defaultSelection,
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
     * tagging-only for cutouts missing sidecars. When [clearCache] is true, also
     * wipes the local image cache so everything is re-downloaded from Drive on reload;
     * otherwise the existing cache is kept and only the metadata snapshot is refreshed.
     */
internal fun WardrobeViewModel.continueRepairProcessing(process: Boolean, clearCache: Boolean = false) {
        val audit = pendingAudit ?: run {
            _state.update { it.copy(auditProgress = null) }
            if (clearCache) clearCacheAndRefresh() else loadImages()
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
        val filteredDuplicates = audit.duplicates.filter      { it.cutoutDriveId in selected }
        val anySelected = filteredOrphaned.isNotEmpty() || filteredRaw.isNotEmpty() ||
            filteredSidecar.isNotEmpty() || filteredDuplicates.isNotEmpty()

        if (!process || !anySelected) {
            Log.d(TAG, "User skipped processing (process=$process, selected=${selected.size}, clearCache=$clearCache) — reloading")
            _state.update { it.copy(auditProgress = null) }
            viewModelScope.launch(Dispatchers.IO) {
                if (clearCache) {
                    audit.folderIds.forEach { localCacheFile(it).delete() }
                    getApplication<Application>().filesDir.resolve("wardrobe")
                        .listFiles()?.forEach { it.delete() }
                }
                withContext(Dispatchers.Main) { loadImages() }
            }
            return
        }

        val processTotal = filteredOrphaned.size + filteredRaw.size + filteredSidecar.size + filteredDuplicates.size
        Log.d(TAG, "Processing started — ${filteredOrphaned.size} orphaned original(s), ${filteredRaw.size} raw image(s), ${filteredSidecar.size} cutout(s) needing tagging, ${filteredDuplicates.size} duplicate(s) to delete (selection-filtered)")
        _state.update { it.copy(auditProgress = AuditProgress(isProcessing = true, processTotal = processTotal)) }
        acquireJobWakeLock()

        viewModelScope.launch(Dispatchers.IO) {
            try {
                var done = 0
                // Short-circuit the audit if any item runs out of credits, so we
                // don't make N pointless proxy calls. Global dialog still appears
                // via CreditsEvents emitted from throwIf402.
                var creditsExhausted = false

                // --- Orphaned originals: bg removal + cutout upload + tag + sidecar ---
                filteredOrphaned.forEach { item ->
                    if (creditsExhausted) return@forEach
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
                        if (e is com.librelookai.billing.InsufficientCreditsException) creditsExhausted = true
                        Log.e(TAG, "Failed processing orphaned original ${item.driveId}: ${e.message}", e)
                    }
                    done++
                    _state.update { s -> s.copy(auditProgress = s.auditProgress?.copy(processDone = done)) }
                }

                // --- Raw/unknown images: full AI processing (same as orphaned originals) ---
                filteredRaw.forEach { item ->
                    if (creditsExhausted) return@forEach
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
                        if (e is com.librelookai.billing.InsufficientCreditsException) creditsExhausted = true
                        Log.e(TAG, "Failed processing raw image ${item.driveId}: ${e.message}", e)
                    }
                    done++
                    _state.update { s -> s.copy(auditProgress = s.auditProgress?.copy(processDone = done)) }
                }

                // --- Cutouts missing sidecars or with empty tags: tag from cutout + write sidecar ---
                filteredSidecar.forEach { item ->
                    if (creditsExhausted) return@forEach
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
                        if (e is com.librelookai.billing.InsufficientCreditsException) creditsExhausted = true
                        Log.e(TAG, "Failed tagging cutout ${item.cutoutDriveId}: ${e.message}", e)
                    }
                    done++
                    _state.update { s -> s.copy(auditProgress = s.auditProgress?.copy(processDone = done)) }
                }

                // --- Duplicates: delete the cutout + original + sidecar from Drive ---
                filteredDuplicates.forEach { item ->
                    Log.d(TAG, "Deleting duplicate cutout: ${item.cutoutName} (${item.cutoutDriveId})")
                    runCatching {
                        // Find the in-memory item to grab its original + sidecar Drive IDs
                        val existing = _state.value.images.find { it.driveId == item.cutoutDriveId }
                        existing?.originalDriveId?.let { runCatching { drive.deleteFile(it) } }
                        existing?.sidecarDriveId?.let { runCatching { drive.deleteFile(it) } }
                        drive.deleteFile(item.cutoutDriveId)
                        EmbeddingService.index.remove(item.cutoutDriveId)
                    }.onFailure { e ->
                        Log.e(TAG, "Failed deleting duplicate ${item.cutoutDriveId}: ${e.message}", e)
                    }
                    done++
                    _state.update { s -> s.copy(auditProgress = s.auditProgress?.copy(processDone = done)) }
                }
                if (filteredDuplicates.isNotEmpty()) {
                    runCatching { EmbeddingService.index.save() }
                }

                Log.d(TAG, "Processing complete (clearCache=$clearCache) — reloading")
                if (clearCache) {
                    audit.folderIds.forEach { localCacheFile(it).delete() }
                    getApplication<Application>().filesDir.resolve("wardrobe")
                        .listFiles()?.forEach { it.delete() }
                }

                _state.update { it.copy(auditProgress = AuditProgress(isDone = true)) }
                withContext(Dispatchers.Main) { loadImages() }
            } finally {
                releaseJobWakeLock()
            }
        }
    }

    /**
     * Embed every cached cutout across [folderIds] (skipping those already in the index) and
     * search the index for above-threshold peers, excluding the cutout itself. Each entry in the
     * returned list represents one cutout that has at least one visually-similar peer.
     *
     * Cutouts not present in the local cache are skipped — embedding requires the cutout file to
     * be downloaded, which would be expensive during a scan and is not needed: those cutouts will
     * be handled the next time the user opens the wardrobe.
     */
internal suspend fun WardrobeViewModel.detectDuplicateCutouts(folderIds: List<String>): List<AuditDuplicateItem> {
        if (!EmbeddingService.isModelAvailable()) return emptyList()
        // Use the cross-closet snapshot so the duplicate scan covers every configured closet
        // (matches the contract with [startRepairAndRefresh], which passes every folderId).
        refreshAllLocationImagesState()
        val crossClosetImages = _state.value.allLocationImages
            .filter { folderIds.isEmpty() || it.folderId in folderIds }
        EmbeddingService.syncIndex(crossClosetImages, drive.cacheDir)
        val byDriveId = crossClosetImages.associateBy { it.driveId }
        val scope = byDriveId.keys
        // Repair & Sync uses a stricter threshold than capture/import dedupe: an unattended scan
        // over the entire wardrobe surfaces far more near-collisions than a one-shot capture, so
        // we want only very confident matches to appear in the delete-candidates list.
        val repairThreshold = maxOf(dedupeThreshold, WardrobeViewModel.REPAIR_DUPE_THRESHOLD)
        val clusters = EmbeddingService.findDuplicateClusters(repairThreshold, restrictToIds = scope)
        if (clusters.isEmpty()) return emptyList()
        // For each anchor with similar peers, build an audit entry. Anchors with the same set of
        // peers are equally valid candidates for deletion — surface them all so the user picks.
        return clusters.entries.mapNotNull { (anchorId, peers) ->
            val img = byDriveId[anchorId] ?: return@mapNotNull null
            AuditDuplicateItem(
                folderId = img.folderId.ifEmpty { folderIds.firstOrNull().orEmpty() },
                cutoutDriveId = anchorId,
                cutoutName = img.name,
                similarTo = peers.map { it.driveId },
                topScore = peers.firstOrNull()?.score ?: 0f,
            )
        }.sortedByDescending { it.topScore }
    }

    /** Clears the audit result state (call after user dismisses the "done" message). */
internal fun WardrobeViewModel.dismissAuditResult() {
        _state.update { it.copy(auditProgress = null) }
    }

    /** Toggle whether [driveId] will be processed by Repair & Sync. No-op outside the confirmation step. */
internal fun WardrobeViewModel.toggleAuditSelection(driveId: String) {
        _state.update { s ->
            val a = s.auditProgress ?: return@update s
            if (!a.awaitingConfirmation) return@update s
            val next = a.selectedAuditIds.toMutableSet()
            if (!next.add(driveId)) next.remove(driveId)
            s.copy(auditProgress = a.copy(selectedAuditIds = next))
        }
    }

    /** Replace the audit selection (used by Select all / Deselect all). */
internal fun WardrobeViewModel.setAuditSelection(driveIds: Set<String>) {
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
internal suspend fun WardrobeViewModel.fetchAuditThumbnail(entry: AuditFileEntry): File? = withContext(Dispatchers.IO) {
        // Prefer an already-cached file (especially for NEEDS_SIDECAR cutouts, which the
        // wardrobe load may have downloaded into cache as `{id}.png`).
        drive.cachedFile(entry.driveId)?.let { return@withContext it }
        runCatching { drive.downloadToCache(entry.driveId, entry.name) }.getOrNull()
    }

    // ---------- Fix cutout backgrounds (Settings → Data) ----------

    /** Scans every cutout in [folderIds] for black-background / green-halo issues and pauses
     *  with awaitingConfirmation=true. The user reviews the preview grid (flagged-only by
     *  default; toggle to show all) and selects which to fix. */
