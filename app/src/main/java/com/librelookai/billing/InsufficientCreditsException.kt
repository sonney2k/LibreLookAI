package com.librelookai.billing

/**
 * Raised by `GeminiRepository` when the proxy returns HTTP 402 because the
 * signed-in user's coin balance is below the action's price. Callers that
 * want to prompt the user to top up should catch this and route to
 * [BuyCreditsScreen] with the deficit pre-filled.
 *
 * BYOK callers never see this — the exception is proxy-mode only.
 */
class InsufficientCreditsException(
    val needed: Int,
    val have: Int,
) : RuntimeException("Insufficient credits: need $needed, have $have")
