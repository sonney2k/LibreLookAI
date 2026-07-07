package com.librelookai.travel

import androidx.compose.runtime.Composable
import androidx.compose.runtime.compositionLocalOf
import com.librelookai.gemini.CostTokens

/**
 * Renders the packing-generation cost badge on the travel planner's generate button and the trip
 * bulk-refine apply button (the [CostTokens] estimate is computed travel-side and threaded in).
 * The `CostBadge` composable lives in `feature/billing`, which neither core modules nor sibling
 * feature modules may depend on, so the app shell provides this from `feature/billing`'s
 * `CostBadge`; unprovided (previews / tests) hides the badge. Mirrors `LocalTryOnCostBadge`.
 */
val LocalTravelCostBadge =
    compositionLocalOf<(@Composable (tokens: CostTokens?) -> Unit)?> { null }
