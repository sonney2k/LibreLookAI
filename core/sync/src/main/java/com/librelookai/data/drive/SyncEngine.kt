package com.librelookai.data.drive

import android.app.Application
import com.librelookai.data.local.PendingMutation
import com.librelookai.data.local.PendingMutationStore
import com.librelookai.util.NetworkMonitor
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import dagger.multibindings.Multibinds
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/** Result of one [MutationHandler.apply] attempt. */
sealed class MutationOutcome {
    /** The Drive write landed — the mutation is removed from the queue. */
    object Success : MutationOutcome()

    /**
     * A transient failure (network, 5xx, rate limit) — the drain halts (preserving FIFO order)
     * and the mutation is retried on the next [SyncEngine.drain], up to
     * [SyncEngine.MAX_ATTEMPTS] before it is treated as permanent.
     */
    data class Retry(val error: String) : MutationOutcome()

    /**
     * The write can never succeed (target deleted remotely, invalid state) — the handler's
     * [MutationHandler.rollback] restores the pre-mutation local state and the queue moves on.
     */
    data class Permanent(val error: String) : MutationOutcome()
}

/**
 * Applies one kind of queued Drive write (refactor § 2). Implementations live feature-side and
 * are registered via Hilt set multibinding (`@Binds @IntoSet`); the [kind] string must match the
 * value the writer passed to [PendingMutationStore.enqueue]. Handlers own their mutation's
 * `payload`/`rollback` JSON schema end-to-end.
 */
interface MutationHandler {
    val kind: String

    /** Replays the Drive write described by [mutation]'s payload. Must be idempotent. */
    suspend fun apply(mutation: PendingMutation): MutationOutcome

    /** Restores the pre-mutation local state on permanent failure. Default: nothing to undo. */
    suspend fun rollback(mutation: PendingMutation) {}
}

/**
 * Drains the [PendingMutationStore] to Drive, strictly oldest-first (refactor § 2). Write paths
 * convert feature-by-feature, each keeping the optimistic-write UX (instant local update, Drive
 * syncs behind) while replacing the in-coroutine inline Drive call with a queued, retryable,
 * process-death-surviving mutation. Triggers: inline post-enqueue, the app-start /
 * network-regain [SyncConnectivityCatchUp], the in-process backoff re-drain below, and — for
 * process death with a non-empty queue — the [DrainScheduler] WorkManager backstop.
 *
 * Drain rules, encoded in `SyncEngineTest`:
 * - Strict global FIFO; a [MutationOutcome.Retry] halts the drain so later mutations can't
 *   overtake one that must land first.
 * - [MutationOutcome.Permanent] (or [MAX_ATTEMPTS] exhausted) rolls the mutation back and the
 *   drain continues.
 * - An unknown `kind` (app downgrade racing a newer queue row) halts the drain without deleting
 *   anything — the legacy-cache downgrade nets stay intact and an upgrade resumes the queue.
 */
@Singleton
class SyncEngine @Inject constructor(
    private val store: PendingMutationStore,
    handlers: Set<@JvmSuppressWildcards MutationHandler>,
    private val scheduler: DrainScheduler,
) {
    private val byKind = handlers.associateBy { it.kind }
    private val mutex = Mutex()

    private val retryScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var retryJob: Job? = null

    /** Backoff for the self-scheduled re-drain; `internal var` so tests can shrink the waits. */
    internal var initialRetryDelayMs = 30_000L
    internal var maxRetryDelayMs = 10 * 60_000L
    private var nextRetryDelayMs: Long? = null

    /**
     * Single-flight: concurrent calls queue behind the running drain instead of interleaving.
     * A drain halted by a transient [MutationOutcome.Retry] self-schedules a re-drain with
     * exponential backoff (30s → 10min) — otherwise a queue stalled by one 503 while the device
     * stays online would wait for the next app start / offline→online flip. An unknown-kind halt
     * does *not* self-retry (only an app upgrade can resolve it); any external [drain] still picks
     * it up.
     */
    suspend fun drain() {
        var haltedTransiently = false
        mutex.withLock {
            // Persistent backstop while work exists: if the process dies mid-queue (the
            // in-process triggers die with it), WorkManager re-runs the drain. Never
            // cancelled here — a drain that empties the queue leaves it to no-op cheaply.
            if (store.oldest() != null) scheduler.ensureScheduled()
            loop@ while (true) {
                val mutation = store.oldest() ?: break@loop
                val handler = byKind[mutation.kind] ?: return
                when (val outcome = handler.apply(mutation)) {
                    is MutationOutcome.Success -> store.remove(mutation.id)
                    is MutationOutcome.Permanent -> {
                        runCatching { handler.rollback(mutation) }
                        store.remove(mutation.id)
                    }
                    is MutationOutcome.Retry -> {
                        if (mutation.attempts + 1 >= MAX_ATTEMPTS) {
                            runCatching { handler.rollback(mutation) }
                            store.remove(mutation.id)
                        } else {
                            store.recordFailure(mutation.id, outcome.error)
                            haltedTransiently = true
                            break@loop
                        }
                    }
                }
            }
        }
        if (haltedTransiently) {
            val delayMs = nextRetryDelayMs ?: initialRetryDelayMs
            nextRetryDelayMs = (delayMs * 2).coerceAtMost(maxRetryDelayMs)
            retryJob?.cancel()
            retryJob = retryScope.launch {
                delay(delayMs)
                // Clear the handle before re-draining so the nested drain's bookkeeping can't
                // cancel this very coroutine mid-flight.
                retryJob = null
                drain()
            }
        } else {
            nextRetryDelayMs = null
            retryJob?.cancel()
            retryJob = null
        }
    }

    companion object {
        const val MAX_ATTEMPTS = 8
    }
}

/** Lets the engine inject an empty handler set until the first feature registers one. */
@Module
@InstallIn(SingletonComponent::class)
abstract class SyncEngineModule {
    @Multibinds
    abstract fun mutationHandlers(): Set<MutationHandler>
}

/**
 * The one process-wide [NetworkMonitor] (its callback registration lives for the process — the
 * composition-scoped instance it replaces re-registered per activity). `AppContent` reads it for
 * `LocalIsOffline`, [SyncConnectivityCatchUp] and the wardrobe prefetch retry collect it.
 */
@Module
@InstallIn(SingletonComponent::class)
object NetworkMonitorModule {
    @Provides
    @Singleton
    fun networkMonitor(app: Application): NetworkMonitor = NetworkMonitor(app)
}

/**
 * The app-start / network-regain catch-up drain (refactor § 2): collects
 * [NetworkMonitor.isOnline] on a process-long scope and [SyncEngine.drain]s on subscription
 * (StateFlow replays the current value — the app-start pass) and on every offline→online flip —
 * replacing the `LaunchedEffect(isOnline)` bridge in `AppContent` (§ 5). Kept outside
 * [SyncEngine] so the engine stays constructible in plain-JUnit tests. Started once from the
 * Application's `onCreate` (the `StaticPreferenceMirrors` pattern).
 */
@Singleton
class SyncConnectivityCatchUp @Inject constructor(
    private val engine: SyncEngine,
    private val networkMonitor: NetworkMonitor,
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var started = false

    fun start() {
        if (started) return
        started = true
        scope.launch {
            networkMonitor.isOnline.collect { online -> if (online) engine.drain() }
        }
    }
}
