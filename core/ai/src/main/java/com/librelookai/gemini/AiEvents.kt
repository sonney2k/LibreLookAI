package com.librelookai.gemini

import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow

/**
 * Semantic reason for an AI failure, mapped to a localized string at the UI layer (the global
 * notice dialog in `AppContent`). The repository deliberately emits *reasons*, not string
 * resources — it has no UI locale context, and keeping `R` out of this layer is what lets it
 * live below the app module.
 */
enum class AiErrorReason {
    /** No Gemini key (and no managed proxy) is configured. */
    NOT_CONFIGURED,
    /** Managed mode without a usable backend (no proxy URL / no Firebase session). */
    UNAVAILABLE,
    /** HTTP 429 — quota / rate limit exhausted. */
    QUOTA,
    /** HTTP 400 with an invalid-API-key marker. */
    KEY_INVALID,
    /** HTTP 403 — key lacks permission (e.g. image generation needs a billing-enabled key). */
    PERMISSION,
    /** HTTP 200/0 with a blocked or empty candidate — safety block or parse failure. */
    BLOCKED,
    /** HTTP 5xx — Gemini-side server error. */
    SERVER,
    /** Anything else. */
    GENERIC,
}

/**
 * Thrown by [GeminiRepository.buildRequest] when no usable AI backend is available — managed mode
 * with no Firebase session, or nothing configured at all. It carries the semantic [reason] so the
 * UI layer can show a localized explanation. Every Gemini method's generic `catch (Exception)`
 * turns it into a graceful `null`, so it never crashes the caller.
 */
class AiUnavailableException(val reason: AiErrorReason) : Exception("AI unavailable")

/** What kind of problem an [AiNotice] reports — drives the title, icon and primary action. */
enum class AiNoticeKind {
    /** No Gemini key (and no managed proxy) is configured — the primary action sets one up. */
    NOT_CONFIGURED,
    /** A configured key produced a failed/blocked response — the primary action retries. */
    FAILED,
}

/**
 * A user-facing AI problem surfaced by the global handler in `AppContent`. Carries a semantic
 * [reason] (mapped to a localized string at the UI layer) and, for [AiNoticeKind.FAILED], whether
 * a one-tap retry of the originating action is available (see [AiRetry]).
 */
data class AiNotice(
    val kind: AiNoticeKind,
    val reason: AiErrorReason,
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

    fun emit(kind: AiNoticeKind, reason: AiErrorReason, canRetry: Boolean = false) {
        emit(AiNotice(kind, reason, canRetry))
    }
}

/**
 * Holds the most recent **user-initiated** AI action so the global failure dialog can offer a
 * one-tap retry that re-runs the whole originating operation (not just the HTTP call). The
 * triggering ViewModel/UI sets [action] right before launching; it is cleared once consumed.
 * Only single, user-tapped actions register here — bulk/automatic operations leave it null so the
 * failure dialog shows a reason without a misleading "retry the batch" button.
 *
 * An injected `@Singleton` (refactor § 3 slice 5 — formerly a static `object` holder): the
 * triggering VMs take it as a constructor param, the global dialog in `AppContent` receives the
 * same instance through `MainActivity`.
 */
@javax.inject.Singleton
class AiRetry @javax.inject.Inject constructor() {
    @Volatile
    var action: (() -> Unit)? = null
}
