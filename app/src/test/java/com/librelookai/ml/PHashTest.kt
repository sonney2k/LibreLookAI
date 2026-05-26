package com.librelookai.ml

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Unit tests for the pure-math hash-comparison helpers in PHash.
 * The Bitmap-based [PHash.compute]/[PHash.computeRotations] paths need an Android
 * graphics runtime (Robolectric/instrumented), so they are intentionally not covered here.
 */
class PHashTest {

    // 63 comparable bits (DC bit at index 0 is dropped → COMPARE_BITS = LOW*LOW - 1).
    private val compareBits = 63

    @Test
    fun similarity_identicalHashesIsOne() {
        assertEquals(1f, PHash.similarity(0L, 0L), 0f)
        assertEquals(1f, PHash.similarity(-1L, -1L), 0f)
        assertEquals(1f, PHash.similarity(0xDEADBEEFL, 0xDEADBEEFL), 0f)
    }

    @Test
    fun similarity_oneBitDifferenceScaledByCompareBits() {
        // A single differing bit costs 1/63.
        val expected = 1f - 1f / compareBits
        assertEquals(expected, PHash.similarity(0L, 1L), 1e-6f)
    }

    @Test
    fun similarity_isSymmetric() {
        val a = 0x0123456789ABCDEFL
        val b = 0xFEDCBA9876543210uL.toLong()
        assertEquals(PHash.similarity(a, b), PHash.similarity(b, a), 0f)
    }

    @Test
    fun similarity_neverNegativeForLargeHammingDistance() {
        // 64 differing bits exceeds the 63-bit denominator; result is allowed to dip
        // slightly below zero — assert it stays near the floor, not wildly off.
        val s = PHash.similarity(0L, -1L)
        assertEquals(-1f / compareBits, s, 1e-6f)
    }

    @Test
    fun bestSimilarity_emptyGalleryIsZero() {
        assertEquals(0f, PHash.bestSimilarity(123L, LongArray(0)), 0f)
    }

    @Test
    fun bestSimilarity_picksClosestGalleryEntry() {
        val query = 0b1010L
        // Entries differ from query by 3, 1, and 4 bits respectively.
        val gallery = longArrayOf(0b1101L, 0b1011L, 0b0101L)
        // Closest is the 1-bit-off entry.
        val expected = 1f - 1f / compareBits
        assertEquals(expected, PHash.bestSimilarity(query, gallery), 1e-6f)
    }

    @Test
    fun bestSimilarity_exactMatchInGalleryIsOne() {
        val gallery = longArrayOf(1L, 42L, 999L)
        assertEquals(1f, PHash.bestSimilarity(42L, gallery), 0f)
    }
}
