package com.librelookai.data.local

import android.content.Context
import android.util.Log
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import com.librelookai.data.model.Outfit
import dagger.hilt.android.qualifiers.ApplicationContext
import java.io.File
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

private const val TAG = "OutfitStore"

@Singleton
class RoomOutfitStore @Inject constructor(
    @param:ApplicationContext private val context: Context,
    private val dao: OutfitDao,
) : OutfitStore {

    private val gson = Gson()
    private val seedMutex = Mutex()

    /** Folders whose seed check already ran this process — skips re-statting absent files. */
    private val seedChecked = mutableSetOf<String>()

    private fun legacyCacheFile(folderId: String) =
        File(context.filesDir, "styles_cache_$folderId.json")

    /**
     * One-time-per-folder import of the legacy `styles_cache_{folderId}.json` (a bare
     * `List<Outfit>` array). Mirrors [RoomWardrobeItemStore.ensureSeeded]: the marker row is
     * only written when a legacy file actually existed, and once present the seed never runs
     * again. The legacy file is kept on disk as a downgrade safety net. A duplicate outfit id
     * across several legacy files (the old empty-`folderId` duplication) collapses to a single
     * home via the primary key; the next Drive sync re-homes it correctly.
     */
    private suspend fun ensureSeeded(folderId: String) {
        if (folderId in seedChecked) return
        seedMutex.withLock {
            if (folderId in seedChecked) return
            if (!dao.folderExists(folderId)) {
                val legacy = legacyCacheFile(folderId)
                if (legacy.exists()) {
                    runCatching {
                        val type = object : TypeToken<List<Outfit>>() {}.type
                        val outfits: List<Outfit> =
                            gson.fromJson(legacy.readText(), type) ?: emptyList()
                        dao.upsertAll(outfits.map { it.toEntity(folderId) })
                        dao.insertFolder(OutfitFolderEntity(folderId))
                        Log.d(TAG, "seeded folder=$folderId outfits=${outfits.size} from legacy cache")
                    }.onFailure { Log.w(TAG, "legacy seed failed for folder=$folderId", it) }
                }
            }
            seedChecked += folderId
        }
    }

    private fun Outfit.toEntity(folderId: String) =
        OutfitEntity(id = id, folderId = folderId, json = gson.toJson(this))

    private fun OutfitEntity.toOutfit(): Outfit? =
        runCatching { gson.fromJson(json, Outfit::class.java)?.copy(folderId = folderId) }
            .getOrNull()

    override suspend fun outfitsFor(folderId: String): List<Outfit> {
        ensureSeeded(folderId)
        return dao.outfitsFor(folderId).mapNotNull { it.toOutfit() }
    }

    override suspend fun replaceFolder(folderId: String, outfits: List<Outfit>) {
        ensureSeeded(folderId)
        dao.replaceFolder(folderId, outfits.map { it.toEntity(folderId) })
    }

    override suspend fun hasFolder(folderId: String): Boolean {
        ensureSeeded(folderId)
        return dao.folderExists(folderId)
    }
}
