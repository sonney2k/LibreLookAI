package com.librelookai.billing

import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow

/**
 * Process-wide event bus for credit-related signals that any screen / VM
 * may want to react to without plumbing callbacks.
 *
 * Currently surfaces one event: `topUp` — a 402 from the Gemini proxy.
 * [GeminiRepository.throwIf402] emits here *before* throwing, so even if a
 * caller swallows the `InsufficientCreditsException` in its generic
 * `catch (e: Exception)` block, the top-level observer in `MainActivity`
 * still gets the event and shows [InsufficientCreditsDialog].
 *
 * Replay buffer of 0: the dialog is only shown to whoever is currently
 * collecting. Extra buffer = 4 so simultaneous calls don't drop events.
 */
object CreditsEvents {
    private val _topUp = MutableSharedFlow<InsufficientCreditsException>(
        replay = 0,
        extraBufferCapacity = 4,
    )
    val topUp: SharedFlow<InsufficientCreditsException> = _topUp.asSharedFlow()

    fun emitTopUp(e: InsufficientCreditsException) {
        _topUp.tryEmit(e)
    }
}
