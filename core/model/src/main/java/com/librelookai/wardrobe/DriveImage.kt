package com.librelookai.wardrobe

import com.librelookai.gemini.ClothingTags

/**
 * One wardrobe item as the whole app sees it — the central data class. Lives in `:core:model`
 * (it is referenced by `:core:ml` and nearly every feature package); kept in the
 * `com.librelookai.wardrobe` package so existing import sites stay untouched.
 */
data class DriveImage(
    val driveId: String,
    val localPath: String,
    val name: String,
    val tags: ClothingTags? = null,
    /** Bumped on every local reprocess so Coil knows to reload from disk. */
    val version: Long = 0L,
    /** Drive file ID of the unprocessed original, if one was saved to Drive. */
    val originalDriveId: String? = null,
    /** Drive file ID of the per-item sidecar JSON (named "{driveId}.json"). */
    val sidecarDriveId: String? = null,
    /** Drive folder ID this item actually lives in. */
    val folderId: String = "",
    /** Drive's `createdTime` for the cutout file, in millis. 0 when unknown (legacy cache). */
    val createdTimeMs: Long = 0L,
)
