package com.librelookai.data.local

import com.librelookai.data.model.Trip

/**
 * Local source of truth for cached trips (a single global list — one `_trips/` Drive folder).
 *
 * Replaces the `trips_cache.json` snapshot TripsViewModel read/wrote. Single-instance
 * (Hilt @Singleton). On first access the store seeds itself from the legacy JSON file if one
 * exists, so an app upgrade keeps the trips list's Phase-1 paint instant with no Drive
 * re-download.
 */
interface TripStore {
    suspend fun trips(): List<Trip>

    /** Replaces the entire cached trip list (post-load / post-mutation snapshot). */
    suspend fun replaceAll(trips: List<Trip>)
}
