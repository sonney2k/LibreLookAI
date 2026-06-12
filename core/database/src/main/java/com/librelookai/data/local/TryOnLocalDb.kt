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
 * A cached try-on row. `id` (the try-on's UUID) is the primary key; `json` is the
 * Gson-serialized [com.librelookai.data.model.TryOn] (opaque, so the schema doesn't churn as
 * the model evolves — `@Transient itemIds`/`localPath` are never serialized). Try-ons are a
 * single global list (one `_tryons.json` at the Drive root), so there is no folder column.
 */
@Entity(tableName = "tryons")
data class TryOnEntity(
    @PrimaryKey val id: String,
    val json: String,
)

/**
 * Single marker row: the try-on list has been cached at least once (successor of the old
 * "`tryons_cache.json` exists" check; gates the one-time legacy seed). The per-folder marker
 * pattern degenerates to one constant key for this global list.
 */
@Entity(tableName = "tryon_markers")
data class TryOnMarkerEntity(@PrimaryKey val key: String)

@Dao
interface TryOnDao {
    @Query("SELECT * FROM tryons")
    suspend fun tryOns(): List<TryOnEntity>

    @Query("SELECT * FROM tryons")
    fun observeTryOns(): Flow<List<TryOnEntity>>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertAll(tryOns: List<TryOnEntity>)

    @Query("DELETE FROM tryons")
    suspend fun deleteAll()

    @Query("SELECT EXISTS(SELECT 1 FROM tryon_markers WHERE `key` = :key)")
    suspend fun markerExists(key: String): Boolean

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertMarker(marker: TryOnMarkerEntity)

    @Transaction
    suspend fun replaceAll(marker: String, tryOns: List<TryOnEntity>) {
        deleteAll()
        upsertAll(tryOns)
        insertMarker(TryOnMarkerEntity(marker))
    }
}
