package com.librelookai.outfit

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
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
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.librelookai.R
import com.librelookai.core.designsystem.R as DsR
import com.librelookai.data.model.Outfit
import com.librelookai.settings.ProfileViewModel
import com.librelookai.shopping.ShoppingClosetViewModel
import com.librelookai.util.AiProcessingOverlay
import com.librelookai.util.Analytics
import com.librelookai.util.LocalIsOffline
import com.librelookai.util.LocalSystemBarsPadding
import com.librelookai.wardrobe.DriveImage
import com.librelookai.wardrobe.LocationViewModel
import com.librelookai.wardrobe.WardrobeViewModel
import com.librelookai.weather.WeatherViewModel

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
    generationViewModel: OutfitGenerationViewModel,
    wardrobeViewModel: WardrobeViewModel,
    profileViewModel: ProfileViewModel,
    weatherViewModel: WeatherViewModel,
    shoppingClosetViewModel: ShoppingClosetViewModel,
    locationViewModel: LocationViewModel,
    outfitEventsViewModel: OutfitEventsViewModel,
    /** Open the item-viewer destination over this one (composer source: items track the slots). */
    onOpenItemViewer: (String) -> Unit = {},
    /** Pops the OutfitComposerRoute destination (§ 5 slice 9 — the composer is real navigation;
     *  every close path clears the VM draft via closeComposer, then pops through here). */
    onClose: () -> Unit = {},
) {
    val s by generationViewModel.state.collectAsState()
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

    LaunchedEffect(Unit) { Analytics.screen("OutfitComposer") }

    val isEditMode = s.composerMode == ComposerMode.EDIT
    val sourceFolders = s.composerSourceFolderIds
    val crossClosetImages = wardrobe.allLocationImages.ifEmpty { wardrobe.images }
    // Items the source-closet filter exposes for *adding/swapping* into slots.
    val composerImages = remember(crossClosetImages, shoppingState.items, sourceFolders) {
        val filteredWardrobe = if (sourceFolders.isEmpty()) crossClosetImages
        else crossClosetImages.filter { it.folderId in sourceFolders }
        filteredWardrobe + shoppingState.items
    }
    // Resolve slot images from EVERY known source — unfiltered, and including the active
    // closet's `images`, which holds just-uploaded items not yet present in the cross-closet
    // `allLocationImages` snapshot (only refreshed on closet-config changes / prefetch).
    // Without this, a seeded or locked slot whose item lies outside the source-closet filter
    // (or is brand-new) renders as a blank silhouette and looks "not preselected". Active-closet
    // and shopping entries are listed last so their fresher copies win on duplicate driveIds.
    val byId = remember(crossClosetImages, wardrobe.images, shoppingState.items) {
        (crossClosetImages + wardrobe.images + shoppingState.items).associateBy { it.driveId }
    }

    var showAddSlotSheet by remember { mutableStateOf(false) }
    var exchangeSlotId by remember { mutableStateOf<String?>(null) }
    var showWeatherSheet by remember { mutableStateOf(false) }
    var showClosetSheet by remember { mutableStateOf(false) }
    var showDiscardDialog by remember { mutableStateOf(false) }

    val hasChanges = s.composerSlots.map { it.selectedItemId } != s.composerInitialItemIds ||
        s.composerVibes.isNotEmpty()
    val requestClose: () -> Unit = {
        if (isEditMode && hasChanges) showDiscardDialog = true
        else {
            Analytics.action("OutfitComposer", "close")
            generationViewModel.closeComposer()
            onClose()
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

    // A one-piece no longer collapses Top/Bottom (layering is allowed), so every slot counts toward
    // completeness — an empty slot the user added must be filled or removed.
    val effectiveSlots = s.composerSlots
    val filledSlots = effectiveSlots.count { it.selectedItemId != null }

    // The destination replaces the old fullscreen Dialog: edit-mode discard-confirm still
    // guards system back, and the Surface paints full-bleed while the content Box insets
    // itself below the status bar (effectiveBottom handles the nav-bar edge).
    BackHandler(onBack = requestClose)
    Surface(
        modifier = Modifier.fillMaxSize(),
        color = MaterialTheme.colorScheme.background,
    ) {
        Box(modifier = Modifier.fillMaxSize().statusBarsPadding()) {
            Column(modifier = Modifier.fillMaxSize().imePadding()) {
                if (isEditMode) {
                    ComposerHeader(
                        filledSlots = filledSlots,
                        totalSlots = effectiveSlots.size,
                        onClose = requestClose,
                        onOpenFullscreen = {
                            generationViewModel.setComposerMode(ComposerMode.VIEW)
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
                        onToggleLock = { slotId -> generationViewModel.toggleSlotLock(slotId) },
                        onRemove = { slotId -> generationViewModel.removeSlot(slotId) },
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
                            onItemClick = { onOpenItemViewer(it.driveId) },
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
                                    generationViewModel.showComposerSuggestionAt(target)
                                },
                                onNext = {
                                    val n = s.composerSuggestions.size
                                    val target = (s.composerSuggestionIndex + 1) % n
                                    Analytics.action("OutfitComposer", "suggestion_next")
                                    generationViewModel.showComposerSuggestionAt(target)
                                },
                                onOpenFullscreen = {
                                    Analytics.action("OutfitComposer", "suggestions_viewer_reopen")
                                    generationViewModel.openComposerSuggestionsViewer()
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
                    // Exact BYOK cost for the composer suggestion, off the main thread.
                    // Keys mirror every prompt-affecting input of buildComposerPrompt so the
                    // badge refreshes on any Tune-AI pill tap (Considers / Expert-tags like
                    // "only consider color" / weather mode all change the payload size).
                    val composerAiTokens by androidx.compose.runtime.produceState<com.librelookai.gemini.CostTokens?>(
                        initialValue = null,
                        crossClosetImages,
                        s.composerSlots,
                        s.composerSuggestionCount,
                        s.composerVibes,
                        profile.preferences,
                        weather.data,
                        s.composerConsiderationsOverride,
                        s.composerWeatherMode,
                        s.composerManualSeason,
                        s.composerManualTempC,
                        s.composerManualPrecip,
                        s.composerTripContext,
                        s.wearHistory,
                        s.outfits.filter { it.loved }.map { it.id },
                    ) {
                        value = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.Default) {
                            generationViewModel.estimateComposerTokens(
                                prefs = profile.preferences,
                                weather = weather.data,
                                images = crossClosetImages,
                            )
                        }
                    }
                    ComposerEditBottomBar(
                        saveEnabled = effectiveSlots.isNotEmpty() &&
                            effectiveSlots.all { it.selectedItemId != null },
                        aiEnabled = aiCanGenerate,
                        isOffline = isOffline,
                        onGenerateWithAi = {
                            Analytics.action("OutfitComposer", "generate_with_ai")
                            generationViewModel.openPredictionSetup(
                                defaultSourceFolderId = null,
                                source = PredictionSetupSource.COMPOSER,
                            )
                        },
                        onSave = {
                            Analytics.action("OutfitComposer", "save")
                            generationViewModel.prepareSave()
                        },
                        bottomPadding = effectiveBottom,
                        aiTokens = composerAiTokens,
                    )
                }
            }

            if (!isEditMode) {
                // Fullscreen view: minimal close X (top-left) to return to edit mode.
                IconButton(
                    onClick = { generationViewModel.setComposerMode(ComposerMode.EDIT) },
                    modifier = Modifier
                        .align(Alignment.TopStart)
                        .padding(8.dp),
                ) {
                    Icon(
                        Icons.Default.Close,
                        contentDescription = stringResource(DsR.string.action_close),
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

            // While the finished outfit is being written to Drive: a blocking scrim with a
            // spinner so the Save → list-reappears gap reads as "saving", not a frozen UI.
            if (s.isComposerSaving) {
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .background(Color.Black.copy(alpha = 0.55f))
                        .clickable(
                            interactionSource = remember { MutableInteractionSource() },
                            indication = null,
                        ) {},
                    contentAlignment = Alignment.Center,
                ) {
                    Surface(
                        shape = MaterialTheme.shapes.large,
                        color = MaterialTheme.colorScheme.surface,
                        tonalElevation = 6.dp,
                    ) {
                        Column(
                            modifier = Modifier.padding(28.dp),
                            horizontalAlignment = Alignment.CenterHorizontally,
                            verticalArrangement = Arrangement.spacedBy(14.dp),
                        ) {
                            CircularProgressIndicator()
                            Text(
                                stringResource(R.string.outfit_saving),
                                style = MaterialTheme.typography.bodyMedium,
                            )
                        }
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
                generationViewModel.closeComposer()
                onClose()
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
                generationViewModel.dismissSaveDialog()
                // A successful save closes the composer (commitOutfit clears the draft) —
                // pop the destination; a failed save leaves it up with the error state.
                generationViewModel.commitOutfit(name, description, tags) { ok ->
                    if (ok) onClose()
                }
            },
            onDismiss = { generationViewModel.dismissSaveDialog() },
        )
    }

    if (s.composerSuggestionsViewerOpen && s.composerSuggestions.size > 1) {
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
                generationViewModel.commitComposerSuggestion(index)
            },
            onDismiss = { generationViewModel.closeComposerSuggestionsViewer() },
        )
    }

    exchangeSlotId?.let { slotId ->
        val slot = s.composerSlots.find { it.id == slotId }
        if (slot != null) {
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
                    picked.firstOrNull()?.let { generationViewModel.setSlotItem(slotId, it) }
                    exchangeSlotId = null
                },
                onDismiss = { exchangeSlotId = null },
            )
        }
    }

    if (showAddSlotSheet) {
        CategoryPickerSheet(
            onSelect = { layer ->
                generationViewModel.addSlot(layer)
                showAddSlotSheet = false
            },
            onDismiss = { showAddSlotSheet = false },
        )
    }

    if (showWeatherSheet) {
        WeatherPickerSheet(
            mode = s.composerWeatherMode,
            onModeChange = { generationViewModel.setComposerWeatherMode(it) },
            autoWeather = weather.data,
            season = s.composerManualSeason,
            onSeason = { generationViewModel.setComposerManualSeason(it) },
            tempC = s.composerManualTempC,
            onTempC = { generationViewModel.setComposerManualTempC(it) },
            precip = s.composerManualPrecip,
            onPrecip = { generationViewModel.setComposerManualPrecip(it) },
            onDismiss = { showWeatherSheet = false },
        )
    }

    if (showClosetSheet) {
        ClosetPickerSheet(
            locations = locationState.locations,
            selected = sourceFolders,
            onToggle = { generationViewModel.toggleComposerSourceFolder(it) },
            onDismiss = { showClosetSheet = false },
        )
    }
}

// ─── Header ─────────────────────────────────────────────────────────────────

