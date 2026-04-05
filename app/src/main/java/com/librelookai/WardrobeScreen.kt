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
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.interaction.MutableInteractionSource
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
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.ArrowDropDown
import androidx.compose.material.icons.filled.AutoAwesome
import androidx.compose.material.icons.filled.CameraAlt
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.PhotoLibrary
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SmallFloatingActionButton
import androidx.compose.material3.Snackbar
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
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
            onToggleSelection = viewModel::toggleSelection,
            onClearSelection = viewModel::clearSelection,
            onDeleteSelected = viewModel::deleteSelected,
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

private data class TagCategory(val label: String, val tags: List<String>)

private fun List<DriveImage>.tagCategories(): List<TagCategory> {
    val types   = mapNotNull { it.tags?.type }.filter { it.isNotEmpty() }.toSortedSet()
    val cats    = mapNotNull { it.tags?.category }.filter { it.isNotEmpty() }.toSortedSet()
    val uses    = flatMap { it.tags?.uses ?: emptyList() }.toSortedSet()
    val colors  = flatMap { it.tags?.colors ?: emptyList() }.toSortedSet()
    return listOfNotNull(
        TagCategory("Type",     types.toList()).takeIf { it.tags.isNotEmpty() },
        TagCategory("Category", cats.toList()).takeIf { it.tags.isNotEmpty() },
        TagCategory("Uses",     uses.toList()).takeIf { it.tags.isNotEmpty() },
        TagCategory("Colors",   colors.toList()).takeIf { it.tags.isNotEmpty() },
    )
}

private fun DriveImage.allTagStrings() = buildSet {
    tags?.let { t ->
        if (t.type.isNotEmpty()) add(t.type)
        if (t.category.isNotEmpty()) add(t.category)
        addAll(t.uses)
        addAll(t.colors)
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
    onToggleSelection: (String) -> Unit,
    onClearSelection: () -> Unit,
    onDeleteSelected: () -> Unit,
    processingImageId: String?,
    modifier: Modifier = Modifier,
) {
    var cellSizeDp by rememberSaveable { mutableFloatStateOf(120f) }
    var pinchVisualScale by remember { mutableFloatStateOf(1f) }
    var selectedIndex by remember { mutableStateOf<Int?>(null) }
    var showDeleteDialog by remember { mutableStateOf(false) }

    // Filter state
    var selectedTags by remember { mutableStateOf(emptySet<String>()) }
    var expandedCategory by remember { mutableStateOf<String?>(null) }

    val tagCategories = remember(state.images) { state.images.tagCategories() }

    // AND filter: image must contain every selected tag
    val displayedImages = remember(state.images, selectedTags) {
        if (selectedTags.isEmpty()) state.images
        else state.images.filter { img -> selectedTags.all { it in img.allTagStrings() } }
    }

    // Clear viewer when filter changes to avoid stale index
    LaunchedEffect(selectedTags) { selectedIndex = null }

    val isSelectionMode = state.selectedIds.isNotEmpty()
    if (isSelectionMode) BackHandler(onBack = onClearSelection)

    Box(modifier = modifier.fillMaxSize()) {
        Column(Modifier.fillMaxSize()) {
            // ---- Tag filter bar ----
            if (tagCategories.isNotEmpty()) {
                LazyRow(
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 4.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    items(tagCategories) { category ->
                        val activeCount = category.tags.count { it in selectedTags }
                        Box {
                            FilterChip(
                                selected = activeCount > 0,
                                onClick = {
                                    expandedCategory =
                                        if (expandedCategory == category.label) null else category.label
                                },
                                label = {
                                    Text(if (activeCount > 0) "${category.label} ($activeCount)" else category.label)
                                },
                                trailingIcon = {
                                    Icon(Icons.Default.ArrowDropDown, null, Modifier.size(16.dp))
                                },
                            )
                            DropdownMenu(
                                expanded = expandedCategory == category.label,
                                onDismissRequest = { expandedCategory = null },
                            ) {
                                category.tags.forEach { tag ->
                                    val checked = tag in selectedTags
                                    DropdownMenuItem(
                                        text = {
                                            Row(
                                                verticalAlignment = Alignment.CenterVertically,
                                                horizontalArrangement = Arrangement.spacedBy(8.dp),
                                            ) {
                                                if (checked) Icon(
                                                    Icons.Default.Check,
                                                    contentDescription = null,
                                                    tint = MaterialTheme.colorScheme.primary,
                                                    modifier = Modifier.size(18.dp),
                                                ) else Spacer(Modifier.size(18.dp))
                                                Text(tag)
                                            }
                                        },
                                        onClick = {
                                            selectedTags =
                                                if (checked) selectedTags - tag else selectedTags + tag
                                        },
                                    )
                                }
                            }
                        }
                    }
                    if (selectedTags.isNotEmpty()) {
                        item {
                            TextButton(onClick = { selectedTags = emptySet() }) { Text("Clear") }
                        }
                    }
                }
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
                            if (selectedTags.isNotEmpty()) {
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
            FloatingActionButton(
                onClick = { showDeleteDialog = true },
                modifier = Modifier.align(Alignment.BottomEnd).padding(16.dp),
                containerColor = MaterialTheme.colorScheme.errorContainer,
                contentColor = MaterialTheme.colorScheme.onErrorContainer,
            ) {
                Icon(Icons.Default.Delete, contentDescription = "Delete selected")
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

    selectedIndex?.let { startIndex ->
        FullScreenViewer(
            images = displayedImages,
            initialIndex = startIndex.coerceIn(0, (displayedImages.size - 1).coerceAtLeast(0)),
            onDismiss = { selectedIndex = null },
            onTagImage = onTagImage,
            onRemoveBackground = onRemoveBackground,
            processingImageId = processingImageId,
        )
    }
}

// ---------- Full-screen image viewer ----------

@Composable
private fun FullScreenViewer(
    images: List<DriveImage>,
    initialIndex: Int,
    onDismiss: () -> Unit,
    onTagImage: (String) -> Unit,
    onRemoveBackground: (String) -> Unit,
    processingImageId: String?,
) {
    BackHandler(onBack = onDismiss)

    var pageScale by remember { mutableFloatStateOf(1f) }
    val pagerState = rememberPagerState(
        initialPage = initialIndex,
        pageCount = { images.size },
    )
    LaunchedEffect(pagerState.currentPage) { pageScale = 1f }

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
            modifier = Modifier
                .align(Alignment.TopEnd)
                .statusBarsPadding()
                .padding(top = 8.dp, end = 8.dp),
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

// ---------- AI processing overlay ----------

@Composable
private fun AiProcessingOverlay(modifier: Modifier = Modifier) {
    val transition = rememberInfiniteTransition(label = "ai")
    val rotation by transition.animateFloat(
        initialValue = 0f,
        targetValue = 360f,
        animationSpec = infiniteRepeatable(tween(2400, easing = LinearEasing)),
        label = "rotate",
    )
    val pulse by transition.animateFloat(
        initialValue = 0.5f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(tween(800), RepeatMode.Reverse),
        label = "pulse",
    )

    Box(
        modifier = modifier.background(Color.Black.copy(alpha = 0.55f)),
        contentAlignment = Alignment.Center,
    ) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            Box(contentAlignment = Alignment.Center) {
                CircularProgressIndicator(
                    modifier = Modifier.size(72.dp),
                    strokeWidth = 2.dp,
                    color = Color(0xFFFFD700),
                )
                Icon(
                    imageVector = Icons.Default.AutoAwesome,
                    contentDescription = null,
                    modifier = Modifier
                        .size(32.dp)
                        .rotate(rotation)
                        .graphicsLayer { alpha = pulse },
                    tint = Color(0xFFFFD700),
                )
            }
            Text(
                text = "AI is working…",
                color = Color.White,
                style = MaterialTheme.typography.bodyMedium,
            )
        }
    }
}

// ---------- Tags overlay ----------

@Composable
private fun TagsOverlay(
    tags: ClothingTags?,
    onTagImage: () -> Unit,
    onRemoveBackground: () -> Unit,
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
