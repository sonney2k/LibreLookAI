package com.librelookai.outfit
import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.io.File
import java.time.LocalDate
import com.librelookai.auth.GoogleAuthManager
import com.librelookai.data.drive.DriveRepository
import com.librelookai.data.drive.loadOutfitEventsJson
import com.librelookai.data.drive.saveOutfitEventsJson
import com.librelookai.data.model.OutfitEvent
import com.librelookai.util.isNetworkAvailable

data class OutfitEventsUiState(
    val events: List<OutfitEvent> = emptyList(),
    val isLoading: Boolean = false,
    val error: String? = null,
)

class OutfitEventsViewModel(app: Application) : AndroidViewModel(app) {

    private val drive = DriveRepository(app, GoogleAuthManager(app))
    private val gson = Gson()
    private var folderId: String? = null
    private var allFolderIds: List<String>? = null

    private val _state = MutableStateFlow(OutfitEventsUiState())
    val state: StateFlow<OutfitEventsUiState> = _state.asStateFlow()
    private var loadJob: Job? = null

    private fun eventsLocalCacheFile(id: String) =
        File(getApplication<Application>().filesDir, "outfit_events_cache_${id}.json")

    fun setLocation(newFolderId: String) {
        if (folderId == newFolderId && allFolderIds == null) return
        folderId = newFolderId
        allFolderIds = null
        _state.update { OutfitEventsUiState(isLoading = true) }
        loadEvents()
    }

    fun setAllLocations(folderIds: List<String>) {
        if (folderId == null && allFolderIds?.toSet() == folderIds.toSet()) return
        folderId = null
        allFolderIds = folderIds.toList()
        _state.update { OutfitEventsUiState(isLoading = true) }
        loadEvents()
    }

    fun loadEvents() {
        val ids = if (allFolderIds != null) allFolderIds!! else listOfNotNull(folderId)
        if (ids.isEmpty()) { _state.update { it.copy(isLoading = false) }; return }
        loadJob?.cancel()
        loadJob = viewModelScope.launch {
            _state.update { it.copy(isLoading = true, error = null) }

            // Phase 1 — instant: show merged local cache from all folders
            val cachedAll = ids.flatMap { id ->
                val cacheFile = eventsLocalCacheFile(id)
                if (cacheFile.exists()) {
                    runCatching {
                        val type = object : TypeToken<List<OutfitEvent>>() {}.type
                        gson.fromJson<List<OutfitEvent>>(cacheFile.readText(), type) ?: emptyList()
                    }.getOrDefault(emptyList())
                } else emptyList()
            }
            if (cachedAll.isNotEmpty()) _state.update { it.copy(events = cachedAll, isLoading = false) }

            // Phase 2 — Drive sync: skip when offline
            if (!getApplication<Application>().isNetworkAvailable()) {
                _state.update { it.copy(isLoading = false) }
                return@launch
            }
            runCatching {
                ids.flatMap { id ->
                    val json = drive.loadOutfitEventsJson(id)
                    if (json != null) {
                        val type = object : TypeToken<List<OutfitEvent>>() {}.type
                        val events: List<OutfitEvent> = gson.fromJson(json, type) ?: emptyList()
                        // Update per-folder cache
                        runCatching { eventsLocalCacheFile(id).writeText(gson.toJson(events)) }
                        events
                    } else emptyList()
                }
            }.onSuccess { events ->
                _state.update { it.copy(events = events, isLoading = false) }
            }.onFailure { e ->
                _state.update { s ->
                    s.copy(isLoading = false, error = if (s.events.isEmpty()) e.message else null)
                }
            }
        }
    }

    fun recordOutfit(outfitId: String) {
        val event = OutfitEvent(outfitId = outfitId, date = LocalDate.now().toString())
        val updated = _state.value.events + event
        viewModelScope.launch {
            val id = folderId ?: allFolderIds?.firstOrNull() ?: return@launch
            runCatching {
                drive.saveOutfitEventsJson(id, gson.toJson(updated))
            }.onSuccess {
                runCatching { eventsLocalCacheFile(id).writeText(gson.toJson(updated)) }
                _state.update { it.copy(events = updated) }
            }.onFailure { e ->
                _state.update { it.copy(error = e.message) }
            }
        }
    }

    fun clearError() = _state.update { it.copy(error = null) }
}
