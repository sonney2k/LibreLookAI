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
