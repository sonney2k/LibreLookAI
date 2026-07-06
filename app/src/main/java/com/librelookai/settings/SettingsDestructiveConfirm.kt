package com.librelookai.settings

import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import com.librelookai.billing.CreditsViewModel
import com.librelookai.util.ImageEncoding
import com.librelookai.wardrobe.LocationViewModel
import com.librelookai.wardrobe.WardrobeViewModel

/**
 * The destructive-op confirm dialog with its count / dispatch wiring — shared by the
 * Advanced destination's "Fix AI mistakes" rows and the main page's WebP conversion.
 */
@Composable
internal fun SettingsDestructiveConfirmHost(
    action: DestructiveAction,
    wardrobeViewModel: WardrobeViewModel,
    locationViewModel: LocationViewModel,
    creditsViewModel: CreditsViewModel,
    onBuyCredits: () -> Unit,
    onDismiss: () -> Unit,
) {
    val wardrobeState by wardrobeViewModel.state.collectAsState()
    val locationState by locationViewModel.state.collectAsState()
    val creditsState by creditsViewModel.state.collectAsState()
    val itemCount = when (action) {
        // Estimate from legacy (non-WebP) cutouts across all closets.
        DestructiveAction.CONVERT_WEBP ->
            wardrobeState.allLocationImages.count { it.name.endsWith(ImageEncoding.CUTOUT_SUFFIX_LEGACY) }
        else -> wardrobeState.images.size
    }
    DestructiveConfirmDialog(
        action = action,
        itemCount = itemCount,
        balance = creditsState.balance,
        onConfirm = {
            when (action) {
                DestructiveAction.RETAG -> wardrobeViewModel.retagAll()
                DestructiveAction.REMOVE_BG -> wardrobeViewModel.removeAllBackgrounds()
                DestructiveAction.CUTOUT_FIX ->
                    wardrobeViewModel.startCutoutBgFixScan(locationState.locations.map { it.folderId })
                DestructiveAction.CONVERT_WEBP ->
                    wardrobeViewModel.convertImagesToWebp(locationState.locations.map { it.folderId })
            }
            onDismiss()
        },
        onBuyCredits = onBuyCredits,
        onDismiss = onDismiss,
    )
}
