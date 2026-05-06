package com.librelookai

import android.graphics.BitmapFactory
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.calculatePan
import androidx.compose.foundation.gestures.calculateZoom
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.AutoAwesome
import androidx.compose.material.icons.filled.CameraAlt
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Link
import androidx.compose.material.icons.filled.PhotoCamera
import androidx.compose.material.icons.filled.PhotoLibrary
import androidx.compose.material.icons.filled.Place
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.ShoppingBag
import androidx.compose.material.icons.filled.TipsAndUpdates
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Snackbar
import androidx.compose.material3.Surface
import androidx.compose.material3.Tab
import androidx.compose.material3.TabRow
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.runtime.SideEffect
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.compose.ui.window.DialogWindowProvider
import androidx.lifecycle.viewmodel.compose.viewModel
import coil.compose.AsyncImage
import coil.request.ImageRequest
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

@Composable
fun ShoppingHelperScreen(
    shoppingViewModel: ShoppingHelperViewModel = viewModel(),
    shoppingClosetViewModel: ShoppingClosetViewModel = viewModel(),
    wardrobeViewModel: WardrobeViewModel = viewModel(),
    gapViewModel: WardrobeGapViewModel = viewModel(),
    profileViewModel: ProfileViewModel = viewModel(),
    locationViewModel: LocationViewModel = viewModel(),
    onSettingsClick: () -> Unit = {},
    /** Switch to the wardrobe tab and scroll/highlight the picked match. */
    onShowInWardrobe: (DriveImage) -> Unit = {},
    onCreateOutfitFromSelection: (Set<String>) -> Unit = {},
    onTryOnSelection: (Set<String>) -> Unit = {},
    canTryOn: Boolean = false,
    navResetTick: Int = 0,
    modifier: Modifier = Modifier,
) {
    val shoppingState by shoppingViewModel.state.collectAsState()
    val wardrobeState by wardrobeViewModel.state.collectAsState()
    val locationState by locationViewModel.state.collectAsState()
    val shoppingClosetStateTop by shoppingClosetViewModel.state.collectAsState()

    // The URL-import picker (candidate grid + WebView fallback) is hosted at the screen root so
    // it survives the camera early-returns that early-`return` below.
    shoppingClosetStateTop.urlImportPicker?.let { picker ->
        UrlImportPicker(
            pageUrl = picker.pageUrl,
            candidates = picker.candidates,
            isDownloading = picker.isDownloading,
            onPick = shoppingClosetViewModel::confirmUrlImportPick,
            onDismiss = shoppingClosetViewModel::cancelUrlImport,
        )
    }

    // Hoisted above the camera early-returns so the active tab survives across capture —
    // otherwise the rememberSaveable slot is never visited and resets to 0 on return.
    var selectedTab by rememberSaveable { mutableIntStateOf(0) }
    LaunchedEffect(navResetTick) { selectedTab = 0 }
    var isClosetCapturing by rememberSaveable { mutableStateOf(false) }
    var showClosetUrlDialog by remember { mutableStateOf(false) }
    val closetGalleryLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.PickMultipleVisualMedia(),
    ) { uris -> if (uris.isNotEmpty()) shoppingClosetViewModel.addFromGallery(uris) }

    // Similarity Finder takes the camera over the whole screen. The closet selector is hidden:
    // similarity search reads from every closet and never imports.
    if (shoppingState.isCapturing) {
        CaptureScreen(
            onPhotoTaken = { file ->
                shoppingViewModel.onCapturedFile(file, wardrobeState.allLocationImages)
            },
            onCancel = shoppingViewModel::cancelCapture,
            locations = emptyList(),
            importTargetFolderId = null,
            onSetImportTarget = {},
            showCenterCrosshair = true,
            modifier = modifier,
        )
        return
    }

    // Shopping List camera capture (separate flag — local to this screen).
    if (isClosetCapturing) {
        CaptureScreen(
            onPhotoTaken = { file ->
                isClosetCapturing = false
                shoppingClosetViewModel.addFromCamera(file)
            },
            onCancel = { isClosetCapturing = false },
            locations = emptyList(),
            importTargetFolderId = null,
            onSetImportTarget = {},
            onOpenGallery = {
                closetGalleryLauncher.launch(
                    PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly),
                )
            },
            onOpenUrlImport = { showClosetUrlDialog = true },
            modifier = modifier,
        )
        if (showClosetUrlDialog) {
            ShopUrlImportDialog(
                onSubmit = { url ->
                    showClosetUrlDialog = false
                    shoppingClosetViewModel.addFromUrl(url)
                },
                onDismiss = { showClosetUrlDialog = false },
            )
        }
        return
    }

    // Pull the wishlist as soon as the screen first composes.
    LaunchedEffect(Unit) { shoppingClosetViewModel.loadItems() }

    Column(modifier = modifier.fillMaxSize()) {
        AppScreenHeader(
            title = stringResource(R.string.nav_shopping),
            leadingIcon = Icons.Default.ShoppingBag,
            trailingContent = {
                // Shopping List is location-independent; hide the LocationButton there.
                if (selectedTab != 0) {
                    LocationButton(
                        locations = locationState.locations,
                        activeLocationId = locationState.activeLocationId,
                        onSetActiveLocation = locationViewModel::setActiveLocation,
                    )
                }
            },
            onSettingsClick = onSettingsClick,
        )

        LaunchedEffect(selectedTab) {
            val name = when (selectedTab) {
                0 -> "Shopping/List"; 1 -> "Shopping/Similarity"; 2 -> "Shopping/Gaps"
                else -> "Shopping/Tab$selectedTab"
            }
            Analytics.screen(name)
        }
        TabRow(selectedTabIndex = selectedTab) {
            Tab(
                selected = selectedTab == 0,
                onClick = {
                    Analytics.action("Shopping", "subtab_list")
                    selectedTab = 0
                },
                text = { Text(stringResource(R.string.shopping_tab_list)) },
            )
            Tab(
                selected = selectedTab == 1,
                onClick = {
                    Analytics.action("Shopping", "subtab_similarity")
                    selectedTab = 1
                },
                text = { Text(stringResource(R.string.shopping_tab_similarity)) },
            )
            Tab(
                selected = selectedTab == 2,
                onClick = {
                    Analytics.action("Shopping", "subtab_gaps")
                    selectedTab = 2
                },
                text = { Text(stringResource(R.string.shopping_tab_gaps)) },
            )
        }

        when (selectedTab) {
            0 -> ShoppingListTab(
                shoppingClosetViewModel = shoppingClosetViewModel,
                locations = locationState.locations,
                activeLocationId = locationState.activeLocationId,
                onCaptureClick = { isClosetCapturing = true },
                onRefreshWardrobe = wardrobeViewModel::loadImages,
                onCreateOutfitFromSelection = onCreateOutfitFromSelection,
                onTryOnSelection = onTryOnSelection,
                canTryOn = canTryOn,
            )
            1 -> SimilarityFinderTab(
                shoppingViewModel = shoppingViewModel,
                shoppingClosetViewModel = shoppingClosetViewModel,
                wardrobeViewModel = wardrobeViewModel,
                profileViewModel = profileViewModel,
                onShowInWardrobe = onShowInWardrobe,
            )
            2 -> IdentifyGapsTab(
                gapViewModel = gapViewModel,
                wardrobeViewModel = wardrobeViewModel,
                profileViewModel = profileViewModel,
            )
        }
    }
}

// ============================================================================
//  Tab 0: Shopping List (wishlist)
// ============================================================================

@Composable
private fun ShoppingListTab(
    shoppingClosetViewModel: ShoppingClosetViewModel,
    locations: List<Location>,
    activeLocationId: String,
    onCaptureClick: () -> Unit,
    onRefreshWardrobe: () -> Unit,
    onCreateOutfitFromSelection: (Set<String>) -> Unit,
    onTryOnSelection: (Set<String>) -> Unit,
    canTryOn: Boolean,
) {
    val state by shoppingClosetViewModel.state.collectAsState()
    val isOffline = LocalIsOffline.current
    val isSelectionMode = state.selectedIds.isNotEmpty()

    var showUrlDialog by remember { mutableStateOf(false) }
    var showMoveDialog by remember { mutableStateOf(false) }
    var showDeleteDialog by remember { mutableStateOf(false) }
    var selectedIndex by remember { mutableStateOf<Int?>(null) }

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
    LaunchedEffect(selectedTags) { selectedIndex = null }

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
                            selectedIndex = index
                        }
                    },
                    onLongClick = { image ->
                        Analytics.action("Shopping", "long_press_select")
                        shoppingClosetViewModel.toggleSelection(image.driveId)
                    },
                    modifier = Modifier.weight(1f).fillMaxWidth(),
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

        // Speed-dial FAB column
        if (isSelectionMode) {
            Column(
                modifier = Modifier.align(Alignment.BottomEnd).padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
                horizontalAlignment = Alignment.End,
            ) {
                if (!isOffline) {
                    ExtendedFloatingActionButton(
                        onClick = {
                            Analytics.action("Shopping", "create_outfit_from_selection", mapOf("count" to state.selectedIds.size.toString()))
                            onCreateOutfitFromSelection(state.selectedIds)
                        },
                        containerColor = MaterialTheme.colorScheme.primaryContainer,
                        contentColor = MaterialTheme.colorScheme.onPrimaryContainer,
                        icon = { Icon(Icons.Default.AutoAwesome, contentDescription = null) },
                        text = { Text(stringResource(R.string.wardrobe_create_style)) },
                    )
                    if (canTryOn) {
                        ExtendedFloatingActionButton(
                            onClick = {
                                Analytics.action("Shopping", "try_on_selection", mapOf("count" to state.selectedIds.size.toString()))
                                onTryOnSelection(state.selectedIds)
                            },
                            containerColor = MaterialTheme.colorScheme.primaryContainer,
                            contentColor = MaterialTheme.colorScheme.onPrimaryContainer,
                            icon = { Icon(Icons.Default.AutoAwesome, contentDescription = null) },
                            text = { Text(stringResource(R.string.tryon_fab)) },
                        )
                    }
                }
                if (locations.isNotEmpty() && !isOffline) {
                    ExtendedFloatingActionButton(
                        onClick = {
                            Analytics.action("Shopping", "open_move_to_closet_dialog", mapOf("count" to state.selectedIds.size.toString()))
                            showMoveDialog = true
                        },
                        containerColor = MaterialTheme.colorScheme.primaryContainer,
                        contentColor = MaterialTheme.colorScheme.onPrimaryContainer,
                        icon = { Icon(Icons.Default.Place, contentDescription = null) },
                        text = { Text(stringResource(R.string.shop_list_move_to_closet)) },
                    )
                }
                if (!isOffline) {
                    ExtendedFloatingActionButton(
                        onClick = {
                            Analytics.action("Shopping", "open_delete_dialog", mapOf("count" to state.selectedIds.size.toString()))
                            showDeleteDialog = true
                        },
                        containerColor = MaterialTheme.colorScheme.error,
                        contentColor = MaterialTheme.colorScheme.onError,
                        icon = { Icon(Icons.Default.Delete, contentDescription = null) },
                        text = { Text(stringResource(R.string.action_delete)) },
                    )
                }
                ExtendedFloatingActionButton(
                    onClick = {
                        Analytics.action("Shopping", "clear_selection")
                        shoppingClosetViewModel.clearSelection()
                    },
                    containerColor = MaterialTheme.colorScheme.surfaceVariant,
                    contentColor = MaterialTheme.colorScheme.onSurfaceVariant,
                    icon = { Icon(Icons.Default.Close, contentDescription = null) },
                    text = { Text(stringResource(R.string.action_cancel)) },
                )
            }
        } else if (!isOffline) {
            FloatingActionButton(
                onClick = {
                    Analytics.action("Shopping", "open_camera")
                    onCaptureClick()
                },
                modifier = Modifier.align(Alignment.BottomEnd).padding(16.dp),
            ) {
                Icon(Icons.Default.Add, contentDescription = stringResource(R.string.shop_list_add_camera))
            }
        }

        state.error?.let { msg ->
            Snackbar(
                modifier = Modifier.align(Alignment.BottomStart).padding(start = 8.dp, end = 96.dp, bottom = 8.dp),
                action = { TextButton(onClick = shoppingClosetViewModel::clearError) { Text(stringResource(R.string.action_dismiss)) } },
            ) { Text(msg) }
        }

        // Full-screen item viewer — same UX as Wardrobe (tag / remove-bg / rotate / edit tags).
        selectedIndex?.let { startIndex ->
            FullScreenViewer(
                images = displayedItems,
                initialIndex = startIndex.coerceIn(0, (displayedItems.size - 1).coerceAtLeast(0)),
                allTagCategories = tagCategories,
                onDismiss = { selectedIndex = null },
                onTagImage = shoppingClosetViewModel::tagImage,
                onRemoveBackground = shoppingClosetViewModel::reprocessBackground,
                onRotateImage = shoppingClosetViewModel::rotateImage,
                onUpdateTags = shoppingClosetViewModel::updateTags,
                onDeleteItem = { driveId ->
                    shoppingClosetViewModel.deleteItems(setOf(driveId))
                    if (displayedItems.size <= 1) selectedIndex = null
                },
                onMoveToLocation = { ids, folderId ->
                    shoppingClosetViewModel.moveToCloset(ids, folderId) { moved ->
                        if (moved.isNotEmpty()) onRefreshWardrobe()
                    }
                    if (displayedItems.size <= 1) selectedIndex = null
                },
                onCreateOutfitFromSelection = onCreateOutfitFromSelection,
                locations = locations,
                activeLocationId = activeLocationId,
                processingImageId = state.processingImageId,
                writeMode = true,
            )
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
                shoppingClosetViewModel.moveToCloset(state.selectedIds, folderId) { moved ->
                    if (moved.isNotEmpty()) onRefreshWardrobe()
                }
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
                    Text(stringResource(R.string.action_cancel))
                }
            },
        )
    }
}

@Composable
private fun ShopUrlImportDialog(
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
            TextButton(onClick = onDismiss) { Text(stringResource(R.string.action_cancel)) }
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
            TextButton(onClick = onDismiss) { Text(stringResource(R.string.action_cancel)) }
        },
    )
}

// ============================================================================
//  Tab 1: Similarity Finder
// ============================================================================

@Composable
private fun SimilarityFinderTab(
    shoppingViewModel: ShoppingHelperViewModel,
    shoppingClosetViewModel: ShoppingClosetViewModel,
    wardrobeViewModel: WardrobeViewModel,
    profileViewModel: ProfileViewModel,
    onShowInWardrobe: (DriveImage) -> Unit,
) {
    val state by shoppingViewModel.state.collectAsState()
    val wardrobeState by wardrobeViewModel.state.collectAsState()
    val profileState by profileViewModel.state.collectAsState()
    val showDebug = profileState.preferences.debugSimilarityPreview

    var previewIndex by remember { mutableStateOf<Int?>(null) }

    // Kick off a catch-up index sync whenever the cross-closet wardrobe snapshot changes.
    LaunchedEffect(wardrobeState.allLocationImages.size) {
        shoppingViewModel.syncIndex(wardrobeState.allLocationImages)
    }

    Box(modifier = Modifier.fillMaxSize()) {
        LazyColumn(
            modifier = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(bottom = 24.dp),
        ) {
            item {
                IntroAndControls(
                    state = state,
                    onCapture = shoppingViewModel::beginCapture,
                    onClear = shoppingViewModel::clearResults,
                )
            }

            if (state.queryPath != null) {
                item { QueryCard(queryPath = state.queryPath!!) }
            }

            if (state.isMatching) {
                item {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 16.dp, vertical = 12.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        CircularProgressIndicator(modifier = Modifier.size(18.dp), strokeWidth = 2.dp)
                        Spacer(Modifier.width(10.dp))
                        Text(
                            stringResource(R.string.shop_matching),
                            style = MaterialTheme.typography.bodyMedium,
                        )
                    }
                }
            }

            if (state.matches.isNotEmpty()) {
                item {
                    HorizontalDivider()
                    Text(
                        stringResource(R.string.shop_matches_title, state.matches.size),
                        style = MaterialTheme.typography.titleSmall,
                        fontWeight = FontWeight.SemiBold,
                        modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp),
                    )
                }
                itemsIndexed(state.matches, key = { _, m -> m.image.driveId }) { idx, match ->
                    MatchRow(match = match, onClick = {
                        Analytics.action("Shopping/Similarity", "open_match_preview", mapOf("index" to idx.toString()))
                        previewIndex = idx
                    })
                }
            } else if (state.queryPath != null && !state.isMatching) {
                item {
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 16.dp, vertical = 16.dp),
                        verticalArrangement = Arrangement.spacedBy(12.dp),
                    ) {
                        Text(
                            stringResource(R.string.shop_no_matches),
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        val queryPath = state.queryPath
                        if (queryPath != null) {
                            Button(
                                onClick = {
                                    Analytics.action("Shopping/Similarity", "add_query_to_shopping_list")
                                    shoppingClosetViewModel.importQuery(queryPath)
                                },
                                modifier = Modifier.fillMaxWidth(),
                            ) {
                                Icon(Icons.Default.ShoppingBag, contentDescription = null, modifier = Modifier.size(18.dp))
                                Spacer(Modifier.width(8.dp))
                                Text(stringResource(R.string.shop_add_to_shopping_list))
                            }
                        }
                    }
                }
            }
        }
    }

    previewIndex?.let { idx ->
        if (idx in state.matches.indices) {
            MatchPreviewDialog(
                matches = state.matches,
                initialIndex = idx,
                queryRawPath = state.queryPath,
                queryProcessedPath = state.queryProcessedPath,
                querySegmented = state.querySegmented,
                queryHist = state.queryHist,
                queryVec = state.queryVec,
                queryPHash = state.queryPHash,
                showDebug = showDebug,
                onShowInWardrobe = { image ->
                    previewIndex = null
                    onShowInWardrobe(image)
                },
                onAddToShoppingList = {
                    Analytics.action("Shopping/Similarity", "add_match_to_shopping_list")
                    state.queryPath?.let { shoppingClosetViewModel.importQuery(it) }
                    previewIndex = null
                },
                canAddToShoppingList = state.queryPath != null,
                onDismiss = { previewIndex = null },
            )
        }
    }
}

@Composable
private fun IntroAndControls(
    state: ShoppingHelperUiState,
    onCapture: () -> Unit,
    onClear: () -> Unit,
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 12.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Text(
            stringResource(R.string.shop_description),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )

        if (!state.modelAvailable) {
            Surface(
                color = MaterialTheme.colorScheme.errorContainer,
                shape = MaterialTheme.shapes.small,
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text(
                    stringResource(R.string.shop_model_missing),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onErrorContainer,
                    modifier = Modifier.padding(12.dp),
                )
            }
        }

        Row(verticalAlignment = Alignment.CenterVertically) {
            Button(
                onClick = {
                    Analytics.action("Shopping/Similarity", "capture_query")
                    onCapture()
                },
                enabled = state.modelAvailable && !state.isMatching,
                modifier = Modifier.weight(1f),
            ) {
                Icon(Icons.Default.PhotoCamera, contentDescription = null, modifier = Modifier.size(18.dp))
                Spacer(Modifier.width(8.dp))
                Text(
                    if (state.hasQuery) stringResource(R.string.shop_take_another)
                    else stringResource(R.string.shop_take_photo),
                )
            }
            if (state.hasQuery) {
                Spacer(Modifier.width(8.dp))
                OutlinedButton(onClick = {
                    Analytics.action("Shopping/Similarity", "clear_results")
                    onClear()
                }) {
                    Icon(Icons.Default.Refresh, contentDescription = null, modifier = Modifier.size(16.dp))
                    Spacer(Modifier.width(6.dp))
                    Text(stringResource(R.string.shop_clear))
                }
            }
        }

        if (state.isIndexing && state.indexTotal > 0) {
            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(
                    stringResource(R.string.shop_indexing, state.indexDone, state.indexTotal),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                LinearProgressIndicator(
                    progress = { (state.indexDone.toFloat() / state.indexTotal).coerceIn(0f, 1f) },
                    modifier = Modifier.fillMaxWidth(),
                )
            }
        } else {
            Text(
                stringResource(R.string.shop_index_size, state.indexedCount),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }

        state.error?.let { err ->
            Text(err, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
        }
    }
}

@Composable
private fun QueryCard(queryPath: String) {
    val context = LocalContext.current
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 4.dp),
    ) {
        Text(
            stringResource(R.string.shop_your_photo),
            style = MaterialTheme.typography.labelMedium,
            fontWeight = FontWeight.SemiBold,
            modifier = Modifier.padding(bottom = 6.dp),
        )
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(220.dp)
                .clip(RoundedCornerShape(12.dp))
                .background(MaterialTheme.colorScheme.surfaceVariant),
            contentAlignment = Alignment.Center,
        ) {
            AsyncImage(
                model = ImageRequest.Builder(context)
                    .data(File(queryPath))
                    .crossfade(true)
                    .build(),
                contentDescription = null,
                contentScale = ContentScale.Fit,
                modifier = Modifier.fillMaxSize(),
            )
        }
    }
}

@Composable
internal fun MatchRow(match: ShopMatch, onClick: () -> Unit) {
    val context = LocalContext.current
    val percent = ((match.score.coerceIn(-1f, 1f) + 1f) / 2f * 100f).toInt()
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            modifier = Modifier
                .size(72.dp)
                .clip(RoundedCornerShape(10.dp))
                .background(Color(0x1100FF00)),
            contentAlignment = Alignment.Center,
        ) {
            AsyncImage(
                model = ImageRequest.Builder(context)
                    .data(File(match.image.localPath))
                    .crossfade(true)
                    .build(),
                contentDescription = match.image.name,
                contentScale = ContentScale.Fit,
                modifier = Modifier.fillMaxSize().aspectRatio(1f),
            )
        }
        Spacer(Modifier.width(12.dp))
        Column(modifier = Modifier.weight(1f)) {
            val label = match.image.tags?.label?.takeIf { it.isNotBlank() }
                ?: match.image.tags?.type?.takeIf { it.isNotBlank() }
                ?: match.image.name
            Text(label, style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.SemiBold)
            Spacer(Modifier.height(4.dp))
            LinearProgressIndicator(
                progress = { (percent / 100f).coerceIn(0f, 1f) },
                modifier = Modifier.fillMaxWidth(),
            )
            Text(
                stringResource(R.string.shop_match_score, percent),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
    HorizontalDivider()
}

/**
 * Match preview dialog used by both the Similarity Finder and the wardrobe's "Find by photo"
 * entry point. Pages horizontally over [matches] starting at [initialIndex]. The default render
 * shows the match image + score header + action buttons; when [showDebug] is true (Settings →
 * AI tab toggle) the per-channel breakdown is appended above the action buttons.
 */
@Composable
internal fun MatchPreviewDialog(
    matches: List<ShopMatch>,
    initialIndex: Int,
    queryRawPath: String?,
    queryProcessedPath: String?,
    querySegmented: Boolean,
    queryHist: FloatArray?,
    queryVec: FloatArray?,
    queryPHash: Long?,
    showDebug: Boolean,
    onShowInWardrobe: (DriveImage) -> Unit,
    onAddToShoppingList: () -> Unit,
    canAddToShoppingList: Boolean,
    showActions: Boolean = true,
    onDismiss: () -> Unit,
) {
    val barInsets = LocalSystemBarsPadding.current
    val parentContext = LocalContext.current
    val parentConfiguration = LocalConfiguration.current
    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(
            usePlatformDefaultWidth = false,
            decorFitsSystemWindows = false,
        ),
    ) {
      // Force the dialog window to fill the screen edge-to-edge. Without setDecorFitsSystemWindows
      // the window may be inset above the navigation bar in 3-button nav mode, while our captured
      // barInsets bottom padding still adds nav-bar height — net effect: action row clipped.
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
        val pagerState = rememberPagerState(
            initialPage = initialIndex.coerceIn(0, (matches.size - 1).coerceAtLeast(0)),
            pageCount = { matches.size },
        )

        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(Color.Black),
        ) {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(top = barInsets.calculateTopPadding()),
            ) {

                val current = matches[pagerState.currentPage]
                val label = current.image.tags?.label?.takeIf { it.isNotBlank() }
                    ?: current.image.tags?.type?.takeIf { it.isNotBlank() }
                    ?: current.image.name

                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(start = 16.dp, end = 4.dp, top = 8.dp, bottom = 4.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            label,
                            color = Color.White,
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.SemiBold,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                        Text(
                            "${pagerState.currentPage + 1} / ${matches.size}",
                            color = Color.White.copy(alpha = 0.7f),
                            style = MaterialTheme.typography.labelMedium,
                        )
                    }
                    IconButton(onClick = onDismiss) {
                        Icon(
                            Icons.Default.Close,
                            contentDescription = stringResource(R.string.action_dismiss),
                            tint = Color.White,
                        )
                    }
                }

                HorizontalPager(
                    state = pagerState,
                    modifier = Modifier.weight(1f).fillMaxWidth(),
                ) { page ->
                    val match = matches[page]
                    if (showDebug) {
                        MatchDebugPage(
                            match = match,
                            queryRawPath = queryRawPath,
                            queryProcessedPath = queryProcessedPath,
                            querySegmented = querySegmented,
                            queryHist = queryHist,
                            queryVec = queryVec,
                            queryPHash = queryPHash,
                        )
                    } else {
                        MatchDefaultPage(match = match)
                    }
                }

                if (showActions) {
                    MatchActionBar(
                        onShowInWardrobe = { onShowInWardrobe(matches[pagerState.currentPage].image) },
                        onAddToShoppingList = onAddToShoppingList,
                        canAddToShoppingList = canAddToShoppingList,
                    )
                }
            }
        }
      }
    }
}

@Composable
private fun MatchDefaultPage(match: ShopMatch) {
    val combinedPercent = ((match.score.coerceIn(-1f, 1f) + 1f) / 2f * 100f).toInt()
    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(horizontal = 12.dp, vertical = 8.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Surface(
            color = Color(0x22FFFFFF),
            shape = RoundedCornerShape(8.dp),
            modifier = Modifier.fillMaxWidth(),
        ) {
            Text(
                stringResource(R.string.shop_match_score, combinedPercent),
                color = Color.White,
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.SemiBold,
                modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
            )
        }
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .weight(1f)
                .clip(RoundedCornerShape(8.dp))
                .background(Color(0x11FFFFFF)),
            contentAlignment = Alignment.Center,
        ) {
            ZoomableMatchImage(file = File(match.image.localPath))
        }
    }
}

@Composable
private fun MatchActionBar(
    onShowInWardrobe: () -> Unit,
    onAddToShoppingList: () -> Unit,
    canAddToShoppingList: Boolean,
) {
    val barInsets = LocalSystemBarsPadding.current
    // Inside a Compose Dialog window, both LocalSystemBarsPadding (captured from the activity) and
    // the dialog view's rootWindowInsets can be reported as zero on some devices, leaving the
    // action row clipped behind the navigation bar. Read whichever is available and fall back to
    // a 48dp floor (3-button nav bar height) so the row always clears the nav bar.
    val view = androidx.compose.ui.platform.LocalView.current
    val density = androidx.compose.ui.platform.LocalDensity.current
    val rootInsetBottomDp = remember(view) {
        val raw = view.rootWindowInsets
        val bottomPx = if (raw != null) {
            androidx.core.view.WindowInsetsCompat
                .toWindowInsetsCompat(raw, view)
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
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .background(Color.Black)
            .padding(bottom = effectiveBottom)
            .padding(horizontal = 12.dp, vertical = 10.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Button(onClick = onShowInWardrobe, modifier = Modifier.weight(1f)) {
            Text(stringResource(R.string.shop_show_in_wardrobe))
        }
        if (canAddToShoppingList) {
            OutlinedButton(
                onClick = onAddToShoppingList,
                modifier = Modifier.weight(1f),
            ) {
                Text(stringResource(R.string.shop_add_to_shopping_list))
            }
        }
    }
}

@Composable
private fun MatchDebugPage(
    match: ShopMatch,
    queryRawPath: String?,
    queryProcessedPath: String?,
    querySegmented: Boolean,
    queryHist: FloatArray?,
    queryVec: FloatArray?,
    queryPHash: Long?,
) {
    // Pull the match's stored embedding + histogram from the persistent index. produceState
    // re-fetches whenever the visible page changes so HorizontalPager works smoothly.
    val matchEntry by produceState<EmbeddingIndex.Entry?>(initialValue = null, match.image.driveId) {
        value = withContext(Dispatchers.IO) { EmbeddingService.index.entry(match.image.driveId) }
    }

    val combinedPercent = ((match.score.coerceIn(-1f, 1f) + 1f) / 2f * 100f).toInt()
    val embCos = remember(queryVec, matchEntry) {
        val q = queryVec; val e = matchEntry?.vec
        if (q != null && e != null && q.size == e.size) dot(q, e) else null
    }
    val histCos = remember(queryHist, matchEntry) {
        val q = queryHist; val h = matchEntry?.hist
        if (q != null && h != null) ColorHistogram.cosine(q, h) else null
    }
    // Best Hamming similarity between the query's canonical pHash and the match's 24 rotated
    // hashes. Mirrors the rotation-invariant pHash term that `EmbeddingIndex.search` folds into
    // the combined score.
    val pHashSim = remember(queryPHash, matchEntry) {
        val q = queryPHash; val hashes = matchEntry?.pHashes
        if (q != null && hashes != null && hashes.isNotEmpty()) PHash.bestSimilarity(q, hashes) else null
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 12.dp, vertical = 8.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Surface(
            color = Color(0x22FFFFFF),
            shape = RoundedCornerShape(8.dp),
            modifier = Modifier.fillMaxWidth(),
        ) {
            Column(modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp)) {
                Text(
                    "Combined match: $combinedPercent%",
                    color = Color.White,
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.SemiBold,
                )
                Text(
                    "embedding cos = ${formatCos(embCos)}   ·   histogram cos = ${formatCos(histCos)}   ·   pHash sim = ${formatCos(pHashSim)}",
                    color = Color.White.copy(alpha = 0.8f),
                    style = MaterialTheme.typography.labelSmall,
                )
            }
        }

        val processedLabel = if (querySegmented) {
            "Query  (raw → segmented + composited + cropped)"
        } else {
            "Query  (raw → cropped — segmentation FAILED, embedded raw frame)"
        }
        Text(
            processedLabel,
            color = if (querySegmented) Color.White.copy(alpha = 0.85f)
                    else Color(0xFFFFB4A0),
            style = MaterialTheme.typography.labelMedium,
            fontWeight = FontWeight.SemiBold,
        )
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            DebugThumbAsync(
                label = "raw",
                path = queryRawPath,
                modifier = Modifier.weight(1f),
            )
            ProcessedBboxThumb(
                label = "processed",
                file = queryProcessedPath?.let { File(it) },
                alreadyPrepared = true,
                fallbackText = "(segmentation failed)",
                modifier = Modifier.weight(1f),
            )
        }

        Text(
            "Match  (cutout → composited + cropped)",
            color = Color.White.copy(alpha = 0.85f),
            style = MaterialTheme.typography.labelMedium,
            fontWeight = FontWeight.SemiBold,
        )
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            DebugThumbAsync(
                label = "raw",
                path = match.image.localPath,
                modifier = Modifier.weight(1f),
            )
            ProcessedBboxThumb(
                label = "processed",
                file = File(match.image.localPath),
                alreadyPrepared = false,
                modifier = Modifier.weight(1f),
            )
        }

        Text(
            "Hue histograms  (12 bins, summed across S × V)",
            color = Color.White.copy(alpha = 0.85f),
            style = MaterialTheme.typography.labelMedium,
            fontWeight = FontWeight.SemiBold,
        )
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            HueHistogramPanel(
                title = "query",
                hist = queryHist,
                modifier = Modifier.weight(1f),
            )
            HueHistogramPanel(
                title = "match",
                hist = matchEntry?.hist,
                modifier = Modifier.weight(1f),
            )
        }

        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(280.dp)
                .clip(RoundedCornerShape(8.dp))
                .background(Color(0x11FFFFFF)),
            contentAlignment = Alignment.Center,
        ) {
            ZoomableMatchImage(file = File(match.image.localPath))
        }
    }
}

@Composable
private fun DebugThumbAsync(
    label: String,
    path: String?,
    modifier: Modifier = Modifier,
    fallbackText: String? = null,
) {
    val context = LocalContext.current
    Column(
        modifier = modifier,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text(
            label,
            color = Color.White.copy(alpha = 0.7f),
            style = MaterialTheme.typography.labelSmall,
            maxLines = 1,
        )
        Spacer(Modifier.height(2.dp))
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .aspectRatio(1f)
                .clip(RoundedCornerShape(6.dp))
                .background(Color(0x22FFFFFF)),
            contentAlignment = Alignment.Center,
        ) {
            if (path != null) {
                AsyncImage(
                    model = ImageRequest.Builder(context).data(File(path)).crossfade(true).build(),
                    contentDescription = null,
                    contentScale = ContentScale.Fit,
                    modifier = Modifier.fillMaxSize(),
                )
            } else if (fallbackText != null) {
                Text(
                    fallbackText,
                    color = Color.White.copy(alpha = 0.6f),
                    style = MaterialTheme.typography.labelSmall,
                )
            }
        }
    }
}

/**
 * Renders a similarity-debug "processed" thumb with the histogram-considered bbox drawn on top.
 * If [alreadyPrepared] is true the file is loaded as-is (already composited on black by
 * `EmbeddingService.findSimilarWithDebug`); otherwise it is run through `prepareForEmbedding`.
 */
@Composable
private fun ProcessedBboxThumb(
    label: String,
    file: File?,
    alreadyPrepared: Boolean,
    modifier: Modifier = Modifier,
    fallbackText: String? = null,
) {
    data class Prepared(val bitmap: android.graphics.Bitmap, val bbox: android.graphics.Rect?)
    val prepared by produceState<Prepared?>(initialValue = null, file?.absolutePath, alreadyPrepared) {
        value = withContext(Dispatchers.IO) {
            val path = file?.absolutePath ?: return@withContext null
            val src = runCatching { BitmapFactory.decodeFile(path) }.getOrNull()
                ?: return@withContext null
            val bm = if (alreadyPrepared) src else {
                try { EmbeddingRepository.prepareForEmbedding(src) }
                finally { if (!src.isRecycled) src.recycle() }
            } ?: return@withContext null
            Prepared(bm, ColorHistogram.consideredBbox(bm))
        }
    }

    Column(
        modifier = modifier,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text(
            label,
            color = Color.White.copy(alpha = 0.7f),
            style = MaterialTheme.typography.labelSmall,
            maxLines = 1,
        )
        Spacer(Modifier.height(2.dp))
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .aspectRatio(1f)
                .clip(RoundedCornerShape(6.dp))
                .background(Color.Black),
            contentAlignment = Alignment.Center,
        ) {
            val p = prepared
            if (p != null) {
                val bm = p.bitmap
                Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    Image(
                        bitmap = bm.asImageBitmap(),
                        contentDescription = null,
                        contentScale = ContentScale.Fit,
                        modifier = Modifier.fillMaxSize(),
                    )
                    val bbox = p.bbox
                    if (bbox != null) {
                        androidx.compose.foundation.Canvas(modifier = Modifier.fillMaxSize()) {
                            val bw = bm.width.toFloat()
                            val bh = bm.height.toFloat()
                            if (bw <= 0f || bh <= 0f) return@Canvas
                            val scale = minOf(size.width / bw, size.height / bh)
                            val drawW = bw * scale
                            val drawH = bh * scale
                            val offX = (size.width - drawW) / 2f
                            val offY = (size.height - drawH) / 2f
                            val left = offX + bbox.left * scale
                            val top = offY + bbox.top * scale
                            val w = (bbox.right - bbox.left) * scale
                            val h = (bbox.bottom - bbox.top) * scale
                            drawRect(
                                color = Color(0xFF00E676),
                                topLeft = androidx.compose.ui.geometry.Offset(left, top),
                                size = androidx.compose.ui.geometry.Size(w, h),
                                style = androidx.compose.ui.graphics.drawscope.Stroke(width = 2.dp.toPx()),
                            )
                        }
                    }
                }
            } else if (file == null && fallbackText != null) {
                Text(
                    fallbackText,
                    color = Color.White.copy(alpha = 0.6f),
                    style = MaterialTheme.typography.labelSmall,
                )
            } else {
                CircularProgressIndicator(
                    modifier = Modifier.size(24.dp),
                    strokeWidth = 2.dp,
                )
            }
        }
    }
}

@Composable
private fun HueHistogramPanel(
    title: String,
    hist: FloatArray?,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text(
            title,
            color = Color.White.copy(alpha = 0.7f),
            style = MaterialTheme.typography.labelSmall,
        )
        Spacer(Modifier.height(2.dp))
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(80.dp)
                .clip(RoundedCornerShape(6.dp))
                .background(Color(0x22FFFFFF))
                .padding(horizontal = 4.dp, vertical = 4.dp),
            contentAlignment = Alignment.BottomCenter,
        ) {
            if (hist != null && hist.size == HIST_DIM) {
                HueHistogramBars(hist = hist)
            } else {
                Text(
                    "(none)",
                    color = Color.White.copy(alpha = 0.5f),
                    style = MaterialTheme.typography.labelSmall,
                )
            }
        }
    }
}

@Composable
private fun HueHistogramBars(hist: FloatArray) {
    val hueWeights = remember(hist) {
        val out = FloatArray(HIST_H_BINS)
        for (hb in 0 until HIST_H_BINS) {
            var sum = 0f
            for (sb in 0 until HIST_S_BINS) {
                for (vb in 0 until HIST_V_BINS) {
                    sum += hist[(hb * HIST_S_BINS + sb) * HIST_V_BINS + vb]
                }
            }
            out[hb] = sum
        }
        out
    }
    val maxW = hueWeights.maxOrNull() ?: 0f
    val safeMax = if (maxW <= 0f) 1f else maxW
    Row(
        modifier = Modifier.fillMaxSize(),
        verticalAlignment = Alignment.Bottom,
        horizontalArrangement = Arrangement.spacedBy(2.dp),
    ) {
        for (hb in 0 until HIST_H_BINS) {
            val hue = (hb + 0.5f) / HIST_H_BINS * 360f
            val color = Color.hsv(hue, 0.85f, 0.95f)
            val frac = (hueWeights[hb] / safeMax).coerceIn(0f, 1f)
            Column(
                modifier = Modifier.weight(1f).fillMaxHeight(),
                verticalArrangement = Arrangement.Bottom,
            ) {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .fillMaxHeight(if (frac < 0.02f) 0.02f else frac)
                        .background(color, RoundedCornerShape(topStart = 2.dp, topEnd = 2.dp)),
                )
            }
        }
    }
}

@Composable
private fun ZoomableMatchImage(file: File) {
    val context = LocalContext.current
    var scale by remember { mutableFloatStateOf(1f) }
    var offset by remember { mutableStateOf(Offset.Zero) }
    AsyncImage(
        model = ImageRequest.Builder(context).data(file).crossfade(true).build(),
        contentDescription = null,
        contentScale = ContentScale.Fit,
        modifier = Modifier
            .fillMaxSize()
            // Only intercept pinch (2 pointers) for zoom and single-finger pan once zoomed.
            // At scale 1, single-finger horizontal drags pass through to the parent
            // HorizontalPager so the user can swipe between matches.
            .pointerInput(Unit) {
                awaitEachGesture {
                    awaitFirstDown(requireUnconsumed = false)
                    do {
                        val event = awaitPointerEvent()
                        val pointerCount = event.changes.count { it.pressed }
                        if (pointerCount >= 2) {
                            val zoom = event.calculateZoom()
                            val pan = event.calculatePan()
                            val newScale = (scale * zoom).coerceIn(1f, 6f)
                            scale = newScale
                            offset = if (newScale <= 1.01f) Offset.Zero
                                     else Offset(offset.x + pan.x, offset.y + pan.y)
                            event.changes.forEach { it.consume() }
                        } else if (scale > 1.01f) {
                            val pan = event.calculatePan()
                            offset = Offset(offset.x + pan.x, offset.y + pan.y)
                            event.changes.forEach { it.consume() }
                        }
                        // else: single-finger drag at scale 1 — leave changes unconsumed so
                        // the HorizontalPager receives them.
                    } while (event.changes.any { it.pressed })
                }
            }
            .graphicsLayer {
                scaleX = scale
                scaleY = scale
                translationX = offset.x
                translationY = offset.y
            },
    )
}

private fun dot(a: FloatArray, b: FloatArray): Float {
    var s = 0f
    for (i in a.indices) s += a[i] * b[i]
    return s
}

private fun formatCos(value: Float?): String =
    if (value == null) "?" else String.format("%.3f", value)

// ============================================================================
//  Tab 2: Identify Gaps
// ============================================================================

@Composable
private fun IdentifyGapsTab(
    gapViewModel: WardrobeGapViewModel,
    wardrobeViewModel: WardrobeViewModel,
    profileViewModel: ProfileViewModel,
) {
    val isOffline = LocalIsOffline.current
    val state by gapViewModel.state.collectAsState()
    val wardrobeState by wardrobeViewModel.state.collectAsState()
    val profileState by profileViewModel.state.collectAsState()

    Box(modifier = Modifier.fillMaxSize()) {
        LazyColumn(
            contentPadding = PaddingValues(bottom = 24.dp),
        ) {
            item {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp, vertical = 12.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    Text(
                        stringResource(R.string.gap_description),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Button(
                        onClick = {
                            gapViewModel.analyze(
                                images = wardrobeState.images,
                                prefs  = profileState.preferences,
                            )
                        },
                        enabled = !state.isAnalyzing && wardrobeState.images.isNotEmpty() && !isOffline,
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        Icon(Icons.Default.AutoAwesome, contentDescription = null, modifier = Modifier.size(18.dp))
                        Spacer(Modifier.width(8.dp))
                        Text(if (state.analysis != null) stringResource(R.string.gap_reanalyze) else stringResource(R.string.gap_analyze))
                    }
                }
            }

            state.analysis?.let { analysis ->
                if (analysis.summary.isNotEmpty()) {
                    item {
                        HorizontalDivider()
                        Surface(
                            color = MaterialTheme.colorScheme.secondaryContainer,
                            modifier = Modifier.fillMaxWidth(),
                        ) {
                            Text(
                                analysis.summary,
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onSecondaryContainer,
                                modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp),
                            )
                        }
                        HorizontalDivider()
                    }
                }

                itemsIndexed(analysis.suggestions) { index, suggestion ->
                    GapSuggestionCard(
                        rank = index + 1,
                        suggestion = suggestion,
                        modifier = Modifier.padding(horizontal = 16.dp, vertical = 6.dp),
                    )
                }
            }

            if (!state.isAnalyzing && state.analysis == null && state.error == null) {
                item {
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(top = 48.dp),
                        contentAlignment = Alignment.Center,
                    ) {
                        Column(
                            horizontalAlignment = Alignment.CenterHorizontally,
                            verticalArrangement = Arrangement.spacedBy(8.dp),
                            modifier = Modifier.padding(horizontal = 32.dp),
                        ) {
                            Icon(
                                Icons.Default.TipsAndUpdates,
                                contentDescription = null,
                                modifier = Modifier.size(48.dp),
                                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                            Text(
                                stringResource(R.string.gap_empty_title),
                                style = MaterialTheme.typography.titleSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                            Text(
                                stringResource(R.string.gap_empty_hint),
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                }
            }
        }

        if (state.isAnalyzing) {
            AiProcessingOverlay(
                label = stringResource(R.string.ai_analyzing_wardrobe),
                modifier = Modifier.fillMaxSize(),
            )
        }

        state.error?.let { msg ->
            Snackbar(
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .padding(16.dp),
                action = { TextButton(onClick = gapViewModel::clearError) { Text(stringResource(R.string.action_ok)) } },
            ) { Text(msg) }
        }
    }
}
