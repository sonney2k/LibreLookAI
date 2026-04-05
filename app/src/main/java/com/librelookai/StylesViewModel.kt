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
    val isLoading: Boolean = false,
    val isCreating: Boolean = false,
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
    val error: String? = null,
)

class StylesViewModel(app: Application) : AndroidViewModel(app) {

    private val drive = DriveRepository(app, GoogleAuthManager(app))
    private val gemini = GeminiRepository()
    private val gson = Gson()
    private var folderId: String? = null

    private val _state = MutableStateFlow(StylesUiState())
    val state: StateFlow<StylesUiState> = _state.asStateFlow()

    init { loadStyles() }

    // ---------- Load ----------

    fun loadStyles() {
        viewModelScope.launch {
            _state.update { it.copy(isLoading = true, error = null) }
            runCatching {
                val id = folderId ?: drive.getOrCreateFolder().also { folderId = it }
                val json = drive.loadStylesJson(id)
                if (json != null) {
                    val type = object : TypeToken<List<Style>>() {}.type
                    gson.fromJson<List<Style>>(json, type) ?: emptyList()
                } else emptyList()
            }.onSuccess { styles ->
                _state.update { it.copy(styles = styles, isLoading = false) }
            }.onFailure { e ->
                _state.update { it.copy(isLoading = false, error = e.message) }
            }
        }
    }

    // ---------- Create flow ----------

    fun startCreating() = _state.update {
        it.copy(isCreating = true, draftItemIds = emptySet(), draftStyleName = "", draftStyleDescription = "", editingStyle = null)
    }

    fun startCreatingFromItems(itemIds: Set<String>, name: String = "", description: String = "") = _state.update {
        it.copy(isCreating = true, draftItemIds = itemIds, draftStyleName = name, draftStyleDescription = description, editingStyle = null)
    }

    fun startEditing(style: Style) = _state.update {
        it.copy(isCreating = true, draftItemIds = style.itemIds.toSet(), draftStyleName = style.name, draftStyleDescription = style.description, editingStyle = style)
    }

    fun updateDraftName(name: String) = _state.update { it.copy(draftStyleName = name) }
    fun updateDraftDescription(description: String) = _state.update { it.copy(draftStyleDescription = description) }

    fun cancelCreating() = _state.update {
        it.copy(isCreating = false, draftItemIds = emptySet(), draftStyleName = "", draftStyleDescription = "", editingStyle = null, showNameDialog = false)
    }

    fun toggleDraftItem(driveId: String) = _state.update { s ->
        val next = s.draftItemIds.toMutableSet()
        if (!next.add(driveId)) next.remove(driveId)
        s.copy(draftItemIds = next)
    }

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
            val updated = if (s.editingStyle != null) {
                val edited = s.editingStyle.copy(name = resolvedName, description = description, itemIds = draftIds.toList())
                s.styles.map { if (it.id == edited.id) edited else it }
            } else {
                s.styles + Style(name = resolvedName, description = description, itemIds = draftIds.toList())
            }
            runCatching {
                val id = folderId ?: drive.getOrCreateFolder().also { folderId = it }
                drive.saveStylesJson(id, gson.toJson(updated))
            }.onSuccess {
                _state.update { it.copy(styles = updated, isCreating = false, draftItemIds = emptySet(), editingStyle = null, showNameDialog = false) }
            }.onFailure { e ->
                _state.update { it.copy(showNameDialog = false, error = e.message) }
            }
        }
    }

    // ---------- Delete ----------

    fun deleteStyle(styleId: String) {
        val updated = _state.value.styles.filter { it.id != styleId }
        viewModelScope.launch {
            runCatching {
                val id = folderId ?: drive.getOrCreateFolder().also { folderId = it }
                drive.saveStylesJson(id, gson.toJson(updated))
            }.onSuccess {
                _state.update { it.copy(styles = updated) }
            }.onFailure { e ->
                _state.update { it.copy(error = e.message) }
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
            "Select 2–5 item IDs from the wardrobe that form a cohesive, well-coordinated outfit."
        appendLine(selectInstruction)
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
    }
}
