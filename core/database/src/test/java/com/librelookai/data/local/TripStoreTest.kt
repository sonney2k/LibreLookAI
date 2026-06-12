package com.librelookai.data.local

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.librelookai.data.model.Trip
import java.io.File
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * Invariant tests for the Room-backed trip store (runs on the JVM via Robolectric —
 * `./gradlew testDebugUnitTest`). Encodes the rules of the single-global-list cache:
 * snapshot replacement (deletions stay deleted), full model round trip, and the one-time
 * legacy `trips_cache.json` seed.
 */
@RunWith(RobolectricTestRunner::class)
class TripStoreTest {

    private lateinit var db: LocalDatabase
    private lateinit var store: TripStore
    private val context: Context = ApplicationProvider.getApplicationContext()

    @Before
    fun setUp() {
        db = Room.inMemoryDatabaseBuilder(context, LocalDatabase::class.java).build()
        store = RoomTripStore(context, db.tripDao())
    }

    @After
    fun tearDown() {
        db.close()
        File(context.filesDir, "trips_cache.json").delete()
    }

    private fun trip(id: String, name: String = "Trip $id") =
        Trip(id = id, name = name, destination = "Lisbon", days = 3, outfitIds = listOf("o1", "o2", "o3"))

    @Test
    fun `replaceAll writes a snapshot - deletions stay deleted`() = runBlocking {
        store.replaceAll(listOf(trip("a"), trip("b")))
        assertEquals(setOf("a", "b"), store.trips().map { it.id }.toSet())

        store.replaceAll(listOf(trip("b")))
        assertEquals(setOf("b"), store.trips().map { it.id }.toSet())
    }

    @Test
    fun `trip fields survive a round trip`() = runBlocking {
        val original = trip("a", name = "City break").copy(
            vibes = setOf("smart casual"),
            packedItemIds = setOf("d1"),
            packedExtras = setOf("sunscreen"),
            extraItems = listOf("sunscreen", "adapter"),
        )
        store.replaceAll(listOf(original))
        val loaded = store.trips().single()
        assertEquals("City break", loaded.name)
        assertEquals("Lisbon", loaded.destination)
        assertEquals(3, loaded.days)
        assertEquals(listOf("o1", "o2", "o3"), loaded.outfitIds)
        assertEquals(setOf("smart casual"), loaded.vibes)
        assertEquals(setOf("d1"), loaded.packedItemIds)
        assertEquals(setOf("sunscreen"), loaded.packedExtras)
        assertEquals(listOf("sunscreen", "adapter"), loaded.extraItems)
    }

    @Test
    fun `seeds once from the legacy json cache file`() = runBlocking {
        File(context.filesDir, "trips_cache.json").writeText(
            """[{"id":"x","name":"Summer trip","destination":"Rome","days":2,"outfitIds":["o9"]}]""",
        )
        val seeded = store.trips().single()
        assertEquals("x", seeded.id)
        assertEquals("Summer trip", seeded.name)
        assertEquals("Rome", seeded.destination)
        assertEquals(2, seeded.days)
        assertEquals(listOf("o9"), seeded.outfitIds)

        // Later removals must not be resurrected by the legacy file (seed runs once).
        store.replaceAll(emptyList())
        val freshStore = RoomTripStore(context, db.tripDao())
        assertTrue(freshStore.trips().isEmpty())
    }

    @Test
    fun `a corrupt legacy file degrades to an empty list`() = runBlocking {
        File(context.filesDir, "trips_cache.json").writeText("not json {")
        assertTrue(store.trips().isEmpty())
    }

    @Test
    fun `observeTrips emits the current list and follows writes`() = runBlocking {
        store.replaceAll(listOf(trip("a")))
        val flow = store.observeTrips()

        assertEquals(listOf("a"), withTimeout(5_000) { flow.first() }.map { it.id })

        // A write while collecting pushes a fresh emission (Room invalidation).
        val updated = withTimeout(5_000) {
            val next = async { flow.first { it.size == 2 } }
            store.replaceAll(listOf(trip("a"), trip("b")))
            next.await()
        }
        assertEquals(setOf("a", "b"), updated.map { it.id }.toSet())
    }
}
