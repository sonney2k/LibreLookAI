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
import com.google.gson.reflect.TypeToken
import com.librelookai.MainActivity
import com.librelookai.data.drive.DriveRepository
import com.librelookai.data.drive.SyncEngine
import com.librelookai.data.drive.loadOutfitsJson
import com.librelookai.data.local.PendingMutationStore
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
import com.librelookai.data.local.WardrobeItemStore
import com.librelookai.data.session.ClosetSessionHolder
import com.librelookai.wardrobe.DriveImage
import com.librelookai.weather.WeatherData
import java.util.Locale
import java.util.UUID
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

@OptIn(ExperimentalCoroutinesApi::class)
@HiltViewModel
class OutfitsViewModel @Inject constructor(
    app: Application,
    internal val drive: DriveRepository,
    internal val gemini: GeminiRepository,
    private val itemStore: WardrobeItemStore,
    private val outfitStore: com.librelookai.data.local.OutfitStore,
    private val mutationStore: PendingMutationStore,
    private val syncEngine: SyncEngine,
    session: ClosetSessionHolder,
    eventStore: com.librelookai.data.local.OutfitEventStore,
) : AndroidViewModel(app) {
    /** Weekly, location-specific cache around the expensive Gemini trend lookup. */
    internal val trendsCache = com.librelookai.gemini.FashionTrendsCache(app, drive, gemini)
    internal val gson = Gson()
    internal var folderId: String? = null
    private var allFolderIds: List<String>? = null
    /** Folder to save new styles into; set independently of load scope. */
    internal var saveFolderId: String? = null

    internal val _state = MutableStateFlow(OutfitsUiState())
    val state: StateFlow<OutfitsUiState> = _state.asStateFlow()

    // ---- Derived read path (refactor § 5 slice 4b) ----
    /** The folder ids outfits load from (always every closet — never the closet filter). */
    private val outfitsScope = MutableStateFlow<List<String>>(emptyList())
    /** Serializes [persistOutfitFolders] so back-to-back mutations can't interleave store writes. */
    private val persistMutex = Mutex()

    init {
        // Derived outfits (§ 5 slice 4b): the list is the store rows in scope — store rows
        // carry resolved itemIds (only folderId is @Transient), so every mutation's
        // replaceFolder and every Phase-2 reconcile reach the UI via Room invalidation.
        viewModelScope.launch {
            outfitsScope.flatMapLatest { ids -> outfitStore.observeFolders(ids) }
                .collect { outfits -> _state.update { it.copy(outfits = outfits) } }
        }
        // Derived wardrobe images for the outfit cards/pickers: the same item-store scope the
        // old one-shot cache reads used, now refreshed by invalidation (this also retired
        // refreshWardrobeImages()).
        viewModelScope.launch {
            outfitsScope.flatMapLatest { ids -> itemStore.observeItems(ids) }
                .map { byFolder ->
                    byFolder.flatMap { (fid, items) ->
                        items.mapNotNull { entry ->
                            drive.cachedFile(entry.driveId)?.let { f ->
                                DriveImage(
                                    driveId = entry.driveId,
                                    localPath = f.absolutePath,
                                    name = entry.name,
                                    tags = entry.tags,
                                    originalDriveId = entry.originalDriveId,
                                    sidecarDriveId = entry.sidecarDriveId,
                                    folderId = fid,
                                )
                            }
                        }
                    }
                }
                .flowOn(Dispatchers.IO)
                .collect { images -> _state.update { it.copy(wardrobeImages = images) } }
        }
        // Derive the load scope and save target from the shared closet session (replaces the
        // AppContent fan-out bridge). Outfits always load from ALL locations — never filtered
        // by the closet filter; the active closet only steers where new outfits save.
        viewModelScope.launch {
            session.session.collect { s ->
                setAllLocations(s.closetFolderIds)
                s.saveFolderId?.let { saveFolderId = it }
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
        if (folderId == null && allFolderIds?.toSet() == folderIds.toSet()) return
        folderId = null
        allFolderIds = folderIds.toList()
        _state.update { OutfitsUiState(isLoading = true) }
        outfitsScope.value = folderIds.toList()
        loadOutfits()
    }

    // ---------- Load ----------

    /**
     * Local-first outfit save (refactor § 2, derived since § 5 slice 4b): mirrors [styles] —
     * the mutation's already-updated full list — into the per-folder local cache
     * ([OutfitStore]) over the view scope plus [affected] (so an out-of-scope save target is
     * cached too), then enqueues one payload-free [OUTFIT_FOLDER_SYNC_KIND] mutation per
     * affected folder and triggers a drain. The derived [OutfitsUiState.outfits] follows via
     * Room invalidation — mutators no longer splice state. Room is the source of truth the
     * [OutfitFolderSyncHandler] re-reads at apply time, so back-to-back edits coalesce into
     * the latest snapshot, a transient Drive failure retries instead of silently losing the
     * write, and the queued row survives process death. Serialized by [persistMutex] so two
     * rapid mutations can't interleave their store writes.
     */
    internal suspend fun persistOutfitFolders(styles: List<Outfit>, affected: Collection<String>) {
        persistMutex.withLock {
            val affectedIds = affected.filterTo(LinkedHashSet()) { it.isNotEmpty() }
            val scope = allFolderIds ?: listOfNotNull(folderId)
            (scope + affectedIds).toSet().forEach { fid ->
                val folderStyles = styles.filter { it.folderId == fid || it.folderId.isEmpty() }
                runCatching { outfitStore.replaceFolder(fid, folderStyles) }
            }
            affectedIds.forEach { fid ->
                mutationStore.enqueue(OUTFIT_FOLDER_SYNC_KIND, targetId = fid, folderId = fid, payload = "{}")
            }
            syncEngine.drain()
        }
    }

    fun loadOutfits() {
        viewModelScope.launch {
            _state.update { it.copy(isLoading = true, error = null) }
            val ids = allFolderIds
            // Phase 1 — the derived view paints the store by itself; probe it only for flags.
            val cached = if (ids != null) {
                ids.flatMap { outfitStore.outfitsFor(it) }
            } else {
                folderId?.let { outfitStore.outfitsFor(it) } ?: emptyList()
            }
            if (cached.isNotEmpty()) _state.update { it.copy(isLoading = false) }

            // Phase 2 — Drive sync: skip when offline
            if (!getApplication<Application>().isNetworkAvailable()) {
                _state.update { it.copy(isLoading = false) }
                return@launch
            }
            runCatching {
                if (ids != null) {
                    // Build a combined name→ID map from all folders so styles whose items have
                    // been moved to a different location can still be resolved.
                    val combinedNameToId: Map<String, String> = ids
                        .map { id -> async { drive.listFiles(id).associate { com.librelookai.util.ImageEncoding.itemMatchKey(it.name) to it.id } } }
                        .awaitAll()
                        .fold(emptyMap()) { acc, m -> acc + m }
                    ids.map { id -> async { loadOutfitsFromFolder(id, combinedNameToId) } }.awaitAll().flatten()
                } else {
                    val id = folderId ?: return@runCatching cached
                    loadOutfitsFromFolder(id)
                }
            }.onSuccess {
                // loadOutfitsFromFolder wrote each folder's store rows — the view followed.
                _state.update { it.copy(isLoading = false) }
            }.onFailure { e ->
                _state.update { s ->
                    s.copy(isLoading = false, error = if (s.outfits.isEmpty()) e.message else null)
                }
            }
        }
    }

    private suspend fun loadOutfitsFromFolder(id: String, nameToId: Map<String, String>? = null): List<Outfit> {
        val resolvedNameToId = nameToId
            ?: drive.listFiles(id).associate { com.librelookai.util.ImageEncoding.itemMatchKey(it.name) to it.id }
        val json = drive.loadOutfitsJson(id)
        val resolved = if (json != null) {
            val type = object : TypeToken<List<Outfit>>() {}.type
            val raw: List<Outfit> = gson.fromJson(json, type) ?: emptyList()
            raw.map { style ->
                style.copy(
                    itemIds = if (style.itemNames.isNotEmpty())
                        style.itemNames.mapNotNull { resolvedNameToId[com.librelookai.util.ImageEncoding.itemMatchKey(it)] }
                    else style.itemIds,
                    folderId = id,
                )
            }
        } else emptyList()
        runCatching { outfitStore.replaceFolder(id, resolved) }
        return resolved
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

    /** Called by [OutfitListScreen] after it has scrolled to the requested outfit. */
    fun consumePendingScrollOutfit() = _state.update { it.copy(pendingScrollOutfitId = null) }

    /**
     * Ask the Outfits list to scroll the given outfit into view next time it composes.
     * Used by the Try-On detail view's "View outfit" jump-back action.
     */
    fun requestScrollToOutfit(outfitId: String) =
        _state.update { it.copy(pendingScrollOutfitId = outfitId) }

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
    private suspend fun idToNameAllFolders(fallbackFolderIds: Collection<String>): Map<String, String> = coroutineScope {
        (allFolderIds ?: fallbackFolderIds.toList())
            .distinct()
            .map { f -> async { drive.listFiles(f).associate { it.id to it.name } } }
            .awaitAll()
            .fold(emptyMap<String, String>()) { acc, m -> acc + m }
    }

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
            _state.update { it.copy(pendingScrollOutfitId = newOutfit.id) }
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
