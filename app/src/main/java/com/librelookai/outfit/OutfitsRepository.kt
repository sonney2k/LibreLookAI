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
import com.librelookai.data.model.WearSource
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
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.receiveAsFlow
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

    /** One-shot events the outfits list consumes (scroll-to-outfit). Lives here (§ 5 slice 9) so
     *  the generation side can emit on save without a cross-VM call; buffered so a cross-tab
     *  request fired before the list mounts (Try-On "View outfit") still lands. */
    private val _events = Channel<OutfitsEvent>(Channel.BUFFERED)
    val events = _events.receiveAsFlow()

    /** Ask the Outfits list to scroll the given outfit into view. */
    fun requestScrollToOutfit(outfitId: String) {
        _events.trySend(OutfitsEvent.ScrollToOutfit(outfitId))
    }

    private val _pendingWearOutfitId = MutableStateFlow<String?>(null)
    /** After an edit-save, the outfit the list surface should offer to wear immediately (its
     *  snackbar clears it). Stateful, not a one-shot: it shows until acted on / dismissed. */
    val pendingWearOutfitId: StateFlow<String?> = _pendingWearOutfitId

    fun setPendingWearOutfit(outfitId: String?) { _pendingWearOutfitId.value = outfitId }

    /** A "wear this outfit" request headed for the calendar's pick mode. */
    data class PendingCalendarWear(val outfitId: String, val source: WearSource)

    private val _pendingCalendarWear = MutableStateFlow<PendingCalendarWear?>(null)
    /** Cross-tab "pick the wear day in the calendar" hand-off (§ 5 slice 9 — lives here so a
     *  destination-scoped viewer VM's request still reaches the Outfits tab's instance).
     *  Stateful, not a one-shot: `OutfitCalendarTab` resolves it into pick mode and consumes it. */
    val pendingCalendarWear: StateFlow<PendingCalendarWear?> = _pendingCalendarWear

    fun requestCalendarWear(outfitId: String, source: WearSource) {
        _pendingCalendarWear.value = PendingCalendarWear(outfitId, source)
    }

    fun consumeCalendarWear() { _pendingCalendarWear.value = null }

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
            resolveOutfits(raw, id, resolvedNameToId)
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
     * Persists a brand-new outfit into the active save folder (§ 5 slice 9): resolves its
     * `itemNames` across every loaded closet (a composed outfit may span folders — see
     * [idToNameAllFolders]), stamps the home folder (the field is @Transient, so Drive JSON is
     * unchanged; the local cache homes each outfit in exactly one folder, and an empty folderId
     * would leave it mis-homed until the next Drive sync), fires the scroll-to-outfit one-shot
     * for the list, and routes the write through [persistOutfitFolders]. Returns the saved
     * outfit, or null when no save target is resolvable (no closet session yet).
     */
    suspend fun saveOutfit(
        name: String,
        description: String,
        itemIds: List<String>,
        tags: List<String> = emptyList(),
    ): Outfit? {
        if (itemIds.isEmpty()) return null
        val id = saveFolderId ?: folderId ?: return null
        val idToName = idToNameAllFolders(listOf(id))
        val newOutfit = Outfit(
            name = name,
            description = description,
            itemIds = itemIds,
            itemNames = itemIds.mapNotNull { idToName[it] },
            tags = tags,
            folderId = id,
        )
        requestScrollToOutfit(newOutfit.id)
        persistOutfitFolders(outfits.value + newOutfit, listOf(id))
        return newOutfit
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
            val scope = allFolderIds ?: listOfNotNull(folderId)
            foldersToWrite(scope, affected).forEach { fid ->
                runCatching { outfitStore.replaceFolder(fid, outfitsHomedIn(fid, styles)) }
            }
            affected.filter { it.isNotEmpty() }.toSet().forEach { fid ->
                mutationStore.enqueue(OUTFIT_FOLDER_SYNC_KIND, targetId = fid, folderId = fid, payload = "{}")
            }
            syncEngine.drain()
        }
    }
}

// ---- Pure decision logic (unit-tested in OutfitsRepositoryTest; no Drive/Room deps) ----

/**
 * Resolves a folder's parsed [raw] outfits' `itemIds` from their persisted `itemNames` against the
 * folder's live [nameToId] map, **extension-agnostic** via [ImageEncoding.itemMatchKey] (so a
 * `_cutout.png` itemName resolves against a `_cutout.webp` item, and an item moved to another closet
 * still resolves through the combined map), and stamps the home [folderId]. Outfits with no
 * `itemNames` (legacy / freshly composed) keep their existing `itemIds`.
 */
internal fun resolveOutfits(raw: List<Outfit>, folderId: String, nameToId: Map<String, String>): List<Outfit> =
    raw.map { style ->
        style.copy(
            itemIds = if (style.itemNames.isNotEmpty())
                style.itemNames.mapNotNull { nameToId[ImageEncoding.itemMatchKey(it)] }
            else style.itemIds,
            folderId = folderId,
        )
    }

/** The folders [persistOutfitFolders] rewrites for a save: the load [scope] plus any explicitly
 *  [affected] (out-of-scope) save targets; blank ids dropped. */
internal fun foldersToWrite(scope: List<String>, affected: Collection<String>): Set<String> =
    (scope + affected.filter { it.isNotEmpty() }).filter { it.isNotEmpty() }.toSet()

/** The outfits written into folder [fid]: those homed there, plus any not-yet-homed (empty
 *  `folderId`) fresh outfits. [OutfitStore.replaceFolder]'s outfit-id primary key then resolves the
 *  final single home, so an empty-`folderId` outfit lands in exactly one folder, not duplicated. */
internal fun outfitsHomedIn(fid: String, styles: List<Outfit>): List<Outfit> =
    styles.filter { it.folderId == fid || it.folderId.isEmpty() }
