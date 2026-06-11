package com.librelookai.data.local

import javax.inject.Inject
import javax.inject.Singleton

/**
 * A queued Drive-bound write, as seen by the `SyncEngine` and its `MutationHandler`s
 * (Room-free mirror of [PendingMutationEntity]).
 */
data class PendingMutation(
    val id: Long,
    /** Handler key, e.g. `"wardrobe.tagEdit"` — matched against `MutationHandler.kind`. */
    val kind: String,
    /** The mutated entity's stable id (Drive id / outfit id / trip id). */
    val targetId: String,
    /** The *owning* folder at enqueue time (the cache-folder rule), null for global lists. */
    val folderId: String?,
    /** Opaque JSON arguments to replay the Drive write. */
    val payload: String,
    /** Opaque JSON pre-mutation state to restore on permanent failure (null = nothing to undo). */
    val rollback: String?,
    val attempts: Int,
    val lastError: String?,
    val createdMs: Long,
)

/**
 * FIFO queue of Drive-bound writes (refactor § 2). Writers enqueue after their optimistic local
 * update; the `SyncEngine` (`:core:sync`) drains oldest-first. Strict global FIFO — not
 * per-target — so cross-entity ordering (e.g. "create folder, then move item into it") is
 * preserved without handlers having to declare dependencies.
 */
interface PendingMutationStore {
    /** Appends a mutation; returns its queue id. */
    suspend fun enqueue(
        kind: String,
        targetId: String,
        folderId: String?,
        payload: String,
        rollback: String? = null,
    ): Long

    /** The next mutation to apply (lowest id), or null when the queue is empty. */
    suspend fun oldest(): PendingMutation?

    /** Every queued mutation, oldest first (diagnostics / tests). */
    suspend fun all(): List<PendingMutation>

    /** Removes a drained (applied, or permanently failed + rolled back) mutation. */
    suspend fun remove(id: Long)

    /** Records a retryable failure: increments `attempts`, stores `lastError`. */
    suspend fun recordFailure(id: Long, error: String)

    suspend fun count(): Int
}

@Singleton
class RoomPendingMutationStore @Inject constructor(
    private val dao: PendingMutationDao,
) : PendingMutationStore {

    override suspend fun enqueue(
        kind: String,
        targetId: String,
        folderId: String?,
        payload: String,
        rollback: String?,
    ): Long = dao.insert(
        PendingMutationEntity(
            kind = kind,
            targetId = targetId,
            folderId = folderId,
            payload = payload,
            rollback = rollback,
            createdMs = System.currentTimeMillis(),
        ),
    )

    override suspend fun oldest(): PendingMutation? = dao.oldest()?.toModel()

    override suspend fun all(): List<PendingMutation> = dao.all().map { it.toModel() }

    override suspend fun remove(id: Long) = dao.delete(id)

    override suspend fun recordFailure(id: Long, error: String) = dao.recordFailure(id, error)

    override suspend fun count(): Int = dao.count()

    private fun PendingMutationEntity.toModel() = PendingMutation(
        id = id,
        kind = kind,
        targetId = targetId,
        folderId = folderId,
        payload = payload,
        rollback = rollback,
        attempts = attempts,
        lastError = lastError,
        createdMs = createdMs,
    )
}
