package com.librelookai.data.model
import java.util.UUID

data class Outfit(
    val id: String = UUID.randomUUID().toString(),
    val name: String = "",
    val description: String = "",
    /** Runtime Drive file IDs for the current session. Populated from [itemNames] on load. */
    val itemIds: List<String> = emptyList(),
    /**
     * Stable filenames stored in the JSON. Filenames survive copying a Drive folder to a new
     * account (unlike Drive file IDs which change on copy). On load, these are resolved back
     * to current Drive IDs via the folder file listing.
     */
    val itemNames: List<String> = emptyList(),
    /**
     * Free-form outfit tags, e.g. "birthday", "travel", "work". Often suggested by Gemini
     * when an outfit is composed/predicted, editable by the user.
     */
    val tags: List<String> = emptyList(),
    /** Optional back-reference to a [Trip]. Persisted. Null for non-trip outfits. */
    val tripId: String? = null,
    /**
     * Outfit-level "favourite" flag, toggled by the heart on the outfit list/detail views. This is
     * the outfit's own favourite state (settable before it is ever worn); it is distinct from the
     * per-wear [OutfitEvent.loved] feedback shown on the calendar/wear-stats, which is keyed to a
     * specific logged wear. Persisted; Gson back-fills `false` for older outfits.
     */
    val loved: Boolean = false,
    val createdAt: Long = System.currentTimeMillis(),
    /** Runtime Drive folder ID this style was loaded from. Not persisted to JSON. */
    @Transient val folderId: String = "",
)

/**
 * Transient bulk-refine proposal for a single outfit (not persisted): the new item set plus a
 * freshly generated name/description. Used as the trip refine preview before the user applies it.
 */
data class OutfitRefinement(
    val itemIds: List<String> = emptyList(),
    val name: String = "",
    val description: String = "",
)
