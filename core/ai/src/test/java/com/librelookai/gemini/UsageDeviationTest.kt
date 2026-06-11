package com.librelookai.gemini

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Per-category estimate-vs-actual deviation tracking + the shared output heuristic. */
class UsageDeviationTest {

    private fun event(
        cat: UsageCategory,
        actualIn: Int,
        actualOut: Int,
        estIn: Int?,
        estOut: Int?,
        ts: Long = 1_000L,
    ) = UsageEvent(
        timestampMs = ts,
        categoryKey = cat.storageKey,
        model = "gemini-3-flash-preview",
        inputTokens = actualIn,
        outputTokens = actualOut,
        // Pin EUR so deviation math is rate-independent: €1 per 1000 actual tokens, est mirrors.
        eurAtRecord = (actualIn + actualOut) / 1000.0,
        estInputTokens = estIn,
        estOutputTokens = estOut,
        estEurAtRecord = if (estIn != null && estOut != null) (estIn + estOut) / 1000.0 else null,
    )

    @Test
    fun `hasEstimate reflects presence of both estimate fields`() {
        assertTrue(event(UsageCategory.TAGGING, 100, 50, 90, 40).hasEstimate)
        assertNull(event(UsageCategory.TAGGING, 100, 50, null, null).estTotalTokens)
    }

    @Test
    fun `events without an estimate are excluded`() {
        val events = listOf(
            event(UsageCategory.TAGGING, 1000, 400, estIn = null, estOut = null),
            event(UsageCategory.TAGGING, 1000, 400, estIn = 1000, estOut = 400),
        )
        val dev = UsageAggregator.deviationByCategory(events)[UsageCategory.TAGGING]!!
        assertEquals(1, dev.calls) // only the estimated event
    }

    @Test
    fun `positive deviation means the estimate was too low`() {
        // estimate 1000 tok, actual 1200 tok → +20%.
        val events = listOf(event(UsageCategory.OUTFIT_PREDICT, actualIn = 1000, actualOut = 200, estIn = 800, estOut = 200))
        val dev = UsageAggregator.deviationByCategory(events)[UsageCategory.OUTFIT_PREDICT]!!
        assertEquals(1000, dev.estTokens)
        assertEquals(1200, dev.actualTokens)
        assertEquals(20.0, dev.tokenDeviationPct!!, 1e-6)
        assertEquals(20.0, dev.eurDeviationPct!!, 1e-6)
    }

    @Test
    fun `negative deviation means the estimate was too high`() {
        val events = listOf(event(UsageCategory.GAP_ANALYSIS, actualIn = 700, actualOut = 100, estIn = 800, estOut = 200))
        val dev = UsageAggregator.deviationByCategory(events)[UsageCategory.GAP_ANALYSIS]!!
        assertEquals(-20.0, dev.tokenDeviationPct!!, 1e-6) // 800 → 1000 est, 800 actual
    }

    @Test
    fun `deviation aggregates sum-of-actual vs sum-of-estimate per category`() {
        val events = listOf(
            event(UsageCategory.TAGGING, actualIn = 1000, actualOut = 400, estIn = 1000, estOut = 400),
            event(UsageCategory.TAGGING, actualIn = 3000, actualOut = 600, estIn = 1000, estOut = 400),
        )
        val dev = UsageAggregator.deviationByCategory(events)[UsageCategory.TAGGING]!!
        assertEquals(2, dev.calls)
        assertEquals(2800, dev.estTokens)   // (1400 + 1400)
        assertEquals(5000, dev.actualTokens) // (1400 + 3600)
        // (5000 - 2800) / 2800 ≈ 78.57%
        assertEquals(78.57, dev.tokenDeviationPct!!, 0.1)
    }

    @Test
    fun `total rolls up across categories`() {
        val events = listOf(
            event(UsageCategory.TAGGING, 1000, 400, 1000, 400),
            event(UsageCategory.TRY_ON, 2000, 1290, 1500, 1290),
        )
        val total = UsageAggregator.deviationTotal(events)
        assertEquals(2, total.calls)
        assertEquals(1400 + 2790, total.estTokens)
        assertEquals(1400 + 3290, total.actualTokens)
    }

    @Test
    fun `deviation pct is null without a baseline`() {
        val empty = DeviationStat(calls = 0, estTokens = 0, actualTokens = 0, estEur = 0.0, actualEur = 0.0)
        assertNull(empty.tokenDeviationPct)
        assertNull(empty.eurDeviationPct)
    }

    @Test
    fun `expected output tokens are fixed for images and scale for text`() {
        assertEquals(TokenEstimator.IMAGE_OUTPUT_TOKENS, TokenEstimator.expectedOutputTokens(UsageCategory.TRY_ON, 9))
        assertEquals(TokenEstimator.IMAGE_OUTPUT_TOKENS, TokenEstimator.expectedOutputTokens(UsageCategory.BG_REMOVAL))
        // OUTFIT_PREDICT: 40 + 45*n — grows with the suggestion count.
        assertEquals(40 + 45 * 1, TokenEstimator.expectedOutputTokens(UsageCategory.OUTFIT_PREDICT, 1))
        assertEquals(40 + 45 * 5, TokenEstimator.expectedOutputTokens(UsageCategory.OUTFIT_PREDICT, 5))
        // bulkItems floored at 1.
        assertEquals(40 + 45, TokenEstimator.expectedOutputTokens(UsageCategory.OUTFIT_PREDICT, 0))
    }
}
