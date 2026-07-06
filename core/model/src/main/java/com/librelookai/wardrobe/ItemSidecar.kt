package com.librelookai.wardrobe

import com.librelookai.gemini.ClothingTags

/**
 * Per-item sidecar metadata — the JSON shape of the `{cutoutDriveId}.json` Drive file that
 * rides next to each item's cutout/original pair.
 */
data class ItemSidecar(val tags: ClothingTags? = null, val originalDriveId: String? = null)
