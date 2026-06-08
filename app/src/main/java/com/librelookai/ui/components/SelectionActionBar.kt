package com.librelookai.ui.components

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.Icon
import androidx.compose.material3.LocalTextStyle
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.PlatformTextStyle
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.LineHeightStyle
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.librelookai.R
import com.librelookai.ui.theme.LocalWardrobePalette
import androidx.compose.ui.res.stringResource

/**
 * Process-global latch tracking whether any [SelectionActionBar] is currently shown. `AppContent`
 * reads [isVisible] to hide the bottom nav bar + floating weather badge while multi-select is
 * active (the selection bar takes the nav bar's place). A counter (not a bool) so an in-flight
 * tab switch where the old + new screen's bars briefly coexist can't clear the flag early.
 */
object SelectionBarVisibility {
    private var count by mutableIntStateOf(0)
    val isVisible: Boolean get() = count > 0

    internal fun enter() { count++ }
    internal fun leave() { count = (count - 1).coerceAtLeast(0) }
}

/** One action in the [SelectionActionBar]. Order matters: primary first, danger last. */
data class SelectionAction(
    val label: String,
    val icon: ImageVector,
    val kind: Kind = Kind.Normal,
    val enabled: Boolean = true,
    val onClick: () -> Unit,
) {
    enum class Kind { Primary, Normal, Danger }
}

/**
 * One shared, bottom-anchored multi-select action bar. Replaces every per-screen selection
 * speed-dial column and the Calendar dropdown.
 *
 * Sits **below** the content (it does not cover the items being selected, unlike the old
 * speed-dials). Slides up when selection starts; the caller hides [AppFab] while it is visible.
 *
 * @param count   number of selected items ("N selected")
 * @param onClear clears the selection (the ✕)
 * @param actions equal-ish stacked icon-over-label buttons, coloured by [SelectionAction.Kind]
 */
@Composable
fun SelectionActionBar(
    count: Int,
    onClear: () -> Unit,
    actions: List<SelectionAction>,
    modifier: Modifier = Modifier,
    visible: Boolean = true,
) {
    val palette = LocalWardrobePalette.current
    // Tell AppContent to hide the bottom nav bar + weather badge while the bar is up.
    DisposableEffect(visible) {
        if (visible) SelectionBarVisibility.enter()
        onDispose { if (visible) SelectionBarVisibility.leave() }
    }
    AnimatedVisibility(
        visible = visible,
        enter = slideInVertically { it },
        exit = slideOutVertically { it },
        modifier = modifier,
    ) {
        Surface(
            color = palette.surface,
            shadowElevation = 12.dp,
            modifier = Modifier.fillMaxWidth(),
        ) {
            Column(
                Modifier
                    .fillMaxWidth()
                    .padding(start = 14.dp, end = 14.dp, top = 10.dp, bottom = 14.dp),
            ) {
                // Top divider line.
                Spacer(
                    Modifier
                        .fillMaxWidth()
                        .height(1.dp)
                        .background(palette.divider),
                )
                Spacer(Modifier.height(10.dp))
                // Count row.
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Box(
                        Modifier
                            .size(26.dp)
                            .clip(CircleShape)
                            .background(palette.surface2)
                            .clickable(onClick = onClear),
                        contentAlignment = Alignment.Center,
                    ) {
                        Icon(
                            Icons.Default.Close,
                            contentDescription = stringResource(R.string.action_clear_selection),
                            tint = palette.textMid,
                            modifier = Modifier.size(15.dp),
                        )
                    }
                    Spacer(Modifier.width(10.dp))
                    Text(
                        stringResource(R.string.sel_count, count),
                        color = palette.text,
                        fontSize = 14.sp,
                        fontWeight = FontWeight.Bold,
                    )
                }
                Spacer(Modifier.height(10.dp))
                // Action row.
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    actions.forEach { action ->
                        ActionButton(
                            action = action,
                            weight = if (action.kind == SelectionAction.Kind.Primary) 1.4f else 1f,
                            modifier = Modifier,
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun androidx.compose.foundation.layout.RowScope.ActionButton(
    action: SelectionAction,
    weight: Float,
    modifier: Modifier = Modifier,
) {
    val palette = LocalWardrobePalette.current
    val bg: Color
    val fg: Color
    var border: BorderStroke? = null
    when (action.kind) {
        SelectionAction.Kind.Primary -> {
            bg = palette.fabBg
            fg = palette.fabFg
        }
        SelectionAction.Kind.Danger -> {
            bg = palette.error.copy(alpha = 0.09f)
            fg = palette.error
        }
        SelectionAction.Kind.Normal -> {
            bg = palette.surface2
            fg = palette.textMid
            border = BorderStroke(1.dp, palette.border)
        }
    }
    val shape = RoundedCornerShape(14.dp)
    Column(
        modifier = modifier
            .weight(weight)
            .height(46.dp)
            .clip(shape)
            .background(bg)
            .then(if (border != null) Modifier.border(border, shape) else Modifier)
            .alpha(if (action.enabled) 1f else 0.4f)
            .clickable(enabled = action.enabled, onClick = action.onClick)
            .semantics { contentDescription = action.label },
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Icon(action.icon, contentDescription = null, tint = fg, modifier = Modifier.size(18.dp))
        Spacer(Modifier.height(2.dp))
        Text(
            action.label,
            color = fg,
            fontSize = 10.5.sp,
            fontWeight = FontWeight.Bold,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            textAlign = TextAlign.Center,
            // Trim the font's built-in ascent/descent padding so the icon+label block centres on
            // the visible glyphs (default padding leaves dead space below the text, shoving the
            // content visually upward inside the pill). Merge onto LocalTextStyle so the app's
            // themed font family (AppFont) is preserved.
            style = LocalTextStyle.current.merge(
                TextStyle(
                    platformStyle = PlatformTextStyle(includeFontPadding = false),
                    lineHeightStyle = LineHeightStyle(
                        alignment = LineHeightStyle.Alignment.Center,
                        trim = LineHeightStyle.Trim.Both,
                    ),
                ),
            ),
        )
    }
}
