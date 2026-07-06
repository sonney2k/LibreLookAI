package com.librelookai.gemini

/**
 * Typed outcome of an [AiClient] call (refactor § 6) — replaces the old "return null on
 * failure, throw [com.librelookai.billing.InsufficientCreditsException] on a 402" contract.
 * The compiler now forces every caller to decide what a non-[Success] means for it; callers
 * whose only failure handling is to degrade use [getOrNull].
 *
 * The global UI surfaces are unchanged and stay repo-side: a 402 still emits on
 * `CreditsEvents.topUp` (the top-up dialog) before [InsufficientCredits] is returned, and
 * `notify = true` failures still emit an [AiNotice] (the retry dialog) before [Failure] is —
 * the typed result is the *caller's* control-flow surface, not a replacement for the buses.
 */
sealed interface AiResult<out T> {

    /** The call succeeded. */
    data class Success<T>(val value: T) : AiResult<T>

    /** No BYOK key and no managed proxy — there was nothing to call. */
    data object NotConfigured : AiResult<Nothing>

    /**
     * The managed proxy returned HTTP 402. The global top-up dialog has already been raised via
     * `CreditsEvents`; callers only reset their loading flags and short-circuit bulk loops.
     */
    data class InsufficientCredits(val needed: Int, val have: Int) : AiResult<Nothing>

    /** The call ran and failed ([reason] is the semantic mapping the notice dialog also shows). */
    data class Failure(val reason: AiErrorReason, val retryable: Boolean = true) : AiResult<Nothing>
}

/** The success value, or null — for callers whose only failure handling is to degrade. */
fun <T> AiResult<T>.getOrNull(): T? = (this as? AiResult.Success<T>)?.value
