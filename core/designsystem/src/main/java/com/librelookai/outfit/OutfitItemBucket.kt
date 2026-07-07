package com.librelookai.outfit

import com.librelookai.core.designsystem.R
import com.librelookai.wardrobe.DriveImage

/**
 * Category buckets used to group items in outfit-building surfaces (the outfit viewer's
 * item strip, [AddItemSheet]'s grouped grid, the try-on composer). Ordered top-down the
 * way an outfit is worn.
 */
enum class OutfitItemBucket(val resId: Int) {
    Outerwear(R.string.outfit_layer_outerwear),
    Top(R.string.outfit_layer_tops),
    OnePiece(R.string.outfit_layer_onepiece),
    Bottom(R.string.outfit_layer_bottoms),
    Footwear(R.string.outfit_layer_footwear),
    Accessory(R.string.outfit_layer_accessories),
    Other(R.string.outfit_layer_other),
}

fun bucketFor(image: DriveImage): OutfitItemBucket {
    val cat = image.tags?.category?.lowercase().orEmpty()
    return when {
        cat.contains("outer") -> OutfitItemBucket.Outerwear
        cat.contains("foot") || cat.contains("shoe") -> OutfitItemBucket.Footwear
        cat.contains("bottom") || cat == "pants" || cat == "skirt" -> OutfitItemBucket.Bottom
        cat.contains("accessor") -> OutfitItemBucket.Accessory
        cat == "dress" || cat == "suit" || cat == "jumpsuit" || cat == "gown" || cat == "romper" -> OutfitItemBucket.OnePiece
        cat.contains("top") || cat.contains("shirt") -> OutfitItemBucket.Top
        cat.isEmpty() -> OutfitItemBucket.Other
        else -> OutfitItemBucket.Other
    }
}
