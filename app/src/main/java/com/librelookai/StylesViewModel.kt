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
    val error: String? = null,
)

class StylesViewModel(app: Application) : AndroidViewModel(app) {

    private val drive = DriveRepository(app, GoogleAuthManager(app))
    private val gemini = GeminiRepository()
    private val trends = TrendsRepository()
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
    fun triggerPrediction(
        prefs: UserPreferences?,
        weather: WeatherData?,
        images: List<DriveImage>,
    ) {
        val styles = _state.value.styles
        if (styles.isEmpty()) {
            _state.update { it.copy(predictionError = "No styles to choose from yet.") }
            return
        }
        viewModelScope.launch {
            _state.update { it.copy(isPredicting = true, prediction = null, predictionError = null) }

            // Fetch trending topics (non-fatal — empty list is fine)
            val countryCode = deviceCountryCode()
            val trendingTopics = trends.fetchTrending(countryCode)

            val prompt = buildPredictionPrompt(
                prefs          = prefs,
                weather        = weather,
                cityName       = weather?.cityName,
                countryCode    = countryCode,
                trendingTopics = trendingTopics,
                images         = images,
                styles         = styles,
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

            // Strip markdown fences if present
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

            // Validate the suggested style actually exists
            val matched = styles.find { it.id == result.styleId }
            if (matched == null) {
                Log.w("StylesVM", "Gemini returned unknown styleId=${result.styleId}")
                _state.update { it.copy(isPredicting = false, predictionError = "Suggested style not found in wardrobe.") }
                return@launch
            }

            _state.update {
                it.copy(
                    isPredicting = false,
                    prediction = StylePrediction(result.styleId, result.reason),
                )
            }
        }
    }

    fun clearPrediction() = _state.update { it.copy(prediction = null, predictionError = null) }

    // ---------- New style composition ----------

    /**
     * Asks Gemini to compose a brand-new outfit by selecting items from the full wardrobe.
     * The result is a [NewStyleSuggestion] with a proposed name, item IDs, and reason.
     * Call [startCreatingFromItems] with the suggestion's data to open the picker pre-filled.
     */
    fun triggerComposition(
        prefs: UserPreferences?,
        weather: WeatherData?,
        images: List<DriveImage>,
    ) {
        if (images.isEmpty()) {
            _state.update { it.copy(compositionError = "No wardrobe items to compose from.") }
            return
        }
        viewModelScope.launch {
            _state.update { it.copy(isComposing = true, newSuggestion = null, compositionError = null) }

            val countryCode = deviceCountryCode()
            val trendingTopics = trends.fetchTrending(countryCode)

            val prompt = buildCompositionPrompt(
                prefs          = prefs,
                weather        = weather,
                cityName       = weather?.cityName,
                countryCode    = countryCode,
                trendingTopics = trendingTopics,
                images         = images,
            )
            Log.d("StylesVM", "=== COMPOSITION PROMPT (${prompt.length} chars) ===")
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

            // Keep only IDs that actually exist in the wardrobe
            val knownIds = images.map { it.driveId }.toSet()
            val validIds = result.itemIds.filter { it in knownIds }
            if (validIds.isEmpty()) {
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
                        itemIds     = validIds,
                        reason      = result.reason,
                    ),
                )
            }
        }
    }

    /**
     * Like [triggerComposition] but the given [requiredItemIds] MUST appear in the result.
     * Gemini is asked to complete the outfit around them; the required IDs are force-merged
     * into the response even if Gemini omits them.
     */
    fun triggerCompositionFromItems(
        requiredItemIds: Set<String>,
        prefs: UserPreferences?,
        weather: WeatherData?,
        images: List<DriveImage>,
    ) {
        if (images.isEmpty()) {
            _state.update { it.copy(compositionError = "No wardrobe items to compose from.") }
            return
        }
        viewModelScope.launch {
            _state.update { it.copy(isComposing = true, newSuggestion = null, compositionError = null) }

            val countryCode = deviceCountryCode()
            val trendingTopics = trends.fetchTrending(countryCode)

            val prompt = buildCompositionPrompt(
                prefs           = prefs,
                weather         = weather,
                cityName        = weather?.cityName,
                countryCode     = countryCode,
                trendingTopics  = trendingTopics,
                images          = images,
                requiredItemIds = requiredItemIds,
            )
            Log.d("StylesVM", "=== COMPOSITION FROM ITEMS PROMPT (${prompt.length} chars) ===")
            prompt.chunked(3000).forEachIndexed { i, chunk ->
                Log.d("StylesVM", "CompositionFromItemsPrompt[$i]: $chunk")
            }

            val raw = gemini.generateText(prompt)
            if (raw == null) {
                _state.update { it.copy(isComposing = false, compositionError = "Gemini did not respond.") }
                return@launch
            }
            Log.d("StylesVM", "CompositionFromItems raw response: $raw")

            val json = raw.trim()
                .removePrefix("```json").removePrefix("```").removeSuffix("```").trim()

            data class CompResp(
                val name: String = "",
                val description: String = "",
                val itemIds: List<String> = emptyList(),
                val reason: String = "",
            )
            val result = runCatching { gson.fromJson(json, CompResp::class.java) }.getOrNull()

            if (result == null) {
                Log.w("StylesVM", "Failed to parse composition response: $json")
                _state.update { it.copy(isComposing = false, compositionError = "Could not parse Gemini response.") }
                return@launch
            }

            val knownIds = images.map { it.driveId }.toSet()
            // Force-include required items; add any valid extras Gemini suggested
            val merged = (requiredItemIds + result.itemIds).filter { it in knownIds }.distinct()
            if (merged.isEmpty()) {
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

    fun clearNewSuggestion() = _state.update { it.copy(newSuggestion = null, compositionError = null) }

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
    trendingTopics: List<String>,
    images: List<DriveImage>,
    styles: List<Style>,
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
    val trendsStr = if (trendingTopics.isEmpty()) "not available"
    else trendingTopics.joinToString(", ")

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
        appendLine("## Trending Topics Today in $countryCode")
        appendLine(trendsStr)
        appendLine()
        appendLine("## Wardrobe Items (id + tags)")
        appendLine(wardrobeJson)
        appendLine()
        appendLine("## Existing Styles to Choose From")
        appendLine(stylesJson)
        appendLine()
        appendLine("## Instructions")
        appendLine("Pick the single best style ID from the styles list that fits:")
        appendLine("1. The current weather (temperature, conditions)")
        appendLine("2. The trending topics and cultural context of $locationStr")
        appendLine("3. The user's personal preferences and profile")
        appendLine("4. The urban/rural character of the location")
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
    trendingTopics: List<String>,
    images: List<DriveImage>,
    requiredItemIds: Set<String> = emptySet(),
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
    val trendsStr = if (trendingTopics.isEmpty()) "not available"
    else trendingTopics.joinToString(", ")

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
        appendLine("## Trending Topics Today in $countryCode")
        appendLine(trendsStr)
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
        appendLine("Also propose:")
        appendLine("- A short, evocative name for the outfit (\"name\")")
        appendLine("- A 1-2 sentence style description suitable as a caption (\"description\")")
        appendLine()
        appendLine("Respond with ONLY a valid JSON object — no markdown, no extra text:")
        append("""{"name":"<outfit name>","description":"<style caption>","itemIds":["<id1>","<id2>",...],"reason":"<1-2 sentence explanation>"}""")
    }
}
