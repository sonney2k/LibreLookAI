package com.librelookai.tryon

import com.librelookai.gemini.ClothingTags
import com.librelookai.wardrobe.DriveImage

/**
 * Which part(s) of the body a single garment covers — used to gate try-on generation so the
 * result is always fully clothed (no exposed torso or legs). See [tryOnFullyCovered].
 */
internal enum class BodyCoverage { UPPER, LOWER, FULL, OTHER }

/**
 * Classify a garment's body coverage from its tags. The free-text [ClothingTags.type] is the most
 * specific signal, so it is consulted first; the closed-vocabulary [ClothingTags.category] is the
 * fallback. Footwear / accessories / unrecognised items count as [BodyCoverage.OTHER] and never
 * contribute to coverage.
 */
internal fun ClothingTags?.bodyCoverage(): BodyCoverage {
    val type = this?.type?.lowercase()?.trim().orEmpty()

    val fullKw = listOf(
        "dress", "gown", "jumpsuit", "romper", "overall", "dungaree",
        "boilersuit", "catsuit", "bodysuit", "onesie", "playsuit",
    )
    val lowerKw = listOf(
        "jean", "chino", "trouser", "pant", "short", "skirt", "legging",
        "slack", "culotte", "cargo", "jogger", "sweatpant", "bermuda",
    )
    val upperKw = listOf(
        "shirt", "t-shirt", "tee", "top", "blouse", "sweater", "hoodie",
        "cardigan", "jacket", "blazer", "coat", "vest", "tank", "pullover",
        "jumper", "turtleneck", "sweatshirt", "poncho", "parka", "anorak",
        "gilet", "waistcoat",
    )

    fun matches(kw: List<String>) = kw.any { type.contains(it) }

    // Type keywords win when present (most specific).
    when {
        matches(fullKw)  -> return BodyCoverage.FULL
        matches(lowerKw) -> return BodyCoverage.LOWER
        matches(upperKw) -> return BodyCoverage.UPPER
    }

    // Fall back to the broad category vocabulary (tops/bottoms/outerwear/footwear/
    // accessories/dress/suit/jumpsuit — see the classify prompt in PromptStore).
    return when (this?.category?.lowercase()?.trim().orEmpty()) {
        "tops", "outerwear"     -> BodyCoverage.UPPER
        "bottoms"               -> BodyCoverage.LOWER
        "dress", "jumpsuit"     -> BodyCoverage.FULL
        // A "suit" as one item is a matching jacket + trousers set ⇒ full coverage.
        "suit"                  -> BodyCoverage.FULL
        else                    -> BodyCoverage.OTHER
    }
}

/**
 * True when the selected items cover both the upper and lower body — either a single full-body
 * piece (dress / jumpsuit / suit) or at least one upper garment plus one lower garment. This is
 * the precondition for generating a try-on; anything less risks an undressed result, so callers
 * must refuse.
 */
internal fun List<DriveImage>.tryOnFullyCovered(): Boolean {
    val coverage = map { it.tags.bodyCoverage() }
    if (coverage.any { it == BodyCoverage.FULL }) return true
    return coverage.contains(BodyCoverage.UPPER) && coverage.contains(BodyCoverage.LOWER)
}
