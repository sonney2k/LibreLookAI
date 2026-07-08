package com.librelookai.outfit

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.filled.CalendarMonth
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material.icons.filled.FlightTakeoff
import androidx.compose.material.icons.outlined.FavoriteBorder
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.DatePicker
import androidx.compose.material3.DatePickerDialog
import androidx.compose.material3.OutlinedCard
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.InputChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.SuggestionChip
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberDatePickerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil.compose.AsyncImage
import coil.request.ImageRequest
import com.librelookai.feature.outfit.R
import com.librelookai.core.designsystem.R as DsR
import com.librelookai.data.model.Location
import com.librelookai.data.model.Outfit
import com.librelookai.util.Analytics
import com.librelookai.util.LocalIsOffline
import com.librelookai.wardrobe.DriveImage
import java.time.LocalDate

@OptIn(ExperimentalFoundationApi::class, ExperimentalLayoutApi::class)
@Composable
internal fun OutfitCard(
    style: Outfit,
    itemsById: Map<String, DriveImage>,
    locations: List<Location> = emptyList(),
    tripName: String? = null,
    isSelected: Boolean = false,
    isSelectionMode: Boolean = false,
    loved: Boolean = false,
    onEdit: () -> Unit,
    onDelete: () -> Unit,
    onWear: () -> Unit,
    onToggleLoved: () -> Unit = {},
    onOpen: () -> Unit = {},
    onToggleSelection: () -> Unit = {},
) {
    val isOffline = LocalIsOffline.current
    var showDeleteDialog by remember { mutableStateOf(false) }

    if (showDeleteDialog) {
        AlertDialog(
            onDismissRequest = { showDeleteDialog = false },
            title = { Text(stringResource(R.string.outfits_delete_title)) },
            text = { Text(stringResource(R.string.outfits_delete_text, style.name)) },
            confirmButton = {
                TextButton(onClick = {
                    Analytics.action("OutfitEditor", "confirm_delete")
                    onDelete(); showDeleteDialog = false
                }) {
                    Text(stringResource(DsR.string.action_delete), color = MaterialTheme.colorScheme.error)
                }
            },
            dismissButton = {
                TextButton(onClick = { showDeleteDialog = false }) { Text(stringResource(DsR.string.action_cancel)) }
            },
        )
    }

    OutlinedCard(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 12.dp)
            .combinedClickable(
                onClick = {
                    if (isSelectionMode) {
                        Analytics.action("Outfits", "toggle_selection")
                        onToggleSelection()
                    } else {
                        Analytics.action("Outfits", "open_fullscreen")
                        onOpen()
                    }
                },
                onLongClick = {
                    Analytics.action("Outfits", "long_press_select")
                    onToggleSelection()
                },
            ),
        border = if (isSelected)
            androidx.compose.foundation.BorderStroke(2.dp, MaterialTheme.colorScheme.primary)
        else CardDefaults.outlinedCardBorder(),
    ) {
        Column(
            modifier = Modifier.padding(12.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            val locationName = if (locations.size > 1) {
                remember(style.itemIds, itemsById, locations) {
                    val folderIds = style.itemIds
                        .mapNotNull { itemsById[it]?.folderId?.takeIf { f -> f.isNotEmpty() } }
                        .toSet()
                    val names = folderIds.mapNotNull { fid -> locations.find { it.folderId == fid }?.name }
                    names.distinct().sorted().joinToString(", ").takeIf { it.isNotBlank() }
                }
            } else null
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.Top,
            ) {
                Text(
                    style.name,
                    style = MaterialTheme.typography.titleSmall,
                    modifier = Modifier.weight(1f),
                )
                if (locationName != null) {
                    Box(
                        modifier = Modifier
                            .padding(start = 6.dp)
                            .background(
                                color = MaterialTheme.colorScheme.secondaryContainer,
                                shape = MaterialTheme.shapes.extraSmall,
                            )
                            .padding(horizontal = 4.dp, vertical = 1.dp),
                    ) {
                        Text(
                            text = locationName,
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSecondaryContainer,
                            fontSize = 8.sp,
                            lineHeight = 10.sp,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                }
            }
            if (style.description.isNotBlank()) {
                Text(
                    style.description,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            if (style.tags.isNotEmpty() || tripName != null) {
                FlowRow(
                    horizontalArrangement = Arrangement.spacedBy(4.dp),
                    verticalArrangement = Arrangement.spacedBy(4.dp),
                ) {
                    tripName?.let { OutfitTripChip(it) }
                    style.tags.forEach { OutfitTagChip(it) }
                }
            }
            val styleItems = style.itemIds.mapNotNull { itemsById[it] }
            if (styleItems.isEmpty()) {
                Text(
                    stringResource(DsR.string.outfits_missing_items),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.outline,
                )
            } else {
                LazyRow(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                    items(styleItems, key = { it.driveId }) { image ->
                        val ctx = LocalContext.current
                        val itemLocName = if (locations.size > 1)
                            remember(image.folderId, locations) {
                                locations.find { it.folderId == image.folderId }?.name
                            }
                        else null
                        Box(modifier = Modifier.size(72.dp)) {
                            AsyncImage(
                                model = remember(image.driveId, image.version) {
                                    ImageRequest.Builder(ctx)
                                        .data(image.localPath)
                                        .memoryCacheKey("${image.driveId}_${image.version}")
                                        .build()
                                },
                                contentDescription = image.name,
                                modifier = Modifier.fillMaxSize(),
                                contentScale = ContentScale.Crop,
                            )
                            if (itemLocName != null) {
                                Box(
                                    modifier = Modifier
                                        .align(Alignment.TopEnd)
                                        .padding(2.dp)
                                        .background(
                                            color = MaterialTheme.colorScheme.secondaryContainer.copy(alpha = 0.85f),
                                            shape = MaterialTheme.shapes.extraSmall,
                                        )
                                        .padding(horizontal = 3.dp, vertical = 1.dp),
                                ) {
                                    Text(
                                        text = itemLocName,
                                        style = MaterialTheme.typography.labelSmall,
                                        color = MaterialTheme.colorScheme.onSecondaryContainer,
                                        fontSize = 7.sp,
                                        lineHeight = 9.sp,
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis,
                                    )
                                }
                            }
                        }
                    }
                }
            }
            // Bottom action row: Edit (left) | favourite + Wear… (right) — hidden in selection mode and offline
            if (!isSelectionMode && !isOffline) Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                TextButton(
                    onClick = onEdit,
                    contentPadding = PaddingValues(horizontal = 8.dp, vertical = 4.dp),
                ) {
                    Icon(
                        Icons.Default.Edit,
                        contentDescription = null,
                        modifier = Modifier.size(16.dp),
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Spacer(Modifier.width(4.dp))
                    Text(
                        stringResource(DsR.string.action_edit),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                Row(verticalAlignment = Alignment.CenterVertically) {
                    IconButton(
                        onClick = {
                            Analytics.action("Outfits", if (loved) "unlove" else "love")
                            onToggleLoved()
                        },
                        modifier = Modifier.size(32.dp),
                    ) {
                        Icon(
                            imageVector = if (loved) Icons.Filled.Favorite else Icons.Outlined.FavoriteBorder,
                            contentDescription = stringResource(R.string.outfits_favorite),
                            modifier = Modifier.size(18.dp),
                            tint = if (loved) MaterialTheme.colorScheme.primary
                                   else MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    Spacer(Modifier.width(4.dp))
                    TextButton(
                        onClick = {
                            Analytics.action("Outfits", "wear_via_calendar")
                            onWear()
                        },
                        contentPadding = PaddingValues(horizontal = 8.dp, vertical = 4.dp),
                    ) {
                        Icon(
                            Icons.Default.CalendarMonth,
                            contentDescription = null,
                            modifier = Modifier.size(16.dp),
                            tint = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        Spacer(Modifier.width(4.dp))
                        Text(
                            stringResource(R.string.outfits_wear),
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        Icon(
                            Icons.AutoMirrored.Filled.KeyboardArrowRight,
                            contentDescription = null,
                            modifier = Modifier.size(16.dp),
                            tint = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            }
        }
    }
}

/**
 * Date picker for choosing which day an outfit is worn (defaults to today). Used by the outfit
 * list card and the fullscreen viewer's "Wear…" action so the wear can be logged on any day, not
 * just today. A [DatePickerDialog] opens its own window, severing the locale-overridden context
 * chain, so LocalContext / LocalConfiguration are re-provided in every slot (CLAUDE.md → Dialog
 * quirks). Mirrors the calendar's move/copy date picker (epoch-day ⇄ UTC-millis conversion).
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun WearDatePickerDialog(
    onDismiss: () -> Unit,
    onConfirm: (LocalDate) -> Unit,
) {
    val parentContext = LocalContext.current
    val parentConfiguration = LocalConfiguration.current
    val datePickerState = rememberDatePickerState(
        initialSelectedDateMillis = LocalDate.now().toEpochDay() * 86_400_000L,
    )
    DatePickerDialog(
        onDismissRequest = onDismiss,
        confirmButton = {
            CompositionLocalProvider(
                LocalContext provides parentContext,
                LocalConfiguration provides parentConfiguration,
            ) {
                TextButton(onClick = {
                    datePickerState.selectedDateMillis?.let {
                        onConfirm(LocalDate.ofEpochDay(it / 86_400_000L))
                    }
                    onDismiss()
                }) { Text(stringResource(DsR.string.action_ok)) }
            }
        },
        dismissButton = {
            CompositionLocalProvider(
                LocalContext provides parentContext,
                LocalConfiguration provides parentConfiguration,
            ) {
                TextButton(onClick = onDismiss) { Text(stringResource(DsR.string.action_cancel)) }
            }
        },
    ) {
        CompositionLocalProvider(
            LocalContext provides parentContext,
            LocalConfiguration provides parentConfiguration,
        ) {
            DatePicker(state = datePickerState)
        }
    }
}

// ---------- Refinement section (also used by TravelScreen) ----------

@Composable
private fun refinementPresets() = listOf(
    stringResource(R.string.outfits_refine_casual),
    stringResource(R.string.outfits_refine_formal),
    stringResource(R.string.outfits_refine_diff_colors),
    stringResource(R.string.outfits_refine_warmer),
    stringResource(R.string.outfits_refine_lighter),
    stringResource(R.string.outfits_refine_trendy),
    stringResource(R.string.outfits_refine_simpler),
    stringResource(R.string.outfits_refine_bold),
)

@OptIn(ExperimentalLayoutApi::class, ExperimentalMaterial3Api::class)
@Composable
internal fun RefinementSection(
    input: String,
    feedbackHistory: List<String>,
    onInputChange: (String) -> Unit,
    onSubmitFreetext: () -> Unit,
    onSubmitPreset: (String) -> Unit,
    presets: List<String> = emptyList(),
    modifier: Modifier = Modifier,
) {
    val effectivePresets = presets.ifEmpty { refinementPresets() }
    Column(modifier = modifier, verticalArrangement = Arrangement.spacedBy(8.dp)) {
        HorizontalDivider()

        Text(
            stringResource(R.string.outfits_refine_label),
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.primary,
        )

        // Applied feedback chips (read-only history)
        if (feedbackHistory.isNotEmpty()) {
            FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                feedbackHistory.forEach { fb ->
                    InputChip(
                        selected = true,
                        onClick = {},
                        label = { Text(fb, style = MaterialTheme.typography.labelSmall) },
                    )
                }
            }
        }

        // Preset quick-picks
        FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            effectivePresets.forEach { preset ->
                SuggestionChip(
                    onClick = { onSubmitPreset(preset) },
                    label = { Text(preset, style = MaterialTheme.typography.labelSmall) },
                )
            }
        }

        // Freetext + send
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            OutlinedTextField(
                value = input,
                onValueChange = onInputChange,
                placeholder = { Text(stringResource(R.string.outfits_refine_custom), style = MaterialTheme.typography.bodySmall) },
                singleLine = true,
                textStyle = MaterialTheme.typography.bodySmall,
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Send),
                keyboardActions = KeyboardActions(onSend = { if (input.isNotBlank()) onSubmitFreetext() }),
                modifier = Modifier.weight(1f),
            )
            androidx.compose.material3.IconButton(
                onClick = onSubmitFreetext,
                enabled = input.isNotBlank(),
            ) {
                Icon(Icons.AutoMirrored.Filled.Send, contentDescription = stringResource(R.string.cd_refine))
            }
        }
    }
}

/** Trip-name chip shown on travel outfits, distinguished from free-form tags by a flight icon. */
@Composable
internal fun OutfitTripChip(name: String) {
    Surface(
        shape = MaterialTheme.shapes.extraSmall,
        color = MaterialTheme.colorScheme.tertiaryContainer,
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(3.dp),
        ) {
            Icon(
                Icons.Default.FlightTakeoff,
                contentDescription = null,
                modifier = Modifier.size(12.dp),
                tint = MaterialTheme.colorScheme.onTertiaryContainer,
            )
            Text(
                text = name,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onTertiaryContainer,
            )
        }
    }
}

@Composable
internal fun OutfitTagChip(label: String) {
    Surface(
        shape = MaterialTheme.shapes.extraSmall,
        color = MaterialTheme.colorScheme.secondaryContainer,
    ) {
        Text(
            text = label,
            modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp),
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSecondaryContainer,
        )
    }
}

/**
 * Pager-based full-screen viewer for outfits. Each page lays out the outfit's
 * items grouped by category (tops, bottoms, footwear, outerwear, accessories,
 * other). The FAB (bottom-right) hosts wear / edit / delete actions.
 */
