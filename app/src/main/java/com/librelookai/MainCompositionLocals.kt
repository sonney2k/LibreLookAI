package com.librelookai

import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.librelookai.data.model.Location

/**
 * Navigates to Settings from a header surface (the gear icon). Null when no host wired it, so
 * fullscreen Dialog viewers can reach Settings without threading the callback through every call site.
 */
val LocalOpenSettings = androidx.compose.runtime.compositionLocalOf<(() -> Unit)?> { null }

/**
 * Re-launches the first-run onboarding tour as a fullscreen overlay. Provided by
 * [AppContent]; null when no host wired it (e.g. previews). Lets Settings offer a "take the tour"
 * row without the tour's visibility state having to live in Settings.
 */
val LocalStartTour = androidx.compose.runtime.compositionLocalOf<(() -> Unit)?> { null }

/**
 * The injected Gemini call-progress bus ([com.librelookai.gemini.GeminiProgress], refactor § 3
 * slice 5 — formerly a static object) for [com.librelookai.util.AiProcessingOverlay]'s live
 * upload bar. Provided by [AppContent] from the Hilt graph; null (previews / tests) degrades
 * the overlay to its time-based estimate.
 */
val LocalGeminiProgress =
    androidx.compose.runtime.staticCompositionLocalOf<com.librelookai.gemini.GeminiProgress?> { null }

/**
 * Active-closet selector state surfaced to fullscreen Dialog viewers / the try-on composer so they
 * can render the same interactive closet dropdown as [AppScreenHeader]. Null hides the dropdown.
 */
data class ClosetSelectorContext(
    val locations: List<Location>,
    val activeLocationId: String,
    val onSetActiveLocation: (String) -> Unit,
)
val LocalClosetSelector = androidx.compose.runtime.compositionLocalOf<ClosetSelectorContext?> { null }
/**
 * Right-aligned header cluster — interactive closet selector + Insights + Settings — reused inside
 * fullscreen Dialog viewers and the try-on composer so these stay reachable while an item / outfit /
 * try-on is open (parity with [AppScreenHeader]). The closet dropdown switches the active closet in
 * place; [onBeforeNavigate] runs before Insights/Settings navigation so the host Dialog dismisses
 * first ("dismiss then navigate"). Each child is hidden when its CompositionLocal is unprovided
 * (and the closet dropdown additionally hides itself when there are fewer than two closets).
 */
@Composable
fun ViewerHeaderActions(
    onBeforeNavigate: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Row(modifier = modifier, verticalAlignment = Alignment.CenterVertically) {
        LocalClosetSelector.current?.let { selector ->
            LocationButton(
                locations = selector.locations,
                activeLocationId = selector.activeLocationId,
                onSetActiveLocation = selector.onSetActiveLocation,
            )
        }
        // Settings: 32.dp button footprint, kept in sync with AppScreenHeader.
        val openSettings = LocalOpenSettings.current
        if (openSettings != null) {
            androidx.compose.material3.IconButton(
                onClick = { onBeforeNavigate(); openSettings() },
                modifier = Modifier.size(32.dp),
            ) {
                Icon(
                    Icons.Default.Settings,
                    contentDescription = "Settings",
                    modifier = Modifier.size(20.dp),
                )
            }
        }
    }
}

