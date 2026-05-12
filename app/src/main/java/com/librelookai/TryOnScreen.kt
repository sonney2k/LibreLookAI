package com.librelookai

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.AutoAwesome
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.Save
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.compose.ui.window.DialogWindowProvider
import coil.compose.AsyncImage
import coil.request.ImageRequest
import java.io.File
import java.text.DateFormat
import java.util.Date

/**
 * Full-screen unified Try-on experience:
 *  - Compose: edit the set of items, explain "all of these will be worn", then generate.
 *  - Preview: zoomable generated image with Save / Regenerate / Change items actions.
 *  - History: grid of saved try-ons; tap one to view image + items; long-press delete.
 */
@OptIn(ExperimentalMaterial3Api::class, ExperimentalFoundationApi::class, ExperimentalLayoutApi::class)
@Composable
fun TryOnComposerScreen(
    tryOnViewModel: TryOnViewModel,
    wardrobeViewModel: WardrobeViewModel,
    profileViewModel: ProfileViewModel,
    shoppingClosetViewModel: ShoppingClosetViewModel = androidx.lifecycle.viewmodel.compose.viewModel(),
    onShowItemInWardrobe: (DriveImage) -> Unit = {},
) {
    val state by tryOnViewModel.state.collectAsState()
    if (!state.isComposerOpen) return

    val wardrobeState by wardrobeViewModel.state.collectAsState()
    val profileState by profileViewModel.state.collectAsState()
    val shoppingClosetState by shoppingClosetViewModel.state.collectAsState()
    // Try-on must resolve item IDs that originate from either the wardrobe or the shopping
    // closet (FAB available in both screens). Merge so id lookups succeed regardless of source.
    val combinedImages = remember(wardrobeState.images, shoppingClosetState.items) {
        wardrobeState.images + shoppingClosetState.items
    }

    val parentContext = LocalContext.current
    val parentConfiguration = LocalConfiguration.current
    val barInsets = LocalSystemBarsPadding.current
    Dialog(
        onDismissRequest = tryOnViewModel::close,
        properties = DialogProperties(
            usePlatformDefaultWidth = false,
            dismissOnClickOutside   = false,
            dismissOnBackPress      = true,
        ),
    ) {
        // Force the dialog window to fill the screen edge-to-edge. Without this, the bottom
        // action row (Save / Try again / Save to gallery) gets clipped behind the navigation
        // bar — same root cause as MatchPreviewDialog (see ShoppingHelperScreen.kt).
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
        val viewing = state.viewingTryOn
        var detailViewerImage by remember { mutableStateOf<DriveImage?>(null) }
        if (detailViewerImage != null && viewing != null) {
            val items = remember(viewing.itemNames, combinedImages) {
                viewing.itemNames.mapNotNull { n -> combinedImages.firstOrNull { it.name == n } }
            }
            val startIdx = items.indexOfFirst { it.driveId == detailViewerImage!!.driveId }.coerceAtLeast(0)
            val allTagCategories = remember(items) { items.tagCategories() }
            FullScreenViewer(
                images = items,
                initialIndex = startIdx,
                allTagCategories = allTagCategories,
                onDismiss = { detailViewerImage = null },
                onTagImage = wardrobeViewModel::tagImage,
                onRemoveBackground = wardrobeViewModel::reprocessBackground,
                onRotateImage = wardrobeViewModel::rotateImage,
                onUpdateTags = wardrobeViewModel::updateTags,
                onDeleteItem = { driveId -> wardrobeViewModel.deleteItems(setOf(driveId)) },
                onMoveToLocation = wardrobeViewModel::moveItemsToLocation,
                onCreateOutfitFromSelection = {},
                onFixCutoutBg = wardrobeViewModel::fixCutoutBgForItem,
                onLoadOriginal = wardrobeViewModel::ensureOriginalCached,
                locations = emptyList(),
                activeLocationId = "",
                processingImageId = wardrobeState.processingImageId,
                writeMode = true,
            )
            return@CompositionLocalProvider
        }
        Scaffold(
            modifier = Modifier.fillMaxSize().padding(barInsets),
            topBar = {
                TopAppBar(
                    title = {
                        Text(
                            when {
                                viewing != null         -> stringResource(R.string.tryon_history_detail_title)
                                state.isHistoryOpen     -> stringResource(R.string.tryon_history_title)
                                else                    -> stringResource(R.string.tryon_title)
                            },
                            fontWeight = FontWeight.SemiBold,
                        )
                    },
                    navigationIcon = {
                        IconButton(onClick = {
                            Analytics.action("TryOn", "close")
                            when {
                                viewing != null && state.historyDetailIsRoot -> tryOnViewModel.close()
                                viewing != null     -> tryOnViewModel.dismissViewingTryOn()
                                state.isHistoryOpen -> tryOnViewModel.closeHistory()
                                else                -> tryOnViewModel.close()
                            }
                        }) { Icon(Icons.Default.Close, contentDescription = stringResource(R.string.action_dismiss)) }
                    },
                )
            },
        ) { innerPadding ->
            Box(Modifier.fillMaxSize().padding(innerPadding)) {
                when {
                    viewing != null -> TryOnDetailContent(
                        tryOn = viewing,
                        wardrobeImages = combinedImages,
                        onDelete = { tryOnViewModel.deleteTryOn(viewing) },
                        onItemTap = { img -> detailViewerImage = img },
                    )

                    state.isHistoryOpen -> TryOnHistoryGrid(
                        history = state.history,
                        onOpen  = { tryOnViewModel.viewTryOn(it) },
                    )

                    state.resultPath != null -> TryOnResultContent(
                        state = state,
                        onSave = {
                            Analytics.action("TryOn/Result", "save")
                            tryOnViewModel.saveCurrent(combinedImages)
                        },
                        onChangeItems = {
                            Analytics.action("TryOn/Result", "change_items")
                            tryOnViewModel.openComposer(state.itemIds)
                        },
                    )

                    else -> TryOnComposerContent(
                        state = state,
                        wardrobeImages = combinedImages,
                        onRemoveItem = tryOnViewModel::removeItem,
                        onAddItems = { ids -> ids.forEach(tryOnViewModel::addItem) },
                        onGenerate = {
                            Analytics.action("TryOn/Composer", "generate", mapOf("count" to state.itemIds.size.toString()))
                            tryOnViewModel.generate(
                                personFiles     = profileViewModel.tryOnFiles(),
                                wardrobeImages  = combinedImages,
                                preferences     = profileState.preferences.preferences,
                            )
                        },
                    )
                }

                // Generating overlay — covers everything.
                if (state.isGenerating) {
                    AiProcessingOverlay(
                        label = stringResource(R.string.tryon_generating),
                        modifier = Modifier.fillMaxSize(),
                    )
                }

                // Error dialog.
                state.error?.let { msg ->
                    AlertDialog(
                        onDismissRequest = tryOnViewModel::clearError,
                        title = { Text(stringResource(R.string.tryon_error)) },
                        text  = { Text(msg) },
                        confirmButton = {
                            TextButton(onClick = tryOnViewModel::clearError) {
                                Text(stringResource(R.string.action_ok))
                            }
                        },
                    )
                }
            }
        }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class, ExperimentalFoundationApi::class, ExperimentalLayoutApi::class)
@Composable
private fun TryOnComposerContent(
    state: TryOnUiState,
    wardrobeImages: List<DriveImage>,
    onRemoveItem: (String) -> Unit,
    onAddItems: (Set<String>) -> Unit,
    onGenerate: () -> Unit,
) {
    var showPicker by remember { mutableStateOf(false) }
    val context = LocalContext.current

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        Surface(
            color = MaterialTheme.colorScheme.secondaryContainer,
            shape = RoundedCornerShape(12.dp),
        ) {
            Text(
                stringResource(R.string.tryon_composer_explain),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSecondaryContainer,
                modifier = Modifier.padding(12.dp),
            )
        }

        val chosenImages = state.itemIds.mapNotNull { id -> wardrobeImages.firstOrNull { it.driveId == id } }
        Text(
            stringResource(R.string.tryon_composer_items_title, chosenImages.size),
            style = MaterialTheme.typography.titleSmall,
            fontWeight = FontWeight.SemiBold,
        )

        FlowRow(
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            chosenImages.forEach { img ->
                Box(
                    modifier = Modifier
                        .size(96.dp)
                        .clip(RoundedCornerShape(10.dp))
                        .border(1.dp, MaterialTheme.colorScheme.outlineVariant, RoundedCornerShape(10.dp))
                        .clickable { onRemoveItem(img.driveId) },
                ) {
                    AsyncImage(
                        model = ImageRequest.Builder(context).data(File(img.localPath)).build(),
                        contentDescription = img.name,
                        contentScale = ContentScale.Crop,
                        modifier = Modifier.fillMaxSize(),
                    )
                    Box(
                        modifier = Modifier
                            .align(Alignment.TopEnd)
                            .padding(4.dp)
                            .size(22.dp)
                            .clip(RoundedCornerShape(50))
                            .background(Color.Black.copy(alpha = 0.55f)),
                        contentAlignment = Alignment.Center,
                    ) {
                        Icon(Icons.Default.Close, null, tint = Color.White, modifier = Modifier.size(14.dp))
                    }
                }
            }
            // Add-tile
            Box(
                modifier = Modifier
                    .size(96.dp)
                    .clip(RoundedCornerShape(10.dp))
                    .border(1.dp, MaterialTheme.colorScheme.outline, RoundedCornerShape(10.dp))
                    .clickable { showPicker = true },
                contentAlignment = Alignment.Center,
            ) {
                Icon(Icons.Default.Add, contentDescription = stringResource(R.string.tryon_add_item))
            }
        }

        Button(
            onClick = onGenerate,
            enabled = chosenImages.isNotEmpty() && !state.isGenerating,
            modifier = Modifier.fillMaxWidth(),
        ) {
            Icon(Icons.Default.AutoAwesome, null, modifier = Modifier.size(18.dp))
            Spacer(Modifier.width(8.dp))
            Text(stringResource(R.string.tryon_generate))
        }
    }

    if (showPicker) {
        ItemPickerSheet(
            wardrobeImages = wardrobeImages,
            alreadyChosen  = state.itemIds,
            onDismiss      = { showPicker = false },
            onConfirm      = { ids ->
                onAddItems(ids)
                showPicker = false
            },
        )
    }
}

@OptIn(ExperimentalMaterial3Api::class, ExperimentalFoundationApi::class, ExperimentalLayoutApi::class)
@Composable
private fun ItemPickerSheet(
    wardrobeImages: List<DriveImage>,
    alreadyChosen: Set<String>,
    onDismiss: () -> Unit,
    onConfirm: (Set<String>) -> Unit,
) {
    val context = LocalContext.current
    var picked by remember { mutableStateOf(emptySet<String>()) }
    val available = wardrobeImages.filter { it.driveId !in alreadyChosen }

    ModalBottomSheet(onDismissRequest = onDismiss) {
        Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth().padding(bottom = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    stringResource(R.string.tryon_add_items_title),
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold,
                    modifier = Modifier.weight(1f),
                )
                TextButton(
                    onClick = { onConfirm(picked) },
                    enabled = picked.isNotEmpty(),
                ) { Text(stringResource(R.string.action_add)) }
            }
            HorizontalDivider()
            LazyVerticalGrid(
                columns = GridCells.Adaptive(96.dp),
                verticalArrangement = Arrangement.spacedBy(6.dp),
                horizontalArrangement = Arrangement.spacedBy(6.dp),
                modifier = Modifier.fillMaxWidth().height(400.dp).padding(vertical = 8.dp),
            ) {
                items(available, key = { it.driveId }) { img ->
                    val selected = img.driveId in picked
                    Box(
                        modifier = Modifier
                            .aspectRatio(1f)
                            .clip(RoundedCornerShape(8.dp))
                            .border(
                                width = if (selected) 3.dp else 1.dp,
                                color = if (selected) MaterialTheme.colorScheme.primary
                                        else MaterialTheme.colorScheme.outlineVariant,
                                shape = RoundedCornerShape(8.dp),
                            )
                            .clickable {
                                picked = if (selected) picked - img.driveId else picked + img.driveId
                            },
                    ) {
                        AsyncImage(
                            model = ImageRequest.Builder(context).data(File(img.localPath)).build(),
                            contentDescription = img.name,
                            contentScale = ContentScale.Crop,
                            modifier = Modifier.fillMaxSize(),
                        )
                        if (selected) {
                            Box(
                                modifier = Modifier
                                    .align(Alignment.TopEnd)
                                    .padding(4.dp)
                                    .size(20.dp)
                                    .clip(RoundedCornerShape(50))
                                    .background(MaterialTheme.colorScheme.primary),
                                contentAlignment = Alignment.Center,
                            ) {
                                Icon(Icons.Default.Check, null, tint = Color.White, modifier = Modifier.size(14.dp))
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun TryOnResultContent(
    state: TryOnUiState,
    onSave: () -> Unit,
    onChangeItems: () -> Unit,
) {
    Column(Modifier.fillMaxSize()) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .weight(1f)
                .background(Color.Black),
        ) {
            ZoomableImage(file = File(state.resultPath!!))
        }
        HorizontalDivider()
        Row(
            modifier = Modifier.fillMaxWidth().padding(12.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Button(
                onClick = onSave,
                enabled = !state.isSaving && !state.isResultSaved,
                modifier = Modifier.weight(1f),
            ) {
                if (state.isSaving) {
                    CircularProgressIndicator(Modifier.size(16.dp), strokeWidth = 2.dp, color = Color.White)
                    Spacer(Modifier.width(8.dp))
                    Text(stringResource(R.string.tryon_saving))
                } else if (state.isResultSaved) {
                    Icon(Icons.Default.Check, null, modifier = Modifier.size(18.dp))
                    Spacer(Modifier.width(8.dp))
                    Text(stringResource(R.string.tryon_saved_to_drive))
                } else {
                    Icon(Icons.Default.Save, null, modifier = Modifier.size(18.dp))
                    Spacer(Modifier.width(8.dp))
                    Text(stringResource(R.string.tryon_save_to_drive))
                }
            }
            OutlinedButton(onClick = onChangeItems, modifier = Modifier.weight(1f)) {
                Icon(Icons.Default.Edit, null, modifier = Modifier.size(18.dp))
                Spacer(Modifier.width(6.dp))
                Text(stringResource(R.string.tryon_change_items))
            }
        }
    }
}

@OptIn(ExperimentalFoundationApi::class, ExperimentalLayoutApi::class)
@Composable
internal fun TryOnHistoryGrid(
    history: List<TryOn>,
    onOpen: (TryOn) -> Unit,
) {
    val context = LocalContext.current
    if (history.isEmpty()) {
        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            Text(
                stringResource(R.string.tryon_history_empty),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        return
    }
    LazyVerticalGrid(
        columns = GridCells.Adaptive(120.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        modifier = Modifier.fillMaxSize().padding(12.dp),
    ) {
        items(history, key = { it.imageDriveId.ifEmpty { it.id } }) { t ->
            Box(
                modifier = Modifier
                    .aspectRatio(0.75f)
                    .clip(RoundedCornerShape(10.dp))
                    .border(1.dp, MaterialTheme.colorScheme.outlineVariant, RoundedCornerShape(10.dp))
                    .clickable { onOpen(t) },
            ) {
                if (t.localPath.isNotEmpty()) {
                    AsyncImage(
                        model = remember(t.imageDriveId, t.localPath) {
                            ImageRequest.Builder(context)
                                .data(File(t.localPath))
                                .memoryCacheKey("tryon_${t.imageDriveId}")
                                .build()
                        },
                        contentDescription = null,
                        contentScale = ContentScale.Crop,
                        modifier = Modifier.fillMaxSize(),
                    )
                } else {
                    Box(Modifier.fillMaxSize().background(Color.LightGray))
                }
                Text(
                    DateFormat.getDateInstance(DateFormat.SHORT).format(Date(t.createdAt)),
                    color = Color.White,
                    style = MaterialTheme.typography.labelSmall,
                    modifier = Modifier
                        .align(Alignment.BottomStart)
                        .background(Color.Black.copy(alpha = 0.55f))
                        .padding(horizontal = 6.dp, vertical = 2.dp),
                )
            }
        }
    }
}

@OptIn(ExperimentalFoundationApi::class, ExperimentalLayoutApi::class)
@Composable
private fun TryOnDetailContent(
    tryOn: TryOn,
    wardrobeImages: List<DriveImage>,
    onDelete: () -> Unit,
    onItemTap: (DriveImage) -> Unit,
) {
    val context = LocalContext.current
    var confirmDelete by remember { mutableStateOf(false) }

    Column(Modifier.fillMaxSize()) {
        Box(
            modifier = Modifier.fillMaxWidth().weight(1f).background(Color.Black),
        ) {
            if (tryOn.localPath.isNotEmpty()) {
                ZoomableImage(file = File(tryOn.localPath))
            } else {
                Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    CircularProgressIndicator(color = Color.White)
                }
            }
        }
        HorizontalDivider()
        // Items list scrolls when long; capped at its weight share so the image and the
        // pinned Delete button below always remain on screen.
        Column(
            Modifier
                .fillMaxWidth()
                .weight(1f, fill = false)
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 12.dp, vertical = 12.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Text(
                stringResource(R.string.tryon_history_items_title),
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.SemiBold,
            )
            // Resolve items by filename so linking survives across folder copies.
            val items = tryOn.itemNames.mapNotNull { n -> wardrobeImages.firstOrNull { it.name == n } }
            if (items.isEmpty()) {
                Text(
                    stringResource(R.string.tryon_history_items_missing),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            } else {
                FlowRow(
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                    verticalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    items.forEach { img ->
                        Box(
                            modifier = Modifier
                                .size(72.dp)
                                .clip(RoundedCornerShape(8.dp))
                                .border(1.dp, MaterialTheme.colorScheme.outlineVariant, RoundedCornerShape(8.dp))
                                .clickable {
                                    Analytics.action("TryOn/Detail", "view_item")
                                    onItemTap(img)
                                },
                        ) {
                            AsyncImage(
                                model = ImageRequest.Builder(context).data(File(img.localPath)).build(),
                                contentDescription = img.name,
                                contentScale = ContentScale.Crop,
                                modifier = Modifier.fillMaxSize(),
                            )
                        }
                    }
                }
            }
        }
        FilledTonalButton(
            onClick = {
                Analytics.action("TryOn/Detail", "open_delete_dialog")
                confirmDelete = true
            },
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 12.dp, vertical = 12.dp),
        ) {
            Icon(Icons.Default.Delete, null, modifier = Modifier.size(18.dp))
            Spacer(Modifier.width(6.dp))
            Text(stringResource(R.string.action_delete))
        }
    }

    if (confirmDelete) {
        AlertDialog(
            onDismissRequest = { confirmDelete = false },
            title = { Text(stringResource(R.string.tryon_delete_confirm_title)) },
            text  = { Text(stringResource(R.string.tryon_delete_confirm_text)) },
            confirmButton = {
                TextButton(onClick = {
                    Analytics.action("TryOn/Detail", "confirm_delete")
                    confirmDelete = false
                    onDelete()
                }) { Text(stringResource(R.string.action_delete)) }
            },
            dismissButton = {
                TextButton(onClick = { confirmDelete = false }) {
                    Text(stringResource(R.string.action_cancel))
                }
            },
        )
    }

}

/** Pinch-zoom & pan image, saturating at [1x .. 6x]. */
@Composable
private fun ZoomableImage(file: File) {
    val context = LocalContext.current
    var scale by remember { mutableStateOf(1f) }
    var offset by remember { mutableStateOf(Offset.Zero) }
    AsyncImage(
        model = ImageRequest.Builder(context).data(file).build(),
        contentDescription = null,
        contentScale = ContentScale.Fit,
        modifier = Modifier
            .fillMaxSize()
            .pointerInput(Unit) {
                detectTransformGestures { _, pan, zoom, _ ->
                    scale = (scale * zoom).coerceIn(1f, 6f)
                    offset = if (scale <= 1.01f) Offset.Zero
                             else Offset(offset.x + pan.x, offset.y + pan.y)
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

