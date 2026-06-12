package com.librelookai.data.local

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.librelookai.data.model.OutfitEvent
import com.librelookai.data.model.WearSource
import java.io.File
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * Invariant tests for the Room-backed outfit-event store (runs on the JVM via Robolectric —
 * `./gradlew testDebugUnitTest`): per-folder snapshots, the one-time legacy-file seed, and the
 * Gson default back-fill that keeps pre-snapshot events loadable.
 */
@RunWith(RobolectricTestRunner::class)
class OutfitEventStoreTest {

    private lateinit var db: LocalDatabase
    private lateinit var store: OutfitEventStore
    private val context: Context = ApplicationProvider.getApplicationContext()

    @Before
    fun setUp() {
        db = Room.inMemoryDatabaseBuilder(context, LocalDatabase::class.java).build()
        store = RoomOutfitEventStore(context, db.outfitEventDao())
    }

    @After
    fun tearDown() {
        db.close()
        context.filesDir.listFiles()?.filter { it.name.startsWith("outfit_events_cache_") }
            ?.forEach { it.delete() }
    }

    private fun event(id: String, date: String = "2026-06-01", loved: Boolean = false) =
        OutfitEvent(
            id = id,
            outfitId = "o-$id",
            date = date,
            loved = loved,
            source = WearSource.AI_SUGGESTED,
        )

    @Test
    fun `replaceFolder writes a snapshot - deletions stay deleted`() = runBlocking {
        store.replaceFolder("f1", listOf(event("a"), event("b")))
        assertEquals(setOf("a", "b"), store.eventsFor("f1").map { it.id }.toSet())

        store.replaceFolder("f1", listOf(event("b")))
        assertEquals(setOf("b"), store.eventsFor("f1").map { it.id }.toSet())
    }

    @Test
    fun `event fields survive a round trip`() = runBlocking {
        store.replaceFolder("f1", listOf(event("a", date = "2026-01-15", loved = true)))
        val loaded = store.eventsFor("f1").single()
        assertEquals("o-a", loaded.outfitId)
        assertEquals("2026-01-15", loaded.date)
        assertTrue(loaded.loved)
        assertEquals(WearSource.AI_SUGGESTED, loaded.source)
    }

    @Test
    fun `seeds once from a legacy json cache file - including the styleId alias`() = runBlocking {
        // "styleId" is the pre-rename JSON key for outfitId; old cache files may still use it.
        File(context.filesDir, "outfit_events_cache_legacyF.json").writeText(
            """[{"id":"x","styleId":"old-style","date":"2025-12-24","loved":true}]""",
        )
        val seeded = store.eventsFor("legacyF").single()
        assertEquals("x", seeded.id)
        assertEquals("old-style", seeded.outfitId)
        assertEquals("2025-12-24", seeded.date)
        assertTrue(seeded.loved)
        // Fields newer than the cached event are back-filled with defaults.
        assertEquals(WearSource.MANUAL, seeded.source)

        // Later removals must not be resurrected by the legacy file (seed runs once).
        store.replaceFolder("legacyF", emptyList())
        val freshStore = RoomOutfitEventStore(context, db.outfitEventDao())
        assertTrue(freshStore.eventsFor("legacyF").isEmpty())
    }

    @Test
    fun `a corrupt legacy file degrades to an empty folder`() = runBlocking {
        File(context.filesDir, "outfit_events_cache_bad.json").writeText("not json {")
        assertTrue(store.eventsFor("bad").isEmpty())
    }

    @Test
    fun `hasFolder is false until the folder is cached - the syncFolder wipe guard`() = runBlocking {
        // A never-cached folder must read as "unknown", not "no wears": the outfitEvent.syncFolder
        // handler refuses to write Drive for it (writing [] would destroy the calendar history).
        assertFalse(store.hasFolder("fresh"))

        store.replaceFolder("fresh", emptyList())
        assertTrue(store.hasFolder("fresh"))
    }

    @Test
    fun `hasFolder sees a legacy-seeded folder`() = runBlocking {
        File(context.filesDir, "outfit_events_cache_seeded.json").writeText("""[{"id":"x"}]""")
        assertTrue(store.hasFolder("seeded"))
    }

    @Test
    fun `observeFolders emits the merged events and follows writes`() = runBlocking {
        store.replaceFolder("f1", listOf(event("e1")))
        store.replaceFolder("f2", listOf(event("e2")))
        val flow = store.observeFolders(listOf("f1", "f2"))

        val first = withTimeout(5_000) { flow.first() }
        assertEquals(setOf("e1", "e2"), first.map { it.id }.toSet())

        // A write while collecting pushes a fresh emission (Room invalidation).
        val updated = withTimeout(5_000) {
            val next = async { flow.first { it.size == 3 } }
            store.replaceFolder("f1", listOf(event("e1"), event("e3")))
            next.await()
        }
        assertEquals(setOf("e1", "e2", "e3"), updated.map { it.id }.toSet())
    }
}
