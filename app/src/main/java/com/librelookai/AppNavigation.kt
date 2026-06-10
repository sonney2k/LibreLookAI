package com.librelookai

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CloudOff
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import kotlinx.serialization.Serializable

/**
 * Type-safe routes for the root NavHost (hosted in [AppContent]).
 *
 * [HomeRoute] is the whole pre-navigation app: the Scaffold with the bottom nav bar and the
 * `when(selectedTab)` tab dispatch. Converted screens become sibling destinations that overlay
 * it full-screen (covering the bottom bar) — replacing the fullscreen-Dialog / hoisted-state
 * patterns one at a time (plan/refactor.md phase 3).
 */
@Serializable
internal data object HomeRoute

/** Trip detail viewer — the first Dialog-era fullscreen mode converted to a destination. */
@Serializable
internal data class TripViewerRoute(val tripId: String)

/**
 * Travel planner — replaces the hoisted `travelPlannerMode` flag that rendered the planner
 * inside the Travel tab with Home's chrome hidden. Planner-created trips pop this destination
 * and navigate to [TripViewerRoute].
 */
@Serializable
internal data object TravelPlannerRoute

/**
 * Fullscreen outfit detail viewer (pager) — replaces the `OutfitFullScreenViewer` Dialog hosts.
 * Outfits resolve LIVE from the ViewModels at the destination (edits/deletes during viewing stay
 * correct); the route only carries the viewing context:
 *  - [SOURCE_LIST]: [outfitIds] = the filtered list in display order, snapshotted at tap time.
 *  - [SOURCE_PREDICTION]: outfits come from `predictionSuggestions`; closing clears the prediction.
 *  - [SOURCE_TRIP]: outfits come from [tripId]'s day list (so a day-edit's id swap stays in sync).
 */
@Serializable
internal data class OutfitViewerRoute(
    val source: String,
    val outfitIds: List<String> = emptyList(),
    val initialOutfitId: String? = null,
    val tripId: String? = null,
) {
    companion object {
        const val SOURCE_LIST = "list"
        const val SOURCE_PREDICTION = "prediction"
        const val SOURCE_TRIP = "trip"
    }
}

/**
 * Slim offline indicator strip shown above the active screen. Shared by [HomeRoute] (above the
 * tab content) and full-screen destinations, which no longer sit under Home's banner.
 */
@Composable
internal fun OfflineBanner(visible: Boolean) {
    AnimatedVisibility(visible = visible) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .background(MaterialTheme.colorScheme.errorContainer)
                .padding(horizontal = 16.dp, vertical = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.Center,
        ) {
            Icon(
                Icons.Default.CloudOff,
                contentDescription = null,
                modifier = Modifier.size(16.dp),
                tint = MaterialTheme.colorScheme.onErrorContainer,
            )
            Spacer(Modifier.width(8.dp))
            Text(
                stringResource(R.string.offline_banner),
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onErrorContainer,
            )
        }
    }
}
