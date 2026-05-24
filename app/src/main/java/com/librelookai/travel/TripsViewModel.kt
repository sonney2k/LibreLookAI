package com.librelookai.travel
import android.app.Application
import android.util.Log
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.google.gson.Gson
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import com.librelookai.auth.GoogleAuthManager
import com.librelookai.data.drive.DriveRepository
import com.librelookai.data.model.Outfit
import com.librelookai.data.model.Trip
import com.librelookai.gemini.GeminiRepository
import com.librelookai.gemini.UsageCategory
import com.librelookai.outfit.OutfitsViewModel
import com.librelookai.wardrobe.DriveImage
import com.librelookai.weather.wmoEmoji

/**
 * Backing state for the trips list and the per-trip viewer. Persisted to
 * `LibreLookAI/_trips/{tripId}.json` (one file per trip).
 */
data class TripsUiState(
    val trips: List<Trip> = emptyList(),
    val isLoading: Boolean = false,
    val error: String? = null,
    /** Drive folder ID of `_trips/` once resolved. */
    val folderId: String? = null,
    /**
     * ID of a trip that was just created+saved, so the viewer can show a one-time "saved"
     * confirmation. Cleared via [TripsViewModel.consumeJustSaved] once shown.
     */
    val justSavedTripId: String? = null,
    /**
     * Pending (un-persisted) bulk-refine result for [refinePreviewTripId]: outfitId → proposed
     * itemIds. The viewer renders this as a preview until the user replaces or discards it.
     */
    val refinePreviewTripId: String? = null,
    val refinePreview: Map<String, List<String>> = emptyMap(),
)

/**
 * Owns the collection of saved [Trip]s. Trip persistence is independent of outfit persistence:
 * the [Trip] only references outfit IDs and the outfits themselves live in their closet's
 * `_outfits.json`. Cascading deletes / outfit creation is orchestrated by [TravelScreen] via
 * the shared [com.librelookai.outfit.OutfitsViewModel].
 */
class TripsViewModel(app: Application) : AndroidViewModel(app) {

    companion object { private const val TAG = "TripsVM" }

    private val drive = DriveRepository(app, GoogleAuthManager(app))
    private val gemini = GeminiRepository(app)
    private val gson = Gson()

    private val _state = MutableStateFlow(TripsUiState())
    val state: StateFlow<TripsUiState> = _state.asStateFlow()

    /** Drive file IDs by trip id, so deletes/upserts can target the right file. */
    private val driveIdsByTripId = mutableMapOf<String, String>()

    private var rootFolderId: String? = null

    /**
     * One-shot navigation events emitted when a new trip is created or selected for viewing.
     * The Travel screen / MainActivity observe this to flip the viewer state.
     */
    private val _navigateToTrip = MutableSharedFlow<String>(extraBufferCapacity = 1)
    val navigateToTrip: SharedFlow<String> = _navigateToTrip.asSharedFlow()

    fun loadTrips() {
        viewModelScope.launch {
            _state.update { it.copy(isLoading = true, error = null) }
            val folderId = runCatching {
                val rootId = rootFolderId ?: drive.getOrCreateFolder().also { rootFolderId = it }
                _state.value.folderId ?: drive.getOrCreateTripsFolder(rootId)
            }.onFailure {
                Log.w(TAG, "trips folder resolve failed", it)
                _state.update { s -> s.copy(isLoading = false, error = it.message) }
            }.getOrNull() ?: return@launch
            _state.update { it.copy(folderId = folderId) }

            runCatching {
                val files = drive.listTripFiles(folderId)
                coroutineScope {
                    files.map { file ->
                        async {
                            val json = drive.loadTripJson(file.id) ?: return@async null
                            val trip = runCatching { gson.fromJson(json, Trip::class.java) }.getOrNull()
                                ?: return@async null
                            file.id to trip
                        }
                    }.awaitAll().filterNotNull()
                }
            }.onSuccess { pairs ->
                driveIdsByTripId.clear()
                pairs.forEach { (fileId, trip) -> driveIdsByTripId[trip.id] = fileId }
                _state.update {
                    it.copy(
                        trips = pairs.map { (_, t) -> t }.sortedByDescending { t -> t.createdAt },
                        isLoading = false,
                    )
                }
            }.onFailure { e ->
                _state.update { it.copy(isLoading = false, error = e.message) }
            }
        }
    }

    /** Upserts [trip] to Drive and adds/replaces it in state. */
    fun upsertTrip(trip: Trip, onDone: (Boolean) -> Unit = {}) {
        viewModelScope.launch {
            val folderId = _state.value.folderId ?: run {
                _state.update { it.copy(error = "Trips folder not ready.") }
                onDone(false); return@launch
            }
            runCatching {
                val fileId = drive.saveTripJson(folderId, trip.id, gson.toJson(trip))
                driveIdsByTripId[trip.id] = fileId
            }.onSuccess {
                _state.update { s ->
                    val without = s.trips.filterNot { it.id == trip.id }
                    s.copy(trips = (listOf(trip) + without))
                }
                onDone(true)
            }.onFailure { e ->
                Log.w(TAG, "saveTripJson failed", e)
                _state.update { it.copy(error = e.message) }
                onDone(false)
            }
        }
    }

    /**
     * Creates [trip] and immediately emits a navigate event so the UI opens its viewer.
     * The caller is responsible for persisting the trip's outfits (via [com.librelookai.outfit.OutfitsViewModel])
     * before calling this — at that point [trip.outfitIds] should already be populated.
     */
    fun createAndOpenTrip(trip: Trip) {
        upsertTrip(trip) { ok ->
            if (ok) {
                _state.update { it.copy(justSavedTripId = trip.id) }
                viewModelScope.launch { _navigateToTrip.emit(trip.id) }
            }
        }
    }

    /** Clears the one-time "just saved" flag after the viewer has shown its confirmation. */
    fun consumeJustSaved() = _state.update { it.copy(justSavedTripId = null) }

    fun renameTrip(tripId: String, newName: String) {
        val current = _state.value.trips.find { it.id == tripId } ?: return
        upsertTrip(current.copy(name = newName))
    }

    /** Replaces `outfitIds[dayIndex]` with [newOutfitId] (used after editing a day's outfit). */
    fun replaceOutfitAtDay(tripId: String, dayIndex: Int, newOutfitId: String) {
        val current = _state.value.trips.find { it.id == tripId } ?: return
        if (dayIndex !in current.outfitIds.indices) return
        if (current.outfitIds[dayIndex] == newOutfitId) return
        val updated = current.copy(
            outfitIds = current.outfitIds.toMutableList().also { it[dayIndex] = newOutfitId },
        )
        upsertTrip(updated)
    }

    /**
     * Deletes the trip from Drive and state. Returns the deleted trip's `outfitIds` via [onDeleted]
     * so the caller can decide whether to cascade-delete the outfits.
     */
    fun deleteTrip(tripId: String, onDeleted: (deletedOutfitIds: List<String>) -> Unit = {}) {
        val current = _state.value.trips.find { it.id == tripId } ?: return
        viewModelScope.launch {
            val driveFileId = driveIdsByTripId[tripId]
            if (driveFileId != null) {
                runCatching { drive.deleteTripJson(driveFileId) }
                    .onFailure { Log.w(TAG, "deleteTripJson failed", it) }
                driveIdsByTripId.remove(tripId)
            }
            _state.update { s -> s.copy(trips = s.trips.filterNot { it.id == tripId }) }
            onDeleted(current.outfitIds)
        }
    }

    /** Convenience: ask the UI to open [tripId] in the viewer. */
    fun openTrip(tripId: String) {
        viewModelScope.launch { _navigateToTrip.emit(tripId) }
    }

    fun clearError() = _state.update { it.copy(error = null) }

    /** Per-trip transient state for the bulk-refine bar. */
    private val _bulkRefining = MutableStateFlow<Set<String>>(emptySet())
    val bulkRefining: StateFlow<Set<String>> = _bulkRefining.asStateFlow()

    /**
     * One Gemini call that re-assigns items for every outfit in [tripId] subject to [instruction]
     * (e.g. "brighter clothes", "pack lighter — fewer distinct items"). The result is stored as an
     * un-persisted preview ([refinePreview]); nothing is saved until [applyRefinePreview].
     */
    fun refineAllOutfits(
        tripId: String,
        instruction: String,
        images: List<DriveImage>,
        currentOutfits: List<Outfit>,
        onDone: (Boolean) -> Unit = {},
    ) {
        val instr = instruction.trim()
        if (instr.isEmpty()) { onDone(false); return }
        val trip = _state.value.trips.find { it.id == tripId } ?: run { onDone(false); return }
        val tripOutfits = currentOutfits.filter { it.id in trip.outfitIds }
        if (tripOutfits.isEmpty()) { onDone(false); return }
        viewModelScope.launch {
            _bulkRefining.update { it + tripId }
            val prompt = buildBulkRefinePrompt(trip, tripOutfits, instr, images)
            Log.d(TAG, "Bulk-refine prompt length: ${prompt.length} chars")
            val raw = try {
                gemini.generateText(prompt, UsageCategory.TRAVEL, bulkItems = tripOutfits.size)
            } catch (e: com.librelookai.billing.InsufficientCreditsException) {
                _bulkRefining.update { it - tripId }
                onDone(false); return@launch
            }
            if (raw == null) {
                _bulkRefining.update { it - tripId }
                _state.update { it.copy(error = "Gemini did not respond.") }
                onDone(false); return@launch
            }
            val json = raw.trim().removePrefix("```json").removePrefix("```").removeSuffix("```").trim()
            data class OutfitUpdate(val id: String = "", val itemIds: List<String> = emptyList())
            data class BulkResp(val outfits: List<OutfitUpdate> = emptyList())
            val parsed = runCatching { gson.fromJson(json, BulkResp::class.java) }.getOrNull()
            if (parsed == null || parsed.outfits.isEmpty()) {
                Log.w(TAG, "bulk-refine parse failed: $json")
                _bulkRefining.update { it - tripId }
                _state.update { it.copy(error = "Could not parse Gemini response.") }
                onDone(false); return@launch
            }
            val knownItemIds = images.map { it.driveId }.toSet()
            val tripOutfitIds = trip.outfitIds.toSet()
            val updates = parsed.outfits.mapNotNull { up ->
                if (up.id !in tripOutfitIds) return@mapNotNull null
                val cleaned = up.itemIds.filter { it in knownItemIds }
                if (cleaned.isEmpty()) null else up.id to cleaned
            }.toMap()
            _bulkRefining.update { it - tripId }
            if (updates.isEmpty()) {
                _state.update { it.copy(error = "No usable refinement returned.") }
                onDone(false); return@launch
            }
            // Store as a preview — the user reviews it and explicitly replaces or discards.
            _state.update { it.copy(refinePreviewTripId = tripId, refinePreview = updates) }
            onDone(true)
        }
    }

    /** Persists the pending [refinePreview] (overwriting each outfit's items) and clears it. */
    fun applyRefinePreview(outfitsViewModel: OutfitsViewModel, onDone: (Boolean) -> Unit = {}) {
        val updates = _state.value.refinePreview
        if (updates.isEmpty()) { onDone(false); return }
        outfitsViewModel.updateOutfitItems(updates) { ok ->
            if (ok) _state.update { it.copy(refinePreviewTripId = null, refinePreview = emptyMap()) }
            onDone(ok)
        }
    }

    /** Drops the pending [refinePreview] without persisting. */
    fun discardRefinePreview() =
        _state.update { it.copy(refinePreviewTripId = null, refinePreview = emptyMap()) }
}

private fun buildBulkRefinePrompt(
    trip: Trip,
    outfits: List<Outfit>,
    instruction: String,
    images: List<DriveImage>,
): String = buildString {
    appendLine("You are helping the user refine ALL outfits in a multi-day trip in one shot.")
    appendLine()
    appendLine("## Trip")
    appendLine("- Name: ${trip.name}")
    appendLine("- Destination: ${trip.destination}")
    appendLine("- Days: ${trip.days}")
    if (trip.goal.isNotBlank()) appendLine("- Goal/notes: ${trip.goal}")
    if (trip.vibes.isNotEmpty()) appendLine("- Vibes: ${trip.vibes.joinToString(", ")}")
    if (trip.forecast.isNotEmpty()) {
        appendLine("- Forecast:")
        trip.forecast.forEachIndexed { i, f ->
            appendLine("  - Day ${i + 1}: ${f.minTempC.toInt()}–${f.maxTempC.toInt()}°C, WMO ${f.weatherCode} ${wmoEmoji(f.weatherCode)}")
        }
    }
    appendLine()
    appendLine("## User instruction (apply to ALL outfits)")
    appendLine(instruction)
    appendLine()
    appendLine("## Current outfits (id, day, current items)")
    outfits.forEachIndexed { i, o ->
        val items = o.itemIds.joinToString(",", "[", "]") { "\"$it\"" }
        appendLine("""{"id":"${o.id}","day":${i + 1},"name":"${o.name}","items":$items}""")
    }
    appendLine()
    appendLine("## Available wardrobe (id, name, tags)")
    val wardrobeJson = images.joinToString(",", "[", "]") { img ->
        val t = img.tags
        if (t == null) """{"id":"${img.driveId}","name":"${img.name}","tags":null}"""
        else """{"id":"${img.driveId}","name":"${img.name}","tags":{"type":"${t.type}","category":"${t.category}","colors":${t.colors.joinToString(",", "[", "]") { "\"$it\"" }}}}"""
    }
    appendLine(wardrobeJson)
    appendLine()
    appendLine("Return ONLY a JSON object — no markdown, no extra text — keeping the same outfit ids:")
    append("""{"outfits":[{"id":"<existingOutfitId>","itemIds":["<wardrobeItemId>"]}]}""")
}

