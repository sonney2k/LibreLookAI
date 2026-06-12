package com.librelookai.wardrobe
import com.librelookai.util.localized
import com.librelookai.gemini.classifyClothing

import android.app.Application
import android.net.Uri
import android.provider.DocumentsContract
import androidx.lifecycle.viewModelScope
import com.librelookai.data.drive.DriveFileDto
import com.librelookai.ml.EmbeddingService
import java.io.File
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

internal fun WardrobeViewModel.importFromFolder(
        treeUri: Uri,
        removeBackground: Boolean = false,
        autoTag: Boolean = false,
        replaceExisting: Boolean = false,
        overwriteDuplicates: Boolean = false,
    ) {
        val id = (defaultImportFolderId ?: folderId) ?: return
        val options = ImportOptions(removeBackground, autoTag, replaceExisting, overwriteDuplicates)
        viewModelScope.launch {
            acquireJobWakeLock()
            try {
                val entries = enumerateSafSources(treeUri) ?: return@launch
                if (entries.isEmpty()) return@launch
                kickoffImport(id, options, entries)
            } finally {
                // For the preview path the lock is released in confirm/cancel; for the fast path
                // runImportEntries() runs synchronously here so we release after it returns.
                if (_state.value.importPreview == null) releaseJobWakeLock()
            }
        }
    }

    // ---------- Drive folder browser helpers (called from composable via LaunchedEffect) ----------

internal suspend fun WardrobeViewModel.listDriveSubfolders(folderId: String): List<DriveFileDto> =
        drive.listSubfolders(folderId)

internal suspend fun WardrobeViewModel.countDriveImages(folderId: String): Int =
        drive.countImages(folderId)

    // ---------- Drive Import ----------

    /**
     * Imports all images from a Google Drive folder ([sourceFolderId]) into the current wardrobe.
     * Mirror of [importFromFolder] but uses the Drive REST API instead of SAF.
     */
internal fun WardrobeViewModel.importFromDriveFolder(
        sourceFolderId: String,
        removeBackground: Boolean = false,
        autoTag: Boolean = false,
        replaceExisting: Boolean = false,
        overwriteDuplicates: Boolean = false,
    ) {
        val id = (defaultImportFolderId ?: folderId) ?: return
        val options = ImportOptions(removeBackground, autoTag, replaceExisting, overwriteDuplicates)
        viewModelScope.launch {
            acquireJobWakeLock()
            try {
                val entries = enumerateDriveSources(sourceFolderId)
                if (entries.isEmpty()) return@launch
                kickoffImport(id, options, entries)
            } finally {
                if (_state.value.importPreview == null) releaseJobWakeLock()
            }
        }
    }

    // ---------- Shared import implementation (SAF + Drive sources go through these) ----------

    /**
     * Phase A.1 — enumerate every image in [treeUri], copy each into a stable per-entry cache
     * file, and return the resulting [ImportPreviewEntry] list (ready for similarity scanning or
     * direct upload). Returns null on enumeration failure.
     */
internal suspend fun WardrobeViewModel.enumerateSafSources(treeUri: Uri): List<ImportPreviewEntry>? {
        val cr = getApplication<Application>().contentResolver
        val treeDocId = DocumentsContract.getTreeDocumentId(treeUri)
        val childrenUri = DocumentsContract.buildChildDocumentsUriUsingTree(treeUri, treeDocId)
        data class Src(val docId: String, val displayName: String, val mimeType: String)
        val srcFiles = mutableListOf<Src>()
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
                    mime.startsWith("image/")         -> srcFiles.add(Src(docId, name, mime))
                }
            }
        }
        val srcMetaByName: Map<String, WardrobeItemMeta> = metaDocId?.let { docId ->
            runCatching {
                val metaUri = DocumentsContract.buildDocumentUriUsingTree(treeUri, docId)
                val json = cr.openInputStream(metaUri)?.use { it.readBytes().toString(Charsets.UTF_8) }
                if (json != null) gson.fromJson(json, WardrobeMetadata::class.java).items.associateBy { it.name }
                else emptyMap()
            }.getOrDefault(emptyMap())
        } ?: emptyMap()
        val ts = System.currentTimeMillis()
        return srcFiles.mapIndexedNotNull { idx, src ->
            val ext = if (src.mimeType == "image/png") "png" else "jpg"
            val cached = File(drive.cacheDir, "import_${ts}_$idx.$ext")
            val ok = runCatching {
                val srcUri = DocumentsContract.buildDocumentUriUsingTree(treeUri, src.docId)
                cr.openInputStream(srcUri)?.use { it.copyTo(cached.outputStream()) } != null
            }.getOrDefault(false)
            if (!ok) return@mapIndexedNotNull null
            val meta = srcMetaByName[src.displayName]
            ImportPreviewEntry(
                key = "saf_$idx",
                displayName = src.displayName,
                cachedFilePath = cached.absolutePath,
                srcMetaTags = meta?.tags,
                srcMetaOriginalDriveId = meta?.originalDriveId,
            )
        }
    }

    /** Phase A.1 for a Drive source — list files, download each into a per-entry cache file. */
internal suspend fun WardrobeViewModel.enumerateDriveSources(sourceFolderId: String): List<ImportPreviewEntry> {
        val srcFiles = runCatching { drive.listFiles(sourceFolderId) }.getOrDefault(emptyList())
        if (srcFiles.isEmpty()) return emptyList()
        val ts = System.currentTimeMillis()
        return srcFiles.mapIndexedNotNull { idx, src ->
            val ext = if (src.name.endsWith(".png", ignoreCase = true)) "png" else "jpg"
            val cached = File(drive.cacheDir, "import_drive_${ts}_$idx.$ext")
            if (drive.downloadFileTo(src.id, cached) == null) return@mapIndexedNotNull null
            ImportPreviewEntry(
                key = "drive_$idx",
                displayName = src.name,
                cachedFilePath = cached.absolutePath,
            )
        }
    }

    /**
     * Phase A.2 — branch on whether the user has dedupe-on-import enabled. With it on, embed each
     * candidate, find above-threshold matches, and pause with [ImportPreview] state for the UI to
     * render a review grid. Without it, run the import directly on every entry.
     */
internal suspend fun WardrobeViewModel.kickoffImport(
        targetFolderId: String,
        options: ImportOptions,
        entries: List<ImportPreviewEntry>,
    ) {
        if (dedupeOnImport && EmbeddingService.isModelAvailable()) {
            // Show empty preview with a scanning indicator
            _state.update { it.copy(importPreview = ImportPreview(
                entries = emptyList(),
                selectedKeys = emptySet(),
                isScanning = true,
                scanDone = 0,
                scanTotal = entries.size,
                targetFolderId = targetFolderId,
                options = options,
            )) }
            // The cross-closet snapshot is store-derived and always current (§ 5 slice 4a).
            val crossClosetImages = _state.value.allLocationImages
            EmbeddingService.syncIndex(crossClosetImages, drive.cacheDir)
            val byId = crossClosetImages.associateBy { it.driveId }
            val scanned = mutableListOf<ImportPreviewEntry>()
            entries.forEachIndexed { idx, entry ->
                val matches = EmbeddingService.findSimilar(
                    file = File(entry.cachedFilePath),
                    threshold = dedupeThreshold,
                    topK = if (debugSimilarityPreview) 50 else 4,
                    segment = true,
                )
                val resolved = matches.mapNotNull { m ->
                    byId[m.driveId]?.let { DuplicateMatch(it, m.score) }
                }
                scanned.add(entry.copy(similar = resolved))
                _state.update { it.copy(importPreview = it.importPreview?.copy(
                    entries = scanned.toList(),
                    scanDone = idx + 1,
                    isScanning = idx < entries.lastIndex,
                )) }
            }
            // Default selection: only candidates without a similarity hit. The user can opt back in.
            val defaultSel = scanned.filter { it.similar.isEmpty() }.map { it.key }.toSet()
            _state.update { it.copy(importPreview = it.importPreview?.copy(
                entries = scanned.toList(),
                selectedKeys = defaultSel,
                isScanning = false,
            )) }
            return
        }
        // Fast path — import every entry without a preview.
        runImportEntries(targetFolderId, options, entries)
    }

    /** Toggle a single entry's selection inside the import preview. */
internal fun WardrobeViewModel.toggleImportPreviewSelection(key: String) {
        _state.update { s ->
            val p = s.importPreview ?: return@update s
            val next = p.selectedKeys.toMutableSet()
            if (!next.add(key)) next.remove(key)
            s.copy(importPreview = p.copy(selectedKeys = next))
        }
    }

    /** Replace the import-preview selection (used by Select all / Deselect all). */
internal fun WardrobeViewModel.setImportPreviewSelection(keys: Set<String>) {
        _state.update { s ->
            val p = s.importPreview ?: return@update s
            s.copy(importPreview = p.copy(selectedKeys = keys))
        }
    }

    /** Run the import on every selected entry; discard cache files for the deselected ones. */
internal fun WardrobeViewModel.confirmImportPreview() {
        val preview = _state.value.importPreview ?: return
        _state.update { it.copy(importPreview = null) }
        val (selected, dropped) = preview.entries.partition { it.key in preview.selectedKeys }
        viewModelScope.launch {
            try {
                if (selected.isNotEmpty()) {
                    runImportEntries(preview.targetFolderId, preview.options, selected)
                }
                dropped.forEach { runCatching { File(it.cachedFilePath).delete() } }
            } finally {
                releaseJobWakeLock()
            }
        }
    }

    /** Cancel the import without uploading anything; discard every cached candidate file. */
internal fun WardrobeViewModel.cancelImportPreview() {
        val preview = _state.value.importPreview ?: return
        _state.update { it.copy(importPreview = null) }
        preview.entries.forEach { runCatching { File(it.cachedFilePath).delete() } }
        releaseJobWakeLock()
    }

    /**
     * Phase B — the actual upload + AI loop. Operates on entries that already have a
     * pre-cached file on disk (regardless of source). Mirrors the original per-entry logic from
     * `importFromFolder` so behavior is unchanged when the preview gate is disabled.
     */
internal suspend fun WardrobeViewModel.runImportEntries(
        id: String,
        options: ImportOptions,
        entries: List<ImportPreviewEntry>,
    ) {
        val replaced: List<DriveImage> = if (options.replaceExisting) {
            val toDelete = _state.value.images
            _state.update { it.copy(isImporting = true, importDone = 0, importTotal = entries.size, error = null) }
            // Drop the rows first so the derived view empties immediately, then delete on Drive.
            toDelete.groupBy { it.folderId.ifEmpty { id } }.forEach { (fid, items) ->
                runCatching { itemStore.remove(fid, items.mapTo(HashSet()) { it.driveId }) }
            }
            toDelete.forEach { img -> runCatching { drive.deleteFile(img.driveId) } }
            toDelete
        } else {
            _state.update { it.copy(isImporting = true, importDone = 0, importTotal = entries.size, error = null) }
            emptyList()
        }
        // The derived state may not have caught up with the removes yet — exclude the replaced
        // items from the duplicate map explicitly instead of re-reading state.
        val replacedIds = replaced.mapTo(HashSet()) { it.driveId }
        val existingByName: Map<String, DriveImage> =
            _state.value.images.filterNot { it.driveId in replacedIds }.associateBy { it.name }
        // Set when any item in the loop hits a 402 — short-circuits the rest so we
        // don't make N pointless proxy calls. Global dialog still appears via
        // CreditsEvents emitted from throwIf402.
        var creditsExhausted = false
        entries.forEachIndexed { index, entry ->
            if (creditsExhausted) return@forEachIndexed
            _state.update { it.copy(importDone = index) }
            val tempFile = File(entry.cachedFilePath)
            val duplicate = existingByName[entry.displayName]
            if (duplicate != null && !options.overwriteDuplicates) {
                runCatching { tempFile.delete() }
                return@forEachIndexed
            }
            val placeholderId: String? = if (duplicate == null)
                "__importing_${index}_${System.nanoTime()}" else null
            if (placeholderId != null) {
                // Synthetic-id placeholder — rides the transient overlay, not the store (its
                // bytes live at an arbitrary temp path no cachedFile lookup would find).
                transientItems.update {
                    it + DriveImage(
                        driveId = placeholderId,
                        localPath = entry.cachedFilePath,
                        name = entry.displayName,
                        folderId = id,
                        createdTimeMs = System.currentTimeMillis(),
                    )
                }
                _state.update { it.copy(processingImageId = placeholderId) }
            } else {
                _state.update { it.copy(processingImageId = duplicate!!.driveId) }
            }
            runCatching {
                if (!tempFile.exists()) error("Cached file missing for ${entry.displayName}")
                var imageToUpload = tempFile
                var rawOriginalFile: File? = null
                var originalDriveId: String? = entry.srcMetaOriginalDriveId ?: duplicate?.originalDriveId
                if (options.removeBackground && entry.srcMetaTags == null) {
                    val processed = gemini.removeBackground(tempFile, drive.cacheDir)
                    if (processed != null) {
                        rawOriginalFile = tempFile
                        imageToUpload = processed
                    }
                }
                val tags = when {
                    entry.srcMetaTags != null -> entry.srcMetaTags
                    duplicate != null         -> duplicate.tags
                    options.autoTag           -> gemini.classifyClothing(imageToUpload, geminiLanguage)
                    else                      -> null
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
                    // Same driveId, new bytes + metadata: store write + Coil version bump.
                    persistItemToCache(
                        duplicate.folderId.ifEmpty { id },
                        duplicate.copy(tags = tags, originalDriveId = finalOriginalId),
                    )
                    bumpImageVersion(duplicate.driveId)
                } else {
                    val cutoutUploaded = uploadAsCutout(id, imageToUpload)
                    val displayCache = File(drive.cacheDir, "${cutoutUploaded.id}.png")
                    imageToUpload.copyTo(displayCache, overwrite = true)
                    val finalOriginalId = rawOriginalFile?.let { orig ->
                        val oid = runCatching { uploadAsOriginal(id, orig, cutoutUploaded.id) }.getOrNull()
                        orig.copyTo(File(drive.cacheDir, "${cutoutUploaded.id}_original.jpg"), overwrite = true)
                        oid
                    } ?: originalDriveId
                    val newImage = DriveImage(
                        driveId = cutoutUploaded.id,
                        localPath = displayCache.absolutePath,
                        name = cutoutUploaded.name,
                        tags = tags,
                        originalDriveId = finalOriginalId,
                        folderId = id,
                        createdTimeMs = System.currentTimeMillis(),
                    )
                    // Persist the real item, then retire its placeholder (the derived view
                    // swaps them in back-to-back emissions).
                    persistItemToCache(id, newImage)
                    if (placeholderId != null) {
                        transientItems.update { t -> t.filterNot { it.driveId == placeholderId } }
                    }
                }
            }.onFailure { e ->
                if (placeholderId != null) {
                    transientItems.update { t -> t.filterNot { it.driveId == placeholderId } }
                }
                if (e is com.librelookai.billing.InsufficientCreditsException) {
                    // Abort the rest of the bulk; the placeholder was rolled back above. No
                    // `error` text — the global dialog tells the user what happened.
                    creditsExhausted = true
                } else {
                    _state.update {
                        it.copy(
                            error = getApplication<Application>().localized().getString(com.librelookai.R.string.error_import_failed_item, entry.displayName, e.message ?: ""),
                        )
                    }
                }
            }
            _state.update { it.copy(processingImageId = null) }
            runCatching { tempFile.delete() }
        }
        _state.update { it.copy(isImporting = false, importDone = 0, importTotal = 0) }
        // Queue sidecar syncs for anything without one. Read the target folder from the store
        // too — the derived state may lag the items persisted moments ago.
        val viewMissing = _state.value.images.filter { it.sidecarDriveId == null }.map { it.driveId }
        val importedMissing = itemStore.itemsFor(id).filter { it.sidecarDriveId == null }.map { it.driveId }
        (viewMissing + importedMissing).distinct().forEach { saveSidecar(it) }
    }

