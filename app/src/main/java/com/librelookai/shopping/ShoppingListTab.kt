package com.librelookai.shopping

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
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
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.AutoAwesome
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Place
import androidx.compose.material.icons.filled.ShoppingBag
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Snackbar
import androidx.compose.material3.Surface
import androidx.compose.material3.Tab
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
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
import androidx.compose.ui.unit.dp
import com.librelookai.R
import com.librelookai.core.designsystem.R as DsR
import com.librelookai.data.model.Location
import com.librelookai.util.Analytics
import com.librelookai.util.LocalIsOffline
import com.librelookai.wardrobe.DriveImage
import com.librelookai.wardrobe.WardrobeFilterSheet
import com.librelookai.ui.components.AppFab
import com.librelookai.ui.components.SelectionAction
import com.librelookai.ui.components.SelectionActionBar
import com.librelookai.ui.components.rememberFabExpanded
import com.librelookai.wardrobe.WardrobeZoomableItemGrid
import com.librelookai.wardrobe.tagCategories
import com.librelookai.wardrobe.tagStringsForCategory

@Composable
internal fun ShoppingListTab(
    shoppingClosetViewModel: ShoppingClosetViewModel,
    locations: List<Location>,
    onCaptureClick: () -> Unit,
    onItemsMovedToCloset: (String, List<DriveImage>) -> Unit,
    onCreateOutfitFromSelection: (Set<String>) -> Unit,
    onTryOnSelection: (Set<String>) -> Unit,
    canTryOn: Boolean,
    onOpenItemViewer: (List<String>, String) -> Unit = { _, _ -> },
) {
    val state by shoppingClosetViewModel.state.collectAsState()
    val isOffline = LocalIsOffline.current
    val isSelectionMode = state.selectedIds.isNotEmpty()
    val shopGridState = rememberLazyGridState()

    var showUrlDialog by remember { mutableStateOf(false) }
    var showMoveDialog by remember { mutableStateOf(false) }
    var showDeleteDialog by remember { mutableStateOf(false) }

    var selectedTags by remember { mutableStateOf(emptyMap<String, Set<String>>()) }
    var filterSheetOpen by remember { mutableStateOf(false) }
    val appliedFilterCount = selectedTags.values.sumOf { it.size }

    val tagCategories = remember(state.items) { state.items.tagCategories() }
    val displayedItems = remember(state.items, selectedTags) {
        val activeFilters = selectedTags.filter { (_, tags) -> tags.isNotEmpty() }
        if (activeFilters.isEmpty()) state.items
        else state.items.filter { img ->
            activeFilters.all { (categoryLabel, catTags) ->
                catTags.any { it in img.tagStringsForCategory(categoryLabel) }
            }
        }
    }

    val galleryLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.PickMultipleVisualMedia(),
    ) { uris ->
        if (uris.isNotEmpty()) shoppingClosetViewModel.addFromGallery(uris)
    }

    Box(modifier = Modifier.fillMaxSize()) {
        Column(Modifier.fillMaxSize()) {
            if (state.items.isNotEmpty()) {
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
                        )
                        TextButton(
                            onClick = shoppingClosetViewModel::clearSelection,
                            contentPadding = PaddingValues(horizontal = 8.dp, vertical = 0.dp),
                        ) {
                            Text(stringResource(R.string.action_deselect_all))
                        }
                    }
                }
            }

            if (state.items.isEmpty() && !state.isLoading) {
                Column(
                    modifier = Modifier.weight(1f).fillMaxWidth().padding(horizontal = 32.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.Center,
                ) {
                    Icon(
                        Icons.Default.ShoppingBag,
                        contentDescription = null,
                        modifier = Modifier.size(48.dp),
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Spacer(Modifier.height(8.dp))
                    Text(
                        stringResource(R.string.shop_list_empty_title),
                        style = MaterialTheme.typography.titleSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Spacer(Modifier.height(4.dp))
                    Text(
                        stringResource(R.string.shop_list_empty_hint),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            } else if (displayedItems.isEmpty()) {
                Box(Modifier.weight(1f).fillMaxWidth(), contentAlignment = Alignment.Center) {
                    Text(stringResource(R.string.wardrobe_empty_filter), style = MaterialTheme.typography.bodyLarge)
                }
            } else {
                WardrobeZoomableItemGrid(
                    images = displayedItems,
                    selectedIds = state.selectedIds,
                    onClick = { index, image ->
                        if (isSelectionMode) {
                            Analytics.action("Shopping", "toggle_selection")
                            shoppingClosetViewModel.toggleSelection(image.driveId)
                        } else {
                            Analytics.action("Shopping", "open_item_viewer")
                            onOpenItemViewer(displayedItems.map { it.driveId }, image.driveId)
                        }
                    },
                    onLongClick = { image ->
                        Analytics.action("Shopping", "long_press_select")
                        shoppingClosetViewModel.toggleSelection(image.driveId)
                    },
                    modifier = Modifier.weight(1f).fillMaxWidth(),
                    gridState = shopGridState,
                    contentPadding = PaddingValues(bottom = 96.dp),
                )
            }
        }

        if (state.isLoading || state.isUploading || state.isMoving || state.pendingJobs > 0) {
            Surface(
                color = MaterialTheme.colorScheme.surface.copy(alpha = 0.85f),
                tonalElevation = 4.dp,
                modifier = Modifier.align(Alignment.TopCenter).padding(top = 8.dp),
            ) {
                Row(
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    CircularProgressIndicator(modifier = Modifier.size(16.dp), strokeWidth = 2.dp)
                    val label = when {
                        state.isUploading -> stringResource(R.string.shop_list_uploading)
                        state.isMoving -> stringResource(R.string.shop_list_moving)
                        state.pendingJobs > 0 -> stringResource(R.string.shop_list_processing, state.pendingJobs)
                        else -> stringResource(R.string.shop_list_loading)
                    }
                    Text(label, style = MaterialTheme.typography.bodyMedium)
                }
            }
        }

        // Shared create FAB — hidden during selection mode / offline.
        AppFab(
            label = stringResource(R.string.fab_shopping_add),
            icon = Icons.Default.Add,
            onClick = {
                Analytics.action("Shopping", "open_camera")
                onCaptureClick()
            },
            expanded = rememberFabExpanded(shopGridState),
            visible = !isSelectionMode && !isOffline,
            modifier = Modifier.align(Alignment.BottomEnd).padding(end = 16.dp, bottom = 16.dp),
        )

        // Shared selection action bar — Add to closet (primary) · Style · Delete (danger).
        val shopSelectionActions = buildList {
            if (!isOffline) {
                if (locations.isNotEmpty()) {
                    add(
                        SelectionAction(
                            label = stringResource(R.string.sel_add_to_closet),
                            icon = Icons.Default.Place,
                            kind = SelectionAction.Kind.Primary,
                        ) {
                            Analytics.action("Shopping", "open_move_to_closet_dialog", mapOf("count" to state.selectedIds.size.toString()))
                            showMoveDialog = true
                        },
                    )
                }
                add(
                    SelectionAction(
                        label = stringResource(R.string.sel_style),
                        icon = Icons.Default.AutoAwesome,
                    ) {
                        Analytics.action("Shopping", "create_outfit_from_selection", mapOf("count" to state.selectedIds.size.toString()))
                        onCreateOutfitFromSelection(state.selectedIds)
                    },
                )
                add(
                    SelectionAction(
                        label = stringResource(R.string.action_delete),
                        icon = Icons.Default.Delete,
                        kind = SelectionAction.Kind.Danger,
                    ) {
                        Analytics.action("Shopping", "open_delete_dialog", mapOf("count" to state.selectedIds.size.toString()))
                        showDeleteDialog = true
                    },
                )
            }
        }
        SelectionActionBar(
            count = state.selectedIds.size,
            onClear = {
                Analytics.action("Shopping", "clear_selection")
                shoppingClosetViewModel.clearSelection()
            },
            actions = shopSelectionActions,
            visible = isSelectionMode && shopSelectionActions.isNotEmpty(),
            modifier = Modifier.align(Alignment.BottomCenter),
        )

        state.error?.let { msg ->
            Snackbar(
                modifier = Modifier
                    .align(Alignment.TopCenter)
                    .statusBarsPadding()
                    .padding(start = 8.dp, end = 8.dp, top = 64.dp),
                action = { TextButton(onClick = shoppingClosetViewModel::clearError) { Text(stringResource(R.string.action_dismiss)) } },
            ) { Text(msg) }
        }

    }

    if (filterSheetOpen) {
        WardrobeFilterSheet(
            tagCategories = tagCategories,
            selectedTags = selectedTags,
            appliedCount = displayedItems.size,
            onTagsChanged = { selectedTags = it },
            onDismiss = { filterSheetOpen = false },
        )
    }

    if (showUrlDialog) {
        ShopUrlImportDialog(
            onSubmit = { url ->
                showUrlDialog = false
                shoppingClosetViewModel.addFromUrl(url)
            },
            onDismiss = { showUrlDialog = false },
        )
    }

    if (showMoveDialog) {
        MoveToClosetDialog(
            locations = locations,
            onConfirm = { folderId ->
                showMoveDialog = false
                shoppingClosetViewModel.moveToCloset(
                    state.selectedIds, folderId,
                    onMoved = { moved -> if (moved.isNotEmpty()) onItemsMovedToCloset(folderId, moved) },
                )
            },
            onDismiss = { showMoveDialog = false },
        )
    }

    if (showDeleteDialog) {
        AlertDialog(
            onDismissRequest = { showDeleteDialog = false },
            title = { Text(stringResource(R.string.shop_list_delete_title)) },
            text = { Text(stringResource(R.string.shop_list_delete_text, state.selectedIds.size)) },
            confirmButton = {
                TextButton(onClick = {
                    Analytics.action("Shopping", "confirm_delete_selected", mapOf("count" to state.selectedIds.size.toString()))
                    shoppingClosetViewModel.deleteItems(state.selectedIds)
                    showDeleteDialog = false
                }) { Text(stringResource(R.string.action_delete)) }
            },
            dismissButton = {
                TextButton(onClick = { showDeleteDialog = false }) {
                    Text(stringResource(DsR.string.action_cancel))
                }
            },
        )
    }
}

@Composable
internal fun ShopUrlImportDialog(
    onSubmit: (String) -> Unit,
    onDismiss: () -> Unit,
) {
    var url by remember { mutableStateOf("") }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.shop_list_url_title)) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(
                    stringResource(R.string.shop_list_url_hint),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                OutlinedTextField(
                    value = url,
                    onValueChange = { url = it },
                    placeholder = { Text("https://…") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
            }
        },
        confirmButton = {
            TextButton(enabled = url.isNotBlank(), onClick = {
                Analytics.action("Shopping", "submit_url_import")
                onSubmit(url.trim())
            }) {
                Text(stringResource(R.string.action_continue))
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text(stringResource(DsR.string.action_cancel)) }
        },
    )
}

@Composable
private fun MoveToClosetDialog(
    locations: List<Location>,
    onConfirm: (folderId: String) -> Unit,
    onDismiss: () -> Unit,
) {
    var selectedFolderId by remember { mutableStateOf(locations.firstOrNull()?.folderId) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.shop_list_move_title)) },
        text = {
            Column {
                locations.sortedBy { it.name }.forEach { loc ->
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable { selectedFolderId = loc.folderId }
                            .padding(vertical = 8.dp, horizontal = 4.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(12.dp),
                    ) {
                        RadioButton(
                            selected = loc.folderId == selectedFolderId,
                            onClick = { selectedFolderId = loc.folderId },
                        )
                        Text(loc.name, style = MaterialTheme.typography.bodyLarge)
                    }
                }
            }
        },
        confirmButton = {
            TextButton(
                enabled = selectedFolderId != null,
                onClick = {
                    Analytics.action("Shopping", "confirm_move_to_closet")
                    selectedFolderId?.let(onConfirm)
                },
            ) { Text(stringResource(R.string.action_continue)) }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text(stringResource(DsR.string.action_cancel)) }
        },
    )
}

// ============================================================================
//  Tab 1: Similarity Finder
// ============================================================================

