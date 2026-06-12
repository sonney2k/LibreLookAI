package com.librelookai.data.local

import androidx.room.Dao
import androidx.room.Entity
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.PrimaryKey
import androidx.room.Query
import androidx.room.Transaction
import kotlinx.coroutines.flow.Flow

/**
 * A cached trip row. `id` (the trip's UUID) is the primary key; `json` is the Gson-serialized
 * [com.librelookai.data.model.Trip] (opaque, so the schema doesn't churn as the model evolves).
 * Trips are a single global list (one `_trips/` Drive folder), so there is no folder column.
 */
@Entity(tableName = "trips")
data class TripEntity(
    @PrimaryKey val id: String,
    val json: String,
)

/**
 * Single marker row: the trips list has been cached at least once (successor of the old
 * "`trips_cache.json` exists" check; gates the one-time legacy seed). The per-folder marker
 * pattern degenerates to one constant key for this global list.
 */
@Entity(tableName = "trip_markers")
data class TripMarkerEntity(@PrimaryKey val key: String)

@Dao
interface TripDao {
    @Query("SELECT * FROM trips")
    suspend fun trips(): List<TripEntity>

    @Query("SELECT * FROM trips")
    fun observeTrips(): Flow<List<TripEntity>>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertAll(trips: List<TripEntity>)

    @Query("DELETE FROM trips")
    suspend fun deleteAll()

    @Query("SELECT EXISTS(SELECT 1 FROM trip_markers WHERE `key` = :key)")
    suspend fun markerExists(key: String): Boolean

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertMarker(marker: TripMarkerEntity)

    @Transaction
    suspend fun replaceAll(marker: String, trips: List<TripEntity>) {
        deleteAll()
        upsertAll(trips)
        insertMarker(TripMarkerEntity(marker))
    }
}
