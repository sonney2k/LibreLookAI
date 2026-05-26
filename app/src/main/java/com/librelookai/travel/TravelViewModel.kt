package com.librelookai.travel
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
import com.librelookai.data.model.DayForecast
import com.librelookai.data.model.Outfit
import com.librelookai.data.model.PackingList
import com.librelookai.gemini.GeminiRepository
import com.librelookai.gemini.PromptKey
import com.librelookai.gemini.PromptStore
import com.librelookai.gemini.UsageCategory
import com.librelookai.settings.AiConsiderations
import com.librelookai.settings.UserPreferences
import com.librelookai.wardrobe.DriveImage
import com.librelookai.weather.DestinationSuggestion
import com.librelookai.weather.WeatherRepository
import com.librelookai.settings.AppLanguage
import com.librelookai.weather.wmoEmoji
import kotlinx.coroutines.Job

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
    val error: String? = null,
    /** Free-text goal/notes added to the prompt (e.g. "business meetings + evening dinners"). */
    val goal: String = "",
    /**
     * Number of distinct outfits to generate. null means "follow [days] (one per day)"; the user
     * can override with an explicit number. Independent of [days] once set so changing the
     * duration doesn't silently wipe a custom count.
     */
    val outfitCount: Int? = null,
    /**
     * Per-trip override of the AI considerations toggles. Null means "use the user's saved
     * settings as-is". Cleared on [clearResult] and reset when the user clears the screen.
     */
    val considerationsOverride: AiConsiderations? = null,
    /** Live geocoding suggestions for the destination field. */
    val destinationSuggestions: List<DestinationSuggestion> = emptyList(),
    /**
     * Coordinates of the picked autocomplete suggestion. Set on [pickDestination], cleared when the
     * user edits the destination text by hand. When present, the forecast fetch uses them directly
     * instead of re-geocoding the (possibly localized) display string, which the geocoder can't match.
     */
    val destinationLatitude: Double? = null,
    val destinationLongitude: Double? = null,
    /** Style vibes selected for this trip (e.g. "Casual", "Business"). */
    val vibes: Set<String> = emptySet(),
    /**
     * Closets to source wardrobe items from when generating. Empty = every closet (default).
     * Mirrors the composer's `composerSourceFolderIds`.
     */
    val sourceFolderIds: Set<String> = emptySet(),
)

class TravelViewModel(app: Application) : AndroidViewModel(app) {

    private val weather = WeatherRepository(app)
    private val gemini  = GeminiRepository(app)
    private val gson    = Gson()

    private val _state = MutableStateFlow(TravelUiState())
    val state: StateFlow<TravelUiState> = _state.asStateFlow()

    private var suggestionJob: Job? = null

    fun updateDestination(s: String, language: String = "en") {
        _state.update { it.copy(destination = s, destinationLatitude = null, destinationLongitude = null) }
        suggestionJob?.cancel()
        val query = s.trim()
        if (query.length < 2) {
            _state.update { it.copy(destinationSuggestions = emptyList()) }
            return
        }
        suggestionJob = viewModelScope.launch {
            kotlinx.coroutines.delay(250L) // debounce
            val results = weather.searchDestinations(query, language = language)
            // Only apply if user hasn't moved on / cleared
            if (_state.value.destination.trim() == query) {
                _state.update { it.copy(destinationSuggestions = results) }
            }
        }
    }

    fun pickDestination(s: DestinationSuggestion) {
        suggestionJob?.cancel()
        _state.update {
            it.copy(
                destination = s.display,
                destinationLatitude = s.latitude,
                destinationLongitude = s.longitude,
                destinationSuggestions = emptyList(),
            )
        }
    }

    fun clearDestinationSuggestions() {
        _state.update { it.copy(destinationSuggestions = emptyList()) }
    }

    fun updateDays(n: Int)           = _state.update { it.copy(days = n.coerceIn(1, 21)) }
    fun updateStartDate(d: LocalDate) = _state.update { it.copy(startDate = d) }
    fun updateGoal(s: String) = _state.update { it.copy(goal = s) }
    fun updateOutfitCount(n: Int?) = _state.update {
        it.copy(outfitCount = n?.coerceIn(1, 21))
    }

    /** Toggle one consideration for this trip, seeding from user defaults on first interaction. */
    fun setConsideration(prefsDefault: AiConsiderations, transform: (AiConsiderations) -> AiConsiderations) {
        _state.update {
            val base = it.considerationsOverride ?: prefsDefault
            it.copy(considerationsOverride = transform(base))
        }
    }
    fun clearError()  = _state.update { it.copy(error = null) }

    /**
     * Clear the just-generated [packingList] without losing the planner inputs. Called by the
     * screen after it has persisted the result as a [com.librelookai.data.model.Trip] so the
     * preview never renders inline.
     */
    fun consumePackingList() = _state.update { it.copy(packingList = null) }

    fun clearResult() = _state.update {
        it.copy(
            packingList = null, forecast = emptyList(), resolvedDestination = "",
            fetchedKey = null, isHistoricalForecast = false, historicalReferenceYear = null,
            error = null,
            goal = "", outfitCount = null, considerationsOverride = null,
            vibes = emptySet(), sourceFolderIds = emptySet(),
            destinationLatitude = null, destinationLongitude = null,
        )
    }

    fun toggleVibe(value: String) = _state.update {
        val next = it.vibes.toMutableSet()
        if (!next.remove(value)) next.add(value)
        it.copy(vibes = next)
    }

    /**
     * Seed the source-closet filter to the currently selected closet ([folderId]); null = all
     * closets. Called when the planner opens so it defaults to whatever closet the user is viewing.
     */
    fun seedSourceFolders(folderId: String?) = _state.update {
        it.copy(sourceFolderIds = folderId?.let { id -> setOf(id) } ?: emptySet())
    }

    /** Toggle whether [folderId] is included in the source-closet filter. Empty set = all closets. */
    fun toggleSourceFolder(folderId: String) = _state.update {
        val next = it.sourceFolderIds.toMutableSet()
        if (!next.add(folderId)) next.remove(folderId)
        it.copy(sourceFolderIds = next)
    }

    fun generate(prefs: UserPreferences?, images: List<DriveImage>, styles: List<Outfit>) {
        doGenerate(prefs, images, styles)
    }

    private fun doGenerate(
        prefs: UserPreferences?,
        images: List<DriveImage>,
        styles: List<Outfit>,
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
                val result = weather.fetchDestinationForecast(
                    dest, days, startDate,
                    latitude = _state.value.destinationLatitude,
                    longitude = _state.value.destinationLongitude,
                )
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

            val s = _state.value
            val effectiveOutfitCount = (s.outfitCount ?: days).coerceIn(1, 21)
            val prompt = buildPackingPrompt(
                preamble        = PromptStore.get(getApplication(), PromptKey.PACKING),
                prefs           = prefs,
                destination     = resolvedDest,
                startDate       = startDate,
                days            = days,
                outfitCount     = effectiveOutfitCount,
                goal            = s.goal,
                vibes           = s.vibes,
                considerationsOverride = s.considerationsOverride,
                forecast        = forecast,
                isHistorical    = isHistorical,
                referenceYear   = refYear,
                images          = images,
                styles          = styles,
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
    outfitCount: Int,
    goal: String,
    vibes: Set<String>,
    considerationsOverride: AiConsiderations?,
    forecast: List<DayForecast>,
    isHistorical: Boolean,
    referenceYear: Int?,
    images: List<DriveImage>,
    styles: List<Outfit>,
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

    val c = considerationsOverride ?: prefs?.aiConsiderations ?: AiConsiderations()
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
        if (c.location) {
            appendLine("## Location context")
            appendLine("- Destination: $destination")
            appendLine("- Consider local style norms, dress codes, and urban/rural practicality at the destination.")
            appendLine()
        }
        if (c.trends) {
            appendLine("## Current fashion trends")
            appendLine("Reflect on what is currently fashionable at the destination based on your training data; favour timeless travel-friendly pieces over short-lived fads.")
            appendLine()
        }
        if (goal.isNotBlank()) {
            appendLine("## Trip goal / focus (user input)")
            appendLine(goal.trim())
            appendLine()
        }
        if (vibes.isNotEmpty()) {
            appendLine("## Style vibes")
            appendLine(vibes.joinToString(" / "))
            appendLine()
        }
        appendLine("## Outfit Plan")
        appendLine("- Compose exactly $outfitCount distinct outfit recommendations for this trip, ranked from most useful to least.")
        appendLine("- Reuse versatile items (jeans, trousers, jackets, shoes, accessories) across multiple outfits — they don't need to be changed daily.")
        appendLine("- Rotate items worn directly against the skin (t-shirts, blouses, shirts, socks, underwear) so the traveler has a fresh one per wear-day.")
        appendLine("- Make sure the overall pack covers all $days days of the trip even though outfits repeat.")
        appendLine("- Name each outfit by its purpose or occasion (e.g. \"City sightseeing\", \"Dinner out\"). Do NOT prefix the occasion with day numbers such as \"Day 1\" or \"Day 1 & 2\".")
        appendLine()
        appendLine("## Available Wardrobe Items (id + name + tags)")
        appendLine(wardrobeJson)
        appendLine()
        if (styles.isNotEmpty()) {
            appendLine("## Saved Styles (pre-built outfits)")
            appendLine(stylesJson)
            appendLine()
        }
        appendLine("Respond with ONLY a valid JSON object — no markdown, no extra text:")
        append("""{"outfits":[{"occasion":"<short occasion label, no day numbers>","itemIds":["<id1>","<id2>",...],"description":"<1-sentence style note>"},...],"extraItems":["<item1>","<item2>",...],"reason":"<1-2 sentence summary>"}""")
        appendLine()
        appendLine()
        appendLine("IMPORTANT: Write all user-facing text fields (occasion, description, reason, extraItems) in ${AppLanguage.toGeminiName(prefs?.language ?: AppLanguage.ENGLISH)}.")
    }
}
