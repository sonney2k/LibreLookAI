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

/** What kind of problem an [AiNotice] reports — drives the title, icon and primary action. */
enum class AiNoticeKind {
    /** No Gemini key (and no managed proxy) is configured — the primary action sets one up. */
    NOT_CONFIGURED,
    /** A configured key produced a failed/blocked response — the primary action retries. */
    FAILED,
}

/**
 * A user-facing AI problem surfaced by the global handler in `AppContent`. Carries a localized
 * [messageRes] (resolved at the UI layer) and, for [AiNoticeKind.FAILED], whether a one-tap retry
 * of the originating action is available (see [AiRetry]).
 */
data class AiNotice(
    val kind: AiNoticeKind,
    @param:StringRes val messageRes: Int,
    val canRetry: Boolean = false,
)

/**
 * Process-wide event bus for "AI couldn't run" signals, mirroring
 * [com.librelookai.billing.CreditsEvents]. The repository emits a notice for **user-initiated**
 * calls (the `notify` flag) before returning null, so even though callers swallow the failure into
 * a null result, the top-level observer in `AppContent` still surfaces the reason and the
 * appropriate action (set up a key, or retry).
 *
 * Replay 0 (only current collectors see it); extraBuffer 4 so concurrent calls don't drop events.
 */
object AiEvents {
    private val _notices = MutableSharedFlow<AiNotice>(replay = 0, extraBufferCapacity = 4)
    val notices: SharedFlow<AiNotice> = _notices.asSharedFlow()

    fun emit(notice: AiNotice) {
        _notices.tryEmit(notice)
    }

    fun emit(kind: AiNoticeKind, @StringRes messageRes: Int, canRetry: Boolean = false) {
        emit(AiNotice(kind, messageRes, canRetry))
    }
}

/**
 * Holds the most recent **user-initiated** AI action so the global failure dialog can offer a
 * one-tap retry that re-runs the whole originating operation (not just the HTTP call). The
 * triggering ViewModel/UI sets [action] right before launching; it is cleared once consumed.
 * Only single, user-tapped actions register here — bulk/automatic operations leave it null so the
 * failure dialog shows a reason without a misleading "retry the batch" button.
 */
object AiRetry {
    @Volatile
    var action: (() -> Unit)? = null
}
