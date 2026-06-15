package com.librelookai.wardrobe

import com.librelookai.data.drive.SyncEngine
import com.librelookai.data.local.PendingMutationStore
import com.librelookai.data.local.WardrobeItemStore
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update

/**
 * Coil cache-buster overlay shared by the wardrobe VM's derived view and the bulk-maintenance
 * use-cases (refactor § 5 slice 6): bumped whenever an item's image *bytes* change under an
 * unchanged driveId (rotate / reprocess / cutout-fix / WebP convert). Versions are per-process
 * display state — the store doesn't hold them; the derivation merges this map in.
 */
@Singleton
class ItemVersions @Inject constructor() {
    private val _versions = MutableStateFlow<Map<String, Long>>(emptyMap())
    val versions: StateFlow<Map<String, Long>> = _versions.asStateFlow()

    fun bump(vararg driveIds: String) {
        if (driveIds.isEmpty()) return
        val now = System.currentTimeMillis()
        _versions.update { it + driveIds.associateWith { now } }
    }
}

/**
 * Queues the `wardrobe.sidecarSync` mutation for a driveId's *current* Room row (refactor § 2).
 * Callers must have written their edit to the store first — the row is what the handler (and
 * the derived view) read. Shared by the bulk use-cases (retag / remove-bg).
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
