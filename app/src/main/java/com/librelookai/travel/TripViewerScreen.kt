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
import androidx.compose.material.icons.filled.CloudDone
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.AssistChip
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedCard
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
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
    locationViewModel: com.librelookai.wardrobe.LocationViewModel = androidx.lifecycle.viewmodel.compose.viewModel(),
    outfitEventsViewModel: com.librelookai.outfit.OutfitEventsViewModel = androidx.lifecycle.viewmodel.compose.viewModel(),
    onClose: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val isOffline = LocalIsOffline.current
    val tripsState by tripsViewModel.state.collectAsState()
    val outfitsState by outfitsViewModel.state.collectAsState()
    val wardrobeState by wardrobeViewModel.state.collectAsState()
    val profileState by profileViewModel.state.collectAsState()
    val locationState by locationViewModel.state.collectAsState()
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
    var viewerOutfitId by remember { mutableStateOf<String?>(null) }
    var editing by remember(tripId) { mutableStateOf(false) }
    val isBulkRefining = tripId in bulkRefining

    // Local draft for the trip goal, persisted (debounced) so typing doesn't spam Drive writes.
    var goalDraft by remember(tripId) { mutableStateOf(trip.goal) }
    androidx.compose.runtime.LaunchedEffect(goalDraft) {
        if (goalDraft != trip.goal) {
            kotlinx.coroutines.delay(600)
            tripsViewModel.setTripGoal(tripId, goalDraft)
        }
    }

    // Pending (un-persisted) bulk-refine result for this trip, if any.
    val refinePreview = if (tripsState.refinePreviewTripId == tripId) tripsState.refinePreview else emptyMap()
    val previewing = refinePreview.isNotEmpty()

    // Leaving edit mode also drops any unsaved refine preview.
    val exitEdit = {
        editing = false
        tripsViewModel.discardRefinePreview()
    }

    // System back exits edit mode first; the parent handles closing the viewer otherwise.
    if (editing) {
        androidx.activity.compose.BackHandler { exitEdit() }
    }

    // One-time "saved" confirmation shown when a freshly generated trip opens.
    val snackbarHostState = remember { SnackbarHostState() }
    val savedMessage = stringResource(R.string.trip_saved_confirmation)
    androidx.compose.runtime.LaunchedEffect(tripsState.justSavedTripId) {
        if (tripsState.justSavedTripId == tripId) {
            snackbarHostState.showSnackbar(savedMessage)
            tripsViewModel.consumeJustSaved()
        }
    }

    Box(modifier = modifier.fillMaxSize()) {
        Column(modifier = Modifier.fillMaxSize()) {
            if (editing) {
                // Edit mode: generic title, no delete here (delete lives on the view-mode FAB).
                TripHeader(
                    name = stringResource(R.string.trip_planned_title),
                    onClose = exitEdit,
                    onDelete = {},
                    deleteEnabled = false,
                )
            } else {
                // View mode: trip title on the left, "saved" reassurance on the right.
                TripViewHeader(title = trip.name, onClose = onClose)
            }
            Box(modifier = Modifier.weight(1f).fillMaxWidth()) {
                LazyColumn(
                    contentPadding = PaddingValues(bottom = if (editing) 16.dp else 96.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    item {
                        TripMetaSection(
                            trip = trip,
                            editable = editing,
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
                            overrideItemIds = refinePreview[outfitId],
                            showEditIcon = editing && !previewing,
                            // While previewing, cards show proposed items; opening the full
                            // viewer (which reads saved items) is disabled to avoid confusion.
                            enabled = outfit != null && !previewing,
                            onClick = { if (outfit != null) viewerOutfitId = outfit.id },
                        )
                    }

                    if (trip.extraItems.isNotEmpty()) {
                        item {
                            Spacer(Modifier.height(8.dp))
                            ExtrasCard(items = trip.extraItems)
                        }
                    }

                    if (editing) {
                        // Planner options — same controls used when creating a trip. Days &
                        // weather are intentionally kept; only these inputs drive re-styling.
                        item {
                            Spacer(Modifier.height(8.dp))
                            HorizontalDivider()
                            Column(
                                modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
                                verticalArrangement = Arrangement.spacedBy(8.dp),
                            ) {
                                com.librelookai.travel.GoalAiCard(
                                    goal = goalDraft,
                                    onUpdateGoal = { goalDraft = it },
                                )
                                com.librelookai.travel.VibeChips(
                                    selected = trip.vibes,
                                    onToggle = { tripsViewModel.toggleTripVibe(trip.id, it) },
                                )
                                com.librelookai.travel.AiConsidersChips(
                                    considerations = trip.considerations,
                                    onToggle = { transform ->
                                        tripsViewModel.setTripConsiderations(trip.id, transform(trip.considerations))
                                    },
                                )
                            }
                        }
                    }

                    if (editing && !isOffline) {
                        item {
                            Spacer(Modifier.height(8.dp))
                            HorizontalDivider()
                            BulkRefineSection(
                                isRefining = isBulkRefining,
                                onSubmit = { instruction ->
                                    // Flush the goal draft first so the refine prompt sees it.
                                    tripsViewModel.setTripGoal(trip.id, goalDraft)
                                    tripsViewModel.refineAllOutfits(
                                        tripId          = trip.id,
                                        instruction     = instruction,
                                        images          = wardrobeState.images,
                                        currentOutfits  = outfitsState.outfits,
                                        prefs           = profileState.preferences,
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
            // Sticky review bar for an unsaved refine preview.
            if (previewing) {
                RefinePreviewBar(
                    onDiscard = { tripsViewModel.discardRefinePreview() },
                    onReplace = { tripsViewModel.applyRefinePreview(outfitsViewModel) },
                )
            }
        }

        // View-only edit FAB (speed dial: Edit / Delete) — hidden offline (write paths only).
        if (!editing && !isOffline) {
            TripActionsFab(
                onEdit = { editing = true },
                onDelete = { showDeleteDialog = true },
                modifier = Modifier.align(Alignment.BottomEnd).padding(16.dp),
            )
        }

        SnackbarHost(
            hostState = snackbarHostState,
            modifier = Modifier.align(Alignment.BottomCenter).padding(16.dp),
        )
    }

    // Fullscreen outfit viewer — swipe across this trip's outfits. Mirrors the Outfits screen
    // viewer; editing routes back into the composer with this trip's context preserved.
    viewerOutfitId?.let { vid ->
        val startIndex = tripOutfits.indexOfFirst { it.id == vid }
        if (startIndex >= 0) {
            com.librelookai.outfit.OutfitFullScreenViewer(
                outfits = tripOutfits,
                initialIndex = startIndex,
                itemsById = imagesById,
                locations = locationState.locations,
                activeLocationId = locationState.activeLocationId,
                onDismiss = { viewerOutfitId = null },
                onEdit = { o ->
                    viewerOutfitId = null
                    val dayIdx = trip.outfitIds.indexOf(o.id).coerceAtLeast(0)
                    outfitsViewModel.startEditing(
                        style       = o,
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
                },
                onWear = { o -> outfitEventsViewModel.recordOutfit(o.id) },
                onDelete = { o ->
                    outfitsViewModel.deleteOutfit(o.id)
                    if (tripOutfits.size <= 1) viewerOutfitId = null
                },
                onSuggestTags = { o ->
                    outfitsViewModel.suggestTagsForOutfit(o, wardrobeState.images, profileState.preferences)
                },
                onEditTags = { o -> outfitsViewModel.openOutfitTagsEditor(o.id) },
                canTryOn = false,
                wardrobeViewModel = wardrobeViewModel,
            )
        } else {
            androidx.compose.runtime.LaunchedEffect(vid) { viewerOutfitId = null }
        }
    }

    // Tag-edit dialog launched by tapping the tags row in the outfit viewer.
    outfitsState.tagEditingOutfitId?.let { editId ->
        outfitsState.outfits.find { it.id == editId }?.let { target ->
            com.librelookai.outfit.EditOutfitTagsDialog(
                initialTags = target.tags,
                onDismiss = outfitsViewModel::closeOutfitTagsEditor,
                onSave = { newTags -> outfitsViewModel.setOutfitTags(editId, newTags) },
            )
        }
    }

    // AI tag-suggestion dialog launched from the outfit viewer.
    outfitsState.tagSuggestion?.let { sugg ->
        com.librelookai.outfit.SuggestTagsDialog(
            state = sugg,
            onDismiss = outfitsViewModel::dismissTagSuggestions,
            onApply = { selected -> outfitsViewModel.applyTagSuggestions(sugg.outfitId, selected) },
        )
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

/** View-mode top panel: close + trip title, with a "saved" reassurance pinned to the right. */
@Composable
private fun TripViewHeader(
    title: String,
    onClose: () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(start = 8.dp, end = 12.dp, top = 4.dp, bottom = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        IconButton(onClick = onClose, modifier = Modifier.size(40.dp)) {
            Icon(Icons.Default.Close, contentDescription = stringResource(R.string.action_cancel))
        }
        Spacer(Modifier.width(4.dp))
        Text(
            if (title.isBlank()) stringResource(R.string.trip_viewer_title) else title,
            style = MaterialTheme.typography.headlineSmall.copy(
                fontSize = 24.sp,
                fontWeight = FontWeight.Bold,
            ),
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f),
        )
        Spacer(Modifier.width(8.dp))
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            Icon(
                Icons.Default.CloudDone,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.primary,
                modifier = Modifier.size(14.dp),
            )
            Text(
                stringResource(R.string.trip_saved_label),
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.primary,
            )
        }
    }
    HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
}

/** Sticky review bar for an unsaved bulk-refine preview. */
@Composable
private fun RefinePreviewBar(
    onDiscard: () -> Unit,
    onReplace: () -> Unit,
) {
    HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
    Surface(tonalElevation = 3.dp) {
        Column(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 10.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Text(
                stringResource(R.string.trip_refine_preview_note),
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.End,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                TextButton(onClick = onDiscard) {
                    Text(stringResource(R.string.trip_refine_discard))
                }
                Spacer(Modifier.width(8.dp))
                TripGradientButton(
                    label = stringResource(R.string.trip_refine_replace),
                    enabled = true,
                    onClick = onReplace,
                )
            }
        }
    }
}

/** Speed-dial FAB for the view-only trip: expands to Edit / Delete actions. */
@Composable
private fun TripActionsFab(
    onEdit: () -> Unit,
    onDelete: () -> Unit,
    modifier: Modifier = Modifier,
) {
    var expanded by remember { mutableStateOf(false) }
    Column(
        modifier = modifier,
        horizontalAlignment = Alignment.End,
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        if (expanded) {
            ExtendedFloatingActionButton(
                onClick = { expanded = false; onEdit() },
                containerColor = MaterialTheme.colorScheme.primaryContainer,
                contentColor = MaterialTheme.colorScheme.onPrimaryContainer,
                icon = { Icon(Icons.Default.Edit, contentDescription = null) },
                text = { Text(stringResource(R.string.action_edit)) },
            )
            ExtendedFloatingActionButton(
                onClick = { expanded = false; onDelete() },
                containerColor = MaterialTheme.colorScheme.error,
                contentColor = MaterialTheme.colorScheme.onError,
                icon = { Icon(Icons.Default.Delete, contentDescription = null) },
                text = { Text(stringResource(R.string.trip_delete)) },
            )
        }
        FloatingActionButton(onClick = { expanded = !expanded }) {
            Icon(
                if (expanded) Icons.Default.Close else Icons.Default.Edit,
                contentDescription = stringResource(R.string.action_edit),
            )
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun TripMetaSection(
    trip: Trip,
    editable: Boolean,
    onRename: (String) -> Unit,
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
        Row(verticalAlignment = Alignment.CenterVertically) {
            if (editable && renaming) {
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
                    Icon(Icons.Default.Check, contentDescription = stringResource(R.string.action_ok))
                }
            } else {
                Text(
                    trip.destination.ifBlank { stringResource(R.string.trip_no_destination) },
                    style = MaterialTheme.typography.titleMedium,
                    modifier = Modifier.weight(1f),
                )
                if (editable) {
                    IconButton(onClick = { renaming = true; draftName = trip.name }) {
                        Icon(Icons.Default.Edit, contentDescription = stringResource(R.string.trip_rename))
                    }
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
    showEditIcon: Boolean,
    onClick: () -> Unit,
    overrideItemIds: List<String>? = null,
) {
    val ctx = LocalContext.current
    val effectiveItemIds = overrideItemIds ?: outfit?.itemIds ?: emptyList()
    val items = effectiveItemIds.mapNotNull { imagesById[it] }
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
                if (outfit != null && showEditIcon) {
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
        // Presets only fill the instruction field — no action is taken until "Apply with AI".
        FlowRow(
            horizontalArrangement = Arrangement.spacedBy(6.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            presets.forEach { preset ->
                AssistChip(
                    onClick = { text = if (text.isBlank()) preset else "$text, $preset" },
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
                // Instruction is optional — Apply can also just re-style using the edited options.
                enabled = !isRefining,
                onClick = {
                    onSubmit(text.trim())
                    text = ""
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
