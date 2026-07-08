package com.librelookai.wardrobe

import androidx.compose.runtime.Composable
import androidx.compose.runtime.compositionLocalOf
import com.librelookai.gemini.GeminiActionId

/**
 * Renders the per-item AI cost badge on the local-bg-removal review's "Remove background" skip
 * button (REMOVE_BACKGROUND, over the raw file) and the tag-edit screen's re-detect button
 * (CLASSIFY_CLOTHING, over the item's local image). The `CostBadge` composable + the
 * `rememberRemoveBgCostTokens`/`rememberClassifyCostTokens` estimators live in `feature/billing`,
 * which neither core modules nor sibling feature modules may depend on, so the app shell provides
 * this from `feature/billing`; unprovided (previews / tests) hides the badge. Mirrors
 * `LocalTravelCostBadge` / `LocalOutfitCostBadge` — takes the file path since the token estimate
 * is derived from the image bytes billing-side.
 */
val LocalWardrobeCostBadge =
    compositionLocalOf<(@Composable (action: GeminiActionId, filePath: String) -> Unit)?> { null }
