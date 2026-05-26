package com.librelookai.outfit
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.itemsIndexed
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.activity.compose.BackHandler
import androidx.compose.material.icons.filled.ImageSearch
import kotlinx.coroutines.launch
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.AutoAwesome
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Checkroom
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.ExpandLess
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.LockOpen
import androidx.compose.material.icons.filled.MoreHoriz
import androidx.compose.material.icons.filled.Place
import androidx.compose.material.icons.filled.SwapHoriz
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowLeft
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.Tune
import androidx.compose.material.icons.filled.WbSunny
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.InputChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TextFieldDefaults
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.blur
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.offset
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.compose.ui.window.DialogWindowProvider
import coil.compose.AsyncImage
import coil.request.ImageRequest
import androidx.compose.foundation.layout.widthIn
import com.librelookai.data.model.Location
import com.librelookai.data.model.Outfit
import com.librelookai.settings.ProfileViewModel
import com.librelookai.shopping.ShoppingClosetViewModel
import com.librelookai.util.AiProcessingOverlay
import com.librelookai.util.Analytics
import com.librelookai.util.LocalIsOffline
import com.librelookai.util.LocalSystemBarsPadding
import com.librelookai.wardrobe.DriveImage
import com.librelookai.wardrobe.tagCategories
import com.librelookai.wardrobe.tagStringsForCategory
import com.librelookai.wardrobe.LocationViewModel
import com.librelookai.wardrobe.WardrobeViewModel
import com.librelookai.weather.WeatherData
import com.librelookai.weather.WeatherViewModel
import com.librelookai.R
import com.librelookai.weather.wmoEmoji

private fun DriveImage.displayLabel(): String =
    tags?.label?.ifEmpty { null }
        ?: tags?.type?.ifEmpty { null }
        ?: name

/**
 * Unified full-screen composer for creating/viewing an outfit.
 * In VIEW mode: read-only slot cards with images or silhouettes.
 * In EDIT mode: lock toggles, exchange buttons, "+ Add slot", Generate-with-AI, Save.
 */
@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
fun OutfitComposerScreen(
    stylesViewModel: OutfitsViewModel,
    wardrobeViewModel: WardrobeViewModel,
    profileViewModel: ProfileViewModel,
    weatherViewModel: WeatherViewModel,
    shoppingClosetViewModel: ShoppingClosetViewModel = androidx.lifecycle.viewmodel.compose.viewModel(),
    locationViewModel: LocationViewModel = androidx.lifecycle.viewmodel.compose.viewModel(),
    outfitEventsViewModel: OutfitEventsViewModel = androidx.lifecycle.viewmodel.compose.viewModel(),
) {
    val s by stylesViewModel.state.collectAsState()
    val wardrobe by wardrobeViewModel.state.collectAsState()
    val profile by profileViewModel.state.collectAsState()
    val weather by weatherViewModel.state.collectAsState()
    val shoppingState by shoppingClosetViewModel.state.collectAsState()
    val locationState by locationViewModel.state.collectAsState()
    val outfitEventsState by outfitEventsViewModel.state.collectAsState()

    // driveId → number of calendar wear events that include this item (for AddItemSheet sort)
    val popularityMap = remember(outfitEventsState.events, s.outfits) {
        val outfitWearCount = outfitEventsState.events.groupingBy { it.outfitId }.eachCount()
        val itemCount = mutableMapOf<String, Int>()
        s.outfits.forEach { style ->
            val count = outfitWearCount[style.id] ?: 0
            if (count > 0) style.itemIds.forEach { id -> itemCount[id] = (itemCount[id] ?: 0) + count }
        }
        itemCount as Map<String, Int>
    }
    val parentContext = LocalContext.current
    val parentConfiguration = LocalConfiguration.current
    val isOffline = LocalIsOffline.current

    if (!s.isComposerOpen) return

    val isEditMode = s.composerMode == ComposerMode.EDIT
    val sourceFolders = s.composerSourceFolderIds
    val crossClosetImages = wardrobe.allLocationImages.ifEmpty { wardrobe.images }
    val composerImages = remember(crossClosetImages, shoppingState.items, sourceFolders) {
        val filteredWardrobe = if (sourceFolders.isEmpty()) crossClosetImages
        else crossClosetImages.filter { it.folderId in sourceFolders }
        filteredWardrobe + shoppingState.items
    }
    val byId = remember(composerImages) { composerImages.associateBy { it.driveId } }

    var showAddSlotSheet by remember { mutableStateOf(false) }
    var exchangeSlotId by remember { mutableStateOf<String?>(null) }
    var showWeatherSheet by remember { mutableStateOf(false) }
    var showClosetSheet by remember { mutableStateOf(false) }
    var showDiscardDialog by remember { mutableStateOf(false) }
    var viewerImage by remember { mutableStateOf<DriveImage?>(null) }

    val hasChanges = s.composerSlots.map { it.selectedItemId } != s.composerInitialItemIds ||
        s.composerVibes.isNotEmpty()
    val requestClose: () -> Unit = {
        if (isEditMode && hasChanges) showDiscardDialog = true
        else {
            Analytics.action("OutfitComposer", "close")
            stylesViewModel.closeComposer()
        }
    }

    val barInsets = LocalSystemBarsPadding.current
    val view = androidx.compose.ui.platform.LocalView.current
    val density = LocalDensity.current
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
    val effectiveBottom = maxOf(barInsets.calculateBottomPadding(), rootInsetBottomDp, 48.dp)

    // Effective slots drop the empty Top/Bottom (or empty OnePiece) collapsed away by a
    // one-piece, so counts and completeness treat a dress outfit as fully filled.
    val effectiveSlots = collapseOnePieceSlots(s.composerSlots)
    val filledSlots = effectiveSlots.count { it.selectedItemId != null }

    Dialog(
        onDismissRequest = requestClose,
        properties = DialogProperties(
            usePlatformDefaultWidth = false,
            dismissOnBackPress = true,
            dismissOnClickOutside = false,
            decorFitsSystemWindows = false,
        ),
    ) {
        val dialogView = androidx.compose.ui.platform.LocalView.current
        androidx.compose.runtime.SideEffect {
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
                Box(modifier = Modifier.fillMaxSize()) {
                    Column(modifier = Modifier.fillMaxSize().imePadding()) {
                        if (isEditMode) {
                            ComposerHeader(
                                filledSlots = filledSlots,
                                totalSlots = effectiveSlots.size,
                                onClose = requestClose,
                                onOpenFullscreen = {
                                    stylesViewModel.setComposerMode(ComposerMode.VIEW)
                                },
                            )
                        }

                        if (isEditMode) {
                            ComposerStackedView(
                                slots = s.composerSlots,
                                byId = byId,
                                locations = locationState.locations,
                                isEditMode = true,
                                onPickItem = { slotId -> exchangeSlotId = slotId },
                                onToggleLock = { slotId -> stylesViewModel.toggleSlotLock(slotId) },
                                onRemove = { slotId -> stylesViewModel.removeSlot(slotId) },
                                modifier = Modifier
                                    .weight(1f)
                                    .fillMaxWidth()
                                    .padding(horizontal = 12.dp, vertical = 8.dp),
                            )
                        } else {
                            // VIEW mode: mirror OutfitFullScreenViewer — show name / description /
                            // tags above an OutfitPageBody so the composer's fullscreen preview matches
                            // the saved-outfit viewer.
                            val draftOutfit = remember(
                                s.composerName, s.composerAiSuggestedName,
                                s.composerDescription, s.composerAiSuggestedDescription,
                                s.composerTags, s.composerAiSuggestedTags,
                                s.composerSlots,
                            ) {
                                Outfit(
                                    id = "draft",
                                    name = s.composerName.ifBlank { s.composerAiSuggestedName },
                                    description = s.composerDescription.ifBlank { s.composerAiSuggestedDescription },
                                    tags = s.composerTags.ifEmpty { s.composerAiSuggestedTags },
                                    itemIds = s.composerSlots.mapNotNull { it.selectedItemId },
                                )
                            }
                            val hasMeta = draftOutfit.name.isNotBlank() ||
                                draftOutfit.description.isNotBlank() ||
                                draftOutfit.tags.isNotEmpty()
                            if (hasMeta) {
                                Column(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .padding(
                                            top = 8.dp,
                                            start = 56.dp, end = 56.dp, bottom = 8.dp,
                                        ),
                                    horizontalAlignment = Alignment.CenterHorizontally,
                                    verticalArrangement = Arrangement.spacedBy(4.dp),
                                ) {
                                    if (draftOutfit.name.isNotBlank()) {
                                        Text(
                                            text = draftOutfit.name,
                                            color = MaterialTheme.colorScheme.onBackground,
                                            style = MaterialTheme.typography.titleMedium,
                                            maxLines = 2,
                                            overflow = TextOverflow.Ellipsis,
                                        )
                                    }
                                    if (draftOutfit.description.isNotBlank()) {
                                        Text(
                                            text = draftOutfit.description,
                                            color = MaterialTheme.colorScheme.onBackground.copy(alpha = 0.85f),
                                            style = MaterialTheme.typography.bodySmall,
                                            maxLines = 3,
                                            overflow = TextOverflow.Ellipsis,
                                        )
                                    }
                                    if (draftOutfit.tags.isNotEmpty()) {
                                        val maxWidth = LocalConfiguration.current.screenWidthDp.dp * 0.85f
                                        FlowRow(
                                            modifier = Modifier.widthIn(max = maxWidth),
                                            horizontalArrangement = Arrangement.spacedBy(4.dp, Alignment.CenterHorizontally),
                                            verticalArrangement = Arrangement.spacedBy(4.dp),
                                        ) {
                                            draftOutfit.tags.forEach { OutfitTagChip(it) }
                                        }
                                    }
                                }
                            } else {
                                // No metadata: leave room at the top for the close-X overlay.
                                Spacer(Modifier.height(48.dp))
                            }
                            Box(modifier = Modifier.weight(1f).fillMaxWidth()) {
                                OutfitPageBody(
                                    outfit = draftOutfit,
                                    itemsById = byId,
                                    locations = locationState.locations,
                                    onItemClick = { viewerImage = it },
                                    bottomPadding = effectiveBottom,
                                )
                            }
                        }

                        if (isEditMode || s.composerReason.isNotBlank() || s.composerError != null) {
                            Column(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(horizontal = 16.dp)
                                    .padding(bottom = 8.dp),
                                verticalArrangement = Arrangement.spacedBy(8.dp),
                            ) {
                                if (isEditMode && s.composerSuggestions.size > 1) {
                                    ComposerSuggestionSwiper(
                                        index = s.composerSuggestionIndex,
                                        count = s.composerSuggestions.size,
                                        onPrev = {
                                            val n = s.composerSuggestions.size
                                            val target = ((s.composerSuggestionIndex - 1) % n + n) % n
                                            Analytics.action("OutfitComposer", "suggestion_prev")
                                            stylesViewModel.showComposerSuggestionAt(target)
                                        },
                                        onNext = {
                                            val n = s.composerSuggestions.size
                                            val target = (s.composerSuggestionIndex + 1) % n
                                            Analytics.action("OutfitComposer", "suggestion_next")
                                            stylesViewModel.showComposerSuggestionAt(target)
                                        },
                                        onOpenFullscreen = {
                                            Analytics.action("OutfitComposer", "suggestions_viewer_reopen")
                                            stylesViewModel.openComposerSuggestionsViewer()
                                        },
                                    )
                                }
                                if (isEditMode) {
                                    TextButton(
                                        onClick = { showAddSlotSheet = true },
                                        modifier = Modifier.fillMaxWidth(),
                                    ) {
                                        Icon(Icons.Default.Add, contentDescription = null, modifier = Modifier.size(16.dp))
                                        Spacer(Modifier.width(4.dp))
                                        Text(stringResource(R.string.outfit_slot_add))
                                        Spacer(Modifier.width(4.dp))
                                        Icon(
                                            Icons.AutoMirrored.Filled.KeyboardArrowRight,
                                            contentDescription = null,
                                            modifier = Modifier.size(16.dp),
                                        )
                                    }
                                }
                                if (s.composerReason.isNotBlank()) {
                                    Surface(
                                        shape = RoundedCornerShape(12.dp),
                                        color = MaterialTheme.colorScheme.surface,
                                        border = androidx.compose.foundation.BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
                                        modifier = Modifier.fillMaxWidth(),
                                    ) {
                                        Text(
                                            s.composerReason,
                                            style = MaterialTheme.typography.bodySmall,
                                            modifier = Modifier.padding(12.dp),
                                        )
                                    }
                                }
                                s.composerError?.let {
                                    Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
                                }
                            }
                        }

                        if (isEditMode) {
                            // AI can only act on slots that are not (locked AND filled).
                            // If every slot is locked + has an item, there's nothing for AI to do.
                            // Collapsed-away empties don't count — a dress outfit can still be complete.
                            val aiCanGenerate = effectiveSlots.any {
                                !(it.isLocked && it.selectedItemId != null)
                            }
                            ComposerEditBottomBar(
                                saveEnabled = effectiveSlots.isNotEmpty() &&
                                    effectiveSlots.all { it.selectedItemId != null },
                                aiEnabled = aiCanGenerate,
                                isOffline = isOffline,
                                onGenerateWithAi = {
                                    Analytics.action("OutfitComposer", "generate_with_ai")
                                    stylesViewModel.openPredictionSetup(
                                        defaultSourceFolderId = null,
                                        source = PredictionSetupSource.COMPOSER,
                                    )
                                },
                                onSave = {
                                    Analytics.action("OutfitComposer", "save")
                                    stylesViewModel.prepareSave()
                                },
                                bottomPadding = effectiveBottom,
                            )
                        }
                    }

                    if (!isEditMode) {
                        // Fullscreen view: minimal close X (top-left) to return to edit mode.
                        IconButton(
                            onClick = { stylesViewModel.setComposerMode(ComposerMode.EDIT) },
                            modifier = Modifier
                                .align(Alignment.TopStart)
                                .padding(8.dp),
                        ) {
                            Icon(
                                Icons.Default.Close,
                                contentDescription = stringResource(R.string.action_dismiss),
                                tint = MaterialTheme.colorScheme.onBackground,
                            )
                        }
                    }

                    if (s.isComposerEnhancing) {
                        AiProcessingOverlay(
                            label = stringResource(R.string.composer_enhancing),
                            modifier = Modifier.fillMaxSize(),
                        )
                    }
                }
            }
        }
    }

    if (showDiscardDialog) {
        DiscardChangesDialog(
            parentContext = parentContext,
            parentConfiguration = parentConfiguration,
            onConfirm = {
                Analytics.action("OutfitComposer", "close")
                stylesViewModel.closeComposer()
            },
            onDismiss = { showDiscardDialog = false },
        )
    }

    if (s.isSaveDialogOpen) {
        val existingOutfit = s.composerEditingOutfitId?.let { id -> s.outfits.find { it.id == id } }
        // Build a frequency-ranked list of tags already used on other outfits so the user can
        // re-apply familiar tags with a single tap instead of typing them again.
        val tagSuggestions = remember(s.outfits, existingOutfit?.id) {
            s.outfits
                .filter { it.id != existingOutfit?.id }
                .flatMap { it.tags }
                .map { it.trim() }
                .filter { it.isNotEmpty() }
                .groupingBy { it.lowercase(java.util.Locale.ROOT) }
                .eachCount()
                .entries
                .sortedByDescending { it.value }
                .map { it.key }
                .take(20)
        }
        SaveOutfitDialog(
            initialName = s.composerName.ifBlank {
                existingOutfit?.name ?: s.composerAiSuggestedName
            },
            initialDescription = s.composerDescription.ifBlank {
                existingOutfit?.description ?: s.composerAiSuggestedDescription
            },
            initialTags = s.composerTags.ifEmpty {
                existingOutfit?.tags ?: s.composerAiSuggestedTags
            },
            tagSuggestions = tagSuggestions,
            parentContext = parentContext,
            parentConfiguration = parentConfiguration,
            onConfirm = { name, description, tags ->
                stylesViewModel.dismissSaveDialog()
                stylesViewModel.commitOutfit(name, description, tags)
            },
            onDismiss = { stylesViewModel.dismissSaveDialog() },
        )
    }

    if (s.composerSuggestionsViewerOpen && s.composerSuggestions.size > 1) {
        CompositionLocalProvider(
            LocalContext provides parentContext,
            LocalConfiguration provides parentConfiguration,
        ) {
            ComposerSuggestionsViewer(
                suggestions = s.composerSuggestions,
                initialIndex = s.composerSuggestionIndex,
                slots = s.composerSlots,
                itemsById = byId,
                locations = locationState.locations,
                onSelect = { index ->
                    Analytics.action("OutfitComposer", "suggestion_select_from_viewer")
                    // Commits the pick and drops the other suggestions — once the user has
                    // chosen, alternatives just clutter the composer.
                    stylesViewModel.commitComposerSuggestion(index)
                },
                onDismiss = { stylesViewModel.closeComposerSuggestionsViewer() },
            )
        }
    }

    viewerImage?.let { img ->
        val draftItems = remember(s.composerSlots, byId) {
            s.composerSlots.mapNotNull { it.selectedItemId?.let(byId::get) }
        }
        if (draftItems.isEmpty()) {
            viewerImage = null
        } else {
            val startIdx = draftItems.indexOfFirst { it.driveId == img.driveId }
                .coerceAtLeast(0)
            val allTagCategories = remember(byId) { byId.values.toList().tagCategories() }
            CompositionLocalProvider(
                LocalContext provides parentContext,
                LocalConfiguration provides parentConfiguration,
            ) {
                com.librelookai.wardrobe.FullScreenViewer(
                    images = draftItems,
                    initialIndex = startIdx,
                    allTagCategories = allTagCategories,
                    onDismiss = { viewerImage = null },
                    onTagImage = wardrobeViewModel::tagImage,
                    onRemoveBackground = wardrobeViewModel::reprocessBackground,
                    onRotateImage = wardrobeViewModel::rotateImage,
                    onUpdateTags = wardrobeViewModel::updateTags,
                    onDeleteItem = { driveId -> wardrobeViewModel.deleteItems(setOf(driveId)) },
                    onMoveToLocation = wardrobeViewModel::moveItemsToLocation,
                    onCreateOutfitFromSelection = {},
                    onFixCutoutBg = wardrobeViewModel::fixCutoutBgForItem,
                    onLoadOriginal = wardrobeViewModel::ensureOriginalCached,
                    locations = locationState.locations,
                    activeLocationId = locationState.activeLocationId,
                    processingImageId = wardrobe.processingImageId,
                    writeMode = true,
                )
            }
        }
    }

    exchangeSlotId?.let { slotId ->
        val slot = s.composerSlots.find { it.id == slotId }
        if (slot != null) {
            CompositionLocalProvider(
                LocalContext provides parentContext,
                LocalConfiguration provides parentConfiguration,
            ) {
                val layerItems = composerImages.filter { layerFor(it) == slot.category }
                val alreadyChosen = s.composerSlots
                    .filter { it.id != slotId }
                    .mapNotNull { it.selectedItemId }
                    .toSet()
                AddItemSheet(
                    allItems = layerItems,
                    alreadyChosen = alreadyChosen,
                    locations = locationState.locations,
                    popularityMap = popularityMap,
                    onTextFilter = wardrobeViewModel::fuzzyFilterByText,
                    findSimilarByPhoto = { file, candidates ->
                        wardrobeViewModel.findSimilarInCandidates(file, candidates)
                            .associate { it.driveId to it.score }
                    },
                    onConfirm = { picked ->
                        picked.firstOrNull()?.let { stylesViewModel.setSlotItem(slotId, it) }
                        exchangeSlotId = null
                    },
                    onDismiss = { exchangeSlotId = null },
                )
            }
        }
    }

    if (showAddSlotSheet) {
        CompositionLocalProvider(
            LocalContext provides parentContext,
            LocalConfiguration provides parentConfiguration,
        ) {
            CategoryPickerSheet(
                onSelect = { layer ->
                    stylesViewModel.addSlot(layer)
                    showAddSlotSheet = false
                },
                onDismiss = { showAddSlotSheet = false },
            )
        }
    }

    if (showWeatherSheet) {
        CompositionLocalProvider(
            LocalContext provides parentContext,
            LocalConfiguration provides parentConfiguration,
        ) {
            WeatherPickerSheet(
                mode = s.composerWeatherMode,
                onModeChange = { stylesViewModel.setComposerWeatherMode(it) },
                autoWeather = weather.data,
                season = s.composerManualSeason,
                onSeason = { stylesViewModel.setComposerManualSeason(it) },
                tempC = s.composerManualTempC,
                onTempC = { stylesViewModel.setComposerManualTempC(it) },
                precip = s.composerManualPrecip,
                onPrecip = { stylesViewModel.setComposerManualPrecip(it) },
                onDismiss = { showWeatherSheet = false },
            )
        }
    }

    if (showClosetSheet) {
        CompositionLocalProvider(
            LocalContext provides parentContext,
            LocalConfiguration provides parentConfiguration,
        ) {
            ClosetPickerSheet(
                locations = locationState.locations,
                selected = sourceFolders,
                onToggle = { stylesViewModel.toggleComposerSourceFolder(it) },
                onDismiss = { showClosetSheet = false },
            )
        }
    }
}

// ─── Header ─────────────────────────────────────────────────────────────────

@Composable
private fun ComposerHeader(
    filledSlots: Int,
    totalSlots: Int,
    onClose: () -> Unit,
    onOpenFullscreen: () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(start = 8.dp, end = 8.dp, top = 4.dp, bottom = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        IconButton(onClick = onClose, modifier = Modifier.size(40.dp)) {
            Icon(Icons.Default.Close, contentDescription = stringResource(R.string.action_cancel))
        }
        Spacer(Modifier.width(4.dp))
        Column(modifier = Modifier.weight(1f)) {
            Text(
                stringResource(R.string.composer_title),
                style = MaterialTheme.typography.headlineSmall.copy(
                    fontSize = 36.sp,
                    fontWeight = FontWeight.Bold,
                ),
            )
            Text(
                stringResource(R.string.composer_header_subtitle, filledSlots),
                style = MaterialTheme.typography.bodySmall.copy(fontSize = 11.sp),
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        TextButton(onClick = onOpenFullscreen) {
            Text(
                stringResource(R.string.composer_mode_fullscreen),
                style = MaterialTheme.typography.labelMedium,
            )
        }
    }
    HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
}


// ─── Context strip ──────────────────────────────────────────────────────────

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun ContextStrip(
    weatherMode: ComposerWeatherMode,
    autoWeather: WeatherData?,
    manualTempC: Int?,
    closetNames: List<String>,
    closetPickerAvailable: Boolean,
    selectedVibes: Set<String>,
    onToggleVibe: (String) -> Unit,
    onClickWeather: () -> Unit,
    onClickCloset: () -> Unit,
    forecastDate: String? = null,
    forecastDayPreview: com.librelookai.data.model.DayForecast? = null,
) {
    val weatherLabel: String = when {
        forecastDayPreview != null -> {
            val date = runCatching { java.time.LocalDate.parse(forecastDayPreview.date) }.getOrNull()
            val label = date?.format(java.time.format.DateTimeFormatter.ofPattern("MMM d")) ?: ""
            "$label · ${forecastDayPreview.maxTempC.toInt()}°"
        }
        forecastDate != null -> {
            // Forecast picked but preview data not yet loaded
            val date = runCatching { java.time.LocalDate.parse(forecastDate) }.getOrNull()
            date?.format(java.time.format.DateTimeFormatter.ofPattern("MMM d")) ?: forecastDate
        }
        weatherMode == ComposerWeatherMode.AUTO -> autoWeather?.let { "${it.temperatureCelsius.toInt()}°" }
            ?: stringResource(R.string.composer_factor_weather_auto)
        // Manual mode always identifies itself as "Manual" so users can tell their override is in
        // effect; if a temp is set we append it ("Manual · 22°").
        else -> {
            val base = stringResource(R.string.composer_weather_manual)
            manualTempC?.let { "$base · ${it}°" } ?: base
        }
    }
    val vibes = listOf(
        "Casual" to R.string.composer_vibe_casual,
        "Business" to R.string.composer_vibe_business,
        "Formal" to R.string.composer_vibe_formal,
        "Streetwear" to R.string.composer_vibe_streetwear,
        "Minimalist" to R.string.composer_vibe_minimalist,
        "Sporty" to R.string.composer_vibe_sporty,
        "Elegant" to R.string.composer_vibe_elegant,
        "Classic" to R.string.composer_vibe_classic,
    )
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .horizontalScroll(rememberScrollState()),
        horizontalArrangement = Arrangement.spacedBy(6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        ContextChip(
            label = weatherLabel,
            icon = Icons.Default.WbSunny,
            active = true,
            onClick = onClickWeather,
        )
        if (closetPickerAvailable) {
            ContextChip(
                label = closetNames.takeIf { it.isNotEmpty() }?.joinToString(" · ")
                    ?: stringResource(R.string.composer_closets_all),
                icon = Icons.Default.Place,
                active = closetNames.isNotEmpty(),
                onClick = onClickCloset,
            )
        }
        vibes.forEach { (value, labelRes) ->
            ContextChip(
                label = stringResource(labelRes),
                icon = null,
                active = value in selectedVibes,
                onClick = { onToggleVibe(value) },
            )
        }
    }
}

@Composable
private fun ContextChip(
    label: String,
    icon: ImageVector?,
    active: Boolean,
    onClick: () -> Unit,
) {
    val bg = if (active) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.surface
    val fg = if (active) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.onSurface
    val borderColor = if (active) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outlineVariant
    Row(
        modifier = Modifier
            .clip(RoundedCornerShape(999.dp))
            .background(bg)
            .border(if (active) 1.5.dp else 1.dp, borderColor, RoundedCornerShape(999.dp))
            .clickable(onClick = onClick)
            .padding(horizontal = 12.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        if (icon != null) {
            Icon(icon, contentDescription = null, tint = fg, modifier = Modifier.size(12.dp))
        }
        Text(
            label,
            style = MaterialTheme.typography.labelMedium.copy(fontSize = 12.sp, fontWeight = FontWeight.SemiBold),
            color = fg,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}




// ─── Bottom bar (edit mode) ──────────────────────────────────────────────────

@Composable
private fun ComposerEditBottomBar(
    saveEnabled: Boolean,
    aiEnabled: Boolean,
    isOffline: Boolean,
    onGenerateWithAi: () -> Unit,
    onSave: () -> Unit,
    bottomPadding: Dp,
) {
    HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .background(MaterialTheme.colorScheme.background.copy(alpha = 0.95f))
            .padding(bottom = bottomPadding)
            .padding(start = 16.dp, end = 16.dp, top = 10.dp, bottom = 4.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (!isOffline) {
            OutlinedButton(
                onClick = onGenerateWithAi,
                enabled = aiEnabled,
                modifier = Modifier.weight(1f).height(48.dp),
                shape = RoundedCornerShape(24.dp),
            ) {
                com.librelookai.billing.CostBadge(com.librelookai.gemini.GeminiActionId.OUTFIT_SUGGESTION)
                Icon(Icons.Default.AutoAwesome, contentDescription = null, modifier = Modifier.size(16.dp))
                Spacer(Modifier.width(6.dp))
                Text(
                    stringResource(R.string.outfit_generate_with_ai),
                    style = MaterialTheme.typography.labelLarge.copy(fontSize = 13.sp, fontWeight = FontWeight.Bold),
                )
                Spacer(Modifier.width(4.dp))
                Icon(
                    Icons.AutoMirrored.Filled.KeyboardArrowRight,
                    contentDescription = null,
                    modifier = Modifier.size(16.dp),
                )
            }
        }
        androidx.compose.material3.FilledTonalButton(
            onClick = onSave,
            enabled = saveEnabled,
            shape = RoundedCornerShape(24.dp),
            contentPadding = PaddingValues(horizontal = 18.dp, vertical = 10.dp),
            modifier = Modifier.height(48.dp),
        ) {
            Icon(Icons.Default.Check, contentDescription = null, modifier = Modifier.size(16.dp))
            Spacer(Modifier.width(6.dp))
            Text(
                stringResource(R.string.composer_save),
                style = MaterialTheme.typography.labelLarge.copy(fontSize = 13.sp, fontWeight = FontWeight.Bold),
            )
        }
    }
}

// ─── Stacked composer view (overlapping tiles with drop shadows) ────────────

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun ComposerStackedView(
    slots: List<OutfitSlot>,
    byId: Map<String, DriveImage>,
    locations: List<Location>,
    isEditMode: Boolean,
    onPickItem: (slotId: String) -> Unit,
    onToggleLock: (slotId: String) -> Unit,
    onRemove: (slotId: String) -> Unit,
    modifier: Modifier = Modifier,
) {
    // A filled one-piece collapses the empty Top/Bottom slots it covers (and vice versa).
    // In view mode, also hide remaining empty silhouettes — match OutfitFullScreenViewer.
    val visibleSlots = remember(slots, isEditMode) {
        val collapsed = collapseOnePieceSlots(slots)
        if (isEditMode) collapsed else collapsed.filter { it.selectedItemId != null }
    }
    val grouped: Map<Layer, List<OutfitSlot>> = remember(visibleSlots) {
        visibleSlots.groupBy { it.category }
    }
    BoxWithConstraints(modifier = modifier) {
        if (visibleSlots.isEmpty()) {
            Text(
                stringResource(R.string.outfits_missing_items),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.outline,
                modifier = Modifier.align(Alignment.Center),
            )
            return@BoxWithConstraints
        }
        val onePiece = grouped[Layer.OnePiece].orEmpty()
        val tops = grouped[Layer.Top].orEmpty()
        val bottoms = grouped[Layer.Bottom].orEmpty()
        // A one-piece (Einteiler) covers BOTH the top and bottom. When it coexists with the Top/
        // Bottom slots it covers, lay them side by side as one "core band": the suit is a single
        // full-height rectangle (2 vertical units), the Top tile shares its TOP edge and the Bottom
        // tile shares its BOTTOM edge — instead of being stacked above/below the suit.
        val coreFlank = onePiece.isNotEmpty() && (tops.isNotEmpty() || bottoms.isNotEmpty())

        // Split the Top/Bottom tiles across a left and a right flank of the suit (left-biased: a
        // single top/bottom stays on the left, the next one appears on the right).
        val leftTops = tops.take((tops.size + 1) / 2)
        val rightTops = tops.drop((tops.size + 1) / 2)
        val leftBottoms = bottoms.take((bottoms.size + 1) / 2)
        val rightBottoms = bottoms.drop((bottoms.size + 1) / 2)
        val leftFlankUnits = maxOf(leftTops.size, leftBottoms.size)
        val rightFlankUnits = maxOf(rightTops.size, rightBottoms.size)

        // Top-to-bottom, left-to-right layout — no overlap, so every tile's lock/remove buttons
        // stay tappable. A square "unit" is chosen to fit the widest row and all rows within the
        // available area; the suit is 2 units tall, and in the core band it can be flanked by a
        // Top/Bottom column on each side.
        val rowGap = 8.dp
        val itemGap = 8.dp
        var vUnits = 0
        var hUnits = 1
        Layer.values().forEach { layer ->
            val s = grouped[layer]?.takeIf { it.isNotEmpty() } ?: return@forEach
            when {
                coreFlank && (layer == Layer.Top || layer == Layer.Bottom) -> Unit // folded into band
                coreFlank && layer == Layer.OnePiece -> {
                    vUnits += 2
                    hUnits = maxOf(hUnits, leftFlankUnits + onePiece.size + rightFlankUnits)
                }
                layer == Layer.OnePiece -> { vUnits += 2; hUnits = maxOf(hUnits, s.size) }
                else -> { vUnits += 1; hUnits = maxOf(hUnits, s.size) }
            }
        }
        vUnits = vUnits.coerceAtLeast(1)
        val byW = (maxWidth - itemGap * (hUnits - 1)) / hUnits
        val byH = (maxHeight - rowGap * (vUnits - 1)) / vUnits
        val unit = minOf(byW, byH).coerceIn(88.dp, 240.dp)

        Column(
            modifier = Modifier.align(Alignment.Center),
            verticalArrangement = Arrangement.spacedBy(rowGap),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Layer.values().forEach { layer ->
                val s = grouped[layer]?.takeIf { it.isNotEmpty() } ?: return@forEach
                when {
                    coreFlank && (layer == Layer.Top || layer == Layer.Bottom) -> Unit // in the band
                    coreFlank && layer == Layer.OnePiece -> ComposerCoreBand(
                        leftTops = leftTops,
                        leftBottoms = leftBottoms,
                        onePiece = onePiece,
                        rightTops = rightTops,
                        rightBottoms = rightBottoms,
                        byId = byId,
                        isEditMode = isEditMode,
                        unit = unit,
                        rowGap = rowGap,
                        itemGap = itemGap,
                        onPickItem = onPickItem,
                        onToggleLock = onToggleLock,
                        onRemove = onRemove,
                    )
                    else -> {
                        // The tall one-piece tile spans 2 units plus the gap between them.
                        val tileHeight = if (layer == Layer.OnePiece) unit * 2 + rowGap else unit
                        Row(horizontalArrangement = Arrangement.spacedBy(itemGap)) {
                            s.forEach { slot ->
                                ComposerStackedTile(
                                    slot = slot,
                                    image = slot.selectedItemId?.let { byId[it] },
                                    isEditMode = isEditMode,
                                    width = unit,
                                    height = tileHeight,
                                    onTap = { onPickItem(slot.id) },
                                    onToggleLock = { onToggleLock(slot.id) },
                                    onRemove = { onRemove(slot.id) },
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}

/**
 * The Top / OnePiece / Bottom "core band": the one-piece renders as a single full-height rectangle
 * that can be flanked by a Top/Bottom column on each side. In every flank column the Top tile is
 * pinned to the band's top edge and the Bottom tile to its bottom edge, so each shares an edge line
 * with the suit rather than stacking above/below it.
 */
@Composable
private fun ComposerCoreBand(
    leftTops: List<OutfitSlot>,
    leftBottoms: List<OutfitSlot>,
    onePiece: List<OutfitSlot>,
    rightTops: List<OutfitSlot>,
    rightBottoms: List<OutfitSlot>,
    byId: Map<String, DriveImage>,
    isEditMode: Boolean,
    unit: Dp,
    rowGap: Dp,
    itemGap: Dp,
    onPickItem: (slotId: String) -> Unit,
    onToggleLock: (slotId: String) -> Unit,
    onRemove: (slotId: String) -> Unit,
) {
    val bandHeight = unit * 2 + rowGap
    Row(
        horizontalArrangement = Arrangement.spacedBy(itemGap),
        verticalAlignment = Alignment.Top,
    ) {
        if (leftTops.isNotEmpty() || leftBottoms.isNotEmpty()) {
            ComposerFlankColumn(
                tops = leftTops, bottoms = leftBottoms, bandHeight = bandHeight, byId = byId,
                isEditMode = isEditMode, unit = unit, itemGap = itemGap,
                onPickItem = onPickItem, onToggleLock = onToggleLock, onRemove = onRemove,
            )
        }
        // The full suit — one tall rectangle spanning both the top and bottom edges.
        onePiece.forEach { slot ->
            ComposerStackedTile(
                slot = slot,
                image = slot.selectedItemId?.let { byId[it] },
                isEditMode = isEditMode,
                width = unit,
                height = bandHeight,
                onTap = { onPickItem(slot.id) },
                onToggleLock = { onToggleLock(slot.id) },
                onRemove = { onRemove(slot.id) },
            )
        }
        if (rightTops.isNotEmpty() || rightBottoms.isNotEmpty()) {
            ComposerFlankColumn(
                tops = rightTops, bottoms = rightBottoms, bandHeight = bandHeight, byId = byId,
                isEditMode = isEditMode, unit = unit, itemGap = itemGap,
                onPickItem = onPickItem, onToggleLock = onToggleLock, onRemove = onRemove,
            )
        }
    }
}

/**
 * One side of the core band: a column exactly as tall as the suit, with [tops] flush to the top
 * edge and [bottoms] flush to the bottom edge (a weight spacer pushes them apart).
 */
@Composable
private fun ComposerFlankColumn(
    tops: List<OutfitSlot>,
    bottoms: List<OutfitSlot>,
    bandHeight: Dp,
    byId: Map<String, DriveImage>,
    isEditMode: Boolean,
    unit: Dp,
    itemGap: Dp,
    onPickItem: (slotId: String) -> Unit,
    onToggleLock: (slotId: String) -> Unit,
    onRemove: (slotId: String) -> Unit,
) {
    Column(modifier = Modifier.height(bandHeight)) {
        if (tops.isNotEmpty()) {
            Row(horizontalArrangement = Arrangement.spacedBy(itemGap)) {
                tops.forEach { slot ->
                    ComposerStackedTile(
                        slot = slot,
                        image = slot.selectedItemId?.let { byId[it] },
                        isEditMode = isEditMode,
                        width = unit,
                        height = unit,
                        onTap = { onPickItem(slot.id) },
                        onToggleLock = { onToggleLock(slot.id) },
                        onRemove = { onRemove(slot.id) },
                    )
                }
            }
        }
        Spacer(Modifier.weight(1f))
        if (bottoms.isNotEmpty()) {
            Row(horizontalArrangement = Arrangement.spacedBy(itemGap)) {
                bottoms.forEach { slot ->
                    ComposerStackedTile(
                        slot = slot,
                        image = slot.selectedItemId?.let { byId[it] },
                        isEditMode = isEditMode,
                        width = unit,
                        height = unit,
                        onTap = { onPickItem(slot.id) },
                        onToggleLock = { onToggleLock(slot.id) },
                        onRemove = { onRemove(slot.id) },
                    )
                }
            }
        }
    }
}

@Composable
private fun ComposerStackedTile(
    slot: OutfitSlot,
    image: DriveImage?,
    isEditMode: Boolean,
    width: Dp,
    height: Dp,
    onTap: () -> Unit,
    onToggleLock: () -> Unit,
    onRemove: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val ctx = LocalContext.current
    // Icons/badges scale off the (uniform) width so they look consistent whether the tile is a
    // normal square or the taller one-piece rectangle.
    val unit = width
    Box(
        modifier = modifier
            .width(width)
            .height(height)
            .then(if (isEditMode) Modifier.clickable(onClick = onTap) else Modifier),
    ) {
        if (image != null) {
            val model = remember(image.driveId, image.version) {
                ImageRequest.Builder(ctx)
                    .data(image.localPath)
                    .memoryCacheKey("${image.driveId}_${image.version}")
                    .build()
            }
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
        } else {
            // Empty slot silhouette (edit mode only — view mode hides empty slots).
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .dashedBorder(
                        color = MaterialTheme.colorScheme.outline.copy(alpha = 0.55f),
                        width = 1.5.dp,
                        radius = 16.dp,
                    ),
                contentAlignment = Alignment.Center,
            ) {
                Column(
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    Box(contentAlignment = Alignment.Center) {
                        Icon(
                            painter = androidx.compose.ui.res.painterResource(slot.category.iconRes),
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.45f),
                            modifier = Modifier.size(unit * 0.32f),
                        )
                        // AI sparkle badge — communicates that "Generate with AI" will fill this slot.
                        Icon(
                            Icons.Default.AutoAwesome,
                            contentDescription = stringResource(R.string.composer_empty_slot_ai_hint),
                            tint = MaterialTheme.colorScheme.primary,
                            modifier = Modifier
                                .align(Alignment.BottomEnd)
                                .offset(x = unit * 0.10f, y = unit * 0.05f)
                                .size(unit * 0.18f),
                        )
                    }
                    Text(
                        stringResource(slot.category.labelRes),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f),
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
        }

        if (isEditMode) {
            // All controls stacked on the right edge — no filled circle background. Top button
            // is delete; lock toggle (when an item is present) sits just below it.
            Column(
                modifier = Modifier
                    .align(Alignment.TopEnd)
                    .padding(2.dp),
                horizontalAlignment = Alignment.End,
                verticalArrangement = Arrangement.spacedBy(0.dp),
            ) {
                IconButton(
                    onClick = onRemove,
                    modifier = Modifier.size(24.dp),
                ) {
                    Icon(
                        Icons.Default.Close,
                        contentDescription = stringResource(R.string.outfit_slot_remove),
                        modifier = Modifier.size(18.dp),
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                if (image != null) {
                    IconButton(
                        onClick = onToggleLock,
                        modifier = Modifier.size(24.dp),
                    ) {
                        Icon(
                            if (slot.isLocked) Icons.Default.Lock else Icons.Default.LockOpen,
                            contentDescription = stringResource(
                                if (slot.isLocked) R.string.outfit_slot_unlock else R.string.outfit_slot_lock
                            ),
                            modifier = Modifier.size(18.dp),
                            tint = if (slot.isLocked) MaterialTheme.colorScheme.primary
                                   else MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            }
            // AI sparkle on filled + unlocked tiles: signals AI may replace this item on the
            // next "Generate with AI" run. Empty slots already show the badge via the
            // silhouette block above.
            if (image != null && !slot.isLocked) {
                Icon(
                    Icons.Default.AutoAwesome,
                    contentDescription = stringResource(R.string.composer_empty_slot_ai_hint),
                    tint = MaterialTheme.colorScheme.primary,
                    modifier = Modifier
                        .align(Alignment.BottomEnd)
                        .padding(4.dp)
                        .size(unit * 0.18f),
                )
            }
        }
    }
}

// ─── Category picker sheet ──────────────────────────────────────────────────

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun CategoryPickerSheet(
    onSelect: (Layer) -> Unit,
    onDismiss: () -> Unit,
) {
    val parentContext = LocalContext.current
    val parentConfiguration = LocalConfiguration.current
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
    ) {
        CompositionLocalProvider(
            LocalContext provides parentContext,
            LocalConfiguration provides parentConfiguration,
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .navigationBarsPadding()
                    .padding(horizontal = 16.dp, vertical = 8.dp),
                verticalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                Text(
                    stringResource(R.string.outfit_pick_category),
                    style = MaterialTheme.typography.titleMedium,
                    modifier = Modifier.padding(bottom = 8.dp),
                )
                Layer.values().forEach { layer ->
                    TextButton(
                        onClick = { onSelect(layer) },
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        Icon(
                            painter = androidx.compose.ui.res.painterResource(layer.iconRes),
                            contentDescription = null,
                            modifier = Modifier.size(18.dp),
                        )
                        Spacer(Modifier.width(12.dp))
                        Text(
                            stringResource(layer.labelRes),
                            modifier = Modifier.weight(1f),
                        )
                    }
                }
            }
        }
    }
}

// ─── Save outfit dialog ──────────────────────────────────────────────────────

@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
internal fun SaveOutfitDialog(
    initialName: String,
    initialDescription: String,
    initialTags: List<String>,
    tagSuggestions: List<String> = emptyList(),
    parentContext: android.content.Context,
    parentConfiguration: android.content.res.Configuration,
    onConfirm: (name: String, description: String, tags: List<String>) -> Unit,
    onDismiss: () -> Unit,
) {
    var name by remember(initialName) { mutableStateOf(initialName) }
    var description by remember(initialDescription) { mutableStateOf(initialDescription) }
    var tags by remember(initialTags) { mutableStateOf(initialTags) }
    var newTagInput by remember { mutableStateOf("") }

    val locale: @Composable (@Composable () -> Unit) -> Unit = { content ->
        CompositionLocalProvider(
            LocalContext provides parentContext,
            LocalConfiguration provides parentConfiguration,
        ) { content() }
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { locale { Text(stringResource(R.string.outfit_save_dialog_title)) } },
        text = {
            locale {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedTextField(
                        value = name,
                        onValueChange = { name = it },
                        label = { Text(stringResource(R.string.outfit_save_dialog_name)) },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth(),
                    )
                    OutlinedTextField(
                        value = description,
                        onValueChange = { description = it },
                        label = { Text(stringResource(R.string.outfit_save_dialog_description)) },
                        singleLine = false,
                        minLines = 2,
                        maxLines = 4,
                        modifier = Modifier.fillMaxWidth(),
                    )
                    Text(
                        stringResource(R.string.outfit_save_dialog_tags),
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.primary,
                    )
                    OutfitTagsEditor(
                        tags = tags,
                        onAdd = { t -> if (t.isNotBlank()) tags = (tags + t).distinctBy { it.lowercase() } },
                        onRemove = { t -> tags = tags - t },
                    )
                    val unusedSuggestions = remember(tagSuggestions, tags) {
                        val have = tags.map { it.lowercase(java.util.Locale.ROOT) }.toSet()
                        tagSuggestions.filter { it.lowercase(java.util.Locale.ROOT) !in have }
                    }
                    if (unusedSuggestions.isNotEmpty()) {
                        Text(
                            stringResource(R.string.outfit_save_dialog_tag_suggestions),
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        FlowRow(
                            horizontalArrangement = Arrangement.spacedBy(6.dp),
                            verticalArrangement = Arrangement.spacedBy(4.dp),
                        ) {
                            unusedSuggestions.forEach { suggestion ->
                                InputChip(
                                    selected = false,
                                    onClick = {
                                        tags = (tags + suggestion).distinctBy { it.lowercase(java.util.Locale.ROOT) }
                                    },
                                    label = { Text(suggestion, style = MaterialTheme.typography.labelSmall) },
                                    leadingIcon = {
                                        Icon(Icons.Default.Add, contentDescription = null,
                                             modifier = Modifier.size(14.dp))
                                    },
                                )
                            }
                        }
                    }
                }
            }
        },
        confirmButton = {
            locale {
                TextButton(onClick = { onConfirm(name.trim(), description.trim(), tags) }) {
                    Text(stringResource(R.string.outfit_save_dialog_confirm))
                }
            }
        },
        dismissButton = {
            locale {
                TextButton(onClick = onDismiss) {
                    Text(stringResource(R.string.action_cancel))
                }
            }
        },
    )
}

// ─── Discard changes dialog ──────────────────────────────────────────────────

@Composable
internal fun DiscardChangesDialog(
    parentContext: android.content.Context,
    parentConfiguration: android.content.res.Configuration,
    onConfirm: () -> Unit,
    onDismiss: () -> Unit,
) {
    val locale: @Composable (@Composable () -> Unit) -> Unit = { content ->
        CompositionLocalProvider(
            LocalContext provides parentContext,
            LocalConfiguration provides parentConfiguration,
        ) { content() }
    }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { locale { Text(stringResource(R.string.outfit_discard_changes_title)) } },
        text = { locale { Text(stringResource(R.string.outfit_discard_changes_body)) } },
        confirmButton = {
            locale {
                TextButton(onClick = onConfirm) {
                    Text(stringResource(R.string.outfit_discard_changes_confirm))
                }
            }
        },
        dismissButton = {
            locale {
                TextButton(onClick = onDismiss) {
                    Text(stringResource(R.string.action_cancel))
                }
            }
        },
    )
}

// ─── Dashed border helper ───────────────────────────────────────────────────

private fun Modifier.dashedBorder(color: Color, width: Dp, radius: Dp): Modifier =
    this.then(
        Modifier.drawBehind {
            val strokePx = width.toPx()
            val radiusPx = radius.toPx()
            val pe = PathEffect.dashPathEffect(floatArrayOf(strokePx * 3, strokePx * 3), 0f)
            drawRoundRect(
                color = color,
                cornerRadius = CornerRadius(radiusPx, radiusPx),
                size = Size(size.width - strokePx, size.height - strokePx),
                topLeft = androidx.compose.ui.geometry.Offset(strokePx / 2, strokePx / 2),
                style = Stroke(width = strokePx, pathEffect = pe),
            )
        }
    )


// ─── Existing helpers (unchanged) ──────────────────────────────────────────

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun WeatherSection(
    mode: ComposerWeatherMode,
    onModeChange: (ComposerWeatherMode) -> Unit,
    autoWeather: WeatherData?,
    season: String,
    onSeason: (String) -> Unit,
    tempC: Int?,
    onTempC: (Int?) -> Unit,
    precip: String,
    onPrecip: (String) -> Unit,
) {
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        FilterChip(
            selected = mode == ComposerWeatherMode.AUTO,
            onClick = { onModeChange(ComposerWeatherMode.AUTO) },
            label = { Text(stringResource(R.string.composer_weather_auto)) },
        )
        FilterChip(
            selected = mode == ComposerWeatherMode.MANUAL,
            onClick = { onModeChange(ComposerWeatherMode.MANUAL) },
            label = { Text(stringResource(R.string.composer_weather_manual)) },
        )
    }
    if (mode == ComposerWeatherMode.AUTO) {
        val txt = autoWeather?.let {
            "${it.temperatureCelsius.toInt()}°C · ${wmoEmoji(it.weatherCode)} · ${it.cityName.ifEmpty { "—" }}"
        } ?: stringResource(R.string.composer_weather_unknown)
        Text(txt, style = MaterialTheme.typography.bodyMedium)
    } else {
        Text(stringResource(R.string.composer_weather_season), style = MaterialTheme.typography.labelMedium)
        FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            val seasons = listOf(
                "Spring" to R.string.composer_season_spring,
                "Summer" to R.string.composer_season_summer,
                "Fall" to R.string.composer_season_fall,
                "Winter" to R.string.composer_season_winter,
            )
            seasons.forEach { (value, labelRes) ->
                FilterChip(
                    selected = season == value,
                    onClick = { onSeason(if (season == value) "" else value) },
                    label = { Text(stringResource(labelRes)) },
                )
            }
        }
        Text(stringResource(R.string.composer_weather_temp), style = MaterialTheme.typography.labelMedium)
        FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            listOf(-5, 5, 15, 22, 30).forEach { t ->
                FilterChip(
                    selected = tempC == t,
                    onClick = { onTempC(if (tempC == t) null else t) },
                    label = { Text(stringResource(R.string.composer_temp_value, t)) },
                )
            }
        }
        Text(stringResource(R.string.composer_weather_precip), style = MaterialTheme.typography.labelMedium)
        FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            val precips = listOf(
                "None" to R.string.composer_precip_none,
                "Light" to R.string.composer_precip_light,
                "Heavy" to R.string.composer_precip_heavy,
            )
            precips.forEach { (value, labelRes) ->
                FilterChip(
                    selected = precip == value,
                    onClick = { onPrecip(if (precip == value) "" else value) },
                    label = { Text(stringResource(labelRes)) },
                )
            }
        }
    }
}


/**
 * Wardrobe-style slot picker. Opens as a fullscreen Dialog (above the composer) so the user
 * can browse the entire layer-relevant subset of their wardrobe with the same affordances the
 * Wardrobe screen offers: pinch-to-zoom, tag filtering, location tag, ellipsis menu, etc.
 *
 * Picks are single-select: tapping an item confirms immediately and dismisses the picker.
 * `internal` so the Try-On composer can reuse the same picker UI as the Outfit composer.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun AddItemSheet(
    allItems: List<DriveImage>,
    alreadyChosen: Set<String>,
    locations: List<com.librelookai.data.model.Location>,
    popularityMap: Map<String, Int> = emptyMap(),
    onTextFilter: (String, List<DriveImage>) -> List<DriveImage> = { _, items -> items },
    findSimilarByPhoto: (suspend (java.io.File, List<DriveImage>) -> Map<String, Float>)? = null,
    // When true, a long-press starts multi-select: tap toggles items and a FAB confirms the
    // batch. When false (default, e.g. single-slot exchange) a tap confirms one item immediately.
    allowMultiSelect: Boolean = false,
    onConfirm: (Set<String>) -> Unit,
    onDismiss: () -> Unit,
) {
    val parentContext = LocalContext.current
    val parentConfiguration = LocalConfiguration.current

    val candidates = remember(allItems, alreadyChosen) {
        allItems.filter { it.driveId !in alreadyChosen }
    }

    var selectedTags by remember { mutableStateOf(emptyMap<String, Set<String>>()) }
    var textQuery by remember { mutableStateOf("") }
    var sortBy by remember { mutableStateOf(com.librelookai.wardrobe.SortOption.DATE_DESC) }
    var filterSheetOpen by remember { mutableStateOf(false) }
    // Photo-search overlay state. capturing == true → CaptureScreen replaces the picker;
    // photoScores != null → list is filtered + sorted by similarity to the captured photo.
    var capturing by remember { mutableStateOf(false) }
    var photoScores by remember { mutableStateOf<Map<String, Float>?>(null) }
    var photoSearching by remember { mutableStateOf(false) }
    // Multi-select picks (only used when allowMultiSelect); long-press starts the mode.
    var pickedIds by remember { mutableStateOf(emptySet<String>()) }
    val selectionMode = allowMultiSelect && pickedIds.isNotEmpty()
    val photoScope = androidx.compose.runtime.rememberCoroutineScope()
    val tagCategories = remember(candidates) { candidates.tagCategories() }
    val filtered = remember(candidates, selectedTags, textQuery, photoScores) {
        val byTags = if (selectedTags.values.all { it.isEmpty() }) candidates
        else candidates.filter { image ->
            selectedTags.all { (cat, picked) ->
                picked.isEmpty() || image.tagStringsForCategory(cat).any { it in picked }
            }
        }
        val byText = if (textQuery.isBlank()) byTags else onTextFilter(textQuery, byTags)
        val scores = photoScores
        if (scores == null) byText
        else byText.filter { it.driveId in scores.keys }
    }
    val displayed = remember(filtered, sortBy, popularityMap, photoScores) {
        val scores = photoScores
        if (scores != null) {
            // Photo-search active: order by similarity score (highest first) regardless of sortBy.
            filtered.sortedByDescending { scores[it.driveId] ?: 0f }
        } else when (sortBy) {
            com.librelookai.wardrobe.SortOption.DATE_DESC  -> filtered.sortedByDescending { it.createdTimeMs }
            com.librelookai.wardrobe.SortOption.DATE_ASC   -> filtered.sortedBy { it.createdTimeMs }
            com.librelookai.wardrobe.SortOption.POPULARITY -> filtered.sortedByDescending { popularityMap[it.driveId] ?: 0 }
            com.librelookai.wardrobe.SortOption.TYPE       -> filtered.sortedBy { it.tags?.type?.lowercase() ?: "" }
            com.librelookai.wardrobe.SortOption.CATEGORY   -> filtered.sortedBy { it.tags?.category?.lowercase() ?: "" }
        }
    }
    val appliedFilterCount = selectedTags.values.sumOf { it.size } +
        (if (textQuery.isNotBlank()) 1 else 0) +
        (if (photoScores != null) 1 else 0)
    val locationLookup: (DriveImage) -> String? = { img ->
        if (locations.size > 1) locations.find { it.folderId == img.folderId }?.name else null
    }

    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(
            usePlatformDefaultWidth = false,
            dismissOnBackPress = true,
            decorFitsSystemWindows = false,
        ),
    ) {
        val dialogView = androidx.compose.ui.platform.LocalView.current
        androidx.compose.runtime.SideEffect {
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
            // Back exits multi-select before dismissing the picker.
            if (selectionMode) BackHandler { pickedIds = emptySet() }
            Surface(
                modifier = Modifier.fillMaxSize(),
                color = MaterialTheme.colorScheme.background,
            ) {
                if (capturing) {
                    // Inline camera capture for "find by photo". Keeps the user in the slot-pick
                    // flow instead of dropping them out to the wardrobe tab.
                    com.librelookai.wardrobe.CaptureScreen(
                        onPhotoTaken = { file ->
                            capturing = false
                            photoSearching = true
                            photoScope.launch {
                                val scores = runCatching { findSimilarByPhoto!!.invoke(file, candidates) }
                                    .getOrNull()
                                    ?: emptyMap()
                                photoScores = scores
                                photoSearching = false
                            }
                        },
                        onCancel = { capturing = false },
                        showCenterCrosshair = true,
                        modifier = Modifier.fillMaxSize(),
                    )
                } else {
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
                                stringResource(R.string.composer_add_items),
                                style = MaterialTheme.typography.titleMedium,
                                maxLines = 1,
                                overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis,
                                modifier = Modifier.weight(1f),
                            )
                            com.librelookai.ViewerHeaderActions(onBeforeNavigate = onDismiss)
                        }
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .background(com.librelookai.ui.theme.LocalWardrobePalette.current.surface),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            com.librelookai.wardrobe.QuickCategoryRow(
                                totalCount = candidates.size,
                                filteredCount = filtered.size,
                                appliedFilterCount = appliedFilterCount,
                                filtersEnabled = tagCategories.isNotEmpty() || candidates.isNotEmpty(),
                                onClearFilters = {
                                    selectedTags = emptyMap()
                                    textQuery = ""
                                    photoScores = null
                                },
                                onOpenFilters = { filterSheetOpen = true },
                                modifier = Modifier.weight(1f),
                            )
                            if (findSimilarByPhoto != null) {
                                IconButton(onClick = {
                                    Analytics.action("ComposerSlotPicker", "open_find_by_photo")
                                    capturing = true
                                }) {
                                    Icon(
                                        Icons.Default.ImageSearch,
                                        contentDescription = stringResource(R.string.wardrobe_search),
                                    )
                                }
                            }
                            com.librelookai.wardrobe.SortButton(
                                sortBy = sortBy,
                                onSortChanged = { sortBy = it },
                            )
                        }
                        Box(modifier = Modifier.weight(1f).fillMaxWidth()) {
                            com.librelookai.wardrobe.WardrobeZoomableItemGrid(
                                images = displayed,
                                selectedIds = if (allowMultiSelect) pickedIds else emptySet(),
                                onClick = { _, image ->
                                    if (selectionMode) {
                                        pickedIds = if (image.driveId in pickedIds) pickedIds - image.driveId
                                                    else pickedIds + image.driveId
                                    } else {
                                        onConfirm(setOf(image.driveId))
                                    }
                                },
                                onLongClick = { image ->
                                    if (allowMultiSelect) {
                                        pickedIds = if (image.driveId in pickedIds) pickedIds - image.driveId
                                                    else pickedIds + image.driveId
                                    }
                                },
                                modifier = Modifier
                                    .fillMaxSize()
                                    .padding(bottom = barInsets.calculateBottomPadding()),
                                locationLookup = locationLookup,
                            )
                            if (photoSearching) {
                                AiProcessingOverlay(modifier = Modifier.fillMaxSize())
                            }
                            // Confirm the multi-select batch.
                            if (selectionMode) {
                                ExtendedFloatingActionButton(
                                    onClick = { onConfirm(pickedIds) },
                                    icon = { Icon(Icons.Default.Check, contentDescription = null) },
                                    text = { Text("${stringResource(R.string.action_add)} (${pickedIds.size})") },
                                    modifier = Modifier
                                        .align(Alignment.BottomEnd)
                                        .padding(16.dp)
                                        .padding(bottom = barInsets.calculateBottomPadding()),
                                )
                            }
                        }
                    }
                }
            }
        }
    }

    if (filterSheetOpen) {
        CompositionLocalProvider(
            LocalContext provides parentContext,
            LocalConfiguration provides parentConfiguration,
        ) {
            com.librelookai.wardrobe.WardrobeFilterSheet(
                tagCategories = tagCategories,
                selectedTags = selectedTags,
                appliedCount = filtered.size,
                onTagsChanged = { selectedTags = it },
                textQuery = textQuery,
                onTextQueryChanged = { textQuery = it },
                onDismiss = { filterSheetOpen = false },
            )
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
internal fun OutfitTagsEditor(
    tags: List<String>,
    onAdd: (String) -> Unit,
    onRemove: (String) -> Unit,
) {
    var input by remember { mutableStateOf("") }
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        if (tags.isNotEmpty()) {
            FlowRow(
                horizontalArrangement = Arrangement.spacedBy(6.dp),
                verticalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                tags.forEach { tag ->
                    InputChip(
                        selected = true,
                        onClick = { onRemove(tag) },
                        label = { Text(tag, style = MaterialTheme.typography.labelSmall) },
                        trailingIcon = {
                            Icon(
                                Icons.Default.Close,
                                contentDescription = stringResource(R.string.outfits_tag_remove, tag),
                                modifier = Modifier.size(14.dp),
                            )
                        },
                    )
                }
            }
        }
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            OutlinedTextField(
                value = input,
                onValueChange = { input = it },
                placeholder = { Text(stringResource(R.string.outfits_tag_add_placeholder)) },
                singleLine = true,
                modifier = Modifier.weight(1f),
            )
            TextButton(
                onClick = {
                    val t = input.trim()
                    if (t.isNotEmpty()) { onAdd(t); input = "" }
                },
                enabled = input.trim().isNotEmpty(),
            ) {
                Icon(Icons.Default.Add, contentDescription = null, modifier = Modifier.size(18.dp))
                Spacer(Modifier.width(4.dp))
                Text(stringResource(R.string.outfits_tag_add))
            }
        }
    }
}

// ─── Context-strip sheets ──────────────────────────────────────────────────

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun WeatherPickerSheet(
    mode: ComposerWeatherMode,
    onModeChange: (ComposerWeatherMode) -> Unit,
    autoWeather: WeatherData?,
    season: String,
    onSeason: (String) -> Unit,
    tempC: Int?,
    onTempC: (Int?) -> Unit,
    precip: String,
    onPrecip: (String) -> Unit,
    onDismiss: () -> Unit,
) {
    val parentContext = LocalContext.current
    val parentConfiguration = LocalConfiguration.current
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
    ) {
        CompositionLocalProvider(
            LocalContext provides parentContext,
            LocalConfiguration provides parentConfiguration,
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .navigationBarsPadding()
                    .padding(horizontal = 16.dp, vertical = 8.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        stringResource(R.string.composer_weather_title),
                        style = MaterialTheme.typography.titleMedium,
                        modifier = Modifier.weight(1f),
                    )
                    TextButton(onClick = onDismiss) { Text(stringResource(R.string.composer_sheet_done)) }
                }
                WeatherSection(
                    mode = mode,
                    onModeChange = onModeChange,
                    autoWeather = autoWeather,
                    season = season,
                    onSeason = onSeason,
                    tempC = tempC,
                    onTempC = onTempC,
                    precip = precip,
                    onPrecip = onPrecip,
                )
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
internal fun ClosetPickerSheet(
    locations: List<Location>,
    selected: Set<String>,
    onToggle: (String) -> Unit,
    onDismiss: () -> Unit,
) {
    val parentContext = LocalContext.current
    val parentConfiguration = LocalConfiguration.current
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
    ) {
        CompositionLocalProvider(
            LocalContext provides parentContext,
            LocalConfiguration provides parentConfiguration,
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .navigationBarsPadding()
                    .padding(horizontal = 16.dp, vertical = 8.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        stringResource(R.string.composer_closets_title),
                        style = MaterialTheme.typography.titleMedium,
                        modifier = Modifier.weight(1f),
                    )
                    TextButton(onClick = onDismiss) { Text(stringResource(R.string.composer_sheet_done)) }
                }
                FlowRow(
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                    verticalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    FilterChip(
                        selected = selected.isEmpty(),
                        onClick = {
                            if (selected.isNotEmpty()) selected.forEach { onToggle(it) }
                        },
                        label = { Text(stringResource(R.string.composer_closets_all)) },
                    )
                    locations.forEach { loc ->
                        FilterChip(
                            selected = loc.folderId in selected,
                            onClick = { onToggle(loc.folderId) },
                            label = { Text(loc.name) },
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun ComposerSuggestionSwiper(
    index: Int,
    count: Int,
    onPrev: () -> Unit,
    onNext: () -> Unit,
    onOpenFullscreen: (() -> Unit)? = null,
) {
    Surface(
        shape = RoundedCornerShape(20.dp),
        color = MaterialTheme.colorScheme.surfaceVariant,
        modifier = Modifier.fillMaxWidth(),
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 4.dp, vertical = 2.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            IconButton(onClick = onPrev) {
                Icon(
                    Icons.AutoMirrored.Filled.KeyboardArrowLeft,
                    contentDescription = stringResource(R.string.outfits_prediction_prev),
                )
            }
            val indicatorMod = if (onOpenFullscreen != null) {
                Modifier
                    .weight(1f)
                    .clickable(onClick = onOpenFullscreen)
                    .padding(vertical = 8.dp)
            } else {
                Modifier.weight(1f)
            }
            Text(
                text = stringResource(R.string.outfits_prediction_indicator, index + 1, count),
                style = MaterialTheme.typography.labelLarge,
                modifier = indicatorMod,
                textAlign = androidx.compose.ui.text.style.TextAlign.Center,
            )
            IconButton(onClick = onNext) {
                Icon(
                    Icons.AutoMirrored.Filled.KeyboardArrowRight,
                    contentDescription = stringResource(R.string.outfits_prediction_next),
                )
            }
        }
    }
}

/**
 * Fullscreen pager preview of the AI-generated outfit suggestions. Mirrors
 * [OutfitFullScreenViewer]'s layout (header + HorizontalPager + close-X) but with a single
 * "Use this outfit" FAB instead of the wear/edit/delete dial. Returns the picked index to
 * the caller which then applies it via [OutfitsViewModel.showComposerSuggestionAt].
 */
@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
private fun ComposerSuggestionsViewer(
    suggestions: List<ComposerSuggestion>,
    initialIndex: Int,
    slots: List<OutfitSlot>,
    itemsById: Map<String, DriveImage>,
    locations: List<com.librelookai.data.model.Location>,
    onSelect: (Int) -> Unit,
    onDismiss: () -> Unit,
) {
    if (suggestions.isEmpty()) return
    val parentContext = LocalContext.current
    val parentConfiguration = LocalConfiguration.current
    val barInsets = LocalSystemBarsPadding.current
    val view = androidx.compose.ui.platform.LocalView.current
    val density = LocalDensity.current
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
    val effectiveBottom = maxOf(barInsets.calculateBottomPadding(), rootInsetBottomDp, 48.dp)

    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(
            usePlatformDefaultWidth = false,
            dismissOnBackPress = true,
            decorFitsSystemWindows = false,
        ),
    ) {
        val dialogView = androidx.compose.ui.platform.LocalView.current
        androidx.compose.runtime.SideEffect {
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
            BackHandler(onBack = onDismiss)
            val pagerState = rememberPagerState(
                initialPage = initialIndex.coerceIn(0, suggestions.lastIndex),
                pageCount = { suggestions.size },
            )

            // Build a draft Outfit per suggestion using the same locked-slot + assignment
            // resolution used by applyComposerSuggestionToSlots.
            val outfits = remember(suggestions, slots) {
                suggestions.map { sug ->
                    val itemIds = slots.mapNotNull { slot ->
                        if (slot.isLocked && slot.selectedItemId != null) slot.selectedItemId
                        else sug.slotAssignments[slot.id]
                    }
                    Outfit(
                        id = "draft-${slots.hashCode()}-${sug.hashCode()}",
                        name = sug.name,
                        description = sug.description,
                        tags = sug.tags,
                        itemIds = itemIds,
                    )
                }
            }
            val current = outfits[pagerState.currentPage]

            Surface(
                modifier = Modifier.fillMaxSize(),
                color = MaterialTheme.colorScheme.background,
            ) {
                Box(modifier = Modifier.fillMaxSize()) {
                    Column(modifier = Modifier.fillMaxSize()) {
                        Column(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(top = 8.dp, start = 56.dp, end = 56.dp, bottom = 8.dp),
                            horizontalAlignment = Alignment.CenterHorizontally,
                            verticalArrangement = Arrangement.spacedBy(4.dp),
                        ) {
                            if (current.name.isNotBlank()) {
                                Text(
                                    text = current.name,
                                    color = MaterialTheme.colorScheme.onBackground,
                                    style = MaterialTheme.typography.titleMedium,
                                    maxLines = 2,
                                    overflow = TextOverflow.Ellipsis,
                                )
                            }
                            Text(
                                text = "${pagerState.currentPage + 1} / ${outfits.size}",
                                color = MaterialTheme.colorScheme.onBackground.copy(alpha = 0.7f),
                                style = MaterialTheme.typography.labelMedium,
                            )
                            if (current.description.isNotBlank()) {
                                Text(
                                    text = current.description,
                                    color = MaterialTheme.colorScheme.onBackground.copy(alpha = 0.85f),
                                    style = MaterialTheme.typography.bodySmall,
                                    maxLines = 3,
                                    overflow = TextOverflow.Ellipsis,
                                )
                            }
                            if (current.tags.isNotEmpty()) {
                                val maxWidth = LocalConfiguration.current.screenWidthDp.dp * 0.85f
                                FlowRow(
                                    modifier = Modifier.widthIn(max = maxWidth),
                                    horizontalArrangement = Arrangement.spacedBy(4.dp, Alignment.CenterHorizontally),
                                    verticalArrangement = Arrangement.spacedBy(4.dp),
                                ) {
                                    current.tags.forEach { OutfitTagChip(it) }
                                }
                            }
                        }
                        HorizontalPager(
                            state = pagerState,
                            modifier = Modifier
                                .fillMaxWidth()
                                .weight(1f),
                        ) { page ->
                            OutfitPageBody(
                                outfit = outfits[page],
                                itemsById = itemsById,
                                locations = locations,
                                onItemClick = {},
                                // Reserve room for the FAB at the bottom-right.
                                bottomPadding = effectiveBottom + 72.dp,
                            )
                        }
                    }

                    IconButton(
                        onClick = onDismiss,
                        modifier = Modifier.align(Alignment.TopStart).padding(8.dp),
                    ) {
                        Icon(
                            Icons.Default.Close,
                            contentDescription = stringResource(R.string.action_dismiss),
                            tint = MaterialTheme.colorScheme.onBackground,
                        )
                    }

                    ExtendedFloatingActionButton(
                        onClick = { onSelect(pagerState.currentPage) },
                        modifier = Modifier
                            .align(Alignment.BottomEnd)
                            .padding(bottom = effectiveBottom)
                            .padding(16.dp),
                        containerColor = MaterialTheme.colorScheme.primary,
                        contentColor = MaterialTheme.colorScheme.onPrimary,
                        icon = { Icon(Icons.Default.Check, contentDescription = null) },
                        text = { Text(stringResource(R.string.composer_select_suggestion)) },
                    )
                }
            }
        }
    }
}
