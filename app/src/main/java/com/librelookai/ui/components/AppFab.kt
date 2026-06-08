package com.librelookai.ui.components

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.grid.LazyGridState
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import com.librelookai.ui.theme.LocalWardrobePalette

/**
 * One shared, scroll-aware Extended FAB used by every primary screen (Outfits, Wardrobe,
 * Calendar, Shopping, Travel). Replaces the ad-hoc per-screen `+` buttons.
 *
 * It uses the **same** [LocalWardrobePalette] `fabBg`/`fabFg` tokens as the nav-bar "Try on"
 * FAB, so the two read as one family across all themes. Callers position it via [modifier]
 * (typically `Modifier.align(Alignment.BottomEnd).padding(...)` inside a `Box`).
 *
 * @param label   the verb shown when expanded (e.g. "Add item")
 * @param icon    leading icon
 * @param expanded true = labeled pill, false = icon-only circle (drive from [rememberFabExpanded])
 * @param visible hidden during selection mode / offline (slides + fades out)
 */
@Composable
fun AppFab(
    label: String,
    icon: ImageVector,
    onClick: () -> Unit,
    expanded: Boolean,
    modifier: Modifier = Modifier,
    visible: Boolean = true,
) {
    val palette = LocalWardrobePalette.current
    AnimatedVisibility(
        visible = visible,
        enter = scaleIn() + fadeIn(),
        exit = scaleOut() + fadeOut(),
        modifier = modifier,
    ) {
        ExtendedFloatingActionButton(
            onClick = onClick,
            expanded = expanded,
            containerColor = palette.fabBg,
            contentColor = palette.fabFg,
            icon = { Icon(icon, contentDescription = null, modifier = Modifier.size(22.dp)) },
            text = { Text(label) },
            modifier = Modifier.semantics { contentDescription = label },
        )
    }
}

/**
 * Drives [AppFab]'s `expanded` flag from a grid's scroll state: labeled when at rest or at the
 * top, collapsed to a circle while actively scrolling away from the top.
 */
@Composable
fun rememberFabExpanded(state: LazyGridState): Boolean {
    val expanded by remember(state) {
        derivedStateOf {
            state.firstVisibleItemIndex == 0 && state.firstVisibleItemScrollOffset == 0 ||
                !state.isScrollInProgress
        }
    }
    return expanded
}

/** [LazyListState] overload — same behavior as the [LazyGridState] one. */
@Composable
fun rememberFabExpanded(state: LazyListState): Boolean {
    val expanded by remember(state) {
        derivedStateOf {
            state.firstVisibleItemIndex == 0 && state.firstVisibleItemScrollOffset == 0 ||
                !state.isScrollInProgress
        }
    }
    return expanded
}
