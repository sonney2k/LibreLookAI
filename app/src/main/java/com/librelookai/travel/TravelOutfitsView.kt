package com.librelookai.travel

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.activity.compose.BackHandler
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.FlightTakeoff
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.librelookai.AppScreenHeader
import com.librelookai.LocationButton
import com.librelookai.R
import com.librelookai.outfit.OutfitsViewModel
import com.librelookai.settings.ProfileViewModel
import com.librelookai.ui.components.AppFab
import com.librelookai.ui.components.SelectionAction
import com.librelookai.ui.components.SelectionActionBar
import com.librelookai.ui.components.rememberFabExpanded
import com.librelookai.util.LocalIsOffline
import com.librelookai.wardrobe.LocationViewModel
import com.librelookai.wardrobe.QuickCategoryRow
import com.librelookai.wardrobe.WardrobeFilterSheet
import com.librelookai.wardrobe.WardrobeViewModel
import com.librelookai.wardrobe.tagCategories
import com.librelookai.wardrobe.tagStringsForCategory

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun TravelOutfitsView(
    wardrobeViewModel: WardrobeViewModel,
    profileViewModel: ProfileViewModel,
    stylesViewModel: OutfitsViewModel,
    generationViewModel: com.librelookai.outfit.OutfitGenerationViewModel,
    tripsViewModel: TripsViewModel,
    locationViewModel: LocationViewModel,
    onOpenPlanner: () -> Unit,
    onOpenTrip: (String) -> Unit,
    onSettingsClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val isOffline = LocalIsOffline.current
    val outfitsState   by stylesViewModel.state.collectAsState()
    val wardrobeState  by wardrobeViewModel.state.collectAsState()
    val profileState   by profileViewModel.state.collectAsState()
    val locationState  by locationViewModel.state.collectAsState()
    val tripsState     by tripsViewModel.state.collectAsState()

    val outfitsById = remember(outfitsState.outfits) { outfitsState.outfits.associateBy { it.id } }
    val tripOutfitIds = remember(tripsState.trips) { tripsState.trips.flatMap { it.outfitIds }.toSet() }
    // "Other travel outfits": travel-tagged outfits NOT in any trip (legacy data).
    val orphanTravelOutfits = remember(outfitsState.outfits, tripOutfitIds) {
        outfitsState.outfits.filter { o ->
            o.id !in tripOutfitIds && o.tags.any { it.equals("travel", ignoreCase = true) }
        }
    }
    // Resolve trip/outfit items from the cross-closet snapshot (every configured closet) UNION the
    // active closet's in-memory `images`. The snapshot is the comprehensive base — resolving
    // against `images` alone would make a trip's items vanish the moment a closet filter is applied
    // (MainActivity's `setLocation(folderId)` trims `images` to one closet). But `allLocationImages`
    // only refreshes on closet-config changes / prefetch, so it omits just-uploaded items; overlay
    // `images` last so its fresher copies win on duplicate driveIds and brand-new items still
    // resolve. (Mirrors the OutfitComposer/OutfitsScreen item-pool union.)
    val imagesById = remember(wardrobeState.allLocationImages, wardrobeState.images) {
        (wardrobeState.allLocationImages + wardrobeState.images).associateBy { it.driveId }
    }

    // Currently selected closet (null = All). A trip/outfit belongs to a closet based on where its
    // *items* live (each item's `folderId`), not where the outfit JSON happens to be stored — those
    // can differ (outfits save to the closet that was active at creation), which previously made a
    // trip whose items are all in one closet either vanish or render empty under that closet.
    val closetFilter = locationViewModel.activeFolderId

    // ---- Filter + sort state (mirrors the Outfits screen sub-panel) ----
    var selectedTags by remember { mutableStateOf(emptyMap<String, Set<String>>()) }
    var textQuery by remember { mutableStateOf("") }
    var filterSheetOpen by remember { mutableStateOf(false) }
    var sortBy by remember { mutableStateOf(TripSortOption.DATE_DESC) }
    val tripsListState = rememberLazyListState()
    val appliedFilterCount = selectedTags.values.sumOf { it.size } + (if (textQuery.isNotBlank()) 1 else 0)

    // Tag categories are derived from the wardrobe items referenced by every trip outfit
    // (plus the legacy orphan travel outfits) — same source the Outfits filter uses.
    val tagCategories = remember(tripsState.trips, orphanTravelOutfits, outfitsById, imagesById) {
        val tripImages = tripsState.trips.asSequence()
            .flatMap { it.outfitIds.asSequence() }
            .mapNotNull { outfitsById[it] }
            .flatMap { it.itemIds.asSequence() }
            .mapNotNull { imagesById[it] }
        val orphanImages = orphanTravelOutfits.asSequence()
            .flatMap { it.itemIds.asSequence() }
            .mapNotNull { imagesById[it] }
        (tripImages + orphanImages).distinct().toList().tagCategories()
    }

    // Closet filter only. A trip/orphan is shown unless we can positively resolve its item closets
    // AND none of them belong to the active closet. We never hide solely because item images aren't
    // downloaded yet: after a reinstall the cross-closet snapshot is empty/partial until every closet
    // syncs, and the old "drop trips with no resolvable items" rule blanked the whole list in the
    // meantime. TripCard degrades gracefully (placeholder previews) until thumbnails load.
    val closetTrips = remember(tripsState.trips, outfitsById, imagesById, closetFilter) {
        tripsState.trips.filter { trip ->
            if (closetFilter == null) return@filter true
            val folders = trip.outfitIds.mapNotNull { outfitsById[it] }
                .flatMap { it.itemIds }
                .mapNotNull { imagesById[it]?.folderId }
            folders.isEmpty() || folders.any { it == closetFilter }
        }
    }
    val closetOrphans = remember(orphanTravelOutfits, imagesById, closetFilter) {
        orphanTravelOutfits.filter { outfit ->
            if (closetFilter == null) return@filter true
            val folders = outfit.itemIds.mapNotNull { imagesById[it]?.folderId }
            folders.isEmpty() || folders.any { it == closetFilter }
        }
    }

    val displayedTrips = remember(closetTrips, selectedTags, textQuery, sortBy, outfitsById, imagesById) {
        val activeFilters = selectedTags.filter { it.value.isNotEmpty() }
        val q = textQuery.trim().lowercase()
        val filtered = closetTrips.filter { trip ->
            val outfits = trip.outfitIds.mapNotNull { outfitsById[it] }
            val images = outfits.flatMap { it.itemIds }.mapNotNull { imagesById[it] }
            val tagsOk = activeFilters.isEmpty() || activeFilters.all { (label, tags) ->
                images.any { img -> tags.any { it in img.tagStringsForCategory(label) } }
            }
            if (!tagsOk) return@filter false
            if (q.isBlank()) return@filter true
            trip.name.lowercase().contains(q) ||
                trip.destination.lowercase().contains(q) ||
                trip.goal.lowercase().contains(q) ||
                trip.vibes.any { it.lowercase().contains(q) } ||
                outfits.any { it.name.lowercase().contains(q) } ||
                images.any { it.name.lowercase().contains(q) }
        }
        when (sortBy) {
            TripSortOption.DATE_DESC -> filtered.sortedByDescending { it.startDate }
            TripSortOption.DATE_ASC  -> filtered.sortedBy { it.startDate }
            TripSortOption.NAME_AZ   -> filtered.sortedBy { it.name.lowercase() }
            TripSortOption.NAME_ZA   -> filtered.sortedByDescending { it.name.lowercase() }
        }
    }

    val displayedOrphans = remember(closetOrphans, selectedTags, textQuery, imagesById) {
        val activeFilters = selectedTags.filter { it.value.isNotEmpty() }
        val q = textQuery.trim().lowercase()
        closetOrphans.filter { outfit ->
            val images = outfit.itemIds.mapNotNull { imagesById[it] }
            val tagsOk = activeFilters.isEmpty() || activeFilters.all { (label, tags) ->
                images.any { img -> tags.any { it in img.tagStringsForCategory(label) } }
            }
            if (!tagsOk) return@filter false
            if (q.isBlank()) return@filter true
            outfit.name.lowercase().contains(q) ||
                outfit.description.lowercase().contains(q) ||
                outfit.tags.any { it.lowercase().contains(q) } ||
                images.any { it.name.lowercase().contains(q) }
        }
    }

    val hasAnyContent = closetTrips.isNotEmpty() || closetOrphans.isNotEmpty()

    // ---- Multi-select state (long-press a trip to enter) ----
    var selectedTripIds by remember { mutableStateOf(emptySet<String>()) }
    // Drop selections whose trip is no longer shown (deleted / filtered out).
    val visibleTripIds = remember(displayedTrips) { displayedTrips.map { it.id }.toSet() }
    LaunchedEffect(visibleTripIds) {
        if (selectedTripIds.any { it !in visibleTripIds }) {
            selectedTripIds = selectedTripIds intersect visibleTripIds
        }
    }
    val isSelectionMode = selectedTripIds.isNotEmpty()
    if (isSelectionMode) BackHandler { selectedTripIds = emptySet() }
    var showDeleteDialog by remember { mutableStateOf(false) }

    Column(modifier = modifier.fillMaxSize()) {
        AppScreenHeader(
            title = stringResource(R.string.travel_outfits_section),
            leadingIcon = Icons.Default.FlightTakeoff,
            trailingContent = {
                LocationButton(
                    locations = locationState.locations,
                    activeLocationId = locationState.activeLocationId,
                    onSetActiveLocation = locationViewModel::setActiveLocation,
                )
            },
            onSettingsClick = onSettingsClick,
        )

        // ---- Filter chip row + sort ----
        if (hasAnyContent) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(com.librelookai.ui.theme.LocalWardrobePalette.current.surface),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                QuickCategoryRow(
                    totalCount = closetTrips.size + closetOrphans.size,
                    filteredCount = displayedTrips.size + displayedOrphans.size,
                    appliedFilterCount = appliedFilterCount,
                    filtersEnabled = true,
                    onClearFilters = { selectedTags = emptyMap(); textQuery = "" },
                    onOpenFilters = { filterSheetOpen = true },
                    modifier = Modifier.weight(1f),
                )
                TripSortButton(
                    sortBy = sortBy,
                    onSortChanged = { sortBy = it },
                    modifier = Modifier.padding(end = 4.dp),
                )
            }
        }

        Box(modifier = Modifier.fillMaxSize()) {
            when {
                !hasAnyContent -> {
                    Column(
                        modifier = Modifier
                            .fillMaxSize()
                            .padding(top = 64.dp, start = 32.dp, end = 32.dp),
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        Icon(
                            Icons.Default.FlightTakeoff,
                            contentDescription = null,
                            modifier = Modifier.size(48.dp),
                            tint = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        Text(
                            stringResource(R.string.outfits_empty),
                            style = MaterialTheme.typography.bodyLarge,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            textAlign = TextAlign.Center,
                        )
                        Text(
                            stringResource(R.string.travel_outfits_empty_hint),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            textAlign = TextAlign.Center,
                        )
                    }
                }
                displayedTrips.isEmpty() && displayedOrphans.isEmpty() -> {
                    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                        Text(
                            stringResource(R.string.outfits_no_match),
                            style = MaterialTheme.typography.bodyLarge,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
                else -> {
                    LazyColumn(
                        state = tripsListState,
                        contentPadding = PaddingValues(top = 12.dp, bottom = 96.dp, start = 16.dp, end = 16.dp),
                        verticalArrangement = Arrangement.spacedBy(10.dp),
                        modifier = Modifier.fillMaxSize(),
                    ) {
                        if (displayedTrips.isNotEmpty()) {
                            items(displayedTrips, key = { it.id }, contentType = { "trip_card" }) { trip ->
                                TripCard(
                                    trip = trip,
                                    outfitsById = outfitsById,
                                    imagesById = imagesById,
                                    onClick = { onOpenTrip(trip.id) },
                                    isSelected = trip.id in selectedTripIds,
                                    isSelectionMode = isSelectionMode,
                                    onToggleSelection = {
                                        selectedTripIds = selectedTripIds.toMutableSet()
                                            .also { if (!it.add(trip.id)) it.remove(trip.id) }
                                    },
                                )
                            }
                        }
                        if (displayedOrphans.isNotEmpty()) {
                            item {
                                Text(
                                    stringResource(R.string.trip_section_other_outfits),
                                    style = MaterialTheme.typography.titleSmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    modifier = Modifier.padding(top = 8.dp),
                                )
                            }
                            items(displayedOrphans, key = { it.id }, contentType = { "orphan_outfit_card" }) { outfit ->
                                TravelOutfitListCard(
                                    outfit = outfit,
                                    imagesById = imagesById,
                                    onClick = {
                                        generationViewModel.startEditing(
                                            outfit,
                                            wardrobeState.images,
                                            profileState.preferences,
                                        )
                                    },
                                )
                            }
                        }
                    }
                }
            }

            AppFab(
                label = stringResource(R.string.fab_travel_plan),
                icon = Icons.Default.FlightTakeoff,
                onClick = onOpenPlanner,
                expanded = rememberFabExpanded(tripsListState),
                visible = !isOffline && !isSelectionMode,
                modifier = Modifier.align(Alignment.BottomEnd).padding(end = 16.dp, bottom = 16.dp),
            )

            // Shared selection action bar — Edit (primary, single-select only) · Delete (danger).
            // Hidden offline (write paths).
            val tripSelectionActions = buildList {
                if (!isOffline) {
                    if (selectedTripIds.size == 1) {
                        add(
                            SelectionAction(
                                label = stringResource(R.string.action_edit),
                                icon = Icons.Default.Edit,
                                kind = SelectionAction.Kind.Primary,
                            ) {
                                val id = selectedTripIds.first()
                                selectedTripIds = emptySet()
                                tripsViewModel.requestEditTrip(id)
                                onOpenTrip(id)
                            },
                        )
                    }
                    add(
                        SelectionAction(
                            label = stringResource(R.string.action_delete),
                            icon = Icons.Default.Delete,
                            kind = SelectionAction.Kind.Danger,
                        ) { showDeleteDialog = true },
                    )
                }
            }
            SelectionActionBar(
                count = selectedTripIds.size,
                onClear = { selectedTripIds = emptySet() },
                actions = tripSelectionActions,
                visible = isSelectionMode && tripSelectionActions.isNotEmpty(),
                modifier = Modifier.align(Alignment.BottomCenter),
            )
        }
    }

    if (showDeleteDialog) {
        DeleteTripDialog(
            onDismiss = { showDeleteDialog = false },
            onCascade = {
                showDeleteDialog = false
                val ids = selectedTripIds
                tripsViewModel.deleteTrips(ids) { outfitIds ->
                    stylesViewModel.deleteOutfitsByIds(outfitIds)
                }
                selectedTripIds = emptySet()
            },
            onKeep = {
                showDeleteDialog = false
                tripsViewModel.deleteTrips(selectedTripIds) { /* keep outfits */ }
                selectedTripIds = emptySet()
            },
        )
    }

    if (filterSheetOpen) {
        WardrobeFilterSheet(
            tagCategories = tagCategories,
            selectedTags = selectedTags,
            appliedCount = appliedFilterCount,
            onTagsChanged = { selectedTags = it },
            textQuery = textQuery,
            onTextQueryChanged = { textQuery = it },
            onDismiss = { filterSheetOpen = false },
        )
    }
}

