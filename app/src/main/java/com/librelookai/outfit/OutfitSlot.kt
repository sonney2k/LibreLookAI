package com.librelookai.outfit

import com.librelookai.R
import com.librelookai.wardrobe.DriveImage

/** Garment layers, in display order top→bottom in the look board. */
enum class Layer(
    val labelRes: Int,
    val iconRes: Int,
) {
    Outerwear(R.string.outfit_layer_outerwear, R.drawable.ic_layer_jacket),
    Top(R.string.outfit_layer_tops,            R.drawable.ic_layer_shirt),
    OnePiece(R.string.outfit_layer_onepiece,   R.drawable.ic_layer_dress),
    Bottom(R.string.outfit_layer_bottoms,      R.drawable.ic_layer_pants),
    Footwear(R.string.outfit_layer_footwear,   R.drawable.ic_layer_shoe),
    Accessory(R.string.outfit_layer_accessories, R.drawable.ic_layer_bag),
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
 * A filled [Layer.OnePiece] slot satisfies both, so it is mutually exclusive with these.
 */
val ONE_PIECE_COVERS = setOf(Layer.Top, Layer.Bottom)

/** True when filling a slot of category [a] should clear a slot of category [b] (and vice versa). */
fun onePieceConflict(a: Layer, b: Layer): Boolean =
    (a == Layer.OnePiece && b in ONE_PIECE_COVERS) ||
        (b == Layer.OnePiece && a in ONE_PIECE_COVERS)

/**
 * Enforces the one-piece ↔ top/bottom exclusion for the AI/bulk path (no single "just touched"
 * slot). A locked side wins; otherwise a filled one-piece wins. The losing side's items are
 * cleared so an outfit never carries a dress alongside a separate top or bottom.
 */
fun normalizeOnePieceExclusion(slots: List<OutfitSlot>): List<OutfitSlot> {
    fun anyFilled(filter: (OutfitSlot) -> Boolean) = slots.any { it.selectedItemId != null && filter(it) }
    val lockedOnePiece = anyFilled { it.isLocked && it.category == Layer.OnePiece }
    val lockedCovered = anyFilled { it.isLocked && it.category in ONE_PIECE_COVERS }
    val keepOnePiece = when {
        lockedOnePiece -> true
        lockedCovered -> false
        else -> anyFilled { it.category == Layer.OnePiece }
    }
    return slots.map { slot ->
        when {
            keepOnePiece && slot.category in ONE_PIECE_COVERS -> slot.copy(selectedItemId = null, isLocked = false)
            !keepOnePiece && slot.category == Layer.OnePiece -> slot.copy(selectedItemId = null, isLocked = false)
            else -> slot
        }
    }
}

/**
 * Hides empty slots made redundant by a one-piece: when a [Layer.OnePiece] is filled, empty
 * Top/Bottom slots collapse away; when a Top or Bottom is filled, an empty OnePiece slot
 * collapses away. Filled slots are always kept. Used for rendering and completeness so an
 * outfit built from a dress (+ shoes) reads as complete without dangling empty Top/Bottom rows.
 */
fun collapseOnePieceSlots(slots: List<OutfitSlot>): List<OutfitSlot> {
    val onePieceFilled = slots.any { it.category == Layer.OnePiece && it.selectedItemId != null }
    val coveredFilled = slots.any { it.category in ONE_PIECE_COVERS && it.selectedItemId != null }
    return slots.filter { slot ->
        when {
            slot.selectedItemId != null -> true
            slot.category in ONE_PIECE_COVERS && onePieceFilled -> false
            slot.category == Layer.OnePiece && coveredFilled -> false
            else -> true
        }
    }
}

/** Whether the composer is in read-only view or editing mode. */
enum class ComposerMode { VIEW, EDIT }

/** Who opened the AI-prediction setup sheet. */
enum class PredictionSetupSource { OUTFITS_LIST, COMPOSER }
