package com.librelookai.data.local

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.librelookai.data.model.TryOn
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
 * Invariant tests for the Room-backed try-on store (runs on the JVM via Robolectric —
 * `./gradlew testDebugUnitTest`). Encodes the rules of the single-global-list cache:
 * snapshot replacement (deletions stay deleted), metadata round trip (incl. provenance),
 * and the one-time legacy `tryons_cache.json` seed.
 */
@RunWith(RobolectricTestRunner::class)
class TryOnStoreTest {

    private lateinit var db: LocalDatabase
    private lateinit var store: TryOnStore
    private val context: Context = ApplicationProvider.getApplicationContext()

    @Before
    fun setUp() {
        db = Room.inMemoryDatabaseBuilder(context, LocalDatabase::class.java).build()
        store = RoomTryOnStore(context, db.tryOnDao())
    }

    @After
    fun tearDown() {
        db.close()
        File(context.filesDir, "tryons_cache.json").delete()
    }

    private fun tryOn(id: String, kind: String = "outfit") = TryOn(
        id = id,
        imageDriveId = "img_$id",
        imageName = "tryon_$id.png",
        itemNames = listOf("${id}_cutout.webp"),
        sourceKind = kind,
        sourceContext = "Context $id",
    )

    @Test
    fun `replaceAll writes a snapshot - deletions stay deleted`() = runBlocking {
        store.replaceAll(listOf(tryOn("a"), tryOn("b")))
        assertEquals(setOf("a", "b"), store.tryOns().map { it.id }.toSet())

        store.replaceAll(listOf(tryOn("b")))
        assertEquals(setOf("b"), store.tryOns().map { it.id }.toSet())
    }

    @Test
    fun `tryon fields survive a round trip - incl provenance`() = runBlocking {
        store.replaceAll(listOf(tryOn("a", kind = "travel").copy(sourceOutfitId = "o1")))
        val loaded = store.tryOns().single()
        assertEquals("img_a", loaded.imageDriveId)
        assertEquals("tryon_a.png", loaded.imageName)
        assertEquals(listOf("a_cutout.webp"), loaded.itemNames)
        assertEquals("travel", loaded.sourceKind)
        assertEquals("Context a", loaded.sourceContext)
        assertEquals("o1", loaded.sourceOutfitId)
    }

    @Test
    fun `seeds once from the legacy json cache file`() = runBlocking {
        // A pre-sourceKind entry: Gson back-fills the "outfit" default (the ViewModel's
        // migrateSource then re-reads item-only entries as "wardrobe" on load).
        File(context.filesDir, "tryons_cache.json").writeText(
            """[{"id":"x","imageDriveId":"img_x","imageName":"old.png","itemNames":["x_cutout.png"]}]""",
        )
        val seeded = store.tryOns().single()
        assertEquals("x", seeded.id)
        assertEquals("img_x", seeded.imageDriveId)
        assertEquals(listOf("x_cutout.png"), seeded.itemNames)
        assertEquals("outfit", seeded.sourceKind)

        // Later removals must not be resurrected by the legacy file (seed runs once).
        store.replaceAll(emptyList())
        val freshStore = RoomTryOnStore(context, db.tryOnDao())
        assertTrue(freshStore.tryOns().isEmpty())
    }

    @Test
    fun `a corrupt legacy file degrades to an empty list`() = runBlocking {
        File(context.filesDir, "tryons_cache.json").writeText("not json {")
        assertTrue(store.tryOns().isEmpty())
    }
}
