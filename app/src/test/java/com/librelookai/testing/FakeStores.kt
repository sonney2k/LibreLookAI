package com.librelookai.testing

import com.librelookai.data.local.CachedWardrobeItem
import com.librelookai.data.local.OutfitEventStore
import com.librelookai.data.local.OutfitStore
import com.librelookai.data.local.PendingMutation
import com.librelookai.data.local.PendingMutationStore
import com.librelookai.data.local.TripStore
import com.librelookai.data.local.TryOnStore
import com.librelookai.data.local.WardrobeItemStore
import com.librelookai.data.model.Outfit
import com.librelookai.data.model.OutfitEvent
import com.librelookai.data.model.Trip
import com.librelookai.data.model.TryOn
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.map

/**
 * In-memory store fakes for fake-based repository/handler tests (refactor § 3 / § 8).
 * Each mirrors its Room-backed impl's key invariant (one folder per drive/outfit id) but skips
 * the legacy-seed machinery — tests write their own starting rows.
 */

class FakeWardrobeItemStore : WardrobeItemStore {
    private val folders = linkedMapOf<String, MutableList<CachedWardrobeItem>>()
    private val changes = MutableStateFlow(0L)

    /** Homes [items] under [folderId], removing them from any other folder (the store invariant). */
    private fun home(folderId: String, items: List<CachedWardrobeItem>) {
        val ids = items.map { it.driveId }.toSet()
        folders.values.forEach { list -> list.removeAll { it.driveId in ids } }
        folders.getOrPut(folderId) { mutableListOf() } += items
        changes.value++
    }

    override suspend fun hasFolder(folderId: String): Boolean = folders.containsKey(folderId)

    override suspend fun itemsFor(folderId: String): List<CachedWardrobeItem> =
        folders[folderId].orEmpty().toList()

    override fun observeItems(folderIds: List<String>): Flow<Map<String, List<CachedWardrobeItem>>> =
        changes.map {
            folderIds.associateWith { fid -> folders[fid].orEmpty().toList() }
                .filterValues { it.isNotEmpty() }
        }

    override suspend fun replaceFolder(folderId: String, items: List<CachedWardrobeItem>) {
        folders[folderId] = mutableListOf()
        home(folderId, items)
    }

    override suspend fun upsert(folderId: String, item: CachedWardrobeItem, staleDriveId: String?) {
        staleDriveId?.let { stale -> folders.values.forEach { l -> l.removeAll { it.driveId == stale } } }
        home(folderId, listOf(item))
    }

    override suspend fun addAll(folderId: String, items: List<CachedWardrobeItem>) =
        home(folderId, items)

    override suspend fun remove(folderId: String, ids: Set<String>) {
        folders[folderId]?.removeAll { it.driveId in ids }
        changes.value++
    }

    override suspend fun find(driveId: String): Pair<String, CachedWardrobeItem>? {
        for ((fid, list) in folders) list.firstOrNull { it.driveId == driveId }?.let { return fid to it }
        return null
    }

    override suspend fun setSidecarId(driveId: String, sidecarId: String) {
        for (list in folders.values) {
            val idx = list.indexOfFirst { it.driveId == driveId }
            if (idx >= 0) {
                list[idx] = list[idx].copy(sidecarDriveId = sidecarId)
                changes.value++
                return
            }
        }
    }

    override suspend fun clearFolder(folderId: String) {
        folders.remove(folderId)
        changes.value++
    }
}

class FakeOutfitStore : OutfitStore {
    private val folders = linkedMapOf<String, List<Outfit>>()
    private val changes = MutableStateFlow(0L)

    override suspend fun outfitsFor(folderId: String): List<Outfit> = folders[folderId].orEmpty()

    override fun observeFolders(folderIds: List<String>): Flow<List<Outfit>> =
        changes.map { folderIds.flatMap { fid -> folders[fid].orEmpty() } }

    /** Mirrors the Room impl: the outfit-id primary key re-homes an outfit written under a new
     *  folder, and reads restore the `@Transient folderId` from the row. */
    override suspend fun replaceFolder(folderId: String, outfits: List<Outfit>) {
        val ids = outfits.map { it.id }.toSet()
        folders.keys.forEach { fid -> folders[fid] = folders[fid].orEmpty().filter { it.id !in ids } }
        folders[folderId] = outfits.map { it.copy(folderId = folderId) }
        changes.value++
    }

    override suspend fun hasFolder(folderId: String): Boolean = folders.containsKey(folderId)
}

class FakeOutfitEventStore : OutfitEventStore {
    private val folders = linkedMapOf<String, List<OutfitEvent>>()
    private val changes = MutableStateFlow(0L)

    override suspend fun eventsFor(folderId: String): List<OutfitEvent> = folders[folderId].orEmpty()

    override fun observeFolders(folderIds: List<String>): Flow<List<OutfitEvent>> =
        changes.map { folderIds.flatMap { fid -> folders[fid].orEmpty() } }

    /** Mirrors the Room impl: the event-id primary key re-homes an event written under a new
     *  folder (the calendar's single-target persist relies on it). */
    override suspend fun replaceFolder(folderId: String, events: List<OutfitEvent>) {
        val ids = events.map { it.id }.toSet()
        folders.keys.forEach { fid -> folders[fid] = folders[fid].orEmpty().filter { it.id !in ids } }
        folders[folderId] = events
        changes.value++
    }

    override suspend fun hasFolder(folderId: String): Boolean = folders.containsKey(folderId)
}

class FakeTripStore : TripStore {
    val flow = MutableStateFlow<List<Trip>>(emptyList())

    override suspend fun trips(): List<Trip> = flow.value

    override fun observeTrips(): Flow<List<Trip>> = flow

    override suspend fun replaceAll(trips: List<Trip>) {
        flow.value = trips
    }
}

class FakeTryOnStore : TryOnStore {
    val flow = MutableStateFlow<List<TryOn>>(emptyList())

    override suspend fun tryOns(): List<TryOn> = flow.value

    override fun observeTryOns(): Flow<List<TryOn>> = flow

    override suspend fun replaceAll(tryOns: List<TryOn>) {
        flow.value = tryOns
    }
}

/**
 * In-memory [PendingMutationStore] (promoted out of `TripsRepositoryTest` /
 * `WardrobeBulkAiUseCasesTest`): [rows] exposes the queue for assertions; [recordFailure]
 * is a no-op — engine retry/backoff behavior is the handler suites' concern.
 */
class FakeMutationStore : PendingMutationStore {
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
