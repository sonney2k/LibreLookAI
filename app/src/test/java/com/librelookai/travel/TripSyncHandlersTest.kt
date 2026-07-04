package com.librelookai.travel

import com.google.gson.Gson
import com.librelookai.data.drive.MutationOutcome
import com.librelookai.data.local.PendingMutation
import com.librelookai.data.model.Trip
import com.librelookai.testing.FakeDriveService
import com.librelookai.testing.FakeTripStore
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Queued-mutation invariants for the trip sync handlers (refactor § 3 slice 2 / § 8): the
 * payload-free store re-read (back-to-back edits coalesce), the trip-gone → Permanent rule
 * (the delete queued behind it owns the Drive file), and the stable-id file resolution that
 * makes deletes idempotent.
 */
class TripSyncHandlersTest {

    private val gson = Gson()
    private val drive = FakeDriveService()
    private val trips = FakeTripStore()

    private fun mutation(kind: String, tripId: String) =
        PendingMutation(1L, kind, tripId, null, "", null, 0, null, 0L)

    private fun trip(id: String, name: String = "Trip $id") =
        Trip(id = id, name = name, createdAt = 1L)

    // ---------- trip save ----------

    @Test
    fun `save writes the trip's current store row into the trips folder`() = runTest {
        trips.replaceAll(listOf(trip("a", name = "Latest edit")))
        val handler = TripSaveSyncHandler(drive, trips)

        val outcome = handler.apply(mutation(TRIP_SAVE_KIND, "a"))

        assertEquals(MutationOutcome.Success, outcome)
        assertEquals(gson.toJson(trip("a", name = "Latest edit")), drive.tripJsonById["file-a"])
    }

    @Test
    fun `save of a deleted trip is permanent and writes nothing`() = runTest {
        val handler = TripSaveSyncHandler(drive, trips)

        val outcome = handler.apply(mutation(TRIP_SAVE_KIND, "gone"))

        assertTrue(outcome is MutationOutcome.Permanent)
        assertTrue(drive.tripJsonById.isEmpty())
    }

    @Test
    fun `save retries on a drive failure`() = runTest {
        trips.replaceAll(listOf(trip("a")))
        val failingDrive = object : FakeDriveService() {
            override suspend fun saveTripJson(tripsFolderId: String, tripId: String, json: String): String =
                error("drive down")
        }
        val handler = TripSaveSyncHandler(failingDrive, trips)

        val outcome = handler.apply(mutation(TRIP_SAVE_KIND, "a"))

        assertTrue(outcome is MutationOutcome.Retry)
    }

    // ---------- trip delete ----------

    @Test
    fun `delete resolves the file by stable trip id and removes it`() = runTest {
        drive.putTripJson("a", gson.toJson(trip("a")))
        val handler = TripDeleteSyncHandler(drive)

        val outcome = handler.apply(mutation(TRIP_DELETE_KIND, "a"))

        assertEquals(MutationOutcome.Success, outcome)
        assertTrue(drive.tripJsonById.isEmpty())
        assertEquals(listOf("file-a"), drive.deletedFileIds)
    }

    @Test
    fun `delete of an already-gone file succeeds without a delete call`() = runTest {
        val handler = TripDeleteSyncHandler(drive)

        val outcome = handler.apply(mutation(TRIP_DELETE_KIND, "gone"))

        assertEquals(MutationOutcome.Success, outcome)
        assertTrue(drive.deletedFileIds.isEmpty())
    }

    @Test
    fun `delete retries on a drive failure`() = runTest {
        val failingDrive = object : FakeDriveService() {
            override suspend fun findTripFileId(tripsFolderId: String, tripId: String): String? =
                error("drive down")
        }
        val handler = TripDeleteSyncHandler(failingDrive)

        val outcome = handler.apply(mutation(TRIP_DELETE_KIND, "a"))

        assertTrue(outcome is MutationOutcome.Retry)
    }
}
