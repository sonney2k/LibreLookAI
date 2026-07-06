package com.librelookai.wardrobe

import com.librelookai.data.local.CachedWardrobeItem

/**
 * Maps a wardrobe item to its Room-backed cache row ([CachedWardrobeItem] in
 * `WardrobeItemStore`). The local path is not persisted — it is re-derived from the Drive
 * download cache on read.
 */
fun DriveImage.toCachedItem() = CachedWardrobeItem(
    driveId = driveId,
    name = name,
    tags = tags,
    originalDriveId = originalDriveId,
    sidecarDriveId = sidecarDriveId,
    createdTimeMs = createdTimeMs,
)
