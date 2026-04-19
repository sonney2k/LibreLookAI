package com.librelookai

import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
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
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.itemsIndexed
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.material.icons.Icons
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.ArrowDropDown
import androidx.compose.material.icons.filled.AutoAwesome
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.automirrored.filled.Sort
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.AutoFixHigh
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
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.res.stringResource
import androidx.lifecycle.viewmodel.compose.viewModel
import coil.compose.AsyncImage
import coil.request.ImageRequest

private enum class StyleSortOption {
    DATE_DESC, DATE_ASC, POPULARITY, NAME_AZ, NAME_ZA, ITEM_COUNT
}

@Composable
private fun StyleSortOption.displayLabel(): String = when (this) {
    StyleSortOption.DATE_DESC  -> stringResource(R.string.styles_sort_newest)
    StyleSortOption.DATE_ASC   -> stringResource(R.string.styles_sort_oldest)
    StyleSortOption.POPULARITY -> stringResource(R.string.styles_sort_most_worn)
    StyleSortOption.NAME_AZ    -> stringResource(R.string.styles_sort_name_az)
    StyleSortOption.NAME_ZA    -> stringResource(R.string.styles_sort_name_za)
    StyleSortOption.ITEM_COUNT -> stringResource(R.string.styles_sort_most_items)
}

private fun List<Style>.styleTagCategories(itemsById: Map<String, DriveImage>): List<TagCategory> {
    val allImages = flatMap { style -> style.itemIds.mapNotNull { itemsById[it] } }
    return allImages.tagCategories()
}

@OptIn(ExperimentalMaterial3Api::class, ExperimentalFoundationApi::class)
@Composable
fun StylesScreen(
    stylesViewModel: StylesViewModel = viewModel(),
    wardrobeViewModel: WardrobeViewModel = viewModel(),
    outfitsViewModel: OutfitsViewModel = viewModel(),
    profileViewModel: ProfileViewModel = viewModel(),
    weatherViewModel: WeatherViewModel = viewModel(),
    locationViewModel: LocationViewModel = viewModel(),
    onSettingsClick: () -> Unit = {},
    modifier: Modifier = Modifier,
) {
    val stylesState  by stylesViewModel.state.collectAsState()
    val wardrobeState by wardrobeViewModel.state.collectAsState()
    val profileState by profileViewModel.state.collectAsState()
    val weatherState by weatherViewModel.state.collectAsState()
    val outfitsState by outfitsViewModel.state.collectAsState()
    val locationState by locationViewModel.state.collectAsState()

    // Refresh wardrobe image cache for styles once wardrobe Drive sync completes.
    LaunchedEffect(wardrobeState.isSyncing, stylesState.isLoading) {
        if (!wardrobeState.isSyncing && !stylesState.isLoading) {
            stylesViewModel.refreshWardrobeImages()
        }
    }

    // styleId → number of calendar wear events
    val wearCounts = remember(outfitsState.events) {
        outfitsState.events.groupingBy { it.styleId }.eachCount()
    }

    // When Gemini returns a prediction, auto-open the style editing view with that style.
    LaunchedEffect(stylesState.prediction) {
        val pred = stylesState.prediction ?: return@LaunchedEffect
        val style = stylesState.styles.find { it.id == pred.styleId } ?: return@LaunchedEffect
        stylesViewModel.openPredictionInEditView(style)
    }

    // When Gemini composes a new outfit, auto-open the style editing view with it.
    LaunchedEffect(stylesState.newSuggestion) {
        val suggestion = stylesState.newSuggestion ?: return@LaunchedEffect
        stylesViewModel.openSuggestionInEditView(suggestion)
    }

    Box(modifier = modifier.fillMaxSize()) {
        when {
            stylesState.isEditingStyleView -> {
                StyleEditingView(
                    draftItemIds = stylesState.draftItemIds,
                    draftStyleName = stylesState.draftStyleName,
                    draftStyleDescription = stylesState.draftStyleDescription,
                    isEditing = stylesState.editingStyle != null,
                    prediction = stylesState.prediction,
                    newSuggestion = stylesState.newSuggestion,
                    allItems = wardrobeState.images,
                    isLoadingAlternatives = stylesState.isLoadingAlternatives,
                    alternativeIds = stylesState.alternativeIds,
                    refinementInput = stylesState.refinementInput,
                    feedbackHistory = stylesState.feedbackHistory,
                    onNameChanged = stylesViewModel::updateDraftName,
                    onDescriptionChanged = stylesViewModel::updateDraftDescription,
                    onSwapItem = stylesViewModel::swapDraftItem,
                    onRemoveItem = stylesViewModel::removeDraftItem,
                    onAddItem = stylesViewModel::addDraftItem,
                    onSuggestAlternatives = { itemId ->
                        stylesViewModel.suggestAlternatives(itemId, wardrobeState.images, profileState.preferences)
                    },
                    onClearAlternatives = stylesViewModel::clearAlternatives,
                    onConfirm = stylesViewModel::confirmDraft,
                    onCancel = stylesViewModel::cancelStyleEditingView,
                    onWear = if (stylesState.editingStyle != null) {
                        { outfitsViewModel.recordOutfit(stylesState.editingStyle!!.id) }
                    } else null,
                    onRefinementInputChange = stylesViewModel::updateRefinementInput,
                    onRefinePrediction = if (stylesState.prediction != null) {
                        { stylesViewModel.refinePrediction(profileState.preferences, weatherState.data, wardrobeState.images) }
                    } else null,
                    onPresetPrediction = if (stylesState.prediction != null) {
                        { preset -> stylesViewModel.submitPresetPrediction(preset, profileState.preferences, weatherState.data, wardrobeState.images) }
                    } else null,
                    onRefineComposition = if (stylesState.newSuggestion != null) {
                        { stylesViewModel.refineComposition(profileState.preferences, weatherState.data, wardrobeState.images) }
                    } else null,
                    onPresetComposition = if (stylesState.newSuggestion != null) {
                        { preset -> stylesViewModel.submitPresetComposition(preset, profileState.preferences, weatherState.data, wardrobeState.images) }
                    } else null,
                    isRefining = stylesState.isPredicting || stylesState.isComposing,
                    locations = locationState.locations,
                )
            }
            stylesState.isCreating -> {
                StyleItemPicker(
                    items = wardrobeState.images,
                    selectedIds = stylesState.draftItemIds,
                    isEditing = stylesState.editingStyle != null,
                    styleName = stylesState.draftStyleName,
                    styleDescription = stylesState.draftStyleDescription,
                    onNameChanged = stylesViewModel::updateDraftName,
                    onDescriptionChanged = stylesViewModel::updateDraftDescription,
                    onToggleItem = stylesViewModel::toggleDraftItem,
                    onSelectAll = stylesViewModel::selectAllDraftItems,
                    onDeselectAll = stylesViewModel::deselectAllDraftItems,
                    onConfirm = stylesViewModel::confirmDraft,
                    onCancel = stylesViewModel::cancelCreating,
                )
            }
            else -> {
                StyleListScreen(
                    styles = stylesState.styles,
                    items = stylesState.wardrobeImages,
                    wearCounts = wearCounts,
                    isLoading = stylesState.isLoading || wardrobeState.isLoading,
                    isPredicting = stylesState.isPredicting,
                    locations = locationState.locations,
                    activeLocationId = locationState.activeLocationId,
                    onSetActiveLocation = locationViewModel::setActiveLocation,
                    predictionError = stylesState.predictionError,
                    isComposing = stylesState.isComposing,
                    compositionError = stylesState.compositionError,
                    selectedStyleIds = stylesState.selectedStyleIds,
                    onCreateStyle = stylesViewModel::startCreating,
                    onEditStyle = stylesViewModel::startEditing,
                    onDeleteStyle = stylesViewModel::deleteStyle,
                    onWearStyle = outfitsViewModel::recordOutfit,
                    onToggleStyleSelection = stylesViewModel::toggleStyleSelection,
                    onSelectAllStyles = stylesViewModel::selectAllStyles,
                    onClearStyleSelection = stylesViewModel::clearStyleSelection,
                    onDeleteSelectedStyles = stylesViewModel::deleteSelectedStyles,
                    onCombineSelectedStyles = {
                        stylesViewModel.combineSelectedStyles(
                            prefs   = profileState.preferences,
                            weather = weatherState.data,
                            images  = wardrobeState.images,
                        )
                    },
                    onSuggestStyle = {
                        stylesViewModel.triggerPrediction(
                            prefs   = profileState.preferences,
                            weather = weatherState.data,
                            images  = wardrobeState.images,
                        )
                    },
                    onClearPredictionError = stylesViewModel::clearPrediction,
                    onComposeStyle = {
                        stylesViewModel.triggerComposition(
                            prefs   = profileState.preferences,
                            weather = weatherState.data,
                            images  = wardrobeState.images,
                        )
                    },
                    onClearCompositionError = stylesViewModel::clearNewSuggestion,
                    onSettingsClick = onSettingsClick,
                )
            }
        }

        // After saving a style, offer to wear it immediately
        stylesState.pendingWearStyleId?.let { styleId ->
            Snackbar(
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .padding(start = 8.dp, end = 8.dp, bottom = 16.dp),
                action = {
                    TextButton(onClick = {
                        outfitsViewModel.recordOutfit(styleId)
                        stylesViewModel.clearPendingWear()
                    }) {
                        Text(stringResource(R.string.styles_wear_today))
                    }
                },
                dismissAction = {
                    TextButton(onClick = stylesViewModel::clearPendingWear) {
                        Text(stringResource(R.string.action_dismiss))
                    }
                },
            ) {
                Text(stringResource(R.string.styles_saved_wear_today))
            }
        }
    }
}

// ---------- Style list ----------

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun StyleListScreen(
    styles: List<Style>,
    items: List<DriveImage>,
    wearCounts: Map<String, Int> = emptyMap(),
    isLoading: Boolean,
    isPredicting: Boolean,
    predictionError: String?,
    isComposing: Boolean,
    compositionError: String?,
    selectedStyleIds: Set<String> = emptySet(),
    locations: List<Location> = emptyList(),
    activeLocationId: String = "",
    onSetActiveLocation: ((String) -> Unit)? = null,
    onCreateStyle: () -> Unit,
    onEditStyle: (Style) -> Unit,
    onDeleteStyle: (String) -> Unit,
    onWearStyle: (String) -> Unit,
    onToggleStyleSelection: (String) -> Unit = {},
    onSelectAllStyles: (List<String>) -> Unit = {},
    onClearStyleSelection: () -> Unit = {},
    onDeleteSelectedStyles: () -> Unit = {},
    onCombineSelectedStyles: () -> Unit = {},
    onSuggestStyle: () -> Unit,
    onClearPredictionError: () -> Unit,
    onComposeStyle: () -> Unit,
    onClearCompositionError: () -> Unit,
    onSettingsClick: () -> Unit = {},
    modifier: Modifier = Modifier,
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
    var sortBy by remember { mutableStateOf(StyleSortOption.DATE_DESC) }

    val tagCategories = remember(styles, itemsById) { styles.styleTagCategories(itemsById) }

    // A style is shown only when ALL its items are loaded for the current location filter.
    // imagesByName already reflects the active location so this check enforces the filter naturally.
    val filteredStyles = remember(styles, selectedTags, imagesByName) {
        val activeFilters = selectedTags.filter { (_, tags) -> tags.isNotEmpty() }
        styles.filter { style ->
            val allLoaded = if (style.itemNames.isNotEmpty()) {
                style.itemNames.all { it in imagesByName }
            } else {
                style.itemIds.isNotEmpty() && style.itemIds.all { id -> id in itemsById }
            }
            if (!allLoaded) return@filter false
            if (activeFilters.isEmpty()) return@filter true
            activeFilters.all { (categoryLabel, catTags) ->
                style.itemIds.any { id ->
                    val img = itemsById[id] ?: return@any false
                    catTags.any { it in img.tagStringsForCategory(categoryLabel) }
                }
            }
        }
    }

    val displayedStyles = remember(filteredStyles, sortBy, wearCounts) {
        when (sortBy) {
            StyleSortOption.DATE_DESC  -> filteredStyles
            StyleSortOption.DATE_ASC   -> filteredStyles.reversed()
            StyleSortOption.POPULARITY -> filteredStyles.sortedByDescending { wearCounts[it.id] ?: 0 }
            StyleSortOption.NAME_AZ    -> filteredStyles.sortedBy { it.name.lowercase() }
            StyleSortOption.NAME_ZA    -> filteredStyles.sortedByDescending { it.name.lowercase() }
            StyleSortOption.ITEM_COUNT -> filteredStyles.sortedByDescending { it.itemIds.size }
        }
    }

    val isSelectionMode = selectedStyleIds.isNotEmpty()
    if (isSelectionMode) BackHandler(onBack = onClearStyleSelection)

    var showDeleteDialog by remember { mutableStateOf(false) }

    if (showDeleteDialog) {
        AlertDialog(
            onDismissRequest = { showDeleteDialog = false },
            title = { Text(stringResource(R.string.styles_delete_selected_title)) },
            text = { Text(stringResource(R.string.styles_delete_selected_text, selectedStyleIds.size)) },
            confirmButton = {
                TextButton(onClick = { onDeleteSelectedStyles(); showDeleteDialog = false }) {
                    Text(stringResource(R.string.action_delete), color = MaterialTheme.colorScheme.error)
                }
            },
            dismissButton = {
                TextButton(onClick = { showDeleteDialog = false }) { Text(stringResource(R.string.action_cancel)) }
            },
        )
    }

    Box(modifier = modifier.fillMaxSize()) {
        Column(Modifier.fillMaxSize()) {
            // ---- Screen header with sort button ----
            AppScreenHeader(
                title = stringResource(R.string.nav_styles),
                trailingContent = {
                    LocationButton(
                        locations = locations,
                        activeLocationId = activeLocationId,
                        onSetActiveLocation = onSetActiveLocation ?: {},
                    )
                    if (styles.isNotEmpty() && !isSelectionMode) {
                        StyleSortButton(
                            sortBy = sortBy,
                            onSortChanged = { sortBy = it },
                            modifier = Modifier.padding(end = 4.dp),
                        )
                    }
                },
                onSettingsClick = onSettingsClick,
            )

            // ---- Selection bar ----
            if (isSelectionMode) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 12.dp, vertical = 2.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        stringResource(R.string.styles_selected_count, selectedStyleIds.size),
                        modifier = Modifier.weight(1f),
                        style = MaterialTheme.typography.bodyMedium,
                    )
                    if (displayedStyles.any { it.id !in selectedStyleIds }) {
                        TextButton(onClick = { onSelectAllStyles(displayedStyles.map { it.id }) }) {
                            Text(stringResource(R.string.styles_select_all_count, displayedStyles.size))
                        }
                    }
                    TextButton(onClick = onClearStyleSelection) {
                        Text(stringResource(R.string.action_deselect_all))
                    }
                }
                HorizontalDivider()
            }

            // ---- Tag filter chips ----
            TagFilterBar(
                tagCategories = tagCategories,
                selectedTags = selectedTags,
                onTagsChanged = { selectedTags = it },
            )

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
                            Text(stringResource(R.string.styles_empty), style = MaterialTheme.typography.bodyLarge)
                            Spacer(Modifier.height(4.dp))
                            Text(
                                stringResource(R.string.styles_empty_hint),
                                style = MaterialTheme.typography.bodyMedium,
                            )
                        }
                    }
                }
                displayedStyles.isEmpty() -> {
                    Box(Modifier.weight(1f).fillMaxWidth(), contentAlignment = Alignment.Center) {
                        Text(stringResource(R.string.styles_no_match), style = MaterialTheme.typography.bodyLarge)
                    }
                }
                else -> {
                    LazyColumn(
                        modifier = Modifier.weight(1f).fillMaxWidth(),
                        contentPadding = PaddingValues(
                            top = 8.dp,
                            bottom = if (isSelectionMode) 96.dp else 8.dp,
                            start = 0.dp,
                            end = 0.dp,
                        ),
                        verticalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        itemsIndexed(displayedStyles, key = { _, s -> s.id }) { _, style ->
                            StyleCard(
                                style = style,
                                itemsById = itemsById,
                                locations = locations,
                                isSelected = style.id in selectedStyleIds,
                                isSelectionMode = isSelectionMode,
                                onEdit = { onEditStyle(style) },
                                onDelete = { onDeleteStyle(style.id) },
                                onWear = { onWearStyle(style.id) },
                                onToggleSelection = { onToggleStyleSelection(style.id) },
                            )
                        }
                    }
                }
            }
        }

        // Selection mode FAB bar
        if (isSelectionMode) {
            Column(
                modifier = Modifier
                    .align(Alignment.BottomEnd)
                    .padding(end = 16.dp, bottom = 16.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
                horizontalAlignment = Alignment.End,
            ) {
                if (selectedStyleIds.size >= 2 && !isOffline) {
                    ExtendedFloatingActionButton(
                        onClick = { if (!isComposing) onCombineSelectedStyles() },
                        containerColor = MaterialTheme.colorScheme.tertiaryContainer,
                        contentColor = MaterialTheme.colorScheme.onTertiaryContainer,
                        icon = {
                            if (isComposing)
                                CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp)
                            else
                                Icon(Icons.Default.AutoFixHigh, contentDescription = null)
                        },
                        text = { Text(stringResource(R.string.styles_combine)) },
                    )
                }
                if (!isOffline) {
                    ExtendedFloatingActionButton(
                        onClick = { showDeleteDialog = true },
                        containerColor = MaterialTheme.colorScheme.error,
                        contentColor = MaterialTheme.colorScheme.onError,
                        icon = { Icon(Icons.Default.Close, contentDescription = null) },
                        text = { Text(stringResource(R.string.action_delete)) },
                    )
                }
            }
        } else {
            // Normal speed-dial FAB (bottom-end)
            var fabExpanded by remember { mutableStateOf(false) }
            Column(
                modifier = Modifier
                    .align(Alignment.BottomEnd)
                    .padding(end = 16.dp, bottom = 16.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
                horizontalAlignment = Alignment.End,
            ) {
                AnimatedVisibility(
                    visible = fabExpanded,
                    enter = fadeIn() + slideInVertically(initialOffsetY = { it / 2 }),
                    exit = fadeOut() + slideOutVertically(targetOffsetY = { it / 2 }),
                ) {
                    Column(
                        verticalArrangement = Arrangement.spacedBy(8.dp),
                        horizontalAlignment = Alignment.End,
                    ) {
                        SpeedDialItem(
                            label = stringResource(R.string.styles_create_manual),
                            icon = { Icon(Icons.Default.Edit, contentDescription = null) },
                            containerColor = MaterialTheme.colorScheme.primaryContainer,
                            onClick = { fabExpanded = false; onCreateStyle() },
                        )
                        if (!isOffline) {
                            SpeedDialItem(
                                label = if (isPredicting) stringResource(R.string.styles_thinking) else stringResource(R.string.styles_suggest),
                                icon = {
                                    if (isPredicting)
                                        CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp)
                                    else
                                        Icon(Icons.Default.AutoAwesome, contentDescription = null)
                                },
                                containerColor = MaterialTheme.colorScheme.secondaryContainer,
                                onClick = { fabExpanded = false; if (!isPredicting) onSuggestStyle() },
                            )
                            SpeedDialItem(
                                label = if (isComposing) stringResource(R.string.styles_thinking) else stringResource(R.string.styles_compose),
                                icon = {
                                    if (isComposing)
                                        CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp)
                                    else
                                        Icon(Icons.Default.AutoFixHigh, contentDescription = null)
                                },
                                containerColor = MaterialTheme.colorScheme.tertiaryContainer,
                                onClick = { fabExpanded = false; if (!isComposing) onComposeStyle() },
                            )
                        }
                    }
                }
                FloatingActionButton(onClick = { fabExpanded = !fabExpanded }) {
                    Icon(
                        if (fabExpanded) Icons.Default.Close else Icons.Default.Add,
                        contentDescription = if (fabExpanded) "Close" else "Actions",
                    )
                }
            }
        }

        // AI progress overlay — covers the whole screen while Gemini is working
        if (isPredicting || isComposing) {
            AiProcessingOverlay(
                label = if (isComposing) stringResource(R.string.ai_composing_outfit) else stringResource(R.string.ai_suggesting_style),
                modifier = Modifier.fillMaxSize(),
            )
        }

        // Error snackbars (composition takes priority if both are set)
        val errorMsg = compositionError ?: predictionError
        val onClearError = if (compositionError != null) onClearCompositionError else onClearPredictionError
        errorMsg?.let { msg ->
            Snackbar(
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .padding(start = 8.dp, end = 8.dp, bottom = 80.dp),
                action = { TextButton(onClick = onClearError) { Text(stringResource(R.string.action_ok)) } },
            ) { Text(msg) }
        }
    }
}

// ---------- Speed-dial helper ----------

@Composable
private fun SpeedDialItem(
    label: String,
    icon: @Composable () -> Unit,
    containerColor: androidx.compose.ui.graphics.Color,
    onClick: () -> Unit,
) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        androidx.compose.material3.Surface(
            shape = MaterialTheme.shapes.small,
            color = MaterialTheme.colorScheme.surface,
            shadowElevation = 2.dp,
        ) {
            Text(
                label,
                style = MaterialTheme.typography.labelMedium,
                modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp),
            )
        }
        SmallFloatingActionButton(
            onClick = onClick,
            containerColor = containerColor,
        ) { icon() }
    }
}

// ---------- Sort button ----------

@Composable
private fun StyleSortButton(
    sortBy: StyleSortOption,
    onSortChanged: (StyleSortOption) -> Unit,
    modifier: Modifier = Modifier,
) {
    var expanded by remember { mutableStateOf(false) }
    Box(modifier = modifier) {
        IconButton(onClick = { expanded = true }) {
            Icon(Icons.AutoMirrored.Filled.Sort, contentDescription = "Sort")
        }
        DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
            StyleSortOption.entries.forEach { option ->
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
                    onClick = { onSortChanged(option); expanded = false },
                )
            }
        }
    }
}

// ---------- Style card ----------

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun StyleCard(
    style: Style,
    itemsById: Map<String, DriveImage>,
    locations: List<Location> = emptyList(),
    isSelected: Boolean = false,
    isSelectionMode: Boolean = false,
    onEdit: () -> Unit,
    onDelete: () -> Unit,
    onWear: () -> Unit,
    onToggleSelection: () -> Unit = {},
) {
    val isOffline = LocalIsOffline.current
    var showDeleteDialog by remember { mutableStateOf(false) }
    var viewerImage by remember { mutableStateOf<DriveImage?>(null) }

    viewerImage?.let { image ->
        WardrobeItemViewer(image = image, onDismiss = { viewerImage = null })
    }

    if (showDeleteDialog) {
        AlertDialog(
            onDismissRequest = { showDeleteDialog = false },
            title = { Text(stringResource(R.string.styles_delete_title)) },
            text = { Text(stringResource(R.string.styles_delete_text, style.name)) },
            confirmButton = {
                TextButton(onClick = { onDelete(); showDeleteDialog = false }) {
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
                onClick = { if (isSelectionMode) onToggleSelection() else Unit },
                onLongClick = onToggleSelection,
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
            val styleItems = style.itemIds.mapNotNull { itemsById[it] }
            if (styleItems.isEmpty()) {
                Text(
                    stringResource(R.string.styles_missing_items),
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
                                modifier = Modifier.fillMaxSize().clickable { viewerImage = image },
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
                        if (wornToday) stringResource(R.string.styles_worn_today) else stringResource(R.string.styles_wear_today),
                        style = MaterialTheme.typography.labelSmall,
                        color = if (wornToday) MaterialTheme.colorScheme.primary
                                else MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }
    }
}

// ---------- Item picker ----------

@OptIn(ExperimentalMaterial3Api::class, ExperimentalFoundationApi::class)
@Composable
private fun StyleItemPicker(
    items: List<DriveImage>,
    selectedIds: Set<String>,
    isEditing: Boolean,
    styleName: String = "",
    styleDescription: String = "",
    onNameChanged: (String) -> Unit = {},
    onDescriptionChanged: (String) -> Unit = {},
    onToggleItem: (String) -> Unit,
    onSelectAll: (List<String>) -> Unit,
    onDeselectAll: (List<String>) -> Unit,
    onConfirm: () -> Unit,
    onCancel: () -> Unit,
    modifier: Modifier = Modifier,
) {
    BackHandler(onBack = onCancel)

    var selectedTags by remember { mutableStateOf(emptyMap<String, Set<String>>()) }
    val tagCategories = remember(items) { items.tagCategories() }
    val displayedItems = remember(items, selectedTags) {
        val activeFilters = selectedTags.filter { (_, tags) -> tags.isNotEmpty() }
        if (activeFilters.isEmpty()) items
        else items.filter { img ->
            activeFilters.all { (categoryLabel, catTags) ->
                catTags.any { it in img.tagStringsForCategory(categoryLabel) }
            }
        }
    }

    Column(modifier = modifier.fillMaxSize()) {
        // Top bar
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .statusBarsPadding()
                .padding(horizontal = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            IconButton(onClick = onCancel) {
                Icon(Icons.Default.Close, contentDescription = "Cancel")
            }
            BasicTextField(
                value = styleName,
                onValueChange = onNameChanged,
                textStyle = MaterialTheme.typography.titleMedium.copy(
                    color = MaterialTheme.colorScheme.onSurface,
                ),
                singleLine = true,
                modifier = Modifier.weight(1f).padding(horizontal = 8.dp),
                decorationBox = { inner ->
                    Box {
                        if (styleName.isEmpty()) {
                            Text(
                                stringResource(R.string.styles_name_placeholder),
                                style = MaterialTheme.typography.titleMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                        inner()
                    }
                },
            )
            TextButton(onClick = onConfirm, enabled = selectedIds.isNotEmpty()) {
                Text(if (isEditing) stringResource(R.string.action_save) else stringResource(R.string.styles_create_this))
            }
        }

        // Description field — always visible (creating or editing)
        BasicTextField(
            value = styleDescription,
            onValueChange = onDescriptionChanged,
            textStyle = MaterialTheme.typography.bodyMedium.copy(
                color = MaterialTheme.colorScheme.onSurface,
            ),
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 4.dp),
            decorationBox = { inner ->
                Box {
                    if (styleDescription.isEmpty()) {
                        Text(
                            stringResource(R.string.styles_description_placeholder),
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    inner()
                }
            },
        )

        // Reuse the shared filter bar
        TagFilterBar(
            tagCategories = tagCategories,
            selectedTags = selectedTags,
            onTagsChanged = { selectedTags = it },
        )

        // Select all / Deselect all row
        if (displayedItems.isNotEmpty()) {
            val allDisplayedSelected = displayedItems.all { it.driveId in selectedIds }
            Row(
                modifier = Modifier.fillMaxWidth().padding(horizontal = 8.dp),
                horizontalArrangement = Arrangement.End,
            ) {
                TextButton(
                    onClick = {
                        val ids = displayedItems.map { it.driveId }
                        if (allDisplayedSelected) onDeselectAll(ids) else onSelectAll(ids)
                    },
                ) {
                    Text(
                        if (allDisplayedSelected) stringResource(R.string.action_deselect_all)
                        else stringResource(R.string.action_select_all),
                    )
                }
            }
        }

        // Item grid
        if (displayedItems.isEmpty()) {
            Box(Modifier.weight(1f).fillMaxWidth(), contentAlignment = Alignment.Center) {
                Text(
                    if (selectedTags.values.any { it.isNotEmpty() }) stringResource(R.string.wardrobe_empty_filter)
                    else stringResource(R.string.wardrobe_empty),
                    style = MaterialTheme.typography.bodyMedium,
                )
            }
        } else {
            LazyVerticalGrid(
                columns = GridCells.Adaptive(120.dp),
                modifier = Modifier.weight(1f).fillMaxWidth(),
            ) {
                itemsIndexed(displayedItems, key = { _, img -> img.driveId }) { _, image ->
                    val isSelected = image.driveId in selectedIds
                    val ctx = LocalContext.current
                    Box(
                        modifier = Modifier
                            .aspectRatio(1f)
                            .padding(1.dp)
                            .clickable { onToggleItem(image.driveId) },
                    ) {
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
                        if (isSelected) {
                            Box(
                                modifier = Modifier
                                    .fillMaxSize()
                                    .background(MaterialTheme.colorScheme.primary.copy(alpha = 0.35f)),
                                contentAlignment = Alignment.TopEnd,
                            ) {
                                Icon(
                                    Icons.Default.CheckCircle,
                                    contentDescription = null,
                                    tint = MaterialTheme.colorScheme.primary,
                                    modifier = Modifier.padding(4.dp),
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}

// ---------- Style editing view ----------

@OptIn(ExperimentalMaterial3Api::class, ExperimentalFoundationApi::class, ExperimentalLayoutApi::class)
@Composable
private fun StyleEditingView(
    draftItemIds: Set<String>,
    draftStyleName: String,
    draftStyleDescription: String,
    isEditing: Boolean,
    prediction: StylePrediction?,
    newSuggestion: NewStyleSuggestion?,
    allItems: List<DriveImage>,
    isLoadingAlternatives: Boolean,
    alternativeIds: List<String>,
    refinementInput: String,
    feedbackHistory: List<String>,
    onNameChanged: (String) -> Unit,
    onDescriptionChanged: (String) -> Unit,
    onSwapItem: (oldId: String, newId: String) -> Unit,
    onRemoveItem: (String) -> Unit,
    onAddItem: (String) -> Unit,
    onSuggestAlternatives: (itemId: String) -> Unit,
    onClearAlternatives: () -> Unit,
    onConfirm: () -> Unit,
    onCancel: () -> Unit,
    onWear: (() -> Unit)?,
    onRefinementInputChange: (String) -> Unit,
    onRefinePrediction: (() -> Unit)?,
    onPresetPrediction: ((String) -> Unit)?,
    onRefineComposition: (() -> Unit)?,
    onPresetComposition: ((String) -> Unit)?,
    isRefining: Boolean = false,
    locations: List<Location> = emptyList(),
    modifier: Modifier = Modifier,
) {
    val isOffline = LocalIsOffline.current
    BackHandler(onBack = onCancel)

    val itemsById = remember(allItems) { allItems.associateBy { it.driveId } }
    // Preserve wardrobe order for consistent display
    val draftItems = remember(draftItemIds, allItems) { allItems.filter { it.driveId in draftItemIds } }

    // Item currently being swapped (null = no sheet open)
    var swappingItemId by remember { mutableStateOf<String?>(null) }
    // Item add sheet
    var showAddSheet by remember { mutableStateOf(false) }
    // Worn-today feedback
    var wornToday by remember { mutableStateOf(false) }

    // Item swap sheet
    swappingItemId?.let { itemId ->
        itemsById[itemId]?.let { targetItem ->
            ItemSwapSheet(
                targetItem = targetItem,
                allItems = allItems,
                currentStyleItemIds = draftItemIds,
                isLoadingAlternatives = isLoadingAlternatives,
                alternativeIds = alternativeIds,
                onSuggestAlternatives = { onSuggestAlternatives(itemId) },
                onSelectItem = { newId ->
                    if (newId != itemId) onSwapItem(itemId, newId)
                    swappingItemId = null
                    onClearAlternatives()
                },
                onDismiss = {
                    swappingItemId = null
                    onClearAlternatives()
                },
            )
        }
    }

    // Add item sheet
    if (showAddSheet) {
        AddItemSheet(
            candidates = allItems.filter { it.driveId !in draftItemIds },
            onSelectItem = { id -> onAddItem(id); showAddSheet = false },
            onDismiss = { showAddSheet = false },
        )
    }

    Box(modifier = modifier.fillMaxSize()) {
    Column(modifier = Modifier.fillMaxSize()) {
        // Top bar
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .statusBarsPadding()
                .padding(horizontal = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            IconButton(onClick = onCancel) {
                Icon(Icons.Default.Close, contentDescription = "Cancel")
            }
            BasicTextField(
                value = draftStyleName,
                onValueChange = onNameChanged,
                textStyle = MaterialTheme.typography.titleMedium.copy(
                    color = MaterialTheme.colorScheme.onSurface,
                ),
                singleLine = true,
                modifier = Modifier.weight(1f).padding(horizontal = 8.dp),
                decorationBox = { inner ->
                    Box {
                        if (draftStyleName.isEmpty()) {
                            Text(
                                stringResource(R.string.styles_name_placeholder),
                                style = MaterialTheme.typography.titleMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                        inner()
                    }
                },
            )
            // Wear today — only available when editing an existing saved style
            if (onWear != null) {
                IconButton(
                    onClick = { onWear(); wornToday = true },
                ) {
                    Icon(
                        Icons.Default.CalendarMonth,
                        contentDescription = stringResource(R.string.styles_wear_today),
                        tint = if (wornToday) MaterialTheme.colorScheme.primary
                               else MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
            TextButton(
                onClick = onConfirm,
                enabled = draftItemIds.isNotEmpty(),
            ) {
                Text(if (isEditing) stringResource(R.string.action_save) else stringResource(R.string.styles_create_this))
            }
        }
        HorizontalDivider()

        // Scrollable content
        Column(
            modifier = Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 16.dp)
                .navigationBarsPadding()
                .imePadding(),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Spacer(Modifier.height(4.dp))

            // Description field
            BasicTextField(
                value = draftStyleDescription,
                onValueChange = onDescriptionChanged,
                textStyle = MaterialTheme.typography.bodyMedium.copy(
                    color = MaterialTheme.colorScheme.onSurface,
                ),
                modifier = Modifier.fillMaxWidth(),
                decorationBox = { inner ->
                    Box {
                        if (draftStyleDescription.isEmpty()) {
                            Text(
                                stringResource(R.string.styles_description_placeholder),
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                        inner()
                    }
                },
            )

            // AI reason (shown when opened from a Gemini suggestion)
            val reason = prediction?.reason ?: newSuggestion?.reason
            if (reason != null && reason.isNotBlank()) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    Icon(
                        if (newSuggestion != null) Icons.Default.AutoFixHigh else Icons.Default.AutoAwesome,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.size(16.dp),
                    )
                    Text(
                        reason,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }

            HorizontalDivider()

            Text(
                stringResource(R.string.styles_items_label),
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.primary,
            )

            // Outfit items — tappable for swap, with remove button
            FlowRow(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                draftItems.forEach { image ->
                    val locName = remember(image.folderId, locations) {
                        if (locations.size > 1)
                            locations.find { it.folderId == image.folderId }?.name
                        else null
                    }
                    StyleEditItemSlot(
                        image = image,
                        locationName = locName,
                        onTap = { swappingItemId = image.driveId },
                        onRemove = { onRemoveItem(image.driveId) },
                    )
                }
                // "+" slot to add a new item
                Box(
                    modifier = Modifier
                        .size(100.dp)
                        .clip(MaterialTheme.shapes.small)
                        .background(MaterialTheme.colorScheme.surfaceVariant)
                        .clickable { showAddSheet = true },
                    contentAlignment = Alignment.Center,
                ) {
                    Column(
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.spacedBy(2.dp),
                    ) {
                        Icon(
                            Icons.Default.Add,
                            contentDescription = stringResource(R.string.styles_add_item),
                            tint = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        Text(
                            stringResource(R.string.styles_add_item),
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            }

            // Refinement section — only shown when opened from an AI suggestion (hidden offline)
            if (!isOffline && (prediction != null || newSuggestion != null)) {
                RefinementSection(
                    input = refinementInput,
                    feedbackHistory = feedbackHistory,
                    onInputChange = onRefinementInputChange,
                    onSubmitFreetext = {
                        if (prediction != null) onRefinePrediction?.invoke()
                        else onRefineComposition?.invoke()
                    },
                    onSubmitPreset = { preset ->
                        if (prediction != null) onPresetPrediction?.invoke(preset)
                        else onPresetComposition?.invoke(preset)
                    },
                )
            }

            Spacer(Modifier.height(16.dp))
        }
    }

    // AI-thinking overlay during refinement (sits on top of the whole view)
    if (isRefining) {
        AiProcessingOverlay(
            label = if (prediction != null) stringResource(R.string.ai_suggesting_style)
                    else stringResource(R.string.ai_composing_outfit),
            modifier = Modifier.fillMaxSize(),
        )
    }
    } // end Box
}

@Composable
private fun StyleEditItemSlot(
    image: DriveImage,
    locationName: String? = null,
    onTap: () -> Unit,
    onRemove: () -> Unit,
) {
    val ctx = LocalContext.current
    Box(
        modifier = Modifier
            .size(100.dp)
            .clip(MaterialTheme.shapes.small)
            .clickable { onTap() },
    ) {
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
        // Label strip at bottom
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .align(Alignment.BottomCenter)
                .background(Color.Black.copy(alpha = 0.45f)),
        ) {
            Text(
                text = image.tags?.label?.take(16) ?: image.name.take(16),
                style = MaterialTheme.typography.labelSmall,
                color = Color.White,
                maxLines = 1,
                modifier = Modifier.padding(horizontal = 4.dp, vertical = 2.dp),
            )
        }
        // Top-right: remove button + optional location badge stacked vertically
        Column(
            modifier = Modifier.align(Alignment.TopEnd).padding(2.dp),
            horizontalAlignment = Alignment.End,
            verticalArrangement = Arrangement.spacedBy(2.dp),
        ) {
            SmallFloatingActionButton(
                onClick = onRemove,
                modifier = Modifier.size(20.dp),
                containerColor = MaterialTheme.colorScheme.errorContainer,
                elevation = FloatingActionButtonDefaults.elevation(defaultElevation = 0.dp),
            ) {
                Icon(Icons.Default.Close, contentDescription = "Remove", modifier = Modifier.size(12.dp))
            }
            if (locationName != null) {
                Box(
                    modifier = Modifier
                        .background(
                            color = MaterialTheme.colorScheme.secondaryContainer.copy(alpha = 0.85f),
                            shape = MaterialTheme.shapes.extraSmall,
                        )
                        .padding(horizontal = 3.dp, vertical = 1.dp),
                ) {
                    Text(
                        text = locationName,
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

// ---------- Item swap sheet ----------

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ItemSwapSheet(
    targetItem: DriveImage,
    allItems: List<DriveImage>,
    currentStyleItemIds: Set<String>,
    isLoadingAlternatives: Boolean,
    alternativeIds: List<String>,
    onSuggestAlternatives: () -> Unit,
    onSelectItem: (String) -> Unit,
    onDismiss: () -> Unit,
) {
    val isOffline = LocalIsOffline.current
    val ctx = LocalContext.current
    val targetCategory = targetItem.tags?.category

    // Pre-filter by same category; user can change the filter
    var selectedTags by remember(targetCategory) {
        mutableStateOf(
            if (targetCategory != null) mapOf("Category" to setOf(targetCategory))
            else emptyMap<String, Set<String>>()
        )
    }
    var selectedId by remember { mutableStateOf(targetItem.driveId) }

    val tagCategories = remember(allItems) { allItems.tagCategories() }

    val altSet = remember(alternativeIds) { alternativeIds.toSet() }
    val candidateItems = remember(allItems, selectedTags, altSet) {
        val activeFilters = selectedTags.filter { (_, tags) -> tags.isNotEmpty() }
        val filtered = if (activeFilters.isEmpty()) allItems
        else allItems.filter { img ->
            activeFilters.all { (categoryLabel, catTags) ->
                catTags.any { it in img.tagStringsForCategory(categoryLabel) }
            }
        }
        // Sort: AI alternatives first, then current item, then rest
        filtered.sortedWith(
            compareByDescending<DriveImage> { it.driveId in altSet }
                .thenByDescending { it.driveId == targetItem.driveId }
        )
    }

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .navigationBarsPadding(),
        ) {
            // Header row
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(start = 16.dp, end = 8.dp, top = 4.dp, bottom = 4.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    stringResource(R.string.styles_swap_item_for, targetItem.tags?.label ?: targetItem.name),
                    style = MaterialTheme.typography.titleSmall,
                    modifier = Modifier.weight(1f),
                )
                if (isLoadingAlternatives) {
                    CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp)
                    Spacer(Modifier.width(12.dp))
                } else if (!isOffline) {
                    TextButton(onClick = onSuggestAlternatives) {
                        Icon(Icons.Default.AutoAwesome, null, modifier = Modifier.size(14.dp))
                        Spacer(Modifier.width(4.dp))
                        Text(stringResource(R.string.styles_suggest_alternatives), style = MaterialTheme.typography.labelSmall)
                    }
                }
            }

            TagFilterBar(
                tagCategories = tagCategories,
                selectedTags = selectedTags,
                onTagsChanged = { selectedTags = it },
            )

            LazyVerticalGrid(
                columns = GridCells.Adaptive(96.dp),
                modifier = Modifier
                    .fillMaxWidth()
                    .height(320.dp),
                contentPadding = PaddingValues(4.dp),
            ) {
                itemsIndexed(candidateItems, key = { _, img -> img.driveId }) { _, image ->
                    val isSelected = image.driveId == selectedId
                    val isCurrent = image.driveId == targetItem.driveId
                    val isAiSuggested = image.driveId in altSet
                    Box(
                        modifier = Modifier
                            .aspectRatio(1f)
                            .padding(2.dp)
                            .clip(MaterialTheme.shapes.extraSmall)
                            .clickable { selectedId = image.driveId },
                    ) {
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
                        if (isSelected) {
                            Box(
                                modifier = Modifier
                                    .fillMaxSize()
                                    .background(MaterialTheme.colorScheme.primary.copy(alpha = 0.35f)),
                                contentAlignment = Alignment.TopEnd,
                            ) {
                                Icon(
                                    Icons.Default.CheckCircle,
                                    contentDescription = null,
                                    tint = MaterialTheme.colorScheme.primary,
                                    modifier = Modifier.padding(4.dp).size(18.dp),
                                )
                            }
                        }
                        // AI suggestion badge
                        if (isAiSuggested && !isSelected) {
                            Icon(
                                Icons.Default.AutoAwesome,
                                contentDescription = "AI suggestion",
                                tint = MaterialTheme.colorScheme.tertiary,
                                modifier = Modifier
                                    .align(Alignment.TopStart)
                                    .padding(3.dp)
                                    .size(14.dp),
                            )
                        }
                        // "current" label for the item being replaced
                        if (isCurrent) {
                            Box(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .align(Alignment.BottomCenter)
                                    .background(Color.Black.copy(alpha = 0.45f)),
                                contentAlignment = Alignment.Center,
                            ) {
                                Text(
                                    "current",
                                    style = MaterialTheme.typography.labelSmall,
                                    color = Color.White,
                                    modifier = Modifier.padding(vertical = 2.dp),
                                )
                            }
                        }
                    }
                }
            }

            // Action buttons
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 8.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.End),
            ) {
                TextButton(onClick = onDismiss) { Text(stringResource(R.string.action_cancel)) }
                Button(
                    onClick = { onSelectItem(selectedId) },
                    enabled = selectedId != targetItem.driveId,
                ) {
                    Text(stringResource(R.string.styles_select_item))
                }
            }
        }
    }
}

// ---------- Add item sheet ----------

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun AddItemSheet(
    candidates: List<DriveImage>,
    onSelectItem: (String) -> Unit,
    onDismiss: () -> Unit,
) {
    val ctx = LocalContext.current
    var selectedTags by remember { mutableStateOf(emptyMap<String, Set<String>>()) }
    val tagCategories = remember(candidates) { candidates.tagCategories() }

    val filteredItems = remember(candidates, selectedTags) {
        val activeFilters = selectedTags.filter { (_, tags) -> tags.isNotEmpty() }
        if (activeFilters.isEmpty()) candidates
        else candidates.filter { img ->
            activeFilters.all { (categoryLabel, catTags) ->
                catTags.any { it in img.tagStringsForCategory(categoryLabel) }
            }
        }
    }

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .navigationBarsPadding(),
        ) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(start = 16.dp, end = 8.dp, top = 4.dp, bottom = 4.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    stringResource(R.string.styles_add_item),
                    style = MaterialTheme.typography.titleSmall,
                    modifier = Modifier.weight(1f),
                )
            }

            TagFilterBar(
                tagCategories = tagCategories,
                selectedTags = selectedTags,
                onTagsChanged = { selectedTags = it },
            )

            LazyVerticalGrid(
                columns = GridCells.Adaptive(96.dp),
                modifier = Modifier
                    .fillMaxWidth()
                    .height(320.dp),
                contentPadding = PaddingValues(4.dp),
            ) {
                itemsIndexed(filteredItems, key = { _, img -> img.driveId }) { _, image ->
                    Box(
                        modifier = Modifier
                            .aspectRatio(1f)
                            .padding(2.dp)
                            .clip(MaterialTheme.shapes.extraSmall)
                            .clickable { onSelectItem(image.driveId) },
                    ) {
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
                    }
                }
            }

            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 8.dp),
                horizontalArrangement = Arrangement.End,
            ) {
                TextButton(onClick = onDismiss) { Text(stringResource(R.string.action_cancel)) }
            }
        }
    }
}

// ---------- Style suggestion sheet ----------

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun StyleSuggestionSheet(
    style: Style,
    reason: String,
    itemsById: Map<String, DriveImage>,
    refinementInput: String,
    feedbackHistory: List<String>,
    onDismiss: () -> Unit,
    onWear: () -> Unit,
    onRefinementInputChange: (String) -> Unit,
    onRefine: () -> Unit,
    onPreset: (String) -> Unit,
) {
    val ctx = LocalContext.current
    val styleItems = style.itemIds.mapNotNull { itemsById[it] }

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .navigationBarsPadding()
                .imePadding()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 20.dp)
                .padding(bottom = 24.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(
                    Icons.Default.AutoAwesome,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.size(20.dp),
                )
                Spacer(Modifier.width(8.dp))
                Text(stringResource(R.string.styles_suggested_title), style = MaterialTheme.typography.titleMedium)
            }

            HorizontalDivider()

            Text(style.name, style = MaterialTheme.typography.titleSmall)

            if (styleItems.isEmpty()) {
                Text(
                    stringResource(R.string.styles_missing_items),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.outline,
                )
            } else {
                LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    items(styleItems, key = { it.driveId }) { image ->
                        AsyncImage(
                            model = remember(image.driveId, image.version) {
                                ImageRequest.Builder(ctx)
                                    .data(image.localPath)
                                    .memoryCacheKey("${image.driveId}_${image.version}")
                                    .build()
                            },
                            contentDescription = image.name,
                            modifier = Modifier
                                .size(88.dp)
                                .clip(MaterialTheme.shapes.small),
                            contentScale = ContentScale.Crop,
                        )
                    }
                }
            }

            if (reason.isNotBlank()) {
                Text(
                    reason,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }

            RefinementSection(
                input = refinementInput,
                feedbackHistory = feedbackHistory,
                onInputChange = onRefinementInputChange,
                onSubmitFreetext = onRefine,
                onSubmitPreset = onPreset,
            )

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.End),
            ) {
                TextButton(onClick = onDismiss) { Text(stringResource(R.string.action_dismiss)) }
                androidx.compose.material3.Button(onClick = onWear) {
                    Icon(Icons.Default.CalendarMonth, null, modifier = Modifier.size(16.dp))
                    Spacer(Modifier.width(4.dp))
                    Text(stringResource(R.string.styles_wear_today))
                }
            }
        }
    }
}

// ---------- New style composition sheet ----------

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun NewStyleSuggestionSheet(
    suggestion: NewStyleSuggestion,
    itemsById: Map<String, DriveImage>,
    refinementInput: String,
    feedbackHistory: List<String>,
    onDismiss: () -> Unit,
    onAccept: () -> Unit,
    onRefinementInputChange: (String) -> Unit,
    onRefine: () -> Unit,
    onPreset: (String) -> Unit,
) {
    val ctx = LocalContext.current
    val styleItems = suggestion.itemIds.mapNotNull { itemsById[it] }

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .navigationBarsPadding()
                .imePadding()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 20.dp)
                .padding(bottom = 24.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(
                    Icons.Default.AutoFixHigh,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.size(20.dp),
                )
                Spacer(Modifier.width(8.dp))
                Text(stringResource(R.string.styles_composed_title), style = MaterialTheme.typography.titleMedium)
            }

            HorizontalDivider()

            Text(suggestion.name, style = MaterialTheme.typography.titleSmall)

            if (suggestion.description.isNotBlank()) {
                Text(
                    suggestion.description,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }

            if (styleItems.isEmpty()) {
                Text(
                    stringResource(R.string.styles_missing_items),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.outline,
                )
            } else {
                LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    items(styleItems, key = { it.driveId }) { image ->
                        AsyncImage(
                            model = remember(image.driveId, image.version) {
                                ImageRequest.Builder(ctx)
                                    .data(image.localPath)
                                    .memoryCacheKey("${image.driveId}_${image.version}")
                                    .build()
                            },
                            contentDescription = image.name,
                            modifier = Modifier
                                .size(88.dp)
                                .clip(MaterialTheme.shapes.small),
                            contentScale = ContentScale.Crop,
                        )
                    }
                }
            }

            if (suggestion.reason.isNotBlank()) {
                Text(
                    suggestion.reason,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }

            RefinementSection(
                input = refinementInput,
                feedbackHistory = feedbackHistory,
                onInputChange = onRefinementInputChange,
                onSubmitFreetext = onRefine,
                onSubmitPreset = onPreset,
            )

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.End),
            ) {
                TextButton(onClick = onDismiss) { Text(stringResource(R.string.action_dismiss)) }
                androidx.compose.material3.Button(onClick = onAccept) {
                    Icon(Icons.Default.Add, null, modifier = Modifier.size(16.dp))
                    Spacer(Modifier.width(4.dp))
                    Text(stringResource(R.string.styles_create_this))
                }
            }
        }
    }
}

// ---------- Refinement section ----------

@Composable
private fun refinementPresets() = listOf(
    stringResource(R.string.styles_refine_casual),
    stringResource(R.string.styles_refine_formal),
    stringResource(R.string.styles_refine_diff_colors),
    stringResource(R.string.styles_refine_warmer),
    stringResource(R.string.styles_refine_lighter),
    stringResource(R.string.styles_refine_trendy),
    stringResource(R.string.styles_refine_simpler),
    stringResource(R.string.styles_refine_bold),
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
            stringResource(R.string.styles_refine_label),
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
                placeholder = { Text(stringResource(R.string.styles_refine_custom), style = MaterialTheme.typography.bodySmall) },
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
                Icon(Icons.AutoMirrored.Filled.Send, contentDescription = "Refine")
            }
        }
    }
}

// ---------- Name dialog ----------

@Composable
private fun StyleNameDialog(
    initialName: String,
    onConfirm: (String) -> Unit,
    onDismiss: () -> Unit,
) {
    var name by remember { mutableStateOf(initialName) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.styles_name_dialog_title)) },
        text = {
            OutlinedTextField(
                value = name,
                onValueChange = { name = it },
                label = { Text(stringResource(R.string.styles_name_placeholder)) },
                singleLine = true,
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
                keyboardActions = KeyboardActions(onDone = { if (name.isNotBlank()) onConfirm(name) }),
            )
        },
        confirmButton = {
            TextButton(onClick = { onConfirm(name) }) { Text(stringResource(R.string.action_save)) }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text(stringResource(R.string.action_cancel)) }
        },
    )
}

// ---------- Read-only wardrobe item viewer (opened from style cards) ----------

@Composable
private fun WardrobeItemViewer(image: DriveImage, onDismiss: () -> Unit) {
    BackHandler(onBack = onDismiss)

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Color.Black),
    ) {
        StyleZoomableImage(
            localPath = image.localPath,
            name = image.name,
            cacheKey = "${image.driveId}_${image.version}",
        )

        image.tags?.let { tags ->
            StyleTagsOverlay(
                tags = tags,
                modifier = Modifier
                    .align(Alignment.TopEnd)
                    .statusBarsPadding()
                    .padding(top = 8.dp, end = 8.dp),
            )
        }

        IconButton(
            onClick = onDismiss,
            modifier = Modifier.align(Alignment.TopStart).statusBarsPadding().padding(8.dp),
        ) {
            Icon(Icons.Default.Close, contentDescription = null, tint = Color.White)
        }
    }
}

@Composable
private fun StyleZoomableImage(localPath: String, name: String, cacheKey: String) {
    var scale by remember { mutableFloatStateOf(1f) }
    var offset by remember { mutableStateOf(Offset.Zero) }
    val ctx = LocalContext.current

    AsyncImage(
        model = remember(cacheKey) {
            ImageRequest.Builder(ctx).data(localPath).memoryCacheKey(cacheKey).build()
        },
        contentDescription = name,
        modifier = Modifier
            .fillMaxSize()
            .pointerInput(Unit) {
                awaitEachGesture {
                    awaitFirstDown(requireUnconsumed = false, pass = PointerEventPass.Initial)
                    var prevDistance = -1f
                    do {
                        val event = awaitPointerEvent(PointerEventPass.Initial)
                        val pressed = event.changes.filter { it.pressed }
                        when {
                            pressed.size >= 2 -> {
                                val dist = (pressed[1].position - pressed[0].position).getDistance()
                                if (prevDistance > 0f) {
                                    val focal = Offset(
                                        (pressed[0].position.x + pressed[1].position.x) / 2f,
                                        (pressed[0].position.y + pressed[1].position.y) / 2f,
                                    )
                                    val newScale = (scale * (dist / prevDistance)).coerceIn(1f, 8f)
                                    val delta = newScale / scale
                                    val cx = size.width / 2f
                                    val cy = size.height / 2f
                                    offset = Offset(
                                        (focal.x - cx) * (1f - delta) + offset.x * delta,
                                        (focal.y - cy) * (1f - delta) + offset.y * delta,
                                    )
                                    scale = newScale
                                    if (scale <= 1f) offset = Offset.Zero
                                }
                                prevDistance = dist
                                pressed.forEach { it.consume() }
                            }
                            pressed.size == 1 && scale > 1.01f -> {
                                val delta = pressed[0].position - pressed[0].previousPosition
                                offset = Offset(offset.x + delta.x, offset.y + delta.y)
                                pressed[0].consume()
                                prevDistance = -1f
                            }
                            else -> prevDistance = -1f
                        }
                    } while (event.changes.any { it.pressed })
                }
            }
            .graphicsLayer {
                scaleX = scale
                scaleY = scale
                translationX = offset.x
                translationY = offset.y
            },
        contentScale = ContentScale.Fit,
    )
}

@Composable
private fun StyleTagsOverlay(tags: ClothingTags, modifier: Modifier = Modifier) {
    Surface(
        modifier = modifier,
        shape = MaterialTheme.shapes.medium,
        color = Color.Black.copy(alpha = 0.55f),
    ) {
        Column(
            modifier = Modifier.padding(horizontal = 10.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp),
            horizontalAlignment = Alignment.End,
        ) {
            Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                if (tags.type.isNotEmpty()) StyleTagChip(tags.type)
                if (tags.category.isNotEmpty()) StyleTagChip(tags.category.localizedTagValue())
            }
            if (tags.uses.isNotEmpty()) {
                Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                    tags.uses.forEach { StyleTagChip(it.localizedTagValue()) }
                }
            }
            if (tags.colors.isNotEmpty()) {
                Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                    tags.colors.forEach { StyleTagChip(it.localizedTagValue()) }
                }
            }
            if (tags.seasonality.isNotEmpty()) {
                Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                    tags.seasonality.forEach { StyleTagChip(it.localizedTagValue()) }
                }
            }
            if (tags.aesthetic.isNotEmpty()) {
                Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                    tags.aesthetic.forEach { StyleTagChip(it.localizedTagValue()) }
                }
            }
            if (tags.fit.isNotEmpty()) {
                Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                    tags.fit.forEach { StyleTagChip(it.localizedTagValue()) }
                }
            }
            if (tags.material.isNotEmpty()) {
                Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                    tags.material.forEach { StyleTagChip(it.localizedTagValue()) }
                }
            }
            if (tags.pattern.isNotEmpty()) {
                Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                    tags.pattern.forEach { StyleTagChip(it.localizedTagValue()) }
                }
            }
        }
    }
}

@Composable
private fun StyleTagChip(label: String) {
    Surface(
        shape = MaterialTheme.shapes.extraSmall,
        color = Color.White.copy(alpha = 0.18f),
    ) {
        Text(
            text = label,
            modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp),
            style = MaterialTheme.typography.labelSmall,
            color = Color.White,
        )
    }
}
