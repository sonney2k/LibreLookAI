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

fun String.normalizeColor(): String = when (val c = lowercase().trim()) {
    // British ↔ American spelling
    "grey"                              -> "gray"
    // Dark grey variants → charcoal
    "dark gray", "dark grey",
    "dark-gray", "dark-grey"            -> "charcoal"
    // Navy variants
    "navy blue", "dark blue",
    "midnight blue", "dark navy"        -> "navy"
    // Sky / light blue
    "sky blue", "light blue",
    "baby blue", "powder blue"          -> "sky"
    // Forest green variants
    "forest green", "dark green",
    "hunter green"                      -> "forest"
    // Off-white / cream variants
    "off-white", "off white",
    "eggshell"                          -> "cream"
    // Multicolor variants
    "multi-color", "multi color",
    "multicolour", "multi-colour",
    "colorful", "colourful",
    "multicolored", "multicoloured"     -> "multicolor"
    // Denim blue as its own token
    "denim blue"                        -> "denim blue"
    else                                -> c
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
