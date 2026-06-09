package com.librelookai.travel

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Checklist
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.librelookai.R
import com.librelookai.data.model.Outfit
import com.librelookai.data.model.Trip
import com.librelookai.outfit.OutfitsViewModel
import com.librelookai.outfit.TripContext
import com.librelookai.outfit.applyTagSuggestions
import com.librelookai.outfit.closeOutfitTagsEditor
import com.librelookai.outfit.dismissTagSuggestions
import com.librelookai.outfit.openOutfitTagsEditor
import com.librelookai.outfit.setOutfitTags
import com.librelookai.outfit.suggestTagsForOutfit
import com.librelookai.settings.ProfileViewModel
import com.librelookai.util.AiProcessingOverlay
import com.librelookai.util.LocalIsOffline
import com.librelookai.wardrobe.WardrobeViewModel

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
    canTryOn: Boolean = false,
    onTryOnOutfit: (Trip, Outfit) -> Unit = { _, _ -> },
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

    // Render trip items from the cross-closet snapshot (every closet) UNION the active closet's
    // in-memory `images`. The snapshot is the base — resolving against `images` alone would show
    // empty day cards for items in another closet while a specific closet is selected (MainActivity
    // trims `images` via `setLocation`). But `allLocationImages` only refreshes on closet-config
    // changes / prefetch, so overlay `images` last so just-uploaded items resolve and their fresher
    // copies win on duplicate driveIds. (Mirrors the OutfitComposer/OutfitsScreen item-pool union.)
    val imagesById = remember(wardrobeState.allLocationImages, wardrobeState.images) {
        (wardrobeState.allLocationImages + wardrobeState.images).associateBy { it.driveId }
    }
    val outfitsById = remember(outfitsState.outfits) {
        outfitsState.outfits.associateBy { it.id }
    }
    val tripOutfits = remember(trip.outfitIds, outfitsById) {
        trip.outfitIds.mapNotNull { outfitsById[it] }
    }

    var showDeleteDialog by remember { mutableStateOf(false) }
    var viewerOutfitId by remember { mutableStateOf<String?>(null) }
    // Non-null while picking which day to log a wear on (the viewer's Wear action) for a trip outfit.
    var wearPickerOutfit by remember { mutableStateOf<com.librelookai.data.model.Outfit?>(null) }
    var editing by remember(tripId) { mutableStateOf(false) }
    // View-mode actions are hidden under the bottom FAB (tap to reveal the shared action bar).
    var actionsOpen by remember(tripId) { mutableStateOf(false) }
    // The list's single-select "Edit" action opens the viewer straight into edit mode.
    androidx.compose.runtime.LaunchedEffect(tripId, tripsState.pendingEditTripId) {
        if (tripsState.pendingEditTripId == tripId) {
            editing = true
            tripsViewModel.consumePendingEdit()
        }
    }
    // View-mode packing checklist: off by default, toggled from the meta row. When on, day-card
    // and extras items show interactive packing ticks.
    var checklistMode by remember(tripId) { mutableStateOf(false) }
    val isBulkRefining = tripId in bulkRefining

    // Closets the bulk-refine may draw items from. Empty = all closets. Resets per trip.
    var refineSourceFolders by remember(tripId) { mutableStateOf(emptySet<String>()) }
    var showRefineClosetSheet by remember { mutableStateOf(false) }
    val refineClosetNames = remember(refineSourceFolders, locationState.locations) {
        locationState.locations.filter { it.folderId in refineSourceFolders }.map { it.name }
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

    // Open the composer to edit a specific day's outfit, carrying this trip's context. Shared by
    // the fullscreen viewer's edit action and the per-day edit pen.
    val startEditingOutfit: (Outfit) -> Unit = { o ->
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
                            // Checklist toggle only in view mode (not while editing/previewing).
                            checklistActive = checklistMode,
                            onToggleChecklist = if (!editing && !previewing) {
                                { checklistMode = !checklistMode }
                            } else null,
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
                        val preview = refinePreview[outfitId]
                        TripDayCard(
                            dayIndex = dayIdx,
                            outfit = outfit,
                            forecast = trip.forecast.getOrNull(dayIdx),
                            imagesById = imagesById,
                            overrideItemIds = preview?.itemIds,
                            overrideName = preview?.name,
                            overrideDescription = preview?.description,
                            showEditIcon = editing && !previewing,
                            // While previewing, cards show proposed items; opening the full
                            // viewer (which reads saved items) is disabled to avoid confusion.
                            enabled = outfit != null && !previewing,
                            onClick = { if (outfit != null) viewerOutfitId = outfit.id },
                            // Edit pen jumps straight into the composer for this day's outfit.
                            onEdit = if (outfit != null && editing && !previewing) {
                                { startEditingOutfit(outfit) }
                            } else null,
                            // View mode: log a calendar wear for this day's outfit (write path).
                            onWear = if (outfit != null && !editing && !previewing && !isOffline) {
                                { outfitEventsViewModel.recordOutfit(outfit, imagesById) }
                            } else null,
                            // Checklist mode: tick items off as packed (and dim the packed ones).
                            packedItemIds = if (checklistMode) trip.packedItemIds else emptySet(),
                            onTogglePacked = if (checklistMode && !editing && !previewing) {
                                { id -> tripsViewModel.toggleTripPackedItem(trip.id, id) }
                            } else null,
                        )
                    }

                    if (trip.extraItems.isNotEmpty() || (editing && !previewing)) {
                        item {
                            Spacer(Modifier.height(8.dp))
                            ExtrasCard(
                                items = trip.extraItems,
                                editable = editing && !previewing,
                                checklist = checklistMode,
                                packedExtras = trip.packedExtras,
                                onTogglePacked = { extra -> tripsViewModel.toggleTripPackedExtra(trip.id, extra) },
                                onUpdate = { next -> tripsViewModel.updateTripExtras(trip.id, next) },
                            )
                        }
                    }

                    if (editing && !isOffline) {
                        item {
                            Spacer(Modifier.height(8.dp))
                            HorizontalDivider()
                            val refineImages = if (refineSourceFolders.isEmpty()) wardrobeState.images
                                else wardrobeState.images.filter { it.folderId in refineSourceFolders }
                            // Key on the whole `trip` (not trip.id) so toggling its vibe/consideration
                            // pills — which change buildBulkRefinePrompt's payload — refreshes the badge.
                            val refineTokens by androidx.compose.runtime.produceState<com.librelookai.gemini.CostTokens?>(
                                initialValue = null,
                                trip,
                                refineImages,
                                outfitsState.outfits,
                                profileState.preferences,
                            ) {
                                value = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.Default) {
                                    tripsViewModel.estimateBulkRefineTokens(
                                        tripId = trip.id,
                                        instruction = "",
                                        images = refineImages,
                                        currentOutfits = outfitsState.outfits,
                                        prefs = profileState.preferences,
                                    )
                                }
                            }
                            BulkRefineSection(
                                vibes = trip.vibes,
                                onToggleVibe = { tripsViewModel.toggleTripVibe(trip.id, it) },
                                considerations = trip.considerations,
                                onToggleConsideration = { transform ->
                                    tripsViewModel.setTripConsiderations(trip.id, transform(trip.considerations))
                                },
                                isRefining = isBulkRefining,
                                closetNames = if (locationState.locations.size >= 2) refineClosetNames else null,
                                onPickClosets = { showRefineClosetSheet = true },
                                tokens = refineTokens,
                                onSubmit = { instruction ->
                                    tripsViewModel.refineAllOutfits(
                                        tripId          = trip.id,
                                        instruction     = instruction,
                                        images          = refineImages,
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

        // View-only actions hidden under the bottom-end FAB — tap to reveal the shared action bar
        // (Edit primary · Delete danger). Hidden offline (writes) and while editing.
        if (!editing && !isOffline) {
            if (actionsOpen) androidx.activity.compose.BackHandler { actionsOpen = false }
            val palette = com.librelookai.ui.theme.LocalWardrobePalette.current
            val barHeight = 81.dp // DetailActionBar chrome + its 14.dp bottom padding (no inset here)
            com.librelookai.ui.components.DetailActionBar(
                actions = buildList {
                    add(
                        com.librelookai.ui.components.SelectionAction(
                            label = stringResource(R.string.action_edit),
                            icon = Icons.Default.Edit,
                            kind = com.librelookai.ui.components.SelectionAction.Kind.Primary,
                        ) { actionsOpen = false; editing = true },
                    )
                    add(
                        com.librelookai.ui.components.SelectionAction(
                            label = stringResource(R.string.trip_delete),
                            icon = Icons.Default.Delete,
                            kind = com.librelookai.ui.components.SelectionAction.Kind.Danger,
                        ) { actionsOpen = false; showDeleteDialog = true },
                    )
                },
                visible = actionsOpen,
                modifier = Modifier.align(Alignment.BottomCenter),
            )
            androidx.compose.material3.ExtendedFloatingActionButton(
                onClick = { actionsOpen = !actionsOpen },
                expanded = true,
                containerColor = palette.fabBg,
                contentColor = palette.fabFg,
                icon = {
                    Icon(
                        if (actionsOpen) Icons.Default.Close else Icons.Default.Edit,
                        contentDescription = null,
                        modifier = Modifier.size(22.dp),
                    )
                },
                text = { Text(stringResource(if (actionsOpen) R.string.action_close else R.string.action_edit)) },
                modifier = Modifier
                    .align(Alignment.BottomEnd)
                    .padding(end = 16.dp, bottom = if (actionsOpen) barHeight + 8.dp else 16.dp),
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
                onEdit = startEditingOutfit,
                onWear = { o -> wearPickerOutfit = o },
                onToggleLoved = { o -> outfitsViewModel.setOutfitLoved(o.id, !o.loved) },
                onDelete = { o ->
                    outfitsViewModel.deleteOutfit(o.id)
                    if (tripOutfits.size <= 1) viewerOutfitId = null
                },
                onSuggestTags = { o ->
                    outfitsViewModel.suggestTagsForOutfit(o, wardrobeState.images, profileState.preferences)
                },
                onEditTags = { o -> outfitsViewModel.openOutfitTagsEditor(o.id) },
                // Try-on the viewed trip outfit — gated by a saved model photo and online state.
                onTryOn = { o -> onTryOnOutfit(trip, o) },
                canTryOn = canTryOn && !isOffline,
                wardrobeViewModel = wardrobeViewModel,
            )
        } else {
            androidx.compose.runtime.LaunchedEffect(vid) { viewerOutfitId = null }
        }
    }

    // Wear-day picker for the trip outfit viewer's Wear action (logs onto the chosen day).
    wearPickerOutfit?.let { o ->
        com.librelookai.outfit.WearDatePickerDialog(
            onDismiss = { wearPickerOutfit = null },
            onConfirm = { date ->
                outfitEventsViewModel.recordOutfit(o, imagesById, date = date)
                wearPickerOutfit = null
            },
        )
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

    if (showRefineClosetSheet) {
        com.librelookai.outfit.ClosetPickerSheet(
            locations = locationState.locations,
            selected = refineSourceFolders,
            onToggle = { fid ->
                refineSourceFolders = refineSourceFolders.toMutableSet()
                    .also { if (!it.add(fid)) it.remove(fid) }
            },
            onDismiss = { showRefineClosetSheet = false },
        )
    }
}

