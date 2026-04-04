package com.librelookai

import android.app.Application
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

data class DriveImage(val driveId: String, val localPath: String, val name: String)

data class WardrobeUiState(
    val view: WardrobeView = WardrobeView.GRID,
    val images: List<DriveImage> = emptyList(),
    val isLoading: Boolean = false,
    val isUploading: Boolean = false,
    val error: String? = null,
)

class WardrobeViewModel(app: Application) : AndroidViewModel(app) {

    private val drive = DriveRepository(app, GoogleAuthManager(app))

    private val _state = MutableStateFlow(WardrobeUiState())
    val state: StateFlow<WardrobeUiState> = _state.asStateFlow()

    private var folderId: String? = null

    init {
        loadImages()
    }

    fun loadImages() {
        viewModelScope.launch {
            _state.update { it.copy(isLoading = true, error = null) }
            runCatching {
                val id = folderId ?: drive.getOrCreateFolder().also { folderId = it }
                val files = drive.listFiles(id)
                files.map { file ->
                    async { drive.cachedFile(file.id) ?: drive.downloadToCache(file.id) }
                }.awaitAll()
                files.mapNotNull { file ->
                    drive.cachedFile(file.id)?.let { DriveImage(file.id, it.absolutePath, file.name) }
                }
            }.onSuccess { images ->
                _state.update { it.copy(images = images, isLoading = false) }
            }.onFailure { e ->
                _state.update { it.copy(isLoading = false, error = e.message) }
            }
        }
    }

    fun openCapture() = _state.update { it.copy(view = WardrobeView.CAPTURE) }
    fun closeCapture() = _state.update { it.copy(view = WardrobeView.GRID) }

    fun uploadPhoto(file: File) {
        viewModelScope.launch {
            _state.update { it.copy(view = WardrobeView.GRID, isUploading = true, error = null) }
            runCatching {
                val id = folderId ?: drive.getOrCreateFolder().also { folderId = it }
                val uploaded = drive.uploadImage(id, file)
                val cacheFile = File(drive.cacheDir, "${uploaded.id}.jpg")
                file.copyTo(cacheFile, overwrite = true)
                DriveImage(uploaded.id, cacheFile.absolutePath, uploaded.name)
            }.onSuccess { newImage ->
                _state.update { it.copy(isUploading = false, images = listOf(newImage) + it.images) }
            }.onFailure { e ->
                _state.update { it.copy(isUploading = false, error = e.message) }
            }
        }
    }

    fun clearError() = _state.update { it.copy(error = null) }
}
