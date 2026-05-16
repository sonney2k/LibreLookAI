package com.librelookai.outfit
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.itemsIndexed
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.material.icons.Icons
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.AutoAwesome
import androidx.compose.material.icons.filled.Style
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.automirrored.filled.Sort
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.AutoFixHigh
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowLeft
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.CalendarMonth
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.FloatingActionButtonDefaults
import androidx.compose.material3.InputChip
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.SmallFloatingActionButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.SuggestionChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Snackbar
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.blur
import androidx.compose.ui.draw.clip
import androidx.compose.foundation.layout.offset
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.compose.ui.window.DialogWindowProvider
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.SideEffect
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.material.icons.filled.Delete
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.res.stringResource
import androidx.lifecycle.viewmodel.compose.viewModel
import coil.compose.AsyncImage
import coil.request.ImageRequest
import com.librelookai.AppScreenHeader
import com.librelookai.LocationButton
import com.librelookai.data.model.Location
import com.librelookai.data.model.Outfit
import com.librelookai.data.model.TryOn
import com.librelookai.settings.ProfileViewModel
import com.librelookai.tryon.TryOnHistoryGrid
import com.librelookai.tryon.TryOnViewModel
import com.librelookai.util.AiProcessingOverlay
import com.librelookai.util.Analytics
import com.librelookai.util.LocalIsOffline
import com.librelookai.util.LocalSystemBarsPadding
import com.librelookai.util.scrollbar
import com.librelookai.wardrobe.DriveImage
import com.librelookai.wardrobe.FullScreenViewer
import com.librelookai.wardrobe.LocationViewModel
import com.librelookai.wardrobe.QuickCategoryRow
import com.librelookai.wardrobe.TagCategory
import com.librelookai.wardrobe.TagFilterBar
import com.librelookai.wardrobe.WardrobeFilterSheet
import com.librelookai.wardrobe.WardrobeViewModel
import com.librelookai.wardrobe.displayLabel
import com.librelookai.wardrobe.tagCategories
import com.librelookai.wardrobe.tagStringsForCategory
import com.librelookai.weather.WeatherViewModel
import com.librelookai.R

private enum class OutfitSortOption {
    DATE_DESC, DATE_ASC, POPULARITY, NAME_AZ, NAME_ZA, ITEM_COUNT
}

@Composable
private fun OutfitSortOption.displayLabel(): String = when (this) {
    OutfitSortOption.DATE_DESC  -> stringResource(R.string.outfits_sort_newest)
    OutfitSortOption.DATE_ASC   -> stringResource(R.string.outfits_sort_oldest)
    OutfitSortOption.POPULARITY -> stringResource(R.string.outfits_sort_most_worn)
    OutfitSortOption.NAME_AZ    -> stringResource(R.string.outfits_sort_name_az)
    OutfitSortOption.NAME_ZA    -> stringResource(R.string.outfits_sort_name_za)
    OutfitSortOption.ITEM_COUNT -> stringResource(R.string.outfits_sort_most_items)
}

private enum class TryOnSortOption {
    DATE_DESC, DATE_ASC, ITEM_COUNT
}

@Composable
private fun TryOnSortOption.displayLabel(): String = when (this) {
    TryOnSortOption.DATE_DESC  -> stringResource(R.string.outfits_sort_newest)
    TryOnSortOption.DATE_ASC   -> stringResource(R.string.outfits_sort_oldest)
    TryOnSortOption.ITEM_COUNT -> stringResource(R.string.outfits_sort_most_items)
}

private fun List<Outfit>.outfitTagCategories(itemsById: Map<String, DriveImage>): List<TagCategory> {
    val allImages = flatMap { style -> style.itemIds.mapNotNull { itemsById[it] } }
    return allImages.tagCategories()
}

@OptIn(ExperimentalMaterial3Api::class, ExperimentalFoundationApi::class)
@Composable
fun OutfitsScreen(
    outfitsViewModel: OutfitsViewModel = viewModel(),
    wardrobeViewModel: WardrobeViewModel = viewModel(),
    outfitEventsViewModel: OutfitEventsViewModel = viewModel(),
    profileViewModel: ProfileViewModel = viewModel(),
    weatherViewModel: WeatherViewModel = viewModel(),
    locationViewModel: LocationViewModel = viewModel(),
    tryOnViewModel: TryOnViewModel = viewModel(),
    onTryOnStyle: (Outfit) -> Unit = {},
    canTryOn: Boolean = false,
    onSettingsClick: () -> Unit = {},
    navResetTick: Int = 0,
    modifier: Modifier = Modifier,
) {
    val outfitsState  by outfitsViewModel.state.collectAsState()
    val wardrobeState by wardrobeViewModel.state.collectAsState()
    val profileState by profileViewModel.state.collectAsState()
    val weatherState by weatherViewModel.state.collectAsState()
    val outfitEventsState by outfitEventsViewModel.state.collectAsState()
    val locationState by locationViewModel.state.collectAsState()
    val tryOnState by tryOnViewModel.state.collectAsState()

    // Refresh wardrobe image cache for styles once wardrobe Drive sync completes.
    LaunchedEffect(wardrobeState.isSyncing, outfitsState.isLoading) {
        if (!wardrobeState.isSyncing && !outfitsState.isLoading) {
            outfitsViewModel.refreshWardrobeImages()
        }
    }

    // styleId → number of calendar wear events
    val wearCounts = remember(outfitEventsState.events) {
        outfitEventsState.events.groupingBy { it.outfitId }.eachCount()
    }

    Box(modifier = modifier.fillMaxSize()) {
        OutfitListScreen(
                    styles = outfitsState.outfits,
                    items = outfitsState.wardrobeImages,
                    wearCounts = wearCounts,
                    isLoading = outfitsState.isLoading || wardrobeState.isLoading,
                    isPredicting = outfitsState.isPredicting,
                    locations = locationState.locations,
                    activeLocationId = locationState.activeLocationId,
                    onSetActiveLocation = locationViewModel::setActiveLocation,
                    predictionError = outfitsState.predictionError,
                    selectedOutfitIds = outfitsState.selectedOutfitIds,
                    onOpenCreateComposer = {
                        outfitsViewModel.openComposer(
                            seedItemIds = emptySet(),
                            images      = wardrobeState.images,
                            prefs       = profileState.preferences,
                            defaultSourceFolderId = locationViewModel.effectiveDefaultClosetFolderId,
                        )
                    },
                    onSuggestExisting = {
                        outfitsViewModel.openPredictionSetup(defaultSourceFolderId = null)
                    },
                    onEditOutfit = { style ->
                        outfitsViewModel.startEditing(style, wardrobeState.images, profileState.preferences)
                    },
                    onDeleteOutfit = outfitsViewModel::deleteOutfit,
                    onWearOutfit = outfitEventsViewModel::recordOutfit,
                    onSuggestOutfitTags = { o ->
                        outfitsViewModel.suggestTagsForOutfit(o, wardrobeState.images, profileState.preferences)
                    },
                    onEditOutfitTags = { o -> outfitsViewModel.openOutfitTagsEditor(o.id) },
                    onToggleOutfitSelection = outfitsViewModel::toggleOutfitSelection,
                    onSelectAllOutfits = outfitsViewModel::selectAllOutfits,
                    onClearOutfitSelection = outfitsViewModel::clearOutfitSelection,
                    onDeleteSelectedStyles = outfitsViewModel::deleteSelectedOutfits,
                    onCombineSelectedStyles = {
                        outfitsViewModel.openComposerFromSelectedOutfits(
                            images = wardrobeState.images,
                            prefs  = profileState.preferences,
                        )
                    },
                    onClearPredictionError = outfitsViewModel::clearPrediction,
                    onTryOnStyle = onTryOnStyle,
                    canTryOn = canTryOn,
                    onSettingsClick = onSettingsClick,
                    tryOnHistory = tryOnState.history,
                    onLoadTryOnHistory = tryOnViewModel::loadHistory,
                    onOpenTryOnHistoryItem = tryOnViewModel::openHistoryDetail,
                    onOpenTryOnComposer = { tryOnViewModel.openComposer(emptySet()) },
                    canTryOnComposer = canTryOn,
                    navResetTick = navResetTick,
                    wardrobeViewModel = wardrobeViewModel,
                )

        // Tag-edit dialog launched by tapping the tags row in the outfit detail viewer.
        outfitsState.tagEditingOutfitId?.let { editId ->
            val target = outfitsState.outfits.find { it.id == editId }
            if (target != null) {
                EditOutfitTagsDialog(
                    initialTags = target.tags,
                    onDismiss = outfitsViewModel::closeOutfitTagsEditor,
                    onSave = { newTags -> outfitsViewModel.setOutfitTags(editId, newTags) },
                )
            }
        }

        // AI tag-suggestion dialog launched from the outfit detail viewer.
        outfitsState.tagSuggestion?.let { sugg ->
            SuggestTagsDialog(
                state = sugg,
                onDismiss = outfitsViewModel::dismissTagSuggestions,
                onApply = { selected -> outfitsViewModel.applyTagSuggestions(sugg.outfitId, selected) },
            )
        }

        // Existing-outfit suggestion: show the picks in the standard detail viewer,
        // swipe to flip between Gemini's ranked picks.
        if (outfitsState.predictionSuggestions.isNotEmpty() && !outfitsState.isComposerOpen) {
            val predictedOutfits = remember(outfitsState.predictionSuggestions, outfitsState.outfits) {
                outfitsState.predictionSuggestions.mapNotNull { p ->
                    outfitsState.outfits.find { it.id == p.outfitId }
                }
            }
            if (predictedOutfits.isNotEmpty()) {
                val itemsById = remember(wardrobeState.images) {
                    wardrobeState.images.associateBy { it.driveId }
                }
                OutfitFullScreenViewer(
                    outfits = predictedOutfits,
                    initialIndex = outfitsState.predictionIndex.coerceIn(0, predictedOutfits.lastIndex),
                    itemsById = itemsById,
                    locations = locationState.locations,
                    activeLocationId = locationState.activeLocationId,
                    onDismiss = outfitsViewModel::clearPrediction,
                    onEdit = { o ->
                        outfitsViewModel.clearPrediction()
                        outfitsViewModel.startEditing(o, wardrobeState.images, profileState.preferences)
                    },
                    onWear = { o -> outfitEventsViewModel.recordOutfit(o.id) },
                    onDelete = { o ->
                        outfitsViewModel.deleteOutfit(o.id)
                        if (predictedOutfits.size <= 1) outfitsViewModel.clearPrediction()
                    },
                    onSuggestTags = { o ->
                        outfitsViewModel.suggestTagsForOutfit(o, wardrobeState.images, profileState.preferences)
                    },
                    onEditTags = { o -> outfitsViewModel.openOutfitTagsEditor(o.id) },
                    wardrobeViewModel = wardrobeViewModel,
                )
            }
        }

        // After saving a style, offer to wear it immediately
        outfitsState.pendingWearOutfitId?.let { styleId ->
            Snackbar(
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .padding(start = 8.dp, end = 8.dp, bottom = 16.dp),
                action = {
                    TextButton(onClick = {
                        outfitEventsViewModel.recordOutfit(styleId)
                        outfitsViewModel.clearPendingWear()
                    }) {
                        Text(stringResource(R.string.outfits_wear_today))
                    }
                },
                dismissAction = {
                    TextButton(onClick = outfitsViewModel::clearPendingWear) {
                        Text(stringResource(R.string.action_dismiss))
                    }
                },
            ) {
                Text(stringResource(R.string.outfits_saved_wear_today))
            }
        }

        PredictionSetupDialog(
            outfitsViewModel = outfitsViewModel,
            profileViewModel = profileViewModel,
            weatherViewModel = weatherViewModel,
            locationViewModel = locationViewModel,
            wardrobeViewModel = wardrobeViewModel,
        )
    }
}

// ---------- Outfit list ----------

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun OutfitListScreen(
    styles: List<Outfit>,
    items: List<DriveImage>,
    wearCounts: Map<String, Int> = emptyMap(),
    isLoading: Boolean,
    isPredicting: Boolean,
    predictionError: String?,
    selectedOutfitIds: Set<String> = emptySet(),
    locations: List<Location> = emptyList(),
    activeLocationId: String = "",
    onSetActiveLocation: ((String) -> Unit)? = null,
    onOpenCreateComposer: () -> Unit,
    onSuggestExisting: () -> Unit = {},
    onEditOutfit: (Outfit) -> Unit,
    onDeleteOutfit: (String) -> Unit,
    onWearOutfit: (String) -> Unit,
    onSuggestOutfitTags: (Outfit) -> Unit = {},
    onEditOutfitTags: (Outfit) -> Unit = {},
    onToggleOutfitSelection: (String) -> Unit = {},
    onSelectAllOutfits: (List<String>) -> Unit = {},
    onClearOutfitSelection: () -> Unit = {},
    onDeleteSelectedStyles: () -> Unit = {},
    onCombineSelectedStyles: () -> Unit = {},
    onClearPredictionError: () -> Unit,
    onTryOnStyle: (Outfit) -> Unit = {},
    canTryOn: Boolean = false,
    onSettingsClick: () -> Unit = {},
    tryOnHistory: List<TryOn> = emptyList(),
    onLoadTryOnHistory: () -> Unit = {},
    onOpenTryOnHistoryItem: (TryOn) -> Unit = {},
    onOpenTryOnComposer: () -> Unit = {},
    canTryOnComposer: Boolean = false,
    navResetTick: Int = 0,
    modifier: Modifier = Modifier,
    wardrobeViewModel: WardrobeViewModel,
) {
    val isOffline = LocalIsOffline.current
    // itemsById: ALL locations — used to resolve item IDs to images for card display and tag filters.
    val itemsById = remember(items) { items.associateBy { it.driveId } }

    // imagesByName: filtered to the selected location (or all locations when ALL_LOCATIONS_ID).
    // Used for the "all items loaded" gate — styles with items at other locations are hidden when
    // a specific location is selected.
    val locationFolderId = remember(activeLocationId, locations) {
        if (activeLocationId != LocationViewModel.ALL_LOCATIONS_ID && activeLocationId.isNotEmpty())
            locations.find { it.folderId == activeLocationId }?.folderId else null
    }
    val imagesByName = remember(items, locationFolderId, activeLocationId) {
        val filtered = if (activeLocationId == LocationViewModel.ALL_LOCATIONS_ID || locationFolderId == null)
            items
        else
            items.filter { it.folderId == locationFolderId }
        filtered.associateBy { it.name }
    }

    var selectedTags by remember { mutableStateOf(emptyMap<String, Set<String>>()) }
    var sortBy by remember { mutableStateOf(OutfitSortOption.DATE_DESC) }
    var filterSheetOpen by remember { mutableStateOf(false) }
    var textQuery by remember { mutableStateOf("") }
    var tryOnSelectedTags by remember { mutableStateOf(emptyMap<String, Set<String>>()) }
    var tryOnFilterSheetOpen by remember { mutableStateOf(false) }
    var tryOnTextQuery by remember { mutableStateOf("") }
    var tryOnSortBy by remember { mutableStateOf(TryOnSortOption.DATE_DESC) }
    val appliedFilterCount = selectedTags.values.sumOf { it.size } + (if (textQuery.isNotBlank()) 1 else 0)
    val tryOnAppliedFilterCount = tryOnSelectedTags.values.sumOf { it.size } + (if (tryOnTextQuery.isNotBlank()) 1 else 0)

    val tagCategories = remember(styles, itemsById) { styles.outfitTagCategories(itemsById) }
    // Items referenced by any try-on (resolved by filename, same as TryOnDetailContent).
    val tryOnItemsByTryOn = remember(tryOnHistory, imagesByName) {
        tryOnHistory.associateWith { t -> t.itemNames.mapNotNull { n -> imagesByName[n] } }
    }
    val tryOnTagCategories = remember(tryOnItemsByTryOn) {
        tryOnItemsByTryOn.values.flatten().distinctBy { it.driveId }.tagCategories()
    }
    // Flattened wardrobe items referenced by any outfit — drives QuickCategoryRow counts.
    val outfitItemImages = remember(styles, itemsById) {
        styles.flatMap { it.itemIds.mapNotNull { id -> itemsById[id] } }
    }

    // A style is shown only when ALL its items are loaded for the current location filter.
    // imagesByName already reflects the active location so this check enforces the filter naturally.
    val filteredStyles = remember(styles, selectedTags, imagesByName, textQuery) {
        val activeFilters = selectedTags.filter { (_, tags) -> tags.isNotEmpty() }
        val q = textQuery.trim()
        val qLower = q.lowercase()
        val itemsMatchingText: Set<String>? = if (q.isBlank()) null else {
            wardrobeViewModel.fuzzyFilterByText(q, items).map { it.driveId }.toSet()
        }
        styles.filter { style ->
            val allLoaded = if (style.itemNames.isNotEmpty()) {
                style.itemNames.all { it in imagesByName }
            } else {
                style.itemIds.isNotEmpty() && style.itemIds.all { id -> id in itemsById }
            }
            if (!allLoaded) return@filter false
            val tagsOk = activeFilters.isEmpty() || activeFilters.all { (categoryLabel, catTags) ->
                style.itemIds.any { id ->
                    val img = itemsById[id] ?: return@any false
                    catTags.any { it in img.tagStringsForCategory(categoryLabel) }
                }
            }
            if (!tagsOk) return@filter false
            if (itemsMatchingText == null) return@filter true
            style.name.lowercase().contains(qLower) ||
                style.description.lowercase().contains(qLower) ||
                style.tags.any { it.lowercase().contains(qLower) } ||
                style.itemIds.any { it in itemsMatchingText }
        }
    }

    val displayedStyles = remember(filteredStyles, sortBy, wearCounts) {
        when (sortBy) {
            OutfitSortOption.DATE_DESC  -> filteredStyles
            OutfitSortOption.DATE_ASC   -> filteredStyles.reversed()
            OutfitSortOption.POPULARITY -> filteredStyles.sortedByDescending { wearCounts[it.id] ?: 0 }
            OutfitSortOption.NAME_AZ    -> filteredStyles.sortedBy { it.name.lowercase() }
            OutfitSortOption.NAME_ZA    -> filteredStyles.sortedByDescending { it.name.lowercase() }
            OutfitSortOption.ITEM_COUNT -> filteredStyles.sortedByDescending { it.itemIds.size }
        }
    }

    val isSelectionMode = selectedOutfitIds.isNotEmpty()
    if (isSelectionMode) BackHandler(onBack = onClearOutfitSelection)

    var fullscreenStyleId by rememberSaveable { mutableStateOf<String?>(null) }
    var showDeleteDialog by remember { mutableStateOf(false) }

    if (showDeleteDialog) {
        AlertDialog(
            onDismissRequest = { showDeleteDialog = false },
            title = { Text(stringResource(R.string.outfits_delete_selected_title)) },
            text = { Text(stringResource(R.string.outfits_delete_selected_text, selectedOutfitIds.size)) },
            confirmButton = {
                TextButton(onClick = {
                    Analytics.action("Outfits", "confirm_delete_selected")
                    onDeleteSelectedStyles(); showDeleteDialog = false
                }) {
                    Text(stringResource(R.string.action_delete), color = MaterialTheme.colorScheme.error)
                }
            },
            dismissButton = {
                TextButton(onClick = { showDeleteDialog = false }) { Text(stringResource(R.string.action_cancel)) }
            },
        )
    }

    var selectedSubTab by rememberSaveable { mutableIntStateOf(0) }
    LaunchedEffect(navResetTick) { selectedSubTab = 0 }
    val onTryOnsTab = selectedSubTab == 1

    // Lazily refresh history when entering the Try-Ons sub-tab.
    LaunchedEffect(onTryOnsTab) {
        if (onTryOnsTab) onLoadTryOnHistory()
    }

    Box(modifier = modifier.fillMaxSize()) {
        Column(Modifier.fillMaxSize()) {
            // ---- Screen header with sort button (Outfits tab only) ----
            AppScreenHeader(
                title = stringResource(R.string.nav_styles),
                leadingIcon = Icons.Default.Style,
                trailingContent = {
                    LocationButton(
                        locations = locations,
                        activeLocationId = activeLocationId,
                        onSetActiveLocation = onSetActiveLocation ?: {},
                    )
                },
                onSettingsClick = onSettingsClick,
            )

            // ---- Sub-tab row ----
            androidx.compose.material3.TabRow(selectedTabIndex = selectedSubTab) {
                LaunchedEffect(selectedSubTab) {
                    Analytics.screen(if (selectedSubTab == 0) "Outfits/List" else "Outfits/TryOns")
                }
                androidx.compose.material3.Tab(
                    selected = selectedSubTab == 0,
                    onClick = { selectedSubTab = 0 },
                    text = { Text(stringResource(R.string.outfits_tab_outfits)) },
                )
                androidx.compose.material3.Tab(
                    selected = selectedSubTab == 1,
                    onClick = { selectedSubTab = 1 },
                    text = { Text(stringResource(R.string.outfits_tab_tryons)) },
                )
            }

            if (onTryOnsTab) {
                val filteredTryOns = remember(tryOnHistory, tryOnSelectedTags, tryOnItemsByTryOn, tryOnTextQuery) {
                    val active = tryOnSelectedTags.filter { (_, tags) -> tags.isNotEmpty() }
                    val q = tryOnTextQuery.trim()
                    val itemsMatchingText: Set<String>? = if (q.isBlank()) null else {
                        wardrobeViewModel.fuzzyFilterByText(q, items).map { it.driveId }.toSet()
                    }
                    tryOnHistory.filter { t ->
                        val tItems = tryOnItemsByTryOn[t].orEmpty()
                        val tagsOk = active.isEmpty() || active.all { (categoryLabel, catTags) ->
                            tItems.any { img ->
                                catTags.any { it in img.tagStringsForCategory(categoryLabel) }
                            }
                        }
                        if (!tagsOk) return@filter false
                        itemsMatchingText == null || tItems.any { it.driveId in itemsMatchingText }
                    }
                }
                val displayedTryOns = remember(filteredTryOns, tryOnSortBy) {
                    when (tryOnSortBy) {
                        TryOnSortOption.DATE_DESC  -> filteredTryOns.sortedByDescending { it.createdAt }
                        TryOnSortOption.DATE_ASC   -> filteredTryOns.sortedBy { it.createdAt }
                        TryOnSortOption.ITEM_COUNT -> filteredTryOns.sortedByDescending { it.itemNames.size }
                    }
                }
                if (tryOnHistory.isNotEmpty()) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .background(com.librelookai.ui.theme.LocalWardrobePalette.current.surface),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        QuickCategoryRow(
                            totalCount = tryOnHistory.size,
                            filteredCount = filteredTryOns.size,
                            appliedFilterCount = tryOnAppliedFilterCount,
                            filtersEnabled = tryOnTagCategories.isNotEmpty() || tryOnHistory.isNotEmpty(),
                            onClearFilters = { tryOnSelectedTags = emptyMap(); tryOnTextQuery = "" },
                            onOpenFilters = { tryOnFilterSheetOpen = true },
                            modifier = Modifier.weight(1f),
                        )
                        TryOnSortButton(
                            sortBy = tryOnSortBy,
                            onSortChanged = { tryOnSortBy = it },
                            modifier = Modifier.padding(end = 4.dp),
                        )
                    }
                }
                TryOnHistoryGrid(
                    history = displayedTryOns,
                    onOpen  = onOpenTryOnHistoryItem,
                )
                return@Column
            }

            // ---- Selection bar ----
            if (isSelectionMode) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 12.dp, vertical = 2.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        stringResource(R.string.outfits_selected_count, selectedOutfitIds.size),
                        modifier = Modifier.weight(1f),
                        style = MaterialTheme.typography.bodyMedium,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    if (displayedStyles.any { it.id !in selectedOutfitIds }) {
                        TextButton(
                            onClick = { onSelectAllOutfits(displayedStyles.map { it.id }) },
                            contentPadding = PaddingValues(horizontal = 8.dp, vertical = 0.dp),
                        ) {
                            Text(
                                stringResource(R.string.outfits_select_all_count, displayedStyles.size),
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                        }
                    }
                    TextButton(
                        onClick = onClearOutfitSelection,
                        contentPadding = PaddingValues(horizontal = 8.dp, vertical = 0.dp),
                    ) {
                        Text(
                            stringResource(R.string.action_deselect_all),
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                }
                HorizontalDivider()
            }

            // ---- Quick category chip row + sort ----
            if (!onTryOnsTab && styles.isNotEmpty()) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .background(com.librelookai.ui.theme.LocalWardrobePalette.current.surface),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    QuickCategoryRow(
                        totalCount = styles.size,
                        filteredCount = filteredStyles.size,
                        appliedFilterCount = appliedFilterCount,
                        filtersEnabled = tagCategories.isNotEmpty() || styles.isNotEmpty(),
                        onClearFilters = { selectedTags = emptyMap(); textQuery = "" },
                        onOpenFilters = { filterSheetOpen = true },
                        modifier = Modifier.weight(1f),
                    )
                    if (!isSelectionMode) {
                        if (!isOffline) {
                            IconButton(
                                onClick = {
                                    Analytics.action("Outfits", "suggest_existing")
                                    onSuggestExisting()
                                },
                                enabled = !isPredicting,
                            ) {
                                if (isPredicting) {
                                    CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp)
                                } else {
                                    Icon(
                                        Icons.Default.AutoAwesome,
                                        contentDescription = stringResource(R.string.outfits_suggest),
                                    )
                                }
                            }
                        }
                        StyleSortButton(
                            sortBy = sortBy,
                            onSortChanged = { sortBy = it },
                            modifier = Modifier.padding(end = 4.dp),
                        )
                    }
                }
            }

            when {
                isLoading -> Box(Modifier.weight(1f).fillMaxWidth(), contentAlignment = Alignment.Center) {
                    CircularProgressIndicator()
                }
                styles.isEmpty() -> {
                    Box(Modifier.weight(1f).fillMaxWidth(), contentAlignment = Alignment.Center) {
                        Column(
                            horizontalAlignment = Alignment.CenterHorizontally,
                            verticalArrangement = Arrangement.Center,
                        ) {
                            Text(stringResource(R.string.outfits_empty), style = MaterialTheme.typography.bodyLarge)
                            Spacer(Modifier.height(4.dp))
                            Text(
                                stringResource(R.string.outfits_empty_hint),
                                style = MaterialTheme.typography.bodyMedium,
                            )
                        }
                    }
                }
                displayedStyles.isEmpty() -> {
                    Box(Modifier.weight(1f).fillMaxWidth(), contentAlignment = Alignment.Center) {
                        Text(stringResource(R.string.outfits_no_match), style = MaterialTheme.typography.bodyLarge)
                    }
                }
                else -> {
                    val outfitsListState = rememberLazyListState()
                    LazyColumn(
                        state = outfitsListState,
                        modifier = Modifier.weight(1f).fillMaxWidth().scrollbar(outfitsListState),
                        contentPadding = PaddingValues(
                            top = 8.dp,
                            bottom = if (isSelectionMode) 96.dp else 8.dp,
                            start = 0.dp,
                            end = 0.dp,
                        ),
                        verticalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        itemsIndexed(displayedStyles, key = { _, s -> s.id }) { _, style ->
                            OutfitCard(
                                style = style,
                                itemsById = itemsById,
                                locations = locations,
                                isSelected = style.id in selectedOutfitIds,
                                isSelectionMode = isSelectionMode,
                                onEdit = { onEditOutfit(style) },
                                onDelete = { onDeleteOutfit(style.id) },
                                onWear = { onWearOutfit(style.id) },
                                onOpen = { fullscreenStyleId = style.id },
                                onToggleSelection = { onToggleOutfitSelection(style.id) },
                            )
                        }
                    }
                }
            }
        }

        // Selection mode FAB bar
        if (!onTryOnsTab && isSelectionMode) {
            Column(
                modifier = Modifier
                    .align(Alignment.BottomEnd)
                    .padding(end = 16.dp, bottom = 16.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
                horizontalAlignment = Alignment.End,
            ) {
                if (selectedOutfitIds.size >= 2 && !isOffline) {
                    ExtendedFloatingActionButton(
                        onClick = {
                            Analytics.action("Outfits", "combine_selected", mapOf("count" to selectedOutfitIds.size.toString()))
                            onCombineSelectedStyles()
                        },
                        containerColor = MaterialTheme.colorScheme.tertiaryContainer,
                        contentColor = MaterialTheme.colorScheme.onTertiaryContainer,
                        icon = { Icon(Icons.Default.AutoFixHigh, contentDescription = null) },
                        text = { Text(stringResource(R.string.outfits_combine)) },
                    )
                }
                if (selectedOutfitIds.size == 1 && canTryOn && !isOffline) {
                    val selectedStyle = styles.firstOrNull { it.id in selectedOutfitIds }
                    if (selectedStyle != null) {
                        ExtendedFloatingActionButton(
                            onClick = {
                                Analytics.action("Outfits", "try_on_selected_style")
                                onTryOnStyle(selectedStyle)
                            },
                            containerColor = MaterialTheme.colorScheme.primaryContainer,
                            contentColor = MaterialTheme.colorScheme.onPrimaryContainer,
                            icon = { Icon(Icons.Default.AutoAwesome, contentDescription = null) },
                            text = { Text(stringResource(R.string.tryon_fab)) },
                        )
                    }
                }
                if (!isOffline) {
                    ExtendedFloatingActionButton(
                        onClick = {
                            Analytics.action("Outfits", "open_delete_dialog", mapOf("count" to selectedOutfitIds.size.toString()))
                            showDeleteDialog = true
                        },
                        containerColor = MaterialTheme.colorScheme.error,
                        contentColor = MaterialTheme.colorScheme.onError,
                        icon = { Icon(Icons.Default.Close, contentDescription = null) },
                        text = { Text(stringResource(R.string.action_delete)) },
                    )
                }
            }
        } else if (!onTryOnsTab) {
            FloatingActionButton(
                onClick = {
                    Analytics.action("Outfits", "open_create_composer")
                    onOpenCreateComposer()
                },
                modifier = Modifier
                    .align(Alignment.BottomEnd)
                    .padding(end = 16.dp, bottom = 16.dp),
            ) {
                Icon(Icons.Default.Add, contentDescription = stringResource(R.string.wardrobe_create_style))
            }
        } else if (canTryOnComposer && !isOffline) {
            FloatingActionButton(
                onClick = {
                    Analytics.action("Outfits/TryOns", "open_composer")
                    onOpenTryOnComposer()
                },
                modifier = Modifier
                    .align(Alignment.BottomEnd)
                    .padding(end = 16.dp, bottom = 16.dp),
            ) {
                Icon(Icons.Default.Add, contentDescription = stringResource(R.string.tryon_title))
            }
        }

        if (filterSheetOpen) {
            WardrobeFilterSheet(
                tagCategories = tagCategories,
                selectedTags = selectedTags,
                appliedCount = displayedStyles.size,
                onTagsChanged = { selectedTags = it },
                textQuery = textQuery,
                onTextQueryChanged = { textQuery = it },
                onDismiss = { filterSheetOpen = false },
            )
        }

        if (tryOnFilterSheetOpen) {
            val previewCount = run {
                val active = tryOnSelectedTags.filter { (_, tags) -> tags.isNotEmpty() }
                if (active.isEmpty()) tryOnHistory.size
                else tryOnHistory.count { t ->
                    val tItems = tryOnItemsByTryOn[t].orEmpty()
                    active.all { (categoryLabel, catTags) ->
                        tItems.any { img ->
                            catTags.any { it in img.tagStringsForCategory(categoryLabel) }
                        }
                    }
                }
            }
            WardrobeFilterSheet(
                tagCategories = tryOnTagCategories,
                selectedTags = tryOnSelectedTags,
                appliedCount = previewCount,
                onTagsChanged = { tryOnSelectedTags = it },
                textQuery = tryOnTextQuery,
                onTextQueryChanged = { tryOnTextQuery = it },
                onDismiss = { tryOnFilterSheetOpen = false },
            )
        }

        // AI progress overlay — covers the whole screen while Gemini is working
        if (isPredicting) {
            AiProcessingOverlay(
                label = stringResource(R.string.ai_suggesting_style),
                modifier = Modifier.fillMaxSize(),
            )
        }

        // Full-screen outfit viewer (pager). Rendered on top of the list so its FAB
        // overlays and the dialog handles its own back-press.
        fullscreenStyleId?.let { styleId ->
            val startIndex = displayedStyles.indexOfFirst { it.id == styleId }
            if (startIndex >= 0 && displayedStyles.isNotEmpty()) {
                OutfitFullScreenViewer(
                    outfits = displayedStyles,
                    initialIndex = startIndex,
                    itemsById = itemsById,
                    locations = locations,
                    activeLocationId = activeLocationId,
                    onDismiss = { fullscreenStyleId = null },
                    onEdit = { o -> fullscreenStyleId = null; onEditOutfit(o) },
                    onWear = { o -> onWearOutfit(o.id) },
                    onDelete = { o ->
                        onDeleteOutfit(o.id)
                        if (displayedStyles.size <= 1) fullscreenStyleId = null
                    },
                    onSuggestTags = onSuggestOutfitTags,
                    onEditTags = onEditOutfitTags,
                    wardrobeViewModel = wardrobeViewModel,
                )
            } else {
                LaunchedEffect(styleId) { fullscreenStyleId = null }
            }
        }

        // Error snackbar for prediction errors
        predictionError?.let { msg ->
            Snackbar(
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .padding(start = 8.dp, end = 8.dp, bottom = 80.dp),
                action = { TextButton(onClick = onClearPredictionError) { Text(stringResource(R.string.action_ok)) } },
            ) { Text(msg) }
        }
    }
}

// ---------- Sort button ----------

@Composable
private fun StyleSortButton(
    sortBy: OutfitSortOption,
    onSortChanged: (OutfitSortOption) -> Unit,
    modifier: Modifier = Modifier,
) {
    var expanded by remember { mutableStateOf(false) }
    // DropdownMenu renders in its own popup window; re-provide LocalContext/LocalConfiguration
    // so stringResource() honors the in-app language toggle.
    val parentContext = LocalContext.current
    val parentConfiguration = LocalConfiguration.current
    Box(modifier = modifier) {
        IconButton(onClick = { expanded = true }) {
            Icon(Icons.AutoMirrored.Filled.Sort, contentDescription = stringResource(R.string.action_sort))
        }
        DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
            CompositionLocalProvider(
                LocalContext provides parentContext,
                LocalConfiguration provides parentConfiguration,
            ) {
                OutfitSortOption.entries.forEach { option ->
                    DropdownMenuItem(
                        text = {
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(8.dp),
                            ) {
                                if (option == sortBy) Icon(Icons.Default.Check, null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(18.dp))
                                else Spacer(Modifier.size(18.dp))
                                Text(option.displayLabel())
                            }
                        },
                        onClick = {
                            Analytics.action("Outfits", "sort_changed", mapOf("option" to option.name))
                            onSortChanged(option); expanded = false
                        },
                    )
                }
            }
        }
    }
}

@Composable
private fun TryOnSortButton(
    sortBy: TryOnSortOption,
    onSortChanged: (TryOnSortOption) -> Unit,
    modifier: Modifier = Modifier,
) {
    var expanded by remember { mutableStateOf(false) }
    val parentContext = LocalContext.current
    val parentConfiguration = LocalConfiguration.current
    Box(modifier = modifier) {
        IconButton(onClick = { expanded = true }) {
            Icon(Icons.AutoMirrored.Filled.Sort, contentDescription = stringResource(R.string.action_sort))
        }
        DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
            CompositionLocalProvider(
                LocalContext provides parentContext,
                LocalConfiguration provides parentConfiguration,
            ) {
                TryOnSortOption.entries.forEach { option ->
                    DropdownMenuItem(
                        text = {
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(8.dp),
                            ) {
                                if (option == sortBy) Icon(Icons.Default.Check, null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(18.dp))
                                else Spacer(Modifier.size(18.dp))
                                Text(option.displayLabel())
                            }
                        },
                        onClick = {
                            Analytics.action("TryOns", "sort_changed", mapOf("option" to option.name))
                            onSortChanged(option); expanded = false
                        },
                    )
                }
            }
        }
    }
}

// ---------- Outfit card ----------

@OptIn(ExperimentalFoundationApi::class, ExperimentalLayoutApi::class)
@Composable
private fun OutfitCard(
    style: Outfit,
    itemsById: Map<String, DriveImage>,
    locations: List<Location> = emptyList(),
    isSelected: Boolean = false,
    isSelectionMode: Boolean = false,
    onEdit: () -> Unit,
    onDelete: () -> Unit,
    onWear: () -> Unit,
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
                    Text(stringResource(R.string.action_delete), color = MaterialTheme.colorScheme.error)
                }
            },
            dismissButton = {
                TextButton(onClick = { showDeleteDialog = false }) { Text(stringResource(R.string.action_cancel)) }
            },
        )
    }

    Card(
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
        else null,
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
            if (style.tags.isNotEmpty()) {
                FlowRow(
                    horizontalArrangement = Arrangement.spacedBy(4.dp),
                    verticalArrangement = Arrangement.spacedBy(4.dp),
                ) {
                    style.tags.forEach { OutfitTagChip(it) }
                }
            }
            val styleItems = style.itemIds.mapNotNull { itemsById[it] }
            if (styleItems.isEmpty()) {
                Text(
                    stringResource(R.string.outfits_missing_items),
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
            // Bottom action row: Edit (left) | Wear today (right) — hidden in selection mode and offline
            var wornToday by remember { mutableStateOf(false) }
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
                        stringResource(R.string.action_edit),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                TextButton(
                    onClick = { onWear(); wornToday = true },
                    contentPadding = PaddingValues(horizontal = 8.dp, vertical = 4.dp),
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
                        if (wornToday) stringResource(R.string.outfits_worn_today) else stringResource(R.string.outfits_wear_today),
                        style = MaterialTheme.typography.labelSmall,
                        color = if (wornToday) MaterialTheme.colorScheme.primary
                                else MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
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

@Composable
private fun OutfitTagChip(label: String) {
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
@OptIn(ExperimentalFoundationApi::class, ExperimentalLayoutApi::class)
@Composable
private fun OutfitFullScreenViewer(
    outfits: List<Outfit>,
    initialIndex: Int,
    itemsById: Map<String, DriveImage>,
    locations: List<Location>,
    activeLocationId: String,
    onDismiss: () -> Unit,
    onEdit: (Outfit) -> Unit,
    onWear: (Outfit) -> Unit,
    onDelete: (Outfit) -> Unit,
    onSuggestTags: (Outfit) -> Unit = {},
    onEditTags: (Outfit) -> Unit = {},
    wardrobeViewModel: WardrobeViewModel,
) {
    val wardrobeState by wardrobeViewModel.state.collectAsState()
    val allTagCategories = remember(itemsById) { itemsById.values.toList().tagCategories() }
    val isOffline = LocalIsOffline.current
    val barInsets = LocalSystemBarsPadding.current
    val parentContext = LocalContext.current
    val parentConfiguration = LocalConfiguration.current
    val parentView = LocalView.current
    val density = androidx.compose.ui.platform.LocalDensity.current
    val rootInsetBottomDp = remember(parentView) {
        val raw = parentView.rootWindowInsets
        val bottomPx = if (raw != null) {
            androidx.core.view.WindowInsetsCompat
                .toWindowInsetsCompat(raw, parentView)
                .getInsets(androidx.core.view.WindowInsetsCompat.Type.systemBars())
                .bottom
        } else 0
        with(density) { bottomPx.toDp() }
    }
    val effectiveBottom = maxOf(
        barInsets.calculateBottomPadding(),
        rootInsetBottomDp,
        48.dp,
    )
    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(
            usePlatformDefaultWidth = false,
            decorFitsSystemWindows = false,
        ),
    ) {
        val dialogView = LocalView.current
        SideEffect {
            val window = (dialogView.parent as? DialogWindowProvider)?.window ?: return@SideEffect
            window.setLayout(
                android.view.ViewGroup.LayoutParams.MATCH_PARENT,
                android.view.ViewGroup.LayoutParams.MATCH_PARENT,
            )
            androidx.core.view.WindowCompat.setDecorFitsSystemWindows(window, false)
        }
        CompositionLocalProvider(
            LocalContext provides parentContext,
            LocalConfiguration provides parentConfiguration,
        ) {
            BackHandler(onBack = onDismiss)
            val pagerState = rememberPagerState(
                initialPage = initialIndex.coerceIn(0, (outfits.size - 1).coerceAtLeast(0)),
                pageCount = { outfits.size },
            )
            var showEditMenu by remember { mutableStateOf(false) }
            var showDeleteDialog by remember { mutableStateOf(false) }
            var viewerImage by remember { mutableStateOf<DriveImage?>(null) }

            val current = outfits[pagerState.currentPage]

            if (showDeleteDialog) {
                AlertDialog(
                    onDismissRequest = { showDeleteDialog = false },
                    title = { Text(stringResource(R.string.outfits_delete_title)) },
                    text = { Text(stringResource(R.string.outfits_delete_text, current.name)) },
                    confirmButton = {
                        TextButton(onClick = {
                            Analytics.action("OutfitViewer", "confirm_delete")
                            showDeleteDialog = false
                            onDelete(current)
                        }) {
                            Text(stringResource(R.string.action_delete), color = MaterialTheme.colorScheme.error)
                        }
                    },
                    dismissButton = {
                        TextButton(onClick = { showDeleteDialog = false }) { Text(stringResource(R.string.action_cancel)) }
                    },
                )
            }

            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .background(MaterialTheme.colorScheme.background),
            ) {
                Column(modifier = Modifier.fillMaxSize()) {
                    // Header — collapsed to a minimal page indicator when this outfit has no
                    // name AND no tags (e.g. a fresh AI suggestion). Composer fullscreen view
                    // mirrors this minimalist treatment.
                    val hasAnyMetadata = outfits.any { it.name.isNotBlank() || it.tags.isNotEmpty() }
                    if (hasAnyMetadata) {
                        Column(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(top = 8.dp, start = 56.dp, end = 56.dp, bottom = 8.dp),
                            horizontalAlignment = Alignment.CenterHorizontally,
                            verticalArrangement = Arrangement.spacedBy(4.dp),
                        ) {
                            Text(
                                text = current.name.ifBlank { stringResource(R.string.outfits_unnamed) },
                                color = MaterialTheme.colorScheme.onBackground,
                                style = MaterialTheme.typography.titleMedium,
                                maxLines = 2,
                                overflow = TextOverflow.Ellipsis,
                            )
                            Text(
                                text = "${pagerState.currentPage + 1} / ${outfits.size}",
                                color = MaterialTheme.colorScheme.onBackground.copy(alpha = 0.7f),
                                style = MaterialTheme.typography.labelMedium,
                            )
                            if (current.description.isNotBlank()) {
                                Text(
                                    text = current.description,
                                    color = MaterialTheme.colorScheme.onBackground.copy(alpha = 0.85f),
                                    style = MaterialTheme.typography.bodySmall,
                                    maxLines = 3,
                                    overflow = TextOverflow.Ellipsis,
                                )
                            }
                            val maxWidth = LocalConfiguration.current.screenWidthDp.dp * 0.85f
                            val tagsClickable = Modifier
                                .widthIn(max = maxWidth)
                                .then(if (!isOffline) Modifier.clickable {
                                    Analytics.action("OutfitViewer", "edit_tags")
                                    onEditTags(current)
                                } else Modifier)
                            if (current.tags.isNotEmpty()) {
                                FlowRow(
                                    modifier = tagsClickable,
                                    horizontalArrangement = Arrangement.spacedBy(4.dp, Alignment.CenterHorizontally),
                                    verticalArrangement = Arrangement.spacedBy(4.dp),
                                ) {
                                    current.tags.forEach { OutfitTagChip(it) }
                                }
                            } else if (!isOffline) {
                                Text(
                                    text = stringResource(R.string.outfits_tag_add),
                                    modifier = Modifier.clickable {
                                        Analytics.action("OutfitViewer", "edit_tags_empty")
                                        onEditTags(current)
                                    },
                                    style = MaterialTheme.typography.labelMedium,
                                    color = MaterialTheme.colorScheme.primary,
                                )
                            }
                        }
                    } else if (outfits.size > 1) {
                        // Pager indicator only — keep the close-X room (start padding) so the
                        // page count doesn't overlap the close button at top-left.
                        Text(
                            text = "${pagerState.currentPage + 1} / ${outfits.size}",
                            color = MaterialTheme.colorScheme.onBackground.copy(alpha = 0.7f),
                            style = MaterialTheme.typography.labelMedium,
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(top = 16.dp, bottom = 4.dp),
                            textAlign = androidx.compose.ui.text.style.TextAlign.Center,
                        )
                    }

                    HorizontalPager(
                        state = pagerState,
                        modifier = Modifier
                            .fillMaxWidth()
                            .weight(1f),
                    ) { page ->
                        val outfit = outfits[page]
                        OutfitPageBody(
                            outfit = outfit,
                            itemsById = itemsById,
                            locations = locations,
                            onItemClick = { viewerImage = it },
                            bottomPadding = effectiveBottom,
                        )
                    }
                }

                // Close button
                IconButton(
                    onClick = onDismiss,
                    modifier = Modifier
                        .align(Alignment.TopStart)
                        .padding(8.dp),
                ) {
                    Icon(Icons.Default.Close, contentDescription = stringResource(R.string.action_dismiss),
                        tint = MaterialTheme.colorScheme.onBackground)
                }

                // Speed-dial FAB (wear / edit / delete) — hidden offline (writes only).
                if (!isOffline) {
                    Column(
                        modifier = Modifier
                            .align(Alignment.BottomEnd)
                            .padding(bottom = effectiveBottom)
                            .padding(16.dp),
                        horizontalAlignment = Alignment.End,
                        verticalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        if (showEditMenu) {
                            ExtendedFloatingActionButton(
                                onClick = {
                                    Analytics.action("OutfitViewer", "wear_today")
                                    onWear(current); showEditMenu = false
                                },
                                containerColor = MaterialTheme.colorScheme.primaryContainer,
                                contentColor = MaterialTheme.colorScheme.onPrimaryContainer,
                                icon = { Icon(Icons.Default.CalendarMonth, contentDescription = null) },
                                text = { Text(stringResource(R.string.outfits_wear_today)) },
                            )
                            ExtendedFloatingActionButton(
                                onClick = {
                                    Analytics.action("OutfitViewer", "edit")
                                    showEditMenu = false
                                    onEdit(current)
                                },
                                containerColor = MaterialTheme.colorScheme.primaryContainer,
                                contentColor = MaterialTheme.colorScheme.onPrimaryContainer,
                                icon = { Icon(Icons.Default.Edit, contentDescription = null) },
                                text = { Text(stringResource(R.string.action_edit)) },
                            )
                            ExtendedFloatingActionButton(
                                onClick = {
                                    Analytics.action("OutfitViewer", "suggest_tags")
                                    showEditMenu = false
                                    onSuggestTags(current)
                                },
                                containerColor = MaterialTheme.colorScheme.primaryContainer,
                                contentColor = MaterialTheme.colorScheme.onPrimaryContainer,
                                icon = { Icon(Icons.Default.AutoAwesome, contentDescription = null) },
                                text = { Text(stringResource(R.string.outfits_suggest_tags)) },
                            )
                            ExtendedFloatingActionButton(
                                onClick = {
                                    Analytics.action("OutfitViewer", "open_delete_dialog")
                                    showEditMenu = false
                                    showDeleteDialog = true
                                },
                                containerColor = MaterialTheme.colorScheme.error,
                                contentColor = MaterialTheme.colorScheme.onError,
                                icon = { Icon(Icons.Default.Delete, contentDescription = null) },
                                text = { Text(stringResource(R.string.action_delete)) },
                            )
                        }
                        FloatingActionButton(onClick = { showEditMenu = !showEditMenu }) {
                            Icon(
                                if (showEditMenu) Icons.Default.Close else Icons.Default.Edit,
                                contentDescription = stringResource(R.string.action_edit),
                            )
                        }
                    }
                }
            }

            viewerImage?.let { img ->
                val viewerImages = remember(current.itemIds, itemsById) {
                    current.itemIds.mapNotNull { itemsById[it] }
                }
                val startIdx = viewerImages.indexOfFirst { it.driveId == img.driveId }
                    .coerceAtLeast(0)
                FullScreenViewer(
                    images = viewerImages,
                    initialIndex = startIdx,
                    allTagCategories = allTagCategories,
                    onDismiss = { viewerImage = null },
                    onTagImage = wardrobeViewModel::tagImage,
                    onRemoveBackground = wardrobeViewModel::reprocessBackground,
                    onRotateImage = wardrobeViewModel::rotateImage,
                    onUpdateTags = wardrobeViewModel::updateTags,
                    onDeleteItem = { driveId -> wardrobeViewModel.deleteItems(setOf(driveId)) },
                    onMoveToLocation = wardrobeViewModel::moveItemsToLocation,
                    onCreateOutfitFromSelection = {},
                    onFixCutoutBg = wardrobeViewModel::fixCutoutBgForItem,
                    onLoadOriginal = wardrobeViewModel::ensureOriginalCached,
                    locations = locations,
                    activeLocationId = activeLocationId,
                    processingImageId = wardrobeState.processingImageId,
                    writeMode = true,
                )
            }
        }
    }
}

/**
 * Body of one outfit page in the fullscreen viewer. Items are grouped by
 * tag category so the layout reads top → bottom → footwear → outerwear →
 * accessories → other.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun OutfitPageBody(
    outfit: Outfit,
    itemsById: Map<String, DriveImage>,
    locations: List<Location>,
    onItemClick: (DriveImage) -> Unit,
    bottomPadding: androidx.compose.ui.unit.Dp,
) {
    val items = remember(outfit.itemIds, itemsById) {
        outfit.itemIds.mapNotNull { itemsById[it] }
    }
    // Order items by anatomical bucket so the natural stack reads top → bottom.
    val rows: List<List<DriveImage>> = remember(items) {
        val grouped = items.groupBy { bucketFor(it) }
        OutfitItemBucket.entries.mapNotNull { b ->
            grouped[b]?.takeIf { it.isNotEmpty() }
        }
    }
    BoxWithConstraints(
        modifier = Modifier
            .fillMaxSize()
            .padding(horizontal = 12.dp)
            .padding(bottom = bottomPadding),
    ) {
        if (items.isEmpty()) {
            Text(
                stringResource(R.string.outfits_missing_items),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.outline,
                modifier = Modifier.align(Alignment.Center),
            )
            return@BoxWithConstraints
        }
        val rowOverlap = 0.28f
        val itemOverlap = 0.18f
        val n = rows.size
        val m = (rows.maxOfOrNull { it.size } ?: 1).coerceAtLeast(1)
        val availW = maxWidth
        val availH = maxHeight
        val byH = availH / (1f + (1f - rowOverlap) * (n - 1))
        val byW = availW / (1f + (1f - itemOverlap) * (m - 1))
        val tileSize = minOf(byH, byW).coerceIn(96.dp, 320.dp)
        val rowStride = tileSize * (1f - rowOverlap)
        val itemStride = tileSize * (1f - itemOverlap)
        val totalContentH = tileSize + rowStride * (n - 1)
        val topOffset = ((availH - totalContentH) / 2).coerceAtLeast(0.dp)

        rows.forEachIndexed { rowIdx, rowItems ->
            val rowWidth = tileSize + itemStride * (rowItems.size - 1)
            val rowLeft = ((availW - rowWidth) / 2).coerceAtLeast(0.dp)
            val rowTop = topOffset + rowStride * rowIdx
            rowItems.forEachIndexed { itemIdx, image ->
                val left = rowLeft + itemStride * itemIdx
                val locName = if (locations.size > 1)
                    locations.find { it.folderId == image.folderId }?.name
                else null
                OutfitViewerItemTile(
                    image = image,
                    locationName = locName,
                    size = tileSize,
                    onClick = { onItemClick(image) },
                    modifier = Modifier.offset(x = left, y = rowTop),
                )
            }
        }
    }
}

@Composable
private fun OutfitViewerItemTile(
    image: DriveImage,
    locationName: String?,
    size: androidx.compose.ui.unit.Dp = 140.dp,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val ctx = LocalContext.current
    val model = remember(image.driveId, image.version) {
        ImageRequest.Builder(ctx)
            .data(image.localPath)
            .memoryCacheKey("${image.driveId}_${image.version}")
            .build()
    }
    Box(
        modifier = modifier
            .size(size)
            .clickable(onClick = onClick),
    ) {
        // Drop-shadow silhouette — follows the cutout's alpha channel.
        AsyncImage(
            model = model,
            contentDescription = null,
            modifier = Modifier
                .fillMaxSize()
                .offset(x = 3.dp, y = 6.dp)
                .blur(radius = 8.dp)
                .graphicsLayer { alpha = 0.45f },
            contentScale = ContentScale.Fit,
            colorFilter = ColorFilter.tint(Color.Black, BlendMode.SrcIn),
        )
        AsyncImage(
            model = model,
            contentDescription = image.name,
            modifier = Modifier.fillMaxSize(),
            contentScale = ContentScale.Fit,
        )
        if (locationName != null) {
            Box(
                modifier = Modifier
                    .align(Alignment.TopEnd)
                    .padding(4.dp)
                    .background(
                        color = MaterialTheme.colorScheme.secondaryContainer.copy(alpha = 0.85f),
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
}

private enum class OutfitItemBucket(val resId: Int) {
    Outerwear(R.string.outfit_layer_outerwear),
    Top(R.string.outfit_layer_tops),
    Bottom(R.string.outfit_layer_bottoms),
    Footwear(R.string.outfit_layer_footwear),
    Accessory(R.string.outfit_layer_accessories),
    Other(R.string.outfit_layer_other),
}

private fun bucketFor(image: DriveImage): OutfitItemBucket {
    val cat = image.tags?.category?.lowercase().orEmpty()
    return when {
        cat.contains("outer") -> OutfitItemBucket.Outerwear
        cat.contains("foot") || cat.contains("shoe") -> OutfitItemBucket.Footwear
        cat.contains("bottom") || cat == "pants" || cat == "skirt" -> OutfitItemBucket.Bottom
        cat.contains("accessor") -> OutfitItemBucket.Accessory
        cat.contains("top") || cat.contains("shirt") || cat == "dress" || cat == "suit" -> OutfitItemBucket.Top
        cat.isEmpty() -> OutfitItemBucket.Other
        else -> OutfitItemBucket.Other
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun SuggestTagsDialog(
    state: TagSuggestionState,
    onDismiss: () -> Unit,
    onApply: (List<String>) -> Unit,
) {
    var selected by remember(state.suggestions) {
        mutableStateOf(state.suggestions.toSet())
    }
    // Capture parent context/config OUTSIDE the dialog so stringResource honors the
    // in-app language toggle inside the dialog window. (See CLAUDE.md.)
    val parentContext = LocalContext.current
    val parentConfiguration = LocalConfiguration.current
    AlertDialog(
        onDismissRequest = { if (!state.isSaving) onDismiss() },
        title = {
            CompositionLocalProvider(
                LocalContext provides parentContext,
                LocalConfiguration provides parentConfiguration,
            ) { Text(stringResource(R.string.outfits_suggest_tags_title)) }
        },
        text = {
            CompositionLocalProvider(
                LocalContext provides parentContext,
                LocalConfiguration provides parentConfiguration,
            ) {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    when {
                        state.isLoading -> {
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(12.dp),
                            ) {
                                CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp)
                                Text(stringResource(R.string.outfits_suggest_tags_loading))
                            }
                        }
                        state.suggestions.isEmpty() -> {
                            Text(state.error ?: stringResource(R.string.outfits_suggest_tags_empty))
                        }
                        else -> {
                            Text(
                                stringResource(R.string.outfits_suggest_tags_hint),
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                            FlowRow(
                                horizontalArrangement = Arrangement.spacedBy(6.dp),
                                verticalArrangement = Arrangement.spacedBy(6.dp),
                            ) {
                                state.suggestions.forEach { tag ->
                                    FilterChip(
                                        selected = tag in selected,
                                        onClick = {
                                            selected = if (tag in selected) selected - tag else selected + tag
                                        },
                                        label = { Text(tag) },
                                        enabled = !state.isSaving,
                                    )
                                }
                            }
                            if (state.error != null) {
                                Text(
                                    state.error,
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.error,
                                )
                            }
                        }
                    }
                }
            }
        },
        confirmButton = {
            CompositionLocalProvider(
                LocalContext provides parentContext,
                LocalConfiguration provides parentConfiguration,
            ) {
                if (state.suggestions.isNotEmpty()) {
                    TextButton(
                        onClick = { onApply(selected.toList()) },
                        enabled = !state.isSaving && !state.isLoading && selected.isNotEmpty(),
                    ) {
                        if (state.isSaving) {
                            CircularProgressIndicator(Modifier.size(16.dp), strokeWidth = 2.dp)
                        } else {
                            Text(stringResource(R.string.outfits_suggest_tags_apply))
                        }
                    }
                } else {
                    TextButton(onClick = onDismiss, enabled = !state.isSaving) {
                        Text(stringResource(R.string.action_ok))
                    }
                }
            }
        },
        dismissButton = {
            CompositionLocalProvider(
                LocalContext provides parentContext,
                LocalConfiguration provides parentConfiguration,
            ) {
                if (state.suggestions.isNotEmpty()) {
                    TextButton(onClick = onDismiss, enabled = !state.isSaving) {
                        Text(stringResource(R.string.action_cancel))
                    }
                }
            }
        },
    )
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun EditOutfitTagsDialog(
    initialTags: List<String>,
    onDismiss: () -> Unit,
    onSave: (List<String>) -> Unit,
) {
    var working by remember(initialTags) { mutableStateOf(initialTags) }
    val parentContext = LocalContext.current
    val parentConfiguration = LocalConfiguration.current
    AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            CompositionLocalProvider(
                LocalContext provides parentContext,
                LocalConfiguration provides parentConfiguration,
            ) { Text(stringResource(R.string.outfits_tags_label)) }
        },
        text = {
            CompositionLocalProvider(
                LocalContext provides parentContext,
                LocalConfiguration provides parentConfiguration,
            ) {
                OutfitTagsEditor(
                    tags = working,
                    onAdd = { t ->
                        if (working.none { it.equals(t, ignoreCase = true) }) working = working + t
                    },
                    onRemove = { t -> working = working - t },
                )
            }
        },
        confirmButton = {
            CompositionLocalProvider(
                LocalContext provides parentContext,
                LocalConfiguration provides parentConfiguration,
            ) {
                TextButton(onClick = { onSave(working) }) {
                    Text(stringResource(R.string.action_save))
                }
            }
        },
        dismissButton = {
            CompositionLocalProvider(
                LocalContext provides parentContext,
                LocalConfiguration provides parentConfiguration,
            ) {
                TextButton(onClick = onDismiss) {
                    Text(stringResource(R.string.action_cancel))
                }
            }
        },
    )
}

