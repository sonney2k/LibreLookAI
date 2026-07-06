package com.librelookai.tryon

import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import com.librelookai.data.drive.DriveService
import com.librelookai.data.drive.SyncEngine
import com.librelookai.data.local.PendingMutationStore
import com.librelookai.data.local.TryOnStore
import com.librelookai.data.model.TryOn
import com.librelookai.wardrobe.DeleteItemPayload
import com.librelookai.wardrobe.ITEM_DELETE_KIND
import java.io.File
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.withContext

/**
 * Shared try-on data + persistence (refactor § 5 slice 9 — the TryOn VM split): the composer VM
 * ([TryOnViewModel]) saves through it and [TryOnHistoryViewModel] reads/deletes through it, so
 * the two never call each other. Owns the store-derived [history] flow (§ 5 slice 4d), the
 * `_tryons.json` Drive read/write, and the image-file cache naming. Index writes and deletes
 * are **local-first over the § 2 queue** (the last § 2 leftover, converted July 2026): every
 * write ends in [writeAll] — store write (the UI follows via Room invalidation) + one queued
 * [TRYON_INDEX_SYNC_KIND] mutation. Only the image *byte* uploads stay direct (they mint the
 * `imageDriveId` identity, and the user is necessarily online right after generating).
 */
@Singleton
class TryOnRepository @Inject constructor(
    private val drive: DriveService,
    private val tryOnStore: TryOnStore,
    private val mutationStore: PendingMutationStore,
    private val syncEngine: SyncEngine,
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val gson = Gson()
    private var rootFolderId: String? = null

    /**
     * Derived history (§ 5 slice 4d): the store's global try-on list — rows whose image file is
     * in the local Drive cache (an un-downloaded entry reappears with the store write after
     * [refreshFromDrive] fetches it), newest-first — so every store write lands in the UI via
     * Room invalidation. The store holds metadata only; image bytes stay files.
     */
    val history: StateFlow<List<TryOn>> = tryOnStore.observeTryOns()
        .map { entries ->
            entries.map(::migrateSource).mapNotNull { e ->
                val f = cacheFile(e.imageDriveId)
                if (f.exists()) e.copy(localPath = f.absolutePath) else null
            }.sortedByDescending { it.createdAt }
        }
        .flowOn(Dispatchers.IO)
        .stateIn(scope, SharingStarted.Eagerly, emptyList())

    /** The local cache file a try-on's image bytes live in (Drive-ID-keyed, PNG). */
    fun cacheFile(imageDriveId: String): File = File(drive.cacheDir, "tryon_$imageDriveId.png")

    suspend fun ensureRootFolder(): String {
        rootFolderId?.let { return it }
        return withContext(Dispatchers.IO) { drive.getOrCreateFolder() }.also { rootFolderId = it }
    }

    /** The current `_tryons.json` entries (source-migrated); empty on missing/corrupt JSON. */
    suspend fun loadEntries(): List<TryOn> {
        val json = drive.loadTryOnsJson(ensureRootFolder()) ?: return emptyList()
        return runCatching {
            gson.fromJson<List<TryOn>>(json, object : TypeToken<List<TryOn>>() {}.type) ?: emptyList()
        }.getOrElse { emptyList() }.map(::migrateSource)
    }

    /**
     * Local-first index write (refactor § 2): mirrors [entries] into the store (the derived
     * [history] follows via invalidation) and enqueues one payload-free [TRYON_INDEX_SYNC_KIND]
     * mutation — the handler re-serializes the *store* at apply time, so back-to-back writes
     * coalesce and a transient Drive failure retries instead of dropping the entry. Returning
     * means *locally committed + queued*, not Drive-confirmed.
     */
    suspend fun writeAll(entries: List<TryOn>) {
        tryOnStore.replaceAll(entries)
        mutationStore.enqueue(TRYON_INDEX_SYNC_KIND, targetId = "_tryons", folderId = null, payload = "{}")
        syncEngine.drain()
    }

    private var refresh: Deferred<Unit>? = null
    private var refreshSucceeded = false

    /**
     * Phase-2 refresh: re-read `_tryons.json`, download any missing image files, then write the
     * store — its invalidation re-runs the [history] derivation (which now finds the files).
     * Phase 1 is the derived flow itself (store rows paint on subscription). Single-flight —
     * a call while a refresh is in flight joins it. [once] = the VM-init pre-warm semantics:
     * skip entirely when a refresh already succeeded this process, so a destination-scoped
     * fork's init never re-reads Drive (the history feed's per-visit refresh passes false).
     */
    suspend fun refreshFromDrive(once: Boolean = false) {
        if (once && refreshSucceeded) return
        val shared = refresh?.takeIf { it.isActive } ?: scope.async {
            val entries = loadEntries()
            entries.forEach { e ->
                val cached = cacheFile(e.imageDriveId)
                if (!cached.exists()) {
                    runCatching { drive.downloadFileTo(e.imageDriveId, cached) }
                }
            }
            runCatching { tryOnStore.replaceAll(entries) }
            Unit
        }.also { refresh = it }
        shared.await()
        refreshSucceeded = true
    }

    /**
     * Deletes [tryOns] local-first (refactor § 2): cache files and store rows go immediately
     * (the derived feed drops them via invalidation), one payload-carrying [ITEM_DELETE_KIND]
     * per image file rides the queue (same funnel as wardrobe deletes — the entry is gone from
     * the store before the drain, so the file id travels in the payload; a retried delete no
     * longer orphans the Drive file), and the index rewrite rides [writeAll]'s queued sync.
     * The FIFO drain deletes the files before rewriting the index, matching the old order.
     */
    suspend fun deleteTryOns(tryOns: List<TryOn>) {
        if (tryOns.isEmpty()) return
        val ids = tryOns.map { it.imageDriveId }.toSet()
        tryOns.forEach { t ->
            cacheFile(t.imageDriveId).delete()
            mutationStore.enqueue(
                ITEM_DELETE_KIND,
                targetId = t.imageDriveId,
                folderId = null,
                payload = gson.toJson(DeleteItemPayload(listOf(t.imageDriveId))),
            )
        }
        writeAll(tryOnStore.tryOns().filterNot { it.imageDriveId in ids })
    }
}

// Old entries predate sourceKind: Gson fills the default "outfit", but an item-by-item
// try-on (no sourceOutfitId) was really a wardrobe source. Infer it so provenance reads right.
internal fun migrateSource(t: TryOn): TryOn =
    if (t.sourceOutfitId == null && t.sourceKind == "outfit")
        t.copy(sourceKind = "wardrobe")
    else t
