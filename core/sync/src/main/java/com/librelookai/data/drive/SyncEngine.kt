package com.librelookai.data.drive

import com.librelookai.data.local.PendingMutation
import com.librelookai.data.local.PendingMutationStore
import dagger.Module
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import dagger.multibindings.Multibinds
import javax.inject.Inject
import javax.inject.Singleton
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
 * Drains the [PendingMutationStore] to Drive, strictly oldest-first (refactor § 2). Dark-launched:
 * nothing enqueues yet — write paths convert feature-by-feature, each keeping today's
 * optimistic-write UX (instant local update, Drive syncs behind, rollback on failure) while
 * replacing the in-coroutine inline Drive call with a queued, retryable, process-death-surviving
 * mutation. Triggering (app start / network regain / WorkManager) is wired when the first write
 * path converts.
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
) {
    private val byKind = handlers.associateBy { it.kind }
    private val mutex = Mutex()

    /** Single-flight: concurrent calls queue behind the running drain instead of interleaving. */
    suspend fun drain() {
        mutex.withLock {
            while (true) {
                val mutation = store.oldest() ?: return
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
                            return
                        }
                    }
                }
            }
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
