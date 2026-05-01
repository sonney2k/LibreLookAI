package com.librelookai

import android.app.Application
import android.util.Log
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.google.gson.Gson
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.time.LocalDate
import java.time.format.DateTimeFormatter

data class TravelUiState(
    val destination: String = "",
    val startDate: LocalDate = LocalDate.now(),
    val days: Int = 7,
    val isLoadingForecast: Boolean = false,
    val isGenerating: Boolean = false,
    val resolvedDestination: String = "",
    val forecast: List<DayForecast> = emptyList(),
    val isHistoricalForecast: Boolean = false,
    val historicalReferenceYear: Int? = null,
    /** Tracks which (destination, startDate, days) the current forecast was fetched for. */
    val fetchedKey: Triple<String, LocalDate, Int>? = null,
    val packingList: PackingList? = null,
    val refinementInput: String = "",
    val feedbackHistory: List<String> = emptyList(),
    val error: String? = null,
)

class TravelViewModel(app: Application) : AndroidViewModel(app) {

    private val weather = WeatherRepository(app)
    private val gemini  = GeminiRepository(app)
    private val gson    = Gson()

    private val _state = MutableStateFlow(TravelUiState())
    val state: StateFlow<TravelUiState> = _state.asStateFlow()

    fun updateDestination(s: String) = _state.update { it.copy(destination = s) }
    fun updateDays(n: Int)           = _state.update { it.copy(days = n.coerceIn(1, 21)) }
    fun updateStartDate(d: LocalDate) = _state.update { it.copy(startDate = d) }
    fun updateRefinementInput(s: String) = _state.update { it.copy(refinementInput = s) }
    fun clearError()  = _state.update { it.copy(error = null) }
    fun clearResult() = _state.update {
        it.copy(
            packingList = null, forecast = emptyList(), resolvedDestination = "",
            fetchedKey = null, isHistoricalForecast = false, historicalReferenceYear = null,
            feedbackHistory = emptyList(), refinementInput = "", error = null,
        )
    }

    fun generate(prefs: UserPreferences?, images: List<DriveImage>, styles: List<Outfit>) {
        _state.update { it.copy(feedbackHistory = emptyList(), refinementInput = "") }
        doGenerate(prefs, images, styles, emptyList())
    }

    fun refine(prefs: UserPreferences?, images: List<DriveImage>, styles: List<Outfit>) {
        val feedback = _state.value.refinementInput.trim().ifEmpty { return }
        val history  = _state.value.feedbackHistory + feedback
        _state.update { it.copy(feedbackHistory = history, refinementInput = "") }
        doGenerate(prefs, images, styles, history)
    }

    fun submitPreset(
        preset: String,
        prefs: UserPreferences?,
        images: List<DriveImage>,
        styles: List<Outfit>,
    ) {
        val history = _state.value.feedbackHistory + preset
        _state.update { it.copy(feedbackHistory = history, refinementInput = "") }
        doGenerate(prefs, images, styles, history)
    }

    private fun doGenerate(
        prefs: UserPreferences?,
        images: List<DriveImage>,
        styles: List<Outfit>,
        feedbackHistory: List<String>,
    ) {
        val dest      = _state.value.destination.trim()
        val startDate = _state.value.startDate
        val days      = _state.value.days
        if (dest.isEmpty()) {
            _state.update { it.copy(error = "Please enter a destination.") }
            return
        }

        viewModelScope.launch {
            // Phase 1 — fetch forecast only when input changed
            val currentKey = Triple(dest, startDate, days)
            val needsForecast = _state.value.fetchedKey != currentKey

            var resolvedDest  = _state.value.resolvedDestination
            var forecast      = _state.value.forecast
            var isHistorical  = _state.value.isHistoricalForecast
            var refYear       = _state.value.historicalReferenceYear

            if (needsForecast) {
                _state.update { it.copy(isLoadingForecast = true, error = null) }
                val result = weather.fetchDestinationForecast(dest, days, startDate)
                if (result == null) {
                    _state.update { it.copy(isLoadingForecast = false, error = "Could not fetch weather for \"$dest\". Check the destination name.") }
                    return@launch
                }
                resolvedDest = result.resolvedName
                forecast     = result.forecasts
                isHistorical = result.isHistorical
                refYear      = result.referenceYear
                _state.update {
                    it.copy(
                        isLoadingForecast      = false,
                        resolvedDestination    = resolvedDest,
                        forecast               = forecast,
                        isHistoricalForecast   = isHistorical,
                        historicalReferenceYear = refYear,
                        fetchedKey             = currentKey,
                    )
                }
            }

            // Phase 2 — generate packing list
            _state.update { it.copy(isGenerating = true, packingList = null, error = null) }

            val prompt = buildPackingPrompt(
                preamble        = PromptStore.get(getApplication(), PromptKey.PACKING),
                prefs           = prefs,
                destination     = resolvedDest,
                startDate       = startDate,
                days            = days,
                forecast        = forecast,
                isHistorical    = isHistorical,
                referenceYear   = refYear,
                images          = images,
                styles          = styles,
                feedbackHistory = feedbackHistory,
            )
            Log.d("TravelVM", "Packing prompt length: ${prompt.length} chars")

            val raw = gemini.generateText(prompt, UsageCategory.TRAVEL)
            if (raw == null) {
                _state.update { it.copy(isGenerating = false, error = "Gemini did not respond.") }
                return@launch
            }

            val json = raw.trim()
                .removePrefix("```json").removePrefix("```").removeSuffix("```").trim()

            val result = runCatching { gson.fromJson(json, PackingList::class.java) }.getOrNull()
            if (result == null || result.outfits.isEmpty()) {
                Log.w("TravelVM", "Failed to parse packing list: $json")
                _state.update { it.copy(isGenerating = false, error = "Could not parse Gemini response.") }
                return@launch
            }

            _state.update {
                it.copy(
                    isGenerating = false,
                    packingList  = result.copy(destination = resolvedDest, days = days),
                )
            }
        }
    }
}

// ---------- Prompt builder ----------

private val DOW_FMT  = DateTimeFormatter.ofPattern("EEE MMM d")
private val DATE_FMT = DateTimeFormatter.ofPattern("MMM d, yyyy")

private fun buildPackingPrompt(
    preamble: String,
    prefs: UserPreferences?,
    destination: String,
    startDate: LocalDate,
    days: Int,
    forecast: List<DayForecast>,
    isHistorical: Boolean,
    referenceYear: Int?,
    images: List<DriveImage>,
    styles: List<Outfit>,
    feedbackHistory: List<String>,
): String {
    val age    = prefs?.yearOfBirth?.let { LocalDate.now().year - it }
    val endDate = startDate.plusDays((days - 1).toLong())

    val wardrobeJson = images.joinToString(",", "[", "]") { img ->
        val t = img.tags
        if (t == null) {
            """{"id":"${img.driveId}","name":"${img.name}","tags":null}"""
        } else {
            val uses        = t.uses.joinToString(",", "[", "]") { "\"$it\"" }
            val colors      = t.colors.joinToString(",", "[", "]") { "\"$it\"" }
            val seasonality = t.seasonality.joinToString(",", "[", "]") { "\"$it\"" }
            val aesthetic   = t.aesthetic.joinToString(",", "[", "]") { "\"$it\"" }
            val fit         = t.fit.joinToString(",", "[", "]") { "\"$it\"" }
            val material    = t.material.joinToString(",", "[", "]") { "\"$it\"" }
            val pattern     = t.pattern.joinToString(",", "[", "]") { "\"$it\"" }
            """{"id":"${img.driveId}","name":"${img.name}","tags":{"type":"${t.type}","category":"${t.category}","uses":$uses,"colors":$colors,"seasonality":$seasonality,"aesthetic":$aesthetic,"fit":$fit,"material":$material,"pattern":$pattern}}"""
        }
    }

    val stylesJson = styles.joinToString(",", "[", "]") { s ->
        val items = s.itemIds.joinToString(",", "[", "]") { "\"$it\"" }
        """{"id":"${s.id}","name":"${s.name}","items":$items}"""
    }

    val c = prefs?.aiConsiderations ?: AiConsiderations()
    return buildString {
        appendLine(preamble.trim())
        appendLine()
        appendLine("## Trip Details")
        appendLine("- Destination: $destination")
        appendLine("- Travel dates: ${startDate.format(DATE_FMT)} – ${endDate.format(DATE_FMT)} ($days days)")
        appendLine()

        val forecastLabel = if (isHistorical && referenceYear != null)
            "Historical climate reference for $destination ($referenceYear data — trip dates are outside the live forecast window)"
        else
            "Daily Weather Forecast for $destination"
        appendLine("## $forecastLabel")
        forecast.forEachIndexed { i, day ->
            val label = runCatching { LocalDate.parse(day.date).format(DOW_FMT) }.getOrElse { day.date }
            appendLine("- Day ${i + 1} ($label): ${day.minTempC.toInt()}°C – ${day.maxTempC.toInt()}°C ${wmoEmoji(day.weatherCode)}")
        }
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
        appendLine("## Available Wardrobe Items (id + name + tags)")
        appendLine(wardrobeJson)
        appendLine()
        if (styles.isNotEmpty()) {
            appendLine("## Saved Styles (pre-built outfits)")
            appendLine(stylesJson)
            appendLine()
        }
        if (feedbackHistory.isNotEmpty()) {
            appendLine("## User Adjustments (apply all of these)")
            feedbackHistory.forEachIndexed { i, fb -> appendLine("${i + 1}. $fb") }
            appendLine()
        }
        appendLine("Respond with ONLY a valid JSON object — no markdown, no extra text:")
        append("""{"outfits":[{"occasion":"<group label>","itemIds":["<id1>","<id2>",...],"description":"<1-sentence style note>"},...],"extraItems":["<item1>","<item2>",...],"reason":"<1-2 sentence summary>"}""")
        appendLine()
        appendLine()
        appendLine("IMPORTANT: Write all user-facing text fields (occasion, description, reason, extraItems) in ${AppLanguage.toGeminiName(prefs?.language ?: AppLanguage.ENGLISH)}.")
    }
}
