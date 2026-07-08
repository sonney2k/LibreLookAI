package com.librelookai.outfit

import androidx.compose.runtime.Composable
import androidx.compose.runtime.compositionLocalOf
import com.librelookai.gemini.CostTokens
import com.librelookai.gemini.GeminiActionId

/**
 * Renders the AI-generation cost badge on the outfit composer's "Generate with AI" button and the
 * prediction-setup dialog's find/create button. The `CostBadge` composable lives in
 * `feature/billing`, which neither core modules nor sibling feature modules may depend on, so the
 * app shell provides this from `feature/billing`'s `CostBadge`; unprovided (previews / tests) hides
 * the badge. Mirrors `LocalTravelCostBadge` / `LocalTryOnCostBadge` — carries `action` + `bulkCount`
 * since the two outfit call sites differ (composer = OUTFIT_SUGGESTION single, prediction =
 * GENERATE_TEXT with a bulk count).
 */
val LocalOutfitCostBadge =
    compositionLocalOf<(@Composable (action: GeminiActionId, bulkCount: Int, tokens: CostTokens?) -> Unit)?> { null }
