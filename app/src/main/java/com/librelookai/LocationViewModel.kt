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

    init {
        // Phase 1: restore last-known locations from SharedPreferences — instant, no network.
        restoreFromCache()
        // Phase 2: refresh from Drive in the background.
        loadLocations()
    }

    /**
     * Synchronously restores the last-known locations from SharedPreferences so the wardrobe
     * screen can start loading its disk cache immediately, without waiting for any Drive call.
     */
    private fun restoreFromCache() {
        val cachedJson = prefs.getString(PREF_LOCATIONS_JSON, null) ?: return
        val savedActiveId = prefs.getString(PREF_ACTIVE_ID, null) ?: return
        val type = object : TypeToken<List<Location>>() {}.type
        val locations: List<Location> = runCatching {
            gson.fromJson<List<Location>>(cachedJson, type) ?: emptyList()
        }.getOrNull() ?: return
        if (locations.isEmpty()) return
        val activeId = if (locations.any { it.id == savedActiveId }) savedActiveId else locations[0].id
        // isLoading stays true so the UI knows a Drive refresh is still in flight.
        _state.value = LocationUiState(locations = locations, activeLocationId = activeId, isLoading = true)
    }

    fun loadLocations() {
        viewModelScope.launch {
            // Don't reset existing cached locations to empty while refreshing.
            _state.update { it.copy(isLoading = true, error = null) }
            runCatching {
                val rootId = drive.getOrCreateFolder().also {
                    rootFolderId = it
                    prefs.edit().putString(PREF_ROOT_FOLDER_ID, it).apply()
                }
                val savedActiveId = prefs.getString(PREF_ACTIVE_ID, null)

                val json = drive.loadLocationsJson(rootId)
                val locations: List<Location> = if (json != null) {
                    val type = object : TypeToken<List<Location>>() {}.type
                    gson.fromJson(json, type) ?: emptyList()
                } else emptyList()

                if (locations.isEmpty()) {
                    // First run: default "Home" location pointing at the root folder.
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
                prefs.edit().putString(PREF_LOCATIONS_JSON, gson.toJson(locations)).apply()
                _state.update { it.copy(locations = locations, activeLocationId = activeId, isLoading = false) }
            }.onFailure { e ->
                // If we already have cached locations, just stop the loading spinner; don't
                // replace working content with an error banner.
                _state.update { s ->
                    s.copy(isLoading = false, error = if (s.locations.isEmpty()) e.message else null)
                }
            }
        }
    }

    fun setActiveLocation(locationId: String) {
        prefs.edit().putString(PREF_ACTIVE_ID, locationId).apply()
        _state.update { it.copy(activeLocationId = locationId) }
    }

    fun addLocation(name: String, geoLocation: String = "") {
        if (name.isBlank()) return
        viewModelScope.launch {
            runCatching {
                val rootId = rootFolderId ?: drive.getOrCreateFolder().also { rootFolderId = it }
                val newFolderId = drive.createSubfolder(rootId, name.trim())
                val newLocation = Location(name = name.trim(), folderId = newFolderId, geoLocation = geoLocation.trim())
                val updated = _state.value.locations + newLocation
                drive.saveLocationsJson(rootId, gson.toJson(updated))
                updated
            }.onSuccess { updated ->
                prefs.edit().putString(PREF_LOCATIONS_JSON, gson.toJson(updated)).apply()
                _state.update { it.copy(locations = updated) }
            }.onFailure { e ->
                _state.update { it.copy(error = e.message) }
            }
        }
    }

    fun renameLocation(locationId: String, newName: String, geoLocation: String? = null) {
        if (newName.isBlank()) return
        viewModelScope.launch {
            val updated = _state.value.locations.map {
                if (it.id == locationId) it.copy(name = newName.trim(), geoLocation = geoLocation?.trim() ?: it.geoLocation) else it
            }
            runCatching {
                val rootId = rootFolderId ?: drive.getOrCreateFolder().also { rootFolderId = it }
                drive.saveLocationsJson(rootId, gson.toJson(updated))
            }.onSuccess {
                prefs.edit().putString(PREF_LOCATIONS_JSON, gson.toJson(updated)).apply()
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
                prefs.edit().putString(PREF_LOCATIONS_JSON, gson.toJson(updated)).apply()
                _state.update { it.copy(locations = updated, activeLocationId = newActiveId) }
            }.onFailure { e ->
                _state.update { it.copy(error = e.message) }
            }
        }
    }

    /**
     * Returns the folderId for a location with [name] (case-insensitive), creating it if absent.
     * [onResult] is called on the main thread with the folderId, or null on failure.
     */
    fun getOrCreateLocation(name: String, onResult: (String?) -> Unit) {
        viewModelScope.launch {
            runCatching {
                val existing = _state.value.locations.find { it.name.equals(name, ignoreCase = true) }
                if (existing != null) {
                    existing.folderId
                } else {
                    val rootId = rootFolderId ?: drive.getOrCreateFolder().also { rootFolderId = it }
                    val newFolderId = drive.createSubfolder(rootId, name)
                    val newLocation = Location(name = name, folderId = newFolderId)
                    val updated = _state.value.locations + newLocation
                    drive.saveLocationsJson(rootId, gson.toJson(updated))
                    prefs.edit().putString(PREF_LOCATIONS_JSON, gson.toJson(updated)).apply()
                    _state.update { it.copy(locations = updated) }
                    newFolderId
                }
            }.onSuccess { onResult(it) }
             .onFailure { e ->
                _state.update { it.copy(error = e.message) }
                onResult(null)
            }
        }
    }

    fun clearError() = _state.update { it.copy(error = null) }

    companion object {
        private const val PREF_ACTIVE_ID = "active_location_id"
        private const val PREF_ROOT_FOLDER_ID = "root_folder_id"
        private const val PREF_LOCATIONS_JSON = "locations_json"
        const val ALL_LOCATIONS_ID = "all_locations"
    }
}
