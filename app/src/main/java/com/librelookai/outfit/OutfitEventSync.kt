package com.librelookai.outfit

import com.google.gson.Gson
import com.librelookai.data.drive.DriveRepository
import com.librelookai.data.drive.MutationHandler
import com.librelookai.data.drive.MutationOutcome
import com.librelookai.data.drive.saveOutfitEventsJson
import com.librelookai.data.local.OutfitEventStore
import com.librelookai.data.local.PendingMutation
import javax.inject.Inject
import javax.inject.Singleton

/** Queue kind for "rewrite this folder's outfit-events JSON from the store" (see [OutfitEventSyncHandler]). */
const val OUTFIT_EVENT_SYNC_KIND = "outfitEvent.syncFolder"

/**
 * Drains queued calendar-wear saves (refactor § 2) — the events twin of [OutfitFolderSyncHandler].
 * Payload-free: the handler reads the folder's *current* [OutfitEventStore] rows at apply time
 * and writes them as the folder's outfit-events JSON, so coalesced/retried drains always land
 * the latest snapshot.
 *
 * **Wipe guard**: refuses ([MutationOutcome.Permanent]) to write a folder with no
 * [OutfitEventStore.hasFolder] marker — an empty read there means "unknown", not "no wears",
 * and writing `[]` would destroy the user's calendar history on Drive. Any Drive failure →
 * [MutationOutcome.Retry]; no rollback state (Room already holds the edit).
 */
@Singleton
class OutfitEventSyncHandler @Inject constructor(
    private val drive: DriveRepository,
    private val events: OutfitEventStore,
) : MutationHandler {

    private val gson = Gson()

    override val kind: String = OUTFIT_EVENT_SYNC_KIND

    override suspend fun apply(mutation: PendingMutation): MutationOutcome {
        val folderId = mutation.targetId
        // eventsFor runs the one-time legacy seed, so hasFolder sees a seeded marker too.
        val folderEvents = events.eventsFor(folderId)
        if (!events.hasFolder(folderId)) {
            return MutationOutcome.Permanent("folder $folderId never cached — refusing to write")
        }
        return try {
            drive.saveOutfitEventsJson(folderId, gson.toJson(folderEvents))
            MutationOutcome.Success
        } catch (e: Exception) {
            MutationOutcome.Retry(e.message ?: e.javaClass.simpleName)
        }
    }
}
