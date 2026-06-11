package com.librelookai.data.local

import android.content.Context
import android.util.Log
import com.google.gson.Gson
import com.librelookai.gemini.ClothingTags
import dagger.hilt.android.qualifiers.ApplicationContext
import java.io.File
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

private const val TAG = "WardrobeItemStore"

/** SQLite caps bound variables per statement; stay well under it for IN (...) deletes. */
private const val DELETE_CHUNK = 500

/** Shape of the legacy `wardrobe_cache_{folderId}.json` files, parsed only while seeding. */
private data class LegacyCacheEntry(
    val driveId: String = "",
    val name: String = "",
    val tags: ClothingTags? = null,
    val originalDriveId: String? = null,
    val sidecarDriveId: String? = null,
    val createdTimeMs: Long = 0L,
)

private data class LegacyCache(val items: List<LegacyCacheEntry> = emptyList())

@Singleton
class RoomWardrobeItemStore @Inject constructor(
    @param:ApplicationContext private val context: Context,
    private val dao: WardrobeItemDao,
) : WardrobeItemStore {

    private val gson = Gson()
    private val seedMutex = Mutex()

    /** Folders whose seed check already ran this process — skips re-statting absent files. */
    private val seedChecked = mutableSetOf<String>()

    private fun legacyCacheFile(folderId: String) =
        File(context.filesDir, "wardrobe_cache_$folderId.json")

    /**
     * One-time-per-folder import of the legacy JSON cache. Runs before every store operation so
     * an upgrade merges the old cache before any new read or edit. The folder-marker row is only
     * written when a legacy file actually existed — mirroring the old "cache file exists" check —
     * and once it exists the seed never runs again (so later removals can't be resurrected).
     * The legacy file is kept on disk afterwards as a downgrade safety net; [clearFolder]
     * deletes it so a cleared folder can't re-seed stale data.
     */
    private suspend fun ensureSeeded(folderId: String) {
        if (folderId in seedChecked) return
        seedMutex.withLock {
            if (folderId in seedChecked) return
            if (!dao.folderExists(folderId)) {
                val legacy = legacyCacheFile(folderId)
                if (legacy.exists()) {
                    runCatching {
                        val cache = gson.fromJson(legacy.readText(), LegacyCache::class.java)
                        val entities = cache.items
                            .filter { it.driveId.isNotEmpty() }
                            .map { e ->
                                WardrobeItemEntity(
                                    driveId = e.driveId,
                                    folderId = folderId,
                                    name = e.name,
                                    tagsJson = e.tags?.let(gson::toJson),
                                    originalDriveId = e.originalDriveId,
                                    sidecarDriveId = e.sidecarDriveId,
                                    createdTimeMs = e.createdTimeMs,
                                )
                            }
                        dao.upsertAll(entities)
                        dao.insertFolder(WardrobeFolderEntity(folderId))
                        Log.d(TAG, "seeded folder=$folderId items=${entities.size} from legacy cache")
                    }.onFailure { Log.w(TAG, "legacy seed failed for folder=$folderId", it) }
                }
            }
            seedChecked += folderId
        }
    }

    private fun CachedWardrobeItem.toEntity(folderId: String) = WardrobeItemEntity(
        driveId = driveId,
        folderId = folderId,
        name = name,
        tagsJson = tags?.let(gson::toJson),
        originalDriveId = originalDriveId,
        sidecarDriveId = sidecarDriveId,
        createdTimeMs = createdTimeMs,
    )

    private fun WardrobeItemEntity.toItem() = CachedWardrobeItem(
        driveId = driveId,
        name = name,
        tags = tagsJson?.let { runCatching { gson.fromJson(it, ClothingTags::class.java) }.getOrNull() },
        originalDriveId = originalDriveId,
        sidecarDriveId = sidecarDriveId,
        createdTimeMs = createdTimeMs,
    )

    override suspend fun hasFolder(folderId: String): Boolean {
        ensureSeeded(folderId)
        return dao.folderExists(folderId)
    }

    override suspend fun itemsFor(folderId: String): List<CachedWardrobeItem> {
        ensureSeeded(folderId)
        return dao.itemsFor(folderId).map { it.toItem() }
    }

    override suspend fun replaceFolder(folderId: String, items: List<CachedWardrobeItem>) {
        ensureSeeded(folderId)
        dao.replaceFolder(folderId, items.map { it.toEntity(folderId) })
    }

    override suspend fun upsert(folderId: String, item: CachedWardrobeItem, staleDriveId: String?) {
        ensureSeeded(folderId)
        dao.upsertWithStale(item.toEntity(folderId), staleDriveId)
    }

    override suspend fun addAll(folderId: String, items: List<CachedWardrobeItem>) {
        if (items.isEmpty()) return
        ensureSeeded(folderId)
        dao.upsertAll(items.map { it.toEntity(folderId) })
        dao.insertFolder(WardrobeFolderEntity(folderId))
    }

    override suspend fun remove(folderId: String, ids: Set<String>) {
        if (ids.isEmpty()) return
        ensureSeeded(folderId)
        ids.chunked(DELETE_CHUNK).forEach { dao.deleteInFolder(folderId, it) }
    }

    override suspend fun clearFolder(folderId: String) {
        seedMutex.withLock {
            dao.clearFolder(folderId)
            legacyCacheFile(folderId).delete()
            seedChecked += folderId
        }
    }
}
