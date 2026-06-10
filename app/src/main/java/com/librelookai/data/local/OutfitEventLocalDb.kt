package com.librelookai.data.local

import androidx.room.Dao
import androidx.room.Entity
import androidx.room.Index
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.PrimaryKey
import androidx.room.Query
import androidx.room.Transaction

/**
 * A cached calendar-wear row. `id` (the event's UUID) is the primary key; `json` is the
 * Gson-serialized [com.librelookai.data.model.OutfitEvent] (opaque, so the schema doesn't
 * churn as the denormalised wear snapshot evolves — Gson back-fills new fields' defaults via
 * the generated no-arg ctor, which is load-bearing for old events).
 */
@Entity(tableName = "outfit_events", indices = [Index("folderId")])
data class OutfitEventEntity(
    @PrimaryKey val id: String,
    val folderId: String,
    val json: String,
)

/**
 * Marker row: this folder's events have been cached at least once (successor of the old
 * "`outfit_events_cache_{id}.json` exists" check; gates the one-time legacy seed).
 */
@Entity(tableName = "outfit_event_folders")
data class OutfitEventFolderEntity(@PrimaryKey val folderId: String)

@Dao
interface OutfitEventDao {
    @Query("SELECT * FROM outfit_events WHERE folderId = :folderId")
    suspend fun eventsFor(folderId: String): List<OutfitEventEntity>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertAll(events: List<OutfitEventEntity>)

    @Query("DELETE FROM outfit_events WHERE folderId = :folderId")
    suspend fun deleteFolderEvents(folderId: String)

    @Query("SELECT EXISTS(SELECT 1 FROM outfit_event_folders WHERE folderId = :folderId)")
    suspend fun folderExists(folderId: String): Boolean

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertFolder(folder: OutfitEventFolderEntity)

    @Transaction
    suspend fun replaceFolder(folderId: String, events: List<OutfitEventEntity>) {
        deleteFolderEvents(folderId)
        upsertAll(events)
        insertFolder(OutfitEventFolderEntity(folderId))
    }
}
