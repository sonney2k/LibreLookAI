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
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.statusBarsPadding
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
import androidx.compose.material.icons.filled.Send
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.AutoFixHigh
import androidx.compose.material.icons.filled.CalendarMonth
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Sort
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.InputChip
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.SmallFloatingActionButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.SuggestionChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Snackbar
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import coil.compose.AsyncImage
import coil.request.ImageRequest

private enum class StyleSortOption(val label: String) {
    DATE_DESC("Newest first"),
    DATE_ASC("Oldest first"),
    NAME_AZ("Name A–Z"),
    NAME_ZA("Name Z–A"),
    ITEM_COUNT("Most items"),
}

private fun List<Style>.styleTagCategories(itemsById: Map<String, DriveImage>): List<TagCategory> {
    val allImages = flatMap { style -> style.itemIds.mapNotNull { itemsById[it] } }
    return allImages.tagCategories()
}

private fun Style.allTagStrings(itemsById: Map<String, DriveImage>): Set<String> =
    itemIds.flatMap { id -> itemsById[id]?.allTagStrings() ?: emptySet() }.toSet()

@OptIn(ExperimentalMaterial3Api::class, ExperimentalFoundationApi::class)
@Composable
fun StylesScreen(
    stylesViewModel: StylesViewModel = viewModel(),
    wardrobeViewModel: WardrobeViewModel = viewModel(),
    outfitsViewModel: OutfitsViewModel = viewModel(),
    profileViewModel: ProfileViewModel = viewModel(),
    weatherViewModel: WeatherViewModel = viewModel(),
    modifier: Modifier = Modifier,
) {
    val stylesState by stylesViewModel.state.collectAsState()
    val wardrobeState by wardrobeViewModel.state.collectAsState()
    val profileState by profileViewModel.state.collectAsState()
    val weatherState by weatherViewModel.state.collectAsState()

    if (stylesState.isCreating) {
        StyleItemPicker(
            items = wardrobeState.images,
            selectedIds = stylesState.draftItemIds,
            isEditing = stylesState.editingStyle != null,
            styleName = stylesState.draftStyleName,
            styleDescription = stylesState.draftStyleDescription,
            onNameChanged = stylesViewModel::updateDraftName,
            onDescriptionChanged = stylesViewModel::updateDraftDescription,
            onToggleItem = stylesViewModel::toggleDraftItem,
            onConfirm = stylesViewModel::confirmDraft,
            onCancel = stylesViewModel::cancelCreating,
            modifier = modifier,
        )
    } else {
        StyleListScreen(
            styles = stylesState.styles,
            items = wardrobeState.images,
            isLoading = stylesState.isLoading,
            isPredicting = stylesState.isPredicting,
            prediction = stylesState.prediction,
            predictionError = stylesState.predictionError,
            isComposing = stylesState.isComposing,
            newSuggestion = stylesState.newSuggestion,
            compositionError = stylesState.compositionError,
            refinementInput = stylesState.refinementInput,
            feedbackHistory = stylesState.feedbackHistory,
            onCreateStyle = stylesViewModel::startCreating,
            onEditStyle = stylesViewModel::startEditing,
            onDeleteStyle = stylesViewModel::deleteStyle,
            onWearStyle = outfitsViewModel::recordOutfit,
            onSuggestStyle = {
                stylesViewModel.triggerPrediction(
                    prefs   = profileState.preferences,
                    weather = weatherState.data,
                    images  = wardrobeState.images,
                )
            },
            onClearPrediction = stylesViewModel::clearPrediction,
            onComposeStyle = {
                stylesViewModel.triggerComposition(
                    prefs   = profileState.preferences,
                    weather = weatherState.data,
                    images  = wardrobeState.images,
                )
            },
            onAcceptComposition = { suggestion ->
                stylesViewModel.startCreatingFromItems(
                    itemIds     = suggestion.itemIds.toSet(),
                    name        = suggestion.name,
                    description = suggestion.description,
                )
                stylesViewModel.clearNewSuggestion()
            },
            onClearComposition = stylesViewModel::clearNewSuggestion,
            onRefinementInputChange = stylesViewModel::updateRefinementInput,
            onRefinePrediction = {
                stylesViewModel.refinePrediction(profileState.preferences, weatherState.data, wardrobeState.images)
            },
            onPresetPrediction = { preset ->
                stylesViewModel.submitPresetPrediction(preset, profileState.preferences, weatherState.data, wardrobeState.images)
            },
            onRefineComposition = {
                stylesViewModel.refineComposition(profileState.preferences, weatherState.data, wardrobeState.images)
            },
            onPresetComposition = { preset ->
                stylesViewModel.submitPresetComposition(preset, profileState.preferences, weatherState.data, wardrobeState.images)
            },
            modifier = modifier,
        )
    }

}

// ---------- Style list ----------

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun StyleListScreen(
    styles: List<Style>,
    items: List<DriveImage>,
    isLoading: Boolean,
    isPredicting: Boolean,
    prediction: StylePrediction?,
    predictionError: String?,
    isComposing: Boolean,
    newSuggestion: NewStyleSuggestion?,
    compositionError: String?,
    refinementInput: String,
    feedbackHistory: List<String>,
    onCreateStyle: () -> Unit,
    onEditStyle: (Style) -> Unit,
    onDeleteStyle: (String) -> Unit,
    onWearStyle: (String) -> Unit,
    onSuggestStyle: () -> Unit,
    onClearPrediction: () -> Unit,
    onComposeStyle: () -> Unit,
    onAcceptComposition: (NewStyleSuggestion) -> Unit,
    onClearComposition: () -> Unit,
    onRefinementInputChange: (String) -> Unit,
    onRefinePrediction: () -> Unit,
    onPresetPrediction: (String) -> Unit,
    onRefineComposition: () -> Unit,
    onPresetComposition: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    val itemsById = remember(items) { items.associateBy { it.driveId } }

    var selectedTags by remember { mutableStateOf(emptySet<String>()) }
    var sortBy by remember { mutableStateOf(StyleSortOption.DATE_DESC) }

    val tagCategories = remember(styles, itemsById) { styles.styleTagCategories(itemsById) }

    val filteredStyles = remember(styles, selectedTags, itemsById) {
        if (selectedTags.isEmpty()) styles
        else styles.filter { style -> selectedTags.all { it in style.allTagStrings(itemsById) } }
    }

    val displayedStyles = remember(filteredStyles, sortBy) {
        when (sortBy) {
            StyleSortOption.DATE_DESC  -> filteredStyles
            StyleSortOption.DATE_ASC   -> filteredStyles.reversed()
            StyleSortOption.NAME_AZ    -> filteredStyles.sortedBy { it.name.lowercase() }
            StyleSortOption.NAME_ZA    -> filteredStyles.sortedByDescending { it.name.lowercase() }
            StyleSortOption.ITEM_COUNT -> filteredStyles.sortedByDescending { it.itemIds.size }
        }
    }

    Box(modifier = modifier.fillMaxSize()) {
        Column(Modifier.fillMaxSize()) {
            // Filter + sort bar (only when there's something to show)
            if (styles.isNotEmpty()) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    TagFilterBar(
                        tagCategories = tagCategories,
                        selectedTags = selectedTags,
                        onTagsChanged = { selectedTags = it },
                        modifier = Modifier.weight(1f),
                    )
                    StyleSortButton(
                        sortBy = sortBy,
                        onSortChanged = { sortBy = it },
                        modifier = Modifier.padding(end = 4.dp),
                    )
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
                            Text("No styles yet", style = MaterialTheme.typography.bodyLarge)
                            Spacer(Modifier.height(4.dp))
                            Text(
                                "Tap + to build an outfit from your wardrobe",
                                style = MaterialTheme.typography.bodyMedium,
                            )
                        }
                    }
                }
                displayedStyles.isEmpty() -> {
                    Box(Modifier.weight(1f).fillMaxWidth(), contentAlignment = Alignment.Center) {
                        Text("No styles match the filter", style = MaterialTheme.typography.bodyLarge)
                    }
                }
                else -> {
                    LazyColumn(
                        modifier = Modifier.weight(1f).fillMaxWidth(),
                        contentPadding = PaddingValues(vertical = 8.dp, horizontal = 0.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        itemsIndexed(displayedStyles, key = { _, s -> s.id }) { _, style ->
                            StyleCard(
                                style = style,
                                itemsById = itemsById,
                                highlighted = prediction?.styleId == style.id,
                                onEdit = { onEditStyle(style) },
                                onDelete = { onDeleteStyle(style.id) },
                                onWear = { onWearStyle(style.id) },
                            )
                        }
                    }
                }
            }
        }

        // Speed-dial FAB (bottom-end)
        var fabExpanded by remember { mutableStateOf(false) }
        Column(
            modifier = Modifier
                .align(Alignment.BottomEnd)
                .padding(end = 16.dp, bottom = 16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
            horizontalAlignment = Alignment.End,
        ) {
            // Sub-actions — slide up / fade in when expanded
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
                        label = "Create outfit manually",
                        icon = { Icon(Icons.Default.Edit, contentDescription = null) },
                        containerColor = MaterialTheme.colorScheme.primaryContainer,
                        onClick = { fabExpanded = false; onCreateStyle() },
                    )
                    SpeedDialItem(
                        label = if (isPredicting) "Thinking…" else "Suggest existing style",
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
                        label = if (isComposing) "Thinking…" else "Compose new style",
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

            // Main toggle FAB
            FloatingActionButton(onClick = { fabExpanded = !fabExpanded }) {
                Icon(
                    if (fabExpanded) Icons.Default.Close else Icons.Default.Add,
                    contentDescription = if (fabExpanded) "Close" else "Actions",
                )
            }
        }

        // AI progress overlay — covers the whole screen while Gemini is working
        if (isPredicting || isComposing) {
            AiProcessingOverlay(
                label = if (isComposing) "Composing outfit…" else "Suggesting style…",
                modifier = Modifier.fillMaxSize(),
            )
        }

        // Error snackbars (composition takes priority if both are set)
        val errorMsg = compositionError ?: predictionError
        val onClearError = if (compositionError != null) onClearComposition else onClearPrediction
        errorMsg?.let { msg ->
            Snackbar(
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .padding(start = 8.dp, end = 8.dp, bottom = 80.dp),
                action = { TextButton(onClick = onClearError) { Text("OK") } },
            ) { Text(msg) }
        }
    }

    // Prediction result sheet
    prediction?.let { pred ->
        val suggestedStyle = styles.find { it.id == pred.styleId }
        if (suggestedStyle != null) {
            StyleSuggestionSheet(
                style = suggestedStyle,
                reason = pred.reason,
                itemsById = itemsById,
                refinementInput = refinementInput,
                feedbackHistory = feedbackHistory,
                onDismiss = onClearPrediction,
                onWear = { onWearStyle(suggestedStyle.id); onClearPrediction() },
                onRefinementInputChange = onRefinementInputChange,
                onRefine = onRefinePrediction,
                onPreset = onPresetPrediction,
            )
        }
    }

    // Composition result sheet
    newSuggestion?.let { suggestion ->
        NewStyleSuggestionSheet(
            suggestion = suggestion,
            itemsById = itemsById,
            refinementInput = refinementInput,
            feedbackHistory = feedbackHistory,
            onDismiss = onClearComposition,
            onAccept = { onAcceptComposition(suggestion) },
            onRefinementInputChange = onRefinementInputChange,
            onRefine = onRefineComposition,
            onPreset = onPresetComposition,
        )
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
            Icon(Icons.Default.Sort, contentDescription = "Sort")
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
                            Text(option.label)
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
    highlighted: Boolean = false,
    onEdit: () -> Unit,
    onDelete: () -> Unit,
    onWear: () -> Unit,
) {
    var showDeleteDialog by remember { mutableStateOf(false) }

    if (showDeleteDialog) {
        AlertDialog(
            onDismissRequest = { showDeleteDialog = false },
            title = { Text("Delete style?") },
            text = { Text("\"${style.name}\" will be permanently removed.") },
            confirmButton = {
                TextButton(onClick = { onDelete(); showDeleteDialog = false }) {
                    Text("Delete", color = MaterialTheme.colorScheme.error)
                }
            },
            dismissButton = {
                TextButton(onClick = { showDeleteDialog = false }) { Text("Cancel") }
            },
        )
    }

    Card(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 12.dp)
            .combinedClickable(onClick = onEdit, onLongClick = { showDeleteDialog = true }),
        border = if (highlighted)
            androidx.compose.foundation.BorderStroke(2.dp, MaterialTheme.colorScheme.primary)
        else null,
    ) {
        Column(
            modifier = Modifier.padding(12.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Text(style.name, style = MaterialTheme.typography.titleSmall)
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
                    "Items no longer in wardrobe",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.outline,
                )
            } else {
                LazyRow(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                    items(styleItems, key = { it.driveId }) { image ->
                        val ctx = LocalContext.current
                        AsyncImage(
                            model = remember(image.driveId, image.version) {
                                ImageRequest.Builder(ctx)
                                    .data(image.localPath)
                                    .memoryCacheKey("${image.driveId}_${image.version}")
                                    .build()
                            },
                            contentDescription = image.name,
                            modifier = Modifier.size(72.dp),
                            contentScale = ContentScale.Crop,
                        )
                    }
                }
            }
            // "Wear today" action — sits inside the card to avoid conflicting with the card's onClick
            var wornToday by remember { mutableStateOf(false) }
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.End,
            ) {
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
                        if (wornToday) "Worn today!" else "Wear today",
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
    onConfirm: () -> Unit,
    onCancel: () -> Unit,
    modifier: Modifier = Modifier,
) {
    BackHandler(onBack = onCancel)

    var selectedTags by remember { mutableStateOf(emptySet<String>()) }
    val tagCategories = remember(items) { items.tagCategories() }
    val displayedItems = remember(items, selectedTags) {
        if (selectedTags.isEmpty()) items
        else items.filter { img -> selectedTags.all { it in img.allTagStrings() } }
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
                                "Style name",
                                style = MaterialTheme.typography.titleMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                        inner()
                    }
                },
            )
            TextButton(onClick = onConfirm, enabled = selectedIds.isNotEmpty()) {
                Text(if (isEditing) "Save" else "Create")
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
                            "Description (optional)",
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

        // Item grid
        if (displayedItems.isEmpty()) {
            Box(Modifier.weight(1f).fillMaxWidth(), contentAlignment = Alignment.Center) {
                Text(
                    if (selectedTags.isNotEmpty()) "No items match the filter"
                    else "No wardrobe items yet",
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
                Text("Suggested for today", style = MaterialTheme.typography.titleMedium)
            }

            HorizontalDivider()

            Text(style.name, style = MaterialTheme.typography.titleSmall)

            if (styleItems.isEmpty()) {
                Text(
                    "Items no longer in wardrobe",
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
                TextButton(onClick = onDismiss) { Text("Dismiss") }
                androidx.compose.material3.Button(onClick = onWear) {
                    Icon(Icons.Default.CalendarMonth, null, modifier = Modifier.size(16.dp))
                    Spacer(Modifier.width(4.dp))
                    Text("Wear today")
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
                Text("Composed for you", style = MaterialTheme.typography.titleMedium)
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
                    "Items no longer in wardrobe",
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
                TextButton(onClick = onDismiss) { Text("Dismiss") }
                androidx.compose.material3.Button(onClick = onAccept) {
                    Icon(Icons.Default.Add, null, modifier = Modifier.size(16.dp))
                    Spacer(Modifier.width(4.dp))
                    Text("Create this style")
                }
            }
        }
    }
}

// ---------- Refinement section ----------

private val REFINEMENT_PRESETS = listOf(
    "More casual", "More formal", "Different colors",
    "Warmer clothing", "Lighter clothing", "More trendy",
    "Simpler", "More bold",
)

@OptIn(ExperimentalLayoutApi::class, ExperimentalMaterial3Api::class)
@Composable
private fun RefinementSection(
    input: String,
    feedbackHistory: List<String>,
    onInputChange: (String) -> Unit,
    onSubmitFreetext: () -> Unit,
    onSubmitPreset: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(modifier = modifier, verticalArrangement = Arrangement.spacedBy(8.dp)) {
        HorizontalDivider()

        Text(
            "Refine with AI",
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
            REFINEMENT_PRESETS.forEach { preset ->
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
                placeholder = { Text("Custom feedback…", style = MaterialTheme.typography.bodySmall) },
                singleLine = true,
                textStyle = MaterialTheme.typography.bodySmall,
                modifier = Modifier.weight(1f),
            )
            androidx.compose.material3.IconButton(
                onClick = onSubmitFreetext,
                enabled = input.isNotBlank(),
            ) {
                Icon(Icons.Default.Send, contentDescription = "Refine")
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
        title = { Text("Name this style") },
        text = {
            OutlinedTextField(
                value = name,
                onValueChange = { name = it },
                label = { Text("Style name") },
                singleLine = true,
            )
        },
        confirmButton = {
            TextButton(onClick = { onConfirm(name) }) { Text("Save") }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("Cancel") }
        },
    )
}
