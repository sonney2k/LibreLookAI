package com.librelookai

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.FilterAlt
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/**
 * Shared filter UI used by Wardrobe, Outfits and Shopping screens:
 *  - [FiltersPill]:        compact header pill that opens the filter sheet
 *  - [QuickCategoryRow]:   horizontal clothing-category chips with live counts
 *  - [WardrobeFilterSheet]: bottom-sheet hosting the full [TagFilterBar]
 */

@Composable
internal fun FiltersPill(
    appliedCount: Int,
    enabled: Boolean,
    onClick: () -> Unit,
) {
    if (!enabled) return
    val palette = com.librelookai.ui.theme.LocalWardrobePalette.current
    val active = appliedCount > 0
    val bg = if (active) palette.primaryDim else palette.chipBg
    val fg = if (active) palette.primary else palette.chipFg
    val borderColor = if (active) palette.primary else palette.border
    val shape = RoundedCornerShape(20.dp)
    Box(
        modifier = Modifier
            .padding(horizontal = 2.dp)
            .clip(shape)
            .background(bg)
            .border(BorderStroke(if (active) 1.5.dp else 1.dp, borderColor), shape)
            .clickable(onClick = onClick)
            .padding(horizontal = 11.dp, vertical = 6.dp),
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            Icon(
                Icons.Default.FilterAlt,
                contentDescription = null,
                tint = fg,
                modifier = Modifier.size(13.dp),
            )
            Text(
                stringResource(R.string.wardrobe_filters),
                color = fg,
                fontSize = 12.sp,
                fontWeight = FontWeight.SemiBold,
            )
            if (active) {
                Box(
                    modifier = Modifier
                        .padding(start = 2.dp)
                        .clip(RoundedCornerShape(10.dp))
                        .background(palette.primary)
                        .padding(horizontal = 6.dp, vertical = 1.dp),
                ) {
                    Text(
                        appliedCount.toString(),
                        color = palette.onPrimary,
                        fontSize = 10.sp,
                        fontWeight = FontWeight.Bold,
                    )
                }
            }
        }
    }
}

@Composable
internal fun QuickCategoryRow(
    images: List<DriveImage>,
    selectedTags: Map<String, Set<String>>,
    onSelectCategory: (String?) -> Unit,
) {
    val palette = com.librelookai.ui.theme.LocalWardrobePalette.current
    data class Cat(val key: String?, val labelRes: Int)
    val cats = listOf(
        Cat(null, R.string.filter_all_locations),
        Cat("tops", R.string.tag_val_tops),
        Cat("bottoms", R.string.tag_val_bottoms),
        Cat("outerwear", R.string.tag_val_outerwear),
        Cat("footwear", R.string.tag_val_footwear),
        Cat("accessories", R.string.tag_val_accessories),
        Cat("dress", R.string.tag_val_dress),
        Cat("suit", R.string.tag_val_suit),
    )
    val counts = remember(images) {
        val m = HashMap<String, Int>()
        for (it in images) {
            val c = it.tags?.category?.lowercase().orEmpty()
            if (c.isNotEmpty()) m[c] = (m[c] ?: 0) + 1
        }
        m
    }
    val activeCatSet = selectedTags["Category"].orEmpty()
    val allActive = activeCatSet.isEmpty()
    LazyRow(
        modifier = Modifier
            .fillMaxWidth()
            .background(palette.surface)
            .padding(horizontal = 12.dp, vertical = 6.dp),
        horizontalArrangement = Arrangement.spacedBy(6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        val visibleCats = cats.filter { c ->
            c.key == null || (counts[c.key] ?: 0) > 0 || c.key in activeCatSet
        }
        items(visibleCats) { c ->
            val active = if (c.key == null) allActive else c.key in activeCatSet
            val n = if (c.key == null) images.size else (counts[c.key] ?: 0)
            val shape = RoundedCornerShape(18.dp)
            val bg = if (active) palette.primary else palette.chipBg
            val fg = if (active) palette.fabFg else palette.chipFg
            val borderColor = if (active) palette.primary else palette.border
            Row(
                modifier = Modifier
                    .clip(shape)
                    .background(bg)
                    .border(BorderStroke(if (active) 1.5.dp else 1.dp, borderColor), shape)
                    .clickable { onSelectCategory(c.key) }
                    .padding(horizontal = 11.dp, vertical = 5.dp),
                horizontalArrangement = Arrangement.spacedBy(5.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    stringResource(c.labelRes),
                    color = fg,
                    fontSize = 12.sp,
                    fontWeight = FontWeight.SemiBold,
                )
                Text(
                    n.toString(),
                    color = fg,
                    fontSize = 10.sp,
                    fontWeight = FontWeight.Bold,
                )
            }
        }
    }
}

/** Helper: toggle the "Category" entry in [selectedTags] when a quick-category chip is tapped. */
internal fun toggleQuickCategory(
    selectedTags: Map<String, Set<String>>,
    key: String?,
): Map<String, Set<String>> {
    val cur = selectedTags["Category"].orEmpty()
    return when {
        key == null -> selectedTags - "Category"
        cur.size == 1 && key in cur -> selectedTags - "Category"
        else -> selectedTags + ("Category" to setOf(key))
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun WardrobeFilterSheet(
    tagCategories: List<TagCategory>,
    selectedTags: Map<String, Set<String>>,
    appliedCount: Int,
    onTagsChanged: (Map<String, Set<String>>) -> Unit,
    onDismiss: () -> Unit,
) {
    if (tagCategories.isEmpty()) return
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
    ) {
        Column(Modifier.padding(horizontal = 8.dp, vertical = 8.dp)) {
            Text(
                stringResource(R.string.wardrobe_filters),
                style = MaterialTheme.typography.titleMedium,
                modifier = Modifier.padding(start = 8.dp, bottom = 4.dp),
            )
            TagFilterBar(
                tagCategories = tagCategories,
                selectedTags = selectedTags,
                onTagsChanged = onTagsChanged,
            )
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = 8.dp, bottom = 16.dp, start = 8.dp, end = 8.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                TextButton(
                    onClick = { onTagsChanged(emptyMap()) },
                    modifier = Modifier.weight(1f),
                ) { Text(stringResource(R.string.wardrobe_filters_reset)) }
                Button(
                    onClick = onDismiss,
                    modifier = Modifier.weight(1f),
                ) { Text(stringResource(R.string.wardrobe_filters_apply, appliedCount)) }
            }
        }
    }
}
