package com.librelookai.outfit

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Style
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Snackbar
import androidx.compose.material3.Tab
import androidx.compose.material3.TabRow
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import com.librelookai.AppScreenHeader
import com.librelookai.LocationButton
import com.librelookai.feature.outfit.R
import com.librelookai.core.designsystem.R as DsR
import com.librelookai.data.model.Location
import com.librelookai.data.model.Outfit
import com.librelookai.settings.UserPreferences
import com.librelookai.data.model.WearSource
import com.librelookai.wardrobe.DriveImage
import com.librelookai.wardrobe.TagCategory
import com.librelookai.wardrobe.displayLabel
import com.librelookai.wardrobe.tagCategories
import com.librelookai.weather.WeatherViewModel

internal enum class OutfitSortOption {
    DATE_DESC, DATE_ASC, POPULARITY, NAME_AZ, NAME_ZA, ITEM_COUNT
}

@Composable
internal fun OutfitSortOption.displayLabel(): String = when (this) {
    OutfitSortOption.DATE_DESC  -> stringResource(DsR.string.outfits_sort_newest)
    OutfitSortOption.DATE_ASC   -> stringResource(DsR.string.outfits_sort_oldest)
    OutfitSortOption.POPULARITY -> stringResource(R.string.outfits_sort_most_worn)
    OutfitSortOption.NAME_AZ    -> stringResource(DsR.string.outfits_sort_name_az)
    OutfitSortOption.NAME_ZA    -> stringResource(DsR.string.outfits_sort_name_za)
    OutfitSortOption.ITEM_COUNT -> stringResource(DsR.string.outfits_sort_most_items)
}


internal fun List<Outfit>.outfitTagCategories(itemsById: Map<String, DriveImage>): List<TagCategory> {
    val allImages = flatMap { style -> style.itemIds.mapNotNull { itemsById[it] } }
    return allImages.tagCategories()
}

/**
 * Universe of items used to resolve every outfit's thumbnails. Mirrors the composer's slot lookup
 * (see OutfitComposerScreen `byId`): union EVERY known source rather than a single disk-cache read,
 * which omits just-uploaded items and any item a given snapshot hasn't recached yet. Resolving a
 * card against a too-narrow map silently drops thumbnails via `mapNotNull` — the bug a tester hit
 * where freshly-created (and some existing) outfits showed fewer items than they actually contain.
 *
 * [allLocationImages] spans all configured closets + shopping; [wardrobeImages] is the OutfitsVM's
 * own per-folder cache read; [activeImages] is the active closet's freshest in-memory list. The
 * active list is applied LAST so its just-uploaded entries and fresher copies win on duplicate
 * driveIds.
 */
fun outfitItemPool(
    allLocationImages: List<DriveImage>,
    wardrobeImages: List<DriveImage>,
    activeImages: List<DriveImage>,
): List<DriveImage> =
    (allLocationImages + wardrobeImages + activeImages)
        .associateBy { it.driveId }
        .values.toList()

@OptIn(ExperimentalMaterial3Api::class, ExperimentalFoundationApi::class)
@Composable
fun OutfitsScreen(
    outfitsViewModel: OutfitsViewModel = viewModel(),
    generationViewModel: OutfitGenerationViewModel = viewModel(),
    outfitEventsViewModel: OutfitEventsViewModel = viewModel(),
    weatherViewModel: WeatherViewModel = viewModel(),
    // Wardrobe / location / profile / trips data threaded from the shell (feature modules never
    // import another feature's VM — § 1 slice 6 narrowing precedent).
    wardrobeImages: List<DriveImage> = emptyList(),
    allLocationImages: List<DriveImage> = emptyList(),
    wardrobeIsSyncing: Boolean = false,
    wardrobeIsLoading: Boolean = false,
    preferences: UserPreferences = UserPreferences(),
    locations: List<Location> = emptyList(),
    activeLocationId: String = "",
    activeFolderId: String? = null,
    onSetActiveLocation: (String) -> Unit = {},
    /** tripId → trip name, used to label travel outfits with their originating trip. */
    tripNamesById: Map<String, String> = emptyMap(),
    /** Fuzzy text search over the outfit item pool (wardrobe VM in the shell). */
    onFuzzyTextFilter: (String, List<DriveImage>) -> List<DriveImage> = { _, _ -> emptyList() },
    onTryOnStyle: (Outfit) -> Unit = {},
    /** Navigates to the composer destination — callers seed the generation VM first (§ 5 slice 9). */
    onOpenComposer: () -> Unit = {},
    canTryOn: Boolean = false,
    onSettingsClick: () -> Unit = {},
    navResetTick: Int = 0,
    /** Opens the fullscreen viewer destination over the list's filtered outfits. */
    onOpenOutfitViewer: (outfitIds: List<String>, initialOutfitId: String) -> Unit = { _, _ -> },
    /** Opens the fullscreen viewer destination over the current prediction suggestions. */
    onOpenPredictionViewer: () -> Unit = {},
    modifier: Modifier = Modifier,
) {
    val outfitsState  by outfitsViewModel.state.collectAsState()
    val generationState by generationViewModel.state.collectAsState()
    val weatherState by weatherViewModel.state.collectAsState()
    val outfitEventsState by outfitEventsViewModel.state.collectAsState()

    // (No wardrobe-image refresh needed: OutfitsUiState.wardrobeImages is store-derived and
    // follows the wardrobe Drive sync via Room invalidation — refactor § 5 slice 4b.)

    // styleId → number of calendar wear events
    val wearCounts = remember(outfitEventsState.events) {
        outfitEventsState.events.groupingBy { it.outfitId }.eachCount()
    }

    // Universe of items used to resolve every outfit's thumbnails — see [outfitItemPool].
    val outfitItems = remember(
        allLocationImages, outfitsState.wardrobeImages, wardrobeImages,
    ) {
        outfitItemPool(
            allLocationImages = allLocationImages,
            wardrobeImages = outfitsState.wardrobeImages,
            activeImages = wardrobeImages,
        )
    }
    val outfitImagesById = remember(outfitItems) { outfitItems.associateBy { it.driveId } }
    val outfitsById = remember(outfitsState.outfits) { outfitsState.outfits.associateBy { it.id } }
    // Log a calendar wear, capturing today's weather + a tag snapshot as a taste signal.
    val logWear: (Outfit, WearSource) -> Unit = { outfit, source ->
        outfitEventsViewModel.recordOutfit(outfit, outfitImagesById, source, weatherState.data)
    }
    val logWearById: (String, WearSource) -> Unit = { id, source ->
        outfitsById[id]?.let { logWear(it, source) }
    }
    val toggleLovedById: (String) -> Unit = { id ->
        outfitsById[id]?.let { outfitsViewModel.setOutfitLoved(id, !it.loved) }
    }

    // Outfits referencing wardrobe items that no longer exist in ANY closet. Detection runs
    // against the cross-closet snapshot (allLocationImages, includes every closet + shopping)
    // so a single-closet view doesn't flag items that merely live in another closet. These
    // outfits are otherwise hidden by the list's "all items loaded" gate, so the banner is the
    // only way to discover and clean them up. Only trust the result once the wardrobe has synced.
    val brokenOutfits = remember(outfitsState.outfits, allLocationImages, wardrobeImages, wardrobeIsSyncing) {
        if (wardrobeIsSyncing) return@remember emptyList<Outfit>()
        val knownNames = (allLocationImages.asSequence() + wardrobeImages.asSequence())
            .map { it.name }.toHashSet()
        if (knownNames.isEmpty()) emptyList()
        else outfitsState.outfits.filter { o ->
            o.itemNames.isNotEmpty() && o.itemNames.any { it !in knownNames }
        }
    }

    // Sub-tabs: outfit list, wear calendar, and most-worn stats. Calendar + Stats were the former
    // Insights "Calendar" / "Calendar Stats" tabs — they're outfit/wear-centric, so they live here.
    var outfitsTab by rememberSaveable { mutableIntStateOf(0) }
    LaunchedEffect(navResetTick) { outfitsTab = 0 }
    // A pending calendar-wear request pulls the screen to the Calendar sub-tab, wherever it was
    // filed from (list card, viewer destination, AI suggestion). The calendar resolves and
    // consumes it (pick mode).
    LaunchedEffect(outfitsState.pendingCalendarWearId) {
        if (outfitsState.pendingCalendarWearId != null) outfitsTab = 1
    }

    Box(modifier = modifier.fillMaxSize()) {
        Column(Modifier.fillMaxSize()) {
            AppScreenHeader(
                title = stringResource(DsR.string.nav_styles),
                leadingIcon = Icons.Default.Style,
                trailingContent = {
                    LocationButton(
                        locations = locations,
                        activeLocationId = activeLocationId,
                        onSetActiveLocation = onSetActiveLocation,
                    )
                },
                onSettingsClick = onSettingsClick,
            )
            TabRow(selectedTabIndex = outfitsTab) {
                Tab(
                    selected = outfitsTab == 0,
                    onClick = { outfitsTab = 0 },
                    text = { Text(stringResource(DsR.string.nav_styles)) },
                )
                Tab(
                    selected = outfitsTab == 1,
                    onClick = { outfitsTab = 1 },
                    text = { Text(stringResource(R.string.insights_tab_calendar)) },
                )
                Tab(
                    selected = outfitsTab == 2,
                    onClick = { outfitsTab = 2 },
                    text = { Text(stringResource(R.string.insights_tab_calendar_stats)) },
                )
            }
            when (outfitsTab) {
                0 -> OutfitListScreen(
                    styles = outfitsState.outfits,
                    items = outfitItems,
                    wearCounts = wearCounts,
                    isLoading = outfitsState.isLoading || wardrobeIsLoading,
                    isPredicting = generationState.isPredicting,
                    locations = locations,
                    activeLocationId = activeLocationId,
                    tripNamesById = tripNamesById,
                    predictionError = generationState.predictionError,
                    selectedOutfitIds = outfitsState.selectedOutfitIds,
                    onOpenCreateComposer = {
                        generationViewModel.openComposer(
                            seedItemIds = emptySet(),
                            images      = wardrobeImages,
                            prefs       = preferences,
                            // Default to the closet the user is currently viewing (null = All).
                            defaultSourceFolderId = activeFolderId,
                        )
                        onOpenComposer()
                    },
                    onSuggestExisting = {
                        generationViewModel.openPredictionSetup(
                            defaultSourceFolderId = activeFolderId,
                        )
                    },
                    onEditOutfit = { style ->
                        generationViewModel.startEditing(style, wardrobeImages, preferences)
                        onOpenComposer()
                    },
                    onDeleteOutfit = outfitsViewModel::deleteOutfit,
                    onWearOutfit = { id ->
                        // Switch to the Calendar sub-tab and let the user tap the wear day there,
                        // in context of their previously-worn outfits.
                        outfitsViewModel.requestCalendarWear(id, WearSource.MANUAL)
                        outfitsTab = 1
                    },
                    onToggleLovedOutfit = toggleLovedById,
                    onOpenViewer = onOpenOutfitViewer,
                    onToggleOutfitSelection = outfitsViewModel::toggleOutfitSelection,
                    onSelectAllOutfits = outfitsViewModel::selectAllOutfits,
                    onClearOutfitSelection = outfitsViewModel::clearOutfitSelection,
                    onDeleteSelectedStyles = outfitsViewModel::deleteSelectedOutfits,
                    onCombineSelectedStyles = {
                        val selected = outfitsState.outfits.filter { it.id in outfitsState.selectedOutfitIds }
                        // The seed helper no-ops below 2 outfits — don't navigate to an unseeded composer.
                        if (selected.size >= 2) {
                            generationViewModel.openComposerFromSelectedOutfits(
                                selected = selected,
                                images = wardrobeImages,
                                prefs  = preferences,
                            )
                            outfitsViewModel.clearOutfitSelection()
                            onOpenComposer()
                        }
                    },
                    onClearPredictionError = generationViewModel::clearPrediction,
                    scrollEvents = outfitsViewModel.events,
                    onTryOnStyle = onTryOnStyle,
                    canTryOn = canTryOn,
                    brokenOutfits = brokenOutfits,
                    onDeleteBrokenOutfits = {
                        outfitsViewModel.deleteOutfitsByIds(brokenOutfits.map { it.id })
                    },
                    onFuzzyTextFilter = onFuzzyTextFilter,
                )
                1 -> OutfitCalendarTab(
                    outfitEventsViewModel = outfitEventsViewModel,
                    stylesViewModel = outfitsViewModel,
                    wardrobeImages = wardrobeImages,
                    onEditOutfit = { style ->
                        generationViewModel.startEditing(style, wardrobeImages, preferences)
                        onOpenComposer()
                    },
                )
                2 -> OutfitWearStatsTab(
                    outfitEventsViewModel = outfitEventsViewModel,
                    stylesViewModel = outfitsViewModel,
                    wardrobeImages = wardrobeImages,
                )
            }
        }

        // Tag-edit dialog launched by tapping the tags row in the outfit detail viewer.
        generationState.tagEditingOutfitId?.let { editId ->
            val target = outfitsState.outfits.find { it.id == editId }
            if (target != null) {
                EditOutfitTagsDialog(
                    initialTags = target.tags,
                    onDismiss = generationViewModel::closeOutfitTagsEditor,
                    onSave = { newTags -> generationViewModel.setOutfitTags(editId, newTags) },
                )
            }
        }

        // AI tag-suggestion dialog launched from the outfit detail viewer.
        generationState.tagSuggestion?.let { sugg ->
            SuggestTagsDialog(
                state = sugg,
                onDismiss = generationViewModel::dismissTagSuggestions,
                onApply = { selected -> generationViewModel.applyTagSuggestions(sugg.outfitId, selected) },
            )
        }

        // Existing-outfit suggestion: open the picks in the fullscreen viewer destination,
        // swipe to flip between Gemini's ranked picks. The destination resolves the suggestion
        // list live and clears the prediction on every close path — so this trigger (dead while
        // the destination overlays Home) can't re-fire when the user comes back.
        val predictedOutfits = remember(generationState.predictionSuggestions, outfitsState.outfits) {
            generationState.predictionSuggestions.mapNotNull { p ->
                outfitsState.outfits.find { it.id == p.outfitId }
            }
        }
        val showPrediction = predictedOutfits.isNotEmpty()
        LaunchedEffect(showPrediction) {
            if (showPrediction) onOpenPredictionViewer()
        }

        // After saving a style, offer to wear it immediately
        outfitsState.pendingWearOutfitId?.let { styleId ->
            Snackbar(
                modifier = Modifier
                    .align(Alignment.TopCenter)
                    .statusBarsPadding()
                    .padding(start = 8.dp, end = 8.dp, top = 64.dp),
                action = {
                    TextButton(onClick = {
                        logWearById(styleId, WearSource.MANUAL)
                        outfitsViewModel.clearPendingWear()
                    }) {
                        Text(stringResource(DsR.string.outfits_wear_today))
                    }
                },
                dismissAction = {
                    TextButton(onClick = outfitsViewModel::clearPendingWear) {
                        Text(stringResource(DsR.string.action_dismiss))
                    }
                },
            ) {
                Text(stringResource(R.string.outfits_saved_wear_today))
            }
        }

        // PredictionSetupDialog is hosted in MainActivity so it appears regardless of which
        // tab is active when openPredictionSetup() fires (composer can launch it from Wardrobe).
    }
}

// ---------- Outfit list ----------

