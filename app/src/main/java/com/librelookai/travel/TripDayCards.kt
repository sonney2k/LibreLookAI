package com.librelookai.travel

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
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
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.CalendarMonth
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Checklist
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedCard
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import coil.compose.AsyncImage
import coil.request.ImageRequest
import com.librelookai.R
import com.librelookai.core.designsystem.R as DsR
import com.librelookai.data.model.DayForecast
import com.librelookai.data.model.Outfit
import com.librelookai.data.model.Trip
import com.librelookai.wardrobe.DriveImage
import com.librelookai.weather.wmoEmoji

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun TripMetaSection(
    trip: Trip,
    editable: Boolean,
    onRename: (String) -> Unit,
    checklistActive: Boolean = false,
    /** Non-null in view mode: renders a checklist toggle pinned to the right of the dates row. */
    onToggleChecklist: (() -> Unit)? = null,
) {
    // `editable` gates the rename affordance; key the rename toggle on it so leaving edit
    // mode collapses any in-progress rename field.
    var renaming by remember(trip.id, editable) { mutableStateOf(false) }
    var draftName by remember(trip.id) { mutableStateOf(trip.name) }
    val keyboardController = LocalSoftwareKeyboardController.current

    Column(
        modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
        verticalArrangement = Arrangement.spacedBy(2.dp),
    ) {
        // The view-mode header already shows the trip name, so only render the name/destination
        // row when editing (where it's needed to rename the trip).
        if (editable) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                if (renaming) {
                    OutlinedTextField(
                        value = draftName,
                        onValueChange = { draftName = it },
                        singleLine = true,
                        keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
                        modifier = Modifier.weight(1f),
                    )
                    Spacer(Modifier.width(4.dp))
                    IconButton(onClick = {
                        val v = draftName.trim()
                        if (v.isNotBlank() && v != trip.name) onRename(v)
                        renaming = false
                        keyboardController?.hide()
                    }) {
                        Icon(Icons.Default.Check, contentDescription = stringResource(DsR.string.action_ok))
                    }
                } else {
                    Text(
                        trip.destination.ifBlank { stringResource(R.string.trip_no_destination) },
                        style = MaterialTheme.typography.titleMedium,
                        modifier = Modifier.weight(1f),
                    )
                    IconButton(onClick = { renaming = true; draftName = trip.name }) {
                        Icon(Icons.Default.Edit, contentDescription = stringResource(R.string.trip_rename))
                    }
                }
            }
        }
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                stringResource(
                    R.string.trip_meta_dates,
                    trip.startDate.ifBlank { "?" },
                    trip.days,
                ),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.weight(1f),
            )
            // Checklist toggle — turns the per-item packing ticks on/off (view mode only).
            if (onToggleChecklist != null) {
                FilterChip(
                    selected = checklistActive,
                    onClick = onToggleChecklist,
                    label = { Text(stringResource(R.string.trip_checklist)) },
                    leadingIcon = {
                        Icon(
                            Icons.Default.Checklist,
                            contentDescription = null,
                            modifier = Modifier.size(FilterChipDefaults.IconSize),
                        )
                    },
                )
            }
        }
    }
}

@Composable
internal fun ForecastStrip(forecast: List<DayForecast>) {
    LazyRow(
        contentPadding = PaddingValues(horizontal = 16.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        itemsIndexed(forecast) { idx, day ->
            Surface(
                shape = MaterialTheme.shapes.small,
                tonalElevation = 2.dp,
            ) {
                Column(
                    modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(2.dp),
                ) {
                    Text(
                        stringResource(R.string.trip_day_header, idx + 1),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Text(wmoEmoji(day.weatherCode), style = MaterialTheme.typography.bodyMedium)
                    Text(
                        "${day.minTempC.toInt()}–${day.maxTempC.toInt()}°",
                        style = MaterialTheme.typography.labelSmall,
                    )
                }
            }
        }
    }
}

@Composable
internal fun TripDayCard(
    dayIndex: Int,
    outfit: Outfit?,
    forecast: DayForecast?,
    imagesById: Map<String, DriveImage>,
    enabled: Boolean,
    showEditIcon: Boolean,
    onClick: () -> Unit,
    onEdit: (() -> Unit)? = null,
    onWear: (() -> Unit)? = null,
    overrideItemIds: List<String>? = null,
    overrideName: String? = null,
    overrideDescription: String? = null,
    packedItemIds: Set<String> = emptySet(),
    onTogglePacked: ((String) -> Unit)? = null,
) {
    val ctx = LocalContext.current
    val effectiveItemIds = overrideItemIds ?: outfit?.itemIds ?: emptyList()
    val items = effectiveItemIds.mapNotNull { imagesById[it] }
    val effectiveName = overrideName?.takeIf { it.isNotBlank() } ?: outfit?.name
    val effectiveDescription = overrideDescription?.takeIf { it.isNotBlank() } ?: outfit?.description
    OutlinedCard(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp)
            .clickable(enabled = enabled && outfit != null, onClick = onClick),
    ) {
        Column(
            modifier = Modifier.padding(12.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    stringResource(R.string.trip_day_header, dayIndex + 1),
                    style = MaterialTheme.typography.labelMedium,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.primary,
                )
                if (forecast != null) {
                    Spacer(Modifier.width(8.dp))
                    Text(
                        "${wmoEmoji(forecast.weatherCode)}  ${forecast.minTempC.toInt()}–${forecast.maxTempC.toInt()}°",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                // Outfit title sits next to the Day label, taking the remaining space.
                if (effectiveName?.isNotBlank() == true) {
                    Spacer(Modifier.width(8.dp))
                    Text(
                        effectiveName,
                        style = MaterialTheme.typography.titleSmall,
                        maxLines = 1,
                        overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f),
                    )
                } else {
                    Spacer(Modifier.weight(1f))
                }
                if (outfit != null && showEditIcon && onEdit != null) {
                    IconButton(onClick = onEdit, modifier = Modifier.size(28.dp)) {
                        Icon(
                            Icons.Default.Edit,
                            contentDescription = stringResource(R.string.trip_modify_outfit),
                            tint = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.size(16.dp),
                        )
                    }
                }
                // "Wear today" — top-right, at the same level as the Day label.
                if (outfit != null && onWear != null) {
                    var wornToday by remember(outfit.id) { mutableStateOf(false) }
                    TextButton(
                        onClick = { onWear(); wornToday = true },
                        contentPadding = PaddingValues(horizontal = 8.dp, vertical = 0.dp),
                    ) {
                        Icon(
                            Icons.Default.CalendarMonth,
                            contentDescription = null,
                            modifier = Modifier.size(16.dp),
                            tint = if (wornToday) MaterialTheme.colorScheme.primary
                                   else MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        Spacer(Modifier.width(4.dp))
                        Text(
                            if (wornToday) stringResource(R.string.outfits_worn_today)
                            else stringResource(R.string.outfits_wear_today),
                            style = MaterialTheme.typography.labelSmall,
                            color = if (wornToday) MaterialTheme.colorScheme.primary
                                    else MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            }
            if (items.isNotEmpty()) {
                LazyRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    items(items, key = { it.driveId }) { image ->
                        val packed = image.driveId in packedItemIds
                        Box(
                            modifier = Modifier
                                .size(64.dp)
                                .clip(MaterialTheme.shapes.small),
                        ) {
                            AsyncImage(
                                model = remember(image.driveId, image.version) {
                                    ImageRequest.Builder(ctx)
                                        .data(image.localPath)
                                        .memoryCacheKey("${image.driveId}_${image.version}")
                                        .build()
                                },
                                contentDescription = image.name,
                                modifier = Modifier
                                    .fillMaxSize()
                                    .then(if (packed) Modifier.alpha(0.45f) else Modifier),
                                contentScale = ContentScale.Crop,
                            )
                            // View-mode packing tick — toggles "packed" independently of the card tap.
                            if (onTogglePacked != null) {
                                PackedCheck(
                                    packed = packed,
                                    onToggle = { onTogglePacked(image.driveId) },
                                    modifier = Modifier.align(Alignment.TopEnd).padding(3.dp),
                                )
                            }
                        }
                    }
                }
            } else {
                Text(
                    stringResource(R.string.outfits_missing_items),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.outline,
                )
            }
            if (effectiveDescription?.isNotBlank() == true) {
                Text(
                    effectiveDescription,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

/** Small circular packing tick: filled when packed, hollow outline otherwise. */
@Composable
private fun PackedCheck(
    packed: Boolean,
    onToggle: () -> Unit,
    modifier: Modifier = Modifier,
    size: androidx.compose.ui.unit.Dp = 20.dp,
) {
    Box(
        modifier = modifier
            .size(size)
            .clip(CircleShape)
            .background(
                if (packed) MaterialTheme.colorScheme.primary
                else MaterialTheme.colorScheme.surface.copy(alpha = 0.85f),
            )
            .border(
                1.dp,
                if (packed) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outline,
                CircleShape,
            )
            .clickable(onClick = onToggle),
        contentAlignment = Alignment.Center,
    ) {
        if (packed) {
            Icon(
                Icons.Default.Check,
                contentDescription = stringResource(R.string.trip_packed),
                tint = MaterialTheme.colorScheme.onPrimary,
                modifier = Modifier.size(size * 0.7f),
            )
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun ExtrasCard(
    items: List<String>,
    editable: Boolean,
    checklist: Boolean,
    packedExtras: Set<String>,
    onTogglePacked: (String) -> Unit,
    onUpdate: (List<String>) -> Unit,
) {
    OutlinedCard(
        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp),
    ) {
        Column(
            modifier = Modifier.padding(12.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Text(
                stringResource(R.string.travel_extra_items),
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.SemiBold,
            )
            if (editable) {
                // Editable list: each row is a removable item; a field at the bottom adds more.
                items.forEach { item ->
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(item, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f))
                        IconButton(onClick = { onUpdate(items - item) }) {
                            Icon(
                                Icons.Default.Close,
                                contentDescription = stringResource(R.string.action_remove),
                                modifier = Modifier.size(18.dp),
                            )
                        }
                    }
                }
                var draft by remember { mutableStateOf("") }
                val keyboardController = LocalSoftwareKeyboardController.current
                val addDraft = {
                    val v = draft.trim()
                    if (v.isNotBlank() && v !in items) onUpdate(items + v)
                    draft = ""
                }
                Row(verticalAlignment = Alignment.CenterVertically) {
                    OutlinedTextField(
                        value = draft,
                        onValueChange = { draft = it },
                        singleLine = true,
                        placeholder = { Text(stringResource(R.string.tryon_add_item)) },
                        keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
                        modifier = Modifier.weight(1f),
                    )
                    Spacer(Modifier.width(4.dp))
                    IconButton(onClick = { addDraft(); keyboardController?.hide() }, enabled = draft.isNotBlank()) {
                        Icon(Icons.Default.Add, contentDescription = stringResource(R.string.action_add))
                    }
                }
            } else if (checklist) {
                // Checklist mode: each item is a packing checkbox row.
                items.forEach { item ->
                    val packed = item in packedExtras
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable { onTogglePacked(item) },
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        PackedCheck(packed = packed, onToggle = { onTogglePacked(item) })
                        Text(
                            item,
                            style = MaterialTheme.typography.bodyMedium,
                            color = if (packed) MaterialTheme.colorScheme.onSurfaceVariant
                                    else MaterialTheme.colorScheme.onSurface,
                            modifier = Modifier.weight(1f).then(if (packed) Modifier.alpha(0.6f) else Modifier),
                        )
                    }
                }
            } else {
                // View mode, checklist off: plain item list.
                items.forEach { item ->
                    Text(
                        item,
                        style = MaterialTheme.typography.bodyMedium,
                        modifier = Modifier.fillMaxWidth(),
                    )
                }
            }
        }
    }
}

