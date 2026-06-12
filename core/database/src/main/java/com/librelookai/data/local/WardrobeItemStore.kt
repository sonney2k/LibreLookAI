package com.librelookai.data.local

import com.librelookai.gemini.ClothingTags
import kotlinx.coroutines.flow.Flow

/**
 * One cached wardrobe item — the Room-backed successor of the per-folder
 * `wardrobe_cache_{folderId}.json` entries. Carries everything Phase 1 needs to paint a closet
 * without the network; the image bytes themselves stay in the Drive download cache, keyed by
 * [driveId].
 */
data class CachedWardrobeItem(
    val driveId: String,
    val name: String,
    val tags: ClothingTags?,
    val originalDriveId: String? = null,
    val sidecarDriveId: String? = null,
    val createdTimeMs: Long = 0L,
)

/**
 * Local source of truth for cached wardrobe items across all closets (wardrobe, shopping).
 *
 * Replaces the per-folder `wardrobe_cache_*.json` files that WardrobeViewModel,
 * OutfitsViewModel and ShoppingClosetViewModel each read/wrote independently. Single-instance
 * (Hilt @Singleton), so every feature observes the same data and concurrent edits go through
 * one SQLite connection instead of racing on whole-file rewrites.
 *
 * Invariant the old files could not enforce: **an item lives in exactly one folder** (primary
 * key is the Drive ID). Upserting an item under a new folder atomically removes it from its
 * previous one — mirroring what is true on Drive after a move.
 *
 * On first access per folder the store seeds itself from a legacy JSON cache file if one
 * exists, so an app upgrade keeps Phase 1 instant with no Drive re-download.
 */
interface WardrobeItemStore {
    /** True once the folder has been cached at least once (legacy file seeded or a full load saved). */
    suspend fun hasFolder(folderId: String): Boolean

    suspend fun itemsFor(folderId: String): List<CachedWardrobeItem>

    /**
     * Live view of the cached items across [folderIds], keyed by owning folder id. Emits the
     * current rows immediately (running each folder's one-time legacy seed first) and again on
     * every write to the items table. Folders with no cached items are absent from the map.
     */
    fun observeItems(folderIds: List<String>): Flow<Map<String, List<CachedWardrobeItem>>>

    /** Replaces the folder's entire cached item list (the post-load full snapshot write). */
    suspend fun replaceFolder(folderId: String, items: List<CachedWardrobeItem>)

    /**
     * Upserts one finished item into its owning folder, dropping any stale entry under
     * [staleDriveId] (the pre-cutout raw upload id) first.
     */
    suspend fun upsert(folderId: String, item: CachedWardrobeItem, staleDriveId: String? = null)

    /** Upserts [items] into [folderId] (each item is pulled out of any other folder it was in). */
    suspend fun addAll(folderId: String, items: List<CachedWardrobeItem>)

    /** Removes the given drive IDs from [folderId] only (no-op for ids homed elsewhere). */
    suspend fun remove(folderId: String, ids: Set<String>)

    /**
     * The cached item homed under [driveId] with its owning folder id, or null when not cached.
     * Does **not** trigger the per-folder legacy seed (callers are sync paths whose enqueuer
     * already wrote the row).
     */
    suspend fun find(driveId: String): Pair<String, CachedWardrobeItem>?

    /** Stamps the Drive sidecar file id onto the item's row (SyncEngine post-apply feedback). */
    suspend fun setSidecarId(driveId: String, sidecarId: String)

    /** Drops the folder's items and its cached-marker, plus any legacy JSON file (no re-seed). */
    suspend fun clearFolder(folderId: String)
}
