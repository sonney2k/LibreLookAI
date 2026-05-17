package com.librelookai.tryon
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
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
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
import androidx.compose.material.icons.filled.Style
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
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
import com.librelookai.data.model.Location
import com.librelookai.data.model.Outfit
import com.librelookai.data.model.TryOn
import com.librelookai.outfit.AddItemSheet
import com.librelookai.settings.ProfileViewModel
import com.librelookai.shopping.ShoppingClosetViewModel
import com.librelookai.util.AiProcessingOverlay
import com.librelookai.util.Analytics
import com.librelookai.util.LocalSystemBarsPadding
import com.librelookai.wardrobe.DriveImage
import com.librelookai.wardrobe.FullScreenViewer
import com.librelookai.wardrobe.WardrobeViewModel
import com.librelookai.wardrobe.tagCategories
import com.librelookai.R
import com.librelookai.shopping.ShoppingHelperScreen
import com.librelookai.shopping.MatchPreviewDialog

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
    /** Outfits available to pick as the basis of a new try-on. Empty disables the "Use an outfit" path. */
    outfits: List<Outfit> = emptyList(),
    /** Locations passed through to [AddItemSheet] so it can show closet badges in the picker. */
    locations: List<Location> = emptyList(),
    /** Open the saved outfit linked from a try-on detail view. Hidden when null. */
    onOpenSourceOutfit: ((Outfit) -> Unit)? = null,
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
            decorFitsSystemWindows  = false,
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
        // We wrap Scaffold with `.padding(barInsets)` (status + nav bar insets captured by
        // MainActivity). Both Scaffold (contentWindowInsets) and TopAppBar (windowInsets)
        // default to consuming WindowInsets.systemBars themselves — applied again inside the
        // dialog window, that double-adds the bottom inset and clips the bottom action row
        // (and double-adds the top, pushing buttons further off-screen). Disable both.
        Scaffold(
            modifier = Modifier.fillMaxSize().padding(barInsets),
            contentWindowInsets = WindowInsets(0),
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
                    windowInsets = WindowInsets(0),
                )
            },
        ) { innerPadding ->
            Box(Modifier.fillMaxSize().padding(innerPadding)) {
                when {
                    viewing != null -> {
                        val sourceOutfit = remember(viewing.sourceOutfitId, outfits) {
                            viewing.sourceOutfitId?.let { id -> outfits.firstOrNull { it.id == id } }
                        }
                        TryOnDetailContent(
                            tryOn = viewing,
                            wardrobeImages = combinedImages,
                            sourceOutfit = sourceOutfit,
                            onOpenSourceOutfit = onOpenSourceOutfit?.let { open ->
                                { sourceOutfit?.let(open) }
                            },
                            onDelete = { tryOnViewModel.deleteTryOn(viewing) },
                            onItemTap = { img -> detailViewerImage = img },
                        )
                    }

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
                            tryOnViewModel.openComposer(state.itemIds, state.sourceOutfitId)
                        },
                    )

                    else -> TryOnComposerContent(
                        state = state,
                        wardrobeImages = combinedImages,
                        outfits = outfits,
                        locations = locations,
                        wardrobeViewModel = wardrobeViewModel,
                        onRemoveItem = tryOnViewModel::removeItem,
                        onAddItems = { ids -> ids.forEach(tryOnViewModel::addItem) },
                        onPickOutfit = tryOnViewModel::selectOutfit,
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

                // The InsufficientCreditsDialog for 402s is installed globally
                // in MainActivity — it listens on CreditsEvents.topUp and routes
                // "Buy" to the Settings tab. We only need to reset isGenerating
                // here, which TryOnViewModel.generate() handles.
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
    outfits: List<Outfit>,
    locations: List<Location>,
    wardrobeViewModel: WardrobeViewModel,
    onRemoveItem: (String) -> Unit,
    onAddItems: (Set<String>) -> Unit,
    onPickOutfit: (Outfit) -> Unit,
    onGenerate: () -> Unit,
) {
    var showItemPicker by remember { mutableStateOf(false) }
    var showOutfitPicker by remember { mutableStateOf(false) }
    val context = LocalContext.current
    val sourceOutfit = remember(state.sourceOutfitId, outfits) {
        state.sourceOutfitId?.let { id -> outfits.firstOrNull { it.id == id } }
    }

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

        // Show which outfit (if any) the composition originated from. The badge is cleared
        // the moment the user toggles a single item via the X-overlay or the picker (the VM
        // resets sourceOutfitId on any addItem/removeItem).
        sourceOutfit?.let { o ->
            Surface(
                color = MaterialTheme.colorScheme.tertiaryContainer,
                shape = RoundedCornerShape(10.dp),
            ) {
                Text(
                    stringResource(R.string.tryon_from_outfit, o.name.ifBlank { "—" }),
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onTertiaryContainer,
                    modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp),
                )
            }
        }

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
                    .clickable { showItemPicker = true },
                contentAlignment = Alignment.Center,
            ) {
                Icon(Icons.Default.Add, contentDescription = stringResource(R.string.tryon_add_item))
            }
        }

        // "Use an outfit" shortcut — picks every item of a saved outfit at once. Hidden when
        // the user has no outfits yet (nothing to show in the picker).
        if (outfits.isNotEmpty()) {
            OutlinedButton(
                onClick = {
                    Analytics.action("TryOn/Composer", "open_outfit_picker")
                    showOutfitPicker = true
                },
                modifier = Modifier.fillMaxWidth(),
            ) {
                Icon(Icons.Default.Style, null, modifier = Modifier.size(18.dp))
                Spacer(Modifier.width(8.dp))
                Text(stringResource(R.string.tryon_use_outfit))
            }
        }

        Button(
            onClick = onGenerate,
            enabled = chosenImages.isNotEmpty() && !state.isGenerating,
            modifier = Modifier.fillMaxWidth(),
        ) {
            com.librelookai.billing.CostBadge(com.librelookai.gemini.GeminiActionId.TRY_ON_OUTFIT)
            Icon(Icons.Default.AutoAwesome, null, modifier = Modifier.size(18.dp))
            Spacer(Modifier.width(8.dp))
            Text(stringResource(R.string.tryon_generate))
        }
    }

    if (showItemPicker) {
        // Reuse the outfit composer's picker so Try-On gains the same affordances:
        // tag filters, text search, find-by-photo, sort, and pinch-to-zoom grid.
        AddItemSheet(
            allItems = wardrobeImages,
            alreadyChosen = state.itemIds,
            locations = locations,
            onTextFilter = wardrobeViewModel::fuzzyFilterByText,
            findSimilarByPhoto = { file, candidates ->
                wardrobeViewModel.findSimilarInCandidates(file, candidates)
                    .associate { it.driveId to it.score }
            },
            onConfirm = { ids ->
                onAddItems(ids)
                showItemPicker = false
            },
            onDismiss = { showItemPicker = false },
        )
    }

    if (showOutfitPicker) {
        OutfitPickerDialog(
            outfits = outfits,
            wardrobeImages = wardrobeImages,
            onPick = { outfit ->
                Analytics.action("TryOn/Composer", "pick_outfit", mapOf("items" to outfit.itemIds.size.toString()))
                onPickOutfit(outfit)
                showOutfitPicker = false
            },
            onDismiss = { showOutfitPicker = false },
        )
    }
}

/**
 * Fullscreen outfit picker — shows every saved outfit as a row with the outfit name and a
 * thumbnail strip of its items. Picking is single-tap: confirms immediately and dismisses.
 * Sits above the Try-On composer Dialog as its own Dialog (same pattern as [AddItemSheet]).
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun OutfitPickerDialog(
    outfits: List<Outfit>,
    wardrobeImages: List<DriveImage>,
    onPick: (Outfit) -> Unit,
    onDismiss: () -> Unit,
) {
    val parentContext = LocalContext.current
    val parentConfiguration = LocalConfiguration.current
    val itemsById = remember(wardrobeImages) { wardrobeImages.associateBy { it.driveId } }
    // Hide outfits whose items aren't all loaded — try-on needs the cutouts on disk.
    val pickable = remember(outfits, itemsById) {
        outfits.filter { o -> o.itemIds.isNotEmpty() && o.itemIds.all { it in itemsById } }
    }

    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(
            usePlatformDefaultWidth = false,
            dismissOnBackPress = true,
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
            val barInsets = LocalSystemBarsPadding.current
            Surface(
                modifier = Modifier.fillMaxSize().padding(barInsets),
                color = MaterialTheme.colorScheme.background,
            ) {
                Column(modifier = Modifier.fillMaxSize()) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(start = 4.dp, end = 16.dp, top = 4.dp, bottom = 4.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        IconButton(onClick = onDismiss) {
                            Icon(Icons.Default.Close, contentDescription = stringResource(R.string.action_cancel))
                        }
                        Text(
                            stringResource(R.string.tryon_outfit_picker_title),
                            style = MaterialTheme.typography.titleMedium,
                            modifier = Modifier.weight(1f),
                        )
                    }
                    HorizontalDivider()
                    if (pickable.isEmpty()) {
                        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                            Text(
                                stringResource(R.string.tryon_outfit_picker_empty),
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    } else {
                        LazyVerticalGrid(
                            columns = GridCells.Fixed(1),
                            verticalArrangement = Arrangement.spacedBy(8.dp),
                            modifier = Modifier
                                .fillMaxSize()
                                .padding(horizontal = 12.dp, vertical = 8.dp),
                        ) {
                            items(pickable, key = { it.id }) { outfit ->
                                OutfitPickerRow(
                                    outfit = outfit,
                                    itemsById = itemsById,
                                    onClick = { onPick(outfit) },
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun OutfitPickerRow(
    outfit: Outfit,
    itemsById: Map<String, DriveImage>,
    onClick: () -> Unit,
) {
    val context = LocalContext.current
    val items = remember(outfit.itemIds, itemsById) {
        outfit.itemIds.mapNotNull { itemsById[it] }
    }
    Surface(
        shape = RoundedCornerShape(12.dp),
        color = MaterialTheme.colorScheme.surfaceVariant,
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick),
    ) {
        Column(
            modifier = Modifier.padding(12.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Text(
                outfit.name.ifBlank { stringResource(R.string.outfits_unnamed) },
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.SemiBold,
            )
            Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                items.take(6).forEach { img ->
                    Box(
                        modifier = Modifier
                            .size(64.dp)
                            .clip(RoundedCornerShape(8.dp)),
                    ) {
                        AsyncImage(
                            model = ImageRequest.Builder(context).data(File(img.localPath)).build(),
                            contentDescription = img.name,
                            contentScale = ContentScale.Crop,
                            modifier = Modifier.fillMaxSize(),
                        )
                    }
                }
                if (items.size > 6) {
                    Box(
                        modifier = Modifier
                            .size(64.dp)
                            .clip(RoundedCornerShape(8.dp))
                            .background(MaterialTheme.colorScheme.outlineVariant),
                        contentAlignment = Alignment.Center,
                    ) {
                        Text(
                            "+${items.size - 6}",
                            style = MaterialTheme.typography.labelLarge,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
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
    sourceOutfit: Outfit?,
    onOpenSourceOutfit: (() -> Unit)?,
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
        // pinned action buttons below always remain on screen.
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
        // Bottom action row: "View outfit" (only when the source outfit still exists) +
        // Delete. Kept outside the weighted item-scroll above so they stay pinned.
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 12.dp, vertical = 12.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            if (sourceOutfit != null && onOpenSourceOutfit != null) {
                OutlinedButton(
                    onClick = {
                        Analytics.action("TryOn/Detail", "open_source_outfit")
                        onOpenSourceOutfit()
                    },
                    modifier = Modifier.weight(1f),
                ) {
                    Icon(Icons.Default.Style, null, modifier = Modifier.size(18.dp))
                    Spacer(Modifier.width(6.dp))
                    Text(stringResource(R.string.tryon_view_source_outfit))
                }
            }
            FilledTonalButton(
                onClick = {
                    Analytics.action("TryOn/Detail", "open_delete_dialog")
                    confirmDelete = true
                },
                modifier = Modifier.weight(1f),
            ) {
                Icon(Icons.Default.Delete, null, modifier = Modifier.size(18.dp))
                Spacer(Modifier.width(6.dp))
                Text(stringResource(R.string.action_delete))
            }
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

