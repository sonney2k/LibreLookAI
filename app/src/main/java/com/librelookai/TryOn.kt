package com.librelookai

import java.util.UUID

/**
 * A saved try-on. The generated PNG lives in the [_tryons] subfolder of the root Drive folder;
 * the metadata index is stored as [_tryons.json] at the root.
 *
 * [itemNames] holds the cutout filenames of the wardrobe items that were worn in the
 * generation — filenames survive a Drive-folder copy across accounts (unlike Drive IDs),
 * so after loading [itemIds] are resolved from the current folder listing.
 */
data class TryOn(
    val id: String = UUID.randomUUID().toString(),
    /** Drive file ID of the generated PNG (in the _tryons subfolder). */
    val imageDriveId: String = "",
    /** Drive filename of the generated PNG — used as stable handle across re-logins. */
    val imageName: String = "",
    /** Stable cutout filenames of the items included in this try-on. */
    val itemNames: List<String> = emptyList(),
    val createdAt: Long = System.currentTimeMillis(),
    /** Runtime-resolved Drive IDs of the items — not persisted. */
    @Transient val itemIds: List<String> = emptyList(),
    /** Runtime local path of the cached PNG — not persisted. */
    @Transient val localPath: String = "",
)
