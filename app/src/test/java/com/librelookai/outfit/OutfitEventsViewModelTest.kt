package com.librelookai.outfit

import android.app.Application
import androidx.test.core.app.ApplicationProvider
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import com.librelookai.data.drive.DrainScheduler
import com.librelookai.data.drive.SyncEngine
import com.librelookai.data.model.Location
import com.librelookai.data.model.Outfit
import com.librelookai.data.model.OutfitEvent
import com.librelookai.data.model.WearSource
import com.librelookai.data.session.ClosetSession
import com.librelookai.data.session.ClosetSessionHolder
import com.librelookai.testing.FakeDriveService
import com.librelookai.testing.FakeMutationStore
import com.librelookai.testing.FakeOutfitEventStore
import java.time.LocalDate
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * Fake-based suite for the calendar-wears VM (refactor § 8): the session-scoped derived
 * events view, the snapshot-capturing [OutfitEventsViewModel.recordOutfit], the single-persist
 * bulk move/copy/delete mutators with their planned-flag recompute, and the § 2 local-first
 * persist funnel with the real [OutfitEventSyncHandler] registered — including the legacy
 * single-target rule re-homing every displayed event into the persist folder.
 */
@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
class OutfitEventsViewModelTest {

    private val gson = Gson()
    private val app: Application = ApplicationProvider.getApplicationContext()
    private val drive = FakeDriveService()
    private val eventStore = FakeOutfitEventStore()
    private val mutationStore = FakeMutationStore()
    private val syncEngine = SyncEngine(
        mutationStore,
        setOf(OutfitEventSyncHandler(drive, eventStore)),
        object : DrainScheduler {
            override fun ensureScheduled() {}
        },
    )
    private val session = ClosetSessionHolder()

    private fun vm() = OutfitEventsViewModel(app, drive, eventStore, mutationStore, syncEngine, session)

    /** A single-closet session with [active] the active closet (the session keys the active
     *  filter by *folder id* — `Location.id` is ephemeral and never used for identity). */
    private fun activate(active: Location, vararg others: Location) =
        session.setClosets(listOf(active) + others, active.folderId, defaultClosetFolderId = null)

    private fun allLocations(vararg locations: Location) =
        session.setClosets(locations.toList(), ClosetSession.ALL_LOCATIONS_ID, defaultClosetFolderId = null)

    private fun event(id: String, date: String = "2026-07-01", loved: Boolean = false) =
        OutfitEvent(id = id, outfitId = "o-$id", date = date, loved = loved)

    private fun driveEvents(folderId: String): List<OutfitEvent> = gson.fromJson(
        drive.outfitEventsJsonByFolder[folderId],
        object : TypeToken<List<OutfitEvent>>() {}.type,
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
    fun `events derive from the active closet's store rows only`() = runTest {
        eventStore.replaceFolder("f1", listOf(event("in-scope")))
        eventStore.replaceFolder("f2", listOf(event("elsewhere")))
        val c1 = Location(name = "c1", folderId = "f1")
        activate(c1, Location(name = "c2", folderId = "f2"))
        val vm = vm()

        val events = vm.state.first { it.events.isNotEmpty() }.events

        assertEquals(listOf("in-scope"), events.map { it.id })
    }

    @Test
    fun `recordOutfit appends a snapshot wear and the queued folder sync reaches Drive`() = runTest {
        eventStore.replaceFolder("f1", emptyList()) // cached marker — the handler's wipe guard
        activate(Location(name = "c1", folderId = "f1"))
        val vm = vm()
        val outfit = Outfit(id = "o-1", name = "Summer look", tags = listOf("casual"), folderId = "f1")

        vm.recordOutfit(outfit, imagesById = emptyMap(), source = WearSource.AI_SUGGESTED)

        val events = vm.state.first { it.events.isNotEmpty() }.events
        val wear = events.single()
        assertEquals("o-1", wear.outfitId)
        assertEquals("Summer look", wear.outfitName) // the denormalised taste snapshot
        assertEquals(WearSource.AI_SUGGESTED, wear.source)
        // The § 2 funnel drained end-to-end: the folder JSON on Drive holds the wear.
        syncEngine.drain()
        assertEquals(listOf(wear.id), driveEvents("f1").map { it.id })
        assertEquals(0, mutationStore.rows.size)
    }

    @Test
    fun `moveEvents moves a whole day in one persist and recomputes the planned flag`() = runTest {
        eventStore.replaceFolder("f1", listOf(event("e1"), event("e2"), event("stay")))
        activate(Location(name = "c1", folderId = "f1"))
        val vm = vm()
        vm.state.first { it.events.size == 3 }
        val future = LocalDate.now().plusDays(3)

        vm.moveEvents(setOf("e1", "e2"), future)

        val events = vm.state.first { s -> s.events.count { it.date == future.toString() } == 2 }.events
        assertTrue(events.filter { it.id in setOf("e1", "e2") }.all { it.planned })
        assertEquals("2026-07-01", events.single { it.id == "stay" }.date)
    }

    @Test
    fun `copyEvent duplicates the wear under a fresh id and keeps the original`() = runTest {
        eventStore.replaceFolder("f1", listOf(event("src", date = "2026-07-01")))
        activate(Location(name = "c1", folderId = "f1"))
        val vm = vm()
        vm.state.first { it.events.size == 1 }

        vm.copyEvent("src", LocalDate.parse("2026-07-04"))

        val events = vm.state.first { it.events.size == 2 }.events
        val copy = events.single { it.id != "src" }
        assertNotEquals("src", copy.id)
        assertEquals("2026-07-04", copy.date)
        assertEquals("o-src", copy.outfitId) // taste snapshot preserved
        assertEquals("2026-07-01", events.single { it.id == "src" }.date)
    }

    @Test
    fun `deleteEvents clears a whole day in one persist`() = runTest {
        eventStore.replaceFolder("f1", listOf(event("e1"), event("e2"), event("keep")))
        activate(Location(name = "c1", folderId = "f1"))
        val vm = vm()
        vm.state.first { it.events.size == 3 }

        vm.deleteEvents(setOf("e1", "e2"))

        assertEquals(listOf("keep"), vm.state.first { it.events.size == 1 }.events.map { it.id })
    }

    @Test
    fun `setEventLoved toggles the per-wear loved flag`() = runTest {
        eventStore.replaceFolder("f1", listOf(event("e1")))
        activate(Location(name = "c1", folderId = "f1"))
        val vm = vm()
        vm.state.first { it.events.size == 1 }

        vm.setEventLoved("e1", loved = true)

        assertTrue(vm.state.first { s -> s.events.single().loved }.events.single().loved)
    }

    @Test
    fun `an All-locations persist re-homes every displayed wear into the first closet`() = runTest {
        eventStore.replaceFolder("f1", listOf(event("e1")))
        eventStore.replaceFolder("f2", listOf(event("e2")))
        allLocations(Location(name = "c1", folderId = "f1"), Location(name = "c2", folderId = "f2"))
        val vm = vm()
        vm.state.first { it.events.size == 2 }

        vm.setEventLoved("e2", loved = true)

        // The legacy single-target rule: the whole displayed list persists into the first
        // closet's file; the event-id primary key re-homes f2's wear atomically.
        vm.state.first { s -> s.events.any { it.id == "e2" && it.loved } }
        assertEquals(setOf("e1", "e2"), eventStore.eventsFor("f1").map { it.id }.toSet())
        assertTrue(eventStore.eventsFor("f2").isEmpty())
        syncEngine.drain()
        assertEquals(setOf("e1", "e2"), driveEvents("f1").map { it.id }.toSet())
    }
}
