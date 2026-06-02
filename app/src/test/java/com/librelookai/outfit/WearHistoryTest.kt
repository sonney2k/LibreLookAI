package com.librelookai.outfit

import com.librelookai.data.model.Outfit
import com.librelookai.data.model.OutfitEvent
import com.librelookai.data.model.WearSource
import com.librelookai.gemini.ClothingTags
import com.librelookai.wardrobe.DriveImage
import com.librelookai.weather.WeatherData
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate

/**
 * Unit tests for the calendar wear-history → taste-signal helpers ([buildOutfitEvent],
 * [buildWearHistorySummary]).
 */
class WearHistoryTest {

    private fun img(id: String, type: String, colors: List<String> = emptyList(), aesthetic: List<String> = emptyList()) =
        DriveImage(
            driveId = id, localPath = "/$id", name = id,
            tags = ClothingTags(type = type, category = "Top", colors = colors, aesthetic = aesthetic),
        )

    @Test
    fun `buildOutfitEvent snapshots tags weather and source`() {
        val outfit = Outfit(id = "o1", name = "Friday Look", itemIds = listOf("a", "b"), tags = listOf("work"))
        val images = mapOf(
            "a" to img("a", "shirt", colors = listOf("blue"), aesthetic = listOf("smart casual")),
            "b" to img("b", "jeans", colors = listOf("indigo")),
        )
        val weather = WeatherData(temperatureCelsius = 9.4f, weatherCode = 3, cityName = "Berlin")

        val e = buildOutfitEvent(outfit, images, WearSource.MANUAL, weather, date = LocalDate.of(2026, 1, 10))

        assertEquals("o1", e.outfitId)
        assertEquals("Friday Look", e.outfitName)
        assertEquals(listOf("work"), e.outfitTags)
        assertEquals(setOf("shirt", "jeans"), e.itemTypes.toSet())
        assertEquals(setOf("blue", "indigo"), e.colors.toSet())
        assertEquals(listOf("smart casual"), e.aesthetics)
        assertEquals(9, e.tempC)
        assertEquals(3, e.weatherCode)
        assertEquals(WearSource.MANUAL, e.source)
        assertFalse(e.planned)
    }

    @Test
    fun `future date is flagged as planned`() {
        val outfit = Outfit(id = "o1", name = "Trip Day 1", itemIds = emptyList())
        val e = buildOutfitEvent(outfit, emptyMap(), date = LocalDate.now().plusDays(5))
        assertTrue(e.planned)
    }

    @Test
    fun `summary is null without history`() {
        assertNull(buildWearHistorySummary(emptyList()))
        // Only planned (future) events → no usable signal.
        val planned = OutfitEvent(outfitId = "o", date = LocalDate.now().plusDays(2).toString(), planned = true)
        assertNull(buildWearHistorySummary(listOf(planned)))
    }

    @Test
    fun `loved manual wears outrank ai-suggested ones`() {
        val today = LocalDate.of(2026, 1, 20)
        val events = buildList {
            // "Cozy" worn once, manually, loved → weight 2 + 3 = 5.
            add(OutfitEvent(outfitId = "a", outfitName = "Cozy", date = "2026-01-01", source = WearSource.MANUAL, loved = true))
            // "Flashy" worn twice via AI acceptance → weight 1 + 1 = 2.
            add(OutfitEvent(outfitId = "b", outfitName = "Flashy", date = "2026-01-02", source = WearSource.AI_SUGGESTED))
            add(OutfitEvent(outfitId = "b", outfitName = "Flashy", date = "2026-01-03", source = WearSource.AI_SUGGESTED))
        }
        val summary = buildWearHistorySummary(events, today = today)!!
        val mostWornLine = summary.lineSequence().first { it.startsWith("- Most-worn outfits:") }
        assertTrue("Cozy should rank first: $mostWornLine", mostWornLine.indexOf("Cozy") < mostWornLine.indexOf("Flashy"))
        assertTrue(summary.contains("Explicitly loved: Cozy"))
    }

    @Test
    fun `recently worn outfits are surfaced for avoidance`() {
        val today = LocalDate.of(2026, 1, 20)
        val events = listOf(
            OutfitEvent(outfitId = "a", outfitName = "Yesterday", date = "2026-01-19"),
            OutfitEvent(outfitId = "b", outfitName = "LastMonth", date = "2025-12-15"),
        )
        val summary = buildWearHistorySummary(events, today = today)!!
        val recentLine = summary.lineSequence().first { it.contains("last 7 days") }
        assertTrue(recentLine.contains("Yesterday"))
        assertFalse(recentLine.contains("LastMonth"))
    }
}
