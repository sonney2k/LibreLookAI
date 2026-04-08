package com.librelookai

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

    // Credit cost per AI action (informational — enforcement is server-side)
    const val COST_BG_REMOVAL = 5
    const val COST_CLASSIFY   = 2
    const val COST_TEXT       = 2
    const val COST_TRENDS     = 2
}
