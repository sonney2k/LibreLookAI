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
import java.io.File
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

    private fun outfitsLocalCacheFile(id: String) =
        File(getApplication<Application>().filesDir, "outfits_cache_${id}.json")

    fun setLocation(newFolderId: String) {
        if (folderId == newFolderId) return
        folderId = newFolderId
        _state.update { OutfitsUiState(isLoading = true) }
        loadOutfits()
    }

    fun loadOutfits() {
        viewModelScope.launch {
            _state.update { it.copy(isLoading = true, error = null) }
            val id = folderId ?: run { _state.update { it.copy(isLoading = false) }; return@launch }

            // Phase 1 — instant: show local cache
            val cacheFile = outfitsLocalCacheFile(id)
            if (cacheFile.exists()) {
                runCatching {
                    val type = object : TypeToken<List<OutfitEvent>>() {}.type
                    gson.fromJson<List<OutfitEvent>>(cacheFile.readText(), type) ?: emptyList()
                }.onSuccess { events ->
                    if (events.isNotEmpty()) _state.update { it.copy(events = events, isLoading = false) }
                }
            }

            // Phase 2 — Drive sync: skip when offline
            if (!getApplication<Application>().isNetworkAvailable()) {
                _state.update { it.copy(isLoading = false) }
                return@launch
            }
            runCatching {
                val json = drive.loadOutfitsJson(id)
                if (json != null) {
                    val type = object : TypeToken<List<OutfitEvent>>() {}.type
                    gson.fromJson<List<OutfitEvent>>(json, type) ?: emptyList()
                } else emptyList()
            }.onSuccess { events ->
                runCatching { cacheFile.writeText(gson.toJson(events)) }
                _state.update { it.copy(events = events, isLoading = false) }
            }.onFailure { e ->
                _state.update { s ->
                    s.copy(isLoading = false, error = if (s.events.isEmpty()) e.message else null)
                }
            }
        }
    }

    fun recordOutfit(styleId: String) {
        val event = OutfitEvent(styleId = styleId, date = LocalDate.now().toString())
        val updated = _state.value.events + event
        viewModelScope.launch {
            val id = folderId ?: return@launch
            runCatching {
                drive.saveOutfitsJson(id, gson.toJson(updated))
            }.onSuccess {
                runCatching { outfitsLocalCacheFile(id).writeText(gson.toJson(updated)) }
                _state.update { it.copy(events = updated) }
            }.onFailure { e ->
                _state.update { it.copy(error = e.message) }
            }
        }
    }

    fun clearError() = _state.update { it.copy(error = null) }
}
