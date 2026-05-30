package com.librelookai.gemini

import androidx.annotation.StringRes
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow

/**
 * Thrown by [GeminiRepository.buildRequest] when no usable AI backend is available — managed mode
 * with no Firebase session, or nothing configured at all. It carries a localized, user-facing string
 * resource so the reason can be shown without the repository (which has no UI locale context)
 * resolving the text itself. Every Gemini method's generic `catch (Exception)` turns it into a
 * graceful `null`, so it never crashes the caller.
 */
class AiUnavailableException(@param:StringRes val messageRes: Int) : Exception("AI unavailable")

/**
 * Process-wide event bus for "AI couldn't run" signals, mirroring
 * [com.librelookai.billing.CreditsEvents]. [GeminiRepository.buildRequest] emits the string-resource
 * id *before* throwing, so even though callers swallow the exception into a null result, the
 * top-level observer in `AppContent` still surfaces the reason (localized at the UI layer).
 *
 * Replay 0 (only current collectors see it); extraBuffer 4 so concurrent calls don't drop events.
 */
object AiEvents {
    private val _unavailable = MutableSharedFlow<Int>(replay = 0, extraBufferCapacity = 4)
    val unavailable: SharedFlow<Int> = _unavailable.asSharedFlow()

    fun emitUnavailable(@StringRes messageRes: Int) {
        _unavailable.tryEmit(messageRes)
    }
}
