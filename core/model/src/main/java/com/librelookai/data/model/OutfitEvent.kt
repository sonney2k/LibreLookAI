package com.librelookai.data.model
import com.google.gson.annotations.SerializedName
import java.util.UUID

/** How a worn outfit was chosen — used to weight the taste signal fed to outfit suggestions. */
enum class WearSource {
    /** The user actively picked an existing/own outfit to wear — strong taste signal. */
    MANUAL,
    /** The user accepted an AI-suggested outfit — weaker taste signal. */
    AI_SUGGESTED,
}

data class OutfitEvent(
    val id: String = UUID.randomUUID().toString(),
    /** Id of the saved outfit worn on this date. JSON name kept as "styleId" for backward compat. */
    @SerializedName(value = "outfitId", alternate = ["styleId"])
    val outfitId: String = "",
    /** ISO local date, e.g. "2026-04-05". */
    val date: String = "",
    val createdAt: Long = System.currentTimeMillis(),

    // ── Taste-signal snapshot ──────────────────────────────────────────────────
    // Captured at wear time so a wear stays a usable taste signal even after the
    // referenced outfit is renamed, edited, or deleted. All optional: events written
    // before these fields existed deserialize with the defaults below (every field has
    // a default, so Gson uses the generated no-arg constructor and back-fills defaults).
    /** Outfit name when worn. */
    val outfitName: String = "",
    /** Free-form outfit tags (occasion / vibe) when worn. */
    val outfitTags: List<String> = emptyList(),
    /** Item type labels across the outfit's pieces (e.g. "t-shirt", "jeans"). */
    val itemTypes: List<String> = emptyList(),
    /** Canonical colors across the outfit's pieces. */
    val colors: List<String> = emptyList(),
    /** Aesthetic tags across the outfit's pieces. */
    val aesthetics: List<String> = emptyList(),

    /** Explicit positive feedback — the user marked this wear as "loved it". */
    val loved: Boolean = false,
    /** Air temperature (°C) when worn; null if weather was unknown. */
    val tempC: Int? = null,
    /** WMO weather code when worn; null if unknown. */
    val weatherCode: Int? = null,
    /** How the outfit was chosen. */
    val source: WearSource = WearSource.MANUAL,
    /** True when scheduled for a future date rather than actually worn. Excluded from taste stats. */
    val planned: Boolean = false,
)
