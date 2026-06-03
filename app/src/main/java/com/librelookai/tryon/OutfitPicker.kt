package com.librelookai.tryon

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Sort
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
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
import com.librelookai.R
import com.librelookai.data.model.Outfit
import com.librelookai.util.rememberDialogBottomInset
import com.librelookai.wardrobe.DriveImage
import com.librelookai.wardrobe.QuickCategoryRow
import com.librelookai.wardrobe.WardrobeFilterSheet
import com.librelookai.wardrobe.tagCategories
import com.librelookai.wardrobe.tagStringsForCategory
import java.io.File

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun OutfitPickerDialog(
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
                            Icon(Icons.Default.Close, contentDescription = stringResource(R.string.action_close))
                        }
                        Text(
                            stringResource(R.string.tryon_outfit_picker_title),
                            style = MaterialTheme.typography.titleMedium,
                            maxLines = 1,
                            overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis,
                            modifier = Modifier.weight(1f),
                        )
                        com.librelookai.ViewerHeaderActions(onBeforeNavigate = onDismiss)
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

