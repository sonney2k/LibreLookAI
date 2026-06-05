package com.librelookai.tryon

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.compose.ui.window.DialogWindowProvider
import com.librelookai.R
import com.librelookai.data.model.Location
import com.librelookai.data.model.Outfit
import com.librelookai.data.model.TryOn
import com.librelookai.outfit.AddItemSheet
import com.librelookai.settings.ProfileViewModel
import com.librelookai.shopping.MatchPreviewDialog
import com.librelookai.shopping.ShoppingClosetViewModel
import com.librelookai.shopping.ShoppingHelperScreen
import com.librelookai.util.Analytics
import com.librelookai.util.rememberDialogBottomInset
import com.librelookai.wardrobe.DriveImage
import com.librelookai.wardrobe.FullScreenViewer
import com.librelookai.wardrobe.WardrobeViewModel
import com.librelookai.wardrobe.tagCategories
import com.librelookai.wardrobe.fixCutoutBgForItem

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

    // Screen-view tracking: this Dialog hosts both the try-on composer and the history feed; report
    // whichever the user is currently looking at (toggling history re-reports).
    LaunchedEffect(state.isHistoryOpen) {
        Analytics.screen(if (state.isHistoryOpen) "TryOnHistory" else "TryOnComposer")
    }

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
                    }) { Icon(Icons.Default.Close, contentDescription = stringResource(R.string.action_close)) }
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

