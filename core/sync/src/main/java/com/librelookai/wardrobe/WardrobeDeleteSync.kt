package com.librelookai.wardrobe

import com.google.gson.Gson
import com.librelookai.data.drive.DriveService
import com.librelookai.data.drive.MutationHandler
import com.librelookai.data.drive.MutationOutcome
import com.librelookai.data.local.PendingMutation
import javax.inject.Inject
import javax.inject.Singleton

/** Queue kind for "delete this item's Drive file triplet" (see [WardrobeDeleteSyncHandler]). */
const val ITEM_DELETE_KIND = "wardrobe.deleteItem"

/**
 * Payload of an [ITEM_DELETE_KIND] mutation. Unlike the payload-free sidecar sync, the delete
 * must carry its file ids: the optimistic local update removes the item's Room row *before*
 * the drain, so there is nothing left to re-read at apply time.
 */
data class DeleteItemPayload(val fileIds: List<String>)

/**
 * Drains queued item deletions (refactor § 2). The old inline path was fire-and-forget
 * (`runCatching { drive.deleteFile(...) }` per file) — an offline or failed delete silently
 * orphaned the Drive files forever (until a Repair & Sync). Queued deletes retry instead.
 *
 * Outcomes: any network failure → [MutationOutcome.Retry] (re-running is safe — Drive's DELETE
 * of an already-deleted file returns an ignored 404, so partial progress just re-covers the
 * done ids); an unparseable payload → [MutationOutcome.Permanent]. No rollback: the deletion
 * is locally committed at enqueue time, exactly like the old best-effort path.
 */
@Singleton
class WardrobeDeleteSyncHandler @Inject constructor(
    private val drive: DriveService,
) : MutationHandler {

    private val gson = Gson()

    override val kind: String = ITEM_DELETE_KIND

    override suspend fun apply(mutation: PendingMutation): MutationOutcome {
        val payload = runCatching { gson.fromJson(mutation.payload, DeleteItemPayload::class.java) }
            .getOrNull() ?: return MutationOutcome.Permanent("unparseable payload: ${mutation.payload}")
        for (fileId in payload.fileIds) {
            try {
                drive.deleteFile(fileId)
            } catch (e: Exception) {
                return MutationOutcome.Retry(e.message ?: e.javaClass.simpleName)
            }
        }
        return MutationOutcome.Success
    }
}
