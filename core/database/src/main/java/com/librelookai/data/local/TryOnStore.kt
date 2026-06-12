package com.librelookai.data.local

import com.librelookai.data.model.TryOn
import kotlinx.coroutines.flow.Flow

/**
 * Local source of truth for cached try-on metadata (a single global list — one `_tryons.json`
 * at the Drive root). The generated images stay as files in the Drive cache dir
 * (`tryon_{imageDriveId}.png`) — the store holds metadata only, same rule as wardrobe.
 *
 * Replaces the `tryons_cache.json` snapshot TryOnViewModel read/wrote. Single-instance
 * (Hilt @Singleton). On first access the store seeds itself from the legacy JSON file if one
 * exists, so an app upgrade keeps the history grid's Phase-1 paint instant with no Drive
 * re-download.
 */
interface TryOnStore {
    suspend fun tryOns(): List<TryOn>

    /**
     * Live view of the cached try-on list. Emits the current rows immediately (running the
     * one-time legacy seed first) and again on every write to the try-ons table.
     */
    fun observeTryOns(): Flow<List<TryOn>>

    /** Replaces the entire cached try-on list (post-load / post-mutation snapshot). */
    suspend fun replaceAll(tryOns: List<TryOn>)
}
