package com.librelookai.shopping

import android.content.Context
import android.util.Log
import com.google.gson.Gson
import com.librelookai.data.drive.DriveRepository
import com.librelookai.data.drive.DriveService
import com.librelookai.data.local.CachedWardrobeItem
import com.librelookai.data.local.WardrobeItemStore
import com.librelookai.data.session.ClosetSessionHolder
import com.librelookai.wardrobe.DriveImage
import com.librelookai.wardrobe.ItemSidecar
import com.librelookai.wardrobe.ItemVersions
import com.librelookai.wardrobe.toCachedItem
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * The shopping wishlist's load core (refactor § 5 slice 9 — the `WardrobeRepository`/
 * `TripsRepository` pattern, extracted so a destination-scoped [ShoppingClosetViewModel] fork
 * never re-resolves the folder or re-lists Drive): owns the `_shopping/` folder resolution
 * (Drive when online, memoized + persisted for offline cold starts; published into the shared
 * closet session), the store-derived [items] flow (§ 5 slice 4c, over the shared wardrobe
 * [ItemVersions] overlay so byte bumps reach every derived view), and the single-flight
 * Phase-2 [refreshFromDrive] reconcile. VM instances are thin consumers — mutations still
 * write the [WardrobeItemStore] and land here via Room invalidation.
 */
@OptIn(ExperimentalCoroutinesApi::class)
@Singleton
class ShoppingRepository @Inject constructor(
    @param:ApplicationContext private val context: Context,
    private val drive: DriveService,
    private val itemStore: WardrobeItemStore,
    itemVersions: ItemVersions,
    session: ClosetSessionHolder,
) {
    companion object { private const val TAG = "ShoppingRepo" }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val gson = Gson()

    private var rootFolderId: String? = null

    /** Resolved `_shopping/` Drive folder id, or null while still unresolved. */
    private val _folderId = MutableStateFlow<String?>(null)
    val folderId: StateFlow<String?> = _folderId.asStateFlow()

    /**
     * Derived read path (§ 5 slice 4c): the wishlist is the store rows under the resolved
     * `_shopping/` folder (with a cached image file), newest first — matching Drive's
     * createdTime-desc listing — stamped with the shared Coil version overlay. Every store
     * write (a VM's, the ingestion queue's, a SyncEngine handler's) lands via invalidation.
     */
    val items: StateFlow<List<DriveImage>> =
        combine(
            _folderId.flatMapLatest { id ->
                if (id == null) flowOf(emptyMap()) else itemStore.observeItems(listOf(id))
            },
            itemVersions.versions,
        ) { byFolder, versions ->
            byFolder.flatMap { (fid, items) -> items.mapNotNull { it.toDriveImage(fid, versions) } }
                .sortedByDescending { it.createdTimeMs }
        }
            .flowOn(Dispatchers.IO)
            .stateIn(scope, SharingStarted.Eagerly, emptyList())

    init {
        // Publish the resolved folder id into the shared closet session so the wardrobe
        // cross-closet snapshot covers wishlist items (moved off the VM's state collector).
        scope.launch { _folderId.collect { session.setShoppingFolder(it) } }
    }

    /**
     * Maps a store row to its displayable [DriveImage], or null when the image bytes aren't in
     * the local Drive cache yet (the row reappears with the next store write after download).
     */
    private fun CachedWardrobeItem.toDriveImage(
        fid: String,
        versions: Map<String, Long>,
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

    // ---------- Folder resolution ----------

    /**
     * Resolves the `_shopping/` folder id. Online: via Drive (memoized per process), then
     * persisted so a later offline launch can still find the local cache. Offline (or a failed
     * Drive resolve): fall back to the in-memory id or the persisted one — the folder id is
     * otherwise unobtainable without the network, which would leave the store rows (keyed by
     * it) unreachable. Null = offline and never resolved.
     */
    suspend fun resolveFolder(online: Boolean): String? = withContext(Dispatchers.IO) {
        val resolved = if (online) {
            runCatching { resolveFromDrive() }
                .onFailure { Log.w(TAG, "shopping folder resolve failed", it) }
                .getOrNull() ?: _folderId.value ?: persistedFolderId()
        } else {
            _folderId.value ?: persistedFolderId()
        }
        resolved?.also { publish(it) }
    }

    /** Online-only resolve for the import paths — throws on failure (the caller raises its error). */
    suspend fun ensureFolder(): String = withContext(Dispatchers.IO) {
        resolveFromDrive().also { publish(it) }
    }

    private suspend fun resolveFromDrive(): String {
        val rootId = rootFolderId ?: drive.getOrCreateFolder().also { rootFolderId = it }
        return _folderId.value ?: drive.getOrCreateShoppingFolder(rootId)
    }

    private fun publish(id: String) {
        _folderId.value = id
        persistFolderId(id)
    }

    /**
     * The `_shopping/` Drive folder ID is persisted across launches so an offline cold start can
     * locate the local cache (which is keyed by this ID).
     */
    private fun prefs() = context.getSharedPreferences("shopping_closet", Context.MODE_PRIVATE)

    private fun persistedFolderId(): String? =
        prefs().getString("folder_id", null)?.takeIf { it.isNotBlank() }

    private fun persistFolderId(id: String) {
        prefs().edit().putString("folder_id", id).apply()
    }

    // ---------- Phase-2 reconcile ----------

    private var refresh: Deferred<Unit>? = null
    private var refreshSucceeded = false

    /**
     * Phase-2 Drive reconcile of the resolved folder: list `_shopping/`, download uncached
     * cutouts + sidecars, `replaceFolder` the store (the derived [items] follows via
     * invalidation). Single-flight — a call while a refresh is in flight joins it instead of
     * listing Drive twice. [once] = the VM-init pre-warm semantics: skip entirely when a
     * reconcile already succeeded this process, so a destination-scoped fork's init never
     * re-lists Drive (the Shopping tab's per-visit `loadItems` passes false and re-syncs).
     * Throws on failure so the calling VM can surface its own error flag.
     */
    suspend fun refreshFromDrive(once: Boolean = false) {
        if (once && refreshSucceeded) return
        val folderId = _folderId.value ?: return
        val shared = refresh?.takeIf { it.isActive } ?: scope.async {
            val loaded = loadFolderItems(folderId)
            // Store write first (the derived view follows via invalidation) so the calling
            // VM clears its flags only after the data is in place.
            runCatching { itemStore.replaceFolder(folderId, loaded.map { it.toCachedItem() }) }
            Unit
        }.also { refresh = it }
        // await rethrows the shared refresh's failure to every joiner.
        shared.await()
        refreshSucceeded = true
    }

    private suspend fun loadFolderItems(folderId: String): List<DriveImage> = coroutineScope {
        val files = drive.listFiles(folderId)
        val sidecarFiles = drive.listSidecarFiles(folderId)
        val sidecarIdByItemId = sidecarFiles.associate { sf ->
            sf.name.removeSuffix(DriveRepository.SIDECAR_SUFFIX) to sf.id
        }

        // Download uncached cutouts in parallel.
        files.map { file ->
            async {
                drive.cachedFile(file.id) ?: drive.downloadToCache(file.id, file.name)
            }
        }.awaitAll()

        // Load sidecar contents in parallel.
        val sidecarContent: Map<String, ItemSidecar> = sidecarFiles.map { sf ->
            async {
                val itemId = sf.name.removeSuffix(DriveRepository.SIDECAR_SUFFIX)
                val content = drive.loadFileContent(sf.id)
                itemId to content?.let { runCatching { gson.fromJson(it, ItemSidecar::class.java) }.getOrNull() }
            }
        }.awaitAll().mapNotNull { (k, v) -> v?.let { k to it } }.toMap()

        files.mapNotNull { file ->
            drive.cachedFile(file.id)?.let { cached ->
                val sidecar = sidecarContent[file.id]
                DriveImage(
                    driveId = file.id,
                    localPath = cached.absolutePath,
                    name = file.name,
                    tags = sidecar?.tags,
                    originalDriveId = sidecar?.originalDriveId,
                    sidecarDriveId = sidecarIdByItemId[file.id],
                    folderId = folderId,
                    createdTimeMs = file.createdTimeMs,
                )
            }
        }
    }
}
