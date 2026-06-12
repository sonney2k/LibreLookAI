package com.librelookai.data.local

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.librelookai.gemini.ClothingTags
import java.io.File
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * Invariant tests for the Room-backed wardrobe item store (runs on the JVM via Robolectric —
 * `./gradlew testDebugUnitTest`). These encode the optimistic-write cache rules that used to be
 * prose in CLAUDE.md: per-folder snapshots, stale-id replacement during cutout processing,
 * single-home moves between closets, and the one-time seed from a legacy
 * `wardrobe_cache_{folderId}.json` file.
 */
@RunWith(RobolectricTestRunner::class)
class WardrobeItemStoreTest {

    private lateinit var db: LocalDatabase
    private lateinit var store: WardrobeItemStore
    private val context: Context = ApplicationProvider.getApplicationContext()

    @Before
    fun setUp() {
        db = Room.inMemoryDatabaseBuilder(context, LocalDatabase::class.java).build()
        store = RoomWardrobeItemStore(context, db.wardrobeItemDao())
    }

    @After
    fun tearDown() {
        db.close()
        context.filesDir.listFiles()?.filter { it.name.startsWith("wardrobe_cache_") }
            ?.forEach { it.delete() }
    }

    private fun item(id: String, name: String = "${id}_cutout.webp", tags: ClothingTags? = null) =
        CachedWardrobeItem(driveId = id, name = name, tags = tags, createdTimeMs = 42L)

    @Test
    fun `replaceFolder writes a snapshot and marks the folder cached`() = runBlocking {
        assertFalse(store.hasFolder("f1"))
        store.replaceFolder("f1", listOf(item("a"), item("b")))
        assertTrue(store.hasFolder("f1"))
        assertEquals(setOf("a", "b"), store.itemsFor("f1").map { it.driveId }.toSet())

        store.replaceFolder("f1", listOf(item("b"), item("c")))
        assertEquals(setOf("b", "c"), store.itemsFor("f1").map { it.driveId }.toSet())
    }

    @Test
    fun `tags survive a round trip`() = runBlocking {
        val tags = ClothingTags(type = "jacket", colors = listOf("navy", "white"))
        store.replaceFolder("f1", listOf(item("a", tags = tags)))
        val loaded = store.itemsFor("f1").single().tags
        assertEquals("jacket", loaded?.type)
        assertEquals(listOf("navy", "white"), loaded?.colors)
    }

    @Test
    fun `upsert drops the stale raw-upload entry`() = runBlocking {
        store.replaceFolder("f1", listOf(item("raw1"), item("other")))
        store.upsert("f1", item("cutout1"), staleDriveId = "raw1")
        assertEquals(setOf("other", "cutout1"), store.itemsFor("f1").map { it.driveId }.toSet())
    }

    @Test
    fun `an item lives in exactly one folder - upsert re-homes it`() = runBlocking {
        store.replaceFolder("f1", listOf(item("a")))
        store.addAll("f2", listOf(item("a")))
        assertTrue(store.itemsFor("f1").isEmpty())
        assertEquals(listOf("a"), store.itemsFor("f2").map { it.driveId })
    }

    @Test
    fun `remove only touches ids homed in that folder`() = runBlocking {
        store.replaceFolder("f1", listOf(item("a")))
        store.replaceFolder("f2", listOf(item("b")))
        store.remove("f1", setOf("a", "b"))
        assertTrue(store.itemsFor("f1").isEmpty())
        assertEquals(listOf("b"), store.itemsFor("f2").map { it.driveId })
    }

    @Test
    fun `seeds once from a legacy json cache file`() = runBlocking {
        File(context.filesDir, "wardrobe_cache_legacyF.json").writeText(
            """{"items":[{"driveId":"x","name":"x_cutout.png","tags":{"type":"shirt"},
               "originalDriveId":"xo","sidecarDriveId":"xs","createdTimeMs":7}]}""",
        )
        assertTrue(store.hasFolder("legacyF"))
        val seeded = store.itemsFor("legacyF").single()
        assertEquals("x", seeded.driveId)
        assertEquals("x_cutout.png", seeded.name)
        assertEquals("shirt", seeded.tags?.type)
        assertEquals("xo", seeded.originalDriveId)
        assertEquals("xs", seeded.sidecarDriveId)
        assertEquals(7L, seeded.createdTimeMs)

        // Later removals must not be resurrected by the legacy file (seed runs once).
        store.remove("legacyF", setOf("x"))
        val freshStore = RoomWardrobeItemStore(context, db.wardrobeItemDao())
        assertTrue(freshStore.itemsFor("legacyF").isEmpty())
        assertTrue(freshStore.hasFolder("legacyF"))
    }

    @Test
    fun `clearFolder drops items, marker and the legacy file`() = runBlocking {
        val legacy = File(context.filesDir, "wardrobe_cache_f9.json")
        legacy.writeText("""{"items":[{"driveId":"z","name":"z_cutout.webp"}]}""")
        assertEquals(1, store.itemsFor("f9").size)

        store.clearFolder("f9")
        assertFalse(store.hasFolder("f9"))
        assertTrue(store.itemsFor("f9").isEmpty())
        assertFalse(legacy.exists())
    }

    @Test
    fun `a corrupt legacy file degrades to an empty folder`() = runBlocking {
        File(context.filesDir, "wardrobe_cache_bad.json").writeText("not json {")
        assertTrue(store.itemsFor("bad").isEmpty())
        assertFalse(store.hasFolder("bad"))
        assertNull(store.itemsFor("bad").firstOrNull())
    }

    @Test
    fun `find returns the item with its owning folder`() = runBlocking {
        store.replaceFolder("f1", listOf(item("a")))
        store.replaceFolder("f2", listOf(item("b")))

        val (folderId, found) = store.find("b")!!
        assertEquals("f2", folderId)
        assertEquals("b", found.driveId)
        assertNull(store.find("missing"))

        // A move re-homes the item — find follows it.
        store.addAll("f1", listOf(item("b")))
        assertEquals("f1", store.find("b")!!.first)
    }

    @Test
    fun `setSidecarId stamps the row without touching other fields`() = runBlocking {
        store.replaceFolder("f1", listOf(item("a", tags = ClothingTags(type = "jacket"))))

        store.setSidecarId("a", "sc123")

        val (_, found) = store.find("a")!!
        assertEquals("sc123", found.sidecarDriveId)
        assertEquals("jacket", found.tags?.type)
        // Unknown id is a no-op, not a crash.
        store.setSidecarId("missing", "sc999")
    }

    @Test
    fun `observeItems keys current rows by owning folder and follows writes`() = runBlocking {
        store.replaceFolder("f1", listOf(item("a")))
        store.replaceFolder("f2", listOf(item("b")))
        val flow = store.observeItems(listOf("f1", "f2"))

        val first = withTimeout(5_000) { flow.first() }
        assertEquals(listOf("a"), first.getValue("f1").map { it.driveId })
        assertEquals(listOf("b"), first.getValue("f2").map { it.driveId })

        // A write while collecting pushes a fresh emission (Room invalidation).
        val updated = withTimeout(5_000) {
            val next = async { flow.first { it["f1"]?.size == 2 } }
            store.addAll("f1", listOf(item("c")))
            next.await()
        }
        assertEquals(setOf("a", "c"), updated.getValue("f1").map { it.driveId }.toSet())
    }

    @Test
    fun `observeItems seeds legacy folders and scopes to the requested ids`() = runBlocking {
        File(context.filesDir, "wardrobe_cache_obsF.json").writeText(
            """{"items":[{"driveId":"x","name":"x_cutout.webp"}]}""",
        )
        store.replaceFolder("outside", listOf(item("o")))

        val first = withTimeout(5_000) { store.observeItems(listOf("obsF")).first() }
        assertEquals(listOf("x"), first.getValue("obsF").map { it.driveId })
        assertFalse("outside" in first)

        // No requested folders → one empty emission (no SQL `IN ()`).
        assertTrue(store.observeItems(emptyList()).first().isEmpty())
    }
}
