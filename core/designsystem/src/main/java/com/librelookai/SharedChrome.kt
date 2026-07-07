package com.librelookai

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.Place
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.librelookai.core.designsystem.R
import com.librelookai.data.model.Location
import com.librelookai.data.session.ClosetSession
import com.librelookai.settings.AppFont

/**
 * Navigates to Settings from a header surface (the gear icon). Null when no host wired it, so
 * fullscreen Dialog viewers can reach Settings without threading the callback through every call site.
 */
val LocalOpenSettings = androidx.compose.runtime.compositionLocalOf<(() -> Unit)?> { null }

/**
 * The injected Gemini call-progress bus ([com.librelookai.gemini.GeminiProgress], refactor § 3
 * slice 5 — formerly a static object) for [com.librelookai.util.AiProcessingOverlay]'s live
 * upload bar. Provided by AppContent from the Hilt graph; null (previews / tests) degrades
 * the overlay to its time-based estimate.
 */
val LocalGeminiProgress =
    androidx.compose.runtime.staticCompositionLocalOf<com.librelookai.gemini.GeminiProgress?> { null }

/**
 * Renders the remove-background cost badge for a pending capture of the given pixel size
 * ([com.librelookai.wardrobe.PhotoReviewScreen]'s confirm affordance). The badge composable
 * lives in `feature/billing`, which core modules can't depend on, so the app shell provides
 * it; unprovided (previews / tests) hides the badge.
 */
val LocalRemoveBgCostBadge =
    androidx.compose.runtime.compositionLocalOf<(@Composable (width: Int, height: Int) -> Unit)?> { null }

/**
 * Active-closet selector state surfaced to fullscreen Dialog viewers / the try-on composer so they
 * can render the same interactive closet dropdown as the app's screen header. Null hides the dropdown.
 */
data class ClosetSelectorContext(
    val locations: List<Location>,
    val activeLocationId: String,
    val onSetActiveLocation: (String) -> Unit,
)
val LocalClosetSelector = androidx.compose.runtime.compositionLocalOf<ClosetSelectorContext?> { null }

/**
 * Right-aligned header cluster — interactive closet selector + Settings — reused inside
 * fullscreen Dialog viewers and the try-on composer so these stay reachable while an item / outfit /
 * try-on is open (parity with the app's screen header). The closet dropdown switches the active
 * closet in place; [onBeforeNavigate] runs before Settings navigation so the host Dialog dismisses
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

/**
 * Location dropdown button for the top bar — mirrors the sort button style.
 * Only renders content when there are 2+ locations.
 */
@Composable
fun LocationButton(
    locations: List<Location>,
    activeLocationId: String,
    onSetActiveLocation: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    if (locations.size < 2) return
    var expanded by remember { mutableStateOf(false) }
    val allLocationsLabel = stringResource(R.string.filter_all_locations)
    val activeName = when (activeLocationId) {
        ClosetSession.ALL_LOCATIONS_ID -> allLocationsLabel
        else -> locations.find { it.folderId == activeLocationId }?.name ?: allLocationsLabel
    }
    val palette = com.librelookai.ui.theme.LocalWardrobePalette.current
    val shape = RoundedCornerShape(20.dp)
    Box(modifier = modifier) {
        Row(
            modifier = Modifier
                .clip(shape)
                .background(palette.chipBg)
                .border(BorderStroke(1.dp, palette.border), shape)
                .clickable { expanded = true }
                .padding(horizontal = 10.dp, vertical = 6.dp),
            horizontalArrangement = Arrangement.spacedBy(4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(
                Icons.Default.Place,
                contentDescription = null,
                tint = palette.chipFg,
                modifier = Modifier.size(14.dp),
            )
            val isCaveat = com.librelookai.ui.theme.LocalAppFont.current == AppFont.CAVEAT
            Text(
                activeName,
                color = palette.chipFg,
                fontSize = if (isCaveat) 16.sp else 12.sp,
                fontWeight = if (isCaveat) FontWeight.SemiBold else FontWeight.Medium,
                // Cap chip width so a long closet name can't push the screen title to
                // wrap or ellipsize away. The title still owns the remaining space.
                maxLines = 1,
                overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis,
                modifier = Modifier.widthIn(max = 120.dp),
            )
            Icon(
                Icons.Default.KeyboardArrowDown,
                contentDescription = null,
                tint = palette.chipFg,
                modifier = Modifier.size(14.dp),
            )
        }
        DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
            // "All" option first
            val allChecked = activeLocationId == ClosetSession.ALL_LOCATIONS_ID
            DropdownMenuItem(
                text = {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        if (allChecked) Icon(Icons.Default.Check, null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(18.dp))
                        else Spacer(Modifier.size(18.dp))
                        Text(allLocationsLabel)
                    }
                },
                onClick = {
                    onSetActiveLocation(ClosetSession.ALL_LOCATIONS_ID)
                    expanded = false
                },
            )
            locations.sortedBy { it.name }.forEach { loc ->
                val checked = loc.folderId == activeLocationId
                DropdownMenuItem(
                    text = {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                        ) {
                            if (checked) Icon(Icons.Default.Check, null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(18.dp))
                            else Spacer(Modifier.size(18.dp))
                            Text(loc.name)
                        }
                    },
                    onClick = {
                        onSetActiveLocation(loc.folderId)
                        expanded = false
                    },
                )
            }
        }
    }
}
