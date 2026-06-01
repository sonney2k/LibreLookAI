package com.librelookai.outfit

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Unit tests for [parseComposerVariants] — the pure JSON parsing for AI outfit composition.
 *
 * Regression guard: Gson builds the response objects via unsafe allocation (no Kotlin
 * constructor, no default values), so a response missing `slots` / `itemIds` / etc. used to
 * yield nulls in non-null fields and crash with an NPE. These cases must now degrade to an
 * empty list (→ "Could not parse Gemini response.") instead.
 */
class ParseComposerVariantsTest {

    @Test
    fun multiSuggestionSchema_parsesAllVariants() {
        val json = """
            {"suggestions":[
              {"slots":[{"slotId":"s1","itemId":"a"}],"name":"Look A","tags":["casual"]},
              {"slots":[{"slotId":"s2","itemId":"b"}],"name":"Look B"}
            ]}
        """.trimIndent()
        val variants = parseComposerVariants(json)
        assertEquals(2, variants.size)
        assertEquals("Look A", variants[0].name)
        assertEquals("a", variants[0].slots?.first()?.itemId)
        assertEquals(listOf("casual"), variants[0].tags)
    }

    @Test
    fun singleSuggestionSchema_parsesTopLevelSlots() {
        val json = """{"slots":[{"slotId":"s1","itemId":"a"}],"name":"Solo"}"""
        val variants = parseComposerVariants(json)
        assertEquals(1, variants.size)
        assertEquals("Solo", variants[0].name)
        assertEquals("s1", variants[0].slots?.first()?.slotId)
    }

    @Test
    fun legacyFlatItemIds_parsed() {
        val json = """{"itemIds":["a","b"],"name":"Legacy"}"""
        val variants = parseComposerVariants(json)
        assertEquals(1, variants.size)
        assertEquals(listOf("a", "b"), variants[0].itemIds)
    }

    @Test
    fun fencedJson_isUnwrapped() {
        val json = "```json\n{\"slots\":[{\"slotId\":\"s1\",\"itemId\":\"a\"}]}\n```"
        val variants = parseComposerVariants(json)
        assertEquals(1, variants.size)
        assertEquals("a", variants[0].slots?.first()?.itemId)
    }

    // ─── Regression: malformed / short responses must not crash ─────────────────

    @Test
    fun responseWithoutSlotsOrItemIds_returnsEmpty() {
        // The shape that crashed: no `suggestions`, no top-level `slots`, no `itemIds`.
        val json = """{"name":"No items","reason":"I can't help with that."}"""
        assertTrue(parseComposerVariants(json).isEmpty())
    }

    @Test
    fun explicitNullSlots_returnsEmpty() {
        val json = """{"slots":null,"name":"x","itemIds":null}"""
        assertTrue(parseComposerVariants(json).isEmpty())
    }

    @Test
    fun emptySuggestionsArray_returnsEmpty() {
        assertTrue(parseComposerVariants("""{"suggestions":[]}""").isEmpty())
    }

    @Test
    fun suggestionsWithEmptyAssignments_areFilteredOut() {
        val json = """{"suggestions":[{"name":"empty","slots":[]},{"name":"alsoEmpty"}]}"""
        assertTrue(parseComposerVariants(json).isEmpty())
    }

    @Test
    fun blankOrGarbageInput_returnsEmpty() {
        assertTrue(parseComposerVariants("").isEmpty())
        assertTrue(parseComposerVariants("not json at all").isEmpty())
        assertTrue(parseComposerVariants("```json\n```").isEmpty())
    }
}
