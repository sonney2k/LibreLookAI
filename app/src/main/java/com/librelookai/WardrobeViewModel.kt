package com.librelookai

import android.app.Application
import android.net.Uri
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.google.gson.Gson
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.io.File

enum class WardrobeView { GRID, CAPTURE }

data class DriveImage(
    val driveId: String,
    val localPath: String,
    val name: String,
    val tags: ClothingTags? = null,
    /** Bumped on every local reprocess so Coil knows to reload from disk. */
    val version: Long = 0L,
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
    val error: String? = null,
    /** driveId of the image currently being processed by an AI operation, or null. */
    val processingImageId: String? = null,
    val selectedIds: Set<String> = emptySet(),
)

// ---------- Wardrobe metadata (replaces per-file appProperties) ----------

private data class WardrobeItemMeta(val name: String, val tags: ClothingTags?)
private data class WardrobeMetadata(val items: List<WardrobeItemMeta> = emptyList())

class WardrobeViewModel(app: Application) : AndroidViewModel(app) {

    private val drive = DriveRepository(app, GoogleAuthManager(app))
    private val gemini = GeminiRepository()
    private val gson = Gson()

    private val _state = MutableStateFlow(WardrobeUiState())
    val state: StateFlow<WardrobeUiState> = _state.asStateFlow()

    private var folderId: String? = null

    init { loadImages() }

    // ---------- Load ----------

    fun loadImages() {
        viewModelScope.launch {
            _state.update { it.copy(isLoading = true, error = null) }
            runCatching {
                val id = folderId ?: drive.getOrCreateFolder().also { folderId = it }
                val files = drive.listFiles(id)

                // Load metadata file (filename → tags). Falls back to appProperties for migration.
                val metaJson = drive.loadWardrobeMetadataJson(id)
                val metaByName: Map<String, ClothingTags?> = if (metaJson != null) {
                    gson.fromJson(metaJson, WardrobeMetadata::class.java)
                        .items.associate { it.name to it.tags }
                } else emptyMap()

                // Download any uncached files in parallel
                files.map { file -> async { drive.cachedFile(file.id) ?: drive.downloadToCache(file.id) } }
                    .awaitAll()

                val hadLegacyProps = metaJson == null && files.any { it.appProperties?.isNotEmpty() == true }

                files.mapNotNull { file ->
                    drive.cachedFile(file.id)?.let { cached ->
                        val tags = if (metaByName.containsKey(file.name)) {
                            metaByName[file.name]           // primary: metadata file (by filename)
                        } else {
                            file.appProperties?.toClothingTags()  // fallback: legacy appProperties
                        }
                        DriveImage(file.id, cached.absolutePath, file.name, tags)
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

    /** Persists current wardrobe tags to the metadata file. Call after any tag change. */
    private fun saveWardrobeMetadata() {
        val images = _state.value.images
        val id = folderId ?: return
        viewModelScope.launch {
            val meta = WardrobeMetadata(images.map { WardrobeItemMeta(it.name, it.tags) })
            runCatching { drive.saveWardrobeMetadataJson(id, gson.toJson(meta)) }
        }
    }

    // ---------- Navigation ----------

    fun openCapture() = _state.update { it.copy(view = WardrobeView.CAPTURE) }
    fun closeCapture() = _state.update { it.copy(view = WardrobeView.GRID) }

    // ---------- Upload from camera ----------

    fun uploadPhoto(rawFile: File) {
        viewModelScope.launch {
            _state.update { it.copy(view = WardrobeView.GRID, batchTotal = 0, batchDone = 0) }
            processAndUpload(rawFile)?.let { newImage ->
                _state.update { it.copy(images = listOf(newImage) + it.images) }
            }
        }
    }

    // ---------- Upload from gallery ----------

    fun uploadGalleryPhotos(uris: List<Uri>) {
        if (uris.isEmpty()) return
        viewModelScope.launch {
            _state.update { it.copy(batchTotal = uris.size, batchDone = 0, error = null) }
            val cr = getApplication<Application>().contentResolver
            uris.forEachIndexed { index, uri ->
                _state.update { it.copy(batchDone = index) }
                // Copy URI content to a temp file Gemini and Drive can consume
                val tempFile = File(drive.cacheDir, "gallery_${System.currentTimeMillis()}.jpg")
                runCatching {
                    cr.openInputStream(uri)?.use { it.copyTo(tempFile.outputStream()) }
                }.onFailure { e ->
                    _state.update { it.copy(error = "Could not read image: ${e.message}") }
                    return@forEachIndexed
                }
                processAndUpload(tempFile)?.let { newImage ->
                    _state.update { it.copy(images = listOf(newImage) + it.images) }
                }
                tempFile.delete()
            }
            _state.update { it.copy(batchTotal = 0, batchDone = 0, isProcessing = false, isUploading = false) }
        }
    }

    // ---------- Shared process + upload logic ----------

    private suspend fun processAndUpload(rawFile: File): DriveImage? {
        // Step 1 — Gemini background removal
        _state.update { it.copy(isProcessing = true, error = null) }
        val processedFile = gemini.removeBackground(rawFile, drive.cacheDir) ?: rawFile

        // Step 2 — Upload to Drive
        _state.update { it.copy(isProcessing = false, isUploading = true) }
        return runCatching {
            val id = folderId ?: drive.getOrCreateFolder().also { folderId = it }
            val uploaded = drive.uploadImage(id, processedFile)

            val ext = if (processedFile.extension == "png") "png" else "jpg"
            val displayCache = File(drive.cacheDir, "${uploaded.id}.$ext")
            if (processedFile.absolutePath != displayCache.absolutePath) {
                processedFile.copyTo(displayCache, overwrite = true)
            }
            rawFile.copyTo(File(drive.cacheDir, "${uploaded.id}_original.jpg"), overwrite = true)

            // Step 3 — Classify clothing tags
            val tags = gemini.classifyClothing(processedFile)

            DriveImage(uploaded.id, displayCache.absolutePath, uploaded.name, tags)
        }.onFailure { e ->
            _state.update { it.copy(error = e.message) }
        }.onSuccess {
            _state.update { it.copy(isUploading = false) }
            saveWardrobeMetadata()
        }.getOrNull()
    }

    fun reprocessBackground(driveId: String) {
        viewModelScope.launch {
            _state.update { it.copy(isProcessing = true, processingImageId = driveId, error = null) }
            // Prefer the original photo; fall back to whatever is cached
            val source = File(drive.cacheDir, "${driveId}_original.jpg").takeIf { it.exists() }
                ?: drive.cachedFile(driveId)
                ?: run {
                    _state.update { it.copy(isProcessing = false, error = "Original not in cache") }
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

    fun tagImage(driveId: String) {
        viewModelScope.launch {
            _state.update { it.copy(processingImageId = driveId) }
            val cachedFile = drive.cachedFile(driveId)
                ?: run { _state.update { it.copy(processingImageId = null) }; return@launch }
            val tags = gemini.classifyClothing(cachedFile)
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
                val tags = gemini.classifyClothing(cachedFile) ?: return@forEachIndexed
                _state.update { s ->
                    s.copy(images = s.images.map { if (it.driveId == image.driveId) it.copy(tags = tags) else it })
                }
            }
            _state.update { it.copy(isRetagging = false, retagDone = 0, retagTotal = 0) }
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
