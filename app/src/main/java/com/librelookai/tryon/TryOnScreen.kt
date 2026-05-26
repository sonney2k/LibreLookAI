package com.librelookai.tryon
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Sort
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.AutoAwesome
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.Person
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.blur
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
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
import com.librelookai.outfit.OutfitItemBucket
import com.librelookai.outfit.bucketFor
import com.librelookai.settings.ProfileViewModel
import com.librelookai.shopping.ShoppingClosetViewModel
import com.librelookai.util.Analytics
import com.librelookai.util.rememberDialogBottomInset
import com.librelookai.wardrobe.DriveImage
import com.librelookai.wardrobe.FullScreenViewer
import com.librelookai.wardrobe.WardrobeViewModel
import com.librelookai.wardrobe.tagCategories
import com.librelookai.wardrobe.tagStringsForCategory
import com.librelookai.wardrobe.QuickCategoryRow
import com.librelookai.wardrobe.WardrobeFilterSheet
import com.librelookai.R
import com.librelookai.shopping.ShoppingHelperScreen
import com.librelookai.shopping.MatchPreviewDialog

/**
 * Full-screen unified Try-on experience:
 *  - Compose: edit the set of items, explain "all of these will be worn", then generate.
 *  - Preview: zoomable generated image with Save / Regenerate / Change items actions.
 *  - History: grid of saved try-ons; tap one to view image + items; long-press delete.
 */
@OptIn(ExperimentalMaterial3Api::class, ExperimentalFoundationApi::class)
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
    /** Open the Quick Try-On sheet (history FAB + empty-state CTA). */
    onStartTryOn: () -> Unit = {},
    /** Close the dialog and route the user to Settings → Profile (no-reference-photos state). */
    onOpenProfileSettings: () -> Unit = {},
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
    // Captured OUTSIDE the Dialog — see rememberDialogBottomInset. The raw LocalSystemBarsPadding
    // bottom can be 0 inside the dialog window, which clipped the "Generate" button under the nav bar.
    val effectiveBottom = rememberDialogBottomInset()
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
        // Fill the whole dialog window with the theme background so the screen reads as truly
        // full-screen — the underlying app never shows through the status/nav-bar strips. Only
        // the Scaffold *content* is inset to the safe area via padding(effectiveBottom); the Scaffold
        // container itself is transparent so the full-bleed background behind it shows edge-to-edge.
        //
        // Scaffold's contentWindowInsets defaults to consuming WindowInsets.systemBars itself —
        // applied again inside the dialog window that double-adds insets and clips the bottom
        // action row. Disable it (set WindowInsets(0)).
        Box(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background)) {
        Scaffold(
            // The dialog window already insets the top status bar; only the bottom nav bar needs
            // manual padding. Padding the top here would double-pad it.
            modifier = Modifier.fillMaxSize().padding(bottom = effectiveBottom),
            containerColor = Color.Transparent,
            contentWindowInsets = WindowInsets(0),
            topBar = {
                // Mirror the sibling picker dialogs (AddItemSheet / OutfitPickerDialog /
                // TripOutfitPickerDialog) exactly — 4.dp top/bottom, no divider — so the slim top
                // header is consistent across every try-on surface regardless of entry source.
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(start = 4.dp, end = 16.dp, top = 4.dp, bottom = 4.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    IconButton(onClick = {
                        Analytics.action("TryOn", "close")
                        when {
                            viewing != null && state.historyDetailIsRoot -> tryOnViewModel.close()
                            viewing != null     -> tryOnViewModel.dismissViewingTryOn()
                            state.isHistoryOpen && state.historyIsRoot -> tryOnViewModel.close()
                            state.isHistoryOpen -> tryOnViewModel.closeHistory()
                            else                -> tryOnViewModel.close()
                        }
                    }) { Icon(Icons.Default.Close, contentDescription = stringResource(R.string.action_dismiss)) }
                    Text(
                        when {
                            viewing != null         -> stringResource(R.string.tryon_history_detail_title)
                            state.isHistoryOpen     -> stringResource(R.string.tryon_history_title)
                            else                    -> stringResource(R.string.tryon_title)
                        },
                        style = MaterialTheme.typography.titleMedium,
                        maxLines = 1,
                        overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f),
                    )
                    // Keep the closet selector + Insights + Settings reachable while building a
                    // try-on. Navigating closes the whole composer first ("dismiss then navigate").
                    com.librelookai.ViewerHeaderActions(onBeforeNavigate = tryOnViewModel::close)
                }
            },
        ) { innerPadding ->
            Box(Modifier.fillMaxSize().padding(innerPadding)) {
                when {
                    viewing != null -> {
                        // Swipe left/right between past try-ons. The pager spans the full history;
                        // settling on a page syncs viewingTryOn so delete/regenerate act on it.
                        val history = state.history
                        val startIndex = history
                            .indexOfFirst { it.imageDriveId == viewing.imageDriveId }
                            .coerceAtLeast(0)
                        if (history.isEmpty()) {
                            TryOnDetailPage(
                                tryOn = viewing,
                                combinedImages = combinedImages,
                                outfits = outfits,
                                onOpenSourceOutfit = onOpenSourceOutfit,
                                onItemTap = { img -> detailViewerImage = img },
                                tryOnViewModel = tryOnViewModel,
                            )
                        } else {
                            val pagerState = rememberPagerState(
                                initialPage = startIndex.coerceIn(0, history.lastIndex),
                                pageCount = { history.size },
                            )
                            LaunchedEffect(pagerState.currentPage, history) {
                                history.getOrNull(pagerState.currentPage)?.let { tryOnViewModel.viewTryOn(it) }
                            }
                            HorizontalPager(state = pagerState, modifier = Modifier.fillMaxSize()) { page ->
                                TryOnDetailPage(
                                    tryOn = history[page],
                                    combinedImages = combinedImages,
                                    outfits = outfits,
                                    onOpenSourceOutfit = onOpenSourceOutfit,
                                    onItemTap = { img -> detailViewerImage = img },
                                    tryOnViewModel = tryOnViewModel,
                                )
                            }
                        }
                    }

                    state.isHistoryOpen -> TryOnHistoryFeed(
                        history = state.history,
                        wardrobeImages = combinedImages,
                        onOpen = { tryOnViewModel.viewTryOn(it) },
                        onStartTryOn = onStartTryOn,
                        showFab = true,
                        onEditHero = { t ->
                            val ids = t.itemNames
                                .mapNotNull { n -> combinedImages.firstOrNull { it.name == n } }
                                .map { it.driveId }.toSet()
                            tryOnViewModel.openComposer(
                                ids, t.sourceOutfitId,
                                tryOnSourceKindOf(t.sourceKind),
                                t.sourceContext.takeIf { it.isNotBlank() },
                            )
                        },
                    )

                    state.resultPath != null -> TryOnResultContent(
                        state = state,
                        onSave = {
                            Analytics.action("TryOn/Result", "save")
                            tryOnViewModel.saveCurrent(combinedImages)
                        },
                        onTryAgain = {
                            Analytics.action("TryOn/Result", "try_again")
                            tryOnViewModel.generate(
                                personFiles     = profileViewModel.tryOnFiles(),
                                wardrobeImages  = combinedImages,
                                preferences     = profileState.preferences.preferences,
                            )
                        },
                        onChangeItems = {
                            Analytics.action("TryOn/Result", "change_items")
                            tryOnViewModel.openComposer(state.itemIds, state.sourceOutfitId, state.sourceKind, state.sourceContext)
                        },
                        wardrobeImages = combinedImages,
                    )

                    profileState.tryOnLocalPaths.isEmpty() -> TryOnNoPhotos(
                        onOpenSettings = onOpenProfileSettings,
                    )

                    else -> TryOnComposerContent(
                        state = state,
                        wardrobeImages = combinedImages,
                        outfits = outfits,
                        locations = locations,
                        referencePhotoPaths = profileState.tryOnLocalPaths.values.toList(),
                        wardrobeViewModel = wardrobeViewModel,
                        onRemoveItem = tryOnViewModel::removeItem,
                        onAddItems = { ids -> ids.forEach(tryOnViewModel::addItem) },
                        onPickOutfit = tryOnViewModel::selectOutfit,
                        onConsumeAutoPick = tryOnViewModel::consumeAutoPick,
                        onSwapSource = {
                            Analytics.action("TryOn/Composer", "source_swap")
                            onStartTryOn()
                        },
                        onEditReferencePhotos = onOpenProfileSettings,
                        onCancelEmpty = {
                            Analytics.action("TryOn/Composer", "cancel_empty")
                            tryOnViewModel.close()
                        },
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
                    TryOnGeneratingOverlay(
                        itemCount = state.itemIds.size,
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
}

@OptIn(ExperimentalMaterial3Api::class, ExperimentalFoundationApi::class)
@Composable
private fun TryOnComposerContent(
    state: TryOnUiState,
    wardrobeImages: List<DriveImage>,
    outfits: List<Outfit>,
    locations: List<Location>,
    referencePhotoPaths: List<String>,
    wardrobeViewModel: WardrobeViewModel,
    onRemoveItem: (String) -> Unit,
    onAddItems: (Set<String>) -> Unit,
    onPickOutfit: (Outfit) -> Unit,
    onConsumeAutoPick: () -> Unit,
    onSwapSource: () -> Unit,
    onEditReferencePhotos: () -> Unit,
    onCancelEmpty: () -> Unit,
    onGenerate: () -> Unit,
) {
    var showItemPicker by remember { mutableStateOf(false) }
    var showOutfitPicker by remember { mutableStateOf(false) }
    // True while the auto-opened (Quick-sheet) picker is showing on an as-yet-empty composer.
    // If the user cancels that picker without choosing anything, we back out instead of
    // dropping them on an empty composer.
    var awaitingAutoPick by remember { mutableStateOf(false) }
    val sourceOutfit = remember(state.sourceOutfitId, outfits) {
        state.sourceOutfitId?.let { id -> outfits.firstOrNull { it.id == id } }
    }

    // When the Quick sheet routed here with autoPick, open the matching picker once.
    LaunchedEffect(state.autoPick, state.sourceKind) {
        if (state.autoPick) {
            when (state.sourceKind) {
                TryOnSourceKind.OUTFIT -> { showOutfitPicker = true; awaitingAutoPick = true }
                TryOnSourceKind.WARDROBE, TryOnSourceKind.SHOPPING -> { showItemPicker = true; awaitingAutoPick = true }
                else -> {}
            }
            onConsumeAutoPick()
        }
    }

    val chosenImages = state.itemIds.mapNotNull { id -> wardrobeImages.firstOrNull { it.driveId == id } }

    Column(modifier = Modifier.fillMaxSize()) {
        Column(
            modifier = Modifier
                .weight(1f)
                .verticalScroll(rememberScrollState())
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            // Source banner — present whenever the composer was opened from a source surface.
            if (state.sourceKind != TryOnSourceKind.NONE) {
                val bannerTitle = when (state.sourceKind) {
                    TryOnSourceKind.OUTFIT -> sourceOutfit?.name?.ifBlank { null }
                        ?: state.sourceContext
                        ?: stringResource(R.string.tryon_source_context_items, chosenImages.size)
                    else -> state.sourceContext?.takeIf { it.isNotBlank() }
                        ?: stringResource(R.string.tryon_source_context_items, chosenImages.size)
                }
                SourceBanner(
                    kind = state.sourceKind,
                    title = bannerTitle,
                    onSwap = onSwapSource,
                )
            }

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

            Text(
                stringResource(R.string.tryon_composer_items_title, chosenImages.size),
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.SemiBold,
            )

            // Stacked, overlapping cutouts with drop-shadows — mirrors the outfit viewer layout
            // (see OutfitPageBody) instead of a flat grid of thumbnails.
            if (chosenImages.isNotEmpty()) {
                TryOnItemStack(
                    images = chosenImages,
                    modifier = Modifier.fillMaxWidth(),
                    onRemove = onRemoveItem,
                )
            }

            OutlinedButton(
                onClick = { showItemPicker = true },
                modifier = Modifier.fillMaxWidth(),
            ) {
                Icon(Icons.Default.Add, null, modifier = Modifier.size(18.dp))
                Spacer(Modifier.width(8.dp))
                Text(stringResource(R.string.tryon_add_item))
            }

            if (referencePhotoPaths.isNotEmpty()) {
                ReferencePhotosPreview(
                    photoPaths = referencePhotoPaths,
                    onEdit = onEditReferencePhotos,
                )
            }
        }

        // Sticky generate button — pinned at the bottom so it always fits on screen.
        Box(modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp)) {
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
            allowMultiSelect = true,
            onConfirm = { ids ->
                onAddItems(ids)
                showItemPicker = false
                // Confirming with nothing chosen from a Quick-sheet auto-pick is the same as
                // cancelling — don't strand the user on an empty composer.
                if (awaitingAutoPick && ids.isEmpty() && state.itemIds.isEmpty()) onCancelEmpty()
                awaitingAutoPick = false
            },
            onDismiss = {
                showItemPicker = false
                if (awaitingAutoPick) {
                    awaitingAutoPick = false
                    if (state.itemIds.isEmpty()) onCancelEmpty()
                }
            },
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
                awaitingAutoPick = false
            },
            onDismiss = {
                showOutfitPicker = false
                // Backed out of the auto-opened outfit picker without choosing — don't advance
                // to an empty composer.
                if (awaitingAutoPick) {
                    awaitingAutoPick = false
                    if (state.itemIds.isEmpty() && state.sourceOutfitId == null) onCancelEmpty()
                }
            },
        )
    }
}

/**
 * Stacked, overlapping cutouts of the chosen try-on items — the same anatomical top→bottom
 * stack with drop-shadows used by the outfit viewer ([com.librelookai.outfit.OutfitPageBody]).
 * Each tile carries a remove badge.
 */
@Composable
private fun TryOnItemStack(
    images: List<DriveImage>,
    modifier: Modifier = Modifier,
    onItemClick: ((DriveImage) -> Unit)? = null,
    onRemove: ((String) -> Unit)? = null,
) {
    val rows: List<List<DriveImage>> = remember(images) {
        val grouped = images.groupBy { bucketFor(it) }
        OutfitItemBucket.entries.mapNotNull { b -> grouped[b]?.takeIf { it.isNotEmpty() } }
    }
    BoxWithConstraints(modifier = modifier.height(340.dp)) {
        val rowOverlap = 0.28f
        val itemOverlap = 0.18f
        val n = rows.size
        val m = (rows.maxOfOrNull { it.size } ?: 1).coerceAtLeast(1)
        val availW = maxWidth
        val availH = maxHeight
        val byH = availH / (1f + (1f - rowOverlap) * (n - 1))
        val byW = availW / (1f + (1f - itemOverlap) * (m - 1))
        val tileSize = minOf(byH, byW).coerceIn(96.dp, 320.dp)
        val rowStride = tileSize * (1f - rowOverlap)
        val itemStride = tileSize * (1f - itemOverlap)
        val totalContentH = tileSize + rowStride * (n - 1)
        val topOffset = ((availH - totalContentH) / 2).coerceAtLeast(0.dp)

        rows.forEachIndexed { rowIdx, rowItems ->
            val rowWidth = tileSize + itemStride * (rowItems.size - 1)
            val rowLeft = ((availW - rowWidth) / 2).coerceAtLeast(0.dp)
            val rowTop = topOffset + rowStride * rowIdx
            rowItems.forEachIndexed { itemIdx, image ->
                val left = rowLeft + itemStride * itemIdx
                TryOnStackTile(
                    image = image,
                    size = tileSize,
                    onClick = onItemClick?.let { { it(image) } },
                    onRemove = onRemove?.let { { it(image.driveId) } },
                    modifier = Modifier.offset(x = left, y = rowTop),
                )
            }
        }
    }
}

@Composable
private fun TryOnStackTile(
    image: DriveImage,
    size: Dp,
    onClick: (() -> Unit)?,
    onRemove: (() -> Unit)?,
    modifier: Modifier = Modifier,
) {
    val ctx = LocalContext.current
    val model = remember(image.driveId, image.version) {
        ImageRequest.Builder(ctx)
            .data(image.localPath)
            .memoryCacheKey("${image.driveId}_${image.version}")
            .build()
    }
    Box(
        modifier = modifier
            .size(size)
            .then(if (onClick != null) Modifier.clickable(onClick = onClick) else Modifier),
    ) {
        // Drop-shadow silhouette — follows the cutout's alpha channel.
        AsyncImage(
            model = model,
            contentDescription = null,
            modifier = Modifier
                .fillMaxSize()
                .offset(x = 3.dp, y = 6.dp)
                .blur(radius = 8.dp)
                .graphicsLayer { alpha = 0.45f },
            contentScale = ContentScale.Fit,
            colorFilter = ColorFilter.tint(Color.Black, BlendMode.SrcIn),
        )
        AsyncImage(
            model = model,
            contentDescription = image.name,
            modifier = Modifier.fillMaxSize(),
            contentScale = ContentScale.Fit,
        )
        if (onRemove != null) {
            Box(
                modifier = Modifier
                    .align(Alignment.TopEnd)
                    .padding(4.dp)
                    .size(22.dp)
                    .clip(RoundedCornerShape(50))
                    .background(Color.Black.copy(alpha = 0.55f))
                    .clickable(onClick = onRemove),
                contentAlignment = Alignment.Center,
            ) {
                Icon(Icons.Default.Close, contentDescription = stringResource(R.string.action_remove), tint = Color.White, modifier = Modifier.size(14.dp))
            }
        }
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
    // Captured OUTSIDE the Dialog so the nav-bar inset is real (see rememberDialogBottomInset).
    val effectiveBottom = rememberDialogBottomInset()
    val itemsById = remember(wardrobeImages) { wardrobeImages.associateBy { it.driveId } }
    // Hide outfits whose items aren't all loaded — try-on needs the cutouts on disk.
    val pickable = remember(outfits, itemsById) {
        outfits.filter { o -> o.itemIds.isNotEmpty() && o.itemIds.all { it in itemsById } }
    }

    // Filter + sort, mirroring the Outfits screen sub-panel.
    var selectedTags by remember { mutableStateOf(emptyMap<String, Set<String>>()) }
    var textQuery by remember { mutableStateOf("") }
    var filterSheetOpen by remember { mutableStateOf(false) }
    var sortBy by remember { mutableStateOf(OutfitPickerSortOption.DATE_DESC) }
    val appliedFilterCount = selectedTags.values.sumOf { it.size } + (if (textQuery.isNotBlank()) 1 else 0)

    val tagCategories = remember(pickable, itemsById) {
        pickable.flatMap { o -> o.itemIds.mapNotNull { itemsById[it] } }.tagCategories()
    }

    val displayed = remember(pickable, selectedTags, textQuery, sortBy, itemsById) {
        val activeFilters = selectedTags.filter { it.value.isNotEmpty() }
        val q = textQuery.trim().lowercase()
        val filtered = pickable.filter { o ->
            val images = o.itemIds.mapNotNull { itemsById[it] }
            val tagsOk = activeFilters.isEmpty() || activeFilters.all { (label, tags) ->
                images.any { img -> tags.any { it in img.tagStringsForCategory(label) } }
            }
            if (!tagsOk) return@filter false
            if (q.isBlank()) return@filter true
            o.name.lowercase().contains(q) ||
                o.description.lowercase().contains(q) ||
                o.tags.any { it.lowercase().contains(q) } ||
                images.any { it.name.lowercase().contains(q) }
        }
        when (sortBy) {
            OutfitPickerSortOption.DATE_DESC  -> filtered
            OutfitPickerSortOption.DATE_ASC   -> filtered.reversed()
            OutfitPickerSortOption.NAME_AZ    -> filtered.sortedBy { it.name.lowercase() }
            OutfitPickerSortOption.NAME_ZA    -> filtered.sortedByDescending { it.name.lowercase() }
            OutfitPickerSortOption.ITEM_COUNT -> filtered.sortedByDescending { it.itemIds.size }
        }
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
            Surface(
                modifier = Modifier.fillMaxSize(),
                color = MaterialTheme.colorScheme.background,
            ) {
                Column(modifier = Modifier.fillMaxSize().padding(bottom = effectiveBottom)) {
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
                        // Filter chip row + sort, mirroring the Outfits screen.
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .background(com.librelookai.ui.theme.LocalWardrobePalette.current.surface),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            QuickCategoryRow(
                                totalCount = pickable.size,
                                filteredCount = displayed.size,
                                appliedFilterCount = appliedFilterCount,
                                filtersEnabled = true,
                                onClearFilters = { selectedTags = emptyMap(); textQuery = "" },
                                onOpenFilters = { filterSheetOpen = true },
                                modifier = Modifier.weight(1f),
                            )
                            OutfitPickerSortButton(
                                sortBy = sortBy,
                                onSortChanged = { sortBy = it },
                                modifier = Modifier.padding(end = 4.dp),
                            )
                        }
                        if (displayed.isEmpty()) {
                            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                                Text(
                                    stringResource(R.string.outfits_no_match),
                                    style = MaterialTheme.typography.bodyLarge,
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
                                items(displayed, key = { it.id }) { outfit ->
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

    if (filterSheetOpen) {
        WardrobeFilterSheet(
            tagCategories = tagCategories,
            selectedTags = selectedTags,
            appliedCount = appliedFilterCount,
            onTagsChanged = { selectedTags = it },
            textQuery = textQuery,
            onTextQueryChanged = { textQuery = it },
            onDismiss = { filterSheetOpen = false },
        )
    }
}

private enum class OutfitPickerSortOption { DATE_DESC, DATE_ASC, NAME_AZ, NAME_ZA, ITEM_COUNT }

@Composable
private fun OutfitPickerSortOption.displayLabel(): String = when (this) {
    OutfitPickerSortOption.DATE_DESC  -> stringResource(R.string.outfits_sort_newest)
    OutfitPickerSortOption.DATE_ASC   -> stringResource(R.string.outfits_sort_oldest)
    OutfitPickerSortOption.NAME_AZ    -> stringResource(R.string.outfits_sort_name_az)
    OutfitPickerSortOption.NAME_ZA    -> stringResource(R.string.outfits_sort_name_za)
    OutfitPickerSortOption.ITEM_COUNT -> stringResource(R.string.outfits_sort_most_items)
}

@Composable
private fun OutfitPickerSortButton(
    sortBy: OutfitPickerSortOption,
    onSortChanged: (OutfitPickerSortOption) -> Unit,
    modifier: Modifier = Modifier,
) {
    var expanded by remember { mutableStateOf(false) }
    // DropdownMenu renders in its own popup window; re-provide LocalContext/LocalConfiguration
    // so stringResource() honors the in-app language toggle.
    val parentContext = LocalContext.current
    val parentConfiguration = LocalConfiguration.current
    Box(modifier = modifier) {
        IconButton(onClick = { expanded = true }) {
            Icon(Icons.AutoMirrored.Filled.Sort, contentDescription = stringResource(R.string.action_sort))
        }
        DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
            CompositionLocalProvider(
                LocalContext provides parentContext,
                LocalConfiguration provides parentConfiguration,
            ) {
                OutfitPickerSortOption.entries.forEach { option ->
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
    onTryAgain: () -> Unit,
    onChangeItems: () -> Unit,
    wardrobeImages: List<DriveImage>,
) {
    val palette = com.librelookai.ui.theme.LocalWardrobePalette.current
    val context = LocalContext.current
    val items = remember(state.itemIds, wardrobeImages) {
        state.itemIds.mapNotNull { id -> wardrobeImages.firstOrNull { it.driveId == id } }
    }
    Column(Modifier.fillMaxSize()) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .weight(1f)
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 14.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Spacer(Modifier.size(4.dp))
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .aspectRatio(4f / 5f)
                    .clip(RoundedCornerShape(22.dp))
                    .border(1.dp, palette.divider, RoundedCornerShape(22.dp))
                    .background(Color.Black),
            ) {
                ZoomableImage(file = File(state.resultPath!!))
                if (state.sourceKind != TryOnSourceKind.NONE) {
                    SourcePill(
                        kind = state.sourceKind,
                        label = state.sourceContext?.takeIf { it.isNotBlank() }
                            ?: stringResource(sourceMeta(state.sourceKind).labelRes),
                        solid = true,
                        modifier = Modifier.align(Alignment.TopStart).padding(12.dp),
                    )
                }
                Text(
                    stringResource(R.string.tryon_result_zoom_hint),
                    color = palette.textMid,
                    style = MaterialTheme.typography.labelSmall,
                    modifier = Modifier
                        .align(Alignment.BottomEnd)
                        .padding(12.dp)
                        .clip(RoundedCornerShape(10.dp))
                        .background(Color.White.copy(alpha = 0.85f))
                        .padding(horizontal = 9.dp, vertical = 5.dp),
                )
            }
            if (items.isNotEmpty()) {
                Text(
                    stringResource(R.string.tryon_result_items_worn).uppercase(),
                    color = palette.textMid,
                    fontSize = 11.sp,
                    fontWeight = FontWeight.Bold,
                    letterSpacing = 0.4.sp,
                )
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    items.take(5).forEach { img ->
                        Box(
                            modifier = Modifier
                                .weight(1f)
                                .aspectRatio(1f)
                                .clip(RoundedCornerShape(11.dp))
                                .border(1.dp, palette.divider, RoundedCornerShape(11.dp)),
                        ) {
                            AsyncImage(
                                model = ImageRequest.Builder(context).data(File(img.localPath)).build(),
                                contentDescription = img.name,
                                contentScale = ContentScale.Crop,
                                modifier = Modifier.fillMaxSize(),
                            )
                        }
                    }
                    repeat((5 - items.size).coerceAtLeast(0)) { Spacer(Modifier.weight(1f)) }
                }
            }
            Spacer(Modifier.size(4.dp))
        }
        HorizontalDivider()
        Column(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 10.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(
                    onClick = onSave,
                    enabled = !state.isSaving && !state.isResultSaved,
                    shape = RoundedCornerShape(23.dp),
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
                        Icon(Icons.Default.Check, null, modifier = Modifier.size(18.dp))
                        Spacer(Modifier.width(8.dp))
                        Text(stringResource(R.string.tryon_save_to_drive))
                    }
                }
                OutlinedButton(
                    onClick = onTryAgain,
                    enabled = !state.isGenerating,
                    shape = RoundedCornerShape(23.dp),
                    modifier = Modifier.weight(1f),
                ) {
                    Icon(Icons.Default.Refresh, null, modifier = Modifier.size(18.dp))
                    Spacer(Modifier.width(6.dp))
                    Text(stringResource(R.string.tryon_try_again))
                }
            }
            TextButton(onClick = onChangeItems, modifier = Modifier.fillMaxWidth()) {
                Icon(Icons.Default.Edit, null, modifier = Modifier.size(14.dp))
                Spacer(Modifier.width(6.dp))
                Text(stringResource(R.string.tryon_change_items))
            }
        }
    }
}

/** Composer source banner — eyebrow + title + a Swap pill that reopens the Quick sheet. */
@Composable
private fun SourceBanner(
    kind: TryOnSourceKind,
    title: String,
    onSwap: () -> Unit,
) {
    val palette = com.librelookai.ui.theme.LocalWardrobePalette.current
    val meta = sourceMeta(kind)
    Surface(
        shape = RoundedCornerShape(14.dp),
        color = palette.surface,
        border = androidx.compose.foundation.BorderStroke(1.dp, palette.divider),
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Box(
                modifier = Modifier
                    .size(34.dp)
                    .clip(RoundedCornerShape(10.dp))
                    .background(meta.tint.copy(alpha = 0.13f)),
                contentAlignment = Alignment.Center,
            ) {
                Icon(meta.icon, null, tint = meta.tint, modifier = Modifier.size(16.dp))
            }
            Column(Modifier.weight(1f)) {
                Text(
                    stringResource(meta.labelRes).uppercase(),
                    color = palette.textMuted,
                    fontSize = 10.sp,
                    fontWeight = FontWeight.SemiBold,
                    letterSpacing = 0.4.sp,
                )
                Text(
                    title,
                    color = palette.text,
                    fontSize = 13.sp,
                    fontWeight = FontWeight.Bold,
                    maxLines = 1,
                    overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis,
                )
            }
            OutlinedButton(
                onClick = onSwap,
                shape = RoundedCornerShape(999.dp),
                contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = 10.dp, vertical = 0.dp),
                modifier = Modifier.height(28.dp),
            ) {
                Text(stringResource(R.string.tryon_source_swap), fontSize = 11.sp, fontWeight = FontWeight.SemiBold)
            }
        }
    }
}

/** Inline preview of the user's reference photos so they know what Gemini will work from. */
@Composable
private fun ReferencePhotosPreview(
    photoPaths: List<String>,
    onEdit: () -> Unit,
) {
    val palette = com.librelookai.ui.theme.LocalWardrobePalette.current
    val context = LocalContext.current
    Surface(
        shape = RoundedCornerShape(14.dp),
        color = palette.surface,
        border = androidx.compose.foundation.BorderStroke(1.dp, palette.divider),
    ) {
        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Default.Person, null, tint = palette.textMid, modifier = Modifier.size(16.dp))
                Spacer(Modifier.width(6.dp))
                Text(
                    stringResource(R.string.tryon_reference_photos_title),
                    color = palette.text,
                    fontSize = 12.sp,
                    fontWeight = FontWeight.Bold,
                    modifier = Modifier.weight(1f),
                )
                Text(
                    stringResource(R.string.tryon_reference_photos_edit),
                    color = palette.primary,
                    fontSize = 11.sp,
                    fontWeight = FontWeight.Bold,
                    modifier = Modifier.clickable(onClick = onEdit),
                )
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                photoPaths.take(3).forEach { path ->
                    Box(
                        modifier = Modifier
                            .weight(1f)
                            .aspectRatio(3f / 4f)
                            .clip(RoundedCornerShape(10.dp))
                            .border(1.dp, palette.divider, RoundedCornerShape(10.dp)),
                    ) {
                        AsyncImage(
                            model = ImageRequest.Builder(context).data(File(path)).build(),
                            contentDescription = null,
                            contentScale = ContentScale.Crop,
                            modifier = Modifier.fillMaxSize(),
                        )
                    }
                }
                repeat((3 - photoPaths.size).coerceAtLeast(0)) { Spacer(Modifier.weight(1f)) }
            }
        }
    }
}

/** State 5b — no reference photos uploaded. Presents the upload requirement inline. */
@Composable
private fun TryOnNoPhotos(onOpenSettings: () -> Unit) {
    val palette = com.librelookai.ui.theme.LocalWardrobePalette.current
    Column(Modifier.fillMaxSize()) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .weight(1f)
                .verticalScroll(rememberScrollState())
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            Surface(
                shape = RoundedCornerShape(18.dp),
                color = palette.primaryDim,
                border = androidx.compose.foundation.BorderStroke(1.dp, palette.border),
            ) {
                Column(
                    Modifier.padding(horizontal = 16.dp, vertical = 18.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(Icons.Default.Person, null, tint = palette.primary, modifier = Modifier.size(16.dp))
                        Spacer(Modifier.width(6.dp))
                        Text(
                            stringResource(R.string.tryon_photos_eyebrow).uppercase(),
                            color = palette.primary,
                            fontSize = 11.sp,
                            fontWeight = FontWeight.Bold,
                            letterSpacing = 0.5.sp,
                        )
                    }
                    Text(
                        stringResource(R.string.tryon_photos_title),
                        color = palette.text,
                        fontSize = 16.sp,
                        fontWeight = FontWeight.Bold,
                    )
                    Text(
                        stringResource(R.string.tryon_photos_body),
                        color = palette.textMid,
                        fontSize = 12.sp,
                    )
                }
            }
            Text(
                stringResource(R.string.tryon_photos_section_label).uppercase(),
                color = palette.textMid,
                fontSize = 12.sp,
                fontWeight = FontWeight.Bold,
                letterSpacing = 0.4.sp,
            )
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                listOf(
                    stringResource(R.string.tryon_slot_front),
                    stringResource(R.string.tryon_slot_side),
                    stringResource(R.string.tryon_slot_back),
                ).forEachIndexed { i, slotLabel ->
                    Column(
                        modifier = Modifier
                            .weight(1f)
                            .aspectRatio(3f / 4f)
                            .clip(RoundedCornerShape(14.dp))
                            .border(1.5.dp, palette.border, RoundedCornerShape(14.dp))
                            .background(palette.surface)
                            .clickable(onClick = onOpenSettings),
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.Center,
                    ) {
                        Icon(Icons.Default.Add, null, tint = palette.textMid, modifier = Modifier.size(22.dp))
                        Spacer(Modifier.size(4.dp))
                        Text(slotLabel, color = palette.text, fontSize = 11.sp, fontWeight = FontWeight.Bold)
                        if (i == 2) {
                            Text(stringResource(R.string.tryon_photos_optional), color = palette.textMuted, fontSize = 9.sp)
                        }
                    }
                }
            }
            Surface(
                shape = RoundedCornerShape(14.dp),
                color = palette.surface,
                border = androidx.compose.foundation.BorderStroke(1.dp, palette.divider),
            ) {
                Row(Modifier.padding(12.dp), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    Icon(Icons.Default.AutoAwesome, null, tint = palette.primary, modifier = Modifier.size(18.dp))
                    Text(
                        stringResource(R.string.tryon_photos_tip),
                        color = palette.textMid,
                        fontSize = 11.sp,
                    )
                }
            }
        }
        HorizontalDivider()
        Box(Modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 12.dp)) {
            OutlinedButton(
                onClick = onOpenSettings,
                shape = RoundedCornerShape(25.dp),
                modifier = Modifier.fillMaxWidth().height(50.dp),
            ) {
                Text(stringResource(R.string.tryon_photos_open_settings), fontWeight = FontWeight.SemiBold)
            }
        }
    }
}

/** State 5c — generating overlay card (try-on specific; leaves the shared AiProcessingOverlay untouched). */
@Composable
private fun TryOnGeneratingOverlay(itemCount: Int, modifier: Modifier = Modifier) {
    val palette = com.librelookai.ui.theme.LocalWardrobePalette.current
    Box(
        modifier = modifier.background(Color.Black.copy(alpha = 0.55f)),
        contentAlignment = Alignment.Center,
    ) {
        Surface(
            shape = RoundedCornerShape(22.dp),
            color = palette.surface,
            border = androidx.compose.foundation.BorderStroke(1.dp, palette.divider),
            modifier = Modifier.widthIn(max = 300.dp).padding(horizontal = 24.dp),
        ) {
            Column(
                modifier = Modifier.padding(horizontal = 22.dp, vertical = 22.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                Box(contentAlignment = Alignment.Center) {
                    CircularProgressIndicator(
                        modifier = Modifier.size(64.dp),
                        strokeWidth = 3.dp,
                        color = palette.primary,
                        trackColor = palette.primaryDim,
                    )
                    Icon(Icons.Default.AutoAwesome, null, tint = palette.primary, modifier = Modifier.size(24.dp))
                }
                Spacer(Modifier.size(14.dp))
                Text(
                    stringResource(R.string.tryon_generating_title),
                    color = palette.text,
                    fontSize = 15.sp,
                    fontWeight = FontWeight.Bold,
                )
                Spacer(Modifier.size(4.dp))
                Text(
                    stringResource(R.string.tryon_generating_body, itemCount),
                    color = palette.textMuted,
                    fontSize = 12.sp,
                    textAlign = androidx.compose.ui.text.style.TextAlign.Center,
                )
            }
        }
    }
}

/** One page of the swipeable history detail pager — resolves the source outfit and wires the
 *  per-try-on actions onto [TryOnDetailContent]. */
@Composable
private fun TryOnDetailPage(
    tryOn: TryOn,
    combinedImages: List<DriveImage>,
    outfits: List<Outfit>,
    onOpenSourceOutfit: ((Outfit) -> Unit)?,
    onItemTap: (DriveImage) -> Unit,
    tryOnViewModel: TryOnViewModel,
) {
    val sourceOutfit = remember(tryOn.sourceOutfitId, outfits) {
        tryOn.sourceOutfitId?.let { id -> outfits.firstOrNull { it.id == id } }
    }
    TryOnDetailContent(
        tryOn = tryOn,
        wardrobeImages = combinedImages,
        sourceOutfit = sourceOutfit,
        onOpenSourceOutfit = onOpenSourceOutfit?.let { open -> { sourceOutfit?.let(open) } },
        onDelete = { tryOnViewModel.deleteTryOn(tryOn) },
        onItemTap = onItemTap,
        onRegenerate = {
            Analytics.action("TryOn/Detail", "regenerate")
            val ids = tryOn.itemNames
                .mapNotNull { n -> combinedImages.firstOrNull { it.name == n } }
                .map { it.driveId }.toSet()
            tryOnViewModel.openComposer(
                ids, tryOn.sourceOutfitId,
                tryOnSourceKindOf(tryOn.sourceKind),
                tryOn.sourceContext.takeIf { it.isNotBlank() },
            )
        },
    )
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun TryOnDetailContent(
    tryOn: TryOn,
    wardrobeImages: List<DriveImage>,
    sourceOutfit: Outfit?,
    onOpenSourceOutfit: (() -> Unit)?,
    onDelete: () -> Unit,
    onItemTap: (DriveImage) -> Unit,
    onRegenerate: () -> Unit,
) {
    val palette = com.librelookai.ui.theme.LocalWardrobePalette.current
    var confirmDelete by remember { mutableStateOf(false) }
    val kind = tryOnSourceKindOf(tryOn.sourceKind)
    val items = remember(tryOn.itemNames, wardrobeImages) {
        tryOn.itemNames.mapNotNull { n -> wardrobeImages.firstOrNull { it.name == n } }
    }

    Column(modifier = Modifier.fillMaxSize()) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .weight(1f)
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 14.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            Spacer(Modifier.size(0.dp))
            // Image card.
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .aspectRatio(4f / 5f)
                    .clip(RoundedCornerShape(22.dp))
                    .border(1.dp, palette.divider, RoundedCornerShape(22.dp))
                    .background(Color.Black),
            ) {
                if (tryOn.localPath.isNotEmpty()) {
                    ZoomableImage(file = File(tryOn.localPath))
                } else {
                    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                        CircularProgressIndicator(color = Color.White)
                    }
                }
                SourcePill(
                    kind = kind,
                    label = tryOn.sourceContext.takeIf { it.isNotBlank() }
                        ?: stringResource(sourceMeta(kind).labelRes),
                    solid = true,
                    modifier = Modifier.align(Alignment.TopStart).padding(12.dp),
                )
            }
            // Metadata card.
            Surface(
                shape = RoundedCornerShape(14.dp),
                color = palette.surface,
                border = androidx.compose.foundation.BorderStroke(1.dp, palette.divider),
            ) {
                Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Row {
                        Text(
                            stringResource(R.string.tryon_detail_generated).uppercase(),
                            color = palette.textMuted, fontSize = 10.sp, fontWeight = FontWeight.SemiBold,
                            letterSpacing = 0.4.sp, modifier = Modifier.weight(1f),
                        )
                        Text(
                            stringResource(R.string.tryon_detail_source).uppercase(),
                            color = palette.textMuted, fontSize = 10.sp, fontWeight = FontWeight.SemiBold,
                            letterSpacing = 0.4.sp,
                        )
                    }
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            DateFormat.getDateTimeInstance(DateFormat.MEDIUM, DateFormat.SHORT).format(Date(tryOn.createdAt)),
                            color = palette.text, fontSize = 13.sp, fontWeight = FontWeight.Bold,
                            modifier = Modifier.weight(1f),
                        )
                        if (sourceOutfit != null && onOpenSourceOutfit != null) {
                            Text(
                                stringResource(R.string.tryon_view_source_outfit),
                                color = palette.primary, fontSize = 12.sp, fontWeight = FontWeight.Bold,
                                modifier = Modifier.clickable {
                                    Analytics.action("TryOn/Detail", "open_source_outfit")
                                    onOpenSourceOutfit()
                                },
                            )
                        } else {
                            Text(
                                tryOn.sourceContext.takeIf { it.isNotBlank() }
                                    ?: stringResource(sourceMeta(kind).labelRes),
                                color = palette.textMid, fontSize = 12.sp, fontWeight = FontWeight.SemiBold,
                            )
                        }
                    }
                }
            }
            // Items worn — stacked overlapping cutouts, matching the composer. Tap to open.
            Text(
                stringResource(R.string.tryon_history_items_title),
                color = palette.text, fontSize = 13.sp, fontWeight = FontWeight.Bold,
            )
            if (items.isEmpty()) {
                Text(
                    stringResource(R.string.tryon_history_items_missing),
                    style = MaterialTheme.typography.bodySmall,
                    color = palette.textMuted,
                )
            } else {
                TryOnItemStack(
                    images = items,
                    modifier = Modifier.fillMaxWidth(),
                    onItemClick = { img ->
                        Analytics.action("TryOn/Detail", "view_item")
                        onItemTap(img)
                    },
                )
            }
            Spacer(Modifier.size(0.dp))
        }
        // Sticky action row — pinned so Regenerate / Delete are always on screen.
        HorizontalDivider()
        Row(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 10.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            OutlinedButton(
                onClick = onRegenerate,
                shape = RoundedCornerShape(22.dp),
                modifier = Modifier.weight(1f).height(44.dp),
            ) {
                Icon(Icons.Default.Refresh, null, modifier = Modifier.size(16.dp))
                Spacer(Modifier.width(6.dp))
                Text(stringResource(R.string.tryon_detail_regenerate), fontSize = 12.sp, fontWeight = FontWeight.Bold)
            }
            OutlinedButton(
                onClick = {
                    Analytics.action("TryOn/Detail", "open_delete_dialog")
                    confirmDelete = true
                },
                shape = RoundedCornerShape(22.dp),
                colors = androidx.compose.material3.ButtonDefaults.outlinedButtonColors(contentColor = palette.error),
                modifier = Modifier.weight(1f).height(44.dp),
            ) {
                Icon(Icons.Default.Delete, null, modifier = Modifier.size(16.dp))
                Spacer(Modifier.width(6.dp))
                Text(stringResource(R.string.action_delete), fontSize = 12.sp, fontWeight = FontWeight.Bold)
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

