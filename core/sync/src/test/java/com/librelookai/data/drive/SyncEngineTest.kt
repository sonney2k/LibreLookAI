package com.librelookai.data.drive

import com.librelookai.data.local.PendingMutation
import com.librelookai.data.local.PendingMutationStore
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Drain-rule tests for the SyncEngine (plain JUnit — the engine only sees the
 * [PendingMutationStore] interface, so an in-memory fake replaces Room). Encodes the § 2
 * contract: strict FIFO, Retry halts, Permanent rolls back and continues, attempt exhaustion
 * rolls back, unknown kinds halt without data loss.
 */
class SyncEngineTest {

    private class FakeStore : PendingMutationStore {
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

        override suspend fun recordFailure(id: Long, error: String) {
            val i = rows.indexOfFirst { it.id == id }
            rows[i] = rows[i].copy(attempts = rows[i].attempts + 1, lastError = error)
        }

        override suspend fun count(): Int = rows.size
    }

    private class ScriptedHandler(
        override val kind: String,
        private val outcomes: MutableList<MutationOutcome> = mutableListOf(),
    ) : MutationHandler {
        val applied = mutableListOf<String>()
        val rolledBack = mutableListOf<String>()

        fun script(vararg o: MutationOutcome) {
            outcomes += o
        }

        override suspend fun apply(mutation: PendingMutation): MutationOutcome {
            applied += mutation.targetId
            return if (outcomes.isEmpty()) MutationOutcome.Success else outcomes.removeAt(0)
        }

        override suspend fun rollback(mutation: PendingMutation) {
            rolledBack += mutation.targetId
        }
    }

    private class FakeScheduler : DrainScheduler {
        var scheduled = 0
        override fun ensureScheduled() {
            scheduled++
        }
    }

    private val scheduler = FakeScheduler()

    private fun engine(store: PendingMutationStore, vararg handlers: MutationHandler) =
        SyncEngine(store, handlers.toSet(), scheduler)

    @Test
    fun `drains the whole queue in FIFO order on success`() = runBlocking {
        val store = FakeStore()
        val handler = ScriptedHandler("k")
        store.enqueue("k", "t1", null, "{}")
        store.enqueue("k", "t2", null, "{}")
        store.enqueue("k", "t3", null, "{}")

        engine(store, handler).drain()

        assertEquals(listOf("t1", "t2", "t3"), handler.applied)
        assertEquals(0, store.count())
    }

    @Test
    fun `retry halts the drain and records the failure`() = runBlocking {
        val store = FakeStore()
        val handler = ScriptedHandler("k")
        handler.script(MutationOutcome.Retry("HTTP 503"))
        store.enqueue("k", "t1", null, "{}")
        store.enqueue("k", "t2", null, "{}")

        engine(store, handler).drain()

        assertEquals(listOf("t1"), handler.applied) // t2 never overtakes t1
        assertEquals(2, store.count())
        val head = store.oldest()!!
        assertEquals(1, head.attempts)
        assertEquals("HTTP 503", head.lastError)
    }

    @Test
    fun `retried mutation succeeds on the next drain`() = runBlocking {
        val store = FakeStore()
        val handler = ScriptedHandler("k")
        handler.script(MutationOutcome.Retry("offline"))
        store.enqueue("k", "t1", null, "{}")

        val engine = engine(store, handler)
        engine.drain()
        assertEquals(1, store.count())

        engine.drain() // scripted outcomes exhausted → Success
        assertEquals(0, store.count())
        assertEquals(listOf("t1", "t1"), handler.applied)
    }

    @Test
    fun `permanent failure rolls back and the drain continues`() = runBlocking {
        val store = FakeStore()
        val handler = ScriptedHandler("k")
        handler.script(MutationOutcome.Permanent("target deleted remotely"))
        store.enqueue("k", "t1", null, "{}")
        store.enqueue("k", "t2", null, "{}")

        engine(store, handler).drain()

        assertEquals(listOf("t1"), handler.rolledBack)
        assertEquals(listOf("t1", "t2"), handler.applied)
        assertEquals(0, store.count())
    }

    @Test
    fun `exhausted attempts roll back instead of blocking the queue forever`() = runBlocking {
        val store = FakeStore()
        val handler = ScriptedHandler("k")
        store.enqueue("k", "t1", null, "{}")
        // Simulate MAX_ATTEMPTS - 1 prior failed drains.
        repeat(SyncEngine.MAX_ATTEMPTS - 1) { store.recordFailure(store.oldest()!!.id, "offline") }
        handler.script(MutationOutcome.Retry("offline"))
        store.enqueue("k", "t2", null, "{}")

        engine(store, handler).drain()

        assertEquals(listOf("t1"), handler.rolledBack)
        assertEquals(0, store.count()) // t1 gave up, t2 drained
        assertEquals(listOf("t1", "t2"), handler.applied)
    }

    @Test
    fun `unknown kind halts the drain without deleting anything`() = runBlocking {
        val store = FakeStore()
        val handler = ScriptedHandler("known")
        store.enqueue("from-a-newer-app-version", "t1", null, "{}")
        store.enqueue("known", "t2", null, "{}")

        engine(store, handler).drain()

        assertTrue(handler.applied.isEmpty())
        assertEquals(2, store.count())
    }

    @Test
    fun `a transiently halted drain self-schedules a backoff re-drain`() = runBlocking {
        val store = FakeStore()
        val handler = ScriptedHandler("k")
        handler.script(MutationOutcome.Retry("HTTP 503"))
        store.enqueue("k", "t1", null, "{}")
        val engine = engine(store, handler).apply { initialRetryDelayMs = 10 }

        engine.drain()
        assertEquals(1, store.count()) // halted

        // The self-scheduled re-drain (scripted outcomes exhausted → Success) empties the queue.
        waitUntil { store.count() == 0 }
        assertEquals(listOf("t1", "t1"), handler.applied)
    }

    @Test
    fun `an unknown-kind halt does not self-retry`() = runBlocking {
        val store = FakeStore()
        val handler = ScriptedHandler("known")
        store.enqueue("mystery", "t1", null, "{}")
        val engine = engine(store, handler).apply { initialRetryDelayMs = 10 }

        engine.drain()
        delay(100)

        assertTrue(handler.applied.isEmpty())
        assertEquals(1, store.count())
    }

    @Test
    fun `a drain over a non-empty queue registers the persistent backstop`() = runBlocking {
        val store = FakeStore()
        val handler = ScriptedHandler("k")
        store.enqueue("k", "t1", null, "{}")

        engine(store, handler).drain()

        // Scheduled while work existed (process death mid-drain must survive), even though
        // the drain then completed in-process — the no-op worker run is the accepted cost.
        assertEquals(1, scheduler.scheduled)
    }

    @Test
    fun `a drain over an empty queue does not schedule anything`() = runBlocking {
        val store = FakeStore()

        engine(store, ScriptedHandler("k")).drain()

        assertEquals(0, scheduler.scheduled)
    }

    private suspend fun waitUntil(timeoutMs: Long = 2_000, condition: suspend () -> Boolean) {
        val deadline = System.currentTimeMillis() + timeoutMs
        while (!condition()) {
            assertTrue("condition not met within ${timeoutMs}ms", System.currentTimeMillis() < deadline)
            delay(10)
        }
    }
}
