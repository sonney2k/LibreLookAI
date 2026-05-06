package com.librelookai

import androidx.compose.animation.animateContentSize
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.FilterAlt
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.toMutableStateMap
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/**
 * Shared filter UI used by Wardrobe, Outfits and Shopping screens:
 *  - [FiltersPill]:        compact header pill that opens the filter sheet
 *  - [QuickCategoryRow]:   horizontal clothing-category chips with live counts
 *  - [WardrobeFilterSheet]: bottom-sheet with collapsible sections (per design)
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

/**
 * Second-row chip strip: an "All" pill (clears filters, shows total count) plus
 * a Filters pill (opens the filter sheet, shows count of items matching the
 * active filter).
 */
@Composable
internal fun QuickCategoryRow(
    totalCount: Int,
    filteredCount: Int,
    appliedFilterCount: Int,
    filtersEnabled: Boolean,
    onClearFilters: () -> Unit,
    onOpenFilters: () -> Unit,
) {
    val palette = com.librelookai.ui.theme.LocalWardrobePalette.current
    val isCaveat = com.librelookai.ui.theme.LocalAppFont.current == AppFont.CAVEAT
    val labelSize = if (isCaveat) 16.sp else 12.sp
    val countSize = if (isCaveat) 14.sp else 10.sp
    val hasFilter = appliedFilterCount > 0
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .background(palette.surface)
            .padding(horizontal = 12.dp, vertical = 6.dp),
        horizontalArrangement = Arrangement.spacedBy(6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        // ── "All" pill ──
        val allActive = !hasFilter
        val allShape = RoundedCornerShape(18.dp)
        val allBg = if (allActive) palette.primary else palette.chipBg
        val allFg = if (allActive) palette.fabFg else palette.chipFg
        val allBorder = if (allActive) palette.primary else palette.border
        Row(
            modifier = Modifier
                .clip(allShape)
                .background(allBg)
                .border(BorderStroke(if (allActive) 1.5.dp else 1.dp, allBorder), allShape)
                .clickable(enabled = hasFilter, onClick = onClearFilters)
                .padding(horizontal = 11.dp, vertical = 5.dp),
            horizontalArrangement = Arrangement.spacedBy(5.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                stringResource(R.string.filter_all_locations),
                color = allFg,
                fontSize = labelSize,
                fontWeight = FontWeight.SemiBold,
            )
            Text(
                totalCount.toString(),
                color = allFg,
                fontSize = countSize,
                fontWeight = FontWeight.Bold,
            )
        }

        // ── Filters pill (right of All) ──
        if (filtersEnabled) {
            val active = hasFilter
            val shape = RoundedCornerShape(18.dp)
            val bg = if (active) palette.primaryDim else palette.chipBg
            val fg = if (active) palette.primary else palette.chipFg
            val borderColor = if (active) palette.primary else palette.border
            Row(
                modifier = Modifier
                    .clip(shape)
                    .background(bg)
                    .border(BorderStroke(if (active) 1.5.dp else 1.dp, borderColor), shape)
                    .clickable(onClick = onOpenFilters)
                    .padding(horizontal = 11.dp, vertical = 5.dp),
                horizontalArrangement = Arrangement.spacedBy(5.dp),
                verticalAlignment = Alignment.CenterVertically,
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
                    fontSize = labelSize,
                    fontWeight = FontWeight.SemiBold,
                )
                Text(
                    filteredCount.toString(),
                    color = fg,
                    fontSize = countSize,
                    fontWeight = FontWeight.Bold,
                )
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
internal fun WardrobeFilterSheet(
    tagCategories: List<TagCategory>,
    selectedTags: Map<String, Set<String>>,
    appliedCount: Int,
    onTagsChanged: (Map<String, Set<String>>) -> Unit,
    onDismiss: () -> Unit,
) {
    if (tagCategories.isEmpty()) return
    val palette = com.librelookai.ui.theme.LocalWardrobePalette.current

    // Pending state: edits stay local until Apply commits them.
    val pending = remember(selectedTags) {
        mutableStateMapOf<String, Set<String>>().apply {
            selectedTags.forEach { (k, v) -> put(k, v.toSet()) }
        }
    }
    val pendingCount = pending.values.sumOf { it.size }
    var openSection by remember { mutableStateOf<String?>(tagCategories.firstOrNull()?.label) }

    fun toggle(category: String, value: String) {
        val cur = pending[category] ?: emptySet()
        pending[category] = if (value in cur) cur - value else cur + value
    }

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
        containerColor = palette.sheetBg,
    ) {
        // ── Sticky header ──
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(start = 20.dp, end = 12.dp, top = 4.dp, bottom = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                stringResource(R.string.wardrobe_filters),
                fontSize = 18.sp,
                fontWeight = FontWeight.Bold,
                color = palette.text,
                modifier = Modifier.weight(1f),
            )
            Text(
                stringResource(R.string.wardrobe_filters_reset),
                fontSize = 13.sp,
                fontWeight = FontWeight.SemiBold,
                color = palette.textMuted,
                modifier = Modifier
                    .clip(RoundedCornerShape(20.dp))
                    .clickable { pending.clear() }
                    .padding(horizontal = 10.dp, vertical = 8.dp),
            )
            Spacer(Modifier.size(4.dp))
            Box(
                modifier = Modifier
                    .clip(RoundedCornerShape(20.dp))
                    .background(palette.fabBg)
                    .clickable {
                        onTagsChanged(
                            pending
                                .mapValues { it.value.toSet() }
                                .filterValues { it.isNotEmpty() },
                        )
                        onDismiss()
                    }
                    .padding(horizontal = 18.dp, vertical = 8.dp),
            ) {
                Text(
                    stringResource(R.string.wardrobe_filters_apply, pendingCount),
                    color = palette.fabFg,
                    fontSize = 13.sp,
                    fontWeight = FontWeight.Bold,
                )
            }
        }
        HorizontalDivider(color = palette.divider)

        // ── Scrollable list of collapsible sections ──
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(max = 560.dp)
                .verticalScroll(rememberScrollState()),
        ) {
            tagCategories.forEach { category ->
                val isOpen = openSection == category.label
                val activeInCat = (pending[category.label]?.size ?: 0)
                Column(modifier = Modifier.animateContentSize()) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable {
                                openSection = if (isOpen) null else category.label
                            }
                            .padding(horizontal = 20.dp, vertical = 13.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Row(
                            modifier = Modifier.weight(1f),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                        ) {
                            Text(
                                tagCategoryDisplayLabel(category.label),
                                fontSize = 14.sp,
                                fontWeight = FontWeight.Bold,
                                color = palette.text,
                            )
                            if (activeInCat > 0) {
                                Box(
                                    modifier = Modifier
                                        .clip(RoundedCornerShape(10.dp))
                                        .background(palette.primary)
                                        .padding(horizontal = 7.dp, vertical = 1.dp),
                                ) {
                                    Text(
                                        activeInCat.toString(),
                                        color = palette.onPrimary,
                                        fontSize = 10.sp,
                                        fontWeight = FontWeight.Bold,
                                    )
                                }
                            }
                        }
                        val rotation by animateFloatAsState(
                            targetValue = if (isOpen) 180f else 0f,
                            label = "chevron",
                        )
                        Icon(
                            Icons.Default.KeyboardArrowDown,
                            contentDescription = null,
                            tint = palette.textMuted,
                            modifier = Modifier
                                .size(20.dp)
                                .rotate(rotation),
                        )
                    }
                    if (isOpen) {
                        FlowRow(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(start = 16.dp, end = 16.dp, bottom = 14.dp),
                            horizontalArrangement = Arrangement.spacedBy(7.dp),
                            verticalArrangement = Arrangement.spacedBy(7.dp),
                        ) {
                            val selectedSet = pending[category.label] ?: emptySet()
                            category.tags.forEach { tag ->
                                val active = tag in selectedSet
                                val shape = RoundedCornerShape(20.dp)
                                Box(
                                    modifier = Modifier
                                        .clip(shape)
                                        .background(if (active) palette.primary else palette.chipBg)
                                        .then(
                                            if (active) Modifier
                                            else Modifier.border(
                                                BorderStroke(1.dp, palette.border),
                                                shape,
                                            ),
                                        )
                                        .clickable { toggle(category.label, tag) }
                                        .padding(horizontal = 13.dp, vertical = 6.dp),
                                ) {
                                    Text(
                                        tag.localizedTagValue(),
                                        color = if (active) palette.fabFg else palette.chipFg,
                                        fontSize = 13.sp,
                                        fontWeight = FontWeight.SemiBold,
                                    )
                                }
                            }
                        }
                    }
                }
                HorizontalDivider(color = palette.divider)
            }
            Spacer(Modifier.height(16.dp))
        }
    }
}
