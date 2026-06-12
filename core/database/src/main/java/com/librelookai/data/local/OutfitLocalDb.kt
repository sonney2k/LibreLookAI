package com.librelookai.data.local

import androidx.room.Dao
import androidx.room.Entity
import androidx.room.Index
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.PrimaryKey
import androidx.room.Query
import androidx.room.Transaction
import kotlinx.coroutines.flow.Flow

/**
 * A cached outfit row. `id` (the outfit's UUID) is the primary key, so an outfit is homed in
 * exactly one folder — matching Drive, where it lives in exactly one closet's `_outfits.json`.
 * `json` is the Gson-serialized [com.librelookai.data.model.Outfit] (opaque, so the schema
 * doesn't churn as the model evolves; Gson back-fills new fields' defaults on read). The
 * model's `folderId` is `@Transient` and never inside `json` — it is restored from this row's
 * `folderId` column on read.
 */
@Entity(tableName = "outfits", indices = [Index("folderId")])
data class OutfitEntity(
    @PrimaryKey val id: String,
    val folderId: String,
    val json: String,
)

/**
 * Marker row: this folder's outfits have been cached at least once (successor of the old
 * "`styles_cache_{id}.json` exists" check; gates the one-time legacy seed).
 */
@Entity(tableName = "outfit_folders")
data class OutfitFolderEntity(@PrimaryKey val folderId: String)

@Dao
interface OutfitDao {
    @Query("SELECT * FROM outfits WHERE folderId = :folderId")
    suspend fun outfitsFor(folderId: String): List<OutfitEntity>

    @Query("SELECT * FROM outfits WHERE folderId IN (:folderIds)")
    fun observeFolders(folderIds: List<String>): Flow<List<OutfitEntity>>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertAll(outfits: List<OutfitEntity>)

    @Query("DELETE FROM outfits WHERE folderId = :folderId")
    suspend fun deleteFolderOutfits(folderId: String)

    @Query("SELECT EXISTS(SELECT 1 FROM outfit_folders WHERE folderId = :folderId)")
    suspend fun folderExists(folderId: String): Boolean

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertFolder(folder: OutfitFolderEntity)

    @Transaction
    suspend fun replaceFolder(folderId: String, outfits: List<OutfitEntity>) {
        deleteFolderOutfits(folderId)
        upsertAll(outfits)
        insertFolder(OutfitFolderEntity(folderId))
    }
}
