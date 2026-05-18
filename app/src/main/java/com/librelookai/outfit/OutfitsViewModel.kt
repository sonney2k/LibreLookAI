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
import java.util.UUID

data class OutfitPrediction(val outfitId: String, val reason: String)

/**
 * One AI-generated outfit variant produced by the composer's "Generate with AI" flow.
 * Multiple are returned when the user asks Gemini for more than one suggestion so the
 * composer can let the user swipe between alternatives.
 */
data class ComposerSuggestion(
    /** slotId -> wardrobe item id that the AI picked for that slot. */
    val slotAssignments: Map<String, String>,
    val name: String,
    val description: String,
    val reason: String,
    val tags: List<String>,
)

enum class ComposerWeatherMode { AUTO, MANUAL }


data class OutfitsUiState(
    val outfits: List<Outfit> = emptyList(),
    /** All wardrobe images across all locations, for resolving style item icons. */
    val wardrobeImages: List<DriveImage> = emptyList(),
    val isLoading: Boolean = false,
    // Predict existing style
    val isPredicting: Boolean = false,
    val prediction: OutfitPrediction? = null,
    val predictionError: String? = null,
    /** When Gemini returns several outfit picks the user can slide through, the full list lives here. */
    val predictionSuggestions: List<OutfitPrediction> = emptyList(),
    val predictionIndex: Int = 0,
    // Refinement feedback (prediction loop)
    val feedbackHistory: List<String> = emptyList(),
    // After saving a style, offer to wear it immediately
    val pendingWearOutfitId: String? = null,
    /** Outfit id the list screen should scroll into view (and briefly highlight) when set. */
    val pendingScrollOutfitId: String? = null,
    // Multi-select for bulk actions
    val selectedOutfitIds: Set<String> = emptySet(),
    // Unified style composer
    val isComposerOpen: Boolean = false,
    /** Non-null when the composer is editing an existing saved style (update-in-place). */
    val composerEditingOutfitId: String? = null,
    val composerItemIds: List<String> = emptyList(),
    val composerWeatherMode: ComposerWeatherMode = ComposerWeatherMode.AUTO,
    val composerManualSeason: String = "",        // "" / Spring / Summer / Fall / Winter
    val composerManualTempC: Int? = null,          // null = unspecified
    val composerManualPrecip: String = "",         // "" / None / Light / Heavy
    val composerVibes: Set<String> = emptySet(),   // Casual / Sporty / Formal / …
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
    val composerSlots: List<OutfitSlot> = emptyList(),
    /** Snapshot of [composerSlots] selected-item ids at composer open. Used to detect real changes. */
    val composerInitialItemIds: List<String?> = emptyList(),
    val composerMode: ComposerMode = ComposerMode.EDIT,
    val composerAiSuggestedName: String = "",
    val composerAiSuggestedDescription: String = "",
    val composerAiSuggestedTags: List<String> = emptyList(),
    /**
     * AI-generated outfit variants the user can swipe through. Empty until the composer's
     * "Generate with AI" flow returns; cleared on close/discard or when re-running the AI.
     */
    val composerSuggestions: List<ComposerSuggestion> = emptyList(),
    val composerSuggestionIndex: Int = 0,
    /** True while the fullscreen swipe-through preview of AI suggestions is shown. */
    val composerSuggestionsViewerOpen: Boolean = false,
    val isSaveDialogOpen: Boolean = false,
    val predictionSetupSource: PredictionSetupSource = PredictionSetupSource.OUTFITS_LIST,
    /** True while the "Find with AI" setup dialog is showing on the Outfits tab. */
    val isPredictionSetupOpen: Boolean = false,
    /**
     * ISO date ("YYYY-MM-DD") of the future forecast day the user picked for the Find/Create
     * with AI setup. null means use today's live weather (default).
     */
    val composerForecastDate: String? = null,
    /** Number of suggestions Gemini is asked for (1..10). Default 3. */
    val composerSuggestionCount: Int = 3,
    /**
     * Per-invocation override of the AI considerations toggles (Settings → AI → standard
     * criteria). Null means "use the user's saved settings as-is"; set as soon as the user
     * touches one of the chips in the Find/Create-with-AI dialog so subsequent toggles
     * mutate the override instead of reverting to settings on every render.
     */
    val composerConsiderationsOverride: AiConsiderations? = null,
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

    fun clearPendingWear() = _state.update { it.copy(pendingWearOutfitId = null) }

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
            val newOutfit = Outfit(
                name = name.ifBlank { "Travel style" },
                description = description,
                itemIds = itemIds,
                itemNames = itemNames,
                tags = tags,
            )
            val updated = _state.value.outfits + newOutfit
            runCatching {
                drive.saveOutfitsJson(id, gson.toJson(updated))
            }.onSuccess {
                _state.update { it.copy(outfits = updated, pendingScrollOutfitId = newOutfit.id) }
                onDone(true)
            }.onFailure { e ->
                _state.update { it.copy(error = e.message) }
                onDone(false)
            }
        }
    }

    // ---------- Unified style composer ----------

    /**
     * Opens the composer prefilled with [seedItemIds]. The user supplies a preference string
     * prefilled from [prefs]; weather mode defaults to AUTO.
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
        val sourceFolders = defaultSourceFolderId?.let { setOf(it) } ?: emptySet()
        val byId = images.associateBy { it.driveId }
        val slots: List<OutfitSlot> = if (ids.isEmpty()) {
            // Accessories and one-piece are off by default for scratch outfits;
            // user adds them via "+ Add slot".
            Layer.values()
                .filter { it != Layer.Accessory && it != Layer.OnePiece }
                .map { layer ->
                    OutfitSlot(UUID.randomUUID().toString(), layer, null, false)
                }
        } else {
            ids.map { id ->
                val img = byId[id]
                val layer = img?.let { layerFor(it) } ?: Layer.Top
                OutfitSlot(UUID.randomUUID().toString(), layer, id, true)
            }
        }
        // Composer always opens in EDIT mode — both for new and existing outfits.
        // The "Fullscreen" action in the header opens a read-only viewer separately.
        val mode = ComposerMode.EDIT
        _state.update {
            it.copy(
                isComposerOpen              = true,
                composerEditingOutfitId      = editingStyleId,
                composerItemIds             = ids,
                composerSlots               = slots,
                composerInitialItemIds      = slots.map { it.selectedItemId },
                composerMode                = mode,
                composerWeatherMode         = ComposerWeatherMode.AUTO,
                composerManualSeason        = "",
                composerManualTempC         = null,
                composerManualPrecip        = "",
                composerVibes               = emptySet(),
                composerName                = initialName,
                composerDescription         = initialDescription,
                composerTags                = editingStyleId?.let { id ->
                    it.outfits.find { o -> o.id == id }?.tags ?: emptyList()
                } ?: emptyList(),
                composerFeedback            = "",
                composerFeedbackHistory     = emptyList(),
                composerReason              = "",
                composerSourceFolderIds     = sourceFolders,
                isComposerEnhancing         = false,
                composerError               = null,
                composerAiSuggestedName     = "",
                composerAiSuggestedDescription = "",
                composerAiSuggestedTags     = emptyList(),
                isSaveDialogOpen            = false,
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
            isComposerOpen              = false,
            composerEditingOutfitId      = null,
            composerItemIds             = emptyList(),
            composerSlots               = emptyList(),
            composerInitialItemIds      = emptyList(),
            composerFeedback            = "",
            composerFeedbackHistory     = emptyList(),
            composerReason              = "",
            composerError               = null,
            isSaveDialogOpen            = false,
            composerAiSuggestedName     = "",
            composerAiSuggestedDescription = "",
            composerAiSuggestedTags     = emptyList(),
            composerSuggestions         = emptyList(),
            composerSuggestionIndex     = 0,
            composerSuggestionsViewerOpen = false,
        )
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
    fun setComposerForecastDate(date: String?) = _state.update { it.copy(composerForecastDate = date) }
    fun setComposerSuggestionCount(n: Int) = _state.update {
        it.copy(composerSuggestionCount = n.coerceIn(1, 10))
    }
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
        val suggestionCount = s.composerSuggestionCount.coerceIn(1, 10)
        _state.update {
            it.copy(
                isComposerEnhancing     = true,
                composerError           = null,
                composerFeedback        = "",
                composerFeedbackHistory = history,
                composerSuggestions     = emptyList(),
                composerSuggestionIndex = 0,
            )
        }
        viewModelScope.launch {
            val countryCode = deviceCountryCode()
            val region = listOfNotNull(weather?.cityName?.takeIf { it.isNotEmpty() }, countryCode).joinToString(", ")
            val fashionTrends = try {
                gemini.searchFashionTrends(region, UsageCategory.OUTFIT_COMPOSE)
            } catch (e: com.librelookai.billing.InsufficientCreditsException) {
                _state.update { it.copy(isComposerEnhancing = false) }
                return@launch
            }
            val prefString = prefs?.preferences.orEmpty()
            val slots = s.composerSlots
            val prompt = buildComposerPrompt(
                preamble         = PromptStore.get(getApplication(), PromptKey.COMPOSER),
                prefs            = prefs,
                prefOverride     = prefString,
                weatherAuto      = if (s.composerWeatherMode == ComposerWeatherMode.AUTO) weather else null,
                weatherManual    = if (s.composerWeatherMode == ComposerWeatherMode.MANUAL) Triple(
                    s.composerManualSeason, s.composerManualTempC, s.composerManualPrecip
                ) else null,
                vibes            = s.composerVibes,
                slots            = slots,
                images           = images,
                countryCode      = countryCode,
                cityName         = weather?.cityName,
                fashionTrends    = fashionTrends,
                feedbackHistory  = history,
                language         = prefs?.language ?: AppLanguage.ENGLISH,
                suggestionCount  = suggestionCount,
                considerationsOverride = s.composerConsiderationsOverride,
            )
            Log.d("StylesVM", "Composer prompt length: ${prompt.length} chars")
            val raw = try {
                gemini.generateText(prompt, UsageCategory.OUTFIT_COMPOSE, bulkItems = suggestionCount)
            } catch (e: com.librelookai.billing.InsufficientCreditsException) {
                _state.update { it.copy(isComposerEnhancing = false) }
                return@launch
            }
            if (raw == null) {
                _state.update { it.copy(isComposerEnhancing = false, composerError = "Gemini did not respond.") }
                return@launch
            }
            val json = raw.trim()
                .removePrefix("```json").removePrefix("```").removeSuffix("```").trim()
            data class SlotAssignment(val slotId: String = "", val itemId: String = "")
            data class CompResp(
                val slots: List<SlotAssignment> = emptyList(),
                val name: String = "",
                val description: String = "",
                val reason: String = "",
                val tags: List<String> = emptyList(),
                // Legacy fallback: older prompt shape returned flat itemIds.
                val itemIds: List<String> = emptyList(),
            )
            data class MultiResp(
                val suggestions: List<CompResp>? = null,
                // Back-compat: the single-suggestion schema is a top-level CompResp.
                val slots: List<SlotAssignment> = emptyList(),
                val name: String = "",
                val description: String = "",
                val reason: String = "",
                val tags: List<String> = emptyList(),
                val itemIds: List<String> = emptyList(),
            )
            val parsed = runCatching { gson.fromJson(json, MultiResp::class.java) }.getOrNull()
            val variants: List<CompResp> = when {
                parsed == null -> emptyList()
                !parsed.suggestions.isNullOrEmpty() -> parsed.suggestions
                else -> listOf(CompResp(
                    slots = parsed.slots,
                    name = parsed.name,
                    description = parsed.description,
                    reason = parsed.reason,
                    tags = parsed.tags,
                    itemIds = parsed.itemIds,
                ))
            }.filter { it.slots.isNotEmpty() || it.itemIds.isNotEmpty() }

            if (variants.isEmpty()) {
                Log.w("StylesVM", "Failed to parse composer response: $json")
                _state.update { it.copy(isComposerEnhancing = false, composerError = "Could not parse Gemini response.") }
                return@launch
            }
            val knownIds = images.map { it.driveId }.toSet()
            val byImageId = images.associateBy { it.driveId }
            val currentSlots = s.composerSlots

            fun resolveAssignments(v: CompResp): Map<String, String> {
                val direct = v.slots
                    .filter { it.slotId.isNotEmpty() && it.itemId in knownIds }
                    .associate { it.slotId to it.itemId }
                if (direct.isNotEmpty()) return direct
                // Legacy: distribute itemIds across empty/unlocked slots by category.
                val unlockedSlotIds = currentSlots.filter { !(it.isLocked && it.selectedItemId != null) }.map { it.id }.toSet()
                val mutableSlots = currentSlots.toMutableList()
                val mapping = mutableMapOf<String, String>()
                for (candidateId in v.itemIds.filter { it in knownIds }) {
                    val img = byImageId[candidateId] ?: continue
                    val cat = layerFor(img) ?: Layer.Top
                    val idx = mutableSlots.indexOfFirst {
                        it.id in unlockedSlotIds && it.category == cat &&
                            it.selectedItemId == null && it.id !in mapping
                    }
                    if (idx >= 0) mapping[mutableSlots[idx].id] = candidateId
                }
                return mapping
            }

            val composerSuggestions = variants.map { v ->
                ComposerSuggestion(
                    slotAssignments = resolveAssignments(v),
                    name = v.name,
                    description = v.description,
                    reason = v.reason,
                    tags = v.tags.map { it.trim() }.filter { it.isNotEmpty() }.distinct(),
                )
            }

            val first = composerSuggestions.first()
            val updatedSlots = applyComposerSuggestionToSlots(currentSlots, first.slotAssignments)
            val mergedIds = updatedSlots.mapNotNull { it.selectedItemId }.distinct()
            _state.update {
                it.copy(
                    isComposerEnhancing            = false,
                    composerSlots                  = updatedSlots,
                    composerItemIds                = mergedIds,
                    composerAiSuggestedName        = first.name,
                    composerAiSuggestedDescription = first.description,
                    composerAiSuggestedTags        = first.tags,
                    composerReason                 = first.reason,
                    composerSuggestions            = composerSuggestions,
                    composerSuggestionIndex        = 0,
                    // Auto-open the fullscreen suggestion swiper when there's more than one option.
                    composerSuggestionsViewerOpen  = composerSuggestions.size > 1,
                )
            }
        }
    }

    fun closeComposerSuggestionsViewer() = _state.update { it.copy(composerSuggestionsViewerOpen = false) }
    fun openComposerSuggestionsViewer() = _state.update {
        if (it.composerSuggestions.size > 1) it.copy(composerSuggestionsViewerOpen = true) else it
    }

    /**
     * "Use this outfit" path from the fullscreen suggestion viewer. Applies the picked
     * suggestion to the composer like [showComposerSuggestionAt] AND drops the other
     * alternatives so the inline swiper / re-open affordance disappear. The user has chosen
     * the one to keep — the rest are noise from here on.
     */
    fun commitComposerSuggestion(index: Int) {
        val s = _state.value
        if (index !in s.composerSuggestions.indices) return
        val pick = s.composerSuggestions[index]
        val updatedSlots = applyComposerSuggestionToSlots(s.composerSlots, pick.slotAssignments)
        val mergedIds = updatedSlots.mapNotNull { it.selectedItemId }.distinct()
        _state.update {
            it.copy(
                composerSlots                  = updatedSlots,
                composerItemIds                = mergedIds,
                composerAiSuggestedName        = pick.name,
                composerAiSuggestedDescription = pick.description,
                composerAiSuggestedTags        = pick.tags,
                composerReason                 = pick.reason,
                composerSuggestions            = listOf(pick),
                composerSuggestionIndex        = 0,
                composerSuggestionsViewerOpen  = false,
            )
        }
    }

    /**
     * Switches the composer to a different AI-generated variant. Locked slots are preserved
     * (so the user can lock a piece they like, then keep browsing). Empty slots in the chosen
     * variant remain empty.
     */
    fun showComposerSuggestionAt(index: Int) {
        val s = _state.value
        if (index !in s.composerSuggestions.indices) return
        val pick = s.composerSuggestions[index]
        val updatedSlots = applyComposerSuggestionToSlots(s.composerSlots, pick.slotAssignments)
        val mergedIds = updatedSlots.mapNotNull { it.selectedItemId }.distinct()
        _state.update {
            it.copy(
                composerSuggestionIndex        = index,
                composerSlots                  = updatedSlots,
                composerItemIds                = mergedIds,
                composerAiSuggestedName        = pick.name,
                composerAiSuggestedDescription = pick.description,
                composerAiSuggestedTags        = pick.tags,
                composerReason                 = pick.reason,
            )
        }
    }

    private fun applyComposerSuggestionToSlots(
        slots: List<OutfitSlot>,
        assignments: Map<String, String>,
    ): List<OutfitSlot> = slots.map { slot ->
        val lockedFilled = slot.isLocked && slot.selectedItemId != null
        if (lockedFilled) slot
        else {
            val newItemId = assignments[slot.id]
            if (newItemId != null) slot.copy(selectedItemId = newItemId, isLocked = false)
            else slot
        }
    }

    fun clearComposerError() = _state.update { it.copy(composerError = null) }

    fun setComposerMode(mode: ComposerMode) = _state.update { it.copy(composerMode = mode) }

    fun addSlot(category: Layer) = _state.update { s ->
        s.copy(composerSlots = s.composerSlots + OutfitSlot(UUID.randomUUID().toString(), category, null, false))
    }

    fun removeSlot(slotId: String) = _state.update { s ->
        val slot = s.composerSlots.find { it.id == slotId }
        val newSlots = s.composerSlots.filter { it.id != slotId }
        val newIds = slot?.selectedItemId?.let { id -> s.composerItemIds - id } ?: s.composerItemIds
        s.copy(composerSlots = newSlots, composerItemIds = newIds)
    }

    fun setSlotItem(slotId: String, itemId: String?) = _state.update { s ->
        val newSlots = s.composerSlots.map { slot ->
            if (slot.id == slotId) slot.copy(selectedItemId = itemId, isLocked = itemId != null)
            else slot
        }
        val slotItemIds = newSlots.mapNotNull { it.selectedItemId }.distinct()
        s.copy(composerSlots = newSlots, composerItemIds = slotItemIds)
    }

    fun toggleSlotLock(slotId: String) = _state.update { s ->
        s.copy(composerSlots = s.composerSlots.map { slot ->
            if (slot.id == slotId && slot.selectedItemId != null) slot.copy(isLocked = !slot.isLocked)
            else slot
        })
    }

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
        if (editingId == null) {
            saveOutfitDirectly(
                name        = name.ifBlank { "Outfit ${s.outfits.size + 1}" },
                description = description,
                itemIds     = itemIds,
                tags        = tags,
            ) { ok ->
                if (ok) closeComposer()
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
    fun openPredictionSetup(
        defaultSourceFolderId: String?,
        source: PredictionSetupSource = PredictionSetupSource.OUTFITS_LIST,
    ) {
        val sourceFolders = defaultSourceFolderId?.let { setOf(it) } ?: emptySet()
        _state.update {
            it.copy(
                isPredictionSetupOpen          = true,
                predictionSetupSource          = source,
                composerFeedback               = "",
                composerVibes                  = emptySet(),
                composerWeatherMode            = ComposerWeatherMode.AUTO,
                composerManualSeason           = "",
                composerManualTempC            = null,
                composerManualPrecip           = "",
                composerSourceFolderIds        = sourceFolders,
                composerConsiderationsOverride = null,
            )
        }
    }

    /**
     * Toggle one of the per-invocation AI considerations from the Find/Create-with-AI dialog.
     * Seeds the override from [prefsDefault] on first interaction so unchanged fields keep
     * matching the user's saved settings.
     */
    fun setComposerConsideration(prefsDefault: AiConsiderations, transform: (AiConsiderations) -> AiConsiderations) {
        _state.update {
            val base = it.composerConsiderationsOverride ?: prefsDefault
            it.copy(composerConsiderationsOverride = transform(base))
        }
    }

    fun closePredictionSetup() = _state.update { it.copy(isPredictionSetupOpen = false) }

    /** Reset every field steered by the Tune-AI dialog back to its default. */
    fun resetComposerAi() = _state.update {
        it.copy(
            composerFeedback              = "",
            composerVibes                 = emptySet(),
            composerWeatherMode           = ComposerWeatherMode.AUTO,
            composerManualSeason          = "",
            composerManualTempC           = null,
            composerManualPrecip          = "",
            composerForecastDate          = null,
            composerConsiderationsOverride = null,
        )
    }

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
            )
        }
        if (s.predictionSetupSource == PredictionSetupSource.COMPOSER) {
            enhanceComposerWithAi(prefs, weather, images)
        } else {
            doTriggerPrediction(prefs, weather, images, history)
        }
    }

    fun submitPresetPrediction(preset: String, prefs: UserPreferences?, weather: WeatherData?, images: List<DriveImage>) {
        val history = _state.value.feedbackHistory + preset
        _state.update { it.copy(feedbackHistory = history) }
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
            val fashionTrends = try {
                gemini.searchFashionTrends(region, UsageCategory.OUTFIT_PREDICT)
            } catch (e: com.librelookai.billing.InsufficientCreditsException) {
                _state.update { it.copy(isPredicting = false) }
                return@launch
            }

            val suggestionCount = setup.composerSuggestionCount.coerceIn(1, 10)
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
                forecastDate    = setup.composerForecastDate,
                suggestionCount = suggestionCount,
                considerationsOverride = setup.composerConsiderationsOverride,
            )
            Log.d("StylesVM", "Prediction prompt length: ${prompt.length} chars")
            prompt.chunked(3000).forEachIndexed { i, chunk ->
                Log.d("StylesVM", "Prompt[$i]: $chunk")
            }

            val raw = try {
                gemini.generateText(prompt, UsageCategory.OUTFIT_PREDICT, bulkItems = suggestionCount)
            } catch (e: com.librelookai.billing.InsufficientCreditsException) {
                _state.update { it.copy(isPredicting = false) }
                return@launch
            }
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

            // Filter to suggestions that actually exist in the wardrobe; cap at the user-chosen count.
            val styleIds = styles.map { it.id }.toSet()
            val matched: List<OutfitPrediction> = rawItems
                .mapNotNull { p ->
                    val id = p.outfitId?.takeIf { it.isNotBlank() && it in styleIds } ?: return@mapNotNull null
                    OutfitPrediction(id, p.reason.orEmpty())
                }
                .distinctBy { it.outfitId }
                .take(suggestionCount)

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
        _state.update { it.copy(predictionIndex = index, prediction = pred) }
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
            val raw = try {
                gemini.generateText(prompt, UsageCategory.OTHER)
            } catch (e: com.librelookai.billing.InsufficientCreditsException) {
                _state.update {
                    it.copy(tagSuggestion = it.tagSuggestion?.copy(isLoading = false))
                }
                return@launch
            }
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
    forecastDate: String? = null,
    suggestionCount: Int = 3,
    considerationsOverride: AiConsiderations? = null,
): String {
    val c = considerationsOverride ?: prefs?.aiConsiderations ?: AiConsiderations()
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
            val dateLabel = forecastDate?.let { "Weather on $it ($locationStr)" } ?: "Today's Weather ($locationStr)"
            appendLine("## $dateLabel")
            if (forecastDate != null) {
                // The forecast detail itself is omitted server-side; the model is told the date
                // so it can reason about expected conditions when no current reading applies.
                appendLine("(planning ahead — dress for the forecast on $forecastDate)")
            } else if (weatherMode == ComposerWeatherMode.MANUAL) {
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
        val countWord = when (suggestionCount) {
            1 -> "the SINGLE best outfit"
            2 -> "the TWO best outfits"
            3 -> "the THREE best outfits"
            else -> "the $suggestionCount best outfits"
        }
        appendLine("Pick $countWord for today, ranked from best to worst fit.")
        appendLine("All outfit IDs must come from the existing outfits list above and must be distinct.")
        appendLine("Return exactly $suggestionCount entries in the suggestions array (or fewer if not enough distinct outfits exist).")
        appendLine("Respond with ONLY a valid JSON object — no markdown, no extra text:")
        append("""{"suggestions":[{"outfitId":"<id from the outfits list>","reason":"<1-2 sentence explanation>"}]}""")
        appendLine()
        appendLine()
        appendLine("IMPORTANT: Write all user-facing text fields (reason) in ${AppLanguage.toGeminiName(prefs?.language ?: AppLanguage.ENGLISH)}.")
    }
}

private fun buildComposerPrompt(
    preamble: String,
    prefs: UserPreferences?,
    prefOverride: String,
    weatherAuto: WeatherData?,
    weatherManual: Triple<String, Int?, String>?,
    vibes: Set<String>,
    slots: List<OutfitSlot>,
    images: List<DriveImage>,
    countryCode: String,
    cityName: String?,
    fashionTrends: FashionTrends?,
    feedbackHistory: List<String>,
    language: String,
    suggestionCount: Int = 1,
    considerationsOverride: AiConsiderations? = null,
): String {
    val c = considerationsOverride ?: prefs?.aiConsiderations ?: AiConsiderations()
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
        appendLine("## Target slots")
        appendLine("Each slot has an id, category, and optionally a required itemId (locked by user).")
        appendLine("For slots WITHOUT a requiredItemId, pick an appropriate item from the wardrobe.")
        appendLine("For slots WITH a requiredItemId, include that exact item unchanged.")
        slots.forEach { slot ->
            val req = if (slot.isLocked && slot.selectedItemId != null) """ "requiredItemId":"${slot.selectedItemId}"""" else ""
            appendLine("""{"slotId":"${slot.id}","category":"${slot.category.name}"$req}""")
        }
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
        appendLine("## Available wardrobe (id + name + tags)")
        appendLine(wardrobeJson)
        appendLine()
        if (feedbackHistory.isNotEmpty()) {
            appendLine("## User refinement requests (apply ALL of them)")
            feedbackHistory.forEachIndexed { i, fb -> appendLine("${i + 1}. $fb") }
            appendLine()
        }
        val count = suggestionCount.coerceAtLeast(1)
        if (count > 1) {
            val countWord = when (count) {
                2 -> "TWO distinct outfit variants"
                3 -> "THREE distinct outfit variants"
                else -> "$count distinct outfit variants"
            }
            appendLine("Compose $countWord that fit the constraints above, ranked from best to worst.")
            appendLine("Each variant must fill EVERY non-locked slot and must respect every locked slot's requiredItemId.")
            appendLine("Variants should differ meaningfully from each other (swap key items, change colour palette, change vibe) — do not just shuffle one outfit.")
            appendLine("Return exactly $count entries in the suggestions array (or fewer if the wardrobe cannot yield that many distinct outfits).")
            appendLine("Respond with ONLY a valid JSON object — no markdown, no extra text:")
            append("""{"suggestions":[{"slots":[{"slotId":"<id>","itemId":"<wardrobeItemId>"}],"name":"<outfit name>","description":"<1-2 sentence caption>","reason":"<1-2 sentence explanation>","tags":["<short occasion/vibe tag>"]}]}""")
        } else {
            appendLine("Respond with ONLY a valid JSON object — no markdown, no extra text:")
            append("""{"slots":[{"slotId":"<id>","itemId":"<wardrobeItemId>"}],"name":"<outfit name>","description":"<1-2 sentence caption>","reason":"<1-2 sentence explanation>","tags":["<short occasion/vibe tag>"]}""")
        }
        appendLine()
        appendLine()
        appendLine("Include 1-4 short, lowercase free-form tags describing the occasion, season, or vibe of the outfit (e.g. \"birthday\", \"travel\", \"work\", \"summer\", \"date night\"). Keep each tag to 1-2 words.")
        appendLine()
        appendLine()
        appendLine("IMPORTANT: Write all user-facing text fields (name, description, reason) in ${AppLanguage.toGeminiName(language)}.")
    }
}
