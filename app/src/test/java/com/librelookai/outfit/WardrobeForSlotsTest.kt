package com.librelookai.outfit

import com.librelookai.gemini.ClothingTags
import com.librelookai.wardrobe.DriveImage
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** Unit tests for the composer's slot-category wardrobe prefilter ([wardrobeForSlots]). */
class WardrobeForSlotsTest {

    private fun item(id: String, category: String?): DriveImage =
        DriveImage(
            driveId = id,
            localPath = "/$id",
            name = id,
            tags = category?.let { ClothingTags(category = it) },
        )

    private fun slot(layer: Layer, selectedItemId: String? = null, locked: Boolean = false) =
        OutfitSlot(id = "slot-$layer-$selectedItemId", category = layer, selectedItemId = selectedItemId, isLocked = locked)

    private fun ids(images: List<DriveImage>) = images.map { it.driveId }.toSet()

    @Test
    fun `empty slot list applies no filter`() {
        val all = listOf(item("a", "tops"), item("b", "footwear"))
        assertEquals(all, wardrobeForSlots(all, emptyList()))
    }

    @Test
    fun `keeps only items matching the requested slot layers`() {
        val images = listOf(
            item("top", "tops"),
            item("pants", "bottoms"),
            item("shoes", "footwear"),
            item("bag", "accessories"),
            item("coat", "outerwear"),
        )
        // A scratch outfit's default slots: Outerwear, Top, Bottom, Footwear (no Accessory).
        val slots = listOf(slot(Layer.Outerwear), slot(Layer.Top), slot(Layer.Bottom), slot(Layer.Footwear))
        val kept = ids(wardrobeForSlots(images, slots))
        assertTrue(kept.containsAll(setOf("top", "pants", "shoes", "coat")))
        assertTrue("accessory should be filtered out", "bag" !in kept)
    }

    @Test
    fun `top or bottom slot keeps one-piece candidates (dress can satisfy the pair)`() {
        val images = listOf(item("top", "tops"), item("dress", "dress"), item("suit", "suit"))
        val slots = listOf(slot(Layer.Top), slot(Layer.Bottom))
        val kept = ids(wardrobeForSlots(images, slots))
        assertTrue(kept.containsAll(setOf("dress", "suit")))
    }

    @Test
    fun `one-piece slot keeps top and bottom candidates`() {
        val images = listOf(item("top", "tops"), item("pants", "bottoms"), item("bag", "accessories"))
        val slots = listOf(slot(Layer.OnePiece))
        val kept = ids(wardrobeForSlots(images, slots))
        assertTrue(kept.containsAll(setOf("top", "pants")))
        assertTrue("bag" !in kept)
    }

    @Test
    fun `locked item is always kept even if its layer is not requested`() {
        val images = listOf(item("top", "tops"), item("bag", "accessories"))
        // Footwear slot locked to the bag (mismatched layer) — must still ship the locked item.
        val slots = listOf(slot(Layer.Top), slot(Layer.Footwear, selectedItemId = "bag", locked = true))
        val kept = ids(wardrobeForSlots(images, slots))
        assertTrue("bag" in kept)
    }

    @Test
    fun `unclassifiable items are kept to avoid dropping something usable`() {
        val images = listOf(item("mystery", "weird-thing"), item("untagged", null))
        val slots = listOf(slot(Layer.Top))
        val kept = ids(wardrobeForSlots(images, slots))
        assertTrue(kept.containsAll(setOf("mystery", "untagged")))
    }
}
