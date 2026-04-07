package com.librelookai

import android.app.Application
import android.content.Context
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

data class LocationUiState(
    val locations: List<Location> = emptyList(),
    val activeLocationId: String = "",
    val isLoading: Boolean = true,
    val error: String? = null,
)

class LocationViewModel(app: Application) : AndroidViewModel(app) {

    private val drive = DriveRepository(app, GoogleAuthManager(app))
    private val gson = Gson()
    private val prefs = app.getSharedPreferences("librelookai_prefs", Context.MODE_PRIVATE)
    private var rootFolderId: String? = null

    private val _state = MutableStateFlow(LocationUiState())
    val state: StateFlow<LocationUiState> = _state.asStateFlow()

    /** The folderId for the currently active location, or null while loading. */
    val activeFolderId: String?
        get() {
            val s = _state.value
            return s.locations.find { it.id == s.activeLocationId }?.folderId
        }

    init { loadLocations() }

    fun loadLocations() {
        viewModelScope.launch {
            _state.update { it.copy(isLoading = true, error = null) }
            runCatching {
                val rootId = drive.getOrCreateFolder().also { rootFolderId = it }
                val savedActiveId = prefs.getString(PREF_ACTIVE_ID, null)

                val json = drive.loadLocationsJson(rootId)
                val locations: List<Location> = if (json != null) {
                    val type = object : TypeToken<List<Location>>() {}.type
                    gson.fromJson(json, type) ?: emptyList()
                } else emptyList()

                if (locations.isEmpty()) {
                    // First run: default "Home" location pointing at the root folder
                    val defaultLocation = Location(name = "Home", folderId = rootId)
                    val newList = listOf(defaultLocation)
                    drive.saveLocationsJson(rootId, gson.toJson(newList))
                    prefs.edit().putString(PREF_ACTIVE_ID, defaultLocation.id).apply()
                    Pair(newList, defaultLocation.id)
                } else {
                    val activeId = if (savedActiveId != null && locations.any { it.id == savedActiveId }) {
                        savedActiveId
                    } else {
                        locations[0].id.also { prefs.edit().putString(PREF_ACTIVE_ID, it).apply() }
                    }
                    Pair(locations, activeId)
                }
            }.onSuccess { (locations, activeId) ->
                _state.update { it.copy(locations = locations, activeLocationId = activeId, isLoading = false) }
            }.onFailure { e ->
                _state.update { it.copy(isLoading = false, error = e.message) }
            }
        }
    }

    fun setActiveLocation(locationId: String) {
        prefs.edit().putString(PREF_ACTIVE_ID, locationId).apply()
        _state.update { it.copy(activeLocationId = locationId) }
    }

    fun addLocation(name: String) {
        if (name.isBlank()) return
        viewModelScope.launch {
            runCatching {
                val rootId = rootFolderId ?: drive.getOrCreateFolder().also { rootFolderId = it }
                val newFolderId = drive.createSubfolder(rootId, name.trim())
                val newLocation = Location(name = name.trim(), folderId = newFolderId)
                val updated = _state.value.locations + newLocation
                drive.saveLocationsJson(rootId, gson.toJson(updated))
                updated
            }.onSuccess { updated ->
                _state.update { it.copy(locations = updated) }
            }.onFailure { e ->
                _state.update { it.copy(error = e.message) }
            }
        }
    }

    fun renameLocation(locationId: String, newName: String) {
        if (newName.isBlank()) return
        viewModelScope.launch {
            val updated = _state.value.locations.map {
                if (it.id == locationId) it.copy(name = newName.trim()) else it
            }
            runCatching {
                val rootId = rootFolderId ?: drive.getOrCreateFolder().also { rootFolderId = it }
                drive.saveLocationsJson(rootId, gson.toJson(updated))
            }.onSuccess {
                _state.update { it.copy(locations = updated) }
            }.onFailure { e ->
                _state.update { it.copy(error = e.message) }
            }
        }
    }

    fun deleteLocation(locationId: String) {
        val current = _state.value
        if (current.locations.size <= 1) return
        viewModelScope.launch {
            val updated = current.locations.filter { it.id != locationId }
            runCatching {
                val rootId = rootFolderId ?: drive.getOrCreateFolder().also { rootFolderId = it }
                drive.saveLocationsJson(rootId, gson.toJson(updated))
            }.onSuccess {
                val newActiveId = if (current.activeLocationId == locationId) {
                    updated[0].id.also { prefs.edit().putString(PREF_ACTIVE_ID, it).apply() }
                } else current.activeLocationId
                _state.update { it.copy(locations = updated, activeLocationId = newActiveId) }
            }.onFailure { e ->
                _state.update { it.copy(error = e.message) }
            }
        }
    }

    fun clearError() = _state.update { it.copy(error = null) }

    companion object {
        private const val PREF_ACTIVE_ID = "active_location_id"
    }
}
