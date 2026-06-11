package com.librelookai.billing

import com.librelookai.core.ai.BuildConfig

/**
 * Single source of truth for whether the managed coin economy is live in this build.
 *
 * Managed billing = the non-BYOK "refinancing" path: buying coins via Google Play, the
 * server-authoritative balance, coin cost badges, spend confirmations, and the Firebase
 * Gemini proxy that those coins pay for. When disabled the app runs **BYOK-only**: users
 * supply their own Gemini API key (Settings ▸ Advanced) and every coin/purchase surface
 * is hidden.
 *
 * Backed by [BuildConfig.MANAGED_BILLING_ENABLED] (`managed.billing.enabled` in
 * local.properties, default `false`). Flip the flag — together with a deployed proxy and
 * Play products — to re-enable the coin economy with no code change. Managed mode still
 * additionally requires a configured proxy URL, Firebase, and a blank user key at runtime
 * (see `CreditRepository.isManagedMode`).
 */
object ManagedBilling {
    val enabled: Boolean get() = BuildConfig.MANAGED_BILLING_ENABLED
}
