package com.librelookai.outfit

import com.google.gson.Gson
import com.librelookai.data.drive.DriveService
import com.librelookai.data.drive.MutationHandler
import com.librelookai.data.drive.MutationOutcome
import com.librelookai.data.local.OutfitStore
import com.librelookai.data.local.PendingMutation
import dagger.Binds
import dagger.Module
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import dagger.multibindings.IntoSet
import javax.inject.Inject
import javax.inject.Singleton

/** Queue kind for "rewrite this folder's `_outfits.json` from the store" (see [OutfitFolderSyncHandler]). */
const val OUTFIT_FOLDER_SYNC_KIND = "outfit.syncFolder"

/**
 * Drains queued whole-file outfit saves (refactor § 2). Payload-free — Room is the source of
 * truth: the handler reads the folder's *current* [OutfitStore] rows at apply time and writes
 * them as the folder's `_outfits.json`, so coalesced/retried drains always land the latest
 * snapshot and `Outfit.folderId` being `@Transient` makes store-row serialization identical to
 * the old per-site Drive serialization.
 *
 * **Wipe guard**: a folder with no [OutfitStore.hasFolder] marker was never cached — an empty
 * read there means "unknown", not "no outfits", and writing `[]` would destroy the user's
 * outfits on Drive. The handler refuses with [MutationOutcome.Permanent] (enqueuers write the
 * store first, so a legitimate mutation always finds the marker). Any Drive failure →
 * [MutationOutcome.Retry]; there is no rollback state (Room already holds the edit — after
 * attempt exhaustion the edit stays local and re-syncs with the folder's next save/load).
 */
@Singleton
class OutfitFolderSyncHandler @Inject constructor(
    private val drive: DriveService,
    private val outfits: OutfitStore,
) : MutationHandler {

    private val gson = Gson()

    override val kind: String = OUTFIT_FOLDER_SYNC_KIND

    override suspend fun apply(mutation: PendingMutation): MutationOutcome {
        val folderId = mutation.targetId
        // outfitsFor runs the one-time legacy seed, so hasFolder sees a seeded marker too.
        val folderOutfits = outfits.outfitsFor(folderId)
        if (!outfits.hasFolder(folderId)) {
            return MutationOutcome.Permanent("folder $folderId never cached — refusing to write")
        }
        return try {
            drive.saveOutfitsJson(folderId, gson.toJson(folderOutfits))
            MutationOutcome.Success
        } catch (e: Exception) {
            MutationOutcome.Retry(e.message ?: e.javaClass.simpleName)
        }
    }
}

@Module
@InstallIn(SingletonComponent::class)
abstract class OutfitSyncModule {
    @Binds
    @IntoSet
    abstract fun bindOutfitFolderSyncHandler(impl: OutfitFolderSyncHandler): MutationHandler

    @Binds
    @IntoSet
    abstract fun bindOutfitEventSyncHandler(impl: OutfitEventSyncHandler): MutationHandler
}
