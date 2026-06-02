package com.librelookai.gemini

import com.librelookai.billing.tierForEur
import org.junit.Assert.assertEquals
import org.junit.Test

/** Pure-logic checks for the BYOK cost estimate: token sizing + the €-glyph tier scale. */
class TokenEstimatorTest {

    @Test
    fun `text tokens use roughly four chars per token`() {
        assertEquals(0, TokenEstimator.textTokens(""))
        assertEquals(1, TokenEstimator.textTokens("abcd"))   // 4 chars → 1 token
        assertEquals(2, TokenEstimator.textTokens("abcde"))  // 5 chars → ceil(1.25) = 2
        assertEquals(25, TokenEstimator.textTokens("x".repeat(100)))
    }

    @Test
    fun `small images cost a single tile`() {
        // Both sides within the small-image threshold → one tile.
        assertEquals(258, TokenEstimator.imageInputTokens(300, 200))
        assertEquals(258, TokenEstimator.imageInputTokens(384, 384))
    }

    @Test
    fun `large images are tiled into 768 crops`() {
        // 1000x800 → ceil(1000/768)=2 x ceil(800/768)=2 = 4 tiles.
        assertEquals(4 * 258, TokenEstimator.imageInputTokens(1000, 800))
    }

    @Test
    fun `oversized images are capped to the upload max before tiling`() {
        // 4000x3000 caps to 1280x960 → 2x2 tiles = 4 tiles (not 6x4).
        assertEquals(4 * 258, TokenEstimator.imageInputTokens(4000, 3000))
    }

    @Test
    fun `degenerate dimensions fall back to one tile`() {
        assertEquals(258, TokenEstimator.imageInputTokens(0, 0))
        assertEquals(258, TokenEstimator.imageInputTokens(-5, 100))
    }

    @Test
    fun `eur tiers follow round-cent decades`() {
        assertEquals(1, tierForEur(0.0))      // free / sub-cent
        assertEquals(1, tierForEur(0.009))    // <1¢
        assertEquals(2, tierForEur(0.01))     // 1¢
        assertEquals(2, tierForEur(0.049))    // <5¢
        assertEquals(3, tierForEur(0.05))     // 5¢
        assertEquals(3, tierForEur(0.19))     // <20¢
        assertEquals(4, tierForEur(0.20))     // 20¢
        assertEquals(4, tierForEur(1.50))     // expensive bulk
    }
}
