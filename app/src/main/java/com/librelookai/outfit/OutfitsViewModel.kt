package com.librelookai.outfit

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import androidx.lifecycle.viewModelScope
import com.librelookai.data.model.Outfit
import com.librelookai.util.isNetworkAvailable
import com.librelookai.data.session.ClosetSessionHolder
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/**
 * The outfits **list** VM (refactor § 5 slice 9 — 2-way split): grid data, selection, delete /
 * loved / calendar-wear hand-off, plus the save mutators Travel / Try-On call. The AI composer +
 * prediction live on [OutfitGenerationViewModel]; shared data and the save funnel live on
 * [OutfitsRepository], so the two VMs never call each other.
 */
@OptIn(ExperimentalCoroutinesApi::class)
@HiltViewModel
class OutfitsViewModel @Inject constructor(
    app: Application,
    private val outfitsRepo: OutfitsRepository,
    session: ClosetSessionHolder,
    eventStore: com.librelookai.data.local.OutfitEventStore,
) : AndroidViewModel(app) {
    /** Active load scope + save target live on [outfitsRepo] (§ 5 slice 8). */
    private val folderId: String? get() = outfitsRepo.folderId
    private val saveFolderId: String? get() = outfitsRepo.saveFolderId

    private val _state = MutableStateFlow(OutfitsUiState())
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
        // Post-save "wear it now" offer: the generation VM's edit-save sets it on the repo, the
        // list surface shows the snackbar (§ 5 slice 9 — replaces the in-VM state write).
        viewModelScope.launch {
            outfitsRepo.pendingWearOutfitId.collect { id ->
                _state.update { it.copy(pendingWearOutfitId = id) }
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

    /** Thin delegator to [OutfitsRepository.persistOutfitFolders] — every mutator routes its
     *  write through it; the funnel + serialization live in the repo. */
    private suspend fun persistOutfitFolders(styles: List<Outfit>, affected: Collection<String>) =
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

    fun clearPendingWear() = outfitsRepo.setPendingWearOutfit(null)

    /**
     * Ask the Calendar sub-tab to enter "tap a day to wear [outfitId]" mode. Set by a Wear action on
     * the outfit list / detail viewer (the caller also switches to the Calendar tab) so the user
     * picks the wear day in context. Consumed by [OutfitCalendarTab] via [consumeCalendarWear].
     */
    fun requestCalendarWear(outfitId: String, source: com.librelookai.data.model.WearSource = com.librelookai.data.model.WearSource.MANUAL) =
        _state.update { it.copy(pendingCalendarWearId = outfitId, pendingCalendarWearSource = source) }

    fun consumeCalendarWear() = _state.update { it.copy(pendingCalendarWearId = null) }

    /** One-shot events the list consumes (scroll-to-outfit). Owned by [OutfitsRepository]
     *  (§ 5 slice 9) so the generation side can emit on save without a cross-VM call. */
    val events = outfitsRepo.events

    /**
     * Ask the Outfits list to scroll the given outfit into view. Used by the Try-On detail view's
     * "View outfit" jump-back action and after saving a freshly-composed outfit.
     */
    fun requestScrollToOutfit(outfitId: String) = outfitsRepo.requestScrollToOutfit(outfitId)

    /**
     * Builds a Drive-ID → filename map across **every** loaded closet folder, not just the save
     * target. Travel/composer outfits can reference items from multiple closets; resolving names
     * against a single folder drops the rest from `itemNames`, which is the source of truth on
     * reload (see [loadOutfitsFromFolder]) — so those items silently vanish after an app restart.
     */
    private suspend fun idToNameAllFolders(fallbackFolderIds: Collection<String>): Map<String, String> =
        outfitsRepo.idToNameAllFolders(fallbackFolderIds)

    /**
     * Saves a style directly without going through the draft editing flow. Used by the Travel
     * screen to persist packing outfits as styles. The save itself lives on
     * [OutfitsRepository.saveOutfit] (§ 5 slice 9 — the composer's create path calls it too).
     */
    fun saveOutfitDirectly(
        name: String,
        description: String,
        itemIds: List<String>,
        tags: List<String> = emptyList(),
        onDone: (Boolean) -> Unit = {},
    ) {
        if (itemIds.isEmpty()) return
        viewModelScope.launch {
            val saved = outfitsRepo.saveOutfit(name.ifBlank { "Travel style" }, description, itemIds, tags)
            onDone(saved != null)
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

    fun clearError() = _state.update { it.copy(error = null) }
}
