package com.librelookai.settings

/**
 * Toggles controlling which context blocks the outfit-suggestion prompts include.
 * Lives in `:core:model` (referenced by `data/model/Trip.kt`); kept in the
 * `com.librelookai.settings` package so existing import sites stay untouched.
 */
data class AiConsiderations(
    val weather: Boolean = true,
    val location: Boolean = true,
    val trends: Boolean = true,
    val gender: Boolean = true,
    val age: Boolean = true,
    val preferences: Boolean = true,
    /** Feed the calendar wear history into the suggestion prompt as a taste signal. */
    val history: Boolean = true,
    /**
     * Expert option: which item tag dimensions to feed into the wardrobe JSON for outfit creation
     * and gap analysis. `null` means "include every dimension" — this is the back-compat default,
     * since Gson leaves the field absent (→ null) for preferences saved before this option existed.
     * An explicit (possibly empty) set means "include exactly these dimensions". `type`, `category`
     * and the item `name` are always sent regardless. See [TOGGLEABLE_TAGS].
     */
    val itemTags: Set<String>? = null,
) {
    /** Whether the given tag dimension (one of [TOGGLEABLE_TAGS]) should be sent to the model. */
    fun includesItemTag(dim: String): Boolean = itemTags?.contains(dim) ?: true

    /** Flip one tag dimension on/off, materialising the implicit "all" set on first toggle. */
    fun toggleItemTag(dim: String): AiConsiderations {
        val current = itemTags ?: TOGGLEABLE_TAGS.toSet()
        return copy(itemTags = if (dim in current) current - dim else current + dim)
    }

    companion object {
        /**
         * User-toggleable item tag dimensions, in display order. `type`/`category`/`name` are the
         * always-on essentials and are deliberately excluded here.
         */
        val TOGGLEABLE_TAGS = listOf(
            "uses", "colors", "seasonality", "aesthetic", "fit", "material", "pattern",
        )
    }
}
