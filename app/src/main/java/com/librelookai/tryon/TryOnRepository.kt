package com.librelookai.tryon

import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import com.librelookai.data.drive.DriveRepository
import com.librelookai.data.drive.loadTryOnsJson
import com.librelookai.data.drive.saveTryOnsJson
import com.librelookai.data.local.TryOnStore
import com.librelookai.data.model.TryOn
import java.io.File
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
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
 * `_tryons.json` Drive read/write, and the image-file cache naming. Saves/deletes stay
 * Drive-first (not queued through the § 2 SyncEngine — deliberate, see `plan/refactor.md`); every
 * write ends in [writeAll], whose store write reaches the UI via Room invalidation.
 */
@Singleton
class TryOnRepository @Inject constructor(
    private val drive: DriveRepository,
    private val tryOnStore: TryOnStore,
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

    /** Rewrites `_tryons.json` and mirrors it into the store (the derived [history] follows). */
    suspend fun writeAll(entries: List<TryOn>) {
        drive.saveTryOnsJson(ensureRootFolder(), gson.toJson(entries))
        runCatching { tryOnStore.replaceAll(entries) }
    }

    /**
     * Phase-2 refresh: re-read `_tryons.json`, download any missing image files, then write the
     * store — its invalidation re-runs the [history] derivation (which now finds the files).
     * Phase 1 is the derived flow itself (store rows paint on subscription).
     */
    suspend fun refreshFromDrive() {
        val entries = loadEntries()
        entries.forEach { e ->
            val cached = cacheFile(e.imageDriveId)
            if (!cached.exists()) {
                runCatching { drive.downloadFileTo(e.imageDriveId, cached) }
            }
        }
        runCatching { tryOnStore.replaceAll(entries) }
    }

    /**
     * Deletes [tryOns] (Drive image files + cache files) and rewrites the index in a single
     * JSON write. Throws on an index-write failure so the caller can surface the error.
     */
    suspend fun deleteTryOns(tryOns: List<TryOn>) {
        if (tryOns.isEmpty()) return
        val ids = tryOns.map { it.imageDriveId }.toSet()
        tryOns.forEach { t ->
            runCatching { drive.deleteFile(t.imageDriveId) }
            cacheFile(t.imageDriveId).delete()
        }
        writeAll(loadEntries().filterNot { it.imageDriveId in ids })
    }
}

// Old entries predate sourceKind: Gson fills the default "outfit", but an item-by-item
// try-on (no sourceOutfitId) was really a wardrobe source. Infer it so provenance reads right.
internal fun migrateSource(t: TryOn): TryOn =
    if (t.sourceOutfitId == null && t.sourceKind == "outfit")
        t.copy(sourceKind = "wardrobe")
    else t
