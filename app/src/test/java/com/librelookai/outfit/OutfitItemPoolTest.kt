package com.librelookai.outfit

import com.librelookai.wardrobe.DriveImage
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Unit tests for [outfitItemPool] — the union that resolves outfit-card thumbnails.
 *
 * Regression cover for the tester report where a freshly-created outfit (and a handful of existing
 * ones) showed fewer items than they actually contained: the card resolved `style.itemIds` against
 * a single disk-cache read (`outfitsState.wardrobeImages`) that omitted just-uploaded items, so
 * `mapNotNull` silently dropped them. The pool now unions every known source, mirroring the
 * composer's slot lookup.
 */
class OutfitItemPoolTest {

    private fun item(id: String, folderId: String = "closet", version: Long = 0L): DriveImage =
        DriveImage(driveId = id, localPath = "/$id", name = id, folderId = folderId, version = version)

    private fun ids(images: List<DriveImage>) = images.map { it.driveId }.toSet()

    @Test
    fun `just-uploaded item present only in the active closet is included`() {
        // `fresh` lives in the active closet's in-memory list but hasn't reached either cache yet.
        val allLocations = listOf(item("a"), item("b"))
        val wardrobeImages = listOf(item("a"), item("b"))
        val active = listOf(item("a"), item("b"), item("fresh"))

        val pool = outfitItemPool(allLocations, wardrobeImages, active)

        assertTrue("brand-new active-closet item must resolve", "fresh" in ids(pool))
        assertEquals(setOf("a", "b", "fresh"), ids(pool))
    }

    @Test
    fun `item present only in the cross-closet snapshot is included`() {
        // Item from another (non-active) closet: only the snapshot knows it; active list is trimmed.
        val allLocations = listOf(item("a", folderId = "closet1"), item("other", folderId = "closet2"))
        val wardrobeImages = emptyList<DriveImage>()
        val active = listOf(item("a", folderId = "closet1"))

        val pool = outfitItemPool(allLocations, wardrobeImages, active)

        assertTrue("cross-closet item must still resolve", "other" in ids(pool))
    }

    @Test
    fun `duplicate driveId resolves to the active closet's fresher copy`() {
        // Same item in all three sources at different versions; the active (last) copy must win so
        // a freshly-reprocessed thumbnail isn't masked by a stale cache entry.
        val allLocations = listOf(item("a", version = 1L))
        val wardrobeImages = listOf(item("a", version = 2L))
        val active = listOf(item("a", version = 3L))

        val pool = outfitItemPool(allLocations, wardrobeImages, active)

        assertEquals(1, pool.size)
        assertEquals(3L, pool.single().version)
    }

    @Test
    fun `empty sources yield an empty pool`() {
        assertTrue(outfitItemPool(emptyList(), emptyList(), emptyList()).isEmpty())
    }
}
