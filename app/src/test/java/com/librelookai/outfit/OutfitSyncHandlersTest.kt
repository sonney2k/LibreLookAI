package com.librelookai.outfit

import com.google.gson.Gson
import com.librelookai.data.drive.MutationOutcome
import com.librelookai.data.local.PendingMutation
import com.librelookai.data.model.Outfit
import com.librelookai.data.model.OutfitEvent
import com.librelookai.testing.FakeDriveService
import com.librelookai.testing.FakeOutfitEventStore
import com.librelookai.testing.FakeOutfitStore
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Queued-mutation invariants for the outfit / calendar-wear folder-sync handlers (refactor
 * § 3 slice 2 / § 8): the payload-free store re-read, the never-cached-folder **wipe guard**
 * (an empty read means "unknown", not "no data" — writing `[]` would destroy the user's Drive
 * file), and the retry-on-Drive-failure rule become tested invariants.
 */
class OutfitSyncHandlersTest {

    private val gson = Gson()
    private val drive = FakeDriveService()
    private val outfits = FakeOutfitStore()
    private val events = FakeOutfitEventStore()

    private fun mutation(kind: String, folderId: String) =
        PendingMutation(1L, kind, folderId, folderId, "", null, 0, null, 0L)

    // ---------- outfit folder sync ----------

    @Test
    fun `outfit sync writes the folder's current store rows`() = runTest {
        val rows = listOf(Outfit(id = "o1", name = "Casual"), Outfit(id = "o2", name = "Formal"))
        outfits.replaceFolder("folder-1", rows)
        val handler = OutfitFolderSyncHandler(drive, outfits)

        val outcome = handler.apply(mutation(OUTFIT_FOLDER_SYNC_KIND, "folder-1"))

        assertEquals(MutationOutcome.Success, outcome)
        assertEquals(gson.toJson(rows), drive.outfitsJsonByFolder["folder-1"])
    }

    @Test
    fun `outfit sync refuses to write a never-cached folder`() = runTest {
        val handler = OutfitFolderSyncHandler(drive, outfits)

        val outcome = handler.apply(mutation(OUTFIT_FOLDER_SYNC_KIND, "unknown"))

        assertTrue(outcome is MutationOutcome.Permanent)
        assertTrue(drive.outfitsJsonByFolder.isEmpty())
    }

    @Test
    fun `outfit sync writes an empty list for a cached-but-empty folder`() = runTest {
        outfits.replaceFolder("folder-1", emptyList())
        val handler = OutfitFolderSyncHandler(drive, outfits)

        val outcome = handler.apply(mutation(OUTFIT_FOLDER_SYNC_KIND, "folder-1"))

        assertEquals(MutationOutcome.Success, outcome)
        assertEquals("[]", drive.outfitsJsonByFolder["folder-1"])
    }

    @Test
    fun `outfit sync retries on a drive failure`() = runTest {
        outfits.replaceFolder("folder-1", listOf(Outfit(id = "o1")))
        val failingDrive = object : FakeDriveService() {
            override suspend fun saveOutfitsJson(folderId: String, json: String) = error("drive down")
        }
        val handler = OutfitFolderSyncHandler(failingDrive, outfits)

        val outcome = handler.apply(mutation(OUTFIT_FOLDER_SYNC_KIND, "folder-1"))

        assertTrue(outcome is MutationOutcome.Retry)
    }

    // ---------- outfit-event folder sync ----------

    @Test
    fun `event sync writes the folder's current store rows`() = runTest {
        val rows = listOf(
            OutfitEvent(id = "e1", outfitId = "o1", date = "2026-07-01"),
            OutfitEvent(id = "e2", outfitId = "o2", date = "2026-07-02"),
        )
        events.replaceFolder("folder-1", rows)
        val handler = OutfitEventSyncHandler(drive, events)

        val outcome = handler.apply(mutation(OUTFIT_EVENT_SYNC_KIND, "folder-1"))

        assertEquals(MutationOutcome.Success, outcome)
        assertEquals(gson.toJson(rows), drive.outfitEventsJsonByFolder["folder-1"])
    }

    @Test
    fun `event sync refuses to write a never-cached folder`() = runTest {
        val handler = OutfitEventSyncHandler(drive, events)

        val outcome = handler.apply(mutation(OUTFIT_EVENT_SYNC_KIND, "unknown"))

        assertTrue(outcome is MutationOutcome.Permanent)
        assertTrue(drive.outfitEventsJsonByFolder.isEmpty())
    }

    @Test
    fun `event sync retries on a drive failure`() = runTest {
        events.replaceFolder("folder-1", listOf(OutfitEvent(id = "e1")))
        val failingDrive = object : FakeDriveService() {
            override suspend fun saveOutfitEventsJson(folderId: String, json: String) = error("drive down")
        }
        val handler = OutfitEventSyncHandler(failingDrive, events)

        val outcome = handler.apply(mutation(OUTFIT_EVENT_SYNC_KIND, "folder-1"))

        assertTrue(outcome is MutationOutcome.Retry)
    }
}
