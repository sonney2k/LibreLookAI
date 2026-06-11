package com.librelookai.data.local

import android.content.Context
import android.util.Log
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import com.librelookai.data.model.OutfitEvent
import dagger.hilt.android.qualifiers.ApplicationContext
import java.io.File
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

private const val TAG = "OutfitEventStore"

@Singleton
class RoomOutfitEventStore @Inject constructor(
    @param:ApplicationContext private val context: Context,
    private val dao: OutfitEventDao,
) : OutfitEventStore {

    private val gson = Gson()
    private val seedMutex = Mutex()

    /** Folders whose seed check already ran this process — skips re-statting absent files. */
    private val seedChecked = mutableSetOf<String>()

    private fun legacyCacheFile(folderId: String) =
        File(context.filesDir, "outfit_events_cache_$folderId.json")

    /**
     * One-time-per-folder import of the legacy `outfit_events_cache_{folderId}.json` (a bare
     * `List<OutfitEvent>` array). Mirrors [RoomOutfitStore.ensureSeeded]: the marker row is
     * only written when a legacy file actually existed, and once present the seed never runs
     * again. The legacy file is kept on disk as a downgrade safety net.
     */
    private suspend fun ensureSeeded(folderId: String) {
        if (folderId in seedChecked) return
        seedMutex.withLock {
            if (folderId in seedChecked) return
            if (!dao.folderExists(folderId)) {
                val legacy = legacyCacheFile(folderId)
                if (legacy.exists()) {
                    runCatching {
                        val type = object : TypeToken<List<OutfitEvent>>() {}.type
                        val events: List<OutfitEvent> =
                            gson.fromJson(legacy.readText(), type) ?: emptyList()
                        dao.upsertAll(events.map { it.toEntity(folderId) })
                        dao.insertFolder(OutfitEventFolderEntity(folderId))
                        Log.d(TAG, "seeded folder=$folderId events=${events.size} from legacy cache")
                    }.onFailure { Log.w(TAG, "legacy seed failed for folder=$folderId", it) }
                }
            }
            seedChecked += folderId
        }
    }

    private fun OutfitEvent.toEntity(folderId: String) =
        OutfitEventEntity(id = id, folderId = folderId, json = gson.toJson(this))

    private fun OutfitEventEntity.toEvent(): OutfitEvent? =
        runCatching { gson.fromJson(json, OutfitEvent::class.java) }.getOrNull()

    override suspend fun eventsFor(folderId: String): List<OutfitEvent> {
        ensureSeeded(folderId)
        return dao.eventsFor(folderId).mapNotNull { it.toEvent() }
    }

    override suspend fun replaceFolder(folderId: String, events: List<OutfitEvent>) {
        ensureSeeded(folderId)
        dao.replaceFolder(folderId, events.map { it.toEntity(folderId) })
    }

    override suspend fun hasFolder(folderId: String): Boolean {
        ensureSeeded(folderId)
        return dao.folderExists(folderId)
    }
}
