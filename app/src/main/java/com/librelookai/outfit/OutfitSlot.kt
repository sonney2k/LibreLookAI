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
        cat.contains("top") || cat.contains("shirt") || cat == "dress" || cat == "suit" -> Layer.Top
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

/** Whether the composer is in read-only view or editing mode. */
enum class ComposerMode { VIEW, EDIT }

/** Who opened the AI-prediction setup sheet. */
enum class PredictionSetupSource { OUTFITS_LIST, COMPOSER }
