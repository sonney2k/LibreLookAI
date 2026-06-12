package com.librelookai.data.session

import com.librelookai.data.model.Location
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update

/**
 * App-wide closet topology (refactor § 5 slice 1): which closets exist, which one the closet
 * filter selects, where imports land, and where the shopping closet lives.
 *
 * One [ClosetSessionHolder] replaces the AppContent `LaunchedEffect` that fanned these values
 * out into the wardrobe / outfits / events ViewModels as setter calls: [com.librelookai.wardrobe.LocationViewModel]
 * and [com.librelookai.shopping.ShoppingClosetViewModel] publish (they stay the owners of how
 * the values are computed), consumers collect [ClosetSessionHolder.session] in `init` and
 * derive their load scope from it.
 */
data class ClosetSession(
    val locations: List<Location> = emptyList(),
    /** Closet filter; starts at [ALL_LOCATIONS_ID] every launch (never persisted). */
    val activeLocationId: String = ALL_LOCATIONS_ID,
    /** Persisted default closet (import target / outfit-source default); null while loading. */
    val defaultClosetFolderId: String? = null,
    /** The `_shopping/` Drive folder id, once ShoppingClosetViewModel has resolved it. */
    val shoppingFolderId: String? = null,
) {
    /** Every configured closet folder, in display order. Never includes the shopping folder. */
    val closetFolderIds: List<String> get() = locations.map { it.folderId }

    /** The active closet's folder id, or null in All-locations mode / while it isn't known yet. */
    val activeFolderId: String?
        get() = activeLocationId
            .takeIf { it != ALL_LOCATIONS_ID && it.isNotEmpty() }
            ?.takeIf { id -> locations.any { it.folderId == id } }

    /** Closets + shopping — the cross-closet similarity-snapshot scope. */
    val snapshotFolderIds: List<String>
        get() = closetFolderIds + listOfNotNull(shoppingFolderId)

    /** Where imports land: the persisted default closet, falling back to the first location. */
    val defaultImportFolderId: String?
        get() = defaultClosetFolderId?.takeIf { it in closetFolderIds } ?: closetFolderIds.firstOrNull()

    /** Where new outfits save: the active closet, falling back to the first location. */
    val saveFolderId: String? get() = activeFolderId ?: closetFolderIds.firstOrNull()

    companion object {
        const val ALL_LOCATIONS_ID = "all_locations"
    }
}

@Singleton
class ClosetSessionHolder @Inject constructor() {
    private val _session = MutableStateFlow(ClosetSession())
    val session: StateFlow<ClosetSession> = _session.asStateFlow()

    /** Publisher for LocationViewModel — closet list, filter selection, default closet. */
    fun setClosets(locations: List<Location>, activeLocationId: String, defaultClosetFolderId: String?) =
        _session.update {
            it.copy(
                locations = locations,
                activeLocationId = activeLocationId,
                defaultClosetFolderId = defaultClosetFolderId,
            )
        }

    /** Publisher for ShoppingClosetViewModel — the resolved `_shopping/` folder id. */
    fun setShoppingFolder(folderId: String?) =
        _session.update { it.copy(shoppingFolderId = folderId) }
}
