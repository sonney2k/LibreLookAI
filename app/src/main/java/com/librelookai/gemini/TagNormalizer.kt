package com.librelookai.gemini
/**
 * Normalizes a [ClothingTags] object to canonical forms so that:
 * - Spelling variants ("grey" / "gray") collapse to one canonical value
 * - Structural variants ("long-sleeved t-shirt" / "long-sleeve t-shirt") collapse
 * - Enum-field drift ("business casual", "striped") is corrected
 * - All values are trimmed and lowercased where appropriate
 *
 * Applied at Gemini parse time (new items) and at filter-time in the UI (existing items).
 */
fun ClothingTags.normalize(): ClothingTags = copy(
    type        = type.normalizeType(),
    category    = category.lowercase().trim(),
    colors      = colors.map { it.normalizeColor() }.distinct(),
    uses        = uses.map { it.normalizeEnumTag() }.distinct(),
    seasonality = seasonality.map { it.normalizeEnumTag() }.distinct(),
    aesthetic   = aesthetic.map { it.normalizeAesthetic() }.distinct(),
    fit         = fit.map { it.normalizeEnumTag() }.distinct(),
    material    = material.map { it.normalizeMaterial() }.distinct(),
    pattern     = pattern.map { it.normalizePattern() }.distinct(),
)

// ─── Color ──────────────────────────────────────────────────────────────────

/**
 * The **closed** set of colors the app supports. Every stored color tag is snapped to one of
 * these by [normalizeColor], so each value always has a real swatch ([colorSwatchHex]) and a
 * single filter bucket. Order/labels for display live in `wardrobe/WardrobeColorSwatches.kt`
 * ([com.librelookai.wardrobe.FilterColorKeys]); a test asserts the two stay in sync.
 */
val CANONICAL_COLORS: Set<String> = setOf(
    "black", "charcoal", "gray", "silver", "white", "cream",
    "beige", "tan", "camel", "khaki", "brown", "rust",
    "orange", "peach", "coral", "red", "burgundy", "pink", "magenta",
    "purple", "lavender", "lilac",
    "blue", "navy", "sky", "denim blue", "teal",
    "mint", "green", "olive", "forest",
    "yellow", "mustard", "gold",
    "multicolor",
)

/**
 * Snaps any color string to a [CANONICAL_COLORS] value. Known synonyms map to their canonical
 * sibling; anything still unrecognized falls back to "multicolor" so no swatch-less color is ever
 * stored. Applied to Gemini output, manual "add custom" input, and at filter/display time.
 */
fun String.normalizeColor(): String {
    val mapped = when (val c = lowercase().trim()) {
        // ── Neutrals ──
        "grey"                                              -> "gray"
        "light gray", "light grey", "slate", "slate gray",
        "slate grey", "stone", "ash", "smoke"               -> "gray"
        "dark gray", "dark grey", "dark-gray", "dark-grey"  -> "charcoal"
        "jet black", "off-black", "off black"               -> "black"
        "snow", "pure white", "bright white"                -> "white"
        "off-white", "off white", "eggshell", "ivory",
        "bone", "oatmeal"                                   -> "cream"
        // ── Browns / earth ──
        "chocolate", "coffee", "espresso", "mocha",
        "walnut"                                            -> "brown"
        "taupe", "mushroom"                                 -> "tan"
        "sand", "nude"                                      -> "beige"
        "copper", "terracotta", "terra cotta"               -> "rust"
        "bronze"                                            -> "camel"
        // ── Warm ──
        "burnt orange", "amber"                             -> "orange"
        "apricot"                                           -> "peach"
        "salmon"                                            -> "coral"
        "scarlet", "crimson", "cherry"                      -> "red"
        "maroon", "wine", "oxblood", "merlot", "brick"      -> "burgundy"
        "hot pink", "fuchsia"                               -> "magenta"
        "blush", "rose", "pale pink", "baby pink"           -> "pink"
        // ── Purples ──
        "violet", "plum", "eggplant", "aubergine", "grape",
        "indigo"                                            -> "purple"
        "mauve"                                             -> "lilac"
        "periwinkle"                                        -> "lavender"
        // ── Blues ──
        "navy blue", "dark blue", "midnight blue",
        "dark navy"                                         -> "navy"
        "sky blue", "light blue", "baby blue",
        "powder blue"                                       -> "sky"
        "cobalt", "royal blue", "azure", "cerulean",
        "steel blue", "electric blue"                       -> "blue"
        "cyan", "aqua", "aquamarine", "turquoise",
        "teal blue"                                         -> "teal"
        "denim"                                             -> "denim blue"
        // ── Greens ──
        "forest green", "dark green", "hunter green",
        "pine", "bottle green"                              -> "forest"
        "lime", "lime green", "chartreuse", "neon green",
        "emerald", "kelly green"                            -> "green"
        "army green", "military green", "moss",
        "olive green"                                       -> "olive"
        "seafoam", "sage", "mint green"                     -> "mint"
        // ── Yellows / metallics ──
        "mustard yellow"                                    -> "mustard"
        "lemon", "canary"                                   -> "yellow"
        "golden", "metallic gold"                           -> "gold"
        // ── Multicolor variants ──
        "multi-color", "multi color", "multicolour",
        "multi-colour", "colorful", "colourful",
        "multicolored", "multicoloured", "rainbow",
        "patterned", "print"                               -> "multicolor"
        else                                                -> c
    }
    return if (mapped in CANONICAL_COLORS) mapped else "multicolor"
}

// ─── Type ───────────────────────────────────────────────────────────────────

fun String.normalizeType(): String {
    if (isBlank()) return trim()
    val s = trim()
        .replace(Regex("\\s+"), " ")
        .lowercase()
        // "sleeved" → "sleeve" (remove the -d suffix in compound modifiers)
        .replace("long-sleeved", "long-sleeve")
        .replace("long sleeved", "long-sleeve")
        .replace("short-sleeved", "short-sleeve")
        .replace("short sleeved", "short-sleeve")
        // add missing hyphens
        .replace("long sleeve", "long-sleeve")
        .replace("short sleeve", "short-sleeve")
        // t-shirt variants
        .replace(Regex("\\bt\\s*shirt\\b"), "t-shirt")
        .replace(Regex("\\btshirt\\b"), "t-shirt")
    // Title-case each space-separated word (hyphens preserved)
    return s.split(" ").joinToString(" ") { word ->
        word.replaceFirstChar { it.uppercase() }
    }
}

// ─── Aesthetic ──────────────────────────────────────────────────────────────

fun String.normalizeAesthetic(): String = when (val a = lowercase().trim()) {
    "business casual", "business_casual" -> "business-casual"
    else                                 -> a
}

// ─── Pattern ────────────────────────────────────────────────────────────────

fun String.normalizePattern(): String = when (val p = lowercase().trim()) {
    "stripe", "striped"                          -> "stripes"
    "check", "checked", "checkered", "tartan"    -> "plaid"
    "animal print", "animal print pattern"       -> "animal-print"
    "camouflage", "camouflage print"             -> "camo"
    else                                         -> p
}

// ─── Material ───────────────────────────────────────────────────────────────

fun String.normalizeMaterial(): String = when (val m = lowercase().trim()) {
    "denim fabric"         -> "denim"
    "fleece", "knit wear",
    "knitwear", "knitwork" -> "knit"
    "faux leather",
    "vegan leather"        -> "leather"
    "nylon", "synthetic",
    "synthetic fabric"     -> "polyester"
    else                   -> m
}

// ─── Generic enum-field ─────────────────────────────────────────────────────

/** Lowercase + trim for fields whose Gemini values are already well-constrained. */
fun String.normalizeEnumTag(): String = lowercase().trim()
