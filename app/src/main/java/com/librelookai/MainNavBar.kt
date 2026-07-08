package com.librelookai

import androidx.compose.foundation.background
import com.librelookai.core.designsystem.R as DsR
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AutoAwesome
import androidx.compose.material.icons.filled.Checkroom
import androidx.compose.material.icons.filled.FlightTakeoff
import androidx.compose.material.icons.filled.ShoppingBag
import androidx.compose.material.icons.filled.Style
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.librelookai.settings.AppFont

/**
 * Bottom navigation with a raised center "AI" button (Try-On entry point). Stock M3
 * [NavigationBar] can't host a floating center slot, so this is a custom [Row] of four
 * tab slots (Outfits / Wardrobe / Shopping / Travel) split around a fixed-width gap that
 * the center button floats over. Insights moved out of the nav into the screen headers.
 *
 * Tab indices are unchanged from the old nav (Outfits 0, Wardrobe 1, Shopping 2, Travel 3),
 * so [onTabSelected] / [selectedTab] continue to map straight onto MainActivity's tabs.
 */
@Composable
internal fun AppNavBar(
    selectedTab: Int,
    onTabSelected: (Int) -> Unit,
    onTabReselected: (Int) -> Unit = {},
    onCenterClick: () -> Unit = {},
) {
    val palette = com.librelookai.ui.theme.LocalWardrobePalette.current
    data class NavItem(val tab: Int, val labelRes: Int, val icon: ImageVector)
    val leftItems = listOf(
        NavItem(0, DsR.string.nav_styles,   Icons.Default.Style),
        NavItem(1, R.string.nav_wardrobe, Icons.Default.Checkroom),
    )
    val rightItems = listOf(
        NavItem(2, R.string.nav_shopping, Icons.Default.ShoppingBag),
        NavItem(3, R.string.nav_travel,   Icons.Default.FlightTakeoff),
    )
    val navBarBottom = androidx.compose.foundation.layout.WindowInsets.navigationBars
        .asPaddingValues().calculateBottomPadding()

    Box(modifier = Modifier.fillMaxWidth()) {
        Surface(
            color = palette.navBg,
            modifier = Modifier
                .fillMaxWidth()
                .align(Alignment.BottomCenter),
        ) {
            Column {
                HorizontalDivider(color = palette.divider)
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        // Symmetric top/bottom (the system-nav inset stays below) so the tab
                        // icon+label stacks sit vertically centered within the bar.
                        .padding(start = 6.dp, end = 6.dp, top = 4.dp, bottom = 4.dp + navBarBottom),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    leftItems.forEach { item ->
                        NavSlot(item.tab, item.labelRes, item.icon, selectedTab, onTabSelected, onTabReselected, Modifier.weight(1f))
                    }
                    Spacer(Modifier.width(60.dp))
                    rightItems.forEach { item ->
                        NavSlot(item.tab, item.labelRes, item.icon, selectedTab, onTabSelected, onTabReselected, Modifier.weight(1f))
                    }
                }
            }
        }
        // Raised AI button — a FAB that overhangs the panel's top edge by ~20% of its height
        // (52.dp → ~11.dp pokes above the bar). The panel (taller child) sizes the Box, so
        // TopCenter sits on the panel's top border; both the FAB and its title anchor there,
        // keeping the overhang consistent regardless of system-nav inset.
        FloatingActionButton(
            onClick = onCenterClick,
            containerColor = palette.fabBg,
            contentColor = palette.fabFg,
            shape = androidx.compose.foundation.shape.CircleShape,
            modifier = Modifier
                .align(Alignment.TopCenter)
                .offset(y = (-16).dp)
                .size(52.dp),
        ) {
            Icon(
                Icons.Default.AutoAwesome,
                contentDescription = stringResource(R.string.tryon_nav_label),
                modifier = Modifier.size(24.dp),
            )
        }
        // "Try on" title, just below the FAB, aligned with the other tab labels.
        val isCaveat = com.librelookai.ui.theme.LocalAppFont.current == AppFont.CAVEAT
        Text(
            stringResource(R.string.tryon_nav_label),
            color = palette.primary,
            fontSize = if (isCaveat) 16.sp else 12.sp,
            fontWeight = FontWeight.Bold,
            maxLines = 1,
            modifier = Modifier
                .align(Alignment.TopCenter)
                .offset(y = 38.dp),
        )
    }
}

@Composable
private fun NavSlot(
    tab: Int,
    labelRes: Int,
    icon: ImageVector,
    selectedTab: Int,
    onTabSelected: (Int) -> Unit,
    onTabReselected: (Int) -> Unit,
    modifier: Modifier = Modifier,
) {
    val palette = com.librelookai.ui.theme.LocalWardrobePalette.current
    val active = selectedTab == tab
    val label = stringResource(labelRes)
    Column(
        modifier = modifier
            .clip(RoundedCornerShape(12.dp))
            .clickable(
                indication = null,
                interactionSource = remember { androidx.compose.foundation.interaction.MutableInteractionSource() },
            ) { if (active) onTabReselected(tab) else onTabSelected(tab) }
            .padding(horizontal = 2.dp, vertical = 1.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Box(
            modifier = Modifier
                .width(40.dp)
                .clip(RoundedCornerShape(14.dp))
                .background(if (active) palette.navIndicator else androidx.compose.ui.graphics.Color.Transparent)
                .padding(vertical = 2.dp),
            contentAlignment = Alignment.Center,
        ) {
            Icon(
                icon,
                contentDescription = label,
                tint = if (active) palette.primary else palette.textMuted,
                modifier = Modifier.size(26.dp),
            )
        }
        Spacer(Modifier.size(2.dp))
        val isCaveat = com.librelookai.ui.theme.LocalAppFont.current == AppFont.CAVEAT
        Text(
            label,
            color = if (active) palette.primary else palette.textMuted,
            fontSize = if (isCaveat) 16.sp else 12.sp,
            fontWeight = if (active) FontWeight.Bold else FontWeight.SemiBold,
            maxLines = 1,
        )
    }
}
