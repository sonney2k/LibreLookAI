package com.librelookai.data.local

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * Invariant tests for the pending-mutation queue (runs on the JVM via Robolectric —
 * `./gradlew testDebugUnitTest`). Encodes the FIFO contract the `SyncEngine` drain relies on:
 * `oldest()` returns enqueue order, failures don't reorder, removal frees the head.
 */
@RunWith(RobolectricTestRunner::class)
class PendingMutationStoreTest {

    private lateinit var db: LocalDatabase
    private lateinit var store: PendingMutationStore
    private val context: Context = ApplicationProvider.getApplicationContext()

    @Before
    fun setUp() {
        db = Room.inMemoryDatabaseBuilder(context, LocalDatabase::class.java).build()
        store = RoomPendingMutationStore(db.pendingMutationDao())
    }

    @After
    fun tearDown() {
        db.close()
    }

    @Test
    fun `oldest follows enqueue order`() = runBlocking {
        val first = store.enqueue("wardrobe.tagEdit", "item1", "folderA", "{}")
        store.enqueue("wardrobe.delete", "item2", "folderA", "{}")

        assertEquals(first, store.oldest()?.id)
        assertEquals(2, store.count())
    }

    @Test
    fun `remove frees the head for the next mutation`() = runBlocking {
        val first = store.enqueue("a", "t1", null, "{}")
        val second = store.enqueue("b", "t2", null, "{}")

        store.remove(first)
        assertEquals(second, store.oldest()?.id)

        store.remove(second)
        assertNull(store.oldest())
        assertEquals(0, store.count())
    }

    @Test
    fun `recordFailure increments attempts and keeps order`() = runBlocking {
        val first = store.enqueue("a", "t1", null, "{}")
        store.enqueue("b", "t2", null, "{}")

        store.recordFailure(first, "HTTP 503")
        store.recordFailure(first, "HTTP 503 again")

        val head = store.oldest()!!
        assertEquals(first, head.id)
        assertEquals(2, head.attempts)
        assertEquals("HTTP 503 again", head.lastError)
    }

    @Test
    fun `fields survive a round trip`() = runBlocking {
        store.enqueue(
            kind = "outfit.save",
            targetId = "outfit42",
            folderId = "folderB",
            payload = """{"name":"Summer"}""",
            rollback = """{"name":"Spring"}""",
        )

        val row = store.all().single()
        assertEquals("outfit.save", row.kind)
        assertEquals("outfit42", row.targetId)
        assertEquals("folderB", row.folderId)
        assertEquals("""{"name":"Summer"}""", row.payload)
        assertEquals("""{"name":"Spring"}""", row.rollback)
        assertEquals(0, row.attempts)
        assertNull(row.lastError)
    }
}
