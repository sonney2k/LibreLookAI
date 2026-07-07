package com.librelookai.outfit

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.blur
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil.compose.AsyncImage
import coil.request.ImageRequest
import com.librelookai.R
import com.librelookai.data.model.Location
import com.librelookai.data.model.Outfit
import com.librelookai.wardrobe.DriveImage

@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun OutfitPageBody(
    outfit: Outfit,
    itemsById: Map<String, DriveImage>,
    locations: List<Location>,
    onItemClick: (DriveImage) -> Unit,
    bottomPadding: androidx.compose.ui.unit.Dp,
) {
    val items = remember(outfit.itemIds, itemsById) {
        outfit.itemIds.mapNotNull { itemsById[it] }
    }
    // Order items by anatomical bucket so the natural stack reads top → bottom.
    val rows: List<List<DriveImage>> = remember(items) {
        val grouped = items.groupBy { bucketFor(it) }
        OutfitItemBucket.entries.mapNotNull { b ->
            grouped[b]?.takeIf { it.isNotEmpty() }
        }
    }
    BoxWithConstraints(
        modifier = Modifier
            .fillMaxSize()
            .padding(horizontal = 12.dp)
            .padding(bottom = bottomPadding),
    ) {
        if (items.isEmpty()) {
            Text(
                stringResource(R.string.outfits_missing_items),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.outline,
                modifier = Modifier.align(Alignment.Center),
            )
            return@BoxWithConstraints
        }
        val rowOverlap = 0.28f
        val itemOverlap = 0.18f
        val n = rows.size
        val m = (rows.maxOfOrNull { it.size } ?: 1).coerceAtLeast(1)
        val availW = maxWidth
        val availH = maxHeight
        val byH = availH / (1f + (1f - rowOverlap) * (n - 1))
        val byW = availW / (1f + (1f - itemOverlap) * (m - 1))
        val tileSize = minOf(byH, byW).coerceIn(96.dp, 320.dp)
        val rowStride = tileSize * (1f - rowOverlap)
        val itemStride = tileSize * (1f - itemOverlap)
        val totalContentH = tileSize + rowStride * (n - 1)
        val topOffset = ((availH - totalContentH) / 2).coerceAtLeast(0.dp)

        rows.forEachIndexed { rowIdx, rowItems ->
            val rowWidth = tileSize + itemStride * (rowItems.size - 1)
            val rowLeft = ((availW - rowWidth) / 2).coerceAtLeast(0.dp)
            val rowTop = topOffset + rowStride * rowIdx
            rowItems.forEachIndexed { itemIdx, image ->
                val left = rowLeft + itemStride * itemIdx
                val locName = if (locations.size > 1)
                    locations.find { it.folderId == image.folderId }?.name
                else null
                OutfitViewerItemTile(
                    image = image,
                    locationName = locName,
                    size = tileSize,
                    onClick = { onItemClick(image) },
                    modifier = Modifier.offset(x = left, y = rowTop),
                )
            }
        }
    }
}

@Composable
private fun OutfitViewerItemTile(
    image: DriveImage,
    locationName: String?,
    size: androidx.compose.ui.unit.Dp = 140.dp,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val ctx = LocalContext.current
    val model = remember(image.driveId, image.version) {
        ImageRequest.Builder(ctx)
            .data(image.localPath)
            .memoryCacheKey("${image.driveId}_${image.version}")
            .build()
    }
    Box(
        modifier = modifier
            .size(size)
            .clickable(onClick = onClick),
    ) {
        // Drop-shadow silhouette — follows the cutout's alpha channel.
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
        if (locationName != null) {
            Box(
                modifier = Modifier
                    .align(Alignment.TopEnd)
                    .padding(4.dp)
                    .background(
                        color = MaterialTheme.colorScheme.secondaryContainer.copy(alpha = 0.85f),
                        shape = MaterialTheme.shapes.extraSmall,
                    )
                    .padding(horizontal = 4.dp, vertical = 1.dp),
            ) {
                Text(
                    text = locationName,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSecondaryContainer,
                    fontSize = 8.sp,
                    lineHeight = 10.sp,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
    }
}

