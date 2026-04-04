package com.librelookai

import android.app.Application
import android.content.Intent
import androidx.activity.result.ActivityResult
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.google.android.gms.auth.api.signin.GoogleSignIn
import com.google.android.gms.common.api.ApiException
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.io.File

enum class WardrobeView { SIGN_IN, GRID, CAPTURE }

data class DriveImage(val driveId: String, val localPath: String, val name: String)

data class WardrobeUiState(
    val view: WardrobeView = WardrobeView.SIGN_IN,
    val images: List<DriveImage> = emptyList(),
    val isLoading: Boolean = false,
    val isUploading: Boolean = false,
    val error: String? = null,
)

class WardrobeViewModel(app: Application) : AndroidViewModel(app) {

    private val auth = GoogleAuthManager(app)
    private val drive = DriveRepository(app, auth)

    private val _state = MutableStateFlow(WardrobeUiState())
    val state: StateFlow<WardrobeUiState> = _state.asStateFlow()

    /** Cached Drive folder ID to avoid repeated lookups. */
    private var folderId: String? = null

    init {
        if (auth.isSignedIn()) loadImages()
    }

    fun getSignInIntent(): Intent = auth.getSignInIntent()

    fun onSignInResult(result: ActivityResult) {
        try {
            GoogleSignIn.getSignedInAccountFromIntent(result.data)
                .getResult(ApiException::class.java)
            loadImages()
        } catch (e: ApiException) {
            _state.update { it.copy(error = "Sign-in failed (code ${e.statusCode})") }
        }
    }

    fun loadImages() {
        viewModelScope.launch {
            _state.update { it.copy(view = WardrobeView.GRID, isLoading = true, error = null) }
            runCatching {
                val id = folderId ?: drive.getOrCreateFolder().also { folderId = it }
                val files = drive.listFiles(id)
                // Download any un-cached files in parallel
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

    /** Called when the user takes a photo. Uploads to Drive then prepends to the wardrobe grid. */
    fun uploadPhoto(file: File) {
        viewModelScope.launch {
            _state.update { it.copy(view = WardrobeView.GRID, isUploading = true, error = null) }
            runCatching {
                val id = folderId ?: drive.getOrCreateFolder().also { folderId = it }
                val uploaded = drive.uploadImage(id, file)
                // Move captured file into the permanent cache under its Drive ID
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

    fun signOut() {
        viewModelScope.launch {
            auth.signOut()
            folderId = null
            _state.value = WardrobeUiState()
        }
    }

    fun clearError() = _state.update { it.copy(error = null) }
}
