package com.librelookai.wardrobe

import android.content.Context
import android.util.Log
import com.google.gson.Gson
import com.librelookai.data.drive.DriveRepository
import com.librelookai.data.drive.SyncEngine
import com.librelookai.data.drive.listSidecarFiles
import com.librelookai.data.local.CachedWardrobeItem
import com.librelookai.data.local.PendingMutationStore
import com.librelookai.data.local.WardrobeItemStore
import com.librelookai.data.session.ClosetSession
import com.librelookai.data.session.ClosetSessionHolder
import com.librelookai.util.NetworkMonitor
import com.librelookai.util.isNetworkAvailable
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.onStart
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * The wardrobe Phase-2 sync flags, published by [WardrobeRepository.syncStatus] and mirrored
 * into [WardrobeUiState] by each VM instance. [error] carries the last load failure (set only
 * when nothing is painted); a new load or scope change clears it.
 */
data class WardrobeSyncStatus(
    val isLoading: Boolean = false,
    val isSyncing: Boolean = false,
    val syncTotal: Int = 0,
    val syncDone: Int = 0,
    val syncPhase: WardrobeSyncPhase = WardrobeSyncPhase.NONE,
    val error: String? = null,
)

/**
 * The wardrobe load core (refactor § 5 slice 9 — the [OutfitsRepository]/`TryOnRepository`/
 * `TripsRepository` pattern, extracted so a destination-scoped `WardrobeViewModel` fork never
 * re-runs the two-phase Drive load): owns the closet-session-driven scope, the store-derived
 * [images]/[allLocationImages] flows (§ 5 slice 4a), the two-phase [reload] with its
 * [syncStatus] flags, the uncached-closet prefetch (+ connectivity retry), and the
 * recently-moved suppression markers. `WardrobeViewModel` instances are thin consumers —
 * mutations still write the [WardrobeItemStore] and land here via Room invalidation.
 *
 * The Phase-2 load arms live in `WardrobeRepositoryLoad.kt` (same-package extensions);
 * members shared with them are `internal`, not private.
 */
@OptIn(ExperimentalCoroutinesApi::class)
@Singleton
class WardrobeRepository @Inject constructor(
    @param:ApplicationContext internal val context: Context,
    internal val drive: DriveRepository,
    internal val itemStore: WardrobeItemStore,
    private val mutationStore: PendingMutationStore,
    private val syncEngine: SyncEngine,
    itemVersions: ItemVersions,
    networkMonitor: NetworkMonitor,
    session: ClosetSessionHolder,
) {
    companion object { private const val TAG = "WardrobeRepo" }

    internal val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    internal val gson = Gson()

    /** The active closet's folder id, or null in All-locations mode. */
    var folderId: String? = null
        private set
    internal var allFolderIds: List<String>? = null

    /**
     * Every closet folderId the user has configured, regardless of which one is currently
     * displayed. Driven by the [ClosetSessionHolder] collector. Scopes the cross-closet
     * [allLocationImages] snapshot for similarity search.
     */
    private var allConfiguredFolderIds: List<String> = emptyList()

    /** The `_shopping` folder id within [allConfiguredFolderIds], if any. */
    var shoppingFolderId: String? = null
        private set

    // ---- Derived read path (refactor § 5 slice 4a) ----
    // [images] and [allLocationImages] are *derived*: the two scope flows below flatMap into
    // [WardrobeItemStore.observeItems], so every store write — by a VM, the shopping VM, the
    // ingestion pipeline or a SyncEngine handler — lands in consumers via Room invalidation.
    /** The folder ids the grid currently shows (active closet, or every closet in All mode). */
    private val viewScope = MutableStateFlow<List<String>>(emptyList())
    /** Every configured closet + shopping — the cross-closet similarity-snapshot scope. */
    private val configuredScope = MutableStateFlow<List<String>>(emptyList())

    /**
     * The items in the current view scope, stamped with the Coil version overlay. A scope
     * change emits an empty list first (the `onStart`), matching the old blank-then-paint
     * closet switch, then the new scope's cached rows.
     */
    val images: StateFlow<List<DriveImage>> =
        combine(
            viewScope.flatMapLatest { ids -> itemStore.observeItems(ids).onStart { emit(emptyMap()) } },
            itemVersions.versions,
        ) { byFolder, versions ->
            byFolder.flatMap { (fid, items) -> items.mapNotNull { it.toDriveImage(fid, versions) } }
        }
            .flowOn(Dispatchers.IO)
            .stateIn(scope, SharingStarted.Eagerly, emptyList())

    /** Cross-closet snapshot over every configured closet + shopping — always current. */
    val allLocationImages: StateFlow<List<DriveImage>> =
        combine(
            configuredScope.flatMapLatest { ids -> itemStore.observeItems(ids) },
            itemVersions.versions,
        ) { byFolder, versions ->
            byFolder.flatMap { (fid, items) -> items.mapNotNull { it.toDriveImage(fid, versions) } }
        }
            .flowOn(Dispatchers.IO)
            .stateIn(scope, SharingStarted.Eagerly, emptyList())

    internal val _syncStatus = MutableStateFlow(WardrobeSyncStatus())
    val syncStatus: StateFlow<WardrobeSyncStatus> = _syncStatus.asStateFlow()

    /**
     * Bumped whenever the view scope actually changes (closet switch / All toggle) — VM
     * instances reset their per-scope UI state (selection, find-by-photo, view) off this,
     * replacing the old wholesale `WardrobeUiState` reset in `setLocation`.
     */
    private val _scopeChanges = MutableStateFlow(0)
    val scopeChanges: StateFlow<Int> = _scopeChanges.asStateFlow()

    /** When non-null, new photo imports target this folder instead of the active view folder. */
    private val _importTargetFolderId = MutableStateFlow<String?>(null)
    val importTargetFolderId: StateFlow<String?> = _importTargetFolderId.asStateFlow()

    /** Active [reload] coroutine — cancelled when a new location is selected. */
    internal var loadJob: Job? = null
    private var prefetchJob: Job? = null

    /**
     * Tracks items recently moved into another closet. Drive's `q='<folder>' in parents` query
     * is eventually consistent — after a move PATCH both the destination and the source folder
     * listings can lag for minutes. We use this map to (a) re-inject moved items into the TARGET
     * folder's Phase 2 results while Drive catches up, so they don't briefly disappear after
     * switching to the target, and (b) suppress them from the SOURCE folder's Phase 2 results,
     * so the just-moved item doesn't flicker back into the closet it left (or appear in both).
     * Keyed by cutout driveId → (targetFolderId, expiresAtMs).
     */
    internal val recentlyMovedItems = java.util.concurrent.ConcurrentHashMap<String, Pair<String, Long>>()
    // Drive's `'<folder>' in parents` listing can lag well past a minute — testers have seen a
    // moved item still listed in its old parent for ~5 min. Keep the suppression window generous
    // so the item never flickers back into the source closet before Drive catches up.
    private val recentlyMovedTtlMs: Long = 10 * 60 * 1000L

    internal fun pruneRecentlyMoved() {
        val now = System.currentTimeMillis()
        recentlyMovedItems.entries.removeAll { it.value.second < now }
    }

    init {
        // Derive the load scope, cross-closet snapshot and import target from the shared closet
        // session (same call order the VM's collector used). When the active id is neither
        // "All" nor a known closet, the current scope is kept — the old no-op arm.
        scope.launch {
            session.session.collect { s ->
                setAllConfiguredLocations(s.snapshotFolderIds, s.shoppingFolderId)
                val active = s.activeFolderId
                when {
                    s.activeLocationId == ClosetSession.ALL_LOCATIONS_ID -> setAllLocations(s.closetFolderIds)
                    active != null -> setLocation(active)
                }
                _importTargetFolderId.value = s.defaultImportFolderId
            }
        }
        // Retry the cross-closet prefetch on start and whenever connectivity returns: the
        // initial prefetch bails when offline; without this an early network blip would leave
        // some closets permanently uncached (empty snapshot → outfits/trips hidden). StateFlow
        // replays the current value, so an online start also fires once.
        scope.launch {
            networkMonitor.isOnline.collect { online -> if (online) retryPrefetchIfNeeded() }
        }
    }

    /** Set the folder that new photo imports target. Null = fall back to the active view folder. */
    fun setDefaultImportFolderId(folderId: String?) {
        _importTargetFolderId.value = folderId
    }

    private fun setLocation(newFolderId: String) {
        if (folderId == newFolderId && allFolderIds == null) return
        folderId = newFolderId
        allFolderIds = null
        _syncStatus.value = WardrobeSyncStatus(isLoading = true)
        viewScope.value = listOf(newFolderId)
        _scopeChanges.update { it + 1 }
        reload()
    }

    private fun setAllLocations(folderIds: List<String>) {
        if (folderId == null && allFolderIds?.toSet() == folderIds.toSet()) return
        folderId = null
        allFolderIds = folderIds.toList()
        _syncStatus.value = WardrobeSyncStatus(isLoading = true)
        viewScope.value = folderIds.toList()
        _scopeChanges.update { it + 1 }
        reload()
    }

    private fun setAllConfiguredLocations(folderIds: List<String>, shoppingFolderId: String?) {
        if (allConfiguredFolderIds.toSet() == folderIds.toSet() && this.shoppingFolderId == shoppingFolderId) return
        allConfiguredFolderIds = folderIds.toList()
        this.shoppingFolderId = shoppingFolderId
        configuredScope.value = folderIds.toList()
        prefetchUncachedClosets()
    }

    // ---------- Recently-moved markers ----------

    /**
     * Records that [items] have been moved into [targetFolderId] (their cutout/original/sidecar
     * Drive IDs are unchanged, so the cached image bytes are still valid). Re-homes them into the
     * target folder in the [WardrobeItemStore] so switching to that folder shows them in Phase 1
     * instantly, and remembers them so Phase 2 won't drop them while Drive's listing is still
     * propagating. Callable from other view models (e.g. shopping move-to-closet).
     */
    fun notifyItemsMovedTo(targetFolderId: String, items: List<DriveImage>) {
        if (items.isEmpty() || targetFolderId.isEmpty()) return
        val expiry = System.currentTimeMillis() + recentlyMovedTtlMs
        items.forEach { recentlyMovedItems[it.driveId] = targetFolderId to expiry }
        // The store re-home is the whole local move: the derived view drops the items from
        // their source closet and shows them in the target via invalidation.
        scope.launch(Dispatchers.IO) {
            runCatching { itemStore.addAll(targetFolderId, items.map { it.toCachedItem() }) }
        }
    }

    /** Drops the recently-moved markers for [driveIds] (item deleted / move rolled back). */
    fun forgetRecentlyMoved(driveIds: Collection<String>) {
        driveIds.forEach { recentlyMovedItems.remove(it) }
    }

    // ---------- Queued mutations (refactor § 2) ----------

    /**
     * Queues one [ITEM_MOVE_KIND] mutation per item of an already-applied local move (callers
     * run [notifyItemsMovedTo] first — the store re-home survives a restart) and drains. The
     * mutations retry transient Drive failures instead of rolling back on the first error, and
     * only give up — via the handler's re-home + `moveRolledBack` — after the attempts cap.
     */
    suspend fun enqueueMoves(toMove: List<DriveImage>, targetFolderId: String) = withContext(Dispatchers.IO) {
        toMove.forEach { item ->
            val sourceFolderId = item.folderId.ifEmpty { folderId } ?: return@forEach
            mutationStore.enqueue(
                ITEM_MOVE_KIND,
                targetId = item.driveId,
                folderId = targetFolderId,
                payload = gson.toJson(
                    MoveItemPayload(sourceFolderId, targetFolderId, item.originalDriveId, item.sidecarDriveId),
                ),
            )
        }
        syncEngine.drain()
    }

    /**
     * Deletes [items] locally (store removes — the derived views drop them from every affected
     * closet at once) and queues one [ITEM_DELETE_KIND] mutation per item; the cache writes come
     * first, still before any Drive call, so the deletion survives a restart, and the queued
     * mutations retry transient failures instead of silently orphaning the files on Drive.
     * File ids ride in the payload because the Room row is gone before the drain runs.
     */
    suspend fun deleteItems(items: List<DriveImage>) = withContext(Dispatchers.IO) {
        val driveIds = items.map { it.driveId }.toSet()
        forgetRecentlyMoved(driveIds)
        // Resolve each item's owning closet (folderId is empty in All-locations mode, so fall
        // back to the active folder) — a multi-closet selection can span several caches.
        val affectedFolderIds = items.mapNotNull { it.folderId.ifEmpty { folderId } }.toSet()
        affectedFolderIds.forEach { fid -> runCatching { itemStore.remove(fid, driveIds) } }
        items.forEach { img ->
            val fileIds = listOfNotNull(img.driveId, img.originalDriveId, img.sidecarDriveId)
            mutationStore.enqueue(
                ITEM_DELETE_KIND,
                targetId = img.driveId,
                folderId = img.folderId.ifEmpty { folderId },
                payload = gson.toJson(DeleteItemPayload(fileIds)),
            )
        }
        syncEngine.drain()
    }

    // ---------- Cache ----------

    /**
     * Maps a store row to its displayable [DriveImage], or null when the image bytes aren't in
     * the local Drive cache yet (the row reappears with the next store write after download).
     * The derivations above run this for every row on each invalidation.
     */
    private fun CachedWardrobeItem.toDriveImage(
        fid: String,
        versions: Map<String, Long> = emptyMap(),
    ): DriveImage? =
        drive.cachedFile(driveId)?.let { f ->
            DriveImage(
                driveId, f.absolutePath, name, tags,
                originalDriveId = originalDriveId,
                sidecarDriveId = sidecarDriveId,
                folderId = fid,
                createdTimeMs = createdTimeMs,
                version = versions[driveId] ?: 0L,
            )
        }

    /** Reads a folder's currently-paintable items once (Phase-1 cold-load probes). */
    internal suspend fun readCacheAsImages(fid: String): List<DriveImage> =
        itemStore.itemsFor(fid).mapNotNull { it.toDriveImage(fid) }

    /**
     * Writes [items] as [id]'s cached folder snapshot. The derived view and cross-closet
     * snapshot follow via Room invalidation — there is no separate refresh step any more.
     */
    internal suspend fun saveLocalCache(id: String, items: List<DriveImage>) {
        runCatching { itemStore.replaceFolder(id, items.map { it.toCachedItem() }) }
    }

    // ---------- Prefetch ----------

    /**
     * Re-attempt the cross-closet prefetch when any configured closet still lacks a local cache.
     * Safe to call repeatedly (app foreground, connectivity returning) — it no-ops when everything
     * is already cached, when offline, or when a prefetch is in flight. Without this retry an
     * initial prefetch that bailed on a momentary network blip would never run again until the
     * closet list changed, leaving the cross-closet snapshot (and thus outfits/trips) empty.
     */
    private fun retryPrefetchIfNeeded() {
        scope.launch(Dispatchers.IO) {
            if (allConfiguredFolderIds.any { fid -> !itemStore.hasFolder(fid) }) {
                prefetchUncachedClosets()
            }
        }
    }

    /**
     * For every configured closet that doesn't have a local cache yet, fetch its files +
     * sidecars from Drive in the background and write the per-folder cache. This ensures
     * similarity search covers all closets on first run, not just the one the user has visited.
     * Skips folders that already have a cache (those refresh via normal [reload]s).
     */
    private fun prefetchUncachedClosets() {
        if (prefetchJob?.isActive == true) return
        if (!context.isNetworkAvailable()) return
        prefetchJob = scope.launch(Dispatchers.IO) {
            val targets = allConfiguredFolderIds.filter { fid -> !itemStore.hasFolder(fid) }
            Log.d(TAG, "prefetch: configured=${allConfiguredFolderIds.size} uncached=${targets.size}")
            if (targets.isEmpty()) return@launch
            targets.forEach { fid ->
                runCatching {
                    val loaded = loadFolderImages(fid)
                    saveLocalCache(fid, loaded)
                    Log.d(TAG, "prefetch: folder=$fid downloaded=${loaded.size}")
                }.onFailure { Log.w(TAG, "prefetch: folder=$fid FAILED", it) }
            }
        }
    }
}
