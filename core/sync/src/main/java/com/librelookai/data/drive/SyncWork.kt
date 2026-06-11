package com.librelookai.data.drive

import android.content.Context
import android.util.Log
import androidx.work.BackoffPolicy
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import com.librelookai.data.local.PendingMutationStore
import dagger.Binds
import dagger.Module
import dagger.hilt.EntryPoint
import dagger.hilt.InstallIn
import dagger.hilt.android.EntryPointAccessors
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import java.util.concurrent.TimeUnit
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Persistence backstop for the mutation queue (refactor § 2). The in-process drain triggers
 * (inline post-enqueue, app-start / network-regain catch-up, the engine's 30s→10min backoff
 * re-drain) all die with the process; this seam lets [SyncEngine] register an OS-scheduled
 * drain so queued writes land even if the app is killed mid-queue — without the engine
 * depending on WorkManager (its tests stay plain JUnit over a fake).
 */
interface DrainScheduler {
    /**
     * Ensures one persistent, network-constrained drain is scheduled. Idempotent — an already
     * scheduled/running drain is kept. Never cancelled by the engine: a drain that empties the
     * queue in-process leaves the scheduled work to run as a cheap no-op (empty queue →
     * success → gone), which avoids the engine cancelling the very worker that is running it.
     */
    fun ensureScheduled()
}

/**
 * Schedules [SyncDrainWorker] as unique work: one queue, one backstop. Network-constrained
 * (a drain without connectivity can only halt) with WorkManager's own exponential backoff for
 * the retry case — note this is the *process-death* path; while the process lives, the
 * engine's in-process backoff usually drains first and the worker no-ops.
 */
@Singleton
class WorkManagerDrainScheduler @Inject constructor(
    @param:ApplicationContext private val context: Context,
) : DrainScheduler {

    override fun ensureScheduled() {
        // getInstance throws before WorkManager initializes (e.g. bare Robolectric) — the
        // backstop is best-effort on top of the in-process triggers, never load-bearing now.
        runCatching {
            WorkManager.getInstance(context).enqueueUniqueWork(
                SyncDrainWorker.WORK_NAME,
                ExistingWorkPolicy.KEEP,
                OneTimeWorkRequestBuilder<SyncDrainWorker>()
                    .setConstraints(
                        Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build(),
                    )
                    .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 30, TimeUnit.SECONDS)
                    .build(),
            )
        }.onFailure { Log.w("SyncWork", "drain backstop scheduling failed", it) }
    }
}

/**
 * Drains the mutation queue from a WorkManager process (re)start. A plain [CoroutineWorker]
 * built by the default reflective factory — the Hilt graph is reached via an [EntryPoint]
 * on the application, so no androidx.hilt-work machinery is needed. Returns retry while the
 * queue is non-empty (a transient halt rides WorkManager's backoff; the rare unknown-kind
 * halt after a downgrade also lands here and resolves on upgrade).
 */
class SyncDrainWorker(
    appContext: Context,
    params: WorkerParameters,
) : CoroutineWorker(appContext, params) {

    @EntryPoint
    @InstallIn(SingletonComponent::class)
    interface SyncDrainEntryPoint {
        fun syncEngine(): SyncEngine
        fun pendingMutationStore(): PendingMutationStore
    }

    override suspend fun doWork(): Result {
        val entryPoint = EntryPointAccessors.fromApplication(
            applicationContext,
            SyncDrainEntryPoint::class.java,
        )
        entryPoint.syncEngine().drain()
        return if (entryPoint.pendingMutationStore().count() == 0) Result.success() else Result.retry()
    }

    companion object {
        const val WORK_NAME = "sync_queue_drain"
    }
}

@Module
@InstallIn(SingletonComponent::class)
abstract class SyncWorkModule {
    @Binds
    abstract fun bindDrainScheduler(impl: WorkManagerDrainScheduler): DrainScheduler
}
