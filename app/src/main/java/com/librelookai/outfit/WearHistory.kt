package com.librelookai.outfit

import com.librelookai.data.local.OutfitEventStore
import com.librelookai.data.model.Outfit
import com.librelookai.data.model.OutfitEvent
import com.librelookai.data.model.WearSource
import com.librelookai.data.session.ClosetSession
import com.librelookai.data.session.ClosetSessionHolder
import com.librelookai.gemini.ClothingTags
import com.librelookai.wardrobe.DriveImage
import com.librelookai.weather.WeatherData
import java.time.LocalDate
import java.time.temporal.ChronoUnit
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.mapNotNull

// ============================================================================
//  Calendar wear history → taste signal.
//
//  The calendar logs which outfit the user wore on which day. That history is the
//  user's *revealed* taste, and is fed back into the outfit-suggestion prompt. To
//  keep the signal usable even after an outfit is edited/deleted, each [OutfitEvent]
//  carries a denormalised snapshot of the outfit's tags/colors/weather at wear time;
//  these helpers build that snapshot and condense the history into a compact prompt
//  block. See CLAUDE.md § "Calendar wear history".
// ============================================================================

/**
 * Live wear-history feed for the prompt builders (refactor § 5 slice 3): the calendar events
 * in the current closet scope, observed straight from the Room store. The scope follows the
 * closet session with the same arms [OutfitEventsViewModel] uses — All locations → every
 * closet, an active closet → that folder only, an unknown active id → keep observing the
 * previous scope. The events VM persists locally-first (§ 2), so the store is current at
 * prompt-build time; collecting this replaced the old events→styles state mirror in
 * AppContent and the planner UI's `OutfitsUiState.wearHistory` threading.
 */
@OptIn(ExperimentalCoroutinesApi::class)
internal fun wearHistoryFlow(
    session: ClosetSessionHolder,
    store: OutfitEventStore,
): Flow<List<OutfitEvent>> = session.session
    .mapNotNull { s ->
        val active = s.activeFolderId
        when {
            s.activeLocationId == ClosetSession.ALL_LOCATIONS_ID -> s.closetFolderIds
            active != null -> listOf(active)
            else -> null
        }
    }
    .distinctUntilChanged()
    .flatMapLatest(store::observeFolders)

/**
 * Builds an [OutfitEvent] with a tag/color/weather snapshot taken at wear time.
 * [source] weights the taste signal (manual = strong, AI-accepted = weak); [date]
 * defaults to today and flips [OutfitEvent.planned] on when it is in the future.
 */
internal fun buildOutfitEvent(
    outfit: Outfit,
    imagesById: Map<String, DriveImage>,
    source: WearSource = WearSource.MANUAL,
    weather: WeatherData? = null,
    date: LocalDate = LocalDate.now(),
): OutfitEvent {
    val tags: List<ClothingTags> = outfit.itemIds.mapNotNull { imagesById[it]?.tags }
    fun flat(sel: (ClothingTags) -> List<String>): List<String> =
        tags.flatMap(sel).map { it.trim() }.filter { it.isNotEmpty() }.distinct()
    return OutfitEvent(
        outfitId   = outfit.id,
        date       = date.toString(),
        outfitName = outfit.name,
        outfitTags = outfit.tags,
        itemTypes  = tags.mapNotNull { it.type.trim().takeIf { t -> t.isNotEmpty() } }.distinct(),
        colors     = flat { it.colors },
        aesthetics = flat { it.aesthetic },
        tempC      = weather?.temperatureCelsius?.toInt(),
        weatherCode = weather?.weatherCode,
        source     = source,
        planned    = date.isAfter(LocalDate.now()),
    )
}

/**
 * Condenses the wear-history calendar into a compact taste-signal block for the
 * outfit-suggestion prompt, or null when there is no usable history.
 *
 * Aggregates are wear-weighted so the summary leans toward what the user genuinely
 * reaches for: a manual wear counts double an AI-accepted one, and a "loved" wear gets
 * a bonus. Recently worn outfits are surfaced separately so the model can avoid
 * repeating them, and cold-weather wears are called out so suggestions stay practical.
 */
internal fun buildWearHistorySummary(
    events: List<OutfitEvent>,
    today: LocalDate = LocalDate.now(),
    recentDays: Long = 7,
    maxOutfits: Int = 5,
    maxItems: Int = 8,
): String? {
    // Only actually-worn, non-future events carry taste signal.
    val worn = events.filter { !it.planned }
    if (worn.isEmpty()) return null

    fun weight(e: OutfitEvent): Int =
        (if (e.source == WearSource.MANUAL) 2 else 1) + (if (e.loved) 3 else 0)

    fun <T> weightedTop(n: Int, sel: (OutfitEvent) -> Iterable<T>): List<T> {
        val scores = LinkedHashMap<T, Int>()
        worn.forEach { e ->
            val w = weight(e)
            sel(e).forEach { scores[it] = (scores[it] ?: 0) + w }
        }
        return scores.entries.sortedByDescending { it.value }.take(n).map { it.key }
    }

    val parsedDates = worn.mapNotNull { runCatching { LocalDate.parse(it.date) }.getOrNull() }
    val topOutfits = weightedTop(maxOutfits) { listOfNotNull(it.outfitName.takeIf(String::isNotBlank)) }
    val topTypes = weightedTop(maxItems) { it.itemTypes }
    val topColors = weightedTop(6) { it.colors }
    val topAesthetics = weightedTop(5) { it.aesthetics }
    val topTags = weightedTop(6) { it.outfitTags }
    val loved = worn.filter { it.loved }
        .mapNotNull { it.outfitName.takeIf(String::isNotBlank) }.distinct().take(5)
    val recent = worn.mapNotNull { e ->
        val d = runCatching { LocalDate.parse(e.date) }.getOrNull() ?: return@mapNotNull null
        if (ChronoUnit.DAYS.between(d, today) in 0 until recentDays)
            e.outfitName.takeIf(String::isNotBlank) else null
    }.distinct().take(8)
    // Cold-weather lean: item types most common below 13°C.
    val coldTypes = worn.filter { (it.tempC ?: Int.MAX_VALUE) < 13 }
        .flatMap { it.itemTypes }
        .groupingBy { it }.eachCount()
        .entries.sortedByDescending { it.value }.take(5).map { it.key }

    return buildString {
        appendLine("## Wear history (the user's revealed taste — weight this heavily)")
        append("Derived from ${worn.size} logged wear(s)")
        parsedDates.minOrNull()?.let { append(" since $it") }
        appendLine(". Favour outfits, items, colours and vibes consistent with what the user actually wears and has marked as loved.")
        if (topOutfits.isNotEmpty()) appendLine("- Most-worn outfits: ${topOutfits.joinToString(", ")}")
        if (loved.isNotEmpty()) appendLine("- Explicitly loved: ${loved.joinToString(", ")}")
        if (topTypes.isNotEmpty()) appendLine("- Favourite item types: ${topTypes.joinToString(", ")}")
        if (topColors.isNotEmpty()) appendLine("- Favourite colours: ${topColors.joinToString(", ")}")
        if (topAesthetics.isNotEmpty()) appendLine("- Favourite aesthetics: ${topAesthetics.joinToString(", ")}")
        if (topTags.isNotEmpty()) appendLine("- Common occasions/vibes: ${topTags.joinToString(", ")}")
        if (coldTypes.isNotEmpty()) appendLine("- In cold weather tends to wear: ${coldTypes.joinToString(", ")}")
        if (recent.isNotEmpty())
            appendLine("- Worn in the last $recentDays days (avoid repeating unless it is the clear best pick): ${recent.joinToString(", ")}")
    }.trimEnd()
}
