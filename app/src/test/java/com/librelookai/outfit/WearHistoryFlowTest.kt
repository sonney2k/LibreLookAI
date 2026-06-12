package com.librelookai.outfit

import com.librelookai.data.local.OutfitEventStore
import com.librelookai.data.model.Location
import com.librelookai.data.model.OutfitEvent
import com.librelookai.data.session.ClosetSession
import com.librelookai.data.session.ClosetSessionHolder
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.yield
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Scope rules for the store-backed wear-history feed (refactor § 5 slice 3) — the same arms
 * OutfitEventsViewModel derives its load scope from: All locations merges every closet, an
 * active closet narrows to its folder, an unknown active id keeps the previous scope.
 */
class WearHistoryFlowTest {

    private val home = Location(name = "Home", folderId = "fHome")
    private val office = Location(name = "Office", folderId = "fOffice")

    private fun event(id: String) = OutfitEvent(id = id, date = "2026-06-01")

    private class FakeEventStore(
        private val byFolder: Map<String, List<OutfitEvent>>,
    ) : OutfitEventStore {
        override suspend fun eventsFor(folderId: String) = byFolder[folderId].orEmpty()
        override fun observeFolders(folderIds: List<String>): Flow<List<OutfitEvent>> =
            flowOf(folderIds.flatMap { byFolder[it].orEmpty() })
        override suspend fun replaceFolder(folderId: String, events: List<OutfitEvent>) = Unit
        override suspend fun hasFolder(folderId: String) = true
    }

    private val store = FakeEventStore(
        mapOf("fHome" to listOf(event("h1")), "fOffice" to listOf(event("o1"))),
    )

    @Test
    fun `All locations merges every closet's events`() = runBlocking {
        val holder = ClosetSessionHolder()
        holder.setClosets(listOf(home, office), ClosetSession.ALL_LOCATIONS_ID, null)
        val events = withTimeout(2_000) { wearHistoryFlow(holder, store).first() }
        assertEquals(setOf("h1", "o1"), events.map { it.id }.toSet())
    }

    @Test
    fun `an active closet narrows the scope to its folder`() = runBlocking {
        val holder = ClosetSessionHolder()
        holder.setClosets(listOf(home, office), "fOffice", null)
        val events = withTimeout(2_000) { wearHistoryFlow(holder, store).first() }
        assertEquals(listOf("o1"), events.map { it.id })
    }

    @Test
    fun `an unknown active id keeps the previous scope`() = runBlocking {
        val holder = ClosetSessionHolder()
        holder.setClosets(listOf(home, office), "fOffice", null)
        val emissions = mutableListOf<List<OutfitEvent>>()
        val job = launch { wearHistoryFlow(holder, store).collect { emissions += it } }
        withTimeout(2_000) { while (emissions.isEmpty()) yield() }

        // Not "All", not a known closet — the old bridge's silent arm: no scope change.
        holder.setClosets(listOf(home, office), "gone", null)
        repeat(10) { yield() }
        assertEquals(1, emissions.size)

        // A real scope change resumes emissions.
        holder.setClosets(listOf(home, office), ClosetSession.ALL_LOCATIONS_ID, null)
        withTimeout(2_000) { while (emissions.size < 2) yield() }
        assertEquals(setOf("h1", "o1"), emissions.last().map { it.id }.toSet())
        job.cancel()
    }
}
