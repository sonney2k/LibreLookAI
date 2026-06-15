package com.librelookai.outfit

import android.app.Application
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import com.librelookai.data.drive.DriveRepository
import com.librelookai.data.drive.SyncEngine
import com.librelookai.data.drive.loadOutfitsJson
import com.librelookai.data.local.OutfitStore
import com.librelookai.data.local.PendingMutationStore
import com.librelookai.data.local.WardrobeItemStore
import com.librelookai.data.model.Outfit
import com.librelookai.util.ImageEncoding
import com.librelookai.wardrobe.DriveImage
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * Shared outfit data + persistence, owned by a `@Singleton` so the (eventually split, § 5 slice 8)
 * list / composer / prediction view-models all read the same store-derived flows and route every
 * save through one funnel. Extracted from `OutfitsViewModel` (refactor § 5 slice 8 — the
 * foundation that makes the per-screen VM split, and slice 9's destination-scoped VMs, mechanical):
 * holds the cross-closet scope, the derived [outfits]/[wardrobeImages] flows, the Drive load
 * helpers, and the local-first [persistOutfitFolders] save funnel.
 *
 * UI state (isLoading/error/composer/prediction/selection) stays in the VM — this is data, not
 * presentation. The VM drives the scope via [setScope] (from the closet session) and mirrors
 * [outfits]/[wardrobeImages] into its `OutfitsUiState` so screen consumers are unchanged.
 */
@OptIn(ExperimentalCoroutinesApi::class)
@Singleton
class OutfitsRepository @Inject constructor(
    private val app: Application,
    private val drive: DriveRepository,
    private val itemStore: WardrobeItemStore,
    private val outfitStore: OutfitStore,
    private val mutationStore: PendingMutationStore,
    private val syncEngine: SyncEngine,
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val gson = Gson()

    /** Active load scope. Outfits always load from every closet — never the closet filter. */
    var folderId: String? = null
        private set
    var allFolderIds: List<String>? = null
        private set

    /** Folder new styles save into; set independently of load scope (by the closet session). */
    var saveFolderId: String? = null

    private val _scope = MutableStateFlow<List<String>>(emptyList())
    /** Serializes [persistOutfitFolders] so back-to-back mutations can't interleave store writes. */
    private val persistMutex = Mutex()

    /**
     * Derived outfits (§ 5 slice 4b): the store rows in scope — rows carry resolved itemIds (only
     * folderId is @Transient), so every mutation's replaceFolder and every Phase-2 reconcile reach
     * the UI via Room invalidation.
     */
    val outfits: StateFlow<List<Outfit>> = _scope
        .flatMapLatest { ids -> outfitStore.observeFolders(ids) }
        .stateIn(scope, SharingStarted.Eagerly, emptyList())

    /** Derived wardrobe images for the outfit cards/pickers (same item-store scope, invalidated). */
    val wardrobeImages: StateFlow<List<DriveImage>> = _scope
        .flatMapLatest { ids -> itemStore.observeItems(ids) }
        .map { byFolder ->
            byFolder.flatMap { (fid, items) ->
                items.mapNotNull { entry ->
                    drive.cachedFile(entry.driveId)?.let { f ->
                        DriveImage(
                            driveId = entry.driveId,
                            localPath = f.absolutePath,
                            name = entry.name,
                            tags = entry.tags,
                            originalDriveId = entry.originalDriveId,
                            sidecarDriveId = entry.sidecarDriveId,
                            folderId = fid,
                        )
                    }
                }
            }
        }
        .flowOn(Dispatchers.IO)
        .stateIn(scope, SharingStarted.Eagerly, emptyList())

    /** Point the derived flows at every closet (load scope is never the active closet filter). */
    fun setScope(folderIds: List<String>) {
        folderId = null
        allFolderIds = folderIds.toList()
        _scope.value = folderIds.toList()
    }

    /** Phase-1 probe of the local cache (the derived flow paints the store itself; this is for flags). */
    suspend fun cachedOutfits(): List<Outfit> {
        val ids = allFolderIds
        return if (ids != null) ids.flatMap { outfitStore.outfitsFor(it) }
        else folderId?.let { outfitStore.outfitsFor(it) } ?: emptyList()
    }

    /** Phase-2 Drive sync: re-list each folder, resolve itemNames→ids, replace each folder's rows. */
    suspend fun syncOutfitsFromDrive(): Result<Unit> = runCatching {
        coroutineScope {
            val ids = allFolderIds
            if (ids != null) {
                // Combined name→ID map across all folders so styles whose items moved closets resolve.
                val combinedNameToId: Map<String, String> = ids
                    .map { id -> async { drive.listFiles(id).associate { ImageEncoding.itemMatchKey(it.name) to it.id } } }
                    .awaitAll()
                    .fold(emptyMap()) { acc, m -> acc + m }
                ids.map { id -> async { loadOutfitsFromFolder(id, combinedNameToId) } }.awaitAll()
            } else {
                val id = folderId ?: return@coroutineScope
                loadOutfitsFromFolder(id)
            }
        }
    }

    private suspend fun loadOutfitsFromFolder(id: String, nameToId: Map<String, String>? = null): List<Outfit> {
        val resolvedNameToId = nameToId
            ?: drive.listFiles(id).associate { ImageEncoding.itemMatchKey(it.name) to it.id }
        val json = drive.loadOutfitsJson(id)
        val resolved = if (json != null) {
            val type = object : TypeToken<List<Outfit>>() {}.type
            val raw: List<Outfit> = gson.fromJson(json, type) ?: emptyList()
            raw.map { style ->
                style.copy(
                    itemIds = if (style.itemNames.isNotEmpty())
                        style.itemNames.mapNotNull { resolvedNameToId[ImageEncoding.itemMatchKey(it)] }
                    else style.itemIds,
                    folderId = id,
                )
            }
        } else emptyList()
        runCatching { outfitStore.replaceFolder(id, resolved) }
        return resolved
    }

    /**
     * Builds a Drive-ID → filename map across **every** loaded closet folder, not just the save
     * target. Travel/composer outfits can reference items from multiple closets; resolving names
     * against a single folder drops the rest from `itemNames`, the source of truth on reload.
     */
    suspend fun idToNameAllFolders(fallbackFolderIds: Collection<String>): Map<String, String> = coroutineScope {
        (allFolderIds ?: fallbackFolderIds.toList())
            .distinct()
            .map { f -> async { drive.listFiles(f).associate { it.id to it.name } } }
            .awaitAll()
            .fold(emptyMap<String, String>()) { acc, m -> acc + m }
    }

    /**
     * Local-first outfit save (refactor § 2, derived since § 5 slice 4b): mirrors [styles] — the
     * mutation's already-updated full list — into the per-folder local cache over the view scope
     * plus [affected] (so an out-of-scope save target is cached too), then enqueues one payload-free
     * [OUTFIT_FOLDER_SYNC_KIND] mutation per affected folder and drains. The derived [outfits]
     * follows via Room invalidation. Serialized so two rapid mutations can't interleave their writes.
     */
    suspend fun persistOutfitFolders(styles: List<Outfit>, affected: Collection<String>) {
        persistMutex.withLock {
            val affectedIds = affected.filterTo(LinkedHashSet()) { it.isNotEmpty() }
            val s = allFolderIds ?: listOfNotNull(folderId)
            (s + affectedIds).toSet().forEach { fid ->
                val folderStyles = styles.filter { it.folderId == fid || it.folderId.isEmpty() }
                runCatching { outfitStore.replaceFolder(fid, folderStyles) }
            }
            affectedIds.forEach { fid ->
                mutationStore.enqueue(OUTFIT_FOLDER_SYNC_KIND, targetId = fid, folderId = fid, payload = "{}")
            }
            syncEngine.drain()
        }
    }
}
