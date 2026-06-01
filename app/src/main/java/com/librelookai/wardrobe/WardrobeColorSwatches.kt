package com.librelookai.wardrobe

import androidx.compose.ui.graphics.Color
import com.librelookai.gemini.CANONICAL_COLORS

/**
 * Display order of the color swatches in the wardrobe filter and the Edit-Tags color picker.
 * Must contain exactly [CANONICAL_COLORS] (the normalizer's closed set) — `TagNormalizerTest`
 * asserts they stay in sync.
 */
internal val FilterColorKeys: List<String> = listOf(
    "black", "charcoal", "gray", "silver", "white", "cream",
    "beige", "tan", "camel", "khaki", "brown", "rust",
    "orange", "peach", "coral", "red", "burgundy", "pink", "magenta",
    "purple", "lavender", "lilac",
    "blue", "navy", "sky", "denim blue", "teal",
    "mint", "green", "olive", "forest",
    "yellow", "mustard", "gold",
    "multicolor",
)

private val FilterColorKeySet: Set<String> = FilterColorKeys.toSet()

/**
 * Orders color keys by their canonical swatch position ([FilterColorKeys]) — the perceptual
 * neutrals→warm→cool grouping shared by the filter and the Edit-Tags picker. Unknowns sort last.
 */
internal fun Iterable<String>.sortedByColorOrder(): List<String> =
    sortedBy { FilterColorKeys.indexOf(it).let { i -> if (i < 0) Int.MAX_VALUE else i } }

/** Returns the wardrobe-filter swatch for [name], or null if the name is not a known color. */
internal fun colorSwatchOrNull(name: String): Color? {
    val n = name.lowercase().trim()
    return if (n in FilterColorKeySet) colorSwatchHex(n) else null
}

internal fun colorSwatchHex(name: String): Color = when (name.lowercase().trim()) {
    // Neutrals
    "black"      -> Color(0xFF1A1A1A)
    "charcoal"   -> Color(0xFF3A3A3A)
    "gray"       -> Color(0xFF808080)
    "silver"     -> Color(0xFFC0C0C0)
    "white"      -> Color(0xFFF5F5F0)
    "cream"      -> Color(0xFFF1E9D6)
    // Browns / earth
    "beige"      -> Color(0xFFE8DCCB)
    "tan"        -> Color(0xFFB89968)
    "camel"      -> Color(0xFFB87E45)
    "khaki"      -> Color(0xFFA89968)
    "brown"      -> Color(0xFF7A5030)
    "rust"       -> Color(0xFFA34A28)
    // Warm
    "orange"     -> Color(0xFFD07030)
    "peach"      -> Color(0xFFF5B891)
    "coral"      -> Color(0xFFE5806A)
    "red"        -> Color(0xFFB83030)
    "burgundy"   -> Color(0xFF6A1B2A)
    "pink"       -> Color(0xFFD48090)
    "magenta"    -> Color(0xFFB02A7A)
    // Purples
    "purple"     -> Color(0xFF7060A0)
    "lavender"   -> Color(0xFFB7A8D6)
    "lilac"      -> Color(0xFFC8A8D8)
    // Blues
    "blue"       -> Color(0xFF3050A0)
    "navy"       -> Color(0xFF1E2E4A)
    "sky"        -> Color(0xFF8FB8E0)
    "denim blue" -> Color(0xFF4A6B8A)
    "teal"       -> Color(0xFF2E8A8A)
    // Greens
    "mint"       -> Color(0xFFA8D8C0)
    "green"      -> Color(0xFF4A7040)
    "olive"      -> Color(0xFF5A6030)
    "forest"     -> Color(0xFF2E4A2E)
    // Yellows / metallics
    "yellow"     -> Color(0xFFC8B030)
    "mustard"    -> Color(0xFFC59A2E)
    "gold"       -> Color(0xFFC9A227)
    // Special
    "multicolor" -> Color(0xFFB0B0B0)
    else         -> Color(0xFFB0B0B0)
}
