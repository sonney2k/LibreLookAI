package com.librelookai.wardrobe

import com.google.gson.Gson
import com.librelookai.data.drive.DriveService
import com.librelookai.data.drive.MutationHandler
import com.librelookai.data.drive.MutationOutcome
import com.librelookai.data.local.PendingMutation
import com.librelookai.data.local.WardrobeItemStore
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow

/** Queue kind for "move this item's Drive file triplet between closets" (see [WardrobeMoveSyncHandler]). */
const val ITEM_MOVE_KIND = "wardrobe.moveItem"

/**
 * Payload of an [ITEM_MOVE_KIND] mutation. Carries the from→to pair (the Drive PATCH needs the
 * parent being removed, and FIFO drains make chained moves correct: A→B drains before B→C) plus
 * the sub-file ids as known at enqueue time. A sidecar that only exists as a *pending*
 * `sidecarSync` rides as null here — that earlier mutation drains first and, re-reading the
 * already re-homed row, creates the sidecar directly in the target folder.
 */
data class MoveItemPayload(
    val sourceFolderId: String,
    val targetFolderId: String,
    val originalDriveId: String? = null,
    val sidecarDriveId: String? = null,
)

/** Emitted when a move gave up permanently and the Room re-home was reverted. */
data class MoveRollback(val driveId: String, val sourceFolderId: String, val targetFolderId: String)

/**
 * Drains queued item moves (refactor § 2). The optimistic local re-home (state + Room) already
 * happened at enqueue time; this replays the Drive parent PATCHes. The cutout move decides the
 * outcome; original/sidecar moves stay best-effort `runCatching`, exactly like the old inline
 * path. Re-running after a client timeout is safe: Drive ignores `removeParents` for a
 * non-parent and re-adding an existing parent is a no-op.
 *
 * Outcomes: item row gone → [MutationOutcome.Permanent] (deleted after the move was queued; the
 * delete mutation behind us removes the files wherever they live); network failure →
 * [MutationOutcome.Retry]. On attempt exhaustion [rollback] re-homes the Room row back to the
 * source folder (only if it still sits in this mutation's target — a later move supersedes) and
 * emits [moveRolledBack] so the WardrobeViewModel can undo the UI part (recently-moved marker,
 * splice back into view, error banner).
 */
@Singleton
class WardrobeMoveSyncHandler @Inject constructor(
    private val drive: DriveService,
    private val items: WardrobeItemStore,
) : MutationHandler {

    private val gson = Gson()

    private val _moveRolledBack = MutableSharedFlow<MoveRollback>(extraBufferCapacity = 16)
    val moveRolledBack: SharedFlow<MoveRollback> = _moveRolledBack.asSharedFlow()

    override val kind: String = ITEM_MOVE_KIND

    override suspend fun apply(mutation: PendingMutation): MutationOutcome {
        val payload = runCatching { gson.fromJson(mutation.payload, MoveItemPayload::class.java) }
            .getOrNull() ?: return MutationOutcome.Permanent("unparseable payload: ${mutation.payload}")
        if (items.find(mutation.targetId) == null) {
            return MutationOutcome.Permanent("item ${mutation.targetId} no longer cached")
        }
        try {
            drive.moveFile(mutation.targetId, payload.sourceFolderId, payload.targetFolderId)
        } catch (e: Exception) {
            return MutationOutcome.Retry(e.message ?: e.javaClass.simpleName)
        }
        payload.originalDriveId?.let {
            runCatching { drive.moveFile(it, payload.sourceFolderId, payload.targetFolderId) }
        }
        payload.sidecarDriveId?.let {
            runCatching { drive.moveFile(it, payload.sourceFolderId, payload.targetFolderId) }
        }
        return MutationOutcome.Success
    }

    override suspend fun rollback(mutation: PendingMutation) {
        val payload = runCatching { gson.fromJson(mutation.payload, MoveItemPayload::class.java) }
            .getOrNull() ?: return
        val (currentFolder, item) = items.find(mutation.targetId) ?: return
        if (currentFolder == payload.targetFolderId) {
            items.addAll(payload.sourceFolderId, listOf(item))
            _moveRolledBack.tryEmit(
                MoveRollback(mutation.targetId, payload.sourceFolderId, payload.targetFolderId),
            )
        }
    }
}
