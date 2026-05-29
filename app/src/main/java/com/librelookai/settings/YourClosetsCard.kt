package com.librelookai.settings

import androidx.compose.animation.animateColorAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.librelookai.R
import com.librelookai.data.model.Location

/**
 * "Your closets" card — one row per closet (leading dot, name, city + item count,
 * edit pencil) plus an "Add a closet" row. Tapping a non-default row makes it the
 * default instantly (no confirm). See README §"Your closets" card.
 */
@Composable
fun YourClosetsCard(
    closets: List<Location>,
    defaultFolderId: String?,
    itemCounts: Map<String, Int>,
    onSetDefault: (String) -> Unit,
    onEditCloset: (Location) -> Unit,
    onAddCloset: () -> Unit,
) {
    val haptics = LocalHapticFeedback.current
    val effectiveDefault = defaultFolderId ?: closets.firstOrNull()?.folderId
    SettingsCard {
        closets.forEach { closet ->
            val isDefault = closet.folderId == effectiveDefault
            ClosetRow(
                closet = closet,
                isDefault = isDefault,
                itemCount = itemCounts[closet.folderId],
                onTap = {
                    if (!isDefault) {
                        haptics.performHapticFeedback(HapticFeedbackType.LongPress)
                        onSetDefault(closet.folderId)
                    }
                },
                onEdit = { onEditCloset(closet) },
            )
            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
        }
        SettingsRow(
            icon = Icons.Filled.Add,
            label = stringResource(R.string.settings_add_closet),
            isLast = true,
            onClick = onAddCloset,
        )
    }
}

@Composable
private fun ClosetRow(
    closet: Location,
    isDefault: Boolean,
    itemCount: Int?,
    onTap: () -> Unit,
    onEdit: () -> Unit,
) {
    val dotColor by animateColorAsState(
        targetValue = if (isDefault) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outlineVariant,
        label = "closetDot",
    )
    val bg = if (isDefault) primarySoft() else androidx.compose.ui.graphics.Color.Transparent
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .background(bg)
            .clickable(onClick = onTap)
            .semantics { role = Role.Button }
            .heightIn(min = 48.dp)
            .padding(horizontal = 14.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Box(
            modifier = Modifier
                .size(8.dp)
                .clip(CircleShape)
                .background(dotColor),
        )
        Column(modifier = Modifier.weight(1f)) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(
                    text = closet.name,
                    fontSize = 14.sp,
                    fontWeight = if (isDefault) FontWeight.Bold else FontWeight.SemiBold,
                    color = MaterialTheme.colorScheme.onBackground,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                if (isDefault) {
                    Text(
                        text = stringResource(R.string.settings_closets_default_badge),
                        fontSize = 10.sp,
                        fontWeight = FontWeight.Bold,
                        letterSpacing = 0.4.sp,
                        color = MaterialTheme.colorScheme.primary,
                        modifier = Modifier
                            .clip(RoundedCornerShape(8.dp))
                            .background(MaterialTheme.colorScheme.primaryContainer)
                            .padding(horizontal = 6.dp, vertical = 2.dp),
                    )
                }
            }
            val subtitle = listOfNotNull(
                closet.geoLocation.takeIf { it.isNotBlank() },
                itemCount?.let { stringResource(R.string.settings_closet_item_count, it) },
            ).joinToString(" · ")
            if (subtitle.isNotBlank()) {
                Text(
                    text = subtitle,
                    fontSize = 11.5.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(top = 1.dp),
                )
            }
        }
        IconButton(onClick = onEdit, modifier = Modifier.size(24.dp)) {
            Icon(
                imageVector = Icons.Filled.Edit,
                contentDescription = stringResource(R.string.settings_edit_closet),
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.size(16.dp),
            )
        }
    }
}
