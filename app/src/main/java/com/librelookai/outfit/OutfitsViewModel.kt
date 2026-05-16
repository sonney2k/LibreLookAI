package com.librelookai.outfit
import android.app.Application
import android.content.Context
import android.telephony.TelephonyManager
import android.util.Log
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.launch
import java.time.LocalDate
import java.util.Locale
import com.librelookai.auth.GoogleAuthManager
import com.librelookai.data.drive.DriveRepository
import com.librelookai.data.model.Outfit
import com.librelookai.gemini.FashionTrends
import com.librelookai.gemini.GeminiRepository
import com.librelookai.gemini.PromptKey
import com.librelookai.gemini.PromptStore
import com.librelookai.gemini.UsageCategory
import com.librelookai.settings.AiConsiderations
import com.librelookai.settings.AppLanguage
import com.librelookai.settings.UserPreferences
import com.librelookai.util.isNetworkAvailable
import com.librelookai.wardrobe.DriveImage
import com.librelookai.wardrobe.LocalCache
import com.librelookai.weather.WeatherData
import com.librelookai.data.model.Location
import com.librelookai.MainActivity
import com.librelookai.weather.wmoEmoji

data class OutfitPrediction(val outfitId: String, val reason: String)

enum class ComposerWeatherMode { AUTO, MANUAL }

/** Per-layer target item counts for the unified style composer. */
data class ComposerTargets(
    val top: Int = 1,
    val bottom: Int = 1,
    val footwear: Int = 0,
    val outerwear: Int = 0,
    val accessory: Int = 0,
) {
    fun total(): Int = top + bottom + footwear + outerwear + accessory
}

/** Gemini-composed outfit that doesn't yet exist as a saved style. */
data class NewOutfitSuggestion(
    val name: String,
    val description: String,
    val itemIds: List<String>,
    val reason: String,
    val tags: List<String> = emptyList(),
)

data class OutfitsUiState(
    val outfits: List<Outfit> = emptyList(),
    /** All wardrobe images across all locations, for resolving style item icons. */
    val wardrobeImages: List<DriveImage> = emptyList(),
    val isLoading: Boolean = false,
    val isCreating: Boolean = false,
    /** True when the new style-editing view (not the old item-picker) is open. */
    val isEditingOutfitView: Boolean = false,
    val draftItemIds: Set<String> = emptySet(),
    val draftOutfitName: String = "",
    val draftOutfitDescription: String = "",
    val draftOutfitTags: List<String> = emptyList(),
    /** Non-null when editing an existing style; null when creating a new one. */
    val editingOutfit: Outfit? = null,
    val showNameDialog: Boolean = false,
    // Predict existing style
    val isPredicting: Boolean = false,
    val prediction: OutfitPrediction? = null,
    val predictionError: String? = null,
    /** When Gemini returns several outfit picks the user can slide through, the full list lives here. */
    val predictionSuggestions: List<OutfitPrediction> = emptyList(),
    val predictionIndex: Int = 0,
    // Compose brand-new style
    val isComposing: Boolean = false,
    val newSuggestion: NewOutfitSuggestion? = null,
    val compositionError: String? = null,
    // Refinement feedback (shared by prediction + composition loops)
    val refinementInput: String = "",
    val feedbackHistory: List<String> = emptyList(),
    val lastCompositionRequiredIds: Set<String> = emptySet(),
    // After saving a style, offer to wear it immediately
    val pendingWearOutfitId: String? = null,
    // Multi-select for bulk actions
    val selectedOutfitIds: Set<String> = emptySet(),
    // Item-swap alternatives suggested by Gemini
    val isLoadingAlternatives: Boolean = false,
    val alternativeIds: List<String> = emptyList(),
    // Unified style composer (phase 1: Wardrobe-selection entry point)
    val isComposerOpen: Boolean = false,
    /** Non-null when the composer is editing an existing saved style (update-in-place). */
    val composerEditingOutfitId: String? = null,
    val composerItemIds: List<String> = emptyList(),
    val composerWeatherMode: ComposerWeatherMode = ComposerWeatherMode.AUTO,
    val composerManualSeason: String = "",        // "" / Spring / Summer / Fall / Winter
    val composerManualTempC: Int? = null,          // null = unspecified
    val composerManualPrecip: String = "",         // "" / None / Light / Heavy
    val composerVibes: Set<String> = emptySet(),   // Casual / Sporty / Formal / …
    val composerTargets: ComposerTargets = ComposerTargets(),
    val composerName: String = "",
    val composerDescription: String = "",
    val composerTags: List<String> = emptyList(),
    val composerFeedback: String = "",
    val composerFeedbackHistory: List<String> = emptyList(),
    val composerReason: String = "",
    /**
     * Folder IDs of the closets to source items from when adding/suggesting/composing in the
     * composer. Empty set = unrestricted (sources from every closet). Defaults to the user's
     * default closet on open; the user can toggle other closets in the composer.
     */
    val composerSourceFolderIds: Set<String> = emptySet(),
    val isComposerEnhancing: Boolean = false,
    val composerError: String? = null,
    /** True while the "Find with AI" setup dialog is showing on the Outfits tab. */
    val isPredictionSetupOpen: Boolean = false,
    val error: String? = null,
    /** AI tag-suggestion flow launched from the outfit detail viewer. */
    val tagSuggestion: TagSuggestionState? = null,
    /** Outfit currently being tag-edited from the detail viewer (chips clicked). */
    val tagEditingOutfitId: String? = null,
)

/** Transient state for the "Suggest tags" action in the outfit detail viewer. */
data class TagSuggestionState(
    val outfitId: String,
    val isLoading: Boolean = true,
    val suggestions: List<String> = emptyList(),
    val error: String? = null,
    val isSaving: Boolean = false,
)

class OutfitsViewModel(app: Application) : AndroidViewModel(app) {

    private val drive = DriveRepository(app, GoogleAuthManager(app))
    private val gemini = GeminiRepository(app)
    private val gson = Gson()
    private var folderId: String? = null
    private var allFolderIds: List<String>? = null
    /** Folder to save new styles into; set independently of load scope. */
    private var saveFolderId: String? = null

    private val _state = MutableStateFlow(OutfitsUiState())
    val state: StateFlow<OutfitsUiState> = _state.asStateFlow()

    /** Called by MainActivity to inform which folder newly created styles should be saved to. */
    fun updateSaveFolder(folderId: String) {
        saveFolderId = folderId
    }

    fun setLocation(newFolderId: String) {
        if (folderId == newFolderId && allFolderIds == null) return
        folderId = newFolderId
        allFolderIds = null
        _state.update { OutfitsUiState(isLoading = true) }
        loadOutfits()
    }

    fun setAllLocations(folderIds: List<String>) {
        if (folderId == null && allFolderIds?.toSet() == folderIds.toSet()) return
        folderId = null
        allFolderIds = folderIds.toList()
        _state.update { OutfitsUiState(isLoading = true) }
        // Read wardrobe disk caches on IO thread in parallel with styles loading.
        viewModelScope.launch(Dispatchers.IO) {
            val images = readWardrobeImagesFromCache(folderIds)
            _state.update { it.copy(wardrobeImages = images) }
        }
        loadOutfits()
    }

    // ---------- Wardrobe image cache (all locations, disk-only) ----------

    /**
     * Reads wardrobe disk caches for all given folder IDs and returns a flat list of
     * [DriveImage] entries that have a locally cached file.  This is a pure disk read —
     * no network calls — so it's safe to call from the main thread or from [setAllLocations].
     */
    private fun readWardrobeImagesFromCache(folderIds: List<String>): List<DriveImage> {
        val filesDir = getApplication<Application>().filesDir
        return folderIds.flatMap { fid ->
            val cacheFile = java.io.File(filesDir, "wardrobe_cache_${fid}.json")
            if (!cacheFile.exists()) return@flatMap emptyList()
            runCatching {
                val cache = gson.fromJson(cacheFile.readText(), LocalCache::class.java)
                cache.items.mapNotNull { entry ->
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
            }.getOrDefault(emptyList())
        }
    }

    /**
     * Re-reads wardrobe disk caches and updates [OutfitsUiState.wardrobeImages].
     * Call this after wardrobe Drive sync completes so style cards show fresh images.
     */
    fun refreshWardrobeImages() {
        val folderIds = allFolderIds ?: folderId?.let { listOf(it) } ?: return
        viewModelScope.launch(Dispatchers.IO) {
            val images = readWardrobeImagesFromCache(folderIds)
            _state.update { it.copy(wardrobeImages = images) }
        }
    }

    // ---------- Load ----------

    private fun outfitsLocalCacheFile(folderId: String) =
        java.io.File(getApplication<Application>().filesDir, "styles_cache_${folderId}.json")

    private fun readOutfitsLocalCache(id: String): List<Outfit> {
        val file = outfitsLocalCacheFile(id)
        if (!file.exists()) return emptyList()
        val type = object : TypeToken<List<Outfit>>() {}.type
        return runCatching { gson.fromJson<List<Outfit>>(file.readText(), type) ?: emptyList() }
            .getOrDefault(emptyList())
    }

    fun loadOutfits() {
        viewModelScope.launch {
            _state.update { it.copy(isLoading = true, error = null) }
            val ids = allFolderIds
            // Phase 1 — instant: show whatever is in local JSON cache
            val cached = if (ids != null) {
                ids.flatMap { readOutfitsLocalCache(it) }
            } else {
                folderId?.let { readOutfitsLocalCache(it) } ?: emptyList()
            }
            if (cached.isNotEmpty()) _state.update { it.copy(outfits = cached, isLoading = false) }

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
                        .map { id -> async { drive.listFiles(id).associate { it.name to it.id } } }
                        .awaitAll()
                        .fold(emptyMap()) { acc, m -> acc + m }
                    ids.map { id -> async { loadOutfitsFromFolder(id, combinedNameToId) } }.awaitAll().flatten()
                } else {
                    val id = folderId ?: return@runCatching cached
                    loadOutfitsFromFolder(id)
                }
            }.onSuccess { styles ->
                _state.update { it.copy(outfits = styles, isLoading = false) }
            }.onFailure { e ->
                _state.update { s ->
                    s.copy(isLoading = false, error = if (s.outfits.isEmpty()) e.message else null)
                }
            }
        }
    }

    private suspend fun loadOutfitsFromFolder(id: String, nameToId: Map<String, String>? = null): List<Outfit> {
        val resolvedNameToId = nameToId ?: drive.listFiles(id).associate { it.name to it.id }
        val json = drive.loadOutfitsJson(id)
        val resolved = if (json != null) {
            val type = object : TypeToken<List<Outfit>>() {}.type
            val raw: List<Outfit> = gson.fromJson(json, type) ?: emptyList()
            raw.map { style ->
                style.copy(
                    itemIds = if (style.itemNames.isNotEmpty())
                        style.itemNames.mapNotNull { resolvedNameToId[it] }
                    else style.itemIds,
                    folderId = id,
                )
            }
        } else emptyList()
        runCatching { outfitsLocalCacheFile(id).writeText(gson.toJson(resolved)) }
        return resolved
    }

    // ---------- Create flow ----------

    fun startCreating() = _state.update {
        it.copy(isCreating = true, draftItemIds = emptySet(), draftOutfitName = "", draftOutfitDescription = "", draftOutfitTags = emptyList(), editingOutfit = null)
    }

    fun startCreatingFromItems(itemIds: Set<String>, name: String = "", description: String = "") = _state.update {
        it.copy(isCreating = true, draftItemIds = itemIds, draftOutfitName = name, draftOutfitDescription = description, draftOutfitTags = emptyList(), editingOutfit = null)
    }

    /** Opens the unified composer for an existing saved style (update-in-place on save). */
    fun startEditing(style: Outfit, images: List<DriveImage>, prefs: UserPreferences?) {
        openComposer(
            seedItemIds        = style.itemIds.toSet(),
            images             = images,
            prefs              = prefs,
            initialName        = style.name,
            initialDescription = style.description,
            editingStyleId     = style.id,
        )
    }

    /** Called when Gemini predicts an existing style — opens edit view pre-populated with it. */
    fun openPredictionInEditView(style: Outfit) = _state.update {
        it.copy(
            isEditingOutfitView = true,
            draftItemIds = style.itemIds.toSet(),
            draftOutfitName = style.name,
            draftOutfitDescription = style.description,
            draftOutfitTags = style.tags,
            editingOutfit = style,
        )
    }

    /** Called when Gemini composes a new outfit — opens edit view pre-populated with it. */
    fun openSuggestionInEditView(suggestion: NewOutfitSuggestion) = _state.update {
        it.copy(
            isEditingOutfitView = true,
            draftItemIds = suggestion.itemIds.toSet(),
            draftOutfitName = suggestion.name,
            draftOutfitDescription = suggestion.description,
            draftOutfitTags = suggestion.tags,
            editingOutfit = null,
        )
    }

    fun cancelOutfitEditingView() = _state.update {
        it.copy(
            isEditingOutfitView = false,
            draftItemIds = emptySet(),
            draftOutfitName = "",
            draftOutfitDescription = "",
            draftOutfitTags = emptyList(),
            editingOutfit = null,
            prediction = null,
            predictionSuggestions = emptyList(),
            predictionIndex = 0,
            newSuggestion = null,
            alternativeIds = emptyList(),
            feedbackHistory = emptyList(),
            refinementInput = "",
        )
    }

    fun updateDraftName(name: String) = _state.update { it.copy(draftOutfitName = name) }
    fun updateDraftDescription(description: String) = _state.update { it.copy(draftOutfitDescription = description) }

    fun cancelCreating() = _state.update {
        it.copy(isCreating = false, isEditingOutfitView = false, draftItemIds = emptySet(), draftOutfitName = "", draftOutfitDescription = "", draftOutfitTags = emptyList(), editingOutfit = null, showNameDialog = false)
    }

    fun updateDraftTags(tags: List<String>) = _state.update { it.copy(draftOutfitTags = tags.distinct()) }
    fun addDraftTag(tag: String) {
        val t = tag.trim()
        if (t.isEmpty()) return
        _state.update { s ->
            if (s.draftOutfitTags.any { it.equals(t, ignoreCase = true) }) s
            else s.copy(draftOutfitTags = s.draftOutfitTags + t)
        }
    }
    fun removeDraftTag(tag: String) = _state.update { s -> s.copy(draftOutfitTags = s.draftOutfitTags - tag) }

    fun toggleDraftItem(driveId: String) = _state.update { s ->
        val next = s.draftItemIds.toMutableSet()
        if (!next.add(driveId)) next.remove(driveId)
        s.copy(draftItemIds = next)
    }

    fun selectAllDraftItems(ids: List<String>) = _state.update { it.copy(draftItemIds = it.draftItemIds + ids) }

    fun deselectAllDraftItems(ids: List<String>) = _state.update { it.copy(draftItemIds = it.draftItemIds - ids.toSet()) }

    /** Replace one item in the style draft with another. */
    fun swapDraftItem(oldId: String, newId: String) = _state.update { s ->
        val next = s.draftItemIds.toMutableSet().apply { remove(oldId); add(newId) }
        s.copy(draftItemIds = next)
    }

    fun addDraftItem(id: String) = _state.update { s -> s.copy(draftItemIds = s.draftItemIds + id) }

    fun removeDraftItem(id: String) = _state.update { s -> s.copy(draftItemIds = s.draftItemIds - id) }

    /**
     * Asks Gemini to suggest up to 10 alternative items that could replace [itemId] in the
     * current style draft, keeping the overall look coherent.
     */
    fun suggestAlternatives(itemId: String, images: List<DriveImage>, prefs: UserPreferences?) {
        viewModelScope.launch {
            _state.update { it.copy(isLoadingAlternatives = true, alternativeIds = emptyList()) }
            val item = images.find { it.driveId == itemId } ?: run {
                _state.update { it.copy(isLoadingAlternatives = false) }
                return@launch
            }
            val otherIds = _state.value.draftItemIds - setOf(itemId)
            val otherItems = images.filter { it.driveId in otherIds }
            val prompt = buildAlternativesPrompt(
                preamble = PromptStore.get(getApplication(), PromptKey.ALTERNATIVES),
                item = item,
                otherStyleItems = otherItems,
                allImages = images,
                prefs = prefs,
            )
            val raw = gemini.generateText(prompt, UsageCategory.OUTFIT_PREDICT)
            val ids: List<String> = if (raw != null) {
                val json = raw.trim()
                    .removePrefix("```json").removePrefix("```").removeSuffix("```").trim()
                data class AltResp(val alternativeIds: List<String> = emptyList())
                val knownIds = images.map { it.driveId }.toSet()
                runCatching {
                    val resp = gson.fromJson(json, AltResp::class.java)
                    resp.alternativeIds.filter { it in knownIds && it != itemId }.take(10)
                }.getOrDefault(emptyList())
            } else emptyList()
            _state.update { it.copy(isLoadingAlternatives = false, alternativeIds = ids) }
        }
    }

    fun clearAlternatives() = _state.update { it.copy(alternativeIds = emptyList()) }

    fun clearPendingWear() = _state.update { it.copy(pendingWearOutfitId = null) }

    fun confirmDraft() {
        val s = _state.value
        if (s.draftItemIds.isEmpty()) return
        // Name and description are always set inline in the picker — save directly, no popup.
        val resolvedName = s.draftOutfitName.ifEmpty {
            s.editingOutfit?.name ?: "Outfit ${s.outfits.size + 1}"
        }
        saveOutfit(resolvedName)
    }

    fun saveOutfit(name: String) {
        val s = _state.value
        val draftIds = s.draftItemIds
        if (draftIds.isEmpty()) return
        viewModelScope.launch {
            val resolvedName = name.trim().ifEmpty {
                s.editingOutfit?.name ?: "Outfit ${s.outfits.size + 1}"
            }
            val description = s.draftOutfitDescription.trim()
            val id = saveFolderId ?: folderId ?: return@launch

            // Fetch fresh file listing to populate stable itemNames (portable across account copies)
            val idToName = drive.listFiles(id).associate { it.id to it.name }
            val itemNames = draftIds.mapNotNull { idToName[it] }

            val tags = s.draftOutfitTags
            val updated = if (s.editingOutfit != null) {
                val edited = s.editingOutfit.copy(
                    name = resolvedName, description = description,
                    itemIds = draftIds.toList(), itemNames = itemNames,
                    tags = tags,
                )
                s.outfits.map { if (it.id == edited.id) edited else it }
            } else {
                s.outfits + Outfit(
                    name = resolvedName, description = description,
                    itemIds = draftIds.toList(), itemNames = itemNames,
                    tags = tags,
                )
            }
            val savedStyleId = if (s.editingOutfit != null) s.editingOutfit.id else updated.last().id
            runCatching {
                drive.saveOutfitsJson(id, gson.toJson(updated))
            }.onSuccess {
                _state.update {
                    it.copy(
                        outfits = updated,
                        isCreating = false,
                        isEditingOutfitView = false,
                        draftItemIds = emptySet(),
                        draftOutfitTags = emptyList(),
                        editingOutfit = null,
                        showNameDialog = false,
                        prediction = null,
                        newSuggestion = null,
                        feedbackHistory = emptyList(),
                        refinementInput = "",
                        pendingWearOutfitId = savedStyleId,
                    )
                }
            }.onFailure { e ->
                _state.update { it.copy(showNameDialog = false, error = e.message) }
            }
        }
    }

    /**
     * Saves a style directly without going through the draft editing flow.
     * Used by Travel screen to persist packing outfits as styles.
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
            val id = saveFolderId ?: folderId ?: run { onDone(false); return@launch }
            val idToName = drive.listFiles(id).associate { it.id to it.name }
            val itemNames = itemIds.mapNotNull { idToName[it] }
            val updated = _state.value.outfits + Outfit(
                name = name.ifBlank { "Travel style" },
                description = description,
                itemIds = itemIds,
                itemNames = itemNames,
                tags = tags,
            )
            runCatching {
                drive.saveOutfitsJson(id, gson.toJson(updated))
            }.onSuccess {
                _state.update { it.copy(outfits = updated) }
                onDone(true)
            }.onFailure { e ->
                _state.update { it.copy(error = e.message) }
                onDone(false)
            }
        }
    }

    // ---------- Unified style composer ----------

    /**
     * Opens the composer prefilled with [seedItemIds] and target counts derived from
     * their current tag categories.  The user supplies a preference string prefilled
     * from [prefs]; weather mode defaults to AUTO.
     */
    fun openComposer(
        seedItemIds: Set<String>,
        images: List<DriveImage>,
        prefs: UserPreferences?,
        initialName: String = "",
        initialDescription: String = "",
        editingStyleId: String? = null,
        defaultSourceFolderId: String? = null,
    ) {
        val ids = seedItemIds.toList()
        val targets = targetsFromSeed(ids, images)
        // Default the source-closet filter to the user's default closet so suggestions/composition
        // start from a single curated wardrobe; user can broaden it via chips in the composer.
        val sourceFolders = defaultSourceFolderId?.let { setOf(it) } ?: emptySet()
        _state.update {
            it.copy(
                isComposerOpen          = true,
                composerEditingOutfitId  = editingStyleId,
                composerItemIds         = ids,
                composerWeatherMode     = ComposerWeatherMode.AUTO,
                composerManualSeason    = "",
                composerManualTempC     = null,
                composerManualPrecip    = "",
                composerVibes           = emptySet(),
                composerTargets         = targets,
                composerName            = initialName,
                composerDescription     = initialDescription,
                composerTags            = editingStyleId?.let { id ->
                    it.outfits.find { o -> o.id == id }?.tags ?: emptyList()
                } ?: emptyList(),
                composerFeedback        = "",
                composerFeedbackHistory = emptyList(),
                composerReason          = "",
                composerSourceFolderIds = sourceFolders,
                isComposerEnhancing     = false,
                composerError           = null,
            )
        }
    }

    /** Toggle whether [folderId] is included in the composer's source-closet filter. */
    fun toggleComposerSourceFolder(folderId: String) {
        _state.update { s ->
            val next = s.composerSourceFolderIds.toMutableSet()
            if (!next.add(folderId)) next.remove(folderId)
            s.copy(composerSourceFolderIds = next)
        }
    }

    /** Opens the composer seeded with the union of all items from the currently-selected styles. */
    fun openComposerFromSelectedOutfits(images: List<DriveImage>, prefs: UserPreferences?) {
        val selected = _state.value.outfits.filter { it.id in _state.value.selectedOutfitIds }
        if (selected.size < 2) return
        val unionIds = selected.flatMap { it.itemIds }.toSet()
        val suggestedName = selected.joinToString(" + ") { it.name.ifBlank { "Outfit" } }
            .take(60)
        openComposer(
            seedItemIds = unionIds,
            images = images,
            prefs = prefs,
            initialName = suggestedName,
        )
        _state.update { it.copy(selectedOutfitIds = emptySet()) }
    }

    fun closeComposer() = _state.update {
        it.copy(
            isComposerOpen          = false,
            composerEditingOutfitId  = null,
            composerItemIds         = emptyList(),
            composerFeedback        = "",
            composerFeedbackHistory = emptyList(),
            composerReason          = "",
            composerError           = null,
        )
    }

    fun addComposerItems(ids: Collection<String>) = _state.update { s ->
        s.copy(composerItemIds = (s.composerItemIds + ids).distinct())
    }
    fun removeComposerItem(id: String) = _state.update { s ->
        s.copy(composerItemIds = s.composerItemIds - id)
    }
    fun toggleComposerVibe(vibe: String) = _state.update { s ->
        val next = s.composerVibes.toMutableSet()
        if (!next.add(vibe)) next.remove(vibe)
        s.copy(composerVibes = next)
    }
    fun setComposerWeatherMode(mode: ComposerWeatherMode) = _state.update { it.copy(composerWeatherMode = mode) }
    fun setComposerManualSeason(season: String) = _state.update { it.copy(composerManualSeason = season) }
    fun setComposerManualTempC(tempC: Int?) = _state.update { it.copy(composerManualTempC = tempC) }
    fun setComposerManualPrecip(p: String) = _state.update { it.copy(composerManualPrecip = p) }
    fun setComposerTargets(targets: ComposerTargets) = _state.update { it.copy(composerTargets = targets) }
    fun updateComposerName(s: String) = _state.update { it.copy(composerName = s) }
    fun updateComposerDescription(s: String) = _state.update { it.copy(composerDescription = s) }
    fun addComposerTag(tag: String) {
        val t = tag.trim()
        if (t.isEmpty()) return
        _state.update { s ->
            if (s.composerTags.any { it.equals(t, ignoreCase = true) }) s
            else s.copy(composerTags = s.composerTags + t)
        }
    }
    fun removeComposerTag(tag: String) = _state.update { s -> s.copy(composerTags = s.composerTags - tag) }
    fun updateComposerFeedback(s: String) = _state.update { it.copy(composerFeedback = s) }

    /** Asks Gemini to complete the composer draft into a full outfit. */
    fun enhanceComposerWithAi(
        prefs: UserPreferences?,
        weather: WeatherData?,
        images: List<DriveImage>,
    ) {
        val s = _state.value
        if (images.isEmpty()) {
            _state.update { it.copy(composerError = "No wardrobe items to compose from.") }
            return
        }
        val feedbackAdd = s.composerFeedback.trim()
        val history = if (feedbackAdd.isNotEmpty()) s.composerFeedbackHistory + feedbackAdd else s.composerFeedbackHistory
        _state.update {
            it.copy(
                isComposerEnhancing     = true,
                composerError           = null,
                composerFeedback        = "",
                composerFeedbackHistory = history,
            )
        }
        viewModelScope.launch {
            val countryCode = deviceCountryCode()
            val region = listOfNotNull(weather?.cityName?.takeIf { it.isNotEmpty() }, countryCode).joinToString(", ")
            val fashionTrends = gemini.searchFashionTrends(region, UsageCategory.OUTFIT_COMPOSE)
            val prefString = prefs?.preferences.orEmpty()
            val prompt = buildComposerPrompt(
                preamble         = PromptStore.get(getApplication(), PromptKey.COMPOSER),
                prefs            = prefs,
                prefOverride     = prefString,
                weatherAuto      = if (s.composerWeatherMode == ComposerWeatherMode.AUTO) weather else null,
                weatherManual    = if (s.composerWeatherMode == ComposerWeatherMode.MANUAL) Triple(
                    s.composerManualSeason, s.composerManualTempC, s.composerManualPrecip
                ) else null,
                vibes            = s.composerVibes,
                targets          = s.composerTargets,
                requiredItemIds  = s.composerItemIds.toSet(),
                images           = images,
                countryCode      = countryCode,
                cityName         = weather?.cityName,
                fashionTrends    = fashionTrends,
                feedbackHistory  = history,
                language         = prefs?.language ?: AppLanguage.ENGLISH,
            )
            Log.d("StylesVM", "Composer prompt length: ${prompt.length} chars")
            val raw = gemini.generateText(prompt, UsageCategory.OUTFIT_COMPOSE)
            if (raw == null) {
                _state.update { it.copy(isComposerEnhancing = false, composerError = "Gemini did not respond.") }
                return@launch
            }
            val json = raw.trim()
                .removePrefix("```json").removePrefix("```").removeSuffix("```").trim()
            data class CompResp(
                val name: String = "",
                val description: String = "",
                val itemIds: List<String> = emptyList(),
                val reason: String = "",
                val tags: List<String> = emptyList(),
            )
            val result = runCatching { gson.fromJson(json, CompResp::class.java) }.getOrNull()
            if (result == null || result.itemIds.isEmpty()) {
                Log.w("StylesVM", "Failed to parse composer response: $json")
                _state.update { it.copy(isComposerEnhancing = false, composerError = "Could not parse Gemini response.") }
                return@launch
            }
            val knownIds = images.map { it.driveId }.toSet()
            val required = s.composerItemIds
            val merged = (required + result.itemIds).filter { it in knownIds }.distinct()
            val cleanTags = result.tags.map { it.trim() }.filter { it.isNotEmpty() }.distinct()
            _state.update {
                it.copy(
                    isComposerEnhancing = false,
                    composerItemIds     = merged,
                    composerName        = if (it.composerName.isBlank()) result.name else it.composerName,
                    composerDescription = if (it.composerDescription.isBlank()) result.description else it.composerDescription,
                    composerTags        = if (it.composerTags.isEmpty()) cleanTags else it.composerTags,
                    composerReason      = result.reason,
                )
            }
        }
    }

    /** Persists the composer draft. Updates in-place when editing an existing style, else appends. */
    fun saveComposer(onDone: (Boolean) -> Unit = {}) {
        val s = _state.value
        if (s.composerItemIds.isEmpty()) { onDone(false); return }
        val editingId = s.composerEditingOutfitId
        if (editingId == null) {
            val name = s.composerName.ifBlank { "Outfit ${s.outfits.size + 1}" }
            saveOutfitDirectly(
                name        = name,
                description = s.composerDescription,
                itemIds     = s.composerItemIds,
                tags        = s.composerTags,
            ) { ok ->
                if (ok) closeComposer()
                onDone(ok)
            }
            return
        }
        val existing = s.outfits.find { it.id == editingId } ?: run { onDone(false); return }
        val name = s.composerName.ifBlank { existing.name }
        viewModelScope.launch {
            val saveId = existing.folderId.ifEmpty { saveFolderId ?: folderId ?: run { onDone(false); return@launch } }
            val idToName = drive.listFiles(saveId).associate { it.id to it.name }
            val itemNames = s.composerItemIds.mapNotNull { idToName[it] }
            val edited = existing.copy(
                name = name,
                description = s.composerDescription,
                itemIds = s.composerItemIds,
                itemNames = itemNames,
                tags = s.composerTags,
            )
            val updated = s.outfits.map { if (it.id == edited.id) edited else it }
            runCatching {
                val folderStyles = updated.filter { it.folderId == saveId || it.folderId.isEmpty() }
                drive.saveOutfitsJson(saveId, gson.toJson(folderStyles))
            }.onSuccess {
                _state.update { it.copy(outfits = updated, pendingWearOutfitId = edited.id) }
                closeComposer()
                onDone(true)
            }.onFailure { e ->
                _state.update { it.copy(error = e.message) }
                onDone(false)
            }
        }
    }

    fun clearComposerError() = _state.update { it.copy(composerError = null) }

    /** Derives per-layer target counts from the tag categories of the seed items. */
    private fun targetsFromSeed(ids: List<String>, images: List<DriveImage>): ComposerTargets {
        val byId = images.associateBy { it.driveId }
        var top = 0; var bottom = 0; var foot = 0; var outer = 0; var acc = 0
        ids.forEach { id ->
            val cat = byId[id]?.tags?.category?.lowercase().orEmpty()
            when {
                cat.contains("outer")                                   -> outer++
                cat.contains("foot") || cat.contains("shoe")            -> foot++
                cat.contains("bottom") || cat == "pants" || cat == "skirt" -> bottom++
                cat.contains("accessor")                                -> acc++
                cat.contains("top") || cat.contains("shirt") || cat == "dress" || cat == "suit" -> top++
                else                                                    -> top++
            }
        }
        // Defaults: Top and Bottom are required (always at least 1); Footwear, Outerwear, and
        // Accessory are optional and start at 0 unless the seed already includes one.
        return ComposerTargets(
            top       = maxOf(top, 1),
            bottom    = maxOf(bottom, 1),
            footwear  = foot,
            outerwear = outer,
            accessory = acc,
        )
    }

    // ---------- Delete ----------

    fun deleteOutfit(outfitId: String) {
        val updated = _state.value.outfits.filter { it.id != outfitId }
        viewModelScope.launch {
            val id = folderId ?: return@launch
            runCatching {
                drive.saveOutfitsJson(id, gson.toJson(updated))
            }.onSuccess {
                _state.update { it.copy(outfits = updated) }
            }.onFailure { e ->
                _state.update { it.copy(error = e.message) }
            }
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
            runCatching {
                for (id in affectedFolderIds) {
                    val folderStyles = updated.filter { it.folderId == id }
                    drive.saveOutfitsJson(id, gson.toJson(folderStyles))
                }
            }.onSuccess {
                _state.update { it.copy(outfits = updated, selectedOutfitIds = emptySet()) }
            }.onFailure { e ->
                _state.update { it.copy(error = e.message) }
            }
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
    fun openPredictionSetup(defaultSourceFolderId: String?) {
        val sourceFolders = defaultSourceFolderId?.let { setOf(it) } ?: emptySet()
        _state.update {
            it.copy(
                isPredictionSetupOpen   = true,
                composerFeedback        = "",
                composerVibes           = emptySet(),
                composerWeatherMode     = ComposerWeatherMode.AUTO,
                composerManualSeason    = "",
                composerManualTempC     = null,
                composerManualPrecip    = "",
                composerSourceFolderIds = sourceFolders,
            )
        }
    }

    fun closePredictionSetup() = _state.update { it.copy(isPredictionSetupOpen = false) }

    /**
     * Called from the prediction-setup dialog's "Find" CTA. Snapshots the user's goal text as
     * the first refinement entry so the rest of the prediction/refinement loop uses one path.
     */
    fun submitPredictionSetup(prefs: UserPreferences?, weather: WeatherData?, images: List<DriveImage>) {
        val s = _state.value
        val goal = s.composerFeedback.trim()
        val history = if (goal.isNotEmpty()) listOf(goal) else emptyList()
        _state.update {
            it.copy(
                isPredictionSetupOpen = false,
                composerFeedback      = "",
                feedbackHistory       = history,
                refinementInput       = "",
            )
        }
        doTriggerPrediction(prefs, weather, images, history)
    }

    fun refinePrediction(prefs: UserPreferences?, weather: WeatherData?, images: List<DriveImage>) {
        val feedback = _state.value.refinementInput.trim().ifEmpty { return }
        val history = _state.value.feedbackHistory + feedback
        _state.update { it.copy(feedbackHistory = history, refinementInput = "") }
        doTriggerPrediction(prefs, weather, images, history)
    }

    fun submitPresetPrediction(preset: String, prefs: UserPreferences?, weather: WeatherData?, images: List<DriveImage>) {
        val history = _state.value.feedbackHistory + preset
        _state.update { it.copy(feedbackHistory = history, refinementInput = "") }
        doTriggerPrediction(prefs, weather, images, history)
    }

    private fun doTriggerPrediction(
        prefs: UserPreferences?,
        weather: WeatherData?,
        images: List<DriveImage>,
        feedbackHistory: List<String>,
    ) {
        val styles = _state.value.outfits
        if (styles.isEmpty()) {
            _state.update { it.copy(predictionError = "No styles to choose from yet.") }
            return
        }
        val setup = _state.value
        val filteredImages = if (setup.composerSourceFolderIds.isEmpty()) images
            else images.filter { it.folderId in setup.composerSourceFolderIds }
        viewModelScope.launch {
            _state.update { it.copy(isPredicting = true, prediction = null, predictionSuggestions = emptyList(), predictionIndex = 0, predictionError = null) }

            val countryCode = deviceCountryCode()
            val region = listOfNotNull(weather?.cityName?.takeIf { it.isNotEmpty() }, countryCode).joinToString(", ")
            val fashionTrends = gemini.searchFashionTrends(region, UsageCategory.OUTFIT_PREDICT)

            val prompt = buildPredictionPrompt(
                preamble        = PromptStore.get(getApplication(), PromptKey.PREDICTION),
                prefs           = prefs,
                weather         = weather,
                cityName        = weather?.cityName,
                countryCode     = countryCode,
                fashionTrends   = fashionTrends,
                images          = filteredImages,
                styles          = styles,
                feedbackHistory = feedbackHistory,
                weatherMode     = setup.composerWeatherMode,
                manualSeason    = setup.composerManualSeason,
                manualTempC     = setup.composerManualTempC,
                manualPrecip    = setup.composerManualPrecip,
                vibes           = setup.composerVibes,
            )
            Log.d("StylesVM", "Prediction prompt length: ${prompt.length} chars")
            prompt.chunked(3000).forEachIndexed { i, chunk ->
                Log.d("StylesVM", "Prompt[$i]: $chunk")
            }

            val raw = gemini.generateText(prompt, UsageCategory.OUTFIT_PREDICT)
            if (raw == null) {
                _state.update { it.copy(isPredicting = false, predictionError = "Gemini did not respond.") }
                return@launch
            }

            val json = raw.trim()
                .removePrefix("```json").removePrefix("```").removeSuffix("```").trim()

            data class PredItem(val outfitId: String? = "", val reason: String? = "")
            data class PredResp(
                val suggestions: List<PredItem>? = null,
                // Backward-compat: older prompt variants returned a single object.
                val outfitId: String? = null,
                val reason: String? = null,
            )
            val result = runCatching { gson.fromJson(json, PredResp::class.java) }.getOrNull()

            val rawItems: List<PredItem> = when {
                result == null -> emptyList()
                !result.suggestions.isNullOrEmpty() -> result.suggestions
                !result.outfitId.isNullOrBlank() -> listOf(PredItem(result.outfitId, result.reason.orEmpty()))
                else -> emptyList()
            }

            // Filter to suggestions that actually exist in the wardrobe; cap at 3.
            val styleIds = styles.map { it.id }.toSet()
            val matched: List<OutfitPrediction> = rawItems
                .mapNotNull { p ->
                    val id = p.outfitId?.takeIf { it.isNotBlank() && it in styleIds } ?: return@mapNotNull null
                    OutfitPrediction(id, p.reason.orEmpty())
                }
                .distinctBy { it.outfitId }
                .take(3)

            if (matched.isEmpty()) {
                Log.w("StylesVM", "Failed to parse prediction response: $json")
                _state.update { it.copy(isPredicting = false, predictionError = "Could not parse Gemini response.") }
                return@launch
            }

            _state.update {
                it.copy(
                    isPredicting = false,
                    prediction = matched.first(),
                    predictionSuggestions = matched,
                    predictionIndex = 0,
                )
            }
        }
    }

    fun clearPrediction() = _state.update {
        it.copy(
            prediction = null,
            predictionError = null,
            predictionSuggestions = emptyList(),
            predictionIndex = 0,
            feedbackHistory = emptyList(),
            refinementInput = "",
        )
    }

    /**
     * Switches the currently-shown prediction within the existing suggestion list.
     * Swiping/arrowing through suggestions re-opens the editing view for that pick
     * and discards any in-progress edits (acceptable since this is a browse flow).
     */
    fun showPredictionAt(index: Int) {
        val list = _state.value.predictionSuggestions
        if (index !in list.indices) return
        val pred = list[index]
        val style = _state.value.outfits.find { it.id == pred.outfitId } ?: return
        _state.update {
            it.copy(
                predictionIndex = index,
                prediction = pred,
                isEditingOutfitView = true,
                draftItemIds = style.itemIds.toSet(),
                draftOutfitName = style.name,
                draftOutfitDescription = style.description,
                draftOutfitTags = style.tags,
                editingOutfit = style,
                alternativeIds = emptyList(),
            )
        }
    }

    fun updateRefinementInput(text: String) = _state.update { it.copy(refinementInput = text) }

    // ---------- New style composition ----------

    fun triggerComposition(prefs: UserPreferences?, weather: WeatherData?, images: List<DriveImage>) {
        _state.update { it.copy(feedbackHistory = emptyList(), refinementInput = "", lastCompositionRequiredIds = emptySet()) }
        doTriggerComposition(prefs, weather, images, emptyList(), emptySet())
    }

    fun triggerCompositionFromItems(
        requiredItemIds: Set<String>,
        prefs: UserPreferences?,
        weather: WeatherData?,
        images: List<DriveImage>,
    ) {
        _state.update { it.copy(feedbackHistory = emptyList(), refinementInput = "", lastCompositionRequiredIds = requiredItemIds) }
        doTriggerComposition(prefs, weather, images, emptyList(), requiredItemIds)
    }

    fun refineComposition(prefs: UserPreferences?, weather: WeatherData?, images: List<DriveImage>) {
        val feedback = _state.value.refinementInput.trim().ifEmpty { return }
        val history = _state.value.feedbackHistory + feedback
        val required = _state.value.lastCompositionRequiredIds
        _state.update { it.copy(feedbackHistory = history, refinementInput = "") }
        doTriggerComposition(prefs, weather, images, history, required)
    }

    fun submitPresetComposition(preset: String, prefs: UserPreferences?, weather: WeatherData?, images: List<DriveImage>) {
        val history = _state.value.feedbackHistory + preset
        val required = _state.value.lastCompositionRequiredIds
        _state.update { it.copy(feedbackHistory = history, refinementInput = "") }
        doTriggerComposition(prefs, weather, images, history, required)
    }

    private fun doTriggerComposition(
        prefs: UserPreferences?,
        weather: WeatherData?,
        images: List<DriveImage>,
        feedbackHistory: List<String>,
        requiredItemIds: Set<String>,
    ) {
        if (images.isEmpty()) {
            _state.update { it.copy(compositionError = "No wardrobe items to compose from.") }
            return
        }
        viewModelScope.launch {
            _state.update { it.copy(isComposing = true, newSuggestion = null, compositionError = null) }

            val countryCode = deviceCountryCode()
            val region = listOfNotNull(weather?.cityName?.takeIf { it.isNotEmpty() }, countryCode).joinToString(", ")
            val fashionTrends = gemini.searchFashionTrends(region, UsageCategory.OUTFIT_COMPOSE)

            val prompt = buildCompositionPrompt(
                preamble        = PromptStore.get(getApplication(), PromptKey.COMPOSITION),
                prefs           = prefs,
                weather         = weather,
                cityName        = weather?.cityName,
                countryCode     = countryCode,
                fashionTrends   = fashionTrends,
                images          = images,
                requiredItemIds = requiredItemIds,
                feedbackHistory = feedbackHistory,
            )
            Log.d("StylesVM", "=== COMPOSITION PROMPT (${prompt.length} chars, history=${feedbackHistory.size}) ===")
            prompt.chunked(3000).forEachIndexed { i, chunk ->
                Log.d("StylesVM", "CompositionPrompt[$i]: $chunk")
            }

            val raw = gemini.generateText(prompt, UsageCategory.OUTFIT_COMPOSE)
            if (raw == null) {
                _state.update { it.copy(isComposing = false, compositionError = "Gemini did not respond.") }
                return@launch
            }
            Log.d("StylesVM", "Composition raw response: $raw")

            val json = raw.trim()
                .removePrefix("```json").removePrefix("```").removeSuffix("```").trim()

            data class CompResp(
                val name: String = "",
                val description: String = "",
                val itemIds: List<String> = emptyList(),
                val reason: String = "",
                val tags: List<String> = emptyList(),
            )
            val result = runCatching { gson.fromJson(json, CompResp::class.java) }.getOrNull()

            if (result == null || result.itemIds.isEmpty()) {
                Log.w("StylesVM", "Failed to parse composition response: $json")
                _state.update { it.copy(isComposing = false, compositionError = "Could not parse Gemini response.") }
                return@launch
            }

            val knownIds = images.map { it.driveId }.toSet()
            val merged = (requiredItemIds + result.itemIds).filter { it in knownIds }.distinct()
            if (merged.isEmpty()) {
                Log.w("StylesVM", "Gemini returned no valid item IDs: ${result.itemIds}")
                _state.update { it.copy(isComposing = false, compositionError = "Suggested items not found in wardrobe.") }
                return@launch
            }

            _state.update {
                it.copy(
                    isComposing = false,
                    newSuggestion = NewOutfitSuggestion(
                        name        = result.name.ifBlank { "AI Outfit" },
                        description = result.description,
                        itemIds     = merged,
                        reason      = result.reason,
                        tags        = result.tags.map { it.trim() }.filter { it.isNotEmpty() }.distinct(),
                    ),
                )
            }
        }
    }

    fun clearNewSuggestion() = _state.update {
        it.copy(newSuggestion = null, compositionError = null, feedbackHistory = emptyList(), refinementInput = "")
    }

    fun clearError() = _state.update { it.copy(error = null) }

    // ---------- AI tag suggestions (outfit detail viewer) ----------

    fun suggestTagsForOutfit(outfit: Outfit, images: List<DriveImage>, prefs: UserPreferences?) {
        _state.update { it.copy(tagSuggestion = TagSuggestionState(outfitId = outfit.id, isLoading = true)) }
        viewModelScope.launch {
            val itemsById = images.associateBy { it.driveId }
            val items = outfit.itemIds.mapNotNull { itemsById[it] }
            val itemDescriptors = items.map { img ->
                val t = img.tags
                val parts = listOfNotNull(
                    t?.category?.takeIf { it.isNotBlank() }?.let { "category=$it" },
                    t?.type?.takeIf { it.isNotBlank() }?.let { "type=$it" },
                    t?.label?.takeIf { it.isNotBlank() }?.let { "label=$it" },
                    t?.colors?.takeIf { it.isNotEmpty() }?.let { "colors=${it.joinToString("/")}" },
                    t?.aesthetic?.takeIf { it.isNotEmpty() }?.let { "aesthetic=${it.joinToString("/")}" },
                    t?.seasonality?.takeIf { it.isNotEmpty() }?.let { "season=${it.joinToString("/")}" },
                    t?.uses?.takeIf { it.isNotEmpty() }?.let { "uses=${it.joinToString("/")}" },
                    t?.fit?.takeIf { it.isNotEmpty() }?.let { "fit=${it.joinToString("/")}" },
                    t?.material?.takeIf { it.isNotEmpty() }?.let { "material=${it.joinToString("/")}" },
                    t?.pattern?.takeIf { it.isNotEmpty() }?.let { "pattern=${it.joinToString("/")}" },
                )
                "- ${parts.joinToString(", ").ifBlank { img.name }}"
            }
            val langName = AppLanguage.toGeminiName(prefs?.language ?: AppLanguage.ENGLISH)
            val prompt = buildString {
                appendLine("You are tagging a clothing outfit.")
                appendLine("Suggest 3-6 short, lowercase, single-or-two-word tags describing occasions, vibes, seasons, settings, or dress codes that fit this outfit.")
                appendLine("Avoid restating individual garment categories (e.g. 'shirt', 'pants'). Avoid color-only tags unless the palette is the defining trait.")
                appendLine("Return ONLY a JSON array of strings, no markdown, no commentary. Example: [\"smart casual\",\"office\",\"autumn\"]")
                appendLine("Write tags in $langName.")
                appendLine()
                appendLine("Outfit name: ${outfit.name.ifBlank { "(untitled)" }}")
                if (outfit.description.isNotBlank()) appendLine("Description: ${outfit.description}")
                if (outfit.tags.isNotEmpty()) appendLine("Existing tags (do not repeat): ${outfit.tags.joinToString(", ")}")
                appendLine("Items:")
                itemDescriptors.forEach { appendLine(it) }
            }
            Log.d("StylesVM", "Suggest tags prompt:\n$prompt")
            val raw = gemini.generateText(prompt, UsageCategory.OTHER)
            if (raw == null) {
                _state.update {
                    it.copy(tagSuggestion = it.tagSuggestion?.copy(isLoading = false, error = "Gemini did not respond."))
                }
                return@launch
            }
            val json = raw.trim().removePrefix("```json").removePrefix("```").removeSuffix("```").trim()
            val parsed: List<String> = runCatching {
                gson.fromJson<List<String>>(json, object : TypeToken<List<String>>() {}.type)
            }.getOrElse {
                // Tolerate {"tags": [...]} shape.
                runCatching {
                    data class TagsResp(val tags: List<String> = emptyList())
                    gson.fromJson(json, TagsResp::class.java).tags
                }.getOrDefault(emptyList())
            } ?: emptyList()
            val existingLower = outfit.tags.map { it.lowercase(Locale.ROOT) }.toSet()
            val cleaned = parsed
                .map { it.trim() }
                .filter { it.isNotEmpty() && it.lowercase(Locale.ROOT) !in existingLower }
                .distinctBy { it.lowercase(Locale.ROOT) }
                .take(8)
            _state.update {
                it.copy(
                    tagSuggestion = it.tagSuggestion?.copy(
                        isLoading = false,
                        suggestions = cleaned,
                        error = if (cleaned.isEmpty()) "No new tags suggested." else null,
                    ),
                )
            }
        }
    }

    fun dismissTagSuggestions() = _state.update { it.copy(tagSuggestion = null) }

    fun openOutfitTagsEditor(outfitId: String) = _state.update { it.copy(tagEditingOutfitId = outfitId) }
    fun closeOutfitTagsEditor() = _state.update { it.copy(tagEditingOutfitId = null) }

    /** Replaces the tag list of an existing outfit and persists the change to Drive. */
    fun setOutfitTags(outfitId: String, newTags: List<String>) {
        val outfit = _state.value.outfits.find { it.id == outfitId } ?: return
        val cleaned = newTags
            .map { it.trim() }
            .filter { it.isNotEmpty() }
            .distinctBy { it.lowercase(Locale.ROOT) }
        if (cleaned == outfit.tags) {
            _state.update { it.copy(tagEditingOutfitId = null) }
            return
        }
        val updatedOutfit = outfit.copy(tags = cleaned)
        val targetFolderId = outfit.folderId.takeIf { it.isNotEmpty() } ?: saveFolderId ?: folderId ?: return
        val updatedAll = _state.value.outfits.map { if (it.id == outfitId) updatedOutfit else it }
        viewModelScope.launch {
            runCatching {
                val folderIds = updatedAll.map { it.folderId }.filter { it.isNotEmpty() }.toSet() + targetFolderId
                for (fid in folderIds) {
                    val perFolder = if (folderIds.size == 1) updatedAll
                    else updatedAll.filter { it.folderId == fid || (fid == targetFolderId && it.id == outfitId) }
                    drive.saveOutfitsJson(fid, gson.toJson(perFolder))
                }
            }.onSuccess {
                _state.update { it.copy(outfits = updatedAll, tagEditingOutfitId = null) }
            }.onFailure { e ->
                _state.update { it.copy(tagEditingOutfitId = null, error = e.message) }
            }
        }
    }

    /** Merges [tagsToAdd] into the outfit's existing tags and persists the change to Drive. */
    fun applyTagSuggestions(outfitId: String, tagsToAdd: List<String>) {
        val outfit = _state.value.outfits.find { it.id == outfitId } ?: return
        if (tagsToAdd.isEmpty()) {
            _state.update { it.copy(tagSuggestion = null) }
            return
        }
        val existingLower = outfit.tags.map { it.lowercase(Locale.ROOT) }.toSet()
        val merged = outfit.tags + tagsToAdd
            .map { it.trim() }
            .filter { it.isNotEmpty() && it.lowercase(Locale.ROOT) !in existingLower }
            .distinctBy { it.lowercase(Locale.ROOT) }
        if (merged.size == outfit.tags.size) {
            _state.update { it.copy(tagSuggestion = null) }
            return
        }
        val updatedOutfit = outfit.copy(tags = merged)
        val targetFolderId = outfit.folderId.takeIf { it.isNotEmpty() } ?: saveFolderId ?: folderId ?: return
        val updatedAll = _state.value.outfits.map { if (it.id == outfitId) updatedOutfit else it }
        _state.update { it.copy(tagSuggestion = it.tagSuggestion?.copy(isSaving = true)) }
        viewModelScope.launch {
            runCatching {
                // Mirror saveOutfit(): write the full outfit list to the target folder. If outfits
                // span multiple folders, also rewrite each affected sibling folder so nothing is lost.
                val folderIds = updatedAll.map { it.folderId }.filter { it.isNotEmpty() }.toSet() + targetFolderId
                for (fid in folderIds) {
                    val perFolder = if (folderIds.size == 1) updatedAll
                    else updatedAll.filter { it.folderId == fid || (fid == targetFolderId && it.id == outfitId) }
                    drive.saveOutfitsJson(fid, gson.toJson(perFolder))
                }
            }.onSuccess {
                _state.update { it.copy(outfits = updatedAll, tagSuggestion = null) }
            }.onFailure { e ->
                _state.update {
                    it.copy(tagSuggestion = it.tagSuggestion?.copy(isSaving = false, error = e.message ?: "Save failed"))
                }
            }
        }
    }

    /**
     * Returns the ISO 3166-1 alpha-2 country code for the device's current location.
     * Prefers the mobile network registration country (accurate regardless of device language),
     * falls back to SIM country, then device locale as a last resort.
     */
    private fun deviceCountryCode(): String {
        val tel = getApplication<Application>()
            .getSystemService(Context.TELEPHONY_SERVICE) as? TelephonyManager
        return tel?.networkCountryIso?.uppercase()?.takeIf { it.isNotEmpty() }
            ?: tel?.simCountryIso?.uppercase()?.takeIf { it.isNotEmpty() }
            ?: Locale.getDefault().country.takeIf { it.isNotEmpty() }
            ?: "US"
    }
}

// ---------- Prompt builder ----------

private fun buildPredictionPrompt(
    preamble: String,
    prefs: UserPreferences?,
    weather: WeatherData?,
    cityName: String?,
    countryCode: String,
    fashionTrends: FashionTrends?,
    images: List<DriveImage>,
    styles: List<Outfit>,
    feedbackHistory: List<String> = emptyList(),
    weatherMode: ComposerWeatherMode = ComposerWeatherMode.AUTO,
    manualSeason: String = "",
    manualTempC: Int? = null,
    manualPrecip: String = "",
    vibes: Set<String> = emptySet(),
): String {
    val c = prefs?.aiConsiderations ?: AiConsiderations()
    val age = prefs?.yearOfBirth?.let { LocalDate.now().year - it }

    // Compact wardrobe encoding: only include items that belong to at least one style
    val usedItemIds = styles.flatMap { it.itemIds }.toSet()
    val wardrobeJson = images
        .filter { it.driveId in usedItemIds }
        .joinToString(",", "[", "]") { img ->
            val t = img.tags
            if (t == null) {
                """{"id":"${img.driveId}","tags":null}"""
            } else {
                val uses   = t.uses.joinToString(",", "[", "]") { "\"$it\"" }
                val colors = t.colors.joinToString(",", "[", "]") { "\"$it\"" }
                """{"id":"${img.driveId}","tags":{"type":"${t.type}","category":"${t.category}","uses":$uses,"colors":$colors}}"""
            }
        }

    val stylesJson = styles.joinToString(",", "[", "]") { s ->
        val items = s.itemIds.joinToString(",", "[", "]") { "\"$it\"" }
        """{"id":"${s.id}","name":"${s.name}","items":$items}"""
    }

    val locationStr = listOfNotNull(cityName?.takeIf { it.isNotEmpty() }, countryCode)
        .joinToString(", ")
    val weatherStr = if (weather != null)
        "${weather.temperatureCelsius.toInt()}°C, ${wmoEmoji(weather.weatherCode)} (WMO ${weather.weatherCode})"
    else "unknown"

    return buildString {
        appendLine(preamble.trim())
        appendLine()
        val profileLines = buildList {
            if (c.gender) add("- Gender: ${prefs?.gender?.takeIf { it.isNotEmpty() } ?: "not specified"}")
            if (c.age) add("- Age: ${age?.toString() ?: "not specified"}")
            if (c.preferences) add("- Outfit preferences: ${prefs?.preferences?.takeIf { it.isNotEmpty() } ?: "none provided"}")
        }
        if (profileLines.isNotEmpty()) {
            appendLine("## User Profile")
            profileLines.forEach { appendLine(it) }
            appendLine()
        }
        if (c.location) {
            appendLine("## Location")
            appendLine("- City: ${cityName?.takeIf { it.isNotEmpty() } ?: "unknown"}")
            appendLine("- Country: $countryCode")
            appendLine("- Urban/rural: infer from city name (consider local style norms and practicality)")
            appendLine()
        }
        if (c.weather) {
            appendLine("## Today's Weather ($locationStr)")
            if (weatherMode == ComposerWeatherMode.MANUAL) {
                val parts = buildList {
                    manualSeason.takeIf { it.isNotEmpty() }?.let { add("Season: $it") }
                    manualTempC?.let { add("Temperature: ${it}°C") }
                    manualPrecip.takeIf { it.isNotEmpty() }?.let { add("Precipitation: $it") }
                }
                appendLine(
                    if (parts.isEmpty()) "manual override (no details specified)"
                    else parts.joinToString(", ") + " (manual override)"
                )
            } else {
                appendLine(weatherStr)
            }
            appendLine()
        }
        if (vibes.isNotEmpty()) {
            appendLine("## Preferred Vibes")
            appendLine("The user wants outfits matching these vibes: ${vibes.joinToString(", ")}.")
            appendLine()
        }
        if (c.trends) {
            appendLine("## Current Fashion Trends in $locationStr")
            if (fashionTrends != null) {
                appendLine("- Trending colors: ${fashionTrends.trendingColors.joinToString(", ").ifEmpty { "n/a" }}")
                appendLine("- Trending aesthetics: ${fashionTrends.trendingAesthetics.joinToString(", ").ifEmpty { "n/a" }}")
                appendLine("- Must-have items right now: ${fashionTrends.mustHaveItems.joinToString(", ").ifEmpty { "n/a" }}")
                appendLine("- Outdated / avoid: ${fashionTrends.outdatedItems.joinToString(", ").ifEmpty { "n/a" }}")
            } else {
                appendLine("not available")
            }
            appendLine()
        }
        appendLine("## Wardrobe Items (id + tags)")
        appendLine(wardrobeJson)
        appendLine()
        appendLine("## Existing Styles to Choose From")
        appendLine(stylesJson)
        appendLine()
        if (feedbackHistory.isNotEmpty()) {
            appendLine("## User Refinement Requests")
            appendLine("The user reviewed a previous suggestion and wants these adjustments (apply all of them):")
            feedbackHistory.forEachIndexed { i, fb -> appendLine("${i + 1}. $fb") }
            appendLine()
        }
        appendLine("Pick the THREE best outfits for today, ranked from best to worst fit.")
        appendLine("All three outfit IDs must come from the existing outfits list above and must be distinct.")
        appendLine("Respond with ONLY a valid JSON object — no markdown, no extra text:")
        append("""{"suggestions":[{"outfitId":"<id from the outfits list>","reason":"<1-2 sentence explanation>"},{"outfitId":"<second pick>","reason":"<why>"},{"outfitId":"<third pick>","reason":"<why>"}]}""")
        appendLine()
        appendLine()
        appendLine("IMPORTANT: Write all user-facing text fields (reason) in ${AppLanguage.toGeminiName(prefs?.language ?: AppLanguage.ENGLISH)}.")
    }
}

private fun buildAlternativesPrompt(
    preamble: String,
    item: DriveImage,
    otherStyleItems: List<DriveImage>,
    allImages: List<DriveImage>,
    prefs: UserPreferences?,
): String {
    fun driveImageToJson(img: DriveImage): String {
        val t = img.tags ?: return """{"id":"${img.driveId}","tags":null}"""
        val uses   = t.uses.joinToString(",", "[", "]") { "\"$it\"" }
        val colors = t.colors.joinToString(",", "[", "]") { "\"$it\"" }
        return """{"id":"${img.driveId}","tags":{"label":"${t.label}","type":"${t.type}","category":"${t.category}","uses":$uses,"colors":$colors}}"""
    }

    val otherStyleIds = otherStyleItems.map { it.driveId }.toSet()
    val candidatesJson = allImages
        .filter { it.driveId != item.driveId && it.driveId !in otherStyleIds }
        .joinToString(",", "[", "]") { driveImageToJson(it) }

    return buildString {
        appendLine(preamble.trim())
        appendLine()
        appendLine("## Item to replace")
        appendLine(driveImageToJson(item))
        appendLine()
        appendLine("## Other items already in the outfit (context — do NOT suggest these)")
        appendLine(otherStyleItems.joinToString(",", "[", "]") { driveImageToJson(it) })
        appendLine()
        appendLine("## Available wardrobe items to choose from")
        appendLine(candidatesJson)
        appendLine()
        appendLine("Respond with ONLY a valid JSON object — no markdown, no extra text:")
        append("""{"alternativeIds":["<id1>","<id2>","<id3>"]}""")
        appendLine()
        appendLine()
        appendLine("IMPORTANT: Return item IDs exactly as provided in the available wardrobe list.")
    }
}

private fun buildCompositionPrompt(
    preamble: String,
    prefs: UserPreferences?,
    weather: WeatherData?,
    cityName: String?,
    countryCode: String,
    fashionTrends: FashionTrends?,
    images: List<DriveImage>,
    requiredItemIds: Set<String> = emptySet(),
    feedbackHistory: List<String> = emptyList(),
): String {
    val c = prefs?.aiConsiderations ?: AiConsiderations()
    val age = prefs?.yearOfBirth?.let { LocalDate.now().year - it }

    // Include all tagged items; untagged items are listed with null tags
    val wardrobeJson = images.joinToString(",", "[", "]") { img ->
        val t = img.tags
        if (t == null) {
            """{"id":"${img.driveId}","name":"${img.name}","tags":null}"""
        } else {
            val uses   = t.uses.joinToString(",", "[", "]") { "\"$it\"" }
            val colors = t.colors.joinToString(",", "[", "]") { "\"$it\"" }
            """{"id":"${img.driveId}","name":"${img.name}","tags":{"type":"${t.type}","category":"${t.category}","uses":$uses,"colors":$colors}}"""
        }
    }

    val locationStr = listOfNotNull(cityName?.takeIf { it.isNotEmpty() }, countryCode)
        .joinToString(", ")
    val weatherStr = if (weather != null)
        "${weather.temperatureCelsius.toInt()}°C, ${wmoEmoji(weather.weatherCode)} (WMO ${weather.weatherCode})"
    else "unknown"

    return buildString {
        appendLine(preamble.trim())
        appendLine()
        val profileLines = buildList {
            if (c.gender) add("- Gender: ${prefs?.gender?.takeIf { it.isNotEmpty() } ?: "not specified"}")
            if (c.age) add("- Age: ${age?.toString() ?: "not specified"}")
            if (c.preferences) add("- Outfit preferences: ${prefs?.preferences?.takeIf { it.isNotEmpty() } ?: "none provided"}")
        }
        if (profileLines.isNotEmpty()) {
            appendLine("## User Profile")
            profileLines.forEach { appendLine(it) }
            appendLine()
        }
        if (c.location) {
            appendLine("## Location")
            appendLine("- City: ${cityName?.takeIf { it.isNotEmpty() } ?: "unknown"}")
            appendLine("- Country: $countryCode")
            appendLine("- Urban/rural: infer from city name (consider local style norms and practicality)")
            appendLine()
        }
        if (c.weather) {
            appendLine("## Today's Weather ($locationStr)")
            appendLine(weatherStr)
            appendLine()
        }
        if (c.trends) {
            appendLine("## Current Fashion Trends in $locationStr")
            if (fashionTrends != null) {
                appendLine("- Trending colors: ${fashionTrends.trendingColors.joinToString(", ").ifEmpty { "n/a" }}")
                appendLine("- Trending aesthetics: ${fashionTrends.trendingAesthetics.joinToString(", ").ifEmpty { "n/a" }}")
                appendLine("- Must-have items right now: ${fashionTrends.mustHaveItems.joinToString(", ").ifEmpty { "n/a" }}")
                appendLine("- Outdated / avoid: ${fashionTrends.outdatedItems.joinToString(", ").ifEmpty { "n/a" }}")
            } else {
                appendLine("not available")
            }
            appendLine()
        }
        appendLine("## Available Wardrobe Items (id + name + tags)")
        appendLine(wardrobeJson)
        appendLine()
        if (requiredItemIds.isNotEmpty()) {
            appendLine("## Required Items (MUST be included)")
            appendLine("The following item IDs MUST appear in your itemIds list — they are pre-selected by the user:")
            appendLine(requiredItemIds.joinToString(", ") { "\"$it\"" })
            appendLine("You may add 1–3 complementary items from the wardrobe to complete the outfit.")
            appendLine()
        }
        if (feedbackHistory.isNotEmpty()) {
            appendLine("## User Refinement Requests")
            appendLine("The user reviewed a previous suggestion and wants these adjustments (apply all of them):")
            feedbackHistory.forEachIndexed { i, fb -> appendLine("${i + 1}. $fb") }
            appendLine()
        }
        appendLine("Respond with ONLY a valid JSON object — no markdown, no extra text:")
        append("""{"name":"<outfit name>","description":"<style caption>","itemIds":["<id1>","<id2>",...],"reason":"<1-2 sentence explanation>","tags":["<short occasion/vibe tag>", "..."]}""")
        appendLine()
        appendLine()
        appendLine("Include 1-4 short, lowercase free-form tags describing the occasion, season, or vibe of the outfit (e.g. \"birthday\", \"travel\", \"work\", \"summer\", \"date night\"). Keep each tag to 1-2 words.")
        appendLine()
        appendLine()
        appendLine("IMPORTANT: Write all user-facing text fields (name, description, reason) in ${AppLanguage.toGeminiName(prefs?.language ?: AppLanguage.ENGLISH)}.")
    }
}

private fun buildComposerPrompt(
    preamble: String,
    prefs: UserPreferences?,
    prefOverride: String,
    weatherAuto: WeatherData?,
    weatherManual: Triple<String, Int?, String>?,
    vibes: Set<String>,
    targets: ComposerTargets,
    requiredItemIds: Set<String>,
    images: List<DriveImage>,
    countryCode: String,
    cityName: String?,
    fashionTrends: FashionTrends?,
    feedbackHistory: List<String>,
    language: String,
): String {
    val c = prefs?.aiConsiderations ?: AiConsiderations()
    val age = prefs?.yearOfBirth?.let { LocalDate.now().year - it }

    val wardrobeJson = images.joinToString(",", "[", "]") { img ->
        val t = img.tags
        if (t == null) {
            """{"id":"${img.driveId}","name":"${img.name}","tags":null}"""
        } else {
            val uses   = t.uses.joinToString(",", "[", "]") { "\"$it\"" }
            val colors = t.colors.joinToString(",", "[", "]") { "\"$it\"" }
            """{"id":"${img.driveId}","name":"${img.name}","tags":{"type":"${t.type}","category":"${t.category}","uses":$uses,"colors":$colors}}"""
        }
    }

    val weatherStr = when {
        weatherAuto != null ->
            "${weatherAuto.temperatureCelsius.toInt()}°C, ${wmoEmoji(weatherAuto.weatherCode)} (WMO ${weatherAuto.weatherCode}) — auto-detected"
        weatherManual != null -> {
            val (season, tempC, precip) = weatherManual
            listOfNotNull(
                season.takeIf { it.isNotEmpty() }?.let { "season: $it" },
                tempC?.let { "~${it}°C" },
                precip.takeIf { it.isNotEmpty() }?.let { "precipitation: $it" },
            ).joinToString(", ").ifEmpty { "unspecified (user did not set manual weather)" }
        }
        else -> "unknown"
    }

    val locationStr = listOfNotNull(cityName?.takeIf { it.isNotEmpty() }, countryCode).joinToString(", ")

    return buildString {
        appendLine(preamble.trim())
        appendLine()
        val profileLines = buildList {
            if (c.gender) add("- Gender: ${prefs?.gender?.takeIf { it.isNotEmpty() } ?: "not specified"}")
            if (c.age) add("- Age: ${age?.toString() ?: "not specified"}")
            if (c.preferences) add("- Outfit preference (user-editable for this composition): ${prefOverride.ifBlank { "none provided" }}")
        }
        if (profileLines.isNotEmpty()) {
            appendLine("## User Profile")
            profileLines.forEach { appendLine(it) }
            appendLine()
        }
        if (c.weather) {
            appendLine("## Weather")
            appendLine(weatherStr)
            appendLine()
        }
        if (vibes.isNotEmpty()) {
            appendLine("## Desired vibe")
            appendLine(vibes.joinToString(", "))
            appendLine()
        }
        appendLine("## Target composition (per-layer counts)")
        appendLine("- Base tops: ${targets.top}")
        appendLine("- Bottoms: ${targets.bottom}")
        appendLine("- Footwear: ${targets.footwear}")
        appendLine("- Outerwear: ${targets.outerwear}")
        appendLine("- Accessories: ${targets.accessory}")
        appendLine("Total items: ${targets.total()}")
        appendLine()
        if (c.location) {
            appendLine("## Location")
            appendLine(locationStr.ifEmpty { "unknown" })
            appendLine()
        }
        if (c.trends && fashionTrends != null) {
            appendLine("## Current fashion trends")
            appendLine("- Trending colors: ${fashionTrends.trendingColors.joinToString(", ").ifEmpty { "n/a" }}")
            appendLine("- Trending aesthetics: ${fashionTrends.trendingAesthetics.joinToString(", ").ifEmpty { "n/a" }}")
            appendLine("- Must-have items: ${fashionTrends.mustHaveItems.joinToString(", ").ifEmpty { "n/a" }}")
            appendLine()
        }
        appendLine("## Required items (MUST appear in itemIds verbatim)")
        if (requiredItemIds.isNotEmpty()) {
            appendLine(requiredItemIds.joinToString(", ") { "\"$it\"" })
        } else {
            appendLine("(none — compose freely)")
        }
        appendLine()
        appendLine("## Available wardrobe (id + name + tags)")
        appendLine(wardrobeJson)
        appendLine()
        if (feedbackHistory.isNotEmpty()) {
            appendLine("## User refinement requests (apply ALL of them)")
            feedbackHistory.forEachIndexed { i, fb -> appendLine("${i + 1}. $fb") }
            appendLine()
        }
        appendLine("Respond with ONLY a valid JSON object — no markdown, no extra text:")
        append("""{"name":"<outfit name>","description":"<1-2 sentence caption>","itemIds":["<id1>","<id2>",...],"reason":"<1-2 sentence explanation>","tags":["<short occasion/vibe tag>", "..."]}""")
        appendLine()
        appendLine()
        appendLine("Include 1-4 short, lowercase free-form tags describing the occasion, season, or vibe of the outfit (e.g. \"birthday\", \"travel\", \"work\", \"summer\", \"date night\"). Keep each tag to 1-2 words.")
        appendLine()
        appendLine()
        appendLine("IMPORTANT: Write all user-facing text fields (name, description, reason) in ${AppLanguage.toGeminiName(language)}.")
    }
}
