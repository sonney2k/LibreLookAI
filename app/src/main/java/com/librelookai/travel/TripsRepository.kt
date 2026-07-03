package com.librelookai.travel

import com.google.gson.Gson
import com.librelookai.data.drive.DriveRepository
import com.librelookai.data.drive.SyncEngine
import com.librelookai.data.drive.getOrCreateTripsFolder
import com.librelookai.data.drive.listTripFiles
import com.librelookai.data.drive.loadTripJson
import com.librelookai.data.local.PendingMutationStore
import com.librelookai.data.local.TripStore
import com.librelookai.data.model.Trip
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn

/**
 * Shared trips data + persistence (refactor § 5 slice 9 — the [OutfitsRepository]/
 * `TryOnRepository` pattern, extracted as the foundation for a destination-scoped trip-viewer
 * VM): owns the store-derived [trips] flow (§ 5 slice 4d), the Drive Phase-2 reconcile
 * ([refreshFromDrive], single-flight so a second VM instance can't double-list), and the
 * local-first save/delete funnels (store `replaceAll` + a queued `trip.save`/`trip.delete`
 * per trip — refactor § 2 slice 7). [TripsViewModel] instances are thin consumers.
 */
@Singleton
class TripsRepository @Inject constructor(
    private val drive: DriveRepository,
    private val tripStore: TripStore,
    private val mutationStore: PendingMutationStore,
    private val syncEngine: SyncEngine,
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val gson = Gson()
    private var rootFolderId: String? = null
    private var tripsFolderId: String? = null
    private var refresh: Deferred<Unit>? = null
    private var refreshSucceeded = false

    /**
     * Derived read path (§ 5 slice 4d): the store's global trip list, newest-first — every
     * store write (mutators, the Phase-2 reconcile) lands in consumers via Room invalidation.
     */
    val trips: StateFlow<List<Trip>> = tripStore.observeTrips()
        .map { trips -> trips.sortedByDescending { it.createdAt } }
        .stateIn(scope, SharingStarted.Eagerly, emptyList())

    /** Cold-load probe: whether the store already holds trips (decides the spinner, not the data). */
    suspend fun hasCache(): Boolean =
        runCatching { tripStore.trips().isNotEmpty() }.getOrDefault(false)

    /**
     * Phase-2 Drive reconcile: list `_trips/`, parse every `{tripId}.json`, `replaceAll` the
     * store (the derived [trips] follows via invalidation). Single-flight — a call while a
     * refresh is in flight joins it instead of listing Drive twice — and **memoized on
     * success**: exactly one successful reconcile per process (the old app-start pre-warm
     * semantics), so a destination-scoped VM's init pre-warm never re-lists Drive per viewer
     * open. A failed reconcile retries on the next call. Throws on failure so the calling VM
     * can surface its own error flag.
     */
    suspend fun refreshFromDrive() {
        if (refreshSucceeded) return
        val shared = refresh?.takeIf { it.isActive } ?: scope.async {
            val folderId = tripsFolderId ?: run {
                val rootId = rootFolderId ?: drive.getOrCreateFolder().also { rootFolderId = it }
                drive.getOrCreateTripsFolder(rootId).also { tripsFolderId = it }
            }
            val files = drive.listTripFiles(folderId)
            val loaded = coroutineScope {
                files.map { file ->
                    async {
                        val json = drive.loadTripJson(file.id) ?: return@async null
                        runCatching { gson.fromJson(json, Trip::class.java) }.getOrNull()
                    }
                }.awaitAll().filterNotNull()
            }
            // Store write first (the derived view follows via invalidation) so the calling
            // VM clears its flags only after the data is in place.
            runCatching { tripStore.replaceAll(loaded.sortedByDescending { t -> t.createdAt }) }
            Unit
        }.also { refresh = it }
        // await rethrows the shared refresh's failure to every joiner.
        shared.await()
        refreshSucceeded = true
    }

    /**
     * Local-first save (§ 2 slice 7): upserts [trip] into the store (the derived [trips]
     * follows) and queues the per-trip Drive write — the `trip.save` handler re-reads the
     * store row at apply time and resolves the trips folder itself.
     */
    suspend fun saveTrip(trip: Trip) {
        val updated = listOf(trip) + trips.value.filterNot { it.id == trip.id }
        runCatching { tripStore.replaceAll(updated) }
        mutationStore.enqueue(TRIP_SAVE_KIND, targetId = trip.id, folderId = null, payload = "{}")
        syncEngine.drain()
    }

    /**
     * Local-first delete (§ 2 slice 7) for every trip in [tripIds]: one store write, one queued
     * `trip.delete` per trip. Returns the union of the deleted trips' `outfitIds` so the caller
     * can offer the outfit-cascade choice.
     */
    suspend fun deleteTrips(tripIds: Collection<String>): List<String> {
        val targets = trips.value.filter { it.id in tripIds }
        if (targets.isEmpty()) return emptyList()
        runCatching { tripStore.replaceAll(trips.value.filterNot { t -> t.id in tripIds }) }
        targets.forEach { trip ->
            mutationStore.enqueue(TRIP_DELETE_KIND, targetId = trip.id, folderId = null, payload = "{}")
        }
        syncEngine.drain()
        return targets.flatMap { it.outfitIds }.distinct()
    }
}
