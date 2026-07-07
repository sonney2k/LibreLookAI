package com.librelookai.travel

import android.app.Application
import androidx.test.core.app.ApplicationProvider
import com.librelookai.data.drive.DrainScheduler
import com.librelookai.data.drive.SyncEngine
import com.librelookai.data.model.Outfit
import com.librelookai.data.model.Trip
import com.librelookai.gemini.AiResult
import com.librelookai.gemini.AiRetry
import com.librelookai.gemini.ClothingTags
import com.librelookai.testing.FakeAiClient
import com.librelookai.testing.FakeDriveService
import com.librelookai.testing.FakeMutationStore
import com.librelookai.testing.FakeTripStore
import com.librelookai.wardrobe.DriveImage
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * Fake-based suite for the trips UI-surface VM (refactor § 8): the store-derived trips mirror,
 * the save/delete funnels through [TripsRepository] (queue rows asserted, no handlers
 * registered), the cascade hand-back of deleted trips' outfit ids, the create→navigate
 * one-shot, and the bulk-refine flow — preview staging with known-id/known-item filtering and
 * name fallbacks, plus the null/credits degrade paths over a scripted [FakeAiClient].
 */
@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
class TripsViewModelTest {

    private val app: Application = ApplicationProvider.getApplicationContext()
    private val drive = FakeDriveService()
    private val gemini = FakeAiClient()
    private val aiRetry = AiRetry()
    private val tripStore = FakeTripStore()
    private val mutationStore = FakeMutationStore()
    // No handlers registered: drain() halts on the unknown kind, leaving the queued rows
    // observable (the § 2 engine contract — unknown kinds halt without data loss).
    private val syncEngine = SyncEngine(mutationStore, emptySet(), object : DrainScheduler {
        override fun ensureScheduled() {}
    })
    private val repo = TripsRepository(drive, tripStore, mutationStore, syncEngine)

    private fun vm() = TripsViewModel(app, gemini, aiRetry, repo)

    private fun trip(id: String, outfitIds: List<String> = emptyList()) = Trip(
        id = id, name = "Trip $id", destination = "Lisbon", days = outfitIds.size.coerceAtLeast(1),
        outfitIds = outfitIds,
    )

    private fun item(driveId: String) = DriveImage(
        driveId = driveId, localPath = "", name = "${driveId}_cutout.webp",
        tags = ClothingTags(type = "t-shirt", category = "tops"),
    )

    @Before
    fun setUp() {
        Dispatchers.setMain(StandardTestDispatcher())
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }

    @Test
    fun `trips mirror the repo's store-derived flow`() = runTest {
        tripStore.replaceAll(listOf(trip("t1"), trip("t2")))
        val vm = vm()

        val trips = vm.state.first { it.trips.size == 2 }.trips

        assertEquals(setOf("t1", "t2"), trips.map { it.id }.toSet())
    }

    @Test
    fun `renameTrip persists through the repo save funnel`() = runTest {
        tripStore.replaceAll(listOf(trip("t1")))
        val vm = vm()
        vm.state.first { it.trips.size == 1 }

        vm.renameTrip("t1", "Lisbon getaway")

        vm.state.first { s -> s.trips.single().name == "Lisbon getaway" }
        assertEquals("Lisbon getaway", tripStore.flow.value.single().name)
        assertEquals(listOf(TRIP_SAVE_KIND), mutationStore.rows.map { it.kind })
        assertEquals("t1", mutationStore.rows.single().targetId)
    }

    @Test
    fun `deleteTrips hands back the union of outfit ids for the cascade`() = runTest {
        tripStore.replaceAll(listOf(trip("t1", listOf("a", "b")), trip("t2", listOf("b", "c")), trip("keep")))
        val vm = vm()
        vm.state.first { it.trips.size == 3 }
        var cascaded: List<String>? = null

        vm.deleteTrips(listOf("t1", "t2")) { cascaded = it }

        vm.state.first { it.trips.size == 1 }
        assertEquals(setOf("a", "b", "c"), cascaded!!.toSet())
        assertEquals(listOf("keep"), tripStore.flow.value.map { it.id })
        assertEquals(listOf(TRIP_DELETE_KIND, TRIP_DELETE_KIND), mutationStore.rows.map { it.kind })
        assertEquals(setOf("t1", "t2"), mutationStore.rows.map { it.targetId }.toSet())
    }

    @Test
    fun `createAndOpenTrip saves and emits the navigate one-shot`() = runTest {
        val vm = vm()
        var navigated: String? = null
        val collector = launch { navigated = vm.navigateToTrip.first() }

        vm.createAndOpenTrip(trip("fresh"))

        vm.state.first { it.trips.size == 1 }
        advanceUntilIdle()
        assertEquals("fresh", navigated)
        collector.cancel()
    }

    // ---- bulk refine ----

    @Test
    fun `refineAllOutfits stages a filtered preview and never persists by itself`() = runTest {
        tripStore.replaceAll(listOf(trip("t1", outfitIds = listOf("o1", "o2"))))
        val vm = vm()
        vm.state.first { it.trips.size == 1 }
        val outfits = listOf(
            Outfit(id = "o1", name = "Day 1", itemIds = listOf("i1")),
            Outfit(id = "o2", name = "Day 2", itemIds = listOf("i2")),
        )
        val images = listOf(item("i1"), item("i2"), item("i3"))
        gemini.generateResult = """
            {"outfits":[
              {"id":"o1","itemIds":["i3","ghost-item"],"name":"Brighter day 1","description":"d1"},
              {"id":"o2","itemIds":["i1"],"name":"","description":""},
              {"id":"not-in-trip","itemIds":["i1"],"name":"x","description":"x"}
            ]}
        """.trimIndent()
        var done: Boolean? = null

        vm.refineAllOutfits("t1", "brighter", images, outfits) { done = it }

        val s = vm.state.first { it.refinePreview.isNotEmpty() }
        assertEquals(true, done)
        assertEquals("t1", s.refinePreviewTripId)
        assertEquals(setOf("o1", "o2"), s.refinePreview.keys) // the unknown outfit id is dropped
        assertEquals(listOf("i3"), s.refinePreview["o1"]!!.itemIds) // unknown items filtered
        assertEquals("Brighter day 1", s.refinePreview["o1"]!!.name)
        assertEquals("Day 2", s.refinePreview["o2"]!!.name) // blank name falls back to existing
        assertTrue(vm.bulkRefining.value.isEmpty())
        assertNotNull(aiRetry.action)
        // Preview only — nothing was saved anywhere.
        assertTrue(mutationStore.rows.isEmpty())

        vm.discardRefinePreview()
        assertNull(vm.state.value.refinePreviewTripId)
        assertTrue(vm.state.value.refinePreview.isEmpty())
    }

    @Test
    fun `a null refine response surfaces the localized error and clears the busy flag`() = runTest {
        tripStore.replaceAll(listOf(trip("t1", outfitIds = listOf("o1"))))
        val vm = vm()
        vm.state.first { it.trips.size == 1 }
        val outfits = listOf(Outfit(id = "o1", name = "Day 1", itemIds = listOf("i1")))
        var done: Boolean? = null

        vm.refineAllOutfits("t1", "brighter", listOf(item("i1")), outfits) { done = it }

        val s = vm.state.first { it.error != null }
        assertEquals(false, done)
        assertEquals(app.getString(com.librelookai.core.designsystem.R.string.error_gemini_no_response), s.error)
        assertTrue(vm.bulkRefining.value.isEmpty())
    }

    @Test
    fun `insufficient credits abort the refine quietly (global dialog owns the surface)`() = runTest {
        tripStore.replaceAll(listOf(trip("t1", outfitIds = listOf("o1"))))
        val vm = vm()
        vm.state.first { it.trips.size == 1 }
        gemini.outcome = AiResult.InsufficientCredits(needed = 10, have = 2)
        var done: Boolean? = null

        vm.refineAllOutfits("t1", "brighter", listOf(item("i1")), listOf(Outfit(id = "o1", itemIds = listOf("i1")))) { done = it }

        advanceUntilIdle()
        assertEquals(false, done)
        assertNull(vm.state.value.error)
        assertTrue(vm.bulkRefining.value.isEmpty())
    }
}
