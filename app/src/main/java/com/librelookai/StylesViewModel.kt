package com.librelookai

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

data class StylePrediction(val styleId: String, val reason: String)

/** Gemini-composed outfit that doesn't yet exist as a saved style. */
data class NewStyleSuggestion(
    val name: String,
    val description: String,
    val itemIds: List<String>,
    val reason: String,
)

data class StylesUiState(
    val styles: List<Style> = emptyList(),
    /** All wardrobe images across all locations, for resolving style item icons. */
    val wardrobeImages: List<DriveImage> = emptyList(),
    val isLoading: Boolean = false,
    val isCreating: Boolean = false,
    /** True when the new style-editing view (not the old item-picker) is open. */
    val isEditingStyleView: Boolean = false,
    val draftItemIds: Set<String> = emptySet(),
    val draftStyleName: String = "",
    val draftStyleDescription: String = "",
    /** Non-null when editing an existing style; null when creating a new one. */
    val editingStyle: Style? = null,
    val showNameDialog: Boolean = false,
    // Predict existing style
    val isPredicting: Boolean = false,
    val prediction: StylePrediction? = null,
    val predictionError: String? = null,
    // Compose brand-new style
    val isComposing: Boolean = false,
    val newSuggestion: NewStyleSuggestion? = null,
    val compositionError: String? = null,
    // Refinement feedback (shared by prediction + composition loops)
    val refinementInput: String = "",
    val feedbackHistory: List<String> = emptyList(),
    val lastCompositionRequiredIds: Set<String> = emptySet(),
    // After saving a style, offer to wear it immediately
    val pendingWearStyleId: String? = null,
    // Multi-select for bulk actions
    val selectedStyleIds: Set<String> = emptySet(),
    // Item-swap alternatives suggested by Gemini
    val isLoadingAlternatives: Boolean = false,
    val alternativeIds: List<String> = emptyList(),
    val error: String? = null,
)

class StylesViewModel(app: Application) : AndroidViewModel(app) {

    private val drive = DriveRepository(app, GoogleAuthManager(app))
    private val gemini = GeminiRepository(app)
    private val gson = Gson()
    private var folderId: String? = null
    private var allFolderIds: List<String>? = null
    /** Folder to save new styles into; set independently of load scope. */
    private var saveFolderId: String? = null

    private val _state = MutableStateFlow(StylesUiState())
    val state: StateFlow<StylesUiState> = _state.asStateFlow()

    /** Called by MainActivity to inform which folder newly created styles should be saved to. */
    fun updateSaveFolder(folderId: String) {
        saveFolderId = folderId
    }

    fun setLocation(newFolderId: String) {
        if (folderId == newFolderId && allFolderIds == null) return
        folderId = newFolderId
        allFolderIds = null
        _state.update { StylesUiState(isLoading = true) }
        loadStyles()
    }

    fun setAllLocations(folderIds: List<String>) {
        if (folderId == null && allFolderIds?.toSet() == folderIds.toSet()) return
        folderId = null
        allFolderIds = folderIds.toList()
        _state.update { StylesUiState(isLoading = true) }
        // Read wardrobe disk caches on IO thread in parallel with styles loading.
        viewModelScope.launch(Dispatchers.IO) {
            val images = readWardrobeImagesFromCache(folderIds)
            _state.update { it.copy(wardrobeImages = images) }
        }
        loadStyles()
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
     * Re-reads wardrobe disk caches and updates [StylesUiState.wardrobeImages].
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

    private fun stylesLocalCacheFile(folderId: String) =
        java.io.File(getApplication<Application>().filesDir, "styles_cache_${folderId}.json")

    private fun readStylesLocalCache(id: String): List<Style> {
        val file = stylesLocalCacheFile(id)
        if (!file.exists()) return emptyList()
        val type = object : TypeToken<List<Style>>() {}.type
        return runCatching { gson.fromJson<List<Style>>(file.readText(), type) ?: emptyList() }
            .getOrDefault(emptyList())
    }

    fun loadStyles() {
        viewModelScope.launch {
            _state.update { it.copy(isLoading = true, error = null) }
            val ids = allFolderIds
            // Phase 1 — instant: show whatever is in local JSON cache
            val cached = if (ids != null) {
                ids.flatMap { readStylesLocalCache(it) }
            } else {
                folderId?.let { readStylesLocalCache(it) } ?: emptyList()
            }
            if (cached.isNotEmpty()) _state.update { it.copy(styles = cached, isLoading = false) }

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
                    ids.map { id -> async { loadStylesFromFolder(id, combinedNameToId) } }.awaitAll().flatten()
                } else {
                    val id = folderId ?: return@runCatching cached
                    loadStylesFromFolder(id)
                }
            }.onSuccess { styles ->
                _state.update { it.copy(styles = styles, isLoading = false) }
            }.onFailure { e ->
                _state.update { s ->
                    s.copy(isLoading = false, error = if (s.styles.isEmpty()) e.message else null)
                }
            }
        }
    }

    private suspend fun loadStylesFromFolder(id: String, nameToId: Map<String, String>? = null): List<Style> {
        val resolvedNameToId = nameToId ?: drive.listFiles(id).associate { it.name to it.id }
        val json = drive.loadStylesJson(id)
        val resolved = if (json != null) {
            val type = object : TypeToken<List<Style>>() {}.type
            val raw: List<Style> = gson.fromJson(json, type) ?: emptyList()
            raw.map { style ->
                style.copy(
                    itemIds = if (style.itemNames.isNotEmpty())
                        style.itemNames.mapNotNull { resolvedNameToId[it] }
                    else style.itemIds,
                    folderId = id,
                )
            }
        } else emptyList()
        runCatching { stylesLocalCacheFile(id).writeText(gson.toJson(resolved)) }
        return resolved
    }

    // ---------- Create flow ----------

    fun startCreating() = _state.update {
        it.copy(isCreating = true, draftItemIds = emptySet(), draftStyleName = "", draftStyleDescription = "", editingStyle = null)
    }

    fun startCreatingFromItems(itemIds: Set<String>, name: String = "", description: String = "") = _state.update {
        it.copy(isCreating = true, draftItemIds = itemIds, draftStyleName = name, draftStyleDescription = description, editingStyle = null)
    }

    /** Opens the style-editing view for an existing saved style. */
    fun startEditing(style: Style) = _state.update {
        it.copy(
            isEditingStyleView = true,
            isCreating = false,
            draftItemIds = style.itemIds.toSet(),
            draftStyleName = style.name,
            draftStyleDescription = style.description,
            editingStyle = style,
        )
    }

    /** Called when Gemini predicts an existing style — opens edit view pre-populated with it. */
    fun openPredictionInEditView(style: Style) = _state.update {
        it.copy(
            isEditingStyleView = true,
            draftItemIds = style.itemIds.toSet(),
            draftStyleName = style.name,
            draftStyleDescription = style.description,
            editingStyle = style,
        )
    }

    /** Called when Gemini composes a new outfit — opens edit view pre-populated with it. */
    fun openSuggestionInEditView(suggestion: NewStyleSuggestion) = _state.update {
        it.copy(
            isEditingStyleView = true,
            draftItemIds = suggestion.itemIds.toSet(),
            draftStyleName = suggestion.name,
            draftStyleDescription = suggestion.description,
            editingStyle = null,
        )
    }

    fun cancelStyleEditingView() = _state.update {
        it.copy(
            isEditingStyleView = false,
            draftItemIds = emptySet(),
            draftStyleName = "",
            draftStyleDescription = "",
            editingStyle = null,
            prediction = null,
            newSuggestion = null,
            alternativeIds = emptyList(),
            feedbackHistory = emptyList(),
            refinementInput = "",
        )
    }

    fun updateDraftName(name: String) = _state.update { it.copy(draftStyleName = name) }
    fun updateDraftDescription(description: String) = _state.update { it.copy(draftStyleDescription = description) }

    fun cancelCreating() = _state.update {
        it.copy(isCreating = false, isEditingStyleView = false, draftItemIds = emptySet(), draftStyleName = "", draftStyleDescription = "", editingStyle = null, showNameDialog = false)
    }

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
            val prompt = buildAlternativesPrompt(item, otherItems, images, prefs)
            val raw = gemini.generateText(prompt)
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

    fun clearPendingWear() = _state.update { it.copy(pendingWearStyleId = null) }

    fun confirmDraft() {
        val s = _state.value
        if (s.draftItemIds.isEmpty()) return
        // Name and description are always set inline in the picker — save directly, no popup.
        val resolvedName = s.draftStyleName.ifEmpty {
            s.editingStyle?.name ?: "Style ${s.styles.size + 1}"
        }
        saveStyle(resolvedName)
    }

    fun saveStyle(name: String) {
        val s = _state.value
        val draftIds = s.draftItemIds
        if (draftIds.isEmpty()) return
        viewModelScope.launch {
            val resolvedName = name.trim().ifEmpty {
                s.editingStyle?.name ?: "Style ${s.styles.size + 1}"
            }
            val description = s.draftStyleDescription.trim()
            val id = saveFolderId ?: folderId ?: return@launch

            // Fetch fresh file listing to populate stable itemNames (portable across account copies)
            val idToName = drive.listFiles(id).associate { it.id to it.name }
            val itemNames = draftIds.mapNotNull { idToName[it] }

            val updated = if (s.editingStyle != null) {
                val edited = s.editingStyle.copy(
                    name = resolvedName, description = description,
                    itemIds = draftIds.toList(), itemNames = itemNames,
                )
                s.styles.map { if (it.id == edited.id) edited else it }
            } else {
                s.styles + Style(
                    name = resolvedName, description = description,
                    itemIds = draftIds.toList(), itemNames = itemNames,
                )
            }
            val savedStyleId = if (s.editingStyle != null) s.editingStyle.id else updated.last().id
            runCatching {
                drive.saveStylesJson(id, gson.toJson(updated))
            }.onSuccess {
                _state.update {
                    it.copy(
                        styles = updated,
                        isCreating = false,
                        isEditingStyleView = false,
                        draftItemIds = emptySet(),
                        editingStyle = null,
                        showNameDialog = false,
                        prediction = null,
                        newSuggestion = null,
                        feedbackHistory = emptyList(),
                        refinementInput = "",
                        pendingWearStyleId = savedStyleId,
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
    fun saveStyleDirectly(
        name: String,
        description: String,
        itemIds: List<String>,
        onDone: (Boolean) -> Unit = {},
    ) {
        if (itemIds.isEmpty()) return
        viewModelScope.launch {
            val id = saveFolderId ?: folderId ?: run { onDone(false); return@launch }
            val idToName = drive.listFiles(id).associate { it.id to it.name }
            val itemNames = itemIds.mapNotNull { idToName[it] }
            val updated = _state.value.styles + Style(
                name = name.ifBlank { "Travel style" },
                description = description,
                itemIds = itemIds,
                itemNames = itemNames,
            )
            runCatching {
                drive.saveStylesJson(id, gson.toJson(updated))
            }.onSuccess {
                _state.update { it.copy(styles = updated) }
                onDone(true)
            }.onFailure { e ->
                _state.update { it.copy(error = e.message) }
                onDone(false)
            }
        }
    }

    // ---------- Delete ----------

    fun deleteStyle(styleId: String) {
        val updated = _state.value.styles.filter { it.id != styleId }
        viewModelScope.launch {
            val id = folderId ?: return@launch
            runCatching {
                drive.saveStylesJson(id, gson.toJson(updated))
            }.onSuccess {
                _state.update { it.copy(styles = updated) }
            }.onFailure { e ->
                _state.update { it.copy(error = e.message) }
            }
        }
    }

    // ---------- Multi-select ----------

    fun toggleStyleSelection(styleId: String) = _state.update { s ->
        val next = s.selectedStyleIds.toMutableSet()
        if (!next.add(styleId)) next.remove(styleId)
        s.copy(selectedStyleIds = next)
    }

    fun selectAllStyles(ids: List<String>) = _state.update { it.copy(selectedStyleIds = it.selectedStyleIds + ids) }

    fun clearStyleSelection() = _state.update { it.copy(selectedStyleIds = emptySet()) }

    fun deleteSelectedStyles() {
        val toDelete = _state.value.selectedStyleIds
        if (toDelete.isEmpty()) return
        val allStyles = _state.value.styles
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
                    drive.saveStylesJson(id, gson.toJson(folderStyles))
                }
            }.onSuccess {
                _state.update { it.copy(styles = updated, selectedStyleIds = emptySet()) }
            }.onFailure { e ->
                _state.update { it.copy(error = e.message) }
            }
        }
    }

    /**
     * Asks Gemini to merge the selected styles into one cohesive outfit.
     * The result surfaces as [newSuggestion], which auto-opens [StyleEditingView].
     */
    fun combineSelectedStyles(prefs: UserPreferences?, weather: WeatherData?, images: List<DriveImage>) {
        val selectedIds = _state.value.selectedStyleIds
        val selectedStyles = _state.value.styles.filter { it.id in selectedIds }
        if (selectedStyles.size < 2) return
        viewModelScope.launch {
            _state.update { it.copy(isComposing = true, newSuggestion = null, compositionError = null) }
            val countryCode = deviceCountryCode()
            val region = listOfNotNull(weather?.cityName?.takeIf { it.isNotEmpty() }, countryCode).joinToString(", ")
            val fashionTrends = gemini.searchFashionTrends(region)
            val prompt = buildCombinePrompt(
                prefs = prefs,
                weather = weather,
                countryCode = countryCode,
                fashionTrends = fashionTrends,
                styles = selectedStyles,
                images = images,
            )
            Log.d("StylesVM", "Combine prompt length: ${prompt.length} chars")
            val raw = gemini.generateText(prompt)
            if (raw == null) {
                _state.update { it.copy(isComposing = false, compositionError = "Gemini did not respond.") }
                return@launch
            }
            val json = raw.trim()
                .removePrefix("```json").removePrefix("```").removeSuffix("```").trim()
            data class CompResp(
                val name: String = "",
                val description: String = "",
                val itemIds: List<String> = emptyList(),
                val reason: String = "",
            )
            val result = runCatching { gson.fromJson(json, CompResp::class.java) }.getOrNull()
            if (result == null || result.itemIds.isEmpty()) {
                Log.w("StylesVM", "Failed to parse combine response: $json")
                _state.update { it.copy(isComposing = false, compositionError = "Could not parse Gemini response.") }
                return@launch
            }
            val knownIds = images.map { it.driveId }.toSet()
            val merged = result.itemIds.filter { it in knownIds }.distinct()
            if (merged.isEmpty()) {
                _state.update { it.copy(isComposing = false, compositionError = "Suggested items not found in wardrobe.") }
                return@launch
            }
            _state.update {
                it.copy(
                    isComposing = false,
                    selectedStyleIds = emptySet(),
                    newSuggestion = NewStyleSuggestion(
                        name        = result.name.ifBlank { "Combined Style" },
                        description = result.description,
                        itemIds     = merged,
                        reason      = result.reason,
                    ),
                )
            }
        }
    }

    // ---------- Style prediction ----------

    /**
     * Asks Gemini to pick the best existing style for today, given the user's profile,
     * current weather, and trending topics fetched from Google Trends.
     *
     * @param prefs   user preferences loaded from Drive (may be null if not yet set)
     * @param weather current weather reading (may be null if location not yet available)
     * @param images  full wardrobe list with tags
     */
    fun triggerPrediction(prefs: UserPreferences?, weather: WeatherData?, images: List<DriveImage>) {
        _state.update { it.copy(feedbackHistory = emptyList(), refinementInput = "") }
        doTriggerPrediction(prefs, weather, images, emptyList())
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
        val styles = _state.value.styles
        if (styles.isEmpty()) {
            _state.update { it.copy(predictionError = "No styles to choose from yet.") }
            return
        }
        viewModelScope.launch {
            _state.update { it.copy(isPredicting = true, prediction = null, predictionError = null) }

            val countryCode = deviceCountryCode()
            val region = listOfNotNull(weather?.cityName?.takeIf { it.isNotEmpty() }, countryCode).joinToString(", ")
            val fashionTrends = gemini.searchFashionTrends(region)

            val prompt = buildPredictionPrompt(
                prefs           = prefs,
                weather         = weather,
                cityName        = weather?.cityName,
                countryCode     = countryCode,
                fashionTrends   = fashionTrends,
                images          = images,
                styles          = styles,
                feedbackHistory = feedbackHistory,
            )
            Log.d("StylesVM", "Prediction prompt length: ${prompt.length} chars")
            prompt.chunked(3000).forEachIndexed { i, chunk ->
                Log.d("StylesVM", "Prompt[$i]: $chunk")
            }

            val raw = gemini.generateText(prompt)
            if (raw == null) {
                _state.update { it.copy(isPredicting = false, predictionError = "Gemini did not respond.") }
                return@launch
            }

            val json = raw.trim()
                .removePrefix("```json").removePrefix("```").removeSuffix("```").trim()

            val result = runCatching {
                data class PredResp(val styleId: String = "", val reason: String = "")
                gson.fromJson(json, PredResp::class.java)
            }.getOrNull()

            if (result == null || result.styleId.isBlank()) {
                Log.w("StylesVM", "Failed to parse prediction response: $json")
                _state.update { it.copy(isPredicting = false, predictionError = "Could not parse Gemini response.") }
                return@launch
            }

            val matched = styles.find { it.id == result.styleId }
            if (matched == null) {
                Log.w("StylesVM", "Gemini returned unknown styleId=${result.styleId}")
                _state.update { it.copy(isPredicting = false, predictionError = "Suggested style not found in wardrobe.") }
                return@launch
            }

            _state.update {
                it.copy(isPredicting = false, prediction = StylePrediction(result.styleId, result.reason))
            }
        }
    }

    fun clearPrediction() = _state.update {
        it.copy(prediction = null, predictionError = null, feedbackHistory = emptyList(), refinementInput = "")
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
            val fashionTrends = gemini.searchFashionTrends(region)

            val prompt = buildCompositionPrompt(
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

            val raw = gemini.generateText(prompt)
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
                    newSuggestion = NewStyleSuggestion(
                        name        = result.name.ifBlank { "AI Style" },
                        description = result.description,
                        itemIds     = merged,
                        reason      = result.reason,
                    ),
                )
            }
        }
    }

    fun clearNewSuggestion() = _state.update {
        it.copy(newSuggestion = null, compositionError = null, feedbackHistory = emptyList(), refinementInput = "")
    }

    fun clearError() = _state.update { it.copy(error = null) }

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
    prefs: UserPreferences?,
    weather: WeatherData?,
    cityName: String?,
    countryCode: String,
    fashionTrends: FashionTrends?,
    images: List<DriveImage>,
    styles: List<Style>,
    feedbackHistory: List<String> = emptyList(),
): String {
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
        appendLine("You are a personal fashion stylist AI. Choose exactly ONE existing style for the user to wear today.")
        appendLine()
        appendLine("## User Profile")
        appendLine("- Gender: ${prefs?.gender?.takeIf { it.isNotEmpty() } ?: "not specified"}")
        appendLine("- Age: ${age?.toString() ?: "not specified"}")
        appendLine("- Style preferences: ${prefs?.preferences?.takeIf { it.isNotEmpty() } ?: "none provided"}")
        appendLine()
        appendLine("## Location")
        appendLine("- City: ${cityName?.takeIf { it.isNotEmpty() } ?: "unknown"}")
        appendLine("- Country: $countryCode")
        appendLine("- Urban/rural: infer from city name (consider local style norms and practicality)")
        appendLine()
        appendLine("## Today's Weather ($locationStr)")
        appendLine(weatherStr)
        appendLine()
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
        appendLine("## Instructions")
        appendLine("Pick the single best style ID from the styles list that fits:")
        appendLine("1. The current weather (temperature, conditions)")
        appendLine("2. The trending topics and cultural context of $locationStr")
        appendLine("3. The user's personal preferences and profile")
        appendLine("4. The urban/rural character of the location")
        if (feedbackHistory.isNotEmpty()) appendLine("5. All of the user's refinement requests above")
        appendLine()
        appendLine("Respond with ONLY a valid JSON object — no markdown, no extra text:")
        append("""{"styleId":"<id from the styles list>","reason":"<1-2 sentence explanation>"}""")
        appendLine()
        appendLine()
        appendLine("IMPORTANT: Write all user-facing text fields (reason) in ${AppLanguage.toGeminiName(prefs?.language ?: AppLanguage.ENGLISH)}.")
    }
}

private fun buildAlternativesPrompt(
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
        appendLine("You are a personal fashion stylist AI. Suggest up to 10 alternative wardrobe items that could replace a specific item in an outfit.")
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
        appendLine("## Instructions")
        appendLine("Find up to 10 items from the available wardrobe that would work as a good replacement.")
        appendLine("The replacement should:")
        appendLine("1. Serve the same general purpose/category as the item being replaced")
        appendLine("2. Coordinate well with the other outfit items (colors, style, aesthetic)")
        appendLine("3. Be suitable for the same occasions as the original item")
        appendLine()
        appendLine("Respond with ONLY a valid JSON object — no markdown, no extra text:")
        append("""{"alternativeIds":["<id1>","<id2>","<id3>"]}""")
        appendLine()
        appendLine()
        appendLine("IMPORTANT: Return item IDs exactly as provided in the available wardrobe list.")
    }
}

private fun buildCompositionPrompt(
    prefs: UserPreferences?,
    weather: WeatherData?,
    cityName: String?,
    countryCode: String,
    fashionTrends: FashionTrends?,
    images: List<DriveImage>,
    requiredItemIds: Set<String> = emptySet(),
    feedbackHistory: List<String> = emptyList(),
): String {
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
        appendLine("You are a personal fashion stylist AI. Compose a brand-new outfit by selecting items from the user's wardrobe.")
        appendLine()
        appendLine("## User Profile")
        appendLine("- Gender: ${prefs?.gender?.takeIf { it.isNotEmpty() } ?: "not specified"}")
        appendLine("- Age: ${age?.toString() ?: "not specified"}")
        appendLine("- Style preferences: ${prefs?.preferences?.takeIf { it.isNotEmpty() } ?: "none provided"}")
        appendLine()
        appendLine("## Location")
        appendLine("- City: ${cityName?.takeIf { it.isNotEmpty() } ?: "unknown"}")
        appendLine("- Country: $countryCode")
        appendLine("- Urban/rural: infer from city name (consider local style norms and practicality)")
        appendLine()
        appendLine("## Today's Weather ($locationStr)")
        appendLine(weatherStr)
        appendLine()
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
        appendLine("## Instructions")
        val selectInstruction = if (requiredItemIds.isNotEmpty())
            "Build a cohesive outfit around the required items above, adding complementary pieces as needed."
        else
            "Select 4–6 item IDs from the wardrobe that form a complete, well-coordinated outfit."
        appendLine(selectInstruction)
        appendLine()
        appendLine("Every outfit MUST include all four of these layers (pick exactly one item per layer, unless the wardrobe has none):")
        appendLine("  • Base top    — t-shirt, shirt, blouse, or similar inner layer")
        appendLine("  • Bottom      — trousers, jeans, shorts, skirt, or similar")
        appendLine("  • Shoes       — any footwear")
        appendLine("  • Outer layer — jacket, coat, blazer, hoodie, or cardigan (add this OVER the base top; do NOT substitute it for a base top or shoes)")
        appendLine()
        appendLine("IMPORTANT outfit-structure rules:")
        appendLine("- Outerwear (jacket, coat, blazer, hoodie, cardigan) is an ADDITIONAL layer on top of a base top — never pick outerwear instead of a base top")
        appendLine("- Never build an outfit from only outerwear + shoes, or only outerwear + t-shirt with no bottom")
        appendLine("- Do NOT include two items of the same layer (e.g. two pairs of trousers, two jackets, two t-shirts)")
        appendLine()
        appendLine("Choose items that:")
        appendLine("1. Are appropriate for the current weather")
        appendLine("2. Reflect the trending topics and cultural context of $locationStr")
        appendLine("3. Match the user's personal preferences")
        appendLine("4. Work together visually (complementary colors, consistent style category)")
        appendLine("5. Suit the urban/rural character of $locationStr")
        if (feedbackHistory.isNotEmpty()) {
            appendLine("6. Address all of the user's refinement requests listed below")
            appendLine()
            appendLine("## User Refinement Requests")
            appendLine("The user reviewed a previous suggestion and wants these adjustments (apply all of them):")
            feedbackHistory.forEachIndexed { i, fb -> appendLine("${i + 1}. $fb") }
        }
        appendLine()
        appendLine("Also propose:")
        appendLine("- A short, evocative name for the outfit (\"name\")")
        appendLine("- A 1-2 sentence style description suitable as a caption (\"description\")")
        appendLine()
        appendLine("Respond with ONLY a valid JSON object — no markdown, no extra text:")
        append("""{"name":"<outfit name>","description":"<style caption>","itemIds":["<id1>","<id2>",...],"reason":"<1-2 sentence explanation>"}""")
        appendLine()
        appendLine()
        appendLine("IMPORTANT: Write all user-facing text fields (name, description, reason) in ${AppLanguage.toGeminiName(prefs?.language ?: AppLanguage.ENGLISH)}.")
    }
}

private fun buildCombinePrompt(
    prefs: UserPreferences?,
    weather: WeatherData?,
    countryCode: String,
    fashionTrends: FashionTrends?,
    styles: List<Style>,
    images: List<DriveImage>,
): String {
    val age = prefs?.yearOfBirth?.let { LocalDate.now().year - it }
    val idToImage = images.associateBy { it.driveId }

    fun itemJson(img: DriveImage): String {
        val t = img.tags ?: return """{"id":"${img.driveId}","tags":null}"""
        val uses   = t.uses.joinToString(",", "[", "]") { "\"$it\"" }
        val colors = t.colors.joinToString(",", "[", "]") { "\"$it\"" }
        return """{"id":"${img.driveId}","tags":{"label":"${t.label}","type":"${t.type}","category":"${t.category}","uses":$uses,"colors":$colors}}"""
    }

    val stylesJson = styles.joinToString(",\n", "[\n", "\n]") { style ->
        val itemsJson = style.itemIds.mapNotNull { idToImage[it] }
            .joinToString(",", "[", "]") { itemJson(it) }
        """  {"id":"${style.id}","name":"${style.name}","description":"${style.description}","items":$itemsJson}"""
    }

    val weatherStr = if (weather != null)
        "${weather.temperatureCelsius.toInt()}°C, ${wmoEmoji(weather.weatherCode)} (WMO ${weather.weatherCode})"
    else "unknown"

    return buildString {
        appendLine("You are a personal fashion stylist AI. Merge the following ${styles.size} existing styles into one new, cohesive outfit by selecting the best-matching items from across all of them.")
        appendLine()
        appendLine("## User Profile")
        appendLine("- Gender: ${prefs?.gender?.takeIf { it.isNotEmpty() } ?: "not specified"}")
        appendLine("- Age: ${age?.toString() ?: "not specified"}")
        appendLine("- Style preferences: ${prefs?.preferences?.takeIf { it.isNotEmpty() } ?: "none provided"}")
        appendLine()
        appendLine("## Today's Weather")
        appendLine(weatherStr)
        appendLine()
        appendLine("## Current Fashion Trends")
        if (fashionTrends != null) {
            appendLine("- Trending colors: ${fashionTrends.trendingColors.joinToString(", ").ifEmpty { "n/a" }}")
            appendLine("- Trending aesthetics: ${fashionTrends.trendingAesthetics.joinToString(", ").ifEmpty { "n/a" }}")
            appendLine("- Must-have items: ${fashionTrends.mustHaveItems.joinToString(", ").ifEmpty { "n/a" }}")
        } else {
            appendLine("not available")
        }
        appendLine()
        appendLine("## Styles to Combine (each with their items)")
        appendLine(stylesJson)
        appendLine()
        appendLine("## Instructions")
        appendLine("Select 4–6 item IDs from across all the styles above to form one unified, well-coordinated outfit.")
        appendLine()
        appendLine("Every outfit MUST include all four of these layers (pick exactly one item per layer, unless unavailable):")
        appendLine("  • Base top    — t-shirt, shirt, blouse, or similar inner layer")
        appendLine("  • Bottom      — trousers, jeans, shorts, skirt, or similar")
        appendLine("  • Shoes       — any footwear")
        appendLine("  • Outer layer — jacket, coat, blazer, hoodie, or cardigan (add this OVER the base top; do NOT substitute it for a base top or shoes)")
        appendLine()
        appendLine("IMPORTANT outfit-structure rules:")
        appendLine("- Outerwear is an ADDITIONAL layer on top of a base top — never pick outerwear instead of a base top")
        appendLine("- Never build an outfit from only outerwear + shoes, or outerwear + t-shirt with no bottom")
        appendLine("- Do NOT include two items of the same layer (e.g. two jackets, two pairs of trousers)")
        appendLine()
        appendLine("The combined outfit should:")
        appendLine("1. Blend the aesthetic DNA of the source styles into something cohesive")
        appendLine("2. Avoid redundancy (e.g., don't pick two pairs of trousers)")
        appendLine("3. Work together visually (complementary colors, consistent formality level)")
        appendLine("4. Be appropriate for the current weather")
        appendLine()
        appendLine("Also propose:")
        appendLine("- A short, evocative name for the combined outfit (\"name\")")
        appendLine("- A 1-2 sentence style description (\"description\")")
        appendLine("- A brief reason explaining how you merged the styles (\"reason\")")
        appendLine()
        appendLine("Respond with ONLY a valid JSON object — no markdown, no extra text:")
        append("""{"name":"<outfit name>","description":"<style caption>","itemIds":["<id1>","<id2>",...],"reason":"<1-2 sentence explanation>"}""")
        appendLine()
        appendLine()
        appendLine("IMPORTANT: Write all user-facing text fields (name, description, reason) in ${AppLanguage.toGeminiName(prefs?.language ?: AppLanguage.ENGLISH)}.")
    }
}
