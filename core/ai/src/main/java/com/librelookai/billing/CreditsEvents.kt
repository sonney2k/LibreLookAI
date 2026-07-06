package com.librelookai.billing

import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow

/**
 * Process-wide event bus for credit-related signals that any screen / VM
 * may want to react to without plumbing callbacks.
 *
 * Currently surfaces one event: `topUp` — a 402 from the Gemini proxy.
 * `GeminiRepository.creditsIf402` emits here *before* returning the typed
 * `AiResult.InsufficientCredits` (refactor § 6), so the top-level observer in
 * `MainActivity` shows [InsufficientCreditsDialog] regardless of what the
 * caller does with the result.
 *
 * Replay buffer of 0: the dialog is only shown to whoever is currently
 * collecting. Extra buffer = 4 so simultaneous calls don't drop events.
 *
 * An injected `@Singleton` (refactor § 6 slice 2 — formerly a static `object`,
 * mirroring `AiEvents`): `GeminiRepository` takes it as a constructor param,
 * and the dialog observer in `AppContent` receives the same instance through
 * `MainActivity`.
 */
@javax.inject.Singleton
class CreditsEvents @javax.inject.Inject constructor() {
    private val _topUp = MutableSharedFlow<InsufficientCreditsException>(
        replay = 0,
        extraBufferCapacity = 4,
    )
    val topUp: SharedFlow<InsufficientCreditsException> = _topUp.asSharedFlow()

    fun emitTopUp(e: InsufficientCreditsException) {
        _topUp.tryEmit(e)
    }
}
