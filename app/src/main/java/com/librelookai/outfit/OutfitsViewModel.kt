package com.librelookai.outfit

import android.app.Application
import android.content.Context
import android.telephony.TelephonyManager
import android.util.Log
import androidx.lifecycle.AndroidViewModel
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import androidx.lifecycle.viewModelScope
import com.google.gson.Gson
import com.librelookai.MainActivity
import com.librelookai.data.drive.DriveRepository
import com.librelookai.data.model.Outfit
import com.librelookai.gemini.GeminiRepository
import com.librelookai.gemini.PromptKey
import com.librelookai.gemini.PromptStore
import com.librelookai.gemini.UsageCategory
import com.librelookai.settings.AiConsiderations
import com.librelookai.settings.AppLanguage
import com.librelookai.settings.UserPreferences
import com.librelookai.util.Analytics
import com.librelookai.util.isNetworkAvailable
import com.librelookai.data.session.ClosetSessionHolder
import com.librelookai.wardrobe.DriveImage
import com.librelookai.weather.WeatherData
import java.util.Locale
import java.util.UUID
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

@OptIn(ExperimentalCoroutinesApi::class)
@HiltViewModel
class OutfitsViewModel @Inject constructor(
    app: Application,
    internal val drive: DriveRepository,
    internal val gemini: GeminiRepository,
    private val outfitsRepo: OutfitsRepository,
    session: ClosetSessionHolder,
    eventStore: com.librelookai.data.local.OutfitEventStore,
) : AndroidViewModel(app) {
    /** Weekly, location-specific cache around the expensive Gemini trend lookup. */
    internal val trendsCache = com.librelookai.gemini.FashionTrendsCache(app, drive, gemini)
    internal val gson = Gson()
    /** Active load scope + save target live on [outfitsRepo] now (§ 5 slice 8); exposed read-only
     *  for the VM extensions (composer / prediction / tags) and the mutators that read them. */
    internal val folderId: String? get() = outfitsRepo.folderId
    internal val saveFolderId: String? get() = outfitsRepo.saveFolderId

    internal val _state = MutableStateFlow(OutfitsUiState())
    val state: StateFlow<OutfitsUiState> = _state.asStateFlow()

    init {
        // Mirror the repo's store-derived flows (§ 5 slice 8) into UiState so screen consumers
        // read `state.outfits`/`state.wardrobeImages` unchanged. The repo owns the scope + the
        // Room-invalidation derivation (§ 5 slice 4b).
        viewModelScope.launch {
            outfitsRepo.outfits.collect { outfits -> _state.update { it.copy(outfits = outfits) } }
        }
        viewModelScope.launch {
            outfitsRepo.wardrobeImages.collect { images -> _state.update { it.copy(wardrobeImages = images) } }
        }
        // Derive the load scope and save target from the shared closet session (replaces the
        // AppContent fan-out bridge). Outfits always load from ALL locations — never filtered
        // by the closet filter; the active closet only steers where new outfits save.
        viewModelScope.launch {
            session.session.collect { s ->
                setAllLocations(s.closetFolderIds)
                s.saveFolderId?.let { outfitsRepo.saveFolderId = it }
            }
        }
        // Wear history is a DB read (refactor § 5 slice 3): collect the calendar events in
        // the current closet scope straight from the store — replaces the events→styles
        // mirror AppContent used to run.
        viewModelScope.launch {
            wearHistoryFlow(session, eventStore).collect { events ->
                _state.update { it.copy(wearHistory = events) }
            }
        }
    }

    private fun setAllLocations(folderIds: List<String>) {
        if (outfitsRepo.folderId == null && outfitsRepo.allFolderIds?.toSet() == folderIds.toSet()) return
        outfitsRepo.setScope(folderIds)
        _state.update { OutfitsUiState(isLoading = true) }
        loadOutfits()
    }

    // ---------- Load ----------

    /** Thin delegator to [OutfitsRepository.persistOutfitFolders] — the VM extensions (composer /
     *  prediction / tags) and mutators call this; the funnel + serialization live in the repo. */
    internal suspend fun persistOutfitFolders(styles: List<Outfit>, affected: Collection<String>) =
        outfitsRepo.persistOutfitFolders(styles, affected)

    fun loadOutfits() {
        viewModelScope.launch {
            _state.update { it.copy(isLoading = true, error = null) }
            // Phase 1 — the derived view paints the store by itself; probe it only for flags.
            val cached = outfitsRepo.cachedOutfits()
            if (cached.isNotEmpty()) _state.update { it.copy(isLoading = false) }

            // Phase 2 — Drive sync: skip when offline
            if (!getApplication<Application>().isNetworkAvailable()) {
                _state.update { it.copy(isLoading = false) }
                return@launch
            }
            outfitsRepo.syncOutfitsFromDrive()
                .onSuccess {
                    // The repo wrote each folder's store rows — the derived view followed.
                    _state.update { it.copy(isLoading = false) }
                }
                .onFailure { e ->
                    _state.update { s ->
                        s.copy(isLoading = false, error = if (s.outfits.isEmpty()) e.message else null)
                    }
                }
        }
    }

    // ---------- Create flow ----------

    /** Opens the unified composer for an existing saved style (update-in-place on save). */
    fun startEditing(
        style: Outfit,
        images: List<DriveImage>,
        prefs: UserPreferences?,
        tripContext: TripContext? = null,
    ) {
        openComposer(
            seedItemIds        = style.itemIds.toSet(),
            images             = images,
            prefs              = prefs,
            initialName        = style.name,
            initialDescription = style.description,
            editingStyleId     = style.id,
            tripContext        = tripContext,
        )
    }

    fun clearPendingWear() = _state.update { it.copy(pendingWearOutfitId = null) }

    /**
     * Ask the Calendar sub-tab to enter "tap a day to wear [outfitId]" mode. Set by a Wear action on
     * the outfit list / detail viewer (the caller also switches to the Calendar tab) so the user
     * picks the wear day in context. Consumed by [OutfitCalendarTab] via [consumeCalendarWear].
     */
    fun requestCalendarWear(outfitId: String, source: com.librelookai.data.model.WearSource = com.librelookai.data.model.WearSource.MANUAL) =
        _state.update { it.copy(pendingCalendarWearId = outfitId, pendingCalendarWearSource = source) }

    fun consumeCalendarWear() = _state.update { it.copy(pendingCalendarWearId = null) }

    /** One-shot events the list consumes (scroll-to-outfit); buffered so a cross-tab request
     *  fired before the list mounts (Try-On "View outfit") still lands. */
    private val _events = Channel<OutfitsEvent>(Channel.BUFFERED)
    val events = _events.receiveAsFlow()

    /**
     * Ask the Outfits list to scroll the given outfit into view. Used by the Try-On detail view's
     * "View outfit" jump-back action and after saving a freshly-composed outfit.
     */
    fun requestScrollToOutfit(outfitId: String) {
        _events.trySend(OutfitsEvent.ScrollToOutfit(outfitId))
    }

    /**
     * Saves a style directly without going through the draft editing flow.
     * Used by Travel screen to persist packing outfits as styles.
     */
    /**
     * Builds a Drive-ID → filename map across **every** loaded closet folder, not just the save
     * target. Travel/composer outfits can reference items from multiple closets; resolving names
     * against a single folder drops the rest from `itemNames`, which is the source of truth on
     * reload (see [loadOutfitsFromFolder]) — so those items silently vanish after an app restart.
     */
    private suspend fun idToNameAllFolders(fallbackFolderIds: Collection<String>): Map<String, String> =
        outfitsRepo.idToNameAllFolders(fallbackFolderIds)

    fun saveOutfitDirectly(
        name: String,
        description: String,
        itemIds: List<String>,
        tags: List<String> = emptyList(),
        onDone: (Boolean) -> Unit = {},
    ) {
        if (itemIds.isEmpty()) return
        viewModelScope.launch {
            val id = saveFolderId ?: folderId ?: run { onDone(false); return@launch }
            val idToName = idToNameAllFolders(listOf(id))
            val itemNames = itemIds.mapNotNull { idToName[it] }
            val newOutfit = Outfit(
                name = name.ifBlank { "Travel style" },
                description = description,
                itemIds = itemIds,
                itemNames = itemNames,
                tags = tags,
                // Stamp the save folder (the field is @Transient, so Drive JSON is unchanged):
                // the local cache homes each outfit in exactly one folder, and an empty
                // folderId would leave the fresh outfit mis-homed until the next Drive sync.
                folderId = id,
            )
            val updated = _state.value.outfits + newOutfit
            requestScrollToOutfit(newOutfit.id)
            persistOutfitFolders(updated, listOf(id))
            onDone(true)
        }
    }

    /**
     * Bulk-write [newOutfits] to the active save folder in a single JSON write. Resolves each
     * outfit's `itemNames` from the folder's current file listing. Used by the trip creation
     * flow (Travel planner generate → N outfits + 1 Trip).
     */
    fun addOutfits(newOutfits: List<Outfit>, onDone: (Boolean) -> Unit = {}) {
        if (newOutfits.isEmpty()) { onDone(true); return }
        viewModelScope.launch {
            val id = saveFolderId ?: folderId ?: run { onDone(false); return@launch }
            val idToName = idToNameAllFolders(listOf(id))
            val resolved = newOutfits.map { o ->
                o.copy(
                    itemNames = o.itemNames.ifEmpty { o.itemIds.mapNotNull { idToName[it] } },
                    // Home each fresh outfit in its save folder (see saveOutfitDirectly).
                    folderId = id,
                )
            }
            val updated = _state.value.outfits + resolved
            persistOutfitFolders(updated, listOf(id))
            onDone(true)
        }
    }

    /**
     * Bulk-updates the `itemIds` of multiple outfits in one Drive write. [updates] maps outfit id
     * to the new item id list. Used by the trip bulk-refine flow ("brighter", "pack lighter").
     */
    /**
     * Like [updateOutfitItems] but also overwrites each outfit's name and description — used by
     * the trip bulk-refine, which regenerates the whole look (items + naming) per day.
     */
    fun updateOutfitsRefined(
        updates: Map<String, com.librelookai.data.model.OutfitRefinement>,
        onDone: (Boolean) -> Unit = {},
    ) {
        if (updates.isEmpty()) { onDone(true); return }
        val all = _state.value.outfits
        val touched = all.filter { it.id in updates }
        if (touched.isEmpty()) { onDone(true); return }
        viewModelScope.launch {
            val affectedFolderIds = touched.map { it.folderId }.filter { it.isNotEmpty() }.toSet()
                .ifEmpty { listOfNotNull(folderId).toSet() }
            if (affectedFolderIds.isEmpty()) { onDone(false); return@launch }
            // Resolve itemNames against every closet — a refined outfit may span folders.
            val idToName = idToNameAllFolders(affectedFolderIds)
            val updated = all.map { o ->
                val ref = updates[o.id] ?: return@map o
                o.copy(
                    itemIds = ref.itemIds,
                    itemNames = ref.itemIds.mapNotNull { idToName[it] },
                    name = ref.name.ifBlank { o.name },
                    description = ref.description.ifBlank { o.description },
                )
            }
            persistOutfitFolders(updated, affectedFolderIds)
            onDone(true)
        }
    }

    fun updateOutfitItems(updates: Map<String, List<String>>, onDone: (Boolean) -> Unit = {}) {
        if (updates.isEmpty()) { onDone(true); return }
        val all = _state.value.outfits
        val touched = all.filter { it.id in updates }
        if (touched.isEmpty()) { onDone(true); return }
        viewModelScope.launch {
            val affectedFolderIds = touched.map { it.folderId }.filter { it.isNotEmpty() }.toSet()
                .ifEmpty { listOfNotNull(folderId).toSet() }
            if (affectedFolderIds.isEmpty()) { onDone(false); return@launch }
            // Resolve itemNames against every closet — an outfit may span folders.
            val idToName = idToNameAllFolders(affectedFolderIds)
            val updated = all.map { o ->
                val newIds = updates[o.id] ?: return@map o
                o.copy(
                    itemIds = newIds,
                    itemNames = newIds.mapNotNull { idToName[it] },
                )
            }
            persistOutfitFolders(updated, affectedFolderIds)
            onDone(true)
        }
    }

    /**
     * Deletes all outfits whose id is in [ids] (used to cascade a trip delete). Writes once per
     * affected folder.
     */
    fun deleteOutfitsByIds(ids: Collection<String>, onDone: (Boolean) -> Unit = {}) {
        if (ids.isEmpty()) { onDone(true); return }
        val all = _state.value.outfits
        val updated = all.filterNot { it.id in ids }
        val deleted = all.filter { it.id in ids }
        val affectedFolderIds = if (folderId != null) setOf(folderId!!) else
            deleted.map { it.folderId }.filter { it.isNotEmpty() }.toSet()
        if (affectedFolderIds.isEmpty()) { onDone(true); return }
        viewModelScope.launch {
            persistOutfitFolders(updated, affectedFolderIds)
            onDone(true)
        }
    }

    // ---------- Unified style composer ----------

    /**
     * Opens the composer prefilled with [seedItemIds]. The user supplies a preference string
     * prefilled from [prefs]; weather mode defaults to AUTO.
     */
    fun prepareSave() = _state.update { s ->
        val existingOutfit = s.composerEditingOutfitId?.let { id -> s.outfits.find { it.id == id } }
        s.copy(
            isSaveDialogOpen = true,
            composerName = s.composerName.ifBlank { existingOutfit?.name ?: s.composerAiSuggestedName },
            composerDescription = s.composerDescription.ifBlank { existingOutfit?.description ?: s.composerAiSuggestedDescription },
            composerTags = s.composerTags.ifEmpty { existingOutfit?.tags ?: s.composerAiSuggestedTags },
        )
    }

    fun dismissSaveDialog() = _state.update { it.copy(isSaveDialogOpen = false) }

    fun commitOutfit(name: String, description: String, tags: List<String>, onDone: (Boolean) -> Unit = {}) {
        val s = _state.value
        val itemIds = s.composerSlots.mapNotNull { it.selectedItemId }.distinct()
            .ifEmpty { s.composerItemIds }
        if (itemIds.isEmpty()) { onDone(false); return }
        val editingId = s.composerEditingOutfitId
        // The Drive write is a network round-trip; surface a "saving" overlay so the user isn't
        // left staring at the unchanged composer until the list silently reappears.
        _state.update { it.copy(isComposerSaving = true) }
        // Funnel terminal helper: whether the saved outfit used AI suggestions, plus create/edit + size.
        val aiGenerated = s.composerSuggestions.isNotEmpty()
        fun logOutfitSaved(mode: String) = Analytics.event(
            "outfit_saved",
            mapOf("mode" to mode, "ai_generated" to aiGenerated.toString(), "items" to itemIds.size.toString()),
        )
        if (editingId == null) {
            saveOutfitDirectly(
                name        = name.ifBlank { "Outfit ${s.outfits.size + 1}" },
                description = description,
                itemIds     = itemIds,
                tags        = tags,
            ) { ok ->
                if (ok) { logOutfitSaved("create"); closeComposer() }
                else _state.update { it.copy(isComposerSaving = false) }
                onDone(ok)
            }
            return
        }
        val existing = s.outfits.find { it.id == editingId } ?: run { onDone(false); return }
        val resolvedName = name.ifBlank { existing.name }
        viewModelScope.launch {
            val saveId = existing.folderId.ifEmpty { saveFolderId ?: folderId ?: run { onDone(false); return@launch } }
            val idToName = drive.listFiles(saveId).associate { it.id to it.name }
            val itemNames = itemIds.mapNotNull { idToName[it] }
            val edited = existing.copy(
                name = resolvedName,
                description = description,
                itemIds = itemIds,
                itemNames = itemNames,
                tags = tags,
                // Home the edit in the folder it is written to (see saveOutfitDirectly).
                folderId = saveId,
            )
            val updated = s.outfits.map { if (it.id == edited.id) edited else it }
            _state.update { it.copy(pendingWearOutfitId = edited.id) }
            persistOutfitFolders(updated, listOf(saveId))
            logOutfitSaved("edit")
            closeComposer()
            onDone(true)
        }
    }

    // ---------- Delete ----------

    fun deleteOutfit(outfitId: String) {
        val updated = _state.value.outfits.filter { it.id != outfitId }
        viewModelScope.launch {
            val id = folderId ?: return@launch
            persistOutfitFolders(updated, listOf(id))
        }
    }

    // ---------- Favourite ----------

    /**
     * Toggles the outfit-level "loved"/favourite flag and persists it. Optimistic: the in-memory
     * state flips immediately; the Drive write rides the sync queue (a transient failure retries
     * instead of rolling the flip back — Room holds the new value). Only the outfit's home folder
     * is enqueued: the outfit lives in exactly one folder, so no other folder's file changes.
     */
    fun setOutfitLoved(outfitId: String, loved: Boolean) {
        val outfit = _state.value.outfits.find { it.id == outfitId } ?: return
        if (outfit.loved == loved) return
        val updatedAll = _state.value.outfits.map { if (it.id == outfitId) it.copy(loved = loved) else it }
        val targetFolderId = outfit.folderId.takeIf { it.isNotEmpty() } ?: saveFolderId ?: folderId ?: return
        viewModelScope.launch {
            persistOutfitFolders(updatedAll, listOf(targetFolderId))
        }
    }

    // ---------- Multi-select ----------

    fun toggleOutfitSelection(outfitId: String) = _state.update { s ->
        val next = s.selectedOutfitIds.toMutableSet()
        if (!next.add(outfitId)) next.remove(outfitId)
        s.copy(selectedOutfitIds = next)
    }

    fun selectAllOutfits(ids: List<String>) = _state.update { it.copy(selectedOutfitIds = it.selectedOutfitIds + ids) }

    fun clearOutfitSelection() = _state.update { it.copy(selectedOutfitIds = emptySet()) }

    fun deleteSelectedOutfits() {
        val toDelete = _state.value.selectedOutfitIds
        if (toDelete.isEmpty()) return
        val allStyles = _state.value.outfits
        val updated = allStyles.filter { it.id !in toDelete }
        val deleted = allStyles.filter { it.id in toDelete }
        // Determine which folders need their styles JSON updated.
        val affectedFolderIds = if (folderId != null) {
            setOf(folderId!!)
        } else {
            deleted.map { it.folderId }.filter { it.isNotEmpty() }.toSet()
        }
        if (affectedFolderIds.isEmpty()) return
        viewModelScope.launch {
            _state.update { it.copy(selectedOutfitIds = emptySet()) }
            persistOutfitFolders(updated, affectedFolderIds)
        }
    }

    // ---------- Outfit prediction ----------

    /**
     * Asks Gemini to pick the best existing style for today, given the user's profile,
     * current weather, and trending topics fetched from Google Trends.
     *
     * @param prefs   user preferences loaded from Drive (may be null if not yet set)
     * @param weather current weather reading (may be null if location not yet available)
     * @param images  full wardrobe list with tags
     */

    /**
     * Opens the "Find with AI" setup dialog. The dialog collects a free-text goal, weather
     * override, closet filter, and vibe chips (reusing composer state). Submitting it triggers
     * the prediction via [submitPredictionSetup].
     */
    fun clearError() = _state.update { it.copy(error = null) }

    // ---------- AI tag suggestions (outfit detail viewer) ----------

    internal fun deviceCountryCode(): String {
        val tel = getApplication<Application>()
            .getSystemService(Context.TELEPHONY_SERVICE) as? TelephonyManager
        return tel?.networkCountryIso?.uppercase()?.takeIf { it.isNotEmpty() }
            ?: tel?.simCountryIso?.uppercase()?.takeIf { it.isNotEmpty() }
            ?: Locale.getDefault().country.takeIf { it.isNotEmpty() }
            ?: "US"
    }
}
