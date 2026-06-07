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
import com.librelookai.data.model.Outfit
import com.librelookai.data.model.OutfitEvent
import com.librelookai.data.model.WearSource
import com.librelookai.util.isNetworkAvailable
import com.librelookai.wardrobe.DriveImage
import com.librelookai.weather.WeatherData

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

    /**
     * Logs a wear of [outfit] on [date] (today by default), capturing a tag/color/weather snapshot
     * so the wear stays a usable taste signal even if the outfit is later edited or deleted.
     * [source] records whether the user actively chose the outfit (strong signal) or accepted an
     * AI suggestion (weak signal). See [buildOutfitEvent] / [buildWearHistorySummary].
     */
    fun recordOutfit(
        outfit: Outfit,
        imagesById: Map<String, DriveImage>,
        source: WearSource = WearSource.MANUAL,
        weather: WeatherData? = null,
        date: LocalDate = LocalDate.now(),
    ) {
        val event = buildOutfitEvent(outfit, imagesById, source, weather, date)
        persist(_state.value.events + event)
    }

    /**
     * Move a logged wear to [newDate], recomputing whether it is now a future (planned) event so it
     * is correctly excluded from taste stats while planned and counted once it is in the past.
     */
    fun moveEvent(eventId: String, newDate: LocalDate) {
        persist(_state.value.events.map {
            if (it.id == eventId) {
                it.copy(date = newDate.toString(), planned = newDate.isAfter(LocalDate.now()))
            } else it
        })
    }

    /**
     * Move every wear in [eventIds] to [newDate] in a single persist — used by the calendar's
     * long-press-then-tap flow, which moves a whole day's outfits at once (a sequence of single
     * [moveEvent] calls would race, since each reads the pre-update event list).
     */
    fun moveEvents(eventIds: Set<String>, newDate: LocalDate) {
        if (eventIds.isEmpty()) return
        val planned = newDate.isAfter(LocalDate.now())
        val ds = newDate.toString()
        persist(_state.value.events.map {
            if (it.id in eventIds) it.copy(date = ds, planned = planned) else it
        })
    }

    /**
     * Copy a logged wear to [newDate] as a brand-new event (fresh id), preserving the taste snapshot.
     * Leaves the original wear in place.
     */
    fun copyEvent(eventId: String, newDate: LocalDate) {
        val src = _state.value.events.firstOrNull { it.id == eventId } ?: return
        val copy = src.copy(
            id = java.util.UUID.randomUUID().toString(),
            date = newDate.toString(),
            createdAt = System.currentTimeMillis(),
            planned = newDate.isAfter(LocalDate.now()),
        )
        persist(_state.value.events + copy)
    }

    /** Toggle the explicit "loved it" feedback on a logged wear. */
    fun setEventLoved(eventId: String, loved: Boolean) {
        persist(_state.value.events.map { if (it.id == eventId) it.copy(loved = loved) else it })
    }

    /**
     * Set the "loved" flag on every logged wear of an outfit — backs the heart in the aggregated
     * wear-stats list, where a row represents all wears of one outfit rather than a single event.
     */
    fun setOutfitLoved(outfitId: String, loved: Boolean) {
        persist(_state.value.events.map { if (it.outfitId == outfitId) it.copy(loved = loved) else it })
    }

    private fun persist(updated: List<OutfitEvent>) {
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
