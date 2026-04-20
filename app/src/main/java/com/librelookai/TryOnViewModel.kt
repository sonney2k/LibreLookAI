package com.librelookai

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.io.File

data class TryOnUiState(
    val isGenerating: Boolean = false,
    /** Local absolute path of the generated image, or null when none has been produced yet. */
    val resultPath: String? = null,
    val error: String? = null,
)

/**
 * Orchestrates the "Try it on me" feature: takes the user's reference photos and a list
 * of clothing item images, sends them to [GeminiRepository.tryOnOutfit], and exposes the
 * generated composite image via [state].
 */
class TryOnViewModel(app: Application) : AndroidViewModel(app) {

    private val gemini = GeminiRepository(app)
    private val drive = DriveRepository(app, GoogleAuthManager(app))

    private val _state = MutableStateFlow(TryOnUiState())
    val state: StateFlow<TryOnUiState> = _state.asStateFlow()

    /**
     * Runs the try-on. [personFiles] are the user's reference photos (front/side/back),
     * [itemFiles] are the clothing cutout/original images to dress the person with.
     * [preferences] is optional profile free-text that tunes the generation.
     */
    fun generate(personFiles: List<File>, itemFiles: List<File>, preferences: String = "") {
        if (personFiles.isEmpty() || itemFiles.isEmpty()) {
            _state.update { it.copy(error = "Missing photos or items") }
            return
        }
        viewModelScope.launch {
            _state.update { it.copy(isGenerating = true, error = null, resultPath = null) }
            val outDir = File(drive.cacheDir, "tryon_results").apply { mkdirs() }
            val result = gemini.tryOnOutfit(personFiles, itemFiles, outDir, preferences)
            if (result == null) {
                _state.update { it.copy(isGenerating = false, error = "Generation failed") }
            } else {
                _state.update { it.copy(isGenerating = false, resultPath = result.absolutePath) }
            }
        }
    }

    fun dismiss() {
        _state.update { TryOnUiState() }
    }

    fun clearError() = _state.update { it.copy(error = null) }
}
