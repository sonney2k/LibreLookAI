package com.librelookai.data.model
import com.librelookai.settings.AiConsiderations
import java.util.UUID

/**
 * A planned trip. Aggregates the output of the Travel Planner:
 *   - N real [Outfit]s (referenced by [outfitIds], one per day),
 *   - the forecast and planner inputs used to generate them,
 *   - additional packing recommendations ([extraItems]).
 *
 * Persisted as `LibreLookAI/_trips/{id}.json`.
 */
data class Trip(
    val id: String = UUID.randomUUID().toString(),
    val name: String = "",
    val destination: String = "",
    /** ISO yyyy-MM-dd. */
    val startDate: String = "",
    val days: Int = 0,
    val forecast: List<DayForecast> = emptyList(),
    val isHistoricalForecast: Boolean = false,
    val historicalReferenceYear: Int? = null,
    val considerations: AiConsiderations = AiConsiderations(),
    val vibes: Set<String> = emptySet(),
    val goal: String = "",
    /** Outfit IDs in day order — `outfitIds[0]` is day 1. */
    val outfitIds: List<String> = emptyList(),
    val extraItems: List<String> = emptyList(),
    val reason: String = "",
    /** Packing checklist: wardrobe item Drive IDs the user has marked as packed. */
    val packedItemIds: Set<String> = emptySet(),
    /** Packing checklist: [extraItems] entries the user has marked as packed. */
    val packedExtras: Set<String> = emptySet(),
    val createdAt: Long = System.currentTimeMillis(),
)
