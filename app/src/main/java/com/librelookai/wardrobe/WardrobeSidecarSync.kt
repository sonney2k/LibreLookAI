package com.librelookai.wardrobe

import com.google.gson.Gson
import com.librelookai.data.drive.DriveRepository
import com.librelookai.data.drive.MutationHandler
import com.librelookai.data.drive.MutationOutcome
import com.librelookai.data.drive.upsertSidecar
import com.librelookai.data.local.PendingMutation
import com.librelookai.data.local.WardrobeItemStore
import dagger.Binds
import dagger.Module
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import dagger.multibindings.IntoSet
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow

/** Queue kind for "sync this item's tag sidecar to Drive" (see [WardrobeSidecarSyncHandler]). */
const val SIDECAR_SYNC_KIND = "wardrobe.sidecarSync"

/**
 * Drains queued sidecar saves (refactor § 2, first converted write path). The mutation carries
 * no payload — Room is the source of truth: the handler reads the item's *current* tags and
 * owning folder from [WardrobeItemStore] at apply time, so a retried save never resurrects
 * stale tags and a move between enqueue and drain lands the sidecar in the item's new folder.
 *
 * Outcomes: item no longer cached → [MutationOutcome.Permanent] (it was deleted; nothing to
 * roll back — the sidecar file died with it); any Drive failure → [MutationOutcome.Retry]
 * (the attempts cap turns a persistent failure into a give-up, and since Room already holds
 * the edit there is no local state to restore). On success the new sidecar id is stamped onto
 * the Room row and emitted on [sidecarSaved] so the WardrobeViewModel can patch its in-memory
 * state (move/delete use `sidecarDriveId` to relocate/remove the sidecar file).
 */
@Singleton
class WardrobeSidecarSyncHandler @Inject constructor(
    private val drive: DriveRepository,
    private val items: WardrobeItemStore,
) : MutationHandler {

    private val gson = Gson()

    private val _sidecarSaved = MutableSharedFlow<Pair<String, String>>(extraBufferCapacity = 16)

    /** `(itemDriveId, sidecarDriveId)` for every successfully applied sidecar save. */
    val sidecarSaved: SharedFlow<Pair<String, String>> = _sidecarSaved.asSharedFlow()

    override val kind: String = SIDECAR_SYNC_KIND

    override suspend fun apply(mutation: PendingMutation): MutationOutcome {
        val (folderId, item) = items.find(mutation.targetId)
            ?: return MutationOutcome.Permanent("item ${mutation.targetId} no longer cached")
        val json = gson.toJson(ItemSidecar(item.tags, item.originalDriveId))
        val sidecarId = try {
            drive.upsertSidecar(
                folderId,
                "${mutation.targetId}${DriveRepository.SIDECAR_SUFFIX}",
                json,
            )
        } catch (e: Exception) {
            return MutationOutcome.Retry(e.message ?: e.javaClass.simpleName)
        }
        items.setSidecarId(mutation.targetId, sidecarId)
        _sidecarSaved.tryEmit(mutation.targetId to sidecarId)
        return MutationOutcome.Success
    }
}

@Module
@InstallIn(SingletonComponent::class)
abstract class WardrobeSyncModule {
    @Binds
    @IntoSet
    abstract fun bindSidecarSyncHandler(impl: WardrobeSidecarSyncHandler): MutationHandler

    @Binds
    @IntoSet
    abstract fun bindDeleteSyncHandler(impl: WardrobeDeleteSyncHandler): MutationHandler

    @Binds
    @IntoSet
    abstract fun bindMoveSyncHandler(impl: WardrobeMoveSyncHandler): MutationHandler
}
