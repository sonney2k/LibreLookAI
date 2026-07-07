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
import com.librelookai.feature.travel.R
import com.librelookai.core.designsystem.R as DsR
import com.librelookai.data.model.Location
import com.librelookai.data.model.Outfit
import com.librelookai.data.model.OutfitRefinement
import com.librelookai.data.model.Trip
import com.librelookai.settings.UserPreferences
import com.librelookai.util.AiProcessingOverlay
import com.librelookai.util.LocalIsOffline
import com.librelookai.wardrobe.DriveImage

@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
fun TripViewerScreen(
    tripId: String,
    /** Open straight in edit mode (`TripViewerRoute.startInEdit` — the list's "Edit" action). */
    startInEdit: Boolean = false,
    /** Show the one-time "saved" confirmation (`TripViewerRoute.justSaved` — planner-created trip). */
    justSaved: Boolean = false,
    tripsViewModel: TripsViewModel,
    outfits: List<Outfit>,
    wardrobeImages: List<DriveImage>,
    allLocationImages: List<DriveImage>,
    preferences: UserPreferences,
    locations: List<Location>,
    /** Seeds the composer with a day's outfit + trip context and navigates to it (§ 1 slice 6). */
    onEditTripOutfit: (Trip, Outfit) -> Unit,
    /** Outfits-side delete funnel for a cascade trip delete. */
    onDeleteOutfits: (List<String>) -> Unit,
    /** Outfits-side persist funnel for applying a bulk-refine preview (§ 1 slice 6). */
    onUpdateOutfitsRefined: (Map<String, OutfitRefinement>, (Boolean) -> Unit) -> Unit,
    /** Logs a calendar wear for a day's outfit (the events VM's recordOutfit). */
    onRecordWear: (Outfit, Map<String, DriveImage>) -> Unit,
    onClose: () -> Unit,
    canTryOn: Boolean = false,
    onTryOnOutfit: (Trip, Outfit) -> Unit = { _, _ -> },
    /** Opens the fullscreen outfit viewer destination over this trip's day outfits. */
    onOpenOutfitViewer: (Outfit) -> Unit = {},
    modifier: Modifier = Modifier,
) {
    val isOffline = LocalIsOffline.current
    val tripsState by tripsViewModel.state.collectAsState()
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
    val imagesById = remember(allLocationImages, wardrobeImages) {
        (allLocationImages + wardrobeImages).associateBy { it.driveId }
    }
    val outfitsById = remember(outfits) {
        outfits.associateBy { it.id }
    }
    val tripOutfits = remember(trip.outfitIds, outfitsById) {
        trip.outfitIds.mapNotNull { outfitsById[it] }
    }

    var showDeleteDialog by remember { mutableStateOf(false) }
    // The list's single-select "Edit" action opens the viewer straight into edit mode
    // (a route argument since § 5 slice 9 — the trips VM is destination-scoped).
    var editing by remember(tripId) { mutableStateOf(startInEdit) }
    // View-mode actions are hidden under the bottom FAB (tap to reveal the shared action bar).
    var actionsOpen by remember(tripId) { mutableStateOf(false) }
    // View-mode packing checklist: off by default, toggled from the meta row. When on, day-card
    // and extras items show interactive packing ticks.
    var checklistMode by remember(tripId) { mutableStateOf(false) }
    val isBulkRefining = tripId in bulkRefining

    // Closets the bulk-refine may draw items from. Empty = all closets. Resets per trip.
    var refineSourceFolders by remember(tripId) { mutableStateOf(emptySet<String>()) }
    var showRefineClosetSheet by remember { mutableStateOf(false) }
    val refineClosetNames = remember(refineSourceFolders, locations) {
        locations.filter { it.folderId in refineSourceFolders }.map { it.name }
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

    // Open the composer to edit a specific day's outfit, carrying this trip's context (the
    // shell seeds the generation VM and navigates — § 1 slice 6, no outfit-VM type here).
    val startEditingOutfit: (Outfit) -> Unit = { o -> onEditTripOutfit(trip, o) }

    // One-time "saved" confirmation shown when a freshly generated trip opens (a route argument
    // since § 5 slice 9; the rememberSaveable consume keeps it one-time across recreation).
    val snackbarHostState = remember { SnackbarHostState() }
    val savedMessage = stringResource(R.string.trip_saved_confirmation)
    var pendingSavedConfirmation by androidx.compose.runtime.saveable.rememberSaveable(tripId) {
        androidx.compose.runtime.mutableStateOf(justSaved)
    }
    androidx.compose.runtime.LaunchedEffect(pendingSavedConfirmation) {
        if (pendingSavedConfirmation) {
            pendingSavedConfirmation = false
            snackbarHostState.showSnackbar(savedMessage)
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
                            onClick = { if (outfit != null) onOpenOutfitViewer(outfit) },
                            // Edit pen jumps straight into the composer for this day's outfit.
                            onEdit = if (outfit != null && editing && !previewing) {
                                { startEditingOutfit(outfit) }
                            } else null,
                            // View mode: log a calendar wear for this day's outfit (write path).
                            onWear = if (outfit != null && !editing && !previewing && !isOffline) {
                                { onRecordWear(outfit, imagesById) }
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
                            val refineImages = if (refineSourceFolders.isEmpty()) wardrobeImages
                                else wardrobeImages.filter { it.folderId in refineSourceFolders }
                            // Key on the whole `trip` (not trip.id) so toggling its vibe/consideration
                            // pills — which change buildBulkRefinePrompt's payload — refreshes the badge.
                            val refineTokens by androidx.compose.runtime.produceState<com.librelookai.gemini.CostTokens?>(
                                initialValue = null,
                                trip,
                                refineImages,
                                outfits,
                                preferences,
                            ) {
                                value = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.Default) {
                                    tripsViewModel.estimateBulkRefineTokens(
                                        tripId = trip.id,
                                        instruction = "",
                                        images = refineImages,
                                        currentOutfits = outfits,
                                        prefs = preferences,
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
                                closetNames = if (locations.size >= 2) refineClosetNames else null,
                                onPickClosets = { showRefineClosetSheet = true },
                                tokens = refineTokens,
                                onSubmit = { instruction ->
                                    tripsViewModel.refineAllOutfits(
                                        tripId          = trip.id,
                                        instruction     = instruction,
                                        images          = refineImages,
                                        currentOutfits  = outfits,
                                        prefs           = preferences,
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
                    onReplace = { tripsViewModel.applyRefinePreview(onUpdateOutfitsRefined) },
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
                            label = stringResource(DsR.string.action_edit),
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
                text = { Text(stringResource(if (actionsOpen) DsR.string.action_close else DsR.string.action_edit)) },
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

    if (showDeleteDialog) {
        DeleteTripDialog(
            onDismiss = { showDeleteDialog = false },
            onCascade = {
                showDeleteDialog = false
                tripsViewModel.deleteTrip(trip.id) { outfitIds ->
                    onDeleteOutfits(outfitIds)
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
            locations = locations,
            selected = refineSourceFolders,
            onToggle = { fid ->
                refineSourceFolders = refineSourceFolders.toMutableSet()
                    .also { if (!it.add(fid)) it.remove(fid) }
            },
            onDismiss = { showRefineClosetSheet = false },
        )
    }
}

