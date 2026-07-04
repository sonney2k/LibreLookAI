package com.librelookai.travel

import com.google.gson.Gson
import com.librelookai.data.drive.DrainScheduler
import com.librelookai.data.drive.SyncEngine
import com.librelookai.data.local.PendingMutation
import com.librelookai.data.local.PendingMutationStore
import com.librelookai.data.model.Trip
import com.librelookai.testing.FakeDriveService
import com.librelookai.testing.FakeTripStore
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * The first fake-based repository test (refactor § 3 slice 1 / § 8): [TripsRepository] over
 * [FakeDriveService] + in-memory store fakes — the Drive reconcile, its single-flight +
 * memoized-on-success semantics, and the local-first save/delete funnels become tested
 * invariants instead of doc prose.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class TripsRepositoryTest {

    private class FakeMutationStore : PendingMutationStore {
        val rows = mutableListOf<PendingMutation>()
        private var nextId = 1L
        override suspend fun enqueue(
            kind: String,
            targetId: String,
            folderId: String?,
            payload: String,
            rollback: String?,
        ): Long {
            val id = nextId++
            rows += PendingMutation(id, kind, targetId, folderId, payload, rollback, 0, null, id)
            return id
        }

        override suspend fun oldest(): PendingMutation? = rows.minByOrNull { it.id }
        override suspend fun all(): List<PendingMutation> = rows.sortedBy { it.id }
        override suspend fun remove(id: Long) {
            rows.removeAll { it.id == id }
        }

        override suspend fun recordFailure(id: Long, error: String) {}
        override suspend fun count(): Int = rows.size
    }

    private val gson = Gson()
    private val drive = FakeDriveService()
    private val tripStore = FakeTripStore()
    private val mutationStore = FakeMutationStore()
    // No trip handlers registered: drain() halts on the unknown kind, leaving the queued rows
    // observable (the § 2 engine contract — unknown kinds halt without data loss).
    private val syncEngine = SyncEngine(mutationStore, emptySet(), object : DrainScheduler {
        override fun ensureScheduled() {}
    })

    private fun repo() = TripsRepository(drive, tripStore, mutationStore, syncEngine)

    private fun trip(id: String, createdAt: Long, outfitIds: List<String> = emptyList()) =
        Trip(id = id, name = "Trip $id", createdAt = createdAt, outfitIds = outfitIds)

    @Before
    fun setUp() {
        Dispatchers.setMain(StandardTestDispatcher())
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }

    @Test
    fun `refreshFromDrive replaces the store with parsed trips, newest first`() = runTest {
        drive.putTripJson("a", gson.toJson(trip("a", createdAt = 1L)))
        drive.putTripJson("b", gson.toJson(trip("b", createdAt = 2L)))

        repo().refreshFromDrive()

        assertEquals(listOf("b", "a"), tripStore.flow.value.map { it.id })
    }

    @Test
    fun `refreshFromDrive is memoized on success`() = runTest {
        drive.putTripJson("a", gson.toJson(trip("a", createdAt = 1L)))
        val repo = repo()

        repo.refreshFromDrive()
        repo.refreshFromDrive()

        assertEquals(1, drive.listTripFilesCalls)
    }

    @Test
    fun `a failed refresh rethrows and the next call retries`() = runTest {
        var failures = 0
        drive.onListTripFiles = {
            if (failures == 0) {
                failures++
                error("drive down")
            }
        }
        drive.putTripJson("a", gson.toJson(trip("a", createdAt = 1L)))
        val repo = repo()

        val first = runCatching { repo.refreshFromDrive() }
        assertTrue(first.isFailure)
        assertEquals(emptyList<Trip>(), tripStore.flow.value)

        repo.refreshFromDrive()

        assertEquals(2, drive.listTripFilesCalls)
        assertEquals(listOf("a"), tripStore.flow.value.map { it.id })
    }

    @Test
    fun `concurrent refreshes join the in-flight listing (single-flight)`() = runTest {
        val gate = CompletableDeferred<Unit>()
        drive.onListTripFiles = { gate.await() }
        drive.putTripJson("a", gson.toJson(trip("a", createdAt = 1L)))
        val repo = repo()

        val first = launch { repo.refreshFromDrive() }
        val second = launch { repo.refreshFromDrive() }
        advanceUntilIdle() // both callers reach the gate / join the shared Deferred
        gate.complete(Unit)
        first.join()
        second.join()

        assertEquals(1, drive.listTripFilesCalls)
        assertEquals(listOf("a"), tripStore.flow.value.map { it.id })
    }

    @Test
    fun `saveTrip is local-first - store row plus a queued save mutation`() = runTest {
        val repo = repo()
        tripStore.replaceAll(listOf(trip("old", createdAt = 1L)))
        advanceUntilIdle() // let the derived trips StateFlow catch up before the mutator reads it

        repo.saveTrip(trip("new", createdAt = 2L))

        assertEquals(listOf("new", "old"), tripStore.flow.value.map { it.id })
        assertEquals(listOf(TRIP_SAVE_KIND), mutationStore.rows.map { it.kind })
        assertEquals("new", mutationStore.rows.single().targetId)
    }

    @Test
    fun `deleteTrips removes rows, queues one delete per trip, returns the outfit-id union`() = runTest {
        val repo = repo()
        tripStore.replaceAll(
            listOf(
                trip("a", createdAt = 1L, outfitIds = listOf("o1", "o2")),
                trip("b", createdAt = 2L, outfitIds = listOf("o2", "o3")),
                trip("keep", createdAt = 3L),
            ),
        )
        advanceUntilIdle()

        val outfitIds = repo.deleteTrips(listOf("a", "b"))

        assertEquals(listOf("keep"), tripStore.flow.value.map { it.id })
        assertEquals(listOf(TRIP_DELETE_KIND, TRIP_DELETE_KIND), mutationStore.rows.map { it.kind })
        assertEquals(setOf("a", "b"), mutationStore.rows.map { it.targetId }.toSet())
        assertEquals(setOf("o1", "o2", "o3"), outfitIds.toSet())
    }
}
