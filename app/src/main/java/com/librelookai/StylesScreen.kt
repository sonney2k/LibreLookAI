package com.librelookai

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
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
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.itemsIndexed
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
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
    modifier: Modifier = Modifier,
) {
    val stylesState by stylesViewModel.state.collectAsState()
    val wardrobeState by wardrobeViewModel.state.collectAsState()

    if (stylesState.isCreating) {
        StyleItemPicker(
            items = wardrobeState.images,
            selectedIds = stylesState.draftItemIds,
            isEditing = stylesState.editingStyle != null,
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
            onCreateStyle = stylesViewModel::startCreating,
            onEditStyle = stylesViewModel::startEditing,
            onDeleteStyle = stylesViewModel::deleteStyle,
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

@Composable
private fun StyleListScreen(
    styles: List<Style>,
    items: List<DriveImage>,
    isLoading: Boolean,
    onCreateStyle: () -> Unit,
    onEditStyle: (Style) -> Unit,
    onDeleteStyle: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
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
                val itemsById = remember(items) { items.associateBy { it.driveId } }
                LazyColumn(
                    modifier = Modifier.fillMaxSize(),
                    contentPadding = PaddingValues(vertical = 8.dp, horizontal = 0.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    itemsIndexed(styles, key = { _, s -> s.id }) { _, style ->
                        StyleCard(
                            style = style,
                            itemsById = itemsById,
                            onEdit = { onEditStyle(style) },
                            onDelete = { onDeleteStyle(style.id) },
                        )
                    }
                }
            }
        }

        FloatingActionButton(
            onClick = onCreateStyle,
            modifier = Modifier.align(Alignment.BottomEnd).padding(16.dp),
        ) {
            Icon(Icons.Default.Add, contentDescription = "Create style")
        }
    }
}

// ---------- Style card ----------

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun StyleCard(
    style: Style,
    itemsById: Map<String, DriveImage>,
    onEdit: () -> Unit,
    onDelete: () -> Unit,
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
            Text(
                text = if (selectedIds.isEmpty()) "Select items" else "${selectedIds.size} selected",
                style = MaterialTheme.typography.titleMedium,
                modifier = Modifier.weight(1f),
            )
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
