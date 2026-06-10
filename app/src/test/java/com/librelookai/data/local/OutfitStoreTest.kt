package com.librelookai.data.local

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.librelookai.data.model.Outfit
import java.io.File
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * Invariant tests for the Room-backed outfit store (runs on the JVM via Robolectric —
 * `./gradlew testDebugUnitTest`). Encodes the rules the legacy `styles_cache_{folderId}.json`
 * files could not enforce: per-folder snapshots, single-home outfits (no duplication of
 * fresh outfits into every folder's cache), folderId restoration on read, and the one-time
 * legacy-file seed.
 */
@RunWith(RobolectricTestRunner::class)
class OutfitStoreTest {

    private lateinit var db: LocalDatabase
    private lateinit var store: OutfitStore
    private val context: Context = ApplicationProvider.getApplicationContext()

    @Before
    fun setUp() {
        db = Room.inMemoryDatabaseBuilder(context, LocalDatabase::class.java).build()
        store = RoomOutfitStore(context, db.outfitDao())
    }

    @After
    fun tearDown() {
        db.close()
        context.filesDir.listFiles()?.filter { it.name.startsWith("styles_cache_") }
            ?.forEach { it.delete() }
    }

    private fun outfit(id: String, name: String = "Outfit $id", loved: Boolean = false) =
        Outfit(id = id, name = name, itemNames = listOf("${id}_cutout.webp"), loved = loved)

    @Test
    fun `replaceFolder writes a snapshot - deletions stay deleted`() = runBlocking {
        store.replaceFolder("f1", listOf(outfit("a"), outfit("b")))
        assertEquals(setOf("a", "b"), store.outfitsFor("f1").map { it.id }.toSet())

        store.replaceFolder("f1", listOf(outfit("b")))
        assertEquals(setOf("b"), store.outfitsFor("f1").map { it.id }.toSet())
    }

    @Test
    fun `outfit fields survive a round trip and folderId is restored from the row`() = runBlocking {
        store.replaceFolder("f1", listOf(outfit("a", name = "Rainy day", loved = true)))
        val loaded = store.outfitsFor("f1").single()
        assertEquals("Rainy day", loaded.name)
        assertEquals(listOf("a_cutout.webp"), loaded.itemNames)
        assertTrue(loaded.loved)
        // Outfit.folderId is @Transient (never serialized) — the store must restore it.
        assertEquals("f1", loaded.folderId)
    }

    @Test
    fun `an outfit lives in exactly one folder - last write re-homes it`() = runBlocking {
        // The legacy files duplicated empty-folderId outfits into every folder's cache;
        // the primary key makes the later snapshot the single home.
        store.replaceFolder("f1", listOf(outfit("a")))
        store.replaceFolder("f2", listOf(outfit("a")))
        assertTrue(store.outfitsFor("f1").isEmpty())
        assertEquals(listOf("a"), store.outfitsFor("f2").map { it.id })
    }

    @Test
    fun `seeds once from a legacy json cache file`() = runBlocking {
        File(context.filesDir, "styles_cache_legacyF.json").writeText(
            """[{"id":"x","name":"Summer","itemNames":["x_cutout.png"],"tags":["beach"],"loved":true}]""",
        )
        val seeded = store.outfitsFor("legacyF").single()
        assertEquals("x", seeded.id)
        assertEquals("Summer", seeded.name)
        assertEquals(listOf("x_cutout.png"), seeded.itemNames)
        assertEquals(listOf("beach"), seeded.tags)
        assertTrue(seeded.loved)
        assertEquals("legacyF", seeded.folderId)

        // Later removals must not be resurrected by the legacy file (seed runs once).
        store.replaceFolder("legacyF", emptyList())
        val freshStore = RoomOutfitStore(context, db.outfitDao())
        assertTrue(freshStore.outfitsFor("legacyF").isEmpty())
    }

    @Test
    fun `a corrupt legacy file degrades to an empty folder`() = runBlocking {
        File(context.filesDir, "styles_cache_bad.json").writeText("not json {")
        assertTrue(store.outfitsFor("bad").isEmpty())
    }
}
