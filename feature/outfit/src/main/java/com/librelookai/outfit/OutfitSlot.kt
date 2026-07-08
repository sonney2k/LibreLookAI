package com.librelookai.outfit

import com.librelookai.core.designsystem.R as DsR
import com.librelookai.wardrobe.DriveImage

/** Garment layers, in display order top→bottom in the look board. */
enum class Layer(
    val labelRes: Int,
    val iconRes: Int,
) {
    Outerwear(DsR.string.outfit_layer_outerwear, DsR.drawable.ic_layer_jacket),
    Top(DsR.string.outfit_layer_tops,            DsR.drawable.ic_layer_shirt),
    OnePiece(DsR.string.outfit_layer_onepiece,   DsR.drawable.ic_layer_dress),
    Bottom(DsR.string.outfit_layer_bottoms,      DsR.drawable.ic_layer_pants),
    Footwear(DsR.string.outfit_layer_footwear,   DsR.drawable.ic_layer_shoe),
    Accessory(DsR.string.outfit_layer_accessories, DsR.drawable.ic_layer_bag),
}

/** Map a wardrobe item to a layer slot using its category (best-effort). */
fun layerFor(image: DriveImage): Layer? {
    val cat = image.tags?.category?.lowercase().orEmpty()
    return when {
        cat.contains("outer") -> Layer.Outerwear
        cat.contains("foot") || cat.contains("shoe") -> Layer.Footwear
        cat.contains("bottom") || cat == "pants" || cat == "skirt" -> Layer.Bottom
        cat.contains("accessor") -> Layer.Accessory
        cat == "dress" || cat == "suit" || cat == "jumpsuit" || cat == "gown" || cat == "romper" -> Layer.OnePiece
        cat.contains("top") || cat.contains("shirt") -> Layer.Top
        else -> null
    }
}

/** A single slot in the composer's explicit slot list. */
data class OutfitSlot(
    val id: String,
    val category: Layer,
    val selectedItemId: String?,
    val isLocked: Boolean,
    val aiReason: String? = null,
)

/**
 * The body regions a one-piece (Einteiler — dress, gown, jumpsuit, romper, suit) covers.
 * A one-piece and a separate Top/Bottom are NOT mutually exclusive — the composer allows layering
 * a top/bottom with a dress. This set is kept only so [wardrobeForSlots] offers candidates for the
 * other side (a OnePiece slot still surfaces top/bottom items as alternatives, and vice versa).
 */
val ONE_PIECE_COVERS = setOf(Layer.Top, Layer.Bottom)

/**
 * Narrows a wardrobe to just the items that could plausibly fill one of [slots], so the composer
 * prompt doesn't ship every item for a handful of slots (big token saving on large wardrobes
 * without changing model behaviour — the model still picks per slot from what it receives).
 *
 * The relevant-[Layer] set is the slots' own categories, expanded across the one-piece mutual
 * exclusion both ways: a [Layer.OnePiece] slot may be satisfied by a separate Top+Bottom, and a
 * Top/Bottom slot may be satisfied by a one-piece (dress/suit) — so candidates for the other side
 * are kept too. Items we can't classify ([layerFor] == null) and any item already locked into a
 * slot are always kept, so the filter can never drop something usable. Empty [slots] ⇒ no filter.
 */
fun wardrobeForSlots(images: List<DriveImage>, slots: List<OutfitSlot>): List<DriveImage> {
    if (slots.isEmpty()) return images
    val slotLayers = slots.map { it.category }.toSet()
    val relevantLayers = buildSet {
        addAll(slotLayers)
        if (Layer.OnePiece in slotLayers) addAll(ONE_PIECE_COVERS)
        if (slotLayers.any { it in ONE_PIECE_COVERS }) add(Layer.OnePiece)
    }
    val lockedIds = slots.mapNotNull { it.selectedItemId }.toSet()
    return images.filter { img ->
        img.driveId in lockedIds || layerFor(img).let { it == null || it in relevantLayers }
    }
}

/** Whether the composer is in read-only view or editing mode. */
enum class ComposerMode { VIEW, EDIT }

/** Who opened the AI-prediction setup sheet. */
enum class PredictionSetupSource { OUTFITS_LIST, COMPOSER }
