package com.librelookai.gemini

import org.junit.Assert.assertEquals
import org.junit.Test

/** Unit tests for the pure-Kotlin tag normalization in TagNormalizer.kt. */
class TagNormalizerTest {

    // ─── Color ────────────────────────────────────────────────────────────────

    @Test
    fun normalizeColor_collapsesBritishSpelling() {
        assertEquals("gray", "grey".normalizeColor())
        assertEquals("gray", " GREY ".normalizeColor())
    }

    @Test
    fun normalizeColor_mapsVariantsToCanonical() {
        assertEquals("charcoal", "dark grey".normalizeColor())
        assertEquals("charcoal", "dark-gray".normalizeColor())
        assertEquals("navy", "midnight blue".normalizeColor())
        assertEquals("sky", "baby blue".normalizeColor())
        assertEquals("forest", "hunter green".normalizeColor())
        assertEquals("cream", "off-white".normalizeColor())
    }

    @Test
    fun normalizeColor_collapsesMulticolorVariants() {
        listOf("multi-color", "multi color", "colourful", "multicoloured").forEach {
            assertEquals("multicolor", it.normalizeColor())
        }
    }

    @Test
    fun normalizeColor_keepsCanonicalLowercasedTrimmed() {
        assertEquals("teal", "  Teal ".normalizeColor())
        assertEquals("denim blue", "denim blue".normalizeColor())
    }

    @Test
    fun normalizeColor_snapsCommonStraysToCanonical() {
        assertEquals("teal", "cyan".normalizeColor())
        assertEquals("teal", "Turquoise".normalizeColor())
        assertEquals("green", "lime".normalizeColor())
        assertEquals("burgundy", "maroon".normalizeColor())
        assertEquals("magenta", "fuchsia".normalizeColor())
    }

    @Test
    fun normalizeColor_clampsAnyUnknownToMulticolor() {
        // Nothing swatch-less can ever be stored: unrecognized → "multicolor".
        assertEquals("multicolor", "ultraviolet glow".normalizeColor())
        assertEquals("multicolor", "zzz".normalizeColor())
    }

    @Test
    fun colorVocabularyStaysInSyncWithDisplayList() {
        // FilterColorKeys (display order) must hold exactly the normalizer's closed set.
        assertEquals(CANONICAL_COLORS, com.librelookai.wardrobe.FilterColorKeys.toSet())
    }

    // ─── Type ─────────────────────────────────────────────────────────────────

    @Test
    fun normalizeType_canonicalizesSleeveAndTShirt() {
        // Only the first char of each space-separated word is upper-cased; hyphen
        // segments stay lowercase ("Long-sleeve", not "Long-Sleeve").
        assertEquals("Long-sleeve T-shirt", "long-sleeved t-shirt".normalizeType())
        assertEquals("Long-sleeve T-shirt", "long sleeve tshirt".normalizeType())
        assertEquals("Short-sleeve Shirt", "short sleeved   shirt".normalizeType())
    }

    @Test
    fun normalizeType_titleCasesAndCollapsesWhitespace() {
        assertEquals("Denim Jacket", "  denim    jacket  ".normalizeType())
    }

    @Test
    fun normalizeType_blankStaysBlank() {
        assertEquals("", "   ".normalizeType())
    }

    // ─── Aesthetic / Pattern / Material ─────────────────────────────────────────

    @Test
    fun normalizeAesthetic_canonicalizesBusinessCasual() {
        assertEquals("business-casual", "Business Casual".normalizeAesthetic())
        assertEquals("business-casual", "business_casual".normalizeAesthetic())
        assertEquals("streetwear", "Streetwear".normalizeAesthetic())
    }

    @Test
    fun normalizePattern_canonicalizesVariants() {
        assertEquals("stripes", "striped".normalizePattern())
        assertEquals("plaid", "tartan".normalizePattern())
        assertEquals("animal-print", "animal print".normalizePattern())
        assertEquals("camo", "camouflage".normalizePattern())
    }

    @Test
    fun normalizeMaterial_canonicalizesVariants() {
        assertEquals("denim", "denim fabric".normalizeMaterial())
        assertEquals("knit", "knitwear".normalizeMaterial())
        assertEquals("leather", "vegan leather".normalizeMaterial())
        assertEquals("polyester", "synthetic".normalizeMaterial())
    }

    @Test
    fun normalizeEnumTag_lowercasesAndTrims() {
        assertEquals("summer", "  Summer ".normalizeEnumTag())
    }

    // ─── Whole-object normalize ─────────────────────────────────────────────────

    @Test
    fun normalize_appliesPerFieldRulesAndDedupes() {
        val tags = ClothingTags(
            label = "My Shirt",
            type = "long sleeved tshirt",
            category = "  TOPS ",
            colors = listOf("grey", "GREY", "navy blue"),
            uses = listOf("Work", "work"),
            seasonality = listOf("Summer"),
            aesthetic = listOf("Business Casual"),
            pattern = listOf("striped", "stripe"),
            material = listOf("knitwear", "fleece"),
        )

        val n = tags.normalize()

        assertEquals("Long-sleeve T-shirt", n.type)
        assertEquals("tops", n.category)
        // "grey"/"GREY" both collapse to "gray", then distinct() dedupes.
        assertEquals(listOf("gray", "navy"), n.colors)
        assertEquals(listOf("work"), n.uses)
        assertEquals(listOf("business-casual"), n.aesthetic)
        // "striped" and "stripe" both map to "stripes" → single value.
        assertEquals(listOf("stripes"), n.pattern)
        // "knitwear" and "fleece" both map to "knit" → single value.
        assertEquals(listOf("knit"), n.material)
        // label is not normalized.
        assertEquals("My Shirt", n.label)
    }
}
