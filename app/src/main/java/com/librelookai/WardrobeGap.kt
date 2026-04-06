package com.librelookai

data class GapSuggestion(
    /** Human-readable name of the missing item, e.g. "Black Oxford Shoes". */
    val missingItem: String = "",
    /** Broad clothing category, e.g. "Footwear", "Tops", "Outerwear". */
    val category: String = "",
    /** Recommended colours for the item, e.g. ["black", "navy"]. */
    val colors: List<String> = emptyList(),
    /**
     * Contextual explanation phrased as a user-facing sentence, e.g.
     * "You have 4 formal shirts and 3 blazers but no formal footwear."
     */
    val reason: String = "",
    /** Estimated number of new outfit combinations this item would unlock. */
    val outfitCount: Int = 0,
)

data class GapAnalysis(
    val suggestions: List<GapSuggestion> = emptyList(),
    /** One-to-two sentence overall summary of the wardrobe gaps. */
    val summary: String = "",
)
