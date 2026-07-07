package com.librelookai.tryon

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.AutoAwesome
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.blur
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import coil.compose.AsyncImage
import coil.request.ImageRequest
import com.librelookai.R
import com.librelookai.data.model.Location
import com.librelookai.data.model.Outfit
import com.librelookai.data.model.TryOn
import com.librelookai.outfit.AddItemSheet
import com.librelookai.outfit.OutfitItemBucket
import com.librelookai.outfit.bucketFor
import com.librelookai.util.Analytics
import com.librelookai.wardrobe.DriveImage

@OptIn(ExperimentalMaterial3Api::class, ExperimentalFoundationApi::class)
@Composable
internal fun TryOnComposerContent(
    state: TryOnUiState,
    wardrobeImages: List<DriveImage>,
    outfits: List<Outfit>,
    locations: List<Location>,
    referencePhotoPaths: List<String>,
    /** Fuzzy text filter over the picker's candidates (wardrobe search). */
    onTextFilter: (String, List<DriveImage>) -> List<DriveImage>,
    /** Find-by-photo scorer for the picker: file + candidates -> driveId->score. */
    findSimilarByPhoto: suspend (java.io.File, List<DriveImage>) -> Map<String, Float>,
    onRemoveItem: (String) -> Unit,
    onAddItems: (Set<String>) -> Unit,
    onPickOutfit: (Outfit) -> Unit,
    onConsumeAutoPick: () -> Unit,
    onSwapSource: () -> Unit,
    onEditReferencePhotos: () -> Unit,
    onCancelEmpty: () -> Unit,
    onGenerate: () -> Unit,
) {
    var showItemPicker by remember { mutableStateOf(false) }
    var showOutfitPicker by remember { mutableStateOf(false) }
    // True while the auto-opened (Quick-sheet) picker is showing on an as-yet-empty composer.
    // If the user cancels that picker without choosing anything, we back out instead of
    // dropping them on an empty composer.
    var awaitingAutoPick by remember { mutableStateOf(false) }
    val sourceOutfit = remember(state.sourceOutfitId, outfits) {
        state.sourceOutfitId?.let { id -> outfits.firstOrNull { it.id == id } }
    }

    // When the Quick sheet routed here with autoPick, open the matching picker once.
    LaunchedEffect(state.autoPick, state.sourceKind) {
        if (state.autoPick) {
            when (state.sourceKind) {
                TryOnSourceKind.OUTFIT -> { showOutfitPicker = true; awaitingAutoPick = true }
                TryOnSourceKind.WARDROBE, TryOnSourceKind.SHOPPING -> { showItemPicker = true; awaitingAutoPick = true }
                else -> {}
            }
            onConsumeAutoPick()
        }
    }

    val chosenImages = state.itemIds.mapNotNull { id -> wardrobeImages.firstOrNull { it.driveId == id } }

    Column(modifier = Modifier.fillMaxSize()) {
        Column(
            modifier = Modifier
                .weight(1f)
                .verticalScroll(rememberScrollState())
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            // Source banner — present whenever the composer was opened from a source surface.
            if (state.sourceKind != TryOnSourceKind.NONE) {
                val bannerTitle = when (state.sourceKind) {
                    TryOnSourceKind.OUTFIT -> sourceOutfit?.name?.ifBlank { null }
                        ?: state.sourceContext
                        ?: stringResource(R.string.tryon_source_context_items, chosenImages.size)
                    else -> state.sourceContext?.takeIf { it.isNotBlank() }
                        ?: stringResource(R.string.tryon_source_context_items, chosenImages.size)
                }
                SourceBanner(
                    kind = state.sourceKind,
                    title = bannerTitle,
                    onSwap = onSwapSource,
                )
            }

            Surface(
                color = MaterialTheme.colorScheme.secondaryContainer,
                shape = RoundedCornerShape(12.dp),
            ) {
                Text(
                    stringResource(R.string.tryon_composer_explain),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSecondaryContainer,
                    modifier = Modifier.padding(12.dp),
                )
            }

            Text(
                stringResource(R.string.tryon_composer_items_title, chosenImages.size),
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.SemiBold,
            )

            // Stacked, overlapping cutouts with drop-shadows — mirrors the outfit viewer layout
            // (see OutfitPageBody) instead of a flat grid of thumbnails.
            if (chosenImages.isNotEmpty()) {
                TryOnItemStack(
                    images = chosenImages,
                    modifier = Modifier.fillMaxWidth(),
                    onRemove = onRemoveItem,
                )
            }

            OutlinedButton(
                onClick = { showItemPicker = true },
                modifier = Modifier.fillMaxWidth(),
            ) {
                Icon(Icons.Default.Add, null, modifier = Modifier.size(18.dp))
                Spacer(Modifier.width(8.dp))
                Text(stringResource(R.string.tryon_add_item))
                Spacer(Modifier.width(4.dp))
                Icon(
                    Icons.AutoMirrored.Filled.KeyboardArrowRight,
                    null,
                    modifier = Modifier.size(18.dp),
                )
            }

            if (referencePhotoPaths.isNotEmpty()) {
                ReferencePhotosPreview(
                    photoPaths = referencePhotoPaths,
                    onEdit = onEditReferencePhotos,
                )
            }
        }

        // Sticky generate button — pinned at the bottom so it always fits on screen.
        Box(modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp)) {
            Button(
                onClick = onGenerate,
                enabled = chosenImages.isNotEmpty() && !state.isGenerating,
                modifier = Modifier.fillMaxWidth(),
            ) {
                com.librelookai.billing.CostBadge(
                    com.librelookai.gemini.GeminiActionId.TRY_ON_OUTFIT,
                    tokens = com.librelookai.billing.rememberTryOnCostTokens(
                        personPaths = referencePhotoPaths,
                        itemPaths = chosenImages.map { it.localPath },
                    ),
                )
                Icon(Icons.Default.AutoAwesome, null, modifier = Modifier.size(18.dp))
                Spacer(Modifier.width(8.dp))
                Text(stringResource(R.string.tryon_generate))
            }
        }
    }

    if (showItemPicker) {
        // Reuse the outfit composer's picker so Try-On gains the same affordances:
        // tag filters, text search, find-by-photo, sort, and pinch-to-zoom grid.
        AddItemSheet(
            allItems = wardrobeImages,
            alreadyChosen = state.itemIds,
            locations = locations,
            onTextFilter = onTextFilter,
            findSimilarByPhoto = findSimilarByPhoto,
            allowMultiSelect = true,
            onConfirm = { ids ->
                onAddItems(ids)
                showItemPicker = false
                // Confirming with nothing chosen from a Quick-sheet auto-pick is the same as
                // cancelling — don't strand the user on an empty composer.
                if (awaitingAutoPick && ids.isEmpty() && state.itemIds.isEmpty()) onCancelEmpty()
                awaitingAutoPick = false
            },
            onDismiss = {
                showItemPicker = false
                if (awaitingAutoPick) {
                    awaitingAutoPick = false
                    if (state.itemIds.isEmpty()) onCancelEmpty()
                }
            },
        )
    }

    if (showOutfitPicker) {
        OutfitPickerDialog(
            outfits = outfits,
            wardrobeImages = wardrobeImages,
            onPick = { outfit ->
                Analytics.action("TryOn/Composer", "pick_outfit", mapOf("items" to outfit.itemIds.size.toString()))
                onPickOutfit(outfit)
                showOutfitPicker = false
                awaitingAutoPick = false
            },
            onDismiss = {
                showOutfitPicker = false
                // Backed out of the auto-opened outfit picker without choosing — don't advance
                // to an empty composer.
                if (awaitingAutoPick) {
                    awaitingAutoPick = false
                    if (state.itemIds.isEmpty() && state.sourceOutfitId == null) onCancelEmpty()
                }
            },
        )
    }
}

/**
 * Stacked, overlapping cutouts of the chosen try-on items — the same anatomical top→bottom
 * stack with drop-shadows used by the outfit viewer ([com.librelookai.outfit.OutfitPageBody]).
 * Each tile carries a remove badge.
 */
@Composable
internal fun TryOnItemStack(
    images: List<DriveImage>,
    modifier: Modifier = Modifier,
    onItemClick: ((DriveImage) -> Unit)? = null,
    onRemove: ((String) -> Unit)? = null,
) {
    val rows: List<List<DriveImage>> = remember(images) {
        val grouped = images.groupBy { bucketFor(it) }
        OutfitItemBucket.entries.mapNotNull { b -> grouped[b]?.takeIf { it.isNotEmpty() } }
    }
    BoxWithConstraints(modifier = modifier.height(340.dp)) {
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
                TryOnStackTile(
                    image = image,
                    size = tileSize,
                    onClick = onItemClick?.let { { it(image) } },
                    onRemove = onRemove?.let { { it(image.driveId) } },
                    modifier = Modifier.offset(x = left, y = rowTop),
                )
            }
        }
    }
}

@Composable
private fun TryOnStackTile(
    image: DriveImage,
    size: Dp,
    onClick: (() -> Unit)?,
    onRemove: (() -> Unit)?,
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
            .then(if (onClick != null) Modifier.clickable(onClick = onClick) else Modifier),
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
        if (onRemove != null) {
            Box(
                modifier = Modifier
                    .align(Alignment.TopEnd)
                    .padding(4.dp)
                    .size(22.dp)
                    .clip(RoundedCornerShape(50))
                    .background(Color.Black.copy(alpha = 0.55f))
                    .clickable(onClick = onRemove),
                contentAlignment = Alignment.Center,
            ) {
                Icon(Icons.Default.Close, contentDescription = stringResource(R.string.action_remove), tint = Color.White, modifier = Modifier.size(14.dp))
            }
        }
    }
}

/**
 * Fullscreen outfit picker — shows every saved outfit as a row with the outfit name and a
 * thumbnail strip of its items. Picking is single-tap: confirms immediately and dismisses.
 * Sits above the Try-On composer Dialog as its own Dialog (same pattern as [AddItemSheet]).
 */
