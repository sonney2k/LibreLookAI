package com.librelookai

import android.Manifest
import android.content.pm.PackageManager
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.itemsIndexed
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.material.icons.Icons
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.ArrowDropDown
import androidx.compose.material.icons.filled.AutoAwesome
import androidx.compose.material.icons.filled.AutoFixHigh
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.CameraAlt
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.PhotoLibrary
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Sort
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.InputChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.SmallFloatingActionButton
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
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.lifecycle.viewmodel.compose.viewModel
import coil.compose.AsyncImage
import coil.request.ImageRequest

@Composable
fun WardrobeScreen(
    viewModel: WardrobeViewModel = viewModel(),
    onCreateStyleFromSelection: (Set<String>) -> Unit = {},
    onComposeStyleFromSelection: (Set<String>) -> Unit = {},
    modifier: Modifier = Modifier,
) {
    val state by viewModel.state.collectAsState()
    val context = LocalContext.current

    var hasCameraPermission by remember {
        mutableStateOf(
            ContextCompat.checkSelfPermission(context, Manifest.permission.CAMERA)
                == PackageManager.PERMISSION_GRANTED,
        )
    }
    val permissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission(),
    ) { granted ->
        hasCameraPermission = granted
        if (granted) viewModel.openCapture()
    }

    val galleryLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.PickMultipleVisualMedia(),
    ) { uris -> viewModel.uploadGalleryPhotos(uris) }

    when (state.view) {
        WardrobeView.GRID -> GridContent(
            state = state,
            onOpenCamera = {
                if (hasCameraPermission) viewModel.openCapture()
                else permissionLauncher.launch(Manifest.permission.CAMERA)
            },
            onOpenGallery = {
                galleryLauncher.launch(
                    PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly),
                )
            },
            onDismissError = viewModel::clearError,
            onTagImage = viewModel::tagImage,
            onRemoveBackground = viewModel::reprocessBackground,
            onUpdateTags = viewModel::updateTags,
            onRetagAll = viewModel::retagAll,
            onToggleSelection = viewModel::toggleSelection,
            onClearSelection = viewModel::clearSelection,
            onDeleteSelected = viewModel::deleteSelected,
            onCreateStyleFromSelection = onCreateStyleFromSelection,
            onComposeStyleFromSelection = onComposeStyleFromSelection,
            processingImageId = state.processingImageId,
            modifier = modifier,
        )
        WardrobeView.CAPTURE -> CaptureScreen(
            onPhotoTaken = viewModel::uploadPhoto,
            onCancel = viewModel::closeCapture,
            modifier = modifier,
        )
    }
}

internal enum class SortOption(val label: String) {
    DATE_DESC("Newest first"),
    DATE_ASC("Oldest first"),
    TYPE("Type"),
    CATEGORY("Category"),
}

internal data class TagCategory(val label: String, val tags: List<String>)

internal fun List<DriveImage>.tagCategories(): List<TagCategory> {
    fun collect(vararg lists: List<String>) = lists.flatMap { it }.toSortedSet().toList()
    return listOfNotNull(
        TagCategory("Type",        mapNotNull { it.tags?.type }.filter { it.isNotEmpty() }.toSortedSet().toList()).takeIf { it.tags.isNotEmpty() },
        TagCategory("Category",    mapNotNull { it.tags?.category }.filter { it.isNotEmpty() }.toSortedSet().toList()).takeIf { it.tags.isNotEmpty() },
        TagCategory("Uses",        collect(flatMap { it.tags?.uses ?: emptyList() })).takeIf { it.tags.isNotEmpty() },
        TagCategory("Colors",      collect(flatMap { it.tags?.colors ?: emptyList() })).takeIf { it.tags.isNotEmpty() },
        TagCategory("Seasonality", collect(flatMap { it.tags?.seasonality ?: emptyList() })).takeIf { it.tags.isNotEmpty() },
        TagCategory("Aesthetic",   collect(flatMap { it.tags?.aesthetic ?: emptyList() })).takeIf { it.tags.isNotEmpty() },
        TagCategory("Fit",         collect(flatMap { it.tags?.fit ?: emptyList() })).takeIf { it.tags.isNotEmpty() },
        TagCategory("Material",    collect(flatMap { it.tags?.material ?: emptyList() })).takeIf { it.tags.isNotEmpty() },
        TagCategory("Pattern",     collect(flatMap { it.tags?.pattern ?: emptyList() })).takeIf { it.tags.isNotEmpty() },
    )
}

internal fun DriveImage.allTagStrings() = buildSet {
    tags?.let { t ->
        if (t.type.isNotEmpty()) add(t.type)
        if (t.category.isNotEmpty()) add(t.category)
        addAll(t.uses); addAll(t.colors); addAll(t.seasonality)
        addAll(t.aesthetic); addAll(t.fit); addAll(t.material); addAll(t.pattern)
    }
}

internal fun DriveImage.tagStringsForCategory(categoryLabel: String): Set<String> {
    val t = tags ?: return emptySet()
    return when (categoryLabel) {
        "Type"        -> if (t.type.isNotEmpty()) setOf(t.type) else emptySet()
        "Category"    -> if (t.category.isNotEmpty()) setOf(t.category) else emptySet()
        "Uses"        -> t.uses.toSet()
        "Colors"      -> t.colors.toSet()
        "Seasonality" -> t.seasonality.toSet()
        "Aesthetic"   -> t.aesthetic.toSet()
        "Fit"         -> t.fit.toSet()
        "Material"    -> t.material.toSet()
        "Pattern"     -> t.pattern.toSet()
        else          -> emptySet()
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun TagFilterBar(
    tagCategories: List<TagCategory>,
    selectedTags: Map<String, Set<String>>,
    onTagsChanged: (Map<String, Set<String>>) -> Unit,
    modifier: Modifier = Modifier,
) {
    if (tagCategories.isEmpty()) return
    var expandedCategory by remember { mutableStateOf<String?>(null) }
    LazyRow(
        modifier = modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 4.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        items(tagCategories) { category ->
            val catSelected = selectedTags[category.label] ?: emptySet()
            val activeCount = catSelected.size
            Box {
                FilterChip(
                    selected = activeCount > 0,
                    onClick = {
                        expandedCategory = if (expandedCategory == category.label) null else category.label
                    },
                    label = { Text(if (activeCount > 0) "${category.label} ($activeCount)" else category.label) },
                    trailingIcon = { Icon(Icons.Default.ArrowDropDown, null, Modifier.size(16.dp)) },
                )
                DropdownMenu(
                    expanded = expandedCategory == category.label,
                    onDismissRequest = { expandedCategory = null },
                ) {
                    category.tags.forEach { tag ->
                        val checked = tag in catSelected
                        DropdownMenuItem(
                            text = {
                                Row(
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                                ) {
                                    if (checked) Icon(Icons.Default.Check, null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(18.dp))
                                    else Spacer(Modifier.size(18.dp))
                                    Text(tag)
                                }
                            },
                            onClick = {
                                val updated = if (checked) catSelected - tag else catSelected + tag
                                onTagsChanged(selectedTags + (category.label to updated))
                            },
                        )
                    }
                }
            }
        }
        if (selectedTags.values.any { it.isNotEmpty() }) {
            item { TextButton(onClick = { onTagsChanged(emptyMap()) }) { Text("Clear") } }
        }
    }
}

// ---------- Grid ----------

@OptIn(ExperimentalMaterial3Api::class, ExperimentalFoundationApi::class)
@Composable
private fun GridContent(
    state: WardrobeUiState,
    onOpenCamera: () -> Unit,
    onOpenGallery: () -> Unit,
    onDismissError: () -> Unit,
    onTagImage: (String) -> Unit,
    onRemoveBackground: (String) -> Unit,
    onUpdateTags: (String, ClothingTags) -> Unit,
    onRetagAll: () -> Unit,
    onToggleSelection: (String) -> Unit,
    onClearSelection: () -> Unit,
    onDeleteSelected: () -> Unit,
    onCreateStyleFromSelection: (Set<String>) -> Unit,
    onComposeStyleFromSelection: (Set<String>) -> Unit,
    processingImageId: String?,
    modifier: Modifier = Modifier,
) {
    var cellSizeDp by rememberSaveable { mutableFloatStateOf(120f) }
    var pinchVisualScale by remember { mutableFloatStateOf(1f) }
    var selectedIndex by remember { mutableStateOf<Int?>(null) }
    var showDeleteDialog by remember { mutableStateOf(false) }
    var showRetagDialog by remember { mutableStateOf(false) }

    // Filter + sort state
    var selectedTags by remember { mutableStateOf(emptyMap<String, Set<String>>()) }
    var sortBy by remember { mutableStateOf(SortOption.DATE_DESC) }

    val tagCategories = remember(state.images) { state.images.tagCategories() }

    // OR within each category, AND across categories
    val filteredImages = remember(state.images, selectedTags) {
        val activeFilters = selectedTags.filter { (_, tags) -> tags.isNotEmpty() }
        if (activeFilters.isEmpty()) state.images
        else state.images.filter { img ->
            activeFilters.all { (categoryLabel, catTags) ->
                catTags.any { it in img.tagStringsForCategory(categoryLabel) }
            }
        }
    }

    val displayedImages = remember(filteredImages, sortBy) {
        when (sortBy) {
            SortOption.DATE_DESC -> filteredImages
            SortOption.DATE_ASC  -> filteredImages.reversed()
            SortOption.TYPE      -> filteredImages.sortedBy { it.tags?.type?.lowercase() ?: "" }
            SortOption.CATEGORY  -> filteredImages.sortedBy { it.tags?.category?.lowercase() ?: "" }
        }
    }

    // Clear viewer when filter/sort changes to avoid stale index
    LaunchedEffect(selectedTags, sortBy) { selectedIndex = null }

    val isSelectionMode = state.selectedIds.isNotEmpty()
    if (isSelectionMode) BackHandler(onBack = onClearSelection)

    Box(modifier = modifier.fillMaxSize()) {
        Column(Modifier.fillMaxSize()) {
            // ---- Tag filter bar + sort ----
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
                IconButton(
                    onClick = { if (!state.isRetagging) showRetagDialog = true },
                    modifier = Modifier.padding(end = 0.dp),
                ) {
                    if (state.isRetagging)
                        CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp)
                    else
                        Icon(Icons.Default.Refresh, contentDescription = "Re-scan all tags")
                }
                SortButton(
                    sortBy = sortBy,
                    onSortChanged = { sortBy = it },
                    modifier = Modifier.padding(end = 4.dp),
                )
            }

            // ---- Main content ----
            when {
                state.isLoading -> {
                    Box(Modifier.weight(1f).fillMaxWidth(), contentAlignment = Alignment.Center) {
                        CircularProgressIndicator()
                    }
                }
                displayedImages.isEmpty() && !state.isProcessing && !state.isUploading -> {
                    Box(Modifier.weight(1f).fillMaxWidth(), contentAlignment = Alignment.Center) {
                        Column(
                            horizontalAlignment = Alignment.CenterHorizontally,
                            verticalArrangement = Arrangement.Center,
                        ) {
                            if (selectedTags.values.any { it.isNotEmpty() }) {
                                Text("No items match the filter", style = MaterialTheme.typography.bodyLarge)
                            } else {
                                Text("No outfits yet", style = MaterialTheme.typography.bodyLarge)
                                Spacer(Modifier.height(4.dp))
                                Text(
                                    "Take a photo or pick from your gallery",
                                    style = MaterialTheme.typography.bodyMedium,
                                )
                            }
                        }
                    }
                }
                else -> {
                    Box(
                        modifier = Modifier
                            .weight(1f)
                            .fillMaxWidth()
                            .pointerInput(Unit) {
                                awaitEachGesture {
                                    awaitFirstDown(requireUnconsumed = false, pass = PointerEventPass.Initial)
                                    var prevDistance = -1f
                                    do {
                                        val event = awaitPointerEvent(PointerEventPass.Initial)
                                        val pressed = event.changes.filter { it.pressed }
                                        if (pressed.size >= 2) {
                                            val dist = (pressed[1].position - pressed[0].position).getDistance()
                                            if (prevDistance > 0f) pinchVisualScale *= (dist / prevDistance)
                                            prevDistance = dist
                                            pressed.forEach { it.consume() }
                                        } else prevDistance = -1f
                                    } while (event.changes.any { it.pressed })
                                    cellSizeDp = (cellSizeDp * pinchVisualScale).coerceIn(56f, 320f)
                                    pinchVisualScale = 1f
                                }
                            },
                    ) {
                        LazyVerticalGrid(
                            columns = GridCells.Adaptive(cellSizeDp.dp),
                            modifier = Modifier.fillMaxSize().graphicsLayer {
                                clip = false
                                scaleX = pinchVisualScale
                                scaleY = pinchVisualScale
                            },
                        ) {
                            itemsIndexed(displayedImages, key = { _, img -> img.driveId }) { index, image ->
                                val isSelected = state.selectedIds.contains(image.driveId)
                                val ctx = LocalContext.current
                                Box(
                                    modifier = Modifier
                                        .aspectRatio(1f)
                                        .padding(1.dp)
                                        .combinedClickable(
                                            onClick = {
                                                if (isSelectionMode) onToggleSelection(image.driveId)
                                                else selectedIndex = index
                                            },
                                            onLongClick = { onToggleSelection(image.driveId) },
                                        ),
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
                                                .background(Color.White.copy(alpha = 0.4f)),
                                            contentAlignment = Alignment.TopEnd,
                                        ) {
                                            Icon(
                                                imageVector = Icons.Default.CheckCircle,
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
        }

        // Progress pill
        val overlayLabel = when {
            state.isRetagging ->
                "Re-scanning tags (${state.retagDone + 1}/${state.retagTotal})…"
            state.isProcessing && state.batchTotal > 1 ->
                "Removing background (${state.batchDone + 1}/${state.batchTotal})…"
            state.isUploading && state.batchTotal > 1 ->
                "Uploading (${state.batchDone + 1}/${state.batchTotal})…"
            state.isProcessing -> "Removing background…"
            state.isUploading  -> "Uploading to Drive…"
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

        // Speed-dial FAB
        var fabExpanded by remember { mutableStateOf(false) }
        if (isSelectionMode) {
            Column(
                modifier = Modifier.align(Alignment.BottomEnd).padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
                horizontalAlignment = Alignment.End,
            ) {
                ExtendedFloatingActionButton(
                    onClick = { onCreateStyleFromSelection(state.selectedIds) },
                    containerColor = MaterialTheme.colorScheme.primaryContainer,
                    contentColor = MaterialTheme.colorScheme.onPrimaryContainer,
                    icon = { Icon(Icons.Default.Edit, contentDescription = null) },
                    text = { Text("Create outfit manually") },
                )
                ExtendedFloatingActionButton(
                    onClick = { onComposeStyleFromSelection(state.selectedIds) },
                    containerColor = MaterialTheme.colorScheme.tertiaryContainer,
                    contentColor = MaterialTheme.colorScheme.onTertiaryContainer,
                    icon = { Icon(Icons.Default.AutoFixHigh, contentDescription = null) },
                    text = { Text("Compose with AI") },
                )
                ExtendedFloatingActionButton(
                    onClick = { showDeleteDialog = true },
                    containerColor = MaterialTheme.colorScheme.errorContainer,
                    contentColor = MaterialTheme.colorScheme.onErrorContainer,
                    icon = { Icon(Icons.Default.Delete, contentDescription = null) },
                    text = { Text("Delete") },
                )
            }
        } else {
            if (fabExpanded) {
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .background(Color.Black.copy(alpha = 0.3f))
                        .clickable(
                            interactionSource = remember { MutableInteractionSource() },
                            indication = null,
                        ) { fabExpanded = false },
                )
            }
            SpeedDialFab(
                expanded = fabExpanded,
                onToggle = { fabExpanded = !fabExpanded },
                onCamera = { fabExpanded = false; onOpenCamera() },
                onGallery = { fabExpanded = false; onOpenGallery() },
                modifier = Modifier.align(Alignment.BottomEnd),
            )
        }

        state.error?.let { msg ->
            Snackbar(
                modifier = Modifier
                    .align(Alignment.BottomStart)
                    .padding(start = 8.dp, end = 96.dp, bottom = 8.dp),
                action = { TextButton(onClick = onDismissError) { Text("Dismiss") } },
            ) { Text(msg) }
        }
    }

    if (showDeleteDialog) {
        AlertDialog(
            onDismissRequest = { showDeleteDialog = false },
            title = { Text("Delete items?") },
            text = { Text("Are you sure you want to delete ${state.selectedIds.size} selected items? This cannot be undone.") },
            confirmButton = {
                TextButton(
                    onClick = {
                        onDeleteSelected()
                        showDeleteDialog = false
                    }
                ) {
                    Text("Delete", color = MaterialTheme.colorScheme.error)
                }
            },
            dismissButton = {
                TextButton(onClick = { showDeleteDialog = false }) {
                    Text("Cancel")
                }
            }
        )
    }

    if (showRetagDialog) {
        AlertDialog(
            onDismissRequest = { showRetagDialog = false },
            title = { Text("Re-scan all tags?") },
            text = { Text("Gemini will re-classify every item in your wardrobe. This will overwrite any manual tag edits and may take a while.") },
            confirmButton = {
                TextButton(onClick = { onRetagAll(); showRetagDialog = false }) {
                    Text("Re-scan")
                }
            },
            dismissButton = {
                TextButton(onClick = { showRetagDialog = false }) { Text("Cancel") }
            },
        )
    }

    selectedIndex?.let { startIndex ->
        FullScreenViewer(
            images = displayedImages,
            initialIndex = startIndex.coerceIn(0, (displayedImages.size - 1).coerceAtLeast(0)),
            allTagCategories = tagCategories,
            onDismiss = { selectedIndex = null },
            onTagImage = onTagImage,
            onRemoveBackground = onRemoveBackground,
            onUpdateTags = onUpdateTags,
            processingImageId = processingImageId,
        )
    }
}

// ---------- Full-screen image viewer ----------

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun FullScreenViewer(
    images: List<DriveImage>,
    initialIndex: Int,
    allTagCategories: List<TagCategory>,
    onDismiss: () -> Unit,
    onTagImage: (String) -> Unit,
    onRemoveBackground: (String) -> Unit,
    onUpdateTags: (String, ClothingTags) -> Unit,
    processingImageId: String?,
) {
    BackHandler(onBack = onDismiss)

    var pageScale by remember { mutableFloatStateOf(1f) }
    val pagerState = rememberPagerState(
        initialPage = initialIndex,
        pageCount = { images.size },
    )
    LaunchedEffect(pagerState.currentPage) { pageScale = 1f }

    var showTagEdit by remember { mutableStateOf(false) }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Color.Black),
    ) {
        HorizontalPager(
            state = pagerState,
            userScrollEnabled = pageScale <= 1.01f,
            modifier = Modifier.fillMaxSize(),
        ) { page ->
            ZoomableImage(
                localPath = images[page].localPath,
                name = images[page].name,
                cacheKey = "${images[page].driveId}_${images[page].version}",
                onScaleChanged = { s -> if (page == pagerState.currentPage) pageScale = s },
            )
        }

        IconButton(
            onClick = onDismiss,
            modifier = Modifier.align(Alignment.TopStart).statusBarsPadding().padding(8.dp),
        ) {
            Icon(Icons.Default.Close, contentDescription = "Close", tint = Color.White)
        }

        Text(
            text = "${pagerState.currentPage + 1} / ${images.size}",
            color = Color.White,
            style = MaterialTheme.typography.labelMedium,
            modifier = Modifier.align(Alignment.TopCenter).statusBarsPadding().padding(top = 12.dp),
        )

        val currentImage = images[pagerState.currentPage]
        if (currentImage.driveId == processingImageId) {
            AiProcessingOverlay(modifier = Modifier.fillMaxSize())
        }

        TagsOverlay(
            tags = currentImage.tags,
            onTagImage = { onTagImage(currentImage.driveId) },
            onRemoveBackground = { onRemoveBackground(currentImage.driveId) },
            onEditTags = { showTagEdit = true },
            modifier = Modifier
                .align(Alignment.TopEnd)
                .statusBarsPadding()
                .padding(top = 8.dp, end = 8.dp),
        )
    }

    if (showTagEdit) {
        val currentImage = images[pagerState.currentPage]
        TagEditSheet(
            image = currentImage,
            allTagCategories = allTagCategories,
            onSave = { tags ->
                onUpdateTags(currentImage.driveId, tags)
                showTagEdit = false
            },
            onDismiss = { showTagEdit = false },
        )
    }
}

@Composable
private fun ZoomableImage(
    localPath: String,
    name: String,
    cacheKey: String = localPath,
    onScaleChanged: (Float) -> Unit = {},
) {
    var scale by remember { mutableFloatStateOf(1f) }
    var offset by remember { mutableStateOf(Offset.Zero) }
    val ctx = LocalContext.current

    AsyncImage(
        model = remember(cacheKey) {
            ImageRequest.Builder(ctx)
                .data(localPath)
                .memoryCacheKey(cacheKey)
                .build()
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
                                    // Shift translation so the focal point stays fixed
                                    val cx = size.width / 2f
                                    val cy = size.height / 2f
                                    offset = Offset(
                                        (focal.x - cx) * (1f - delta) + offset.x * delta,
                                        (focal.y - cy) * (1f - delta) + offset.y * delta,
                                    )
                                    scale = newScale
                                    if (scale <= 1f) offset = Offset.Zero
                                    onScaleChanged(scale)
                                }
                                prevDistance = dist
                                pressed.forEach { it.consume() }
                            }
                            // Pan while zoomed — also consumes so pager doesn't swipe.
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

// AiProcessingOverlay lives in AiProcessingOverlay.kt (shared)

// ---------- Tags overlay ----------

@Composable
private fun TagsOverlay(
    tags: ClothingTags?,
    onTagImage: () -> Unit,
    onRemoveBackground: () -> Unit,
    onEditTags: () -> Unit,
    modifier: Modifier = Modifier,
) {
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
            if (tags != null) {
                Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                    if (tags.type.isNotEmpty()) TagChip(tags.type)
                    if (tags.category.isNotEmpty()) TagChip(tags.category)
                }
                if (tags.uses.isNotEmpty()) {
                    Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                        tags.uses.forEach { TagChip(it) }
                    }
                }
                if (tags.colors.isNotEmpty()) {
                    Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                        tags.colors.forEach { TagChip(it) }
                    }
                }
            }
            Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                TextButton(onClick = onTagImage, contentPadding = PaddingValues(horizontal = 8.dp, vertical = 4.dp)) {
                    Text("Detect tags", color = Color.White, style = MaterialTheme.typography.labelSmall)
                }
                TextButton(onClick = onRemoveBackground, contentPadding = PaddingValues(horizontal = 8.dp, vertical = 4.dp)) {
                    Text("Remove BG", color = Color.White, style = MaterialTheme.typography.labelSmall)
                }
                TextButton(onClick = onEditTags, contentPadding = PaddingValues(horizontal = 8.dp, vertical = 4.dp)) {
                    Text("Edit tags", color = Color.White, style = MaterialTheme.typography.labelSmall)
                }
            }
        }
    }
}

// ---------- Tag edit sheet ----------

// Predefined suggestion lists matching the Gemini classify prompt
private val PRESET_USES        = listOf("casual", "formal", "business", "sport", "outdoor", "beach", "evening")
private val PRESET_SEASONALITY = listOf("spring", "summer", "fall", "winter")
private val PRESET_AESTHETIC   = listOf("minimalist", "streetwear", "preppy", "bohemian", "classic", "sporty", "romantic", "edgy", "business-casual", "luxury")
private val PRESET_FIT         = listOf("slim", "regular", "relaxed", "oversized", "tailored")
private val PRESET_MATERIAL    = listOf("cotton", "denim", "wool", "leather", "polyester", "linen", "silk", "knit")
private val PRESET_PATTERN     = listOf("solid", "stripes", "plaid", "floral", "geometric", "animal-print", "graphic", "camo", "abstract")

@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
private fun TagEditSheet(
    image: DriveImage,
    allTagCategories: List<TagCategory>,
    onSave: (ClothingTags) -> Unit,
    onDismiss: () -> Unit,
) {
    var type        by remember { mutableStateOf(image.tags?.type        ?: "") }
    var category    by remember { mutableStateOf(image.tags?.category    ?: "") }
    var uses        by remember { mutableStateOf(image.tags?.uses        ?: emptyList()) }
    var colors      by remember { mutableStateOf(image.tags?.colors      ?: emptyList()) }
    var seasonality by remember { mutableStateOf(image.tags?.seasonality ?: emptyList()) }
    var aesthetic   by remember { mutableStateOf(image.tags?.aesthetic   ?: emptyList()) }
    var fit         by remember { mutableStateOf(image.tags?.fit         ?: emptyList()) }
    var material    by remember { mutableStateOf(image.tags?.material    ?: emptyList()) }
    var pattern     by remember { mutableStateOf(image.tags?.pattern     ?: emptyList()) }

    // Merge presets with anything already in the wardrobe for richer suggestions
    fun suggestions(label: String, presets: List<String>): List<String> {
        val fromWardrobe = allTagCategories.find { it.label == label }?.tags.orEmpty()
        return (presets + fromWardrobe).distinct().sorted()
    }

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .navigationBarsPadding()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 20.dp)
                .padding(bottom = 24.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            // Header
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text("Edit Tags", style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f))
                TextButton(onClick = {
                    onSave(ClothingTags(
                        type        = type.trim(),
                        category    = category.trim(),
                        uses        = uses.map { it.trim() }.filter { it.isNotEmpty() },
                        colors      = colors.map { it.trim() }.filter { it.isNotEmpty() },
                        seasonality = seasonality.map { it.trim() }.filter { it.isNotEmpty() },
                        aesthetic   = aesthetic.map { it.trim() }.filter { it.isNotEmpty() },
                        fit         = fit.map { it.trim() }.filter { it.isNotEmpty() },
                        material    = material.map { it.trim() }.filter { it.isNotEmpty() },
                        pattern     = pattern.map { it.trim() }.filter { it.isNotEmpty() },
                    ))
                }) { Text("Save") }
            }

            HorizontalDivider()

            OutlinedTextField(
                value = type,
                onValueChange = { type = it },
                label = { Text("Type") },
                placeholder = { Text("e.g. T-shirt, Chinos, Puffer jacket") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
            )

            OutlinedTextField(
                value = category,
                onValueChange = { category = it },
                label = { Text("Category") },
                placeholder = { Text("tops, bottoms, outerwear, footwear, accessories, dress, suit") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
            )

            ChipListEditor(
                label = "Uses",
                values = uses,
                suggestions = suggestions("Uses", PRESET_USES).filter { it !in uses },
                onAdd    = { uses = uses + it },
                onRemove = { uses = uses - it },
            )

            ChipListEditor(
                label = "Colors",
                values = colors,
                suggestions = suggestions("Colors", emptyList()).filter { it !in colors },
                onAdd    = { colors = colors + it },
                onRemove = { colors = colors - it },
            )

            ChipListEditor(
                label = "Seasonality",
                values = seasonality,
                suggestions = suggestions("Seasonality", PRESET_SEASONALITY).filter { it !in seasonality },
                onAdd    = { seasonality = seasonality + it },
                onRemove = { seasonality = seasonality - it },
            )

            ChipListEditor(
                label = "Aesthetic",
                values = aesthetic,
                suggestions = suggestions("Aesthetic", PRESET_AESTHETIC).filter { it !in aesthetic },
                onAdd    = { aesthetic = aesthetic + it },
                onRemove = { aesthetic = aesthetic - it },
            )

            ChipListEditor(
                label = "Fit",
                values = fit,
                suggestions = suggestions("Fit", PRESET_FIT).filter { it !in fit },
                onAdd    = { fit = fit + it },
                onRemove = { fit = fit - it },
            )

            ChipListEditor(
                label = "Material",
                values = material,
                suggestions = suggestions("Material", PRESET_MATERIAL).filter { it !in material },
                onAdd    = { material = material + it },
                onRemove = { material = material - it },
            )

            ChipListEditor(
                label = "Pattern",
                values = pattern,
                suggestions = suggestions("Pattern", PRESET_PATTERN).filter { it !in pattern },
                onAdd    = { pattern = pattern + it },
                onRemove = { pattern = pattern - it },
            )
        }
    }
}

@OptIn(ExperimentalLayoutApi::class, ExperimentalMaterial3Api::class)
@Composable
private fun ChipListEditor(
    label: String,
    values: List<String>,
    suggestions: List<String>,
    onAdd: (String) -> Unit,
    onRemove: (String) -> Unit,
) {
    var inputText by remember { mutableStateOf("") }
    var showSuggestions by remember { mutableStateOf(false) }

    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Text(label, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)

        // Current values as removable chips
        FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            values.forEach { value ->
                InputChip(
                    selected = false,
                    onClick = {},
                    label = { Text(value) },
                    trailingIcon = {
                        Icon(
                            Icons.Default.Close,
                            contentDescription = "Remove $value",
                            modifier = Modifier.size(14.dp).clickable { onRemove(value) },
                        )
                    },
                )
            }
        }

        // Add field
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedTextField(
                value = inputText,
                onValueChange = { inputText = it; showSuggestions = it.isNotEmpty() },
                placeholder = { Text("Add custom…") },
                singleLine = true,
                trailingIcon = if (inputText.isNotBlank()) {
                    {
                        IconButton(onClick = {
                            onAdd(inputText.trim())
                            inputText = ""
                            showSuggestions = false
                        }) {
                            Icon(Icons.Default.Add, contentDescription = "Add")
                        }
                    }
                } else null,
                modifier = Modifier.weight(1f),
            )
        }

        // Existing suggestions (filtered by input if any)
        val filtered = if (inputText.isBlank()) suggestions
                       else suggestions.filter { it.contains(inputText, ignoreCase = true) }
        if (filtered.isNotEmpty()) {
            FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                filtered.forEach { suggestion ->
                    FilterChip(
                        selected = false,
                        onClick = { onAdd(suggestion); inputText = "" },
                        label = { Text(suggestion) },
                    )
                }
            }
        }
    }
}

@Composable
private fun TagChip(label: String) {
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

// ---------- Speed-dial FAB ----------

@Composable
private fun SpeedDialFab(
    expanded: Boolean,
    onToggle: () -> Unit,
    onCamera: () -> Unit,
    onGallery: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val rotation by animateFloatAsState(targetValue = if (expanded) 45f else 0f, label = "fab_rotate")

    Column(
        modifier = modifier.padding(16.dp),
        horizontalAlignment = Alignment.End,
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        AnimatedVisibility(
            visible = expanded,
            enter = fadeIn() + expandVertically(expandFrom = Alignment.Bottom),
            exit = fadeOut() + shrinkVertically(shrinkTowards = Alignment.Bottom),
        ) {
            Column(
                horizontalAlignment = Alignment.End,
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                SpeedDialItem(label = "Gallery", icon = Icons.Default.PhotoLibrary, onClick = onGallery)
                SpeedDialItem(label = "Camera",  icon = Icons.Default.CameraAlt,    onClick = onCamera)
            }
        }

        FloatingActionButton(onClick = onToggle) {
            Icon(
                imageVector = Icons.Default.Add,
                contentDescription = if (expanded) "Close menu" else "Add outfit",
                modifier = Modifier.rotate(rotation),
            )
        }
    }
}

// ---------- Sort button ----------

@Composable
private fun SortButton(
    sortBy: SortOption,
    onSortChanged: (SortOption) -> Unit,
    modifier: Modifier = Modifier,
) {
    var expanded by remember { mutableStateOf(false) }
    Box(modifier = modifier) {
        IconButton(onClick = { expanded = true }) {
            Icon(Icons.Default.Sort, contentDescription = "Sort")
        }
        DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
            SortOption.values().forEach { option ->
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

@Composable
private fun SpeedDialItem(label: String, icon: ImageVector, onClick: () -> Unit) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Surface(
            shape = MaterialTheme.shapes.small,
            tonalElevation = 2.dp,
            shadowElevation = 2.dp,
        ) {
            Text(
                text = label,
                modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp),
                style = MaterialTheme.typography.labelLarge,
            )
        }
        Spacer(Modifier.width(12.dp))
        SmallFloatingActionButton(onClick = onClick) {
            Icon(icon, contentDescription = label)
        }
    }
}
