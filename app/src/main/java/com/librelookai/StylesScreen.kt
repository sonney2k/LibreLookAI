package com.librelookai

import androidx.activity.compose.BackHandler
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
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.AutoAwesome
import androidx.compose.material.icons.filled.AutoFixHigh
import androidx.compose.material.icons.filled.CalendarMonth
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.SmallFloatingActionButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Snackbar
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
            onNameChanged = stylesViewModel::updateDraftName,
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
                    itemIds = suggestion.itemIds.toSet(),
                    name    = suggestion.name,
                )
                stylesViewModel.clearNewSuggestion()
            },
            onClearComposition = stylesViewModel::clearNewSuggestion,
            modifier = modifier,
        )
    }

    if (stylesState.showNameDialog) {
        StyleNameDialog(
            initialName = stylesState.editingStyle?.name
                ?: "Style ${stylesState.styles.size + 1}",
            onConfirm = stylesViewModel::saveStyle,
            onDismiss = stylesViewModel::cancelCreating,
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
    onCreateStyle: () -> Unit,
    onEditStyle: (Style) -> Unit,
    onDeleteStyle: (String) -> Unit,
    onWearStyle: (String) -> Unit,
    onSuggestStyle: () -> Unit,
    onClearPrediction: () -> Unit,
    onComposeStyle: () -> Unit,
    onAcceptComposition: (NewStyleSuggestion) -> Unit,
    onClearComposition: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val itemsById = remember(items) { items.associateBy { it.driveId } }

    Box(modifier = modifier.fillMaxSize()) {
        when {
            isLoading -> CircularProgressIndicator(modifier = Modifier.align(Alignment.Center))
            styles.isEmpty() -> {
                Column(
                    modifier = Modifier.align(Alignment.Center),
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
            else -> {
                LazyColumn(
                    modifier = Modifier.fillMaxSize(),
                    contentPadding = PaddingValues(vertical = 8.dp, horizontal = 0.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    itemsIndexed(styles, key = { _, s -> s.id }) { _, style ->
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

        // Bottom-start: Compose (small) + Suggest (extended) stacked vertically
        Column(
            modifier = Modifier
                .align(Alignment.BottomStart)
                .padding(start = 16.dp, bottom = 16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
            horizontalAlignment = Alignment.Start,
        ) {
            SmallFloatingActionButton(
                onClick = { if (!isComposing) onComposeStyle() },
                containerColor = MaterialTheme.colorScheme.secondaryContainer,
            ) {
                if (isComposing)
                    CircularProgressIndicator(modifier = Modifier.size(18.dp), strokeWidth = 2.dp)
                else
                    Icon(Icons.Default.AutoFixHigh, contentDescription = "Compose new style")
            }

            ExtendedFloatingActionButton(
                onClick = { if (!isPredicting) onSuggestStyle() },
                icon = {
                    if (isPredicting)
                        CircularProgressIndicator(modifier = Modifier.size(18.dp), strokeWidth = 2.dp)
                    else
                        Icon(Icons.Default.AutoAwesome, contentDescription = null)
                },
                text = { Text(if (isPredicting) "Thinking…" else "Suggest") },
            )
        }

        // Create FAB (bottom-end)
        FloatingActionButton(
            onClick = onCreateStyle,
            modifier = Modifier.align(Alignment.BottomEnd).padding(16.dp),
        ) {
            Icon(Icons.Default.Add, contentDescription = "Create style")
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
                onDismiss = onClearPrediction,
                onWear = { onWearStyle(suggestedStyle.id); onClearPrediction() },
            )
        }
    }

    // Composition result sheet
    newSuggestion?.let { suggestion ->
        NewStyleSuggestionSheet(
            suggestion = suggestion,
            itemsById = itemsById,
            onDismiss = onClearComposition,
            onAccept = { onAcceptComposition(suggestion) },
        )
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
    onNameChanged: (String) -> Unit = {},
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
            if (isEditing) {
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
            } else {
                Text(
                    text = if (selectedIds.isEmpty()) "Select items" else "${selectedIds.size} selected",
                    style = MaterialTheme.typography.titleMedium,
                    modifier = Modifier.weight(1f),
                )
            }
            TextButton(onClick = onConfirm, enabled = selectedIds.isNotEmpty()) {
                Text(if (isEditing) "Save" else "Create Style")
            }
        }

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
    onDismiss: () -> Unit,
    onWear: () -> Unit,
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
    onDismiss: () -> Unit,
    onAccept: () -> Unit,
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
