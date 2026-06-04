package com.librelookai.auth

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow

/**
 * Thrown by [GoogleAuthManager.getAccessToken] when the stored Drive
 * authorization is gone — revoked by the user, expired, or (as happened in
 * testing) the OAuth client was deleted in Cloud Console — and the user must
 * re-authorize.
 *
 * Subclasses [CancellationException] on purpose: throwing it aborts the
 * in-flight Drive coroutine *quietly* — coroutines treat a CancellationException
 * as normal cancellation, so it never reaches the thread's uncaught-exception
 * handler and never crashes the process, even from a `launch {}` that doesn't
 * wrap the Drive call in a try/catch. The UI recovery is driven instead by
 * [AuthEvents.sessionExpired], emitted right before the throw.
 */
class ReauthRequiredException :
    CancellationException("No Drive access token; user must re-authorize")

/**
 * Process-wide bus for auth signals, mirroring `billing.CreditsEvents`:
 * the producer emits *before* throwing, so the top-level observer
 * ([AuthViewModel]) reacts and routes back to sign-in even if a caller
 * swallows [ReauthRequiredException] in a generic catch / runCatching.
 */
object AuthEvents {
    private val _sessionExpired = MutableSharedFlow<Unit>(
        replay = 0,
        extraBufferCapacity = 1,
    )
    val sessionExpired: SharedFlow<Unit> = _sessionExpired.asSharedFlow()

    fun emitSessionExpired() {
        _sessionExpired.tryEmit(Unit)
    }
}
