package com.librelookai.wardrobe

import android.content.Intent
import android.net.Uri
import android.provider.Settings
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.AutoAwesome
import androidx.compose.material.icons.filled.AutoFixHigh
import androidx.compose.material.icons.filled.BarChart
import androidx.compose.material.icons.filled.Checkroom
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.ImageSearch
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Place
import androidx.compose.material.icons.filled.SwapHoriz
import androidx.compose.material.icons.filled.Tune
import com.librelookai.ui.components.AppFab
import com.librelookai.ui.components.SelectionAction
import com.librelookai.ui.components.SelectionActionBar
import com.librelookai.ui.components.rememberFabExpanded
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Snackbar
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import com.librelookai.AppScreenHeader
import com.librelookai.LocationButton
import com.librelookai.R
import com.librelookai.core.designsystem.R as DsR
import com.librelookai.data.model.Location
import com.librelookai.data.model.Outfit
import com.librelookai.data.model.TryOn
import com.librelookai.gemini.ClothingTags
import com.librelookai.gemini.CutoutFixActions
import com.librelookai.util.Analytics
import com.librelookai.util.FeatureFlags
import com.librelookai.util.LocalIsOffline

@OptIn(ExperimentalMaterial3Api::class, ExperimentalFoundationApi::class)
@Composable
internal fun GridContent(
    state: WardrobeUiState,
    // Progress read straight off the owning singletons' flows (§ 5 slice 7 prune) — the
    // screen collects the VM's passthrough properties and hands the snapshots down.
    ingestion: IngestionProgress = IngestionProgress(),
    retag: BulkAiProgress = BulkAiProgress(),
    convert: ConvertProgress = ConvertProgress(),
    popularityMap: Map<String, Int> = emptyMap(),
    locations: List<Location> = emptyList(),
    activeLocationId: String = "",
    isLocationLoading: Boolean = false,
    locationError: String? = null,
    onOpenCamera: () -> Unit,
    onOpenGallery: () -> Unit,
    onImportUrl: (String) -> Unit = {},
    onDismissError: () -> Unit,
    onToggleSelection: (String) -> Unit,
    onSelectAll: (List<String>) -> Unit,
    onClearSelection: () -> Unit,
    onDeleteItems: (Set<String>) -> Unit,
    outfits: List<Outfit> = emptyList(),
    tryOns: List<TryOn> = emptyList(),
    onDeleteOutfits: (List<String>) -> Unit = {},
    onDeleteTryOns: (List<TryOn>) -> Unit = {},
    onMoveToLocation: (Set<String>, String) -> Unit,
    onSetActiveLocation: (String) -> Unit,
    onCreateOutfitFromSelection: (Set<String>) -> Unit,
    onTryOnSelection: (Set<String>) -> Unit = {},
    onSuggestReplacements: (Set<String>) -> Unit = {},
    canTryOn: Boolean = false,
    onDismissBatteryExemption: () -> Unit = {},
    onSetImportTarget: (String) -> Unit = {},
    processingImageId: String?,
    onOpenItemViewer: (List<String>, String) -> Unit = { _, _ -> },
    onSettingsClick: () -> Unit = {},
    onBulkRemoveBackgrounds: () -> Unit = {},
    onBulkFixCutoutBg: () -> Unit = {},
    onOpenFindByPhoto: () -> Unit = {},
    onSearchByText: (String) -> Unit = {},
    onTextFilter: (String, List<DriveImage>) -> List<DriveImage> = { _, items -> items },
    onDismissFindByPhoto: () -> Unit = {},
    scrollEvents: kotlinx.coroutines.flow.Flow<WardrobeEvent> = kotlinx.coroutines.flow.emptyFlow(),
    onAddMatchToShoppingList: (String) -> Unit = {},
    debugSimilarityPreview: Boolean = false,
    modifier: Modifier = Modifier,
) {
    val isOffline = LocalIsOffline.current
    // Non-null while the delete-confirm dialog is open; holds the item driveIds about to be
    // deleted (the multi-select set; the item-viewer destination hosts its own for singles).
    var pendingDeleteIds by remember { mutableStateOf<Set<String>?>(null) }
    var showMoveDialog by remember { mutableStateOf(false) }
    // Filter + sort state
    var selectedTags by remember { mutableStateOf(emptyMap<String, Set<String>>()) }
    var sortBy by remember { mutableStateOf(SortOption.DATE_DESC) }
    var filterSheetOpen by remember { mutableStateOf(false) }
    var statsSheetOpen by remember { mutableStateOf(false) }
    var textQuery by remember { mutableStateOf("") }
    val appliedFilterCount = selectedTags.values.sumOf { it.size } + (if (textQuery.isNotBlank()) 1 else 0)

    val tagCategories = remember(state.images) { state.images.tagCategories() }

    // OR within each category, AND across categories
    val filteredImages = remember(state.images, selectedTags, textQuery) {
        val activeFilters = selectedTags.filter { (_, tags) -> tags.isNotEmpty() }
        val byTags = if (activeFilters.isEmpty()) state.images
        else state.images.filter { img ->
            activeFilters.all { (categoryLabel, catTags) ->
                catTags.any { it in img.tagStringsForCategory(categoryLabel) }
            }
        }
        if (textQuery.isBlank()) byTags else onTextFilter(textQuery, byTags)
    }

    val displayedImages = remember(filteredImages, sortBy, popularityMap) {
        when (sortBy) {
            SortOption.DATE_DESC  -> filteredImages.sortedByDescending { it.createdTimeMs }
            SortOption.DATE_ASC   -> filteredImages.sortedBy { it.createdTimeMs }
            SortOption.POPULARITY -> filteredImages.sortedByDescending { popularityMap[it.driveId] ?: 0 }
            SortOption.TYPE       -> filteredImages.sortedBy { it.tags?.type?.lowercase() ?: "" }
            SortOption.CATEGORY   -> filteredImages.sortedBy { it.tags?.category?.lowercase() ?: "" }
        }
    }

    // After find-by-photo (or "Show in wardrobe" from Similarity Finder): scroll the grid to
    // the matched item and pulse a highlight ring on it. The local var seeds either from a
    // viewer-driven request (find-by-photo's onPickMatch) or from the VM-owned state.
    val gridState = androidx.compose.foundation.lazy.grid.rememberLazyGridState()
    var pendingScrollDriveId by remember { mutableStateOf<String?>(null) }
    var highlightedDriveId by remember { mutableStateOf<String?>(null) }
    LaunchedEffect(Unit) {
        scrollEvents.collect { event ->
            if (event is WardrobeEvent.ScrollToItem) pendingScrollDriveId = event.driveId
        }
    }
    LaunchedEffect(pendingScrollDriveId, displayedImages) {
        val target = pendingScrollDriveId ?: return@LaunchedEffect
        val idx = displayedImages.indexOfFirst { it.driveId == target }
        if (idx >= 0) {
            runCatching { gridState.animateScrollToItem(idx) }
            highlightedDriveId = target
            pendingScrollDriveId = null
            kotlinx.coroutines.delay(2000)
            if (highlightedDriveId == target) highlightedDriveId = null
        }
    }

    val isSelectionMode = state.selectedIds.isNotEmpty()
    if (isSelectionMode) BackHandler(onBack = onClearSelection)

    Box(modifier = modifier.fillMaxSize()) {
        Column(Modifier.fillMaxSize()) {
            // ---- Screen header with sort button ----
            AppScreenHeader(
                title = stringResource(R.string.nav_wardrobe),
                leadingIcon = Icons.Default.Checkroom,
                trailingContent = {
                    LocationButton(
                        locations = locations,
                        activeLocationId = activeLocationId,
                        onSetActiveLocation = onSetActiveLocation,
                    )
                },
                onSettingsClick = onSettingsClick,
            )
            // ---- Filter + search + sort row ----
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(com.librelookai.ui.theme.LocalWardrobePalette.current.surface),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                QuickCategoryRow(
                    totalCount = state.images.size,
                    filteredCount = filteredImages.size,
                    appliedFilterCount = appliedFilterCount,
                    filtersEnabled = tagCategories.isNotEmpty(),
                    onClearFilters = {
                        selectedTags = emptyMap()
                        textQuery = ""
                    },
                    onOpenFilters = {
                        Analytics.action("Wardrobe", "open_filter_sheet", mapOf("active" to appliedFilterCount.toString()))
                        filterSheetOpen = true
                    },
                    modifier = Modifier.weight(1f),
                )
                IconButton(onClick = {
                    Analytics.action("Wardrobe", "open_find_by_photo")
                    onOpenFindByPhoto()
                }) {
                    Icon(
                        Icons.Default.ImageSearch,
                        contentDescription = stringResource(R.string.wardrobe_search),
                    )
                }
                IconButton(onClick = {
                    Analytics.action("Wardrobe", "open_stats")
                    statsSheetOpen = true
                }) {
                    Icon(
                        Icons.Default.BarChart,
                        contentDescription = stringResource(R.string.insights_tab_wardrobe_stats),
                    )
                }
                // Power-feature bulk maintenance ops (re-remove backgrounds / fix leftover cutout
                // pixels). Hidden unless FeatureFlags.powerFeatures; disabled offline (Drive writes).
                if (FeatureFlags.powerFeatures) {
                    var maintenanceMenuOpen by remember { mutableStateOf(false) }
                    Box {
                        IconButton(
                            onClick = { maintenanceMenuOpen = true },
                            enabled = !isOffline,
                        ) {
                            Icon(
                                Icons.Default.MoreVert,
                                contentDescription = stringResource(R.string.wardrobe_maintenance_menu),
                            )
                        }
                        DropdownMenu(
                            expanded = maintenanceMenuOpen,
                            onDismissRequest = { maintenanceMenuOpen = false },
                        ) {
                            DropdownMenuItem(
                                leadingIcon = { Icon(Icons.Default.AutoFixHigh, contentDescription = null) },
                                text = { Text(stringResource(R.string.settings_rebg_row)) },
                                onClick = {
                                    maintenanceMenuOpen = false
                                    Analytics.action("Wardrobe", "bulk_remove_bg")
                                    onBulkRemoveBackgrounds()
                                },
                            )
                            DropdownMenuItem(
                                leadingIcon = { Icon(Icons.Default.Tune, contentDescription = null) },
                                text = { Text(stringResource(R.string.settings_cutout_row)) },
                                onClick = {
                                    maintenanceMenuOpen = false
                                    Analytics.action("Wardrobe", "bulk_fix_cutout")
                                    onBulkFixCutoutBg()
                                },
                            )
                        }
                    }
                }
                SortButton(
                    sortBy = sortBy,
                    onSortChanged = {
                        Analytics.action("Wardrobe", "sort_changed", mapOf("option" to it.name))
                        sortBy = it
                    },
                    modifier = Modifier.padding(end = 4.dp),
                )
            }

            // ---- Selection bar (shown when at least one item is selected) ----
            if (isSelectionMode) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 12.dp, vertical = 2.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        stringResource(R.string.wardrobe_selected_count, state.selectedIds.size),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.weight(1f),
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    if (displayedImages.any { it.driveId !in state.selectedIds }) {
                        TextButton(
                            onClick = {
                                Analytics.action("Wardrobe", "select_all", mapOf("count" to displayedImages.size.toString()))
                                onSelectAll(displayedImages.map { it.driveId })
                            },
                            contentPadding = PaddingValues(horizontal = 8.dp, vertical = 0.dp),
                        ) {
                            Text(
                                stringResource(R.string.wardrobe_select_all_count, displayedImages.size),
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                        }
                    }
                    TextButton(
                        onClick = {
                            Analytics.action("Wardrobe", "clear_selection")
                            onClearSelection()
                        },
                        contentPadding = PaddingValues(horizontal = 8.dp, vertical = 0.dp),
                    ) {
                        Text(
                            stringResource(R.string.action_deselect_all),
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                }
            }

            // ---- Sync progress bar (Phase 2, even when cached items visible) ----
            // Show the bar for every counted sub-step (download → details), plus a "finishing up"
            // label so the user never sees a full, idle bar while caches are written.
            if (state.syncTotal > 0 || state.syncPhase == WardrobeSyncPhase.FINISHING) {
                Column(Modifier.fillMaxWidth()) {
                    LinearProgressIndicator(
                        progress = { if (state.syncTotal > 0) state.syncDone.toFloat() / state.syncTotal else 1f },
                        modifier = Modifier.fillMaxWidth(),
                    )
                    val label = when (state.syncPhase) {
                        WardrobeSyncPhase.DETAILS ->
                            stringResource(R.string.wardrobe_sync_details, state.syncDone, state.syncTotal)
                        WardrobeSyncPhase.FINISHING ->
                            stringResource(R.string.wardrobe_sync_finishing)
                        else ->
                            stringResource(R.string.wardrobe_sync_progress, state.syncDone, state.syncTotal)
                    }
                    Text(
                        label,
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(horizontal = 12.dp, vertical = 2.dp),
                    )
                }
            }

            // ---- Main content ----
            when {
                state.isLoading && state.syncTotal == 0 -> {
                    // Phase 1: reading local cache (very fast) or no network — show spinner
                    Box(Modifier.weight(1f).fillMaxWidth(), contentAlignment = Alignment.Center) {
                        CircularProgressIndicator()
                    }
                }
                state.isLoading && state.syncTotal > 0 -> {
                    // Phase 2 with empty cache: progress bar above is already visible; fill the rest
                    Box(Modifier.weight(1f).fillMaxWidth())
                }
                isLocationLoading && state.images.isEmpty() -> {
                    // Locations not yet loaded from Drive (first launch after install/reinstall)
                    Box(Modifier.weight(1f).fillMaxWidth(), contentAlignment = Alignment.Center) {
                        CircularProgressIndicator()
                    }
                }
                locationError != null && state.images.isEmpty() -> {
                    Box(Modifier.weight(1f).fillMaxWidth(), contentAlignment = Alignment.Center) {
                        Text(
                            text = locationError,
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.error,
                        )
                    }
                }
                displayedImages.isEmpty() && !state.isProcessing && !state.isUploading -> {
                    Box(Modifier.weight(1f).fillMaxWidth(), contentAlignment = Alignment.Center) {
                        Column(
                            horizontalAlignment = Alignment.CenterHorizontally,
                            verticalArrangement = Arrangement.Center,
                        ) {
                            if (selectedTags.values.any { it.isNotEmpty() } || textQuery.isNotBlank()) {
                                Text(stringResource(R.string.wardrobe_empty_filter), style = MaterialTheme.typography.bodyLarge)
                            } else {
                                Text(stringResource(R.string.wardrobe_empty), style = MaterialTheme.typography.bodyLarge)
                                Spacer(Modifier.height(4.dp))
                                Text(
                                    stringResource(R.string.wardrobe_empty_hint),
                                    style = MaterialTheme.typography.bodyMedium,
                                )
                            }
                        }
                    }
                }
                else -> {
                    WardrobeZoomableItemGrid(
                        images = displayedImages,
                        selectedIds = state.selectedIds,
                        onClick = { index, image ->
                            if (isSelectionMode) {
                                Analytics.action("Wardrobe", "toggle_selection")
                                onToggleSelection(image.driveId)
                            } else {
                                Analytics.action("Wardrobe", "open_item_viewer")
                                onOpenItemViewer(displayedImages.map { it.driveId }, image.driveId)
                            }
                        },
                        onLongClick = { image ->
                            Analytics.action("Wardrobe", "long_press_select")
                            onToggleSelection(image.driveId)
                        },
                        modifier = Modifier.weight(1f).fillMaxWidth(),
                        gridState = gridState,
                        locationLookup = if (locations.size > 1) {
                            { image -> locations.find { it.folderId == image.folderId }?.name }
                        } else {
                            { null }
                        },
                        highlightedDriveId = highlightedDriveId,
                        processingDriveId = state.processingImageId,
                        contentPadding = PaddingValues(bottom = 96.dp),
                    )
                }
            }
        }

        // Progress pill
        val retagLabel = if (retag.isRunning) stringResource(R.string.settings_rescanning, retag.done + 1, retag.total) else null
        val overlayLabel = when {
            convert.isConverting -> stringResource(R.string.settings_converting, convert.done, convert.total)
            retag.isRunning -> retagLabel
            state.isMoving -> stringResource(R.string.wardrobe_progress_moving)
            state.isProcessing && ingestion.batchTotal > 1 ->
                stringResource(R.string.wardrobe_progress_removing_bg_batch, ingestion.batchDone + 1, ingestion.batchTotal)
            state.isUploading && ingestion.batchTotal > 1 ->
                stringResource(R.string.wardrobe_progress_uploading_batch, ingestion.batchDone + 1, ingestion.batchTotal)
            state.isProcessing -> stringResource(R.string.wardrobe_progress_removing_bg)
            state.isUploading  -> stringResource(R.string.wardrobe_progress_uploading)
            ingestion.pendingJobs > 0 -> stringResource(R.string.wardrobe_processing_photos, ingestion.pendingJobs)
            else -> null
        }
        if (overlayLabel != null) {
            Surface(
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .padding(bottom = 88.dp),
                shape = MaterialTheme.shapes.medium,
                tonalElevation = 4.dp,
            ) {
                Row(
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 10.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    CircularProgressIndicator(modifier = Modifier.size(18.dp), strokeWidth = 2.dp)
                    Text(overlayLabel, style = MaterialTheme.typography.bodyMedium)
                }
            }
        }

        // Shared create FAB — hidden during selection mode / offline.
        AppFab(
            label = stringResource(R.string.fab_wardrobe_add),
            icon = Icons.Default.Add,
            onClick = {
                Analytics.action("Wardrobe", "open_camera")
                onOpenCamera()
            },
            expanded = rememberFabExpanded(gridState),
            visible = !isSelectionMode && !isOffline,
            modifier = Modifier.align(Alignment.BottomEnd).padding(end = 16.dp, bottom = 16.dp),
        )

        // Shared selection action bar — Style (primary) · Swap · Move · Delete (danger).
        val selectionActions = buildList {
            if (!isOffline) {
                add(
                    SelectionAction(
                        label = stringResource(R.string.sel_style),
                        icon = Icons.Default.AutoFixHigh,
                        kind = SelectionAction.Kind.Primary,
                    ) { onCreateOutfitFromSelection(state.selectedIds) },
                )
                add(
                    SelectionAction(
                        label = stringResource(R.string.sel_swap),
                        icon = Icons.Default.SwapHoriz,
                    ) { onSuggestReplacements(state.selectedIds) },
                )
                if (locations.size > 1) {
                    add(
                        SelectionAction(
                            label = stringResource(R.string.sel_move),
                            icon = Icons.Default.Place,
                        ) {
                            Analytics.action("Wardrobe", "open_move_dialog", mapOf("count" to state.selectedIds.size.toString()))
                            showMoveDialog = true
                        },
                    )
                }
                add(
                    SelectionAction(
                        label = stringResource(R.string.action_delete),
                        icon = Icons.Default.Delete,
                        kind = SelectionAction.Kind.Danger,
                    ) {
                        Analytics.action("Wardrobe", "open_delete_dialog", mapOf("count" to state.selectedIds.size.toString()))
                        pendingDeleteIds = state.selectedIds
                    },
                )
            }
        }
        SelectionActionBar(
            count = state.selectedIds.size,
            onClear = {
                Analytics.action("Wardrobe", "clear_selection")
                onClearSelection()
            },
            actions = selectionActions,
            visible = isSelectionMode && selectionActions.isNotEmpty(),
            modifier = Modifier.align(Alignment.BottomCenter),
        )

        state.error?.let { msg ->
            Snackbar(
                modifier = Modifier
                    .align(Alignment.TopCenter)
                    .statusBarsPadding()
                    .padding(start = 8.dp, end = 8.dp, top = 64.dp),
                action = { TextButton(onClick = onDismissError) { Text(stringResource(R.string.action_dismiss)) } },
            ) { Text(msg) }
        }

        if (filterSheetOpen) {
            WardrobeFilterSheet(
                tagCategories = tagCategories,
                selectedTags = selectedTags,
                appliedCount = displayedImages.size,
                onTagsChanged = { selectedTags = it },
                textQuery = textQuery,
                onTextQueryChanged = { textQuery = it },
                onDismiss = { filterSheetOpen = false },
            )
        }

        if (statsSheetOpen) {
            WardrobeStatsSheet(
                images = state.images,
                onDismiss = { statsSheetOpen = false },
            )
        }

    }

    pendingDeleteIds?.let { ids ->
        DeleteItemsConfirmDialog(
            ids = ids,
            images = state.images,
            outfits = outfits,
            tryOns = tryOns,
            onDeleteItems = onDeleteItems,
            onDeleteOutfits = onDeleteOutfits,
            onDeleteTryOns = onDeleteTryOns,
            onDismiss = { pendingDeleteIds = null },
        )
    }

    if (state.needsBatteryExemption) {
        val batteryContext = LocalContext.current
        AlertDialog(
            onDismissRequest = onDismissBatteryExemption,
            title = { Text(stringResource(R.string.battery_exempt_title)) },
            text = { Text(stringResource(R.string.battery_exempt_text)) },
            confirmButton = {
                TextButton(
                    onClick = {
                        onDismissBatteryExemption()
                        fun launchIntent(vararg intents: Intent) {
                            for (intent in intents) {
                                try {
                                    batteryContext.startActivity(intent)
                                    return
                                } catch (_: Exception) { }
                            }
                        }
                        launchIntent(
                            Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS).apply {
                                data = Uri.parse("package:${batteryContext.packageName}")
                                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                            },
                            Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS).apply {
                                data = Uri.parse("package:${batteryContext.packageName}")
                                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                            },
                            Intent(Settings.ACTION_SETTINGS).apply {
                                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                            },
                        )
                    }
                ) { Text(stringResource(R.string.battery_exempt_action)) }
            },
            dismissButton = {
                TextButton(onClick = onDismissBatteryExemption) {
                    Text(stringResource(DsR.string.action_cancel))
                }
            },
        )
    }

    if (showMoveDialog) {
        // Offer only closets that aren't already a home of every selected item — moving there
        // would be a no-op. In single-closet view this is the active closet; in "All" it's each
        // item's own closet. A selection spanning >1 closet keeps every closet as a destination
        // (moveItemsToLocation skips the per-item no-ops).
        val selectedItems = state.images.filter { it.driveId in state.selectedIds }
        val otherLocations = locations.filter { loc ->
            selectedItems.isEmpty() || selectedItems.any { it.folderId != loc.folderId }
        }
        AlertDialog(
            onDismissRequest = { showMoveDialog = false },
            title = { Text(stringResource(R.string.wardrobe_move_to_title, state.selectedIds.size)) },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    otherLocations.forEach { location ->
                        Surface(
                            shape = MaterialTheme.shapes.small,
                            tonalElevation = 1.dp,
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable {
                                    Analytics.action("Wardrobe", "confirm_move_selected", mapOf("count" to state.selectedIds.size.toString()))
                                    onMoveToLocation(state.selectedIds, location.folderId)
                                    showMoveDialog = false
                                },
                        ) {
                            Row(
                                modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(8.dp),
                            ) {
                                Icon(Icons.Default.Place, contentDescription = null, modifier = Modifier.size(18.dp))
                                Text(location.name, style = MaterialTheme.typography.bodyMedium)
                            }
                        }
                    }
                }
            },
            confirmButton = {},
            dismissButton = {
                TextButton(onClick = { showMoveDialog = false }) {
                    Text(stringResource(DsR.string.action_cancel))
                }
            },
        )
    }

    state.findByPhoto?.let { fbp ->
        FindByPhotoResultsSheet(
            findByPhoto = fbp,
            debugSimilarityPreview = debugSimilarityPreview,
            onPickMatch = { image ->
                selectedTags = emptyMap()
                // If a single closet is active and the match lives in a different one, switch
                // closets so the grid will contain the item once it reloads. In All Locations
                // mode the grid already shows every closet — leave the filter alone. The
                // pending-scroll LaunchedEffect retries when [displayedImages] updates, so the
                // highlight lands on the right tile either way.
                val matchFolder = image.folderId
                val viewingAll = activeLocationId == LocationViewModel.ALL_LOCATIONS_ID
                if (!viewingAll && matchFolder.isNotEmpty() && matchFolder != activeLocationId) {
                    onSetActiveLocation(matchFolder)
                }
                pendingScrollDriveId = image.driveId
                onDismissFindByPhoto()
            },
            onAddToShoppingList = { queryPath ->
                queryPath?.let { onAddMatchToShoppingList(it) }
                onDismissFindByPhoto()
            },
            onSearchAgain = { q -> onSearchByText(q) },
            onDismiss = onDismissFindByPhoto,
        )
    }

}

/**
 * URL import dialog: paste a shopping URL, optionally pick the target closet (when 2+ closets
 * exist). Submit only enables once a non-blank URL is entered.
 */
