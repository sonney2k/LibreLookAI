package com.librelookai.outfit

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.ImageSearch
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.InputChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.compose.ui.window.DialogWindowProvider
import com.librelookai.core.designsystem.R
import com.librelookai.data.model.Location
import com.librelookai.util.AiProcessingOverlay
import com.librelookai.util.Analytics
import com.librelookai.util.LocalSystemBarsPadding
import com.librelookai.wardrobe.DriveImage
import com.librelookai.wardrobe.tagCategories
import com.librelookai.wardrobe.tagStringsForCategory
import kotlinx.coroutines.launch

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AddItemSheet(
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
                                Icon(Icons.Default.Close, contentDescription = stringResource(R.string.action_close))
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
fun OutfitTagsEditor(
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

