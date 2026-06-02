package com.librelookai.billing

import com.librelookai.gemini.CostTokens
import com.librelookai.gemini.DefaultModelPricing
import com.librelookai.gemini.TokenEstimator
import com.librelookai.gemini.buildTryOnPrompt
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * End-to-end checks for the BYOK estimate: a [CostTokens] payload priced with the real default
 * model rates ([DefaultModelPricing]) lands on the expected €-glyph tier ([tierForEur]). Guards the
 * calibration against rate/format drift and proves the estimate reacts to actual input size.
 */
class CostEstimatePricingTest {

    private val textModel = "gemini-3-flash-preview"
    private val imageModel = "gemini-3.1-flash-image-preview"

    private fun eur(model: String, t: CostTokens): Double =
        DefaultModelPricing.snapshot.ratesFor(model)
            .eurFor(t.inputTokens, t.outputTokens, t.outputIsImage)

    private fun tier(model: String, t: CostTokens): Int = tierForEur(eur(model, t))

    @Test
    fun `classify is a one-glyph action`() {
        val t = CostTokens(inputTokens = 1500, outputTokens = 400, outputIsImage = false)
        assertEquals(1, tier(textModel, t)) // sub-cent
    }

    @Test
    fun `small text suggestion is one glyph`() {
        val t = CostTokens(inputTokens = 2000, outputTokens = 200, outputIsImage = false)
        assertEquals(1, tier(textModel, t))
    }

    @Test
    fun `a large wardrobe pushes a text suggestion to two glyphs`() {
        // Big closet → large prompt JSON. The image-out rate is irrelevant here (text out).
        val small = CostTokens(inputTokens = 2_000, outputTokens = 500, outputIsImage = false)
        val huge = CostTokens(inputTokens = 60_000, outputTokens = 500, outputIsImage = false)
        assertEquals(1, tier(textModel, small))
        assertEquals(2, tier(textModel, huge))
        // Monotonic: more input never costs less.
        assertTrue(eur(textModel, huge) > eur(textModel, small))
    }

    @Test
    fun `image-generating actions are three glyphs (output dominated)`() {
        val tryOn = CostTokens(
            inputTokens = 3_000,
            outputTokens = TokenEstimator.IMAGE_OUTPUT_TOKENS,
            outputIsImage = true,
        )
        val bgRemoval = CostTokens(
            inputTokens = 1_400,
            outputTokens = TokenEstimator.IMAGE_OUTPUT_TOKENS,
            outputIsImage = true,
        )
        assertEquals(3, tier(imageModel, tryOn))
        assertEquals(3, tier(imageModel, bgRemoval))
    }

    @Test
    fun `try-on input image count barely moves the output-dominated cost`() {
        // The generated image dominates: adding more input photos must not change the tier.
        val prompt = buildTryOnPrompt(personCount = 1, itemCount = 3, preferences = "")
        val onePhoto = CostTokens(
            inputTokens = TokenEstimator.textTokens(prompt) + TokenEstimator.imageInputTokens(1280, 960),
            outputTokens = TokenEstimator.IMAGE_OUTPUT_TOKENS,
            outputIsImage = true,
        )
        val fivePhotos = CostTokens(
            inputTokens = TokenEstimator.textTokens(prompt) + TokenEstimator.imageInputTokens(1280, 960) * 5,
            outputTokens = TokenEstimator.IMAGE_OUTPUT_TOKENS,
            outputIsImage = true,
        )
        assertEquals(tier(imageModel, onePhoto), tier(imageModel, fivePhotos))
        // The extra input still costs strictly more, just not enough to cross a tier.
        assertTrue(eur(imageModel, fivePhotos) > eur(imageModel, onePhoto))
    }
}
