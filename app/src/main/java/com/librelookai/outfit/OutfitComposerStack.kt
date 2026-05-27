package com.librelookai.outfit

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AutoAwesome
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.LockOpen
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.blur
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import coil.compose.AsyncImage
import coil.request.ImageRequest
import com.librelookai.R
import com.librelookai.data.model.Location
import com.librelookai.wardrobe.DriveImage

@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun ComposerStackedView(
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

