package com.librelookai.wardrobe
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyGridState
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.itemsIndexed
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil.compose.AsyncImage
import coil.request.ImageRequest
import com.librelookai.core.designsystem.R
import com.librelookai.util.scrollbar

/**
 * True when the item has no AI classification yet — tagging failed (e.g. an AI/credits outage at
 * import time) or never ran. Such an item has no `tags.category`, so [com.librelookai.outfit.layerFor]
 * can't assign it to any outfit slot and it silently can't be used in outfits. The grid surfaces
 * these with a "needs tagging" badge so the user knows to re-tag them.
 */
val DriveImage.needsTagging: Boolean
    get() = tags?.category?.isNotBlank() != true

/**
 * Shared tile renderer used by both the main Wardrobe grid and the Shopping wishlist grid.
 *
 * The Coil [ImageRequest] uses `memoryCacheKey = "{driveId}_{version}"` so re-scrolling past
 * already-decoded items reuses the in-memory bitmap rather than re-reading from disk. This is
 * the key reason the shopping grid felt slow before — it was rebuilding the request without a
 * stable memory key on every scroll.
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
fun WardrobeTile(
    image: DriveImage,
    isSelected: Boolean,
    isHighlighted: Boolean,
    isProcessing: Boolean,
    locationName: String?,
    onClick: () -> Unit,
    onLongClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val ctx = LocalContext.current
    Column(
        modifier = modifier
            .padding(1.dp)
            .combinedClickable(onClick = onClick, onLongClick = onLongClick),
    ) {
        Box(
            modifier = Modifier
                .aspectRatio(1f)
                .then(
                    if (isHighlighted) Modifier.border(
                        width = 3.dp,
                        color = MaterialTheme.colorScheme.primary,
                    ) else Modifier,
                ),
        ) {
            AsyncImage(
                model = remember(image.driveId, image.version) {
                    ImageRequest.Builder(ctx)
                        .data(image.localPath)
                        .memoryCacheKey("${image.driveId}_${image.version}")
                        .build()
                },
                contentDescription = image.tags?.label?.ifEmpty { image.name } ?: image.name,
                modifier = Modifier.fillMaxSize(),
                contentScale = ContentScale.Crop,
            )
            if (locationName != null) {
                Box(
                    modifier = Modifier
                        .align(Alignment.TopEnd)
                        .padding(3.dp)
                        .background(
                            color = MaterialTheme.colorScheme.secondaryContainer.copy(alpha = 0.85f),
                            shape = MaterialTheme.shapes.extraSmall,
                        )
                        .padding(horizontal = 4.dp, vertical = 1.dp),
                ) {
                    Text(
                        text = locationName,
                        color = MaterialTheme.colorScheme.onSecondaryContainer,
                        fontSize = 8.sp,
                        lineHeight = 10.sp,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
            // Flag items with no AI classification yet — they can't be placed in outfits until
            // re-tagged (see [DriveImage.needsTagging]).
            if (image.needsTagging) {
                Box(
                    modifier = Modifier
                        .align(Alignment.TopStart)
                        .padding(3.dp)
                        .background(
                            color = MaterialTheme.colorScheme.errorContainer.copy(alpha = 0.9f),
                            shape = MaterialTheme.shapes.extraSmall,
                        )
                        .padding(2.dp),
                ) {
                    Icon(
                        imageVector = Icons.Default.Warning,
                        contentDescription = stringResource(R.string.wardrobe_needs_tagging),
                        tint = MaterialTheme.colorScheme.onErrorContainer,
                        modifier = Modifier.size(14.dp),
                    )
                }
            }
            if (isSelected) {
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .background(Color.White.copy(alpha = 0.4f)),
                    contentAlignment = Alignment.TopEnd,
                ) {
                    Icon(
                        imageVector = Icons.Default.CheckCircle,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.padding(4.dp),
                    )
                }
            }
            if (isProcessing) {
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .background(Color.Black.copy(alpha = 0.35f)),
                    contentAlignment = Alignment.Center,
                ) {
                    CircularProgressIndicator(
                        color = MaterialTheme.colorScheme.primary,
                        strokeWidth = 3.dp,
                    )
                }
            }
        }
        val itemLabel = image.tags?.label?.ifEmpty { null }
        if (itemLabel != null) {
            Text(
                text = itemLabel,
                style = MaterialTheme.typography.labelSmall,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 4.dp, vertical = 2.dp),
            )
        }
    }
}

/**
 * Pinch-to-zoom wrapper around [WardrobeItemGrid]. Two-finger pinch scales the grid live; on
 * lift-off the new cell size is committed (clamped 56dp..320dp) so columns reflow. Cell size is
 * persisted across config changes via [rememberSaveable].
 */
@Composable
fun WardrobeZoomableItemGrid(
    images: List<DriveImage>,
    selectedIds: Set<String>,
    onClick: (index: Int, image: DriveImage) -> Unit,
    onLongClick: (image: DriveImage) -> Unit,
    modifier: Modifier = Modifier,
    gridState: LazyGridState = rememberLazyGridState(),
    locationLookup: (DriveImage) -> String? = { null },
    highlightedDriveId: String? = null,
    processingDriveId: String? = null,
    contentPadding: PaddingValues = PaddingValues(0.dp),
) {
    var cellSizeDp by rememberSaveable { mutableFloatStateOf(120f) }
    var pinchVisualScale by remember { mutableFloatStateOf(1f) }
    Box(
        modifier = modifier.pointerInput(Unit) {
            awaitEachGesture {
                awaitFirstDown(requireUnconsumed = false, pass = PointerEventPass.Initial)
                var prevDistance = -1f
                do {
                    val event = awaitPointerEvent(PointerEventPass.Initial)
                    val pressed = event.changes.filter { it.pressed }
                    if (pressed.size >= 2) {
                        val dist = (pressed[1].position - pressed[0].position).getDistance()
                        if (prevDistance > 0f) pinchVisualScale *= (dist / prevDistance)
                        prevDistance = dist
                        pressed.forEach { it.consume() }
                    } else prevDistance = -1f
                } while (event.changes.any { it.pressed })
                cellSizeDp = (cellSizeDp * pinchVisualScale).coerceIn(56f, 320f)
                pinchVisualScale = 1f
            }
        },
    ) {
        WardrobeItemGrid(
            images = images,
            selectedIds = selectedIds,
            onClick = onClick,
            onLongClick = onLongClick,
            // Only wrap the grid in a graphicsLayer while a pinch is actually in progress. A
            // persistent render layer over the lazy grid (a SubcomposeLayout) makes it skip
            // composing items that become visible after a data swap — e.g. the Phase 1→Phase 2
            // load — until a scroll forces a remeasure, which manifests as "items missing until
            // you scroll". At rest (scale == 1f) the layer earns nothing, so we drop it.
            modifier = Modifier
                .fillMaxSize()
                .then(
                    if (pinchVisualScale != 1f) Modifier.graphicsLayer {
                        clip = false
                        scaleX = pinchVisualScale
                        scaleY = pinchVisualScale
                    } else Modifier,
                ),
            gridState = gridState,
            cellSizeDp = cellSizeDp.dp,
            locationLookup = locationLookup,
            highlightedDriveId = highlightedDriveId,
            processingDriveId = processingDriveId,
            contentPadding = contentPadding,
        )
    }
}

/**
 * Adaptive [LazyVerticalGrid] of wardrobe items. Used by both Wardrobe and Shopping screens to
 * keep visuals and image-cache behavior identical.
 *
 * Callers own the [gridState] (so they can drive scroll-to behavior) and the cell size (so the
 * Wardrobe screen's pinch-to-zoom still works).
 */
@Composable
fun WardrobeItemGrid(
    images: List<DriveImage>,
    selectedIds: Set<String>,
    onClick: (index: Int, image: DriveImage) -> Unit,
    onLongClick: (image: DriveImage) -> Unit,
    modifier: Modifier = Modifier,
    gridState: LazyGridState = rememberLazyGridState(),
    cellSizeDp: Dp = 120.dp,
    locationLookup: (DriveImage) -> String? = { null },
    highlightedDriveId: String? = null,
    processingDriveId: String? = null,
    contentPadding: PaddingValues = PaddingValues(0.dp),
) {
    // Defensive distinctBy: a transient sync race can produce duplicate driveIds in state.images
    // (e.g. local placeholder + Drive-confirmed copy briefly coexisting). LazyVerticalGrid crashes
    // hard on duplicate keys, so dedupe here once instead of patching every caller.
    val uniqueImages = remember(images) {
        if (images.size == images.distinctBy { it.driveId }.size) images
        else images.distinctBy { it.driveId }
    }
    LazyVerticalGrid(
        state = gridState,
        columns = GridCells.Adaptive(cellSizeDp),
        modifier = modifier.scrollbar(gridState),
        contentPadding = contentPadding,
    ) {
        itemsIndexed(
            uniqueImages,
            key = { _, img -> img.driveId },
            // All tiles share one slot type so the lazy layout reuses/measures them predictably
            // across the Phase 1→Phase 2 list swap.
            contentType = { _, _ -> "wardrobe_tile" },
        ) { index, image ->
            WardrobeTile(
                image = image,
                isSelected = image.driveId in selectedIds,
                isHighlighted = image.driveId == highlightedDriveId,
                isProcessing = image.driveId == processingDriveId,
                locationName = locationLookup(image),
                onClick = { onClick(index, image) },
                onLongClick = { onLongClick(image) },
            )
        }
    }
}
