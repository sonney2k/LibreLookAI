package com.librelookai.tryon

import com.google.gson.Gson
import com.librelookai.data.drive.DriveService
import com.librelookai.data.drive.MutationHandler
import com.librelookai.data.drive.MutationOutcome
import com.librelookai.data.local.PendingMutation
import com.librelookai.data.local.TryOnStore
import dagger.Binds
import dagger.Module
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import dagger.multibindings.IntoSet
import javax.inject.Inject
import javax.inject.Singleton

/** Queue kind for "rewrite the `_tryons.json` index from the store" (see [TryOnIndexSyncHandler]). */
const val TRYON_INDEX_SYNC_KIND = "tryon.syncIndex"

/**
 * Drains queued try-on index writes (refactor § 2 — the last converted metadata write). Payload-
 * free: Room is the source of truth — the handler serializes the store's *current* global list
 * at apply time, so back-to-back saves coalesce and a retried write never resurrects a deleted
 * entry. No `hasFolder`-style wipe guard (unlike outfits/events): every enqueue follows a
 * `replaceAll` in the same Room DB, so a queued index sync implies the store's row set is the
 * local truth, and an empty list is a legitimate state (the last try-on was deleted), not
 * "unknown". The save path keeps its Drive-read merge base for the pre-warm-empty store case
 * (see `TryOnViewModel.saveCurrent`).
 *
 * Outcomes: any Drive failure → [MutationOutcome.Retry] (the attempts cap turns a persistent
 * failure into a give-up); Room already holds the edit, so there is no local state to restore.
 */
@Singleton
class TryOnIndexSyncHandler @Inject constructor(
    private val drive: DriveService,
    private val tryOnStore: TryOnStore,
) : MutationHandler {

    private val gson = Gson()

    override val kind: String = TRYON_INDEX_SYNC_KIND

    override suspend fun apply(mutation: PendingMutation): MutationOutcome = try {
        drive.saveTryOnsJson(drive.getOrCreateFolder(), gson.toJson(tryOnStore.tryOns()))
        MutationOutcome.Success
    } catch (e: Exception) {
        MutationOutcome.Retry(e.message ?: e.javaClass.simpleName)
    }
}

@Module
@InstallIn(SingletonComponent::class)
abstract class TryOnSyncModule {
    @Binds
    @IntoSet
    abstract fun bindTryOnIndexSyncHandler(impl: TryOnIndexSyncHandler): MutationHandler
}
