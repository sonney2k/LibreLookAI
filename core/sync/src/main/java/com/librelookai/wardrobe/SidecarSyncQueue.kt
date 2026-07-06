package com.librelookai.wardrobe

import com.librelookai.data.drive.SyncEngine
import com.librelookai.data.local.PendingMutationStore
import com.librelookai.data.local.WardrobeItemStore
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Queues the `wardrobe.sidecarSync` mutation for a driveId's *current* Room row (refactor § 2).
 * Callers must have written their edit to the store first — the row is what the handler (and
 * the derived view) read. Shared by the wardrobe/shopping tag funnels and the bulk use-cases
 * (retag / remove-bg).
 */
@Singleton
class SidecarSyncQueue @Inject constructor(
    private val itemStore: WardrobeItemStore,
    private val mutationStore: PendingMutationStore,
    private val syncEngine: SyncEngine,
) {
    suspend fun enqueue(driveId: String) {
        val fid = itemStore.find(driveId)?.first ?: return
        mutationStore.enqueue(SIDECAR_SYNC_KIND, targetId = driveId, folderId = fid, payload = "{}")
        syncEngine.drain()
    }
}
