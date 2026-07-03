package com.librelookai

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CloudOff
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.navigation.NavDestination
import androidx.navigation.NavDestination.Companion.hasRoute
import kotlinx.serialization.Serializable

/**
 * Type-safe routes for the root NavHost (hosted in [AppContent]).
 *
 * [HomeRoute] is the whole pre-navigation app: the Scaffold with the bottom nav bar and the
 * `when(selectedTab)` tab dispatch. Converted screens become sibling destinations that overlay
 * it full-screen (covering the bottom bar) — replacing the fullscreen-Dialog / hoisted-state
 * patterns one at a time (plan/refactor.md phase 3).
 */
@Serializable
internal data object HomeRoute

/**
 * Home's five tabs as destinations of the nested tab NavHost inside [HomeRoute]'s Scaffold
 * (the bottom bar stays put; a tab switch swaps only the content above it). The nested back
 * stack is kept at a single entry — switching tabs *replaces* rather than stacks, so system
 * back still leaves the app from any tab (the pre-navigation `selectedTab` behavior).
 */
@Serializable
internal data object OutfitsTabRoute

@Serializable
internal data object WardrobeTabRoute

@Serializable
internal data object ShoppingTabRoute

@Serializable
internal data object TravelTabRoute

@Serializable
internal data object SettingsTabRoute

/**
 * Settings sub-screens — nested tab-graph destinations pushed over [SettingsTabRoute]
 * (replacing the old local `SettingsRoute` enum back-stack, so system back pops them).
 * They map to nav-slot 5 in [homeTabIndex] so the bar keeps Settings highlighted.
 */
@Serializable
internal data object SettingsProfileEditRoute

@Serializable
internal data object SettingsAdvancedRoute

@Serializable
internal data object SettingsAboutRoute

@Serializable
internal data object SettingsUsageRoute

@Serializable
internal data object SettingsBuyCreditsRoute

/** Nav-slot index ([AppNavBar]) → tab route. Index 4 is the dead center slot; 5 = Settings. */
internal fun homeTabRoute(tab: Int): Any = when (tab) {
    1 -> WardrobeTabRoute
    2 -> ShoppingTabRoute
    3 -> TravelTabRoute
    5 -> SettingsTabRoute
    else -> OutfitsTabRoute
}

/** Current nested tab destination → nav-slot index (for the bar's active indicator). */
internal fun homeTabIndex(destination: NavDestination?): Int = when {
    destination == null -> 0
    destination.hasRoute<WardrobeTabRoute>() -> 1
    destination.hasRoute<ShoppingTabRoute>() -> 2
    destination.hasRoute<TravelTabRoute>() -> 3
    destination.hasRoute<SettingsTabRoute>() ||
        destination.hasRoute<SettingsProfileEditRoute>() ||
        destination.hasRoute<SettingsAdvancedRoute>() ||
        destination.hasRoute<SettingsAboutRoute>() ||
        destination.hasRoute<SettingsUsageRoute>() ||
        destination.hasRoute<SettingsBuyCreditsRoute>() -> 5
    else -> 0
}

/**
 * Trip detail viewer — the first Dialog-era fullscreen mode converted to a destination, and
 * the first with a **destination-scoped** trips VM (§ 5 slice 9). The cross-surface hand-offs
 * that used to ride `TripsUiState` state fields are route arguments now: [startInEdit] (the
 * trips list's single-select "Edit" action) and [justSaved] (the planner's auto-created trip
 * shows a one-time "saved" confirmation).
 */
@Serializable
internal data class TripViewerRoute(
    val tripId: String,
    val startInEdit: Boolean = false,
    val justSaved: Boolean = false,
)

/**
 * Travel planner — replaces the hoisted `travelPlannerMode` flag that rendered the planner
 * inside the Travel tab with Home's chrome hidden. Planner-created trips pop this destination
 * and navigate to [TripViewerRoute].
 */
@Serializable
internal data object TravelPlannerRoute

/**
 * Fullscreen outfit detail viewer (pager) — replaces the `OutfitFullScreenViewer` Dialog hosts.
 * Outfits resolve LIVE from the ViewModels at the destination (edits/deletes during viewing stay
 * correct); the route only carries the viewing context:
 *  - [SOURCE_LIST]: [outfitIds] = the filtered list in display order, snapshotted at tap time.
 *  - [SOURCE_PREDICTION]: outfits come from `predictionSuggestions`; closing clears the prediction.
 *  - [SOURCE_TRIP]: outfits come from [tripId]'s day list (so a day-edit's id swap stays in sync).
 */
@Serializable
internal data class OutfitViewerRoute(
    val source: String,
    val outfitIds: List<String> = emptyList(),
    val initialOutfitId: String? = null,
    val tripId: String? = null,
) {
    companion object {
        const val SOURCE_LIST = "list"
        const val SOURCE_PREDICTION = "prediction"
        const val SOURCE_TRIP = "trip"
    }
}

/**
 * Fullscreen item detail viewer (pager) — replaces the `FullScreenViewer` Dialog hosts
 * (wardrobe grid / shopping list / try-on detail / outfit-composer stack). Items resolve LIVE
 * from the ViewModels at the destination (tag edits, deletes and moves during viewing stay
 * correct); the route only carries the viewing context:
 *  - [SOURCE_WARDROBE] / [SOURCE_SHOPPING]: [itemIds] = the filtered grid/list in display
 *    order, snapshotted at tap time, resolved against the owning VM.
 *  - [SOURCE_TRYON]: [itemIds] = the viewed try-on's items, resolved against the combined
 *    wardrobe + shopping pool (the try-on feed mixes both).
 *  - [SOURCE_OUTFIT]: [itemIds] = the viewed outfit's items, resolved against the same item
 *    pool the outfit viewer renders from.
 *  - [SOURCE_COMPOSER]: items come live from the composer's slots (a slot swap while viewing
 *    stays in sync); [itemIds] is unused.
 */
@Serializable
internal data class ItemViewerRoute(
    val source: String,
    val itemIds: List<String> = emptyList(),
    val initialItemId: String? = null,
) {
    companion object {
        const val SOURCE_WARDROBE = "wardrobe"
        const val SOURCE_SHOPPING = "shopping"
        const val SOURCE_TRYON = "tryon"
        const val SOURCE_OUTFIT = "outfit"
        const val SOURCE_COMPOSER = "composer"
    }
}

/**
 * Try-on composer (assemble items → generate → result preview, plus the no-photos empty
 * state). **Real navigation** (§ 5 slice 9 — the old single layered Dialog/route split into
 * three destinations): openers seed `TryOnViewModel`'s draft (`openComposer`) then navigate
 * here; the header ✕ / system back clear the draft (`close()`) and pop.
 */
@Serializable
internal data object TryOnRoute

/** Past-try-ons hero feed. Tapping a tile pushes [TryOnDetailRoute]; the hero's edit action
 *  seeds the composer draft and pushes [TryOnRoute] (back returns to the feed). */
@Serializable
internal data object TryOnHistoryRoute

/** Fullscreen pager over the (live, store-derived) try-on history, starting at
 *  [imageDriveId]. Deleting the last entry pops the destination. */
@Serializable
internal data class TryOnDetailRoute(val imageDriveId: String)

/**
 * Outfit composer (create / edit / AI-suggest) — replaces the global `OutfitComposerScreen`
 * Dialog. **Real navigation** (§ 5 slice 9, unlike [TryOnRoute]'s state mirror): openers seed
 * `OutfitGenerationViewModel`'s draft state (`openComposer`/`startEditing`), then navigate here;
 * every close path (header ✕ / system back — both behind the edit-mode discard-confirm — and a
 * successful save) clears the draft via `closeComposer()` and pops the destination.
 */
@Serializable
internal data object OutfitComposerRoute

/**
 * Slim offline indicator strip shown above the active screen. Shared by [HomeRoute] (above the
 * tab content) and full-screen destinations, which no longer sit under Home's banner.
 */
@Composable
internal fun OfflineBanner(visible: Boolean) {
    AnimatedVisibility(visible = visible) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .background(MaterialTheme.colorScheme.errorContainer)
                .padding(horizontal = 16.dp, vertical = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.Center,
        ) {
            Icon(
                Icons.Default.CloudOff,
                contentDescription = null,
                modifier = Modifier.size(16.dp),
                tint = MaterialTheme.colorScheme.onErrorContainer,
            )
            Spacer(Modifier.width(8.dp))
            Text(
                stringResource(R.string.offline_banner),
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onErrorContainer,
            )
        }
    }
}
