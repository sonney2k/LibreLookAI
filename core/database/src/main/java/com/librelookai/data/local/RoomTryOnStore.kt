package com.librelookai.data.local

import android.content.Context
import android.util.Log
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import com.librelookai.data.model.TryOn
import dagger.hilt.android.qualifiers.ApplicationContext
import java.io.File
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.emitAll
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

private const val TAG = "TryOnStore"
private const val SEED_MARKER = "tryons"

@Singleton
class RoomTryOnStore @Inject constructor(
    @param:ApplicationContext private val context: Context,
    private val dao: TryOnDao,
) : TryOnStore {

    private val gson = Gson()
    private val seedMutex = Mutex()

    /** True once the seed check ran this process — skips re-statting an absent file. */
    private var seedChecked = false

    private fun legacyCacheFile() = File(context.filesDir, "tryons_cache.json")

    /**
     * One-time import of the legacy `tryons_cache.json` (a bare `List<TryOn>` array). Mirrors
     * [RoomTripStore.ensureSeeded]: the marker row is only written when the legacy file
     * actually existed, and once present the seed never runs again. The legacy file is kept
     * on disk as a downgrade safety net.
     */
    private suspend fun ensureSeeded() {
        if (seedChecked) return
        seedMutex.withLock {
            if (seedChecked) return
            if (!dao.markerExists(SEED_MARKER)) {
                val legacy = legacyCacheFile()
                if (legacy.exists()) {
                    runCatching {
                        val type = object : TypeToken<List<TryOn>>() {}.type
                        val tryOns: List<TryOn> = gson.fromJson(legacy.readText(), type) ?: emptyList()
                        dao.upsertAll(tryOns.map { it.toEntity() })
                        dao.insertMarker(TryOnMarkerEntity(SEED_MARKER))
                        Log.d(TAG, "seeded tryOns=${tryOns.size} from legacy cache")
                    }.onFailure { Log.w(TAG, "legacy seed failed", it) }
                }
            }
            seedChecked = true
        }
    }

    private fun TryOn.toEntity() = TryOnEntity(id = id, json = gson.toJson(this))

    private fun TryOnEntity.toTryOn(): TryOn? =
        runCatching { gson.fromJson(json, TryOn::class.java) }.getOrNull()

    override suspend fun tryOns(): List<TryOn> {
        ensureSeeded()
        return dao.tryOns().mapNotNull { it.toTryOn() }
    }

    override fun observeTryOns(): Flow<List<TryOn>> = flow {
        ensureSeeded()
        emitAll(
            dao.observeTryOns()
                .map { rows -> rows.mapNotNull { it.toTryOn() } }
                .distinctUntilChanged(),
        )
    }

    override suspend fun replaceAll(tryOns: List<TryOn>) {
        ensureSeeded()
        dao.replaceAll(SEED_MARKER, tryOns.map { it.toEntity() })
    }
}
