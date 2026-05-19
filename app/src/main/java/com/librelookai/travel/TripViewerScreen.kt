package com.librelookai.travel
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
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
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AutoAwesome
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.AssistChip
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedCard
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.res.stringResource
import coil.compose.AsyncImage
import coil.request.ImageRequest
import com.librelookai.R
import com.librelookai.data.model.DayForecast
import com.librelookai.data.model.Trip
import com.librelookai.data.model.Outfit
import com.librelookai.outfit.OutfitsViewModel
import com.librelookai.outfit.TripContext
import com.librelookai.settings.ProfileViewModel
import com.librelookai.util.AiProcessingOverlay
import com.librelookai.util.LocalIsOffline
import com.librelookai.wardrobe.DriveImage
import com.librelookai.wardrobe.WardrobeViewModel
import com.librelookai.weather.wmoEmoji

@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
fun TripViewerScreen(
    tripId: String,
    tripsViewModel: TripsViewModel,
    outfitsViewModel: OutfitsViewModel,
    wardrobeViewModel: WardrobeViewModel,
    profileViewModel: ProfileViewModel,
    onClose: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val isOffline = LocalIsOffline.current
    val tripsState by tripsViewModel.state.collectAsState()
    val outfitsState by outfitsViewModel.state.collectAsState()
    val wardrobeState by wardrobeViewModel.state.collectAsState()
    val profileState by profileViewModel.state.collectAsState()
    val bulkRefining by tripsViewModel.bulkRefining.collectAsState()

    val trip = remember(tripId, tripsState.trips) { tripsState.trips.find { it.id == tripId } }
    if (trip == null) {
        // Trip was deleted (or not loaded yet) — fall back to the list.
        Column(modifier = modifier.fillMaxSize()) {
            TripHeader(name = "", onClose = onClose, onDelete = {}, deleteEnabled = false)
            Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Text(
                    stringResource(R.string.trip_not_found),
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        return
    }

    val imagesById = remember(wardrobeState.images) {
        wardrobeState.images.associateBy { it.driveId }
    }
    val outfitsById = remember(outfitsState.outfits) {
        outfitsState.outfits.associateBy { it.id }
    }
    val tripOutfits = remember(trip.outfitIds, outfitsById) {
        trip.outfitIds.mapNotNull { outfitsById[it] }
    }

    var showDeleteDialog by remember { mutableStateOf(false) }
    val isBulkRefining = tripId in bulkRefining

    Column(modifier = modifier.fillMaxSize()) {
        TripHeader(
            name = trip.name,
            onClose = onClose,
            onDelete = { showDeleteDialog = true },
            deleteEnabled = true,
        )
        Box(modifier = Modifier.weight(1f).fillMaxWidth()) {
            LazyColumn(
                contentPadding = PaddingValues(bottom = 16.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                item {
                    TripMetaSection(
                        trip = trip,
                        onRename = { newName -> tripsViewModel.renameTrip(trip.id, newName) },
                    )
                }
                if (trip.forecast.isNotEmpty()) {
                    item { ForecastStrip(forecast = trip.forecast) }
                }
                if (trip.reason.isNotBlank()) {
                    item {
                        Text(
                            trip.reason,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(horizontal = 16.dp),
                        )
                    }
                }

                item { HorizontalDivider() }

                itemsIndexed(trip.outfitIds, key = { _, id -> id }) { dayIdx, outfitId ->
                    val outfit = outfitsById[outfitId]
                    TripDayCard(
                        dayIndex = dayIdx,
                        outfit = outfit,
                        forecast = trip.forecast.getOrNull(dayIdx),
                        imagesById = imagesById,
                        enabled = !isOffline,
                        onClick = {
                            if (outfit != null) {
                                outfitsViewModel.startEditing(
                                    style       = outfit,
                                    images      = wardrobeState.images,
                                    prefs       = profileState.preferences,
                                    tripContext = TripContext(
                                        tripId         = trip.id,
                                        tripName       = trip.name,
                                        dayIndex       = dayIdx,
                                        dayForecast    = trip.forecast.getOrNull(dayIdx),
                                        tripStartDate  = trip.startDate,
                                        considerations = trip.considerations,
                                        vibes          = trip.vibes,
                                        goal           = trip.goal,
                                    ),
                                )
                            }
                        },
                    )
                }

                if (trip.extraItems.isNotEmpty()) {
                    item {
                        Spacer(Modifier.height(8.dp))
                        ExtrasCard(items = trip.extraItems)
                    }
                }

                if (!isOffline) {
                    item {
                        Spacer(Modifier.height(8.dp))
                        HorizontalDivider()
                        BulkRefineSection(
                            isRefining = isBulkRefining,
                            onSubmit = { instruction ->
                                tripsViewModel.refineAllOutfits(
                                    tripId          = trip.id,
                                    instruction     = instruction,
                                    images          = wardrobeState.images,
                                    currentOutfits  = outfitsState.outfits,
                                    outfitsViewModel = outfitsViewModel,
                                )
                            },
                        )
                    }
                }
            }

            if (isBulkRefining) {
                AiProcessingOverlay(
                    label = stringResource(R.string.trip_bulk_refining),
                    modifier = Modifier.fillMaxSize(),
                )
            }
        }
    }

    if (showDeleteDialog) {
        DeleteTripDialog(
            onDismiss = { showDeleteDialog = false },
            onCascade = {
                showDeleteDialog = false
                tripsViewModel.deleteTrip(trip.id) { outfitIds ->
                    outfitsViewModel.deleteOutfitsByIds(outfitIds)
                }
                onClose()
            },
            onKeep = {
                showDeleteDialog = false
                tripsViewModel.deleteTrip(trip.id) { /* keep outfits */ }
                onClose()
            },
        )
    }
}

@Composable
private fun TripHeader(
    name: String,
    onClose: () -> Unit,
    onDelete: () -> Unit,
    deleteEnabled: Boolean,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(start = 8.dp, end = 8.dp, top = 4.dp, bottom = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        IconButton(onClick = onClose, modifier = Modifier.size(40.dp)) {
            Icon(Icons.Default.Close, contentDescription = stringResource(R.string.action_cancel))
        }
        Spacer(Modifier.width(4.dp))
        Column(modifier = Modifier.weight(1f)) {
            Text(
                if (name.isBlank()) stringResource(R.string.trip_viewer_title) else name,
                style = MaterialTheme.typography.headlineSmall.copy(
                    fontSize = 28.sp,
                    fontWeight = FontWeight.Bold,
                ),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
        if (deleteEnabled) {
            IconButton(onClick = onDelete) {
                Icon(Icons.Default.Delete, contentDescription = stringResource(R.string.trip_delete))
            }
        }
    }
    HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun TripMetaSection(
    trip: Trip,
    onRename: (String) -> Unit,
) {
    var editing by remember(trip.id) { mutableStateOf(false) }
    var draftName by remember(trip.id) { mutableStateOf(trip.name) }
    val keyboardController = LocalSoftwareKeyboardController.current

    Column(
        modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
        verticalArrangement = Arrangement.spacedBy(2.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            if (editing) {
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
                    editing = false
                    keyboardController?.hide()
                }) {
                    Icon(Icons.Default.Check, contentDescription = stringResource(R.string.action_ok))
                }
            } else {
                Text(
                    trip.destination.ifBlank { stringResource(R.string.trip_no_destination) },
                    style = MaterialTheme.typography.titleMedium,
                    modifier = Modifier.weight(1f),
                )
                IconButton(onClick = { editing = true; draftName = trip.name }) {
                    Icon(Icons.Default.Edit, contentDescription = stringResource(R.string.trip_rename))
                }
            }
        }
        Text(
            stringResource(
                R.string.trip_meta_dates,
                trip.startDate.ifBlank { "?" },
                trip.days,
            ),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
private fun ForecastStrip(forecast: List<DayForecast>) {
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
private fun TripDayCard(
    dayIndex: Int,
    outfit: Outfit?,
    forecast: DayForecast?,
    imagesById: Map<String, DriveImage>,
    enabled: Boolean,
    onClick: () -> Unit,
) {
    val ctx = LocalContext.current
    val items = outfit?.itemIds?.mapNotNull { imagesById[it] } ?: emptyList()
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
                Spacer(Modifier.weight(1f))
                if (outfit != null && enabled) {
                    Icon(
                        Icons.Default.Edit,
                        contentDescription = stringResource(R.string.trip_modify_outfit),
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.size(16.dp),
                    )
                }
            }
            if (outfit?.name?.isNotBlank() == true) {
                Text(outfit.name, style = MaterialTheme.typography.titleSmall)
            }
            if (items.isNotEmpty()) {
                LazyRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    items(items, key = { it.driveId }) { image ->
                        AsyncImage(
                            model = remember(image.driveId, image.version) {
                                ImageRequest.Builder(ctx)
                                    .data(image.localPath)
                                    .memoryCacheKey("${image.driveId}_${image.version}")
                                    .build()
                            },
                            contentDescription = image.name,
                            modifier = Modifier
                                .size(64.dp)
                                .clip(MaterialTheme.shapes.small),
                            contentScale = ContentScale.Crop,
                        )
                    }
                }
            } else {
                Text(
                    stringResource(R.string.outfits_missing_items),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.outline,
                )
            }
            if (outfit?.description?.isNotBlank() == true) {
                Text(
                    outfit.description,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun ExtrasCard(items: List<String>) {
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
            FlowRow(
                horizontalArrangement = Arrangement.spacedBy(6.dp),
                verticalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                items.forEach { item ->
                    AssistChip(onClick = {}, label = { Text(item) })
                }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
private fun BulkRefineSection(
    isRefining: Boolean,
    onSubmit: (String) -> Unit,
) {
    var text by remember { mutableStateOf("") }
    val presets = listOf(
        stringResource(R.string.trip_bulk_refine_brighter),
        stringResource(R.string.trip_bulk_refine_lighter),
        stringResource(R.string.trip_bulk_refine_formal),
        stringResource(R.string.trip_bulk_refine_skip_jacket),
    )
    Column(
        modifier = Modifier.padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Text(
            stringResource(R.string.trip_bulk_refine_action),
            style = MaterialTheme.typography.titleSmall,
            fontWeight = FontWeight.SemiBold,
        )
        FlowRow(
            horizontalArrangement = Arrangement.spacedBy(6.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            presets.forEach { preset ->
                AssistChip(
                    onClick = { if (!isRefining) onSubmit(preset) },
                    label = { Text(preset, style = MaterialTheme.typography.labelSmall) },
                )
            }
        }
        OutlinedTextField(
            value = text,
            onValueChange = { text = it },
            placeholder = { Text(stringResource(R.string.trip_bulk_refine_hint)) },
            singleLine = false,
            maxLines = 3,
            modifier = Modifier.fillMaxWidth(),
            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
            enabled = !isRefining,
        )
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.End,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            com.librelookai.billing.CostBadge(com.librelookai.gemini.GeminiActionId.GENERATE_TEXT)
            Spacer(Modifier.width(8.dp))
            TripGradientButton(
                label = stringResource(R.string.trip_bulk_refine_run),
                enabled = !isRefining && text.isNotBlank(),
                onClick = {
                    val v = text.trim()
                    if (v.isNotEmpty()) {
                        onSubmit(v)
                        text = ""
                    }
                },
            )
        }
    }
}

@Composable
private fun TripGradientButton(
    label: String,
    enabled: Boolean,
    onClick: () -> Unit,
) {
    val palette = com.librelookai.ui.theme.LocalWardrobePalette.current
    val alpha = if (enabled) 1f else 0.4f
    Box(
        modifier = Modifier
            .height(40.dp)
            .clip(RoundedCornerShape(20.dp))
            .background(
                Brush.linearGradient(
                    listOf(
                        palette.primary.copy(alpha = alpha),
                        palette.primary.copy(alpha = alpha * 0.85f),
                    ),
                ),
            )
            .border(1.dp, palette.primary.copy(alpha = alpha * 0.5f), RoundedCornerShape(20.dp))
            .clickable(enabled = enabled, onClick = onClick)
            .padding(horizontal = 16.dp),
        contentAlignment = Alignment.Center,
    ) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            Icon(Icons.Default.AutoAwesome, contentDescription = null, tint = palette.onPrimary, modifier = Modifier.size(14.dp))
            Text(label, fontSize = 13.sp, fontWeight = FontWeight.Bold, color = palette.onPrimary)
        }
    }
}

@Composable
private fun DeleteTripDialog(
    onDismiss: () -> Unit,
    onCascade: () -> Unit,
    onKeep: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.trip_delete_confirm_title)) },
        text = { Text(stringResource(R.string.trip_delete_confirm_body)) },
        confirmButton = {
            TextButton(onClick = onCascade) {
                Text(stringResource(R.string.trip_delete_with_outfits))
            }
        },
        dismissButton = {
            Row {
                TextButton(onClick = onKeep) {
                    Text(stringResource(R.string.trip_delete_keep_outfits))
                }
                TextButton(onClick = onDismiss) {
                    Text(stringResource(R.string.action_cancel))
                }
            }
        },
    )
}
