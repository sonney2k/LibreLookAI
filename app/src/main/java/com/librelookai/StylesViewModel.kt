package com.librelookai

import android.app.Application
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

data class StylePrediction(
    val styleId: String,
    val reason: String,
)

data class StylesUiState(
    val styles: List<Style> = emptyList(),
    val isLoading: Boolean = false,
    val isCreating: Boolean = false,
    val draftItemIds: Set<String> = emptySet(),
    val draftStyleName: String = "",
    /** Non-null when editing an existing style; null when creating a new one. */
    val editingStyle: Style? = null,
    val showNameDialog: Boolean = false,
    val isPredicting: Boolean = false,
    val prediction: StylePrediction? = null,
    val predictionError: String? = null,
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
        it.copy(isCreating = true, draftItemIds = emptySet(), draftStyleName = "", editingStyle = null)
    }

    fun startCreatingFromItems(itemIds: Set<String>) = _state.update {
        it.copy(isCreating = true, draftItemIds = itemIds, draftStyleName = "", editingStyle = null)
    }

    fun startEditing(style: Style) = _state.update {
        it.copy(isCreating = true, draftItemIds = style.itemIds.toSet(), draftStyleName = style.name, editingStyle = style)
    }

    fun updateDraftName(name: String) = _state.update { it.copy(draftStyleName = name) }

    fun cancelCreating() = _state.update {
        it.copy(isCreating = false, draftItemIds = emptySet(), draftStyleName = "", editingStyle = null, showNameDialog = false)
    }

    fun toggleDraftItem(driveId: String) = _state.update { s ->
        val next = s.draftItemIds.toMutableSet()
        if (!next.add(driveId)) next.remove(driveId)
        s.copy(draftItemIds = next)
    }

    fun confirmDraft() {
        val s = _state.value
        if (s.draftItemIds.isEmpty()) return
        if (s.editingStyle != null) {
            // Editing: name was set inline — save immediately without the dialog
            saveStyle(s.draftStyleName.ifEmpty { s.editingStyle.name })
        } else {
            _state.update { it.copy(showNameDialog = true) }
        }
    }

    fun saveStyle(name: String) {
        val s = _state.value
        val draftIds = s.draftItemIds
        if (draftIds.isEmpty()) return
        viewModelScope.launch {
            val resolvedName = name.trim().ifEmpty {
                s.editingStyle?.name ?: "Style ${s.styles.size + 1}"
            }
            val updated = if (s.editingStyle != null) {
                val edited = s.editingStyle.copy(name = resolvedName, itemIds = draftIds.toList())
                s.styles.map { if (it.id == edited.id) edited else it }
            } else {
                s.styles + Style(name = resolvedName, itemIds = draftIds.toList())
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
            val countryCode = Locale.getDefault().country.takeIf { it.isNotEmpty() } ?: "US"
            val trendingTopics = trends.fetchTrending(countryCode)

            val prompt = buildPredictionPrompt(
                prefs         = prefs,
                weather       = weather,
                countryCode   = countryCode,
                trendingTopics = trendingTopics,
                images        = images,
                styles        = styles,
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

    fun clearError() = _state.update { it.copy(error = null) }
}

// ---------- Prompt builder ----------

private fun buildPredictionPrompt(
    prefs: UserPreferences?,
    weather: WeatherData?,
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

    val weatherStr = if (weather != null)
        "${weather.temperatureCelsius.toInt()}°C, ${wmoEmoji(weather.weatherCode)} (WMO ${weather.weatherCode})"
    else "unknown"

    val trendsStr = if (trendingTopics.isEmpty()) "not available"
    else trendingTopics.joinToString(", ")

    return """
You are a personal fashion stylist AI. Choose exactly ONE existing style for the user to wear today.

## User Profile
- Gender: ${prefs?.gender?.takeIf { it.isNotEmpty() } ?: "not specified"}
- Age: ${age?.toString() ?: "not specified"}
- Style preferences: ${prefs?.preferences?.takeIf { it.isNotEmpty() } ?: "none provided"}

## Today's Weather ($countryCode)
$weatherStr

## Trending Topics Today in $countryCode
$trendsStr

## Wardrobe Items (id + tags)
$wardrobeJson

## Existing Styles to Choose From
$stylesJson

## Instructions
Pick the single best style ID from the styles list that fits:
1. The current weather (temperature, conditions)
2. The trending topics and cultural context of $countryCode
3. The user's personal preferences and profile

Respond with ONLY a valid JSON object — no markdown, no extra text:
{"styleId":"<id from the styles list>","reason":"<1-2 sentence explanation>"}
    """.trimIndent()
}
