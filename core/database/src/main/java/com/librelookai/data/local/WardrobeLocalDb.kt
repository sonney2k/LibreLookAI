package com.librelookai.data.local

import androidx.room.Dao
import androidx.room.Database
import androidx.room.Entity
import androidx.room.Index
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.PrimaryKey
import androidx.room.Query
import androidx.room.RoomDatabase
import androidx.room.Transaction
import kotlinx.coroutines.flow.Flow

/**
 * A cached wardrobe item row. `driveId` (the cutout's Drive file ID) is the primary key, so an
 * item can only ever be homed in one folder — upserting under a new `folderId` atomically
 * relocates it. `tagsJson` is the Gson-serialized [com.librelookai.gemini.ClothingTags] (kept as
 * an opaque string so the schema doesn't churn when tag dimensions evolve).
 */
@Entity(tableName = "wardrobe_items", indices = [Index("folderId")])
data class WardrobeItemEntity(
    @PrimaryKey val driveId: String,
    val folderId: String,
    val name: String,
    val tagsJson: String?,
    val originalDriveId: String?,
    val sidecarDriveId: String?,
    val createdTimeMs: Long,
)

/**
 * Marker row: this folder has been cached at least once (successor of the old
 * "`wardrobe_cache_{id}.json` exists" check that gates prefetch / restore decisions).
 */
@Entity(tableName = "wardrobe_folders")
data class WardrobeFolderEntity(@PrimaryKey val folderId: String)

@Dao
interface WardrobeItemDao {
    @Query("SELECT * FROM wardrobe_items WHERE folderId = :folderId")
    suspend fun itemsFor(folderId: String): List<WardrobeItemEntity>

    @Query("SELECT * FROM wardrobe_items WHERE folderId IN (:folderIds)")
    fun observeItems(folderIds: List<String>): Flow<List<WardrobeItemEntity>>

    @Query("SELECT * FROM wardrobe_items WHERE driveId = :driveId")
    suspend fun itemById(driveId: String): WardrobeItemEntity?

    @Query("UPDATE wardrobe_items SET sidecarDriveId = :sidecarId WHERE driveId = :driveId")
    suspend fun setSidecarId(driveId: String, sidecarId: String)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertAll(items: List<WardrobeItemEntity>)

    @Query("DELETE FROM wardrobe_items WHERE driveId = :driveId")
    suspend fun deleteById(driveId: String)

    @Query("DELETE FROM wardrobe_items WHERE folderId = :folderId AND driveId IN (:ids)")
    suspend fun deleteInFolder(folderId: String, ids: List<String>)

    @Query("DELETE FROM wardrobe_items WHERE folderId = :folderId")
    suspend fun deleteFolderItems(folderId: String)

    @Query("SELECT EXISTS(SELECT 1 FROM wardrobe_folders WHERE folderId = :folderId)")
    suspend fun folderExists(folderId: String): Boolean

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertFolder(folder: WardrobeFolderEntity)

    @Query("DELETE FROM wardrobe_folders WHERE folderId = :folderId")
    suspend fun deleteFolder(folderId: String)

    @Transaction
    suspend fun replaceFolder(folderId: String, items: List<WardrobeItemEntity>) {
        deleteFolderItems(folderId)
        upsertAll(items)
        insertFolder(WardrobeFolderEntity(folderId))
    }

    @Transaction
    suspend fun upsertWithStale(item: WardrobeItemEntity, staleDriveId: String?) {
        staleDriveId?.let { deleteById(it) }
        upsertAll(listOf(item))
        insertFolder(WardrobeFolderEntity(item.folderId))
    }

    @Transaction
    suspend fun clearFolder(folderId: String) {
        deleteFolderItems(folderId)
        deleteFolder(folderId)
    }
}

@Database(
    entities = [
        WardrobeItemEntity::class, WardrobeFolderEntity::class,
        OutfitEntity::class, OutfitFolderEntity::class,
        OutfitEventEntity::class, OutfitEventFolderEntity::class,
        TripEntity::class, TripMarkerEntity::class,
        TryOnEntity::class, TryOnMarkerEntity::class,
        PendingMutationEntity::class,
    ],
    version = 6,
    exportSchema = false,
)
abstract class LocalDatabase : RoomDatabase() {
    abstract fun wardrobeItemDao(): WardrobeItemDao
    abstract fun outfitDao(): OutfitDao
    abstract fun outfitEventDao(): OutfitEventDao
    abstract fun tripDao(): TripDao
    abstract fun tryOnDao(): TryOnDao
    abstract fun pendingMutationDao(): PendingMutationDao
}
