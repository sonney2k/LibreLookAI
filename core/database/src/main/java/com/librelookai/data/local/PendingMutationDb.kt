package com.librelookai.data.local

import androidx.room.Dao
import androidx.room.Entity
import androidx.room.Insert
import androidx.room.PrimaryKey
import androidx.room.Query

/**
 * One queued Drive-bound write (refactor § 2). A mutation is recorded *after* the optimistic
 * Room/in-memory update and drained to Drive by the `SyncEngine` in `:core:sync`, oldest-first.
 * `payload` / `rollback` are opaque JSON owned by the feature's `MutationHandler` (same
 * schema-stability rationale as the opaque-JSON cache rows): `payload` carries the arguments to
 * replay the Drive write, `rollback` the pre-mutation state to restore when the write fails
 * permanently.
 */
@Entity(tableName = "pending_mutations")
data class PendingMutationEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val kind: String,
    val targetId: String,
    val folderId: String?,
    val payload: String,
    val rollback: String?,
    val attempts: Int = 0,
    val lastError: String? = null,
    val createdMs: Long,
)

@Dao
interface PendingMutationDao {
    @Insert
    suspend fun insert(row: PendingMutationEntity): Long

    @Query("SELECT * FROM pending_mutations ORDER BY id ASC LIMIT 1")
    suspend fun oldest(): PendingMutationEntity?

    @Query("SELECT * FROM pending_mutations ORDER BY id ASC")
    suspend fun all(): List<PendingMutationEntity>

    @Query("DELETE FROM pending_mutations WHERE id = :id")
    suspend fun delete(id: Long)

    @Query("UPDATE pending_mutations SET attempts = attempts + 1, lastError = :error WHERE id = :id")
    suspend fun recordFailure(id: Long, error: String)

    @Query("SELECT COUNT(*) FROM pending_mutations")
    suspend fun count(): Int
}
