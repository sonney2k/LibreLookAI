package com.librelookai

import android.app.Application
import android.net.Uri
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
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
    val error: String? = null,
    /** driveId of the image currently being processed by an AI operation, or null. */
    val processingImageId: String? = null,
    val selectedIds: Set<String> = emptySet(),
)

class WardrobeViewModel(app: Application) : AndroidViewModel(app) {

    private val drive = DriveRepository(app, GoogleAuthManager(app))
    private val gemini = GeminiRepository()

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
                files.map { file -> async { drive.cachedFile(file.id) ?: drive.downloadToCache(file.id) } }
                    .awaitAll()
                files.mapNotNull { file ->
                    drive.cachedFile(file.id)?.let {
                        DriveImage(file.id, it.absolutePath, file.name, file.appProperties?.toClothingTags())
                    }
                }
            }.onSuccess { images ->
                _state.update { it.copy(images = images, isLoading = false) }
            }.onFailure { e ->
                _state.update { it.copy(isLoading = false, error = e.message) }
            }
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

            // Step 3 — Classify clothing tags and persist to Drive appProperties
            val tags = gemini.classifyClothing(processedFile)
            if (tags != null) drive.updateAppProperties(uploaded.id, tags.toAppProperties())

            DriveImage(uploaded.id, displayCache.absolutePath, uploaded.name, tags)
        }.onFailure { e ->
            _state.update { it.copy(error = e.message) }
        }.onSuccess {
            _state.update { it.copy(isUploading = false) }
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
            drive.updateAppProperties(driveId, tags.toAppProperties())
            _state.update { s ->
                s.copy(
                    processingImageId = null,
                    images = s.images.map { if (it.driveId == driveId) it.copy(tags = tags) else it },
                )
            }
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
        }
    }
}

// ---------- appProperties ↔ ClothingTags ----------

private fun ClothingTags.toAppProperties() = mapOf(
    "clothing_type"     to type,
    "clothing_category" to category,
    "clothing_uses"     to uses.joinToString(","),
    "clothing_colors"   to colors.joinToString(","),
)

private fun Map<String, String>.toClothingTags(): ClothingTags? {
    val type = getOrDefault("clothing_type", "")
    if (type.isEmpty()) return null
    return ClothingTags(
        type     = type,
        category = getOrDefault("clothing_category", ""),
        uses     = getOrDefault("clothing_uses", "").split(",").filter { it.isNotBlank() },
        colors   = getOrDefault("clothing_colors", "").split(",").filter { it.isNotBlank() },
    )
}
