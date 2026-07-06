package com.librelookai.outfit

import android.app.Application
import androidx.test.core.app.ApplicationProvider
import com.librelookai.data.drive.DrainScheduler
import com.librelookai.data.drive.DriveFileDto
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
import com.librelookai.testing.FakeOutfitStore
import com.librelookai.testing.FakeWardrobeItemStore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * Fake-based suite for the outfits **list** VM (refactor § 8 — the try-on pair's pattern):
 * the session-driven all-closets mirror, the mutators' home-folder targeting through the
 * [OutfitsRepository.persistOutfitFolders] funnel (queue rows asserted, no handlers
 * registered), the cross-closet `itemNames` re-resolution, the repo-owned calendar-wear
 * hand-off quirks, and the wear-history DB read.
 */
@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
class OutfitsViewModelTest {

    private val app: Application = ApplicationProvider.getApplicationContext()
    private val drive = FakeDriveService()
    private val itemStore = FakeWardrobeItemStore()
    private val outfitStore = FakeOutfitStore()
    private val eventStore = FakeOutfitEventStore()
    private val mutationStore = FakeMutationStore()
    // No handlers registered: drain() halts on the unknown kind, leaving the queued rows
    // observable (the § 2 engine contract — unknown kinds halt without data loss).
    private val syncEngine = SyncEngine(mutationStore, emptySet(), object : DrainScheduler {
        override fun ensureScheduled() {}
    })
    private val session = ClosetSessionHolder()
    private val repo = OutfitsRepository(drive, itemStore, outfitStore, mutationStore, syncEngine)

    private fun vm() = OutfitsViewModel(app, repo, session, eventStore)

    /** All-locations session over [folderIds] — outfits always load from every closet. */
    private fun activate(vararg folderIds: String) = session.setClosets(
        folderIds.map { Location(name = it, folderId = it) },
        ClosetSession.ALL_LOCATIONS_ID,
        defaultClosetFolderId = null,
    )

    private fun outfit(
        id: String,
        folderId: String,
        loved: Boolean = false,
        itemIds: List<String> = emptyList(),
        itemNames: List<String> = emptyList(),
    ) = Outfit(
        id = id, name = "Outfit $id", loved = loved,
        itemIds = itemIds, itemNames = itemNames, folderId = folderId,
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
    fun `state mirrors the store-derived outfits across every closet in the session`() = runTest {
        outfitStore.replaceFolder("f1", listOf(outfit("a", folderId = "f1")))
        outfitStore.replaceFolder("f2", listOf(outfit("b", folderId = "f2")))
        activate("f1", "f2")
        val vm = vm()

        val outfits = vm.state.first { it.outfits.size == 2 }.outfits

        assertEquals(setOf("a", "b"), outfits.map { it.id }.toSet())
    }

    @Test
    fun `setOutfitLoved flips optimistically and queues only the home folder's sync`() = runTest {
        outfitStore.replaceFolder("f1", listOf(outfit("a", folderId = "f1")))
        outfitStore.replaceFolder("f2", listOf(outfit("b", folderId = "f2")))
        activate("f1", "f2")
        val vm = vm()
        vm.state.first { it.outfits.size == 2 }

        vm.setOutfitLoved("a", loved = true)

        vm.state.first { s -> s.outfits.single { it.id == "a" }.loved }
        assertTrue(outfitStore.outfitsFor("f1").single().loved)
        // Only the outfit's home folder is enqueued — the other closet's file is untouched.
        assertEquals(listOf(OUTFIT_FOLDER_SYNC_KIND), mutationStore.rows.map { it.kind })
        assertEquals("f1", mutationStore.rows.single().folderId)
    }

    @Test
    fun `deleteSelectedOutfits clears the selection and writes each deleted outfit's home folder`() = runTest {
        outfitStore.replaceFolder("f1", listOf(outfit("a", folderId = "f1")))
        outfitStore.replaceFolder("f2", listOf(outfit("b", folderId = "f2")))
        activate("f1", "f2")
        val vm = vm()
        vm.state.first { it.outfits.size == 2 }
        vm.toggleOutfitSelection("a")
        vm.toggleOutfitSelection("b")

        vm.deleteSelectedOutfits()

        val s = vm.state.first { it.outfits.isEmpty() }
        assertTrue(s.selectedOutfitIds.isEmpty())
        assertTrue(outfitStore.outfitsFor("f1").isEmpty())
        assertTrue(outfitStore.outfitsFor("f2").isEmpty())
        assertEquals(setOf("f1", "f2"), mutationStore.rows.map { it.folderId }.toSet())
    }

    @Test
    fun `addOutfits homes fresh outfits in the save target and resolves their itemNames`() = runTest {
        drive.filesByFolder["f1"] = listOf(DriveFileDto(id = "id-shirt", name = "shirt_cutout.webp"))
        outfitStore.replaceFolder("f1", listOf(outfit("existing", folderId = "f1")))
        activate("f1", "f2") // All-locations ⇒ saveFolderId falls back to the first closet
        val vm = vm()
        vm.state.first { it.outfits.size == 1 }
        var done: Boolean? = null

        vm.addOutfits(listOf(outfit("new", folderId = "", itemIds = listOf("id-shirt")))) { done = it }

        vm.state.first { s -> s.outfits.any { it.id == "new" } }
        assertEquals(true, done)
        val stored = outfitStore.outfitsFor("f1").single { it.id == "new" }
        assertEquals("f1", stored.folderId)
        assertEquals(listOf("shirt_cutout.webp"), stored.itemNames)
    }

    @Test
    fun `updateOutfitItems re-resolves itemNames against the affected closets`() = runTest {
        drive.filesByFolder["f1"] = listOf(
            DriveFileDto(id = "id-shirt", name = "shirt_cutout.webp"),
            DriveFileDto(id = "id-pants", name = "pants_cutout.webp"),
        )
        outfitStore.replaceFolder(
            "f1",
            listOf(outfit("a", folderId = "f1", itemIds = listOf("id-shirt"), itemNames = listOf("shirt_cutout.webp"))),
        )
        activate("f1")
        val vm = vm()
        vm.state.first { it.outfits.size == 1 }
        var done: Boolean? = null

        vm.updateOutfitItems(mapOf("a" to listOf("id-pants"))) { done = it }

        vm.state.first { s -> s.outfits.single { it.id == "a" }.itemIds == listOf("id-pants") }
        assertEquals(true, done)
        val stored = outfitStore.outfitsFor("f1").single()
        assertEquals(listOf("id-pants"), stored.itemIds)
        assertEquals(listOf("pants_cutout.webp"), stored.itemNames)
    }

    @Test
    fun `the calendar-wear hand-off mirrors the repo request, and consume keeps the source`() = runTest {
        activate("f1")
        val vm = vm()

        vm.requestCalendarWear("o1", WearSource.AI_SUGGESTED)

        val pending = vm.state.first { it.pendingCalendarWearId != null }
        assertEquals("o1", pending.pendingCalendarWearId)
        assertEquals(WearSource.AI_SUGGESTED, pending.pendingCalendarWearSource)

        vm.consumeCalendarWear()

        // The id clears; the source deliberately keeps its last value — the calendar's
        // consume-then-read of the source must not race a null mirror arm.
        val consumed = vm.state.first { it.pendingCalendarWearId == null }
        assertNull(consumed.pendingCalendarWearId)
        assertEquals(WearSource.AI_SUGGESTED, consumed.pendingCalendarWearSource)
    }

    @Test
    fun `wearHistory derives from the event store scoped by the session`() = runTest {
        eventStore.replaceFolder("f1", listOf(OutfitEvent(id = "e1", outfitId = "o1", date = "2026-07-01")))
        eventStore.replaceFolder("elsewhere", listOf(OutfitEvent(id = "e2", outfitId = "o2", date = "2026-07-02")))
        activate("f1", "f2")
        val vm = vm()

        val history = vm.state.first { it.wearHistory.isNotEmpty() }.wearHistory

        assertEquals(listOf("e1"), history.map { it.id })
    }
}
