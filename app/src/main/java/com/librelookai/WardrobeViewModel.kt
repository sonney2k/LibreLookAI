package com.librelookai

import android.app.Application
import android.net.Uri
import android.provider.DocumentsContract
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.google.gson.Gson
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.io.File

enum class WardrobeView { GRID, CAPTURE }

data class DriveImage(
    val driveId: String,
    val localPath: String,
    val name: String,
    val tags: ClothingTags? = null,
    /** Bumped on every local reprocess so Coil knows to reload from disk. */
    val version: Long = 0L,
    /** Drive file ID of the unprocessed original, if one was saved to Drive. */
    val originalDriveId: String? = null,
)

data class WardrobeUiState(
    val view: WardrobeView = WardrobeView.GRID,
    val images: List<DriveImage> = emptyList(),
    val isLoading: Boolean = false,
    val isProcessing: Boolean = false,
    val isUploading: Boolean = false,
    /** Number of items in the current batch (0 = single-item flow). */
    val batchTotal: Int = 0,
    val batchDone: Int = 0,
    val isRetagging: Boolean = false,
    val retagDone: Int = 0,
    val retagTotal: Int = 0,
    val isImporting: Boolean = false,
    val importDone: Int = 0,
    val importTotal: Int = 0,
    val isRemovingAllBg: Boolean = false,
    val removeBgDone: Int = 0,
    val removeBgTotal: Int = 0,
    val error: String? = null,
    /** driveId of the image currently being processed by an AI operation, or null. */
    val processingImageId: String? = null,
    val selectedIds: Set<String> = emptySet(),
    /** Number of photos queued or actively running background processing (bg removal + tagging). */
    val pendingJobs: Int = 0,
)

// ---------- Wardrobe metadata (replaces per-file appProperties) ----------

private data class WardrobeItemMeta(val name: String, val tags: ClothingTags?, val originalDriveId: String? = null)
private data class WardrobeMetadata(val items: List<WardrobeItemMeta> = emptyList())

private data class PendingJob(val driveId: String, val folderId: String)

class WardrobeViewModel(app: Application) : AndroidViewModel(app) {

    private val drive = DriveRepository(app, GoogleAuthManager(app))
    private val gemini = GeminiRepository(app)
    private val gson = Gson()

    private val _state = MutableStateFlow(WardrobeUiState())
    val state: StateFlow<WardrobeUiState> = _state.asStateFlow()

    private var folderId: String? = null
    /** Gemini-facing language name (e.g. "English", "German") for label generation. */
    private var geminiLanguage: String = "English"

    /** Serializes all Drive metadata writes to prevent concurrent saves overwriting each other. */
    private val metaMutex = Mutex()

    /**
     * Background processing queue. Each item represents one uploaded photo that needs
     * bg removal + classification. Processed serially so metadata writes are consistent.
     */
    private val workQueue = Channel<PendingJob>(Channel.UNLIMITED)

    init {
        viewModelScope.launch { processQueue() }
    }

    fun setLanguage(geminiName: String) { geminiLanguage = geminiName }

    fun setLocation(newFolderId: String) {
        if (folderId == newFolderId) return
        folderId = newFolderId
        _state.update { WardrobeUiState(isLoading = true) }
        loadImages()
    }

    // ---------- Load ----------

    fun loadImages() {
        viewModelScope.launch {
            _state.update { it.copy(isLoading = true, error = null) }
            runCatching {
                val id = folderId ?: return@runCatching emptyList()
                val files = drive.listFiles(id)

                // Load metadata file (filename → meta). Falls back to appProperties for migration.
                val metaJson = drive.loadWardrobeMetadataJson(id)
                val metaByName: Map<String, WardrobeItemMeta> = if (metaJson != null) {
                    gson.fromJson(metaJson, WardrobeMetadata::class.java)
                        .items.associateBy { it.name }
                } else emptyMap()

                // Download any uncached files in parallel (pass name so PNG cutouts use .png extension)
                files.map { file -> async { drive.cachedFile(file.id) ?: drive.downloadToCache(file.id, file.name) } }
                    .awaitAll()

                val hadLegacyProps = metaJson == null && files.any { it.appProperties?.isNotEmpty() == true }

                files.mapNotNull { file ->
                    drive.cachedFile(file.id)?.let { cached ->
                        val meta = metaByName[file.name]
                        val tags = if (meta != null) {
                            meta.tags                             // primary: metadata file (by filename)
                        } else {
                            file.appProperties?.toClothingTags()  // fallback: legacy appProperties
                        }
                        DriveImage(file.id, cached.absolutePath, file.name, tags, originalDriveId = meta?.originalDriveId)
                    }
                }.also { images ->
                    // Auto-migrate: if data came from appProperties, write metadata file now
                    if (hadLegacyProps) {
                        val meta = WardrobeMetadata(images.map { WardrobeItemMeta(it.name, it.tags) })
                        runCatching { drive.saveWardrobeMetadataJson(id, gson.toJson(meta)) }
                    }
                }
            }.onSuccess { images ->
                _state.update { it.copy(images = images, isLoading = false) }
            }.onFailure { e ->
                _state.update { it.copy(isLoading = false, error = e.message) }
            }
        }
    }

    /** Persists current wardrobe tags to the metadata file. Serialized via [metaMutex]. */
    private fun saveWardrobeMetadata() {
        viewModelScope.launch {
            metaMutex.withLock {
                val images = _state.value.images
                val id = folderId ?: return@withLock
                val meta = WardrobeMetadata(images.map { WardrobeItemMeta(it.name, it.tags, it.originalDriveId) })
                runCatching { drive.saveWardrobeMetadataJson(id, gson.toJson(meta)) }
            }
        }
    }

    // ---------- Background processing queue ----------

    /** Drains [workQueue] serially — bg removal + tagging for each newly uploaded photo. */
    private suspend fun processQueue() {
        for (job in workQueue) {
            runCatching { processQueuedImage(job) }
                .onFailure { e -> _state.update { it.copy(error = e.message) } }
            _state.update { it.copy(pendingJobs = maxOf(0, it.pendingJobs - 1)) }
            saveWardrobeMetadata()
        }
    }

    private suspend fun processQueuedImage(job: PendingJob) {
        val rawFile = File(drive.cacheDir, "${job.driveId}_original.jpg")
        if (!rawFile.exists()) return

        // Derive the base name from the current Drive item name (e.g. "photo_1234" from "photo_1234.jpg")
        val rawItem = _state.value.images.find { it.driveId == job.driveId } ?: return
        val baseName = rawItem.name.substringBeforeLast(".")
        val cutoutName = "$baseName${DriveRepository.CUTOUT_SUFFIX}"
        val originalName = "$baseName${DriveRepository.ORIGINAL_SUFFIX}"

        // Step 1 — background removal (falls back to raw image if Gemini fails)
        val processedFile = gemini.removeBackground(rawFile, drive.cacheDir) ?: rawFile

        // Step 2 — upload the cutout (or raw fallback) as ${baseName}_cutout.png
        val cutoutDrive = runCatching {
            drive.uploadImageWithName(job.folderId, processedFile, cutoutName)
        }.getOrNull() ?: return

        // Step 3 — upload original backup as ${baseName}_original.jpg (best-effort)
        val originalDriveId = runCatching {
            drive.uploadImageWithName(job.folderId, rawFile, originalName).id
        }.getOrNull()

        // Step 4 — delete the temporary raw upload (it has no _cutout.png suffix and must not appear in listings)
        runCatching { drive.deleteFile(job.driveId) }

        // Step 5 — write cutout to local cache under the new Drive ID
        val localCutout = File(drive.cacheDir, "${cutoutDrive.id}.png")
        if (processedFile.absolutePath != localCutout.absolutePath) {
            processedFile.copyTo(localCutout, overwrite = true)
        }

        // Step 6 — update state: driveId, name, path, and originalDriveId all change
        _state.update { s ->
            s.copy(images = s.images.map { img ->
                if (img.driveId == job.driveId) img.copy(
                    driveId = cutoutDrive.id,
                    name = cutoutDrive.name,
                    localPath = localCutout.absolutePath,
                    version = System.currentTimeMillis(),
                    originalDriveId = originalDriveId,
                ) else img
            })
        }

        // Step 7 — classify clothing tags against the cutout image
        val tags = gemini.classifyClothing(localCutout, geminiLanguage) ?: return
        _state.update { s ->
            s.copy(images = s.images.map { img ->
                if (img.driveId == cutoutDrive.id) img.copy(tags = tags) else img
            })
        }
    }

    // ---------- Navigation ----------

    fun openCapture() = _state.update { it.copy(view = WardrobeView.CAPTURE) }
    fun closeCapture() = _state.update { it.copy(view = WardrobeView.GRID) }

    // ---------- Upload from camera ----------

    fun uploadPhoto(rawFile: File) {
        val id = folderId ?: run {
            _state.update { it.copy(view = WardrobeView.GRID) }
            return
        }
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
                DriveImage(uploaded.id, displayCache.absolutePath, uploaded.name, tags = null)
            }.onSuccess { newImage ->
                _state.update { it.copy(
                    isUploading = false,
                    images = listOf(newImage) + it.images,
                    pendingJobs = it.pendingJobs + 1,
                ) }
                saveWardrobeMetadata()
                workQueue.send(PendingJob(newImage.driveId, id))
            }.onFailure { e ->
                _state.update { it.copy(isUploading = false, error = e.message) }
            }
        }
    }

    // ---------- Upload from gallery ----------

    fun uploadGalleryPhotos(uris: List<Uri>) {
        if (uris.isEmpty()) return
        val id = folderId ?: return
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
                    DriveImage(uploaded.id, displayCache.absolutePath, uploaded.name, tags = null)
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
            saveWardrobeMetadata()
        }
    }

    fun reprocessBackground(driveId: String) {
        viewModelScope.launch {
            _state.update { it.copy(isProcessing = true, processingImageId = driveId, error = null) }
            val source = resolveOriginalFile(driveId)
                ?: run {
                    _state.update { it.copy(isProcessing = false, error = "Original not available") }
                    return@launch
                }
            val processedFile = gemini.removeBackground(source, drive.cacheDir)
                ?: run { _state.update { it.copy(isProcessing = false, processingImageId = null) }; return@launch }

            _state.update { it.copy(isProcessing = false, isUploading = true) }
            runCatching {
                drive.updateImage(driveId, processedFile)
                val displayCache = File(drive.cacheDir, "$driveId.png")
                processedFile.copyTo(displayCache, overwrite = true)
                displayCache.absolutePath
            }.onSuccess { newPath ->
                _state.update { s ->
                    s.copy(
                        isUploading = false,
                        processingImageId = null,
                        images = s.images.map {
                            if (it.driveId == driveId) it.copy(localPath = newPath, version = System.currentTimeMillis())
                            else it
                        },
                    )
                }
            }.onFailure { e ->
                _state.update { it.copy(isUploading = false, processingImageId = null, error = e.message) }
            }
        }
    }

    /**
     * Returns the best available original file for [driveId]:
     * 1. Local cache `${driveId}_original.jpg`
     * 2. Drive download via `originalDriveId` (cached locally for future use)
     * 3. The current cached (processed) image as last resort
     */
    private suspend fun resolveOriginalFile(driveId: String): File? {
        val localOriginal = File(drive.cacheDir, "${driveId}_original.jpg")
        if (localOriginal.exists()) return localOriginal

        val originalDriveId = _state.value.images.find { it.driveId == driveId }?.originalDriveId
        if (originalDriveId != null) {
            val downloaded = drive.downloadToCache(originalDriveId)
            if (downloaded != null) {
                downloaded.copyTo(localOriginal, overwrite = true)
                return localOriginal
            }
        }

        return drive.cachedFile(driveId)
    }

    fun removeAllBackgrounds() {
        val images = _state.value.images
        if (images.isEmpty() || _state.value.isRemovingAllBg) return
        viewModelScope.launch {
            _state.update { it.copy(isRemovingAllBg = true, removeBgDone = 0, removeBgTotal = images.size) }
            images.forEachIndexed { index, image ->
                _state.update { it.copy(removeBgDone = index) }
                val source = resolveOriginalFile(image.driveId) ?: return@forEachIndexed
                val processedFile = gemini.removeBackground(source, drive.cacheDir) ?: return@forEachIndexed

                // Upload original to Drive if not already stored there
                val id = folderId ?: return@forEachIndexed
                val originalDriveId = image.originalDriveId ?: runCatching {
                    drive.uploadImage(id, source).id
                }.getOrNull()

                runCatching {
                    drive.updateImage(image.driveId, processedFile)
                    val displayCache = File(drive.cacheDir, "${image.driveId}.png")
                    processedFile.copyTo(displayCache, overwrite = true)
                    _state.update { s ->
                        s.copy(images = s.images.map {
                            if (it.driveId == image.driveId) it.copy(
                                localPath = displayCache.absolutePath,
                                version = System.currentTimeMillis(),
                                originalDriveId = originalDriveId ?: it.originalDriveId,
                            ) else it
                        })
                    }
                }
            }
            _state.update { it.copy(isRemovingAllBg = false, removeBgDone = 0, removeBgTotal = 0) }
            saveWardrobeMetadata()
        }
    }

    fun tagImage(driveId: String) {
        viewModelScope.launch {
            _state.update { it.copy(processingImageId = driveId) }
            val cachedFile = drive.cachedFile(driveId)
                ?: run { _state.update { it.copy(processingImageId = null) }; return@launch }
            val tags = gemini.classifyClothing(cachedFile, geminiLanguage)
                ?: run { _state.update { it.copy(processingImageId = null) }; return@launch }
            _state.update { s ->
                s.copy(
                    processingImageId = null,
                    images = s.images.map { if (it.driveId == driveId) it.copy(tags = tags) else it },
                )
            }
            saveWardrobeMetadata()
        }
    }

    fun updateTags(driveId: String, tags: ClothingTags) {
        _state.update { s ->
            s.copy(images = s.images.map { if (it.driveId == driveId) it.copy(tags = tags) else it })
        }
        saveWardrobeMetadata()
    }

    fun retagAll() {
        val images = _state.value.images
        if (images.isEmpty() || _state.value.isRetagging) return
        viewModelScope.launch {
            _state.update { it.copy(isRetagging = true, retagDone = 0, retagTotal = images.size) }
            images.forEachIndexed { index, image ->
                _state.update { it.copy(retagDone = index) }
                val cachedFile = drive.cachedFile(image.driveId) ?: return@forEachIndexed
                val tags = gemini.classifyClothing(cachedFile, geminiLanguage) ?: return@forEachIndexed
                _state.update { s ->
                    s.copy(images = s.images.map { if (it.driveId == image.driveId) it.copy(tags = tags) else it })
                }
            }
            _state.update { it.copy(isRetagging = false, retagDone = 0, retagTotal = 0) }
            saveWardrobeMetadata()
        }
    }

    // ---------- Move to another location ----------

    fun moveItemsToLocation(driveIds: Set<String>, targetFolderId: String) {
        val toMove = _state.value.images.filter { it.driveId in driveIds }
        if (toMove.isEmpty()) return
        viewModelScope.launch {
            val sourceFolderId = folderId ?: return@launch
            _state.update { it.copy(isUploading = true, selectedIds = emptySet(), error = null) }

            // Load existing target metadata so we can merge (not overwrite) it
            val targetMetaJson = drive.loadWardrobeMetadataJson(targetFolderId)
            val targetItems: MutableList<WardrobeItemMeta> = if (targetMetaJson != null) {
                runCatching { gson.fromJson(targetMetaJson, WardrobeMetadata::class.java).items.toMutableList() }
                    .getOrDefault(mutableListOf())
            } else mutableListOf()

            val successfulIds = mutableListOf<String>()
            for (item in toMove) {
                val cachedFile = drive.cachedFile(item.driveId) ?: continue
                runCatching {
                    drive.uploadImage(targetFolderId, cachedFile)
                    targetItems.removeAll { it.name == item.name }   // dedup by filename
                    targetItems.add(WardrobeItemMeta(item.name, item.tags))
                    drive.deleteFile(item.driveId)
                    successfulIds.add(item.driveId)
                }.onFailure { e ->
                    _state.update { it.copy(error = e.message) }
                }
            }

            if (successfulIds.isNotEmpty()) {
                // Save merged metadata in target folder
                runCatching { drive.saveWardrobeMetadataJson(targetFolderId, gson.toJson(WardrobeMetadata(targetItems))) }
                // Remove moved items from source state and save source metadata
                _state.update { s -> s.copy(images = s.images.filter { it.driveId !in successfulIds }) }
                saveWardrobeMetadata()
            }
            _state.update { it.copy(isUploading = false) }
        }
    }

    // ---------- SAF Import ----------

    /**
     * Imports all images from [treeUri] into the current wardrobe folder.
     *
     * Images are always uploaded/updated and immediately visible — AI processing is optional.
     *
     * [removeBackground]   — run Gemini BG removal on each image (5 credits/item).
     * [autoTag]            — classify clothing tags with Gemini (2 credits/item).
     * [replaceExisting]    — delete all current wardrobe items before importing (fresh start).
     * [overwriteDuplicates]— when false (default) images whose filename already exists are
     *                        skipped; when true the existing Drive file is replaced in-place.
     *                        Ignored when [replaceExisting] is true.
     */
    fun importFromFolder(
        treeUri: Uri,
        removeBackground: Boolean = false,
        autoTag: Boolean = false,
        replaceExisting: Boolean = false,
        overwriteDuplicates: Boolean = false,
    ) {
        val id = folderId ?: return
        viewModelScope.launch {
            val cr = getApplication<Application>().contentResolver

            // ---- Enumerate source files ----
            val treeDocId = DocumentsContract.getTreeDocumentId(treeUri)
            val childrenUri = DocumentsContract.buildChildDocumentsUriUsingTree(treeUri, treeDocId)

            data class SrcFile(val docId: String, val displayName: String, val mimeType: String)
            val srcFiles = mutableListOf<SrcFile>()
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
                        mime.startsWith("image/")         -> srcFiles.add(SrcFile(docId, name, mime))
                    }
                }
            }

            if (srcFiles.isEmpty()) return@launch

            // ---- Load source metadata (tags/originalDriveId carried over from a prior export) ----
            val srcMetaByName: Map<String, WardrobeItemMeta> = metaDocId?.let { docId ->
                runCatching {
                    val metaUri = DocumentsContract.buildDocumentUriUsingTree(treeUri, docId)
                    val json = cr.openInputStream(metaUri)?.use { it.readBytes().toString(Charsets.UTF_8) }
                    if (json != null) gson.fromJson(json, WardrobeMetadata::class.java).items.associateBy { it.name }
                    else emptyMap()
                }.getOrDefault(emptyMap())
            } ?: emptyMap()

            // ---- Fresh-start: delete all existing wardrobe items first ----
            if (replaceExisting) {
                val toDelete = _state.value.images.map { it.driveId }
                _state.update { it.copy(isImporting = true, importDone = 0, importTotal = srcFiles.size, images = emptyList(), error = null) }
                toDelete.forEach { driveId -> runCatching { drive.deleteFile(driveId) } }
            } else {
                _state.update { it.copy(isImporting = true, importDone = 0, importTotal = srcFiles.size, error = null) }
            }

            // Build name → existing item map for duplicate detection (empty after fresh-start)
            val existingByName: Map<String, DriveImage> = _state.value.images.associateBy { it.name }

            // ---- Process each source image ----
            srcFiles.forEachIndexed { index, src ->
                _state.update { it.copy(importDone = index) }

                val duplicate = existingByName[src.displayName]
                if (duplicate != null && !overwriteDuplicates) {
                    // Skip — item with this name already in wardrobe
                    return@forEachIndexed
                }

                val srcExt = if (src.mimeType == "image/png") "png" else "jpg"
                val tempFile = File(drive.cacheDir, "import_${System.currentTimeMillis()}.$srcExt")
                runCatching {
                    val srcUri = DocumentsContract.buildDocumentUriUsingTree(treeUri, src.docId)
                    cr.openInputStream(srcUri)?.use { it.copyTo(tempFile.outputStream()) }
                        ?: error("Cannot open stream for ${src.displayName}")

                    val srcMeta = srcMetaByName[src.displayName]

                    // Optional background removal
                    var imageToUpload = tempFile
                    var originalDriveId: String? = srcMeta?.originalDriveId ?: duplicate?.originalDriveId
                    if (removeBackground && srcMeta == null) {
                        val processed = gemini.removeBackground(tempFile, drive.cacheDir)
                        if (processed != null) {
                            originalDriveId = runCatching { drive.uploadImage(id, tempFile).id }.getOrNull()
                            imageToUpload = processed
                        }
                    }

                    // Tags: source metadata → existing item tags → Gemini → none
                    val tags = when {
                        srcMeta != null     -> srcMeta.tags
                        duplicate != null   -> duplicate.tags
                        autoTag             -> gemini.classifyClothing(imageToUpload, geminiLanguage)
                        else                -> null
                    }

                    val uploadExt = if (imageToUpload.extension == "png") "png" else srcExt

                    if (duplicate != null) {
                        // Overwrite existing item in-place — keep the same Drive ID
                        drive.updateImage(duplicate.driveId, imageToUpload)
                        val displayCache = File(drive.cacheDir, "${duplicate.driveId}.$uploadExt")
                        imageToUpload.copyTo(displayCache, overwrite = true)
                        if (imageToUpload != tempFile) {
                            tempFile.copyTo(File(drive.cacheDir, "${duplicate.driveId}_original.jpg"), overwrite = true)
                        }
                        _state.update { s ->
                            s.copy(images = s.images.map { img ->
                                if (img.driveId == duplicate.driveId) img.copy(
                                    localPath = displayCache.absolutePath,
                                    tags = tags,
                                    version = System.currentTimeMillis(),
                                    originalDriveId = originalDriveId ?: img.originalDriveId,
                                ) else img
                            })
                        }
                    } else {
                        // New item — upload fresh
                        val uploaded = drive.uploadImage(id, imageToUpload)
                        val displayCache = File(drive.cacheDir, "${uploaded.id}.$uploadExt")
                        imageToUpload.copyTo(displayCache, overwrite = true)
                        if (imageToUpload != tempFile) {
                            tempFile.copyTo(File(drive.cacheDir, "${uploaded.id}_original.jpg"), overwrite = true)
                        }
                        _state.update { it.copy(images = it.images + DriveImage(
                            driveId = uploaded.id,
                            localPath = displayCache.absolutePath,
                            name = uploaded.name,
                            tags = tags,
                            originalDriveId = originalDriveId,
                        )) }
                    }
                }.onFailure { e ->
                    _state.update { it.copy(error = "Import failed for ${src.displayName}: ${e.message}") }
                }
                tempFile.delete()
            }

            _state.update { it.copy(isImporting = false, importDone = 0, importTotal = 0) }
            saveWardrobeMetadata()
        }
    }

    // ---------- Drive folder browser helpers (called from composable via LaunchedEffect) ----------

    suspend fun listDriveSubfolders(folderId: String): List<DriveFileDto> =
        drive.listSubfolders(folderId)

    suspend fun countDriveImages(folderId: String): Int =
        drive.countImages(folderId)

    // ---------- Drive Import ----------

    /**
     * Imports all images from a Google Drive folder ([sourceFolderId]) into the current wardrobe.
     * Mirror of [importFromFolder] but uses the Drive REST API instead of SAF.
     */
    fun importFromDriveFolder(
        sourceFolderId: String,
        removeBackground: Boolean = false,
        autoTag: Boolean = false,
        replaceExisting: Boolean = false,
        overwriteDuplicates: Boolean = false,
    ) {
        val id = folderId ?: return
        viewModelScope.launch {
            val srcFiles = runCatching { drive.listFiles(sourceFolderId) }.getOrDefault(emptyList())
            if (srcFiles.isEmpty()) return@launch

            if (replaceExisting) {
                val toDelete = _state.value.images.map { it.driveId }
                _state.update { it.copy(isImporting = true, importDone = 0, importTotal = srcFiles.size, images = emptyList(), error = null) }
                toDelete.forEach { driveId -> runCatching { drive.deleteFile(driveId) } }
            } else {
                _state.update { it.copy(isImporting = true, importDone = 0, importTotal = srcFiles.size, error = null) }
            }

            val existingByName: Map<String, DriveImage> = _state.value.images.associateBy { it.name }

            srcFiles.forEachIndexed { index, src ->
                _state.update { it.copy(importDone = index) }

                val duplicate = existingByName[src.name]
                if (duplicate != null && !overwriteDuplicates) return@forEachIndexed

                val srcExt = if (src.name.endsWith(".png", ignoreCase = true)) "png" else "jpg"
                val tempFile = File(drive.cacheDir, "import_drive_${System.currentTimeMillis()}.$srcExt")

                runCatching {
                    drive.downloadFileTo(src.id, tempFile)
                        ?: error("Cannot download ${src.name}")

                    var imageToUpload = tempFile
                    var originalDriveId: String? = duplicate?.originalDriveId
                    if (removeBackground) {
                        val processed = gemini.removeBackground(tempFile, drive.cacheDir)
                        if (processed != null) {
                            originalDriveId = runCatching { drive.uploadImage(id, tempFile).id }.getOrNull()
                            imageToUpload = processed
                        }
                    }

                    val tags = when {
                        autoTag       -> gemini.classifyClothing(imageToUpload, geminiLanguage)
                        duplicate != null -> duplicate.tags   // preserve existing tags
                        else          -> null
                    }

                    val uploadExt = if (imageToUpload.extension == "png") "png" else srcExt

                    if (duplicate != null) {
                        drive.updateImage(duplicate.driveId, imageToUpload)
                        val displayCache = File(drive.cacheDir, "${duplicate.driveId}.$uploadExt")
                        imageToUpload.copyTo(displayCache, overwrite = true)
                        if (imageToUpload != tempFile) {
                            tempFile.copyTo(File(drive.cacheDir, "${duplicate.driveId}_original.jpg"), overwrite = true)
                        }
                        _state.update { s ->
                            s.copy(images = s.images.map { img ->
                                if (img.driveId == duplicate.driveId) img.copy(
                                    localPath = displayCache.absolutePath,
                                    tags = tags,
                                    version = System.currentTimeMillis(),
                                    originalDriveId = originalDriveId ?: img.originalDriveId,
                                ) else img
                            })
                        }
                    } else {
                        val uploaded = drive.uploadImage(id, imageToUpload)
                        val displayCache = File(drive.cacheDir, "${uploaded.id}.$uploadExt")
                        imageToUpload.copyTo(displayCache, overwrite = true)
                        if (imageToUpload != tempFile) {
                            tempFile.copyTo(File(drive.cacheDir, "${uploaded.id}_original.jpg"), overwrite = true)
                        }
                        _state.update { it.copy(images = it.images + DriveImage(
                            driveId = uploaded.id,
                            localPath = displayCache.absolutePath,
                            name = uploaded.name,
                            tags = tags,
                            originalDriveId = originalDriveId,
                        )) }
                    }
                }.onFailure { e ->
                    _state.update { it.copy(error = "Import failed for ${src.name}: ${e.message}") }
                }
                tempFile.delete()
            }

            _state.update { it.copy(isImporting = false, importDone = 0, importTotal = 0) }
            saveWardrobeMetadata()
        }
    }

    fun clearError() = _state.update { it.copy(error = null) }

    // ---------- Selection & Delete ----------

    fun toggleSelection(driveId: String) {
        _state.update { s ->
            val next = s.selectedIds.toMutableSet()
            if (!next.add(driveId)) next.remove(driveId)
            s.copy(selectedIds = next)
        }
    }

    fun selectAll(ids: List<String>) = _state.update { it.copy(selectedIds = it.selectedIds + ids) }

    fun clearSelection() = _state.update { it.copy(selectedIds = emptySet()) }

    fun deleteSelected() {
        val toDelete = _state.value.selectedIds
        if (toDelete.isEmpty()) return
        viewModelScope.launch {
            _state.update { it.copy(isUploading = true, selectedIds = emptySet()) }
            toDelete.forEach { id ->
                runCatching { drive.deleteFile(id) }
            }
            _state.update { s ->
                s.copy(
                    isUploading = false,
                    images = s.images.filter { it.driveId !in toDelete }
                )
            }
            saveWardrobeMetadata()
        }
    }
}

// ---------- Legacy appProperties → ClothingTags (migration read-path only) ----------

private fun Map<String, String>.toClothingTags(): ClothingTags? {
    val type = getOrDefault("clothing_type", "")
    if (type.isEmpty()) return null
    return ClothingTags(
        type        = type,
        category    = getOrDefault("clothing_category", ""),
        uses        = getOrDefault("clothing_uses",        "").split(",").filter { it.isNotBlank() },
        colors      = getOrDefault("clothing_colors",      "").split(",").filter { it.isNotBlank() },
        seasonality = getOrDefault("clothing_seasonality", "").split(",").filter { it.isNotBlank() },
        aesthetic   = getOrDefault("clothing_aesthetic",   "").split(",").filter { it.isNotBlank() },
        fit         = getOrDefault("clothing_fit",         "").split(",").filter { it.isNotBlank() },
        material    = getOrDefault("clothing_material",    "").split(",").filter { it.isNotBlank() },
        pattern     = getOrDefault("clothing_pattern",     "").split(",").filter { it.isNotBlank() },
    )
}
