package com.librelookai.data.local

import android.content.Context
import android.util.Log
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import com.librelookai.data.model.Trip
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

private const val TAG = "TripStore"
private const val SEED_MARKER = "trips"

@Singleton
class RoomTripStore @Inject constructor(
    @param:ApplicationContext private val context: Context,
    private val dao: TripDao,
) : TripStore {

    private val gson = Gson()
    private val seedMutex = Mutex()

    /** True once the seed check ran this process — skips re-statting an absent file. */
    private var seedChecked = false

    private fun legacyCacheFile() = File(context.filesDir, "trips_cache.json")

    /**
     * One-time import of the legacy `trips_cache.json` (a bare `List<Trip>` array). Mirrors
     * [RoomOutfitStore.ensureSeeded]: the marker row is only written when the legacy file
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
                        val type = object : TypeToken<List<Trip>>() {}.type
                        val trips: List<Trip> = gson.fromJson(legacy.readText(), type) ?: emptyList()
                        dao.upsertAll(trips.map { it.toEntity() })
                        dao.insertMarker(TripMarkerEntity(SEED_MARKER))
                        Log.d(TAG, "seeded trips=${trips.size} from legacy cache")
                    }.onFailure { Log.w(TAG, "legacy seed failed", it) }
                }
            }
            seedChecked = true
        }
    }

    private fun Trip.toEntity() = TripEntity(id = id, json = gson.toJson(this))

    private fun TripEntity.toTrip(): Trip? =
        runCatching { gson.fromJson(json, Trip::class.java) }.getOrNull()

    override suspend fun trips(): List<Trip> {
        ensureSeeded()
        return dao.trips().mapNotNull { it.toTrip() }
    }

    override fun observeTrips(): Flow<List<Trip>> = flow {
        ensureSeeded()
        emitAll(
            dao.observeTrips()
                .map { rows -> rows.mapNotNull { it.toTrip() } }
                .distinctUntilChanged(),
        )
    }

    override suspend fun replaceAll(trips: List<Trip>) {
        ensureSeeded()
        dao.replaceAll(SEED_MARKER, trips.map { it.toEntity() })
    }
}
