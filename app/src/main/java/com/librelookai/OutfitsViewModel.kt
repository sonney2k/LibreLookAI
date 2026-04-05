package com.librelookai

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.time.LocalDate

data class OutfitsUiState(
    val events: List<OutfitEvent> = emptyList(),
    val isLoading: Boolean = false,
    val error: String? = null,
)

class OutfitsViewModel(app: Application) : AndroidViewModel(app) {

    private val drive = DriveRepository(app, GoogleAuthManager(app))
    private val gson = Gson()
    private var folderId: String? = null

    private val _state = MutableStateFlow(OutfitsUiState())
    val state: StateFlow<OutfitsUiState> = _state.asStateFlow()

    init { loadOutfits() }

    fun loadOutfits() {
        viewModelScope.launch {
            _state.update { it.copy(isLoading = true, error = null) }
            runCatching {
                val id = folderId ?: drive.getOrCreateFolder().also { folderId = it }
                val json = drive.loadOutfitsJson(id)
                if (json != null) {
                    val type = object : TypeToken<List<OutfitEvent>>() {}.type
                    gson.fromJson<List<OutfitEvent>>(json, type) ?: emptyList()
                } else emptyList()
            }.onSuccess { events ->
                _state.update { it.copy(events = events, isLoading = false) }
            }.onFailure { e ->
                _state.update { it.copy(isLoading = false, error = e.message) }
            }
        }
    }

    fun recordOutfit(styleId: String) {
        val event = OutfitEvent(styleId = styleId, date = LocalDate.now().toString())
        val updated = _state.value.events + event
        viewModelScope.launch {
            runCatching {
                val id = folderId ?: drive.getOrCreateFolder().also { folderId = it }
                drive.saveOutfitsJson(id, gson.toJson(updated))
            }.onSuccess {
                _state.update { it.copy(events = updated) }
            }.onFailure { e ->
                _state.update { it.copy(error = e.message) }
            }
        }
    }

    fun clearError() = _state.update { it.copy(error = null) }
}
