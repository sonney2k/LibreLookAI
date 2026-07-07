package com.librelookai

import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.librelookai.settings.AppFont

/**
 * Consistent top header bar used across all main screens.
 *
 * Renders a [title] row with an optional [leadingIcon] and an optional
 * [trailingContent] slot (e.g. a sort/filter button), followed by a divider.
 * Typography and colors are always sourced from [MaterialTheme] so the bar
 * automatically adapts to light/dark mode and dynamic colour.
 */
@Composable
fun AppScreenHeader(
    title: String,
    modifier: Modifier = Modifier,
    leadingIcon: ImageVector? = null,
    trailingContent: @Composable (() -> Unit)? = null,
    onSettingsClick: (() -> Unit)? = null,
) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .padding(
                start = if (leadingIcon != null) 12.dp else 16.dp,
                end = if (trailingContent != null || onSettingsClick != null) 4.dp else 16.dp,
                top = 8.dp,
                bottom = 8.dp,
            ),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (leadingIcon != null) {
            Icon(
                imageVector = leadingIcon,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.primary,
                modifier = Modifier.size(22.dp),
            )
            Spacer(Modifier.width(8.dp))
        }
        val isCaveat = com.librelookai.ui.theme.LocalAppFont.current == AppFont.CAVEAT
        Text(
            text = title,
            style = if (isCaveat) MaterialTheme.typography.headlineLarge else MaterialTheme.typography.titleMedium,
            fontWeight = if (isCaveat) FontWeight.Bold else FontWeight.SemiBold,
            color = MaterialTheme.colorScheme.onSurface,
            // weight(1f) lets the title take remaining space; maxLines=1 + Ellipsis prevents
            // wrapping when trailing content (e.g. a long closet name) gets squeezed in.
            maxLines = 1,
            overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis,
            softWrap = false,
            modifier = Modifier.weight(1f),
        )
        trailingContent?.invoke()
        if (onSettingsClick != null) {
            androidx.compose.material3.IconButton(
                onClick = onSettingsClick,
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
    HorizontalDivider()
}
