package com.librelookai.outfit

import com.librelookai.data.model.Outfit
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Unit tests for the pure decision logic [OutfitsRepository] runs on each load/save (refactor
 * § 5 slice 8). The repo itself can't be constructed in a plain unit test (its `DriveRepository`
 * dep is concrete + heavy — fake-based tests wait on the § 3 interface extraction), so the
 * correctness-critical bits are factored into pure functions covered here:
 *  - [resolveOutfits]    — the extension-agnostic `itemNames` → `itemIds` resolution that survives
 *                          the WebP rename and cross-closet item moves.
 *  - [foldersToWrite] / [outfitsHomedIn] — which folders a save rewrites and which outfits land in
 *                          each (the single-home rule).
 */
class OutfitsRepositoryTest {

    private fun outfit(
        id: String,
        itemNames: List<String> = emptyList(),
        itemIds: List<String> = emptyList(),
        folderId: String = "",
    ) = Outfit(id = id, name = "Outfit $id", itemNames = itemNames, itemIds = itemIds, folderId = folderId)

    // ---- resolveOutfits ----

    @Test
    fun `resolveOutfits resolves itemNames to ids and stamps the folder`() {
        val nameToId = mapOf("shirt_cutout" to "id-shirt", "pants_cutout" to "id-pants")
        val raw = listOf(outfit("a", itemNames = listOf("shirt_cutout.webp", "pants_cutout.webp")))

        val resolved = resolveOutfits(raw, "closet-1", nameToId)

        assertEquals(listOf("id-shirt", "id-pants"), resolved.single().itemIds)
        assertEquals("closet-1", resolved.single().folderId)
    }

    @Test
    fun `resolveOutfits matches a legacy png itemName against a webp item (extension-agnostic)`() {
        // The live item is now `{id}_cutout.webp`; the outfit JSON still holds the old `.png` name.
        // itemMatchKey normalises both to `{id}_cutout`, so it still resolves after the WebP rename.
        val nameToId = mapOf("xyz_cutout" to "id-xyz")
        val raw = listOf(outfit("a", itemNames = listOf("xyz_cutout.png")))

        assertEquals(listOf("id-xyz"), resolveOutfits(raw, "f", nameToId).single().itemIds)
    }

    @Test
    fun `resolveOutfits drops itemNames with no matching live item`() {
        val raw = listOf(outfit("a", itemNames = listOf("gone_cutout.webp", "here_cutout.webp")))
        val nameToId = mapOf("here_cutout" to "id-here")

        assertEquals(listOf("id-here"), resolveOutfits(raw, "f", nameToId).single().itemIds)
    }

    @Test
    fun `resolveOutfits keeps existing itemIds when there are no itemNames`() {
        val raw = listOf(outfit("a", itemNames = emptyList(), itemIds = listOf("kept-1", "kept-2")))

        assertEquals(listOf("kept-1", "kept-2"), resolveOutfits(raw, "f", emptyMap()).single().itemIds)
    }

    // ---- foldersToWrite ----

    @Test
    fun `foldersToWrite unions scope and affected and drops blanks`() {
        assertEquals(
            setOf("a", "b", "c"),
            foldersToWrite(scope = listOf("a", "b"), affected = listOf("b", "c")),
        )
        assertEquals(setOf("a"), foldersToWrite(scope = listOf("a", ""), affected = listOf("")))
    }

    // ---- outfitsHomedIn ----

    @Test
    fun `outfitsHomedIn keeps homed plus not-yet-homed outfits and excludes other folders`() {
        val styles = listOf(
            outfit("homed", folderId = "f1"),
            outfit("other", folderId = "f2"),
            outfit("fresh", folderId = ""), // not yet homed — rides into every folder until replaceFolder resolves it
        )

        assertEquals(setOf("homed", "fresh"), outfitsHomedIn("f1", styles).map { it.id }.toSet())
        assertEquals(setOf("other", "fresh"), outfitsHomedIn("f2", styles).map { it.id }.toSet())
    }
}
