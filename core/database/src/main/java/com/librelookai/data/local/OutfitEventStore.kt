package com.librelookai.data.local

import com.librelookai.data.model.OutfitEvent
import kotlinx.coroutines.flow.Flow

/**
 * Local source of truth for cached calendar wear events, per closet folder.
 *
 * Replaces the per-folder `outfit_events_cache_{folderId}.json` files OutfitEventsViewModel
 * read/wrote. Single-instance (Hilt @Singleton). On first access per folder the store seeds
 * itself from a legacy JSON cache file if one exists, so an app upgrade keeps the calendar's
 * Phase-1 paint instant with no Drive re-download.
 */
interface OutfitEventStore {
    suspend fun eventsFor(folderId: String): List<OutfitEvent>

    /**
     * Live view of the cached events across [folderIds]. Emits the current rows immediately
     * (running each folder's one-time legacy seed first) and again on every write to the
     * events table.
     */
    fun observeFolders(folderIds: List<String>): Flow<List<OutfitEvent>>

    /** Replaces the folder's entire cached event list (post-load / post-mutation snapshot). */
    suspend fun replaceFolder(folderId: String, events: List<OutfitEvent>)

    /**
     * Whether [folderId] has ever been cached (marker row written by [replaceFolder] or the
     * legacy seed). The `outfitEvent.syncFolder` mutation handler refuses to write Drive for a
     * never-cached folder — an empty read there means "unknown", not "no wears", and writing
     * `[]` would destroy the user's calendar history.
     */
    suspend fun hasFolder(folderId: String): Boolean
}
