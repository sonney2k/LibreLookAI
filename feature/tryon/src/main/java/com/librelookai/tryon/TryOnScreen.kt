package com.librelookai.tryon

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
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
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.librelookai.feature.tryon.R
import com.librelookai.core.designsystem.R as DsR
import com.librelookai.data.model.Location
import com.librelookai.data.model.Outfit
import com.librelookai.data.model.TryOn
import com.librelookai.settings.ProfileUiState
import com.librelookai.util.AiProcessingOverlay
import com.librelookai.util.Analytics
import com.librelookai.util.rememberDialogBottomInset
import com.librelookai.wardrobe.DriveImage
import java.io.File
import kotlinx.coroutines.flow.StateFlow

/**
 * Shared full-bleed chrome for the three try-on destinations (composer / history feed /
 * history detail — § 5 slice 9 split the old single layered surface into real routes).
 * Fills the window with the theme background so the underlying app never shows through the
 * status/nav-bar strips; only the Scaffold *content* is inset to the safe area. Scaffold's
 * default contentWindowInsets would double-add insets here — disabled (WindowInsets(0)).
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun TryOnPageScaffold(
    title: String,
    onClose: () -> Unit,
    content: @Composable () -> Unit,
) {
    val effectiveBottom = rememberDialogBottomInset()
    Box(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background)) {
        Scaffold(
            modifier = Modifier.fillMaxSize().statusBarsPadding().padding(bottom = effectiveBottom),
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
                    IconButton(onClick = onClose) {
                        Icon(Icons.Default.Close, contentDescription = stringResource(DsR.string.action_close))
                    }
                    Text(
                        title,
                        style = MaterialTheme.typography.titleMedium,
                        maxLines = 1,
                        overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f),
                    )
                }
            },
        ) { innerPadding ->
            Box(Modifier.fillMaxSize().padding(innerPadding)) { content() }
        }
    }
}

/**
 * Error dialog for the try-on flows. Resolve every string HERE (outside the AlertDialog) so it
 * uses the localized LocalContext: an AlertDialog opens its own window, which severs the locale
 * override inside its slot lambdas (title/text/buttons) and would otherwise fall back to the
 * system locale (CLAUDE.md → Window quirks). errorRes also avoids resolving in the ViewModel
 * (Application = system locale).
 */
@Composable
private fun TryOnErrorDialog(error: String?, errorRes: Int?, onClearError: () -> Unit) {
    val errorTitle = stringResource(R.string.tryon_error)
    val errorOk = stringResource(DsR.string.action_ok)
    val errorMsg = errorRes?.let { stringResource(it) } ?: error
    errorMsg?.let { msg ->
        AlertDialog(
            onDismissRequest = onClearError,
            title = { Text(errorTitle) },
            text  = { Text(msg) },
            confirmButton = {
                TextButton(onClick = onClearError) { Text(errorOk) }
            },
        )
    }
}

/**
 * Content of the `TryOnRoute` destination: the try-on composer (assemble items → generate →
 * result preview), plus the no-reference-photos empty state. Real navigation (§ 5 slice 9):
 * openers seed the draft via [TryOnViewModel.openComposer] then navigate; the header ✕ and
 * system back clear the draft ([TryOnViewModel.close]) and pop through [onClose]. The history
 * feed and detail views are separate destinations (`TryOnHistoryRoute` / `TryOnDetailRoute`).
 *
 * Takes the combined wardrobe+shopping [itemPool] and the profile surface as narrowed
 * data + callbacks threaded from AppContent (§ 1 slice 6, the onboarding precedent) — no
 * wardrobe/shopping/profile VM types cross into the try-on feature.
 */
@OptIn(ExperimentalMaterial3Api::class, ExperimentalFoundationApi::class)
@Composable
fun TryOnComposerScreen(
    tryOnViewModel: TryOnViewModel,
    /** The combined wardrobe + shopping pool try-on surfaces resolve ids/names against. */
    itemPool: List<DriveImage>,
    /** The profile surface (reference-photo paths + style prefs), read-only. */
    profileState: StateFlow<ProfileUiState>,
    /** Resolves the user's reference photos as local files at generate time. */
    tryOnFiles: () -> List<File>,
    /** Fuzzy text filter over the picker's candidates (wardrobe search). */
    onTextFilter: (String, List<DriveImage>) -> List<DriveImage>,
    /** Find-by-photo scorer for the picker: file + candidates → driveId→score. */
    findSimilarByPhoto: suspend (File, List<DriveImage>) -> Map<String, Float>,
    /** Outfits available to pick as the basis of a new try-on. Empty disables the "Use an outfit" path. */
    outfits: List<Outfit> = emptyList(),
    /** Locations passed through to [com.librelookai.outfit.AddItemSheet] so it can show closet badges. */
    locations: List<Location> = emptyList(),
    /** Open the Quick Try-On sheet (source-swap affordance). */
    onStartTryOn: () -> Unit = {},
    /** Close the surface and route the user to Settings → Profile (no-reference-photos state). */
    onOpenProfileSettings: () -> Unit = {},
    /** Pops the TryOnRoute destination. */
    onClose: () -> Unit = {},
) {
    val state by tryOnViewModel.state.collectAsState()
    val profile by profileState.collectAsState()
    val combinedImages = itemPool

    LaunchedEffect(Unit) { Analytics.screen("TryOnComposer") }

    BackHandler {
        Analytics.action("TryOn", "back")
        tryOnViewModel.close()
        onClose()
    }
    TryOnPageScaffold(
        title = stringResource(R.string.tryon_title),
        onClose = {
            Analytics.action("TryOn", "close")
            tryOnViewModel.close()
            onClose()
        },
    ) {
        when {
            state.resultPath != null -> TryOnResultContent(
                state = state,
                onSave = {
                    Analytics.action("TryOn/Result", "save")
                    tryOnViewModel.saveCurrent(combinedImages)
                },
                onTryAgain = {
                    Analytics.action("TryOn/Result", "try_again")
                    tryOnViewModel.generate(
                        personFiles     = tryOnFiles(),
                        wardrobeImages  = combinedImages,
                        preferences     = profile.preferences.preferences,
                    )
                },
                onChangeItems = {
                    Analytics.action("TryOn/Result", "change_items")
                    tryOnViewModel.openComposer(state.itemIds, state.sourceOutfitId, state.sourceKind, state.sourceContext)
                },
                wardrobeImages = combinedImages,
            )

            profile.tryOnLocalPaths.isEmpty() -> TryOnNoPhotos(
                onOpenSettings = onOpenProfileSettings,
            )

            else -> TryOnComposerContent(
                state = state,
                wardrobeImages = combinedImages,
                outfits = outfits,
                locations = locations,
                referencePhotoPaths = profile.tryOnLocalPaths.values.toList(),
                onTextFilter = onTextFilter,
                findSimilarByPhoto = findSimilarByPhoto,
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
                    onClose()
                },
                onGenerate = {
                    Analytics.action("TryOn/Composer", "generate", mapOf("count" to state.itemIds.size.toString()))
                    tryOnViewModel.generate(
                        personFiles     = tryOnFiles(),
                        wardrobeImages  = combinedImages,
                        preferences     = profile.preferences.preferences,
                    )
                },
            )
        }

        // Generating overlay — covers everything. Uses the shared AI progress overlay (live
        // upload bar + wait estimate + elapsed counter) so try-on shows the same progress
        // feedback as every other AI surface.
        if (state.isGenerating) {
            AiProcessingOverlay(
                label = stringResource(R.string.tryon_generating),
                modifier = Modifier.fillMaxSize(),
            )
        }

        TryOnErrorDialog(state.error, state.errorRes, tryOnViewModel::clearError)

        // The InsufficientCreditsDialog for 402s is installed globally in MainActivity — it
        // listens on CreditsEvents.topUp and routes "Buy" to the Settings tab. We only need to
        // reset isGenerating here, which TryOnViewModel.generate() handles.
    }
}

/**
 * Content of the `TryOnHistoryRoute` destination: the past-try-ons hero feed. Tapping a tile
 * navigates to `TryOnDetailRoute`; the hero's edit action seeds the composer draft and
 * navigates to `TryOnRoute` (so back returns to the feed).
 */
@Composable
fun TryOnHistoryDestination(
    tryOnViewModel: TryOnViewModel,
    historyViewModel: TryOnHistoryViewModel,
    /** The combined wardrobe + shopping pool try-on surfaces resolve ids/names against. */
    itemPool: List<DriveImage>,
    /** Open the detail destination for a tapped try-on. */
    onOpenDetail: (TryOn) -> Unit,
    /** Open the Quick Try-On sheet (feed FAB + empty-state CTA). */
    onStartTryOn: () -> Unit,
    /** Navigate to the composer destination after the hero's edit action seeds the draft. */
    onOpenComposer: () -> Unit,
    /** Pops the TryOnHistoryRoute destination. */
    onClose: () -> Unit,
) {
    val historyState by historyViewModel.state.collectAsState()
    val combinedImages = itemPool

    LaunchedEffect(Unit) {
        Analytics.screen("TryOnHistory")
        historyViewModel.refresh()
    }

    TryOnPageScaffold(
        title = stringResource(R.string.tryon_history_title),
        onClose = {
            Analytics.action("TryOn", "close")
            onClose()
        },
    ) {
        TryOnHistoryFeed(
            history = historyState.history,
            wardrobeImages = combinedImages,
            onOpen = onOpenDetail,
            onStartTryOn = onStartTryOn,
            showFab = true,
            onEditHero = { t ->
                val byKey = combinedImages.associateBy { com.librelookai.util.ImageEncoding.itemMatchKey(it.name) }
                val ids = t.itemNames
                    .mapNotNull { n -> byKey[com.librelookai.util.ImageEncoding.itemMatchKey(n)] }
                    .map { it.driveId }.toSet()
                tryOnViewModel.openComposer(
                    ids, t.sourceOutfitId,
                    tryOnSourceKindOf(t.sourceKind),
                    t.sourceContext.takeIf { it.isNotBlank() },
                )
                onOpenComposer()
            },
        )
    }
}

/**
 * Content of the `TryOnDetailRoute` destination: swipe left/right between past try-ons. The
 * pager spans the live derived history (deletes shrink it in place; deleting the last entry
 * pops the destination). Regenerate seeds the composer draft and navigates to `TryOnRoute`.
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
fun TryOnDetailDestination(
    initialImageDriveId: String?,
    tryOnViewModel: TryOnViewModel,
    historyViewModel: TryOnHistoryViewModel,
    /** The combined wardrobe + shopping pool try-on surfaces resolve ids/names against. */
    itemPool: List<DriveImage>,
    /** Outfits pool for resolving a try-on's source-outfit link. */
    outfits: List<Outfit> = emptyList(),
    /** Open the saved outfit linked from the detail view. Hidden when null. */
    onOpenSourceOutfit: ((Outfit) -> Unit)? = null,
    /** Navigate to the composer destination after Regenerate seeds the draft. */
    onOpenComposer: () -> Unit = {},
    /** Open the item-viewer destination over this one (the try-on's items, tapped item). */
    onOpenItemViewer: (List<String>, String) -> Unit = { _, _ -> },
    /** Pops the TryOnDetailRoute destination. */
    onClose: () -> Unit,
) {
    val historyState by historyViewModel.state.collectAsState()
    val combinedImages = itemPool
    val history = historyState.history

    LaunchedEffect(Unit) { Analytics.screen("TryOnDetail") }

    // Every close path funnels through here exactly once: deleting the last try-on both empties
    // the live history (the LaunchedEffect below) and could race a header close — the latch
    // keeps a double pop from dismissing the screen underneath as well.
    var closed by remember { mutableStateOf(false) }
    val closeViewer: () -> Unit = {
        if (!closed) {
            closed = true
            onClose()
        }
    }
    LaunchedEffect(history.isEmpty()) {
        if (history.isEmpty()) closeViewer()
    }
    if (history.isEmpty()) return

    // Tapping an item on a detail page opens the item-viewer destination with the try-on's
    // items (resolved against the combined wardrobe + shopping pool at tap time; the
    // destination re-resolves them live while it's up).
    val openItemViewer: (TryOn, DriveImage) -> Unit = { tryOn, img ->
        val byKey = combinedImages.associateBy { com.librelookai.util.ImageEncoding.itemMatchKey(it.name) }
        val ids = tryOn.itemNames.mapNotNull { n ->
            byKey[com.librelookai.util.ImageEncoding.itemMatchKey(n)]?.driveId
        }
        onOpenItemViewer(ids, img.driveId)
    }

    TryOnPageScaffold(
        title = stringResource(R.string.tryon_history_detail_title),
        onClose = {
            Analytics.action("TryOn", "close")
            closeViewer()
        },
    ) {
        // First-composition snapshot — the pager only reads its initial page once.
        val startIndex = remember {
            history.indexOfFirst { it.imageDriveId == initialImageDriveId }.coerceAtLeast(0)
        }
        val pagerState = rememberPagerState(
            initialPage = startIndex.coerceIn(0, history.lastIndex),
            pageCount = { history.size },
        )
        HorizontalPager(state = pagerState, modifier = Modifier.fillMaxSize()) { page ->
            history.getOrNull(page)?.let { tryOn ->
                TryOnDetailPage(
                    tryOn = tryOn,
                    combinedImages = combinedImages,
                    outfits = outfits,
                    onOpenSourceOutfit = onOpenSourceOutfit,
                    onItemTap = { img -> openItemViewer(tryOn, img) },
                    tryOnViewModel = tryOnViewModel,
                    onDelete = historyViewModel::deleteTryOn,
                    onOpenComposer = onOpenComposer,
                )
            }
        }

        TryOnErrorDialog(historyState.error, historyState.errorRes, historyViewModel::clearError)
    }
}
