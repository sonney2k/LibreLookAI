package com.librelookai.billing
/** In-app product IDs — must match exactly what is registered in Google Play Console. */
object CreditPacks {
    const val PACK_100  = "credits_100"
    const val PACK_500  = "credits_500"
    const val PACK_2000 = "credits_2000"

    val productIds = listOf(PACK_100, PACK_500, PACK_2000)

    /** Credits awarded per product. Mirrors the server-side CREDITS_PER_PACK map. */
    fun creditsFor(productId: String): Int = when (productId) {
        PACK_100  -> 100
        PACK_500  -> 500
        PACK_2000 -> 2000
        else      -> 0
    }

    // Last-resort defaults shown when PricingClient has no live config and no
    // persisted snapshot. Authoritative prices live in Firestore
    // (config/publicPricing); see PricingClient. Keep these in sync with
    // firebase/functions/src/pricing.ts (DEFAULT_PRICING × multiplier).
    // Sized for a 100% after-tax profit under VAT 19% + Play 30% + income tax
    // 47.475% — see plan/FIN.md § "Gemini pricing model & unit economics".
    const val COST_BG_REMOVAL        = 34
    const val COST_CLASSIFY          = 2
    const val COST_TEXT              = 12
    const val COST_TRENDS            = 2
    const val COST_TRY_ON            = 34
    const val COST_OUTFIT_SUGGESTION = 12

    /** Total credits for running BG removal + optional tagging on [count] items. */
    fun bulkCost(count: Int, removeBg: Boolean, autoTag: Boolean): Int =
        count * ((if (removeBg) COST_BG_REMOVAL else 0) + (if (autoTag) COST_CLASSIFY else 0))
}
