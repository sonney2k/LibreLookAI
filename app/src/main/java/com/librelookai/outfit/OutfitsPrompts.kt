package com.librelookai.outfit

import com.librelookai.data.model.DayForecast
import com.librelookai.data.model.Location
import com.librelookai.data.model.Outfit
import com.librelookai.gemini.FashionTrends
import com.librelookai.settings.AiConsiderations
import com.librelookai.settings.AppLanguage
import com.librelookai.settings.UserPreferences
import com.librelookai.wardrobe.DriveImage
import com.librelookai.wardrobe.toPromptJson
import com.librelookai.weather.WeatherData
import com.librelookai.weather.wmoEmoji
import java.time.LocalDate

// ---------- Prompt builder ----------

internal fun buildPredictionPrompt(
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
        .joinToString(",", "[", "]") { it.toPromptJson(c) }

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
        appendLine("## Wardrobe Items (id + name + tags)")
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

internal fun buildComposerPrompt(
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
    tripContext: TripContext? = null,
): String {
    val c = considerationsOverride ?: prefs?.aiConsiderations ?: AiConsiderations()
    val age = prefs?.yearOfBirth?.let { LocalDate.now().year - it }

    // Slot-category prefilter: only send items that could fill one of the requested slots, so a
    // 3-slot outfit doesn't ship the entire wardrobe (see [wardrobeForSlots]).
    val wardrobeJson = wardrobeForSlots(images, slots).joinToString(",", "[", "]") { it.toPromptJson(c) }

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
        if (tripContext != null) {
            appendLine("## Trip context")
            appendLine("- This outfit is day ${tripContext.dayIndex + 1} of a multi-day trip named \"${tripContext.tripName}\".")
            if (tripContext.goal.isNotBlank()) appendLine("- Trip goal/notes: ${tripContext.goal}")
            if (tripContext.vibes.isNotEmpty()) appendLine("- Trip vibes: ${tripContext.vibes.joinToString(", ")}")
            tripContext.dayForecast?.let { f ->
                appendLine("- Forecast for this day: ${f.minTempC.toInt()}–${f.maxTempC.toInt()}°C, WMO ${f.weatherCode} ${wmoEmoji(f.weatherCode)}")
            }
            appendLine("- Coordinate with the rest of the trip: avoid duplicating items the user wears on other days when alternatives exist.")
            appendLine()
        }
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
        appendLine("A OnePiece (dress, gown, jumpsuit, romper, suit) covers BOTH the Top and the Bottom — it is mutually exclusive with the Top and Bottom slots:")
        appendLine("- If you fill a OnePiece slot, leave any Top and Bottom slots empty.")
        appendLine("- If you fill a Top or Bottom slot, leave any OnePiece slot empty.")
        appendLine("- Never combine a one-piece with a separate top or bottom in the same outfit.")
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
            appendLine("Each variant must fill EVERY non-locked slot (except slots left empty by the one-piece rule above) and must respect every locked slot's requiredItemId.")
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

// ---------- TripContext helpers ----------

/**
 * Maps a WMO weather code (used by Open-Meteo / our [DayForecast]) to one of the composer's
 * three precipitation buckets: "None", "Light", or "Heavy".
 *
 * Why: Codes 0–48 are clear/cloudy/fog, 51–67 are drizzle/rain, 71–77 snow showers,
 * 80–82 rain showers, 95–99 thunderstorms. The "Heavy" tier captures the high-end variants
 * Gemini should plan layers/rain gear for.
 */
internal fun precipForWmoCode(code: Int): String = when (code) {
    in 0..48 -> "None"
    63, 65, 67, 75, 77, 82, 95, 96, 99 -> "Heavy"
    else -> "Light"
}

/**
 * Derives the composer's `season` value ("Spring" / "Summer" / "Fall" / "Winter") from the
 * trip's ISO start date plus [dayIndex] offset. Northern-hemisphere bias — for the southern
 * hemisphere the user can still adjust manually in the composer.
 */
internal fun deriveSeasonFromIsoDate(isoDate: String, dayIndex: Int): String {
    val date = runCatching {
        LocalDate.parse(isoDate).plusDays(dayIndex.toLong())
    }.getOrNull() ?: return ""
    return when (date.monthValue) {
        12, 1, 2  -> "Winter"
        3, 4, 5   -> "Spring"
        6, 7, 8   -> "Summer"
        else      -> "Fall"
    }
}
