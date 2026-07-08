package com.librelookai

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import com.librelookai.outfit.OutfitsViewModel
import com.librelookai.wardrobe.DeleteItemsConfirmDialog
import com.librelookai.wardrobe.FullScreenViewer
import com.librelookai.wardrobe.LocationViewModel
import com.librelookai.wardrobe.WardrobeViewModel
import com.librelookai.wardrobe.tagCategories
import com.librelookai.shopping.ShoppingClosetViewModel
import com.librelookai.tryon.TryOnViewModel

/**
 * Content of the [ItemViewerRoute] NavHost destination — the fullscreen item pager that
 * replaced the per-host [FullScreenViewer] Dialogs (wardrobe grid / shopping list / try-on
 * detail / outfit-composer stack). Items resolve LIVE from the owning ViewModel per source
 * (tag edits, deletes and moves during viewing stay correct); the route only pins the
 * viewing context.
 *
 * Per-source behavior matches the old Dialog hosts:
 *  - wardrobe: full WardrobeViewModel actions; Delete routes through the cascade-aware
 *    confirm dialog (hosted here — the grid underneath is not composed while this
 *    destination overlays Home).
 *  - shopping: ShoppingClosetViewModel actions; "Move" is move-to-closet with the
 *    moved/undo snackbar routed through WardrobeViewModel.
 *  - tryon: wardrobe actions on the try-on's items (no move — locations are withheld).
 *  - composer: items come live from the composer's slots; wardrobe actions.
 */
@Composable
internal fun ItemViewerDestination(
    source: String,
    routeItemIds: List<String>,
    initialItemId: String?,
    wardrobeViewModel: WardrobeViewModel,
    shoppingClosetViewModel: ShoppingClosetViewModel,
    outfitsViewModel: OutfitsViewModel,
    generationViewModel: com.librelookai.outfit.OutfitGenerationViewModel,
    tryOnHistoryViewModel: com.librelookai.tryon.TryOnHistoryViewModel,
    locationViewModel: LocationViewModel,
    onCreateOutfitFromSelection: (Set<String>) -> Unit,
    onClose: () -> Unit,
) {
    val wardrobeState by wardrobeViewModel.state.collectAsState()
    val shoppingState by shoppingClosetViewModel.state.collectAsState()
    val outfitsState by outfitsViewModel.state.collectAsState()
    val generationState by generationViewModel.state.collectAsState()
    val tryOnState by tryOnHistoryViewModel.state.collectAsState()
    val locationState by locationViewModel.state.collectAsState()

    val isShopping = source == ItemViewerRoute.SOURCE_SHOPPING

    val images = remember(
        source, routeItemIds, wardrobeState.images, wardrobeState.allLocationImages,
        shoppingState.items, generationState.composerSlots, outfitsState.wardrobeImages,
    ) {
        when (source) {
            ItemViewerRoute.SOURCE_SHOPPING -> {
                val byId = shoppingState.items.associateBy { it.driveId }
                routeItemIds.mapNotNull(byId::get)
            }
            ItemViewerRoute.SOURCE_TRYON -> {
                // The try-on feed mixes wardrobe and shopping items (FAB in both screens).
                val byId = (wardrobeState.images + shoppingState.items).associateBy { it.driveId }
                routeItemIds.mapNotNull(byId::get)
            }
            ItemViewerRoute.SOURCE_OUTFIT -> {
                // Same item pool the outfit viewer resolves its thumbnails from.
                val byId = com.librelookai.outfit.outfitItemPool(
                    allLocationImages = wardrobeState.allLocationImages,
                    wardrobeImages = outfitsState.wardrobeImages,
                    activeImages = wardrobeState.images,
                ).associateBy { it.driveId }
                routeItemIds.mapNotNull(byId::get)
            }
            ItemViewerRoute.SOURCE_COMPOSER -> {
                // Same every-known-source pool the composer resolves its slots from (fresher
                // active-closet / shopping copies win on duplicate driveIds).
                val crossCloset = wardrobeState.allLocationImages.ifEmpty { wardrobeState.images }
                val byId = (crossCloset + wardrobeState.images + shoppingState.items)
                    .associateBy { it.driveId }
                generationState.composerSlots.mapNotNull { it.selectedItemId?.let(byId::get) }
            }
            else -> {
                val byId = wardrobeState.images.associateBy { it.driveId }
                routeItemIds.mapNotNull(byId::get)
            }
        }
    }

    // Tag taxonomy of the source's whole pool (not just the viewed slice), matching the old
    // hosts — the tag editor offers categories seen across the surface the viewer came from.
    val allTagCategories = remember(source, wardrobeState.images, shoppingState.items, images) {
        when (source) {
            ItemViewerRoute.SOURCE_SHOPPING -> shoppingState.items.tagCategories()
            ItemViewerRoute.SOURCE_WARDROBE -> wardrobeState.images.tagCategories()
            else -> images.tagCategories()
        }
    }

    // Every close path funnels through here exactly once: deleting/moving the last item both
    // triggers the host's close AND empties the live list — without the latch that would pop
    // twice and dismiss the screen underneath as well.
    var closed by remember { mutableStateOf(false) }
    val closeViewer: () -> Unit = {
        if (!closed) {
            closed = true
            onClose()
        }
    }
    LaunchedEffect(images.isEmpty()) {
        if (images.isEmpty()) closeViewer()
    }
    if (images.isEmpty()) return

    // First-composition snapshot — the pager only reads its initial page once.
    val initialIndex = remember {
        images.indexOfFirst { it.driveId == initialItemId }.coerceAtLeast(0)
    }

    // Wardrobe deletes go through the cascade-aware confirm (it can see affected
    // outfits/try-ons); the viewer stays open behind it and live-drops the item on confirm.
    var pendingDeleteIds by remember { mutableStateOf<Set<String>?>(null) }

    // Full-bleed background with the content inset below the status bar — matches the
    // converted outfit-viewer destination.
    Box(
        Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
            .statusBarsPadding(),
    ) {
        FullScreenViewer(
            images = images,
            initialIndex = initialIndex,
            allTagCategories = allTagCategories,
            onDismiss = closeViewer,
            onTagImage = if (isShopping) shoppingClosetViewModel::tagImage
                else wardrobeViewModel::tagImage,
            onRemoveBackground = if (isShopping) shoppingClosetViewModel::reprocessBackground
                else wardrobeViewModel::reprocessBackground,
            onRotateImage = if (isShopping) shoppingClosetViewModel::rotateImage
                else wardrobeViewModel::rotateImage,
            onUpdateTags = if (isShopping) shoppingClosetViewModel::updateTags
                else wardrobeViewModel::updateTags,
            onDeleteItem = { driveId ->
                when (source) {
                    ItemViewerRoute.SOURCE_WARDROBE -> pendingDeleteIds = setOf(driveId)
                    ItemViewerRoute.SOURCE_SHOPPING ->
                        shoppingClosetViewModel.deleteItems(setOf(driveId))
                    else -> wardrobeViewModel.deleteItems(setOf(driveId))
                }
            },
            onMoveToLocation = { ids, folderId ->
                if (isShopping) {
                    shoppingClosetViewModel.moveToCloset(
                        ids, folderId,
                        onMoved = { moved ->
                            if (moved.isNotEmpty()) wardrobeViewModel.notifyItemsMovedTo(folderId, moved)
                        },
                    )
                } else {
                    wardrobeViewModel.moveItemsToLocation(ids, folderId)
                }
            },
            onCreateOutfitFromSelection = onCreateOutfitFromSelection,
            onFixCutoutBg = if (isShopping) { _, _ -> } else wardrobeViewModel::fixCutoutBgForItem,
            onLoadOriginal = if (isShopping) shoppingClosetViewModel::ensureOriginalCached
                else wardrobeViewModel::ensureOriginalCached,
            // Try-on items mix sources, so the move affordance is withheld there (old host
            // passed no locations).
            locations = if (source == ItemViewerRoute.SOURCE_TRYON) emptyList()
                else locationState.locations,
            processingImageId = if (isShopping) shoppingState.processingImageId
                else wardrobeState.processingImageId,
            powerFeatures = com.librelookai.util.FeatureFlags.powerFeatures,
        )
    }

    pendingDeleteIds?.let { ids ->
        DeleteItemsConfirmDialog(
            ids = ids,
            images = wardrobeState.images,
            outfits = outfitsState.outfits,
            tryOns = tryOnState.history,
            onDeleteItems = wardrobeViewModel::deleteItems,
            onDeleteOutfits = outfitsViewModel::deleteOutfitsByIds,
            onDeleteTryOns = tryOnHistoryViewModel::deleteTryOns,
            onDismiss = { pendingDeleteIds = null },
        )
    }
}
