package com.librelookai.outfit

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import com.librelookai.OutfitViewerRoute
import com.librelookai.data.model.Outfit
import com.librelookai.data.model.Trip
import com.librelookai.data.model.WearSource
import com.librelookai.settings.ProfileViewModel
import com.librelookai.settings.UserPreferences
import com.librelookai.travel.TripsViewModel
import com.librelookai.util.LocalIsOffline
import com.librelookai.wardrobe.DriveImage
import com.librelookai.wardrobe.LocationViewModel
import com.librelookai.wardrobe.WardrobeViewModel

/**
 * Content of the [OutfitViewerRoute] NavHost destination — the fullscreen outfit pager that
 * replaced the per-host `OutfitFullScreenViewer` Dialogs (outfit list / prediction result /
 * trip day list). Outfits resolve LIVE from [OutfitsViewModel] / [OutfitGenerationViewModel] (and the trip's day list for
 * the trip source) so edits, deletes and day-outfit replacements during viewing stay correct;
 * the route only pins the viewing context.
 *
 * Per-source behavior matches the old Dialog hosts:
 *  - list: Wear files a calendar-wear request (MANUAL) and closes; try-on closes first.
 *  - prediction: closing in ANY way clears the prediction (so Home's navigate trigger can't
 *    re-fire); Wear files the request as AI_SUGGESTED.
 *  - trip: Wear opens the day-date picker (no cross-tab jump); Edit carries the trip context
 *    into the composer; try-on overlays the viewer without closing it.
 *
 * The tag-edit / tag-suggestion dialogs are hosted here too: the tab or destination that owns
 * them otherwise is not composed while this destination overlays it.
 */
@Composable
internal fun OutfitViewerDestination(
    source: String,
    routeOutfitIds: List<String>,
    initialOutfitId: String?,
    tripId: String?,
    outfitsViewModel: OutfitsViewModel,
    generationViewModel: OutfitGenerationViewModel,
    /** Navigates to the composer destination after Edit seeds the generation VM (§ 5 slice 9). */
    onOpenComposer: () -> Unit,
    wardrobeViewModel: WardrobeViewModel,
    profileViewModel: ProfileViewModel,
    outfitEventsViewModel: OutfitEventsViewModel,
    tripsViewModel: TripsViewModel,
    locationViewModel: LocationViewModel,
    canTryOn: Boolean,
    onClose: () -> Unit,
    onTryOnStyle: (Outfit) -> Unit,
    onTryOnTripOutfit: (Trip, Outfit) -> Unit,
    /** Open the item-viewer destination over this one (the outfit's items, tapped item). */
    onOpenItemViewer: (List<String>, String) -> Unit = { _, _ -> },
) {
    val isOffline = LocalIsOffline.current
    val outfitsState by outfitsViewModel.state.collectAsState()
    val generationState by generationViewModel.state.collectAsState()
    val wardrobeState by wardrobeViewModel.state.collectAsState()
    val profileState by profileViewModel.state.collectAsState()
    val locationState by locationViewModel.state.collectAsState()
    val tripsState by tripsViewModel.state.collectAsState()

    val outfitsById = remember(outfitsState.outfits) { outfitsState.outfits.associateBy { it.id } }
    // Universe of items resolving the outfits' thumbnails — same pool as the Outfits screen.
    val itemsById = remember(
        wardrobeState.allLocationImages, outfitsState.wardrobeImages, wardrobeState.images,
    ) {
        outfitItemPool(
            allLocationImages = wardrobeState.allLocationImages,
            wardrobeImages = outfitsState.wardrobeImages,
            activeImages = wardrobeState.images,
        ).associateBy { it.driveId }
    }

    val trip = if (source == OutfitViewerRoute.SOURCE_TRIP)
        tripsState.trips.find { it.id == tripId } else null

    val outfits = remember(source, routeOutfitIds, outfitsById, generationState.predictionSuggestions, trip) {
        when (source) {
            OutfitViewerRoute.SOURCE_PREDICTION ->
                generationState.predictionSuggestions.mapNotNull { outfitsById[it.outfitId] }
            OutfitViewerRoute.SOURCE_TRIP ->
                trip?.outfitIds.orEmpty().mapNotNull { outfitsById[it] }
            else -> routeOutfitIds.mapNotNull { outfitsById[it] }
        }
    }
    // First-composition snapshot — the pager only reads its initial page once.
    val initialIndex = remember {
        when (source) {
            OutfitViewerRoute.SOURCE_PREDICTION -> generationState.predictionIndex
            else -> outfits.indexOfFirst { it.id == initialOutfitId }
        }.coerceAtLeast(0)
    }

    // Every close path funnels through here exactly once: deleting the last outfit both calls
    // onDelete's close AND empties the live list (the LaunchedEffect below) — without the latch
    // that would pop twice and dismiss the screen underneath as well.
    var closed by remember { mutableStateOf(false) }
    val closeViewer: () -> Unit = {
        if (!closed) {
            closed = true
            if (source == OutfitViewerRoute.SOURCE_PREDICTION) generationViewModel.clearPrediction()
            onClose()
        }
    }
    LaunchedEffect(outfits.isEmpty()) {
        if (outfits.isEmpty()) closeViewer()
    }

    // Trip-source Wear picks the day to log onto (the trip viewer kept its date-picker UX —
    // a cross-tab jump to the calendar would be jarring from the Travel flow).
    var wearPickerOutfit by remember { mutableStateOf<Outfit?>(null) }

    val prefs = profileState.preferences
    // Full-bleed background with the content inset below the status bar — matches the old
    // Dialog window, which painted edge-to-edge but laid its content out under the bar.
    Box(
        Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
            .statusBarsPadding(),
    ) {
    OutfitFullScreenViewer(
        outfits = outfits,
        initialIndex = initialIndex,
        itemsById = itemsById,
        locations = locationState.locations,
        onDismiss = closeViewer,
        onEdit = { o ->
            closeViewer()
            if (trip != null) {
                generationViewModel.startEditingTripOutfit(trip, o, wardrobeState.images, prefs)
            } else {
                generationViewModel.startEditing(o, wardrobeState.images, prefs)
            }
            onOpenComposer()
        },
        onWear = { o ->
            when {
                trip != null -> wearPickerOutfit = o
                else -> {
                    val wearSource = if (source == OutfitViewerRoute.SOURCE_PREDICTION)
                        WearSource.AI_SUGGESTED else WearSource.MANUAL
                    outfitsViewModel.requestCalendarWear(o.id, wearSource)
                    // The Outfits screen flips to its Calendar sub-tab on the pending request.
                    closeViewer()
                }
            }
        },
        onToggleLoved = { o -> outfitsViewModel.setOutfitLoved(o.id, !o.loved) },
        onDelete = { o ->
            outfitsViewModel.deleteOutfit(o.id)
            if (outfits.size <= 1) closeViewer()
        },
        onSuggestTags = { o ->
            generationViewModel.suggestTagsForOutfit(o, wardrobeState.images, prefs)
        },
        onEditTags = { o -> generationViewModel.openOutfitTagsEditor(o.id) },
        onTryOn = { o ->
            if (trip != null) {
                // Try-on composer overlays the viewer; closing it returns here (old behavior).
                onTryOnTripOutfit(trip, o)
            } else {
                closeViewer()
                onTryOnStyle(o)
            }
        },
        canTryOn = if (trip != null) canTryOn && !isOffline else canTryOn,
        onOpenItemViewer = onOpenItemViewer,
    )
    }

    wearPickerOutfit?.let { o ->
        WearDatePickerDialog(
            onDismiss = { wearPickerOutfit = null },
            onConfirm = { date ->
                outfitEventsViewModel.recordOutfit(o, itemsById, date = date)
                wearPickerOutfit = null
            },
        )
    }

    // Tag-edit dialog launched by tapping the tags row in the viewer.
    generationState.tagEditingOutfitId?.let { editId ->
        outfitsState.outfits.find { it.id == editId }?.let { target ->
            EditOutfitTagsDialog(
                initialTags = target.tags,
                onDismiss = generationViewModel::closeOutfitTagsEditor,
                onSave = { newTags -> generationViewModel.setOutfitTags(editId, newTags) },
            )
        }
    }

    // AI tag-suggestion dialog launched from the viewer.
    generationState.tagSuggestion?.let { sugg ->
        SuggestTagsDialog(
            state = sugg,
            onDismiss = generationViewModel::dismissTagSuggestions,
            onApply = { selected -> generationViewModel.applyTagSuggestions(sugg.outfitId, selected) },
        )
    }
}

/**
 * Opens the composer to edit [outfit] as [trip]'s day outfit, carrying the trip's context.
 * Shared by the viewer destination's Edit action and the trip viewer's per-day edit pen.
 */
internal fun OutfitGenerationViewModel.startEditingTripOutfit(
    trip: Trip,
    outfit: Outfit,
    images: List<DriveImage>,
    prefs: UserPreferences?,
) {
    val dayIdx = trip.outfitIds.indexOf(outfit.id).coerceAtLeast(0)
    startEditing(
        style       = outfit,
        images      = images,
        prefs       = prefs,
        tripContext = TripContext(
            tripId         = trip.id,
            tripName       = trip.name,
            dayIndex       = dayIdx,
            dayForecast    = trip.forecast.getOrNull(dayIdx),
            tripStartDate  = trip.startDate,
            considerations = trip.considerations,
            vibes          = trip.vibes,
            goal           = trip.goal,
        ),
    )
}
