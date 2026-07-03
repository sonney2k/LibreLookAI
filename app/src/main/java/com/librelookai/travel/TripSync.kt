package com.librelookai.travel

import com.google.gson.Gson
import com.librelookai.data.drive.DriveRepository
import com.librelookai.data.drive.MutationHandler
import com.librelookai.data.drive.MutationOutcome
import com.librelookai.data.drive.deleteTripJson
import com.librelookai.data.drive.findTripFileId
import com.librelookai.data.drive.saveTripJson
import com.librelookai.data.local.PendingMutation
import com.librelookai.data.local.TripStore
import dagger.Binds
import dagger.Module
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import dagger.multibindings.IntoSet
import javax.inject.Inject
import javax.inject.Singleton

/** Queue kind for "write this trip's `{tripId}.json` from the store" (see [TripSaveSyncHandler]). */
const val TRIP_SAVE_KIND = "trip.save"

/** Queue kind for "delete this trip's `{tripId}.json` from Drive" (see [TripDeleteSyncHandler]). */
const val TRIP_DELETE_KIND = "trip.delete"

/**
 * Drains queued trip saves (refactor § 2). Unlike outfits/events (whole-folder files), each trip
 * is its own name-addressed Drive file (`{tripId}.json` in the `_trips/` folder), so the
 * mutation is per-trip and payload-free: the handler re-reads the trip's *current* row from
 * [TripStore] at apply time (back-to-back edits coalesce) and resolves the trips folder itself —
 * the enqueuer no longer needs Phase 2's `folderId` to have loaded, so a trip created before the
 * folder resolves still syncs. Trip gone from the store → [MutationOutcome.Permanent] (deleted
 * meanwhile; the delete mutation queued behind it owns the Drive file). Drive failure →
 * [MutationOutcome.Retry]; no rollback state (Room already holds the edit).
 */
@Singleton
class TripSaveSyncHandler @Inject constructor(
    private val drive: DriveRepository,
    private val trips: TripStore,
) : MutationHandler {

    private val gson = Gson()

    override val kind: String = TRIP_SAVE_KIND

    override suspend fun apply(mutation: PendingMutation): MutationOutcome {
        val trip = trips.trips().find { it.id == mutation.targetId }
            ?: return MutationOutcome.Permanent("trip ${mutation.targetId} no longer cached")
        return try {
            val folderId = drive.getOrCreateTripsFolder(drive.getOrCreateFolder())
            drive.saveTripJson(folderId, trip.id, gson.toJson(trip))
            MutationOutcome.Success
        } catch (e: Exception) {
            MutationOutcome.Retry(e.message ?: e.javaClass.simpleName)
        }
    }
}

/**
 * Drains queued trip deletes. Resolves the Drive file by the stable trip id
 * ([com.librelookai.data.drive.findTripFileId]) instead of the old session-local
 * `driveIdsByTripId` map — so a trip deleted before Phase 2 load populated the map no longer
 * orphans its Drive file, and re-running after a timeout is idempotent (file already gone →
 * [MutationOutcome.Success]).
 */
@Singleton
class TripDeleteSyncHandler @Inject constructor(
    private val drive: DriveRepository,
) : MutationHandler {

    override val kind: String = TRIP_DELETE_KIND

    override suspend fun apply(mutation: PendingMutation): MutationOutcome = try {
        val folderId = drive.getOrCreateTripsFolder(drive.getOrCreateFolder())
        drive.findTripFileId(folderId, mutation.targetId)?.let { drive.deleteTripJson(it) }
        MutationOutcome.Success
    } catch (e: Exception) {
        MutationOutcome.Retry(e.message ?: e.javaClass.simpleName)
    }
}

@Module
@InstallIn(SingletonComponent::class)
abstract class TravelSyncModule {
    @Binds
    @IntoSet
    abstract fun bindTripSaveSyncHandler(impl: TripSaveSyncHandler): MutationHandler

    @Binds
    @IntoSet
    abstract fun bindTripDeleteSyncHandler(impl: TripDeleteSyncHandler): MutationHandler
}
