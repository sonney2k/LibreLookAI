package com.librelookai.data.local

import com.librelookai.data.model.Outfit
import kotlinx.coroutines.flow.Flow

/**
 * Local source of truth for cached outfits, per closet folder.
 *
 * Replaces the per-folder `styles_cache_{folderId}.json` files OutfitsViewModel read/wrote.
 * Single-instance (Hilt @Singleton). The primary key is the outfit id, so **an outfit is homed
 * in exactly one folder** — the old files duplicated freshly created (empty-`folderId`) outfits
 * into every folder's cache, which surfaced them twice in the all-locations Phase-1 read.
 *
 * Unlike the legacy files (which lost the `@Transient` `Outfit.folderId` entirely), reads
 * return outfits with `folderId` restored from the row, so Phase 1 paints fully-attributed
 * outfits before the Drive sync.
 *
 * On first access per folder the store seeds itself from a legacy JSON cache file if one
 * exists, so an app upgrade keeps Phase 1 instant with no Drive re-download.
 */
interface OutfitStore {
    /** All cached outfits homed in [folderId], with [Outfit.folderId] restored. */
    suspend fun outfitsFor(folderId: String): List<Outfit>

    /**
     * Live view of the cached outfits across [folderIds], with [Outfit.folderId] restored.
     * Emits the current rows immediately (running each folder's one-time legacy seed first)
     * and again on every write to the outfits table.
     */
    fun observeFolders(folderIds: List<String>): Flow<List<Outfit>>

    /** Replaces the folder's entire cached outfit list (post-load / post-mutation snapshot). */
    suspend fun replaceFolder(folderId: String, outfits: List<Outfit>)

    /**
     * Whether [folderId] has ever been cached (marker row written by [replaceFolder] or the
     * legacy seed). The `outfit.syncFolder` mutation handler refuses to write Drive for a
     * never-cached folder — an empty read there means "unknown", not "no outfits", and writing
     * `[]` would destroy the user's outfits.
     */
    suspend fun hasFolder(folderId: String): Boolean
}
