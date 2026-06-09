package com.librelookai.outfit

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.grid.itemsIndexed
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Sort
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.AutoAwesome
import androidx.compose.material.icons.filled.AutoFixHigh
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material.icons.filled.Warning
import com.librelookai.ui.components.AppFab
import com.librelookai.ui.components.SelectionAction
import com.librelookai.ui.components.SelectionActionBar
import com.librelookai.ui.components.rememberFabExpanded
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Snackbar
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import com.librelookai.R
import com.librelookai.data.model.Location
import com.librelookai.data.model.Outfit
import com.librelookai.util.AiProcessingOverlay
import com.librelookai.util.Analytics
import com.librelookai.util.LocalIsOffline
import com.librelookai.util.scrollbar
import com.librelookai.wardrobe.DriveImage
import com.librelookai.wardrobe.LocationViewModel
import com.librelookai.wardrobe.QuickCategoryRow
import com.librelookai.wardrobe.WardrobeFilterSheet
import com.librelookai.wardrobe.WardrobeViewModel
import com.librelookai.wardrobe.displayLabel
import com.librelookai.wardrobe.tagCategories
import com.librelookai.wardrobe.tagStringsForCategory
import com.librelookai.wardrobe.fuzzyFilterByText

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun OutfitListScreen(
    styles: List<Outfit>,
    items: List<DriveImage>,
    wearCounts: Map<String, Int> = emptyMap(),
    isLoading: Boolean,
    isPredicting: Boolean,
    predictionError: String?,
    selectedOutfitIds: Set<String> = emptySet(),
    locations: List<Location> = emptyList(),
    activeLocationId: String = "",
    tripNamesById: Map<String, String> = emptyMap(),
    onOpenCreateComposer: () -> Unit,
    onSuggestExisting: () -> Unit = {},
    onEditOutfit: (Outfit) -> Unit,
    onDeleteOutfit: (String) -> Unit,
    onWearOutfit: (String) -> Unit,
    onToggleLovedOutfit: (String) -> Unit = {},
    onSuggestOutfitTags: (Outfit) -> Unit = {},
    onEditOutfitTags: (Outfit) -> Unit = {},
    onToggleOutfitSelection: (String) -> Unit = {},
    onSelectAllOutfits: (List<String>) -> Unit = {},
    onClearOutfitSelection: () -> Unit = {},
    onDeleteSelectedStyles: () -> Unit = {},
    onCombineSelectedStyles: () -> Unit = {},
    onClearPredictionError: () -> Unit,
    pendingScrollOutfitId: String? = null,
    onConsumePendingScrollOutfit: () -> Unit = {},
    onTryOnStyle: (Outfit) -> Unit = {},
    canTryOn: Boolean = false,
    brokenOutfits: List<Outfit> = emptyList(),
    onDeleteBrokenOutfits: () -> Unit = {},
    modifier: Modifier = Modifier,
    wardrobeViewModel: WardrobeViewModel,
) {
    val isOffline = LocalIsOffline.current
    // itemsById: ALL locations — used to resolve item IDs to images for card display and tag filters.
    val itemsById = remember(items) { items.associateBy { it.driveId } }

    // The active closet's folder id (null = All locations). Used for closet-scoped filtering.
    val locationFolderId = remember(activeLocationId, locations) {
        if (activeLocationId != LocationViewModel.ALL_LOCATIONS_ID && activeLocationId.isNotEmpty())
            locations.find { it.folderId == activeLocationId }?.folderId else null
    }
    // Resolve an item's closet by name (legacy outfits store itemNames, not itemIds). Spans every
    // loaded closet so we can tell which closet an outfit's items belong to.
    val folderByName = remember(items) {
        items.associate { com.librelookai.util.ImageEncoding.itemMatchKey(it.name) to it.folderId }
    }

    var selectedTags by remember { mutableStateOf(emptyMap<String, Set<String>>()) }
    var sortBy by remember { mutableStateOf(OutfitSortOption.DATE_DESC) }
    var filterSheetOpen by remember { mutableStateOf(false) }
    var textQuery by remember { mutableStateOf("") }
    val appliedFilterCount = selectedTags.values.sumOf { it.size } + (if (textQuery.isNotBlank()) 1 else 0)

    val tagCategories = remember(styles, itemsById) { styles.outfitTagCategories(itemsById) }
    // Flattened wardrobe items referenced by any outfit — drives QuickCategoryRow counts.
    val outfitItemImages = remember(styles, itemsById) {
        styles.flatMap { it.itemIds.mapNotNull { id -> itemsById[id] } }
    }

    // Closet filter only — an outfit is shown unless we can positively resolve its items AND none
    // of them live in the active closet. We deliberately do NOT hide outfits whose item images
    // simply aren't downloaded yet: after a reinstall the cross-closet image cache is empty/partial
    // until every closet finishes syncing, and the old "all items loaded" gate made every outfit
    // disappear in the meantime. OutfitCard already degrades gracefully (placeholders / "missing
    // items"), so showing an outfit before its thumbnails load is safe.
    val filteredStyles = remember(styles, selectedTags, itemsById, folderByName, locationFolderId, textQuery) {
        val activeFilters = selectedTags.filter { (_, tags) -> tags.isNotEmpty() }
        val q = textQuery.trim()
        val qLower = q.lowercase()
        val itemsMatchingText: Set<String>? = if (q.isBlank()) null else {
            wardrobeViewModel.fuzzyFilterByText(q, items).map { it.driveId }.toSet()
        }
        styles.filter { style ->
            if (locationFolderId != null) {
                val resolvedFolders = if (style.itemNames.isNotEmpty())
                    style.itemNames.mapNotNull { folderByName[com.librelookai.util.ImageEncoding.itemMatchKey(it)] }
                else
                    style.itemIds.mapNotNull { id -> itemsById[id]?.folderId }
                // Hide only when items resolve and all of them belong to other closets.
                val inActiveCloset = resolvedFolders.isEmpty() || resolvedFolders.any { it == locationFolderId }
                if (!inActiveCloset) return@filter false
            }
            val tagsOk = activeFilters.isEmpty() || activeFilters.all { (categoryLabel, catTags) ->
                style.itemIds.any { id ->
                    val img = itemsById[id] ?: return@any false
                    catTags.any { it in img.tagStringsForCategory(categoryLabel) }
                }
            }
            if (!tagsOk) return@filter false
            if (itemsMatchingText == null) return@filter true
            style.name.lowercase().contains(qLower) ||
                style.description.lowercase().contains(qLower) ||
                style.tags.any { it.lowercase().contains(qLower) } ||
                style.itemIds.any { it in itemsMatchingText }
        }
    }

    val displayedStyles = remember(filteredStyles, sortBy, wearCounts) {
        when (sortBy) {
            OutfitSortOption.DATE_DESC  -> filteredStyles
            OutfitSortOption.DATE_ASC   -> filteredStyles.reversed()
            OutfitSortOption.POPULARITY -> filteredStyles.sortedByDescending { wearCounts[it.id] ?: 0 }
            OutfitSortOption.NAME_AZ    -> filteredStyles.sortedBy { it.name.lowercase() }
            OutfitSortOption.NAME_ZA    -> filteredStyles.sortedByDescending { it.name.lowercase() }
            OutfitSortOption.ITEM_COUNT -> filteredStyles.sortedByDescending { it.itemIds.size }
        }
    }

    val isSelectionMode = selectedOutfitIds.isNotEmpty()
    if (isSelectionMode) BackHandler(onBack = onClearOutfitSelection)

    // Hoisted so the shared create FAB can collapse on scroll (also used by the LazyColumn below).
    val outfitsListState = rememberLazyListState()

    var fullscreenStyleId by rememberSaveable { mutableStateOf<String?>(null) }
    var showDeleteDialog by remember { mutableStateOf(false) }
    // Dismissible "broken outfits" banner state + its confirm dialog.
    var brokenBannerDismissed by rememberSaveable { mutableStateOf(false) }
    var showBrokenDeleteDialog by remember { mutableStateOf(false) }

    if (showBrokenDeleteDialog) {
        // Capture parent context/config OUTSIDE the dialog so stringResource honours the
        // in-app language toggle inside the dialog window. (See CLAUDE.md → Dialog quirks.)
        val parentContext = LocalContext.current
        val parentConfiguration = LocalConfiguration.current
        val unnamed = stringResource(R.string.outfits_unnamed)
        val shown = remember(brokenOutfits) { brokenOutfits.take(8) }
        AlertDialog(
            onDismissRequest = { showBrokenDeleteDialog = false },
            title = {
                CompositionLocalProvider(
                    LocalContext provides parentContext,
                    LocalConfiguration provides parentConfiguration,
                ) { Text(stringResource(R.string.outfits_broken_delete_title)) }
            },
            text = {
                CompositionLocalProvider(
                    LocalContext provides parentContext,
                    LocalConfiguration provides parentConfiguration,
                ) {
                    Column(
                        modifier = Modifier.heightIn(max = 320.dp).verticalScroll(rememberScrollState()),
                        verticalArrangement = Arrangement.spacedBy(4.dp),
                    ) {
                        Text(stringResource(R.string.outfits_broken_delete_text, brokenOutfits.size))
                        Spacer(Modifier.height(4.dp))
                        shown.forEach { o ->
                            Text(
                                "•  " + o.name.ifBlank { unnamed },
                                style = MaterialTheme.typography.bodyMedium,
                            )
                        }
                        if (brokenOutfits.size > shown.size) {
                            Text(
                                stringResource(R.string.outfits_broken_more, brokenOutfits.size - shown.size),
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                }
            },
            confirmButton = {
                CompositionLocalProvider(
                    LocalContext provides parentContext,
                    LocalConfiguration provides parentConfiguration,
                ) {
                    TextButton(onClick = {
                        Analytics.action("Outfits", "confirm_delete_broken", mapOf("count" to brokenOutfits.size.toString()))
                        onDeleteBrokenOutfits(); showBrokenDeleteDialog = false
                    }) {
                        Text(stringResource(R.string.action_delete), color = MaterialTheme.colorScheme.error)
                    }
                }
            },
            dismissButton = {
                CompositionLocalProvider(
                    LocalContext provides parentContext,
                    LocalConfiguration provides parentConfiguration,
                ) {
                    TextButton(onClick = { showBrokenDeleteDialog = false }) { Text(stringResource(R.string.action_cancel)) }
                }
            },
        )
    }

    if (showDeleteDialog) {
        // Re-provide parent context/config so the dialog window honours the in-app language
        // toggle (an AlertDialog opens its own window; see CLAUDE.md → Dialog quirks).
        val parentContext = LocalContext.current
        val parentConfiguration = LocalConfiguration.current
        AlertDialog(
            onDismissRequest = { showDeleteDialog = false },
            title = {
                CompositionLocalProvider(
                    LocalContext provides parentContext,
                    LocalConfiguration provides parentConfiguration,
                ) { Text(stringResource(R.string.outfits_delete_selected_title)) }
            },
            text = {
                CompositionLocalProvider(
                    LocalContext provides parentContext,
                    LocalConfiguration provides parentConfiguration,
                ) { Text(stringResource(R.string.outfits_delete_selected_text, selectedOutfitIds.size)) }
            },
            confirmButton = {
                CompositionLocalProvider(
                    LocalContext provides parentContext,
                    LocalConfiguration provides parentConfiguration,
                ) {
                    TextButton(onClick = {
                        Analytics.action("Outfits", "confirm_delete_selected")
                        onDeleteSelectedStyles(); showDeleteDialog = false
                    }) {
                        Text(stringResource(R.string.action_delete), color = MaterialTheme.colorScheme.error)
                    }
                }
            },
            dismissButton = {
                CompositionLocalProvider(
                    LocalContext provides parentContext,
                    LocalConfiguration provides parentConfiguration,
                ) {
                    TextButton(onClick = { showDeleteDialog = false }) { Text(stringResource(R.string.action_cancel)) }
                }
            },
        )
    }

    Box(modifier = modifier.fillMaxSize()) {
        Column(Modifier.fillMaxSize()) {
            // Header + sub-tabs are owned by OutfitsScreen; this content starts at the selection bar.
            // ---- Selection bar ----
            if (isSelectionMode) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 12.dp, vertical = 2.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        stringResource(R.string.outfits_selected_count, selectedOutfitIds.size),
                        modifier = Modifier.weight(1f),
                        style = MaterialTheme.typography.bodyMedium,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    if (displayedStyles.any { it.id !in selectedOutfitIds }) {
                        TextButton(
                            onClick = { onSelectAllOutfits(displayedStyles.map { it.id }) },
                            contentPadding = PaddingValues(horizontal = 8.dp, vertical = 0.dp),
                        ) {
                            Text(
                                stringResource(R.string.outfits_select_all_count, displayedStyles.size),
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                        }
                    }
                    TextButton(
                        onClick = onClearOutfitSelection,
                        contentPadding = PaddingValues(horizontal = 8.dp, vertical = 0.dp),
                    ) {
                        Text(
                            stringResource(R.string.action_deselect_all),
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                }
                HorizontalDivider()
            }

            // ---- Quick category chip row + sort ----
            if (styles.isNotEmpty()) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .background(com.librelookai.ui.theme.LocalWardrobePalette.current.surface),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    QuickCategoryRow(
                        totalCount = styles.size,
                        filteredCount = filteredStyles.size,
                        appliedFilterCount = appliedFilterCount,
                        filtersEnabled = tagCategories.isNotEmpty() || styles.isNotEmpty(),
                        onClearFilters = { selectedTags = emptyMap(); textQuery = "" },
                        onOpenFilters = { filterSheetOpen = true },
                        modifier = Modifier.weight(1f),
                    )
                    if (!isSelectionMode) {
                        if (!isOffline) {
                            IconButton(
                                onClick = {
                                    Analytics.action("Outfits", "suggest_existing")
                                    onSuggestExisting()
                                },
                                enabled = !isPredicting,
                            ) {
                                if (isPredicting) {
                                    CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp)
                                } else {
                                    Icon(
                                        Icons.Default.AutoAwesome,
                                        contentDescription = stringResource(R.string.outfits_suggest),
                                    )
                                }
                            }
                        }
                        StyleSortButton(
                            sortBy = sortBy,
                            onSortChanged = { sortBy = it },
                            modifier = Modifier.padding(end = 4.dp),
                        )
                    }
                }
            }

            // ---- Broken-outfits banner: outfits referencing items no longer in any closet ----
            if (brokenOutfits.isNotEmpty() && !brokenBannerDismissed && !isSelectionMode) {
                Surface(
                    color = MaterialTheme.colorScheme.errorContainer,
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 6.dp),
                    shape = MaterialTheme.shapes.medium,
                ) {
                    Row(
                        modifier = Modifier.padding(start = 12.dp, end = 4.dp, top = 4.dp, bottom = 4.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Icon(
                            Icons.Default.Warning,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.onErrorContainer,
                            modifier = Modifier.size(20.dp),
                        )
                        Text(
                            stringResource(R.string.outfits_broken_banner, brokenOutfits.size),
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onErrorContainer,
                            modifier = Modifier.weight(1f).padding(horizontal = 8.dp),
                        )
                        TextButton(onClick = {
                            Analytics.action("Outfits", "open_delete_broken_dialog", mapOf("count" to brokenOutfits.size.toString()))
                            showBrokenDeleteDialog = true
                        }) {
                            Text(stringResource(R.string.outfits_broken_review), color = MaterialTheme.colorScheme.onErrorContainer)
                        }
                        IconButton(onClick = { brokenBannerDismissed = true }) {
                            Icon(
                                Icons.Default.Close,
                                contentDescription = stringResource(R.string.action_cancel),
                                tint = MaterialTheme.colorScheme.onErrorContainer,
                            )
                        }
                    }
                }
            }

            when {
                isLoading -> Box(Modifier.weight(1f).fillMaxWidth(), contentAlignment = Alignment.Center) {
                    CircularProgressIndicator()
                }
                styles.isEmpty() -> {
                    Box(Modifier.weight(1f).fillMaxWidth(), contentAlignment = Alignment.Center) {
                        Column(
                            horizontalAlignment = Alignment.CenterHorizontally,
                            verticalArrangement = Arrangement.Center,
                        ) {
                            Text(stringResource(R.string.outfits_empty), style = MaterialTheme.typography.bodyLarge)
                            Spacer(Modifier.height(4.dp))
                            Text(
                                stringResource(R.string.outfits_empty_hint),
                                style = MaterialTheme.typography.bodyMedium,
                            )
                        }
                    }
                }
                displayedStyles.isEmpty() -> {
                    Box(Modifier.weight(1f).fillMaxWidth(), contentAlignment = Alignment.Center) {
                        Text(stringResource(R.string.outfits_no_match), style = MaterialTheme.typography.bodyLarge)
                    }
                }
                else -> {
                    // Scroll the new outfit into view (and briefly open it) after the user
                    // saves it from the composer. List keys are outfit ids so animateScroll is
                    // safe even though the list is built from a derived state.
                    LaunchedEffect(pendingScrollOutfitId, displayedStyles) {
                        val target = pendingScrollOutfitId ?: return@LaunchedEffect
                        val idx = displayedStyles.indexOfFirst { it.id == target }
                        if (idx >= 0) {
                            runCatching { outfitsListState.animateScrollToItem(idx) }
                            onConsumePendingScrollOutfit()
                        }
                    }
                    LazyColumn(
                        state = outfitsListState,
                        modifier = Modifier.weight(1f).fillMaxWidth().scrollbar(outfitsListState),
                        contentPadding = PaddingValues(
                            top = 8.dp,
                            bottom = 96.dp,
                            start = 0.dp,
                            end = 0.dp,
                        ),
                        verticalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        itemsIndexed(
                            displayedStyles,
                            key = { _, s -> s.id },
                            contentType = { _, _ -> "outfit_card" },
                        ) { _, style ->
                            OutfitCard(
                                style = style,
                                itemsById = itemsById,
                                locations = locations,
                                tripName = style.tripId?.let { tripNamesById[it]?.takeIf { n -> n.isNotBlank() } },
                                isSelected = style.id in selectedOutfitIds,
                                isSelectionMode = isSelectionMode,
                                loved = style.loved,
                                onEdit = { onEditOutfit(style) },
                                onDelete = { onDeleteOutfit(style.id) },
                                onWear = { onWearOutfit(style.id) },
                                onToggleLoved = { onToggleLovedOutfit(style.id) },
                                onOpen = { fullscreenStyleId = style.id },
                                onToggleSelection = { onToggleOutfitSelection(style.id) },
                            )
                        }
                    }
                }
            }
        }

        // Shared create FAB — hidden during selection mode / offline.
        AppFab(
            label = stringResource(R.string.fab_outfits_new),
            icon = Icons.Default.Add,
            onClick = {
                Analytics.action("Outfits", "open_create_composer")
                onOpenCreateComposer()
            },
            expanded = rememberFabExpanded(outfitsListState),
            visible = !isSelectionMode && !isOffline,
            modifier = Modifier.align(Alignment.BottomEnd).padding(end = 16.dp, bottom = 16.dp),
        )

        // Shared selection action bar — Combine (≥2) / Try on (==1) primary · Love · Delete (danger).
        val outfitSelectionActions = buildList {
            if (!isOffline) {
                if (selectedOutfitIds.size >= 2) {
                    add(
                        SelectionAction(
                            label = stringResource(R.string.sel_combine),
                            icon = Icons.Default.AutoFixHigh,
                            kind = SelectionAction.Kind.Primary,
                        ) {
                            Analytics.action("Outfits", "combine_selected", mapOf("count" to selectedOutfitIds.size.toString()))
                            onCombineSelectedStyles()
                        },
                    )
                }
                if (selectedOutfitIds.size == 1 && canTryOn) {
                    val selectedStyle = styles.firstOrNull { it.id in selectedOutfitIds }
                    if (selectedStyle != null) {
                        add(
                            SelectionAction(
                                label = stringResource(R.string.tryon_fab),
                                icon = Icons.Default.AutoAwesome,
                                kind = SelectionAction.Kind.Primary,
                            ) {
                                Analytics.action("Outfits", "try_on_selected_style")
                                onTryOnStyle(selectedStyle)
                            },
                        )
                    }
                }
                add(
                    SelectionAction(
                        label = stringResource(R.string.sel_love),
                        icon = Icons.Default.Favorite,
                    ) {
                        Analytics.action("Outfits", "love_selected", mapOf("count" to selectedOutfitIds.size.toString()))
                        selectedOutfitIds.forEach { onToggleLovedOutfit(it) }
                    },
                )
                add(
                    SelectionAction(
                        label = stringResource(R.string.action_delete),
                        icon = Icons.Default.Delete,
                        kind = SelectionAction.Kind.Danger,
                    ) {
                        Analytics.action("Outfits", "open_delete_dialog", mapOf("count" to selectedOutfitIds.size.toString()))
                        showDeleteDialog = true
                    },
                )
            }
        }
        SelectionActionBar(
            count = selectedOutfitIds.size,
            onClear = onClearOutfitSelection,
            actions = outfitSelectionActions,
            visible = isSelectionMode && outfitSelectionActions.isNotEmpty(),
            modifier = Modifier.align(Alignment.BottomCenter),
        )

        if (filterSheetOpen) {
            WardrobeFilterSheet(
                tagCategories = tagCategories,
                selectedTags = selectedTags,
                appliedCount = displayedStyles.size,
                onTagsChanged = { selectedTags = it },
                textQuery = textQuery,
                onTextQueryChanged = { textQuery = it },
                onDismiss = { filterSheetOpen = false },
            )
        }

        // AI progress overlay — covers the whole screen while Gemini is working
        if (isPredicting) {
            AiProcessingOverlay(
                label = stringResource(R.string.ai_suggesting_style),
                modifier = Modifier.fillMaxSize(),
            )
        }

        // Full-screen outfit viewer (pager). Rendered on top of the list so its FAB
        // overlays and the dialog handles its own back-press.
        fullscreenStyleId?.let { styleId ->
            val startIndex = displayedStyles.indexOfFirst { it.id == styleId }
            if (startIndex >= 0 && displayedStyles.isNotEmpty()) {
                OutfitFullScreenViewer(
                    outfits = displayedStyles,
                    initialIndex = startIndex,
                    itemsById = itemsById,
                    locations = locations,
                    activeLocationId = activeLocationId,
                    onDismiss = { fullscreenStyleId = null },
                    onEdit = { o -> fullscreenStyleId = null; onEditOutfit(o) },
                    onWear = { o -> onWearOutfit(o.id) },
                    onToggleLoved = { o -> onToggleLovedOutfit(o.id) },
                    onDelete = { o ->
                        onDeleteOutfit(o.id)
                        if (displayedStyles.size <= 1) fullscreenStyleId = null
                    },
                    onSuggestTags = onSuggestOutfitTags,
                    onEditTags = onEditOutfitTags,
                    onTryOn = { o ->
                        fullscreenStyleId = null
                        onTryOnStyle(o)
                    },
                    canTryOn = canTryOn,
                    wardrobeViewModel = wardrobeViewModel,
                )
            } else {
                LaunchedEffect(styleId) { fullscreenStyleId = null }
            }
        }

        // Error snackbar for prediction errors
        predictionError?.let { msg ->
            Snackbar(
                modifier = Modifier
                    .align(Alignment.TopCenter)
                    .statusBarsPadding()
                    .padding(start = 8.dp, end = 8.dp, top = 64.dp),
                action = { TextButton(onClick = onClearPredictionError) { Text(stringResource(R.string.action_ok)) } },
            ) { Text(msg) }
        }
    }
}

// ---------- Sort button ----------

@Composable
private fun StyleSortButton(
    sortBy: OutfitSortOption,
    onSortChanged: (OutfitSortOption) -> Unit,
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
                OutfitSortOption.entries.forEach { option ->
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
                        onClick = {
                            Analytics.action("Outfits", "sort_changed", mapOf("option" to option.name))
                            onSortChanged(option); expanded = false
                        },
                    )
                }
            }
        }
    }
}

// ---------- Outfit card ----------

