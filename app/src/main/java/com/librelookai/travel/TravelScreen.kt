package com.librelookai.travel

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Place
import androidx.compose.material3.AssistChip
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import com.librelookai.R
import com.librelookai.data.model.Outfit
import com.librelookai.data.model.PackingList
import com.librelookai.data.model.Trip
import com.librelookai.outfit.OutfitsViewModel
import com.librelookai.settings.ProfileViewModel
import com.librelookai.wardrobe.LocationViewModel
import com.librelookai.wardrobe.WardrobeViewModel

@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
fun TravelScreen(
    travelViewModel: TravelViewModel = viewModel(),
    tripsViewModel: TripsViewModel = viewModel(),
    wardrobeViewModel: WardrobeViewModel = viewModel(),
    profileViewModel: ProfileViewModel = viewModel(),
    stylesViewModel: OutfitsViewModel = viewModel(),
    locationViewModel: LocationViewModel = viewModel(),
    onSettingsClick: () -> Unit = {},
    plannerMode: Boolean = false,
    onPlannerModeChange: (Boolean) -> Unit = {},
    onOpenTrip: (String) -> Unit = {},
    modifier: Modifier = Modifier,
) {
    // ---- Auto-create Trip + navigate when planner returns a packing list. ----
    val onOpenTripState = rememberUpdatedState(onOpenTrip)
    val onPlannerModeChangeState = rememberUpdatedState(onPlannerModeChange)
    val travelState by travelViewModel.state.collectAsState()
    val wardrobeState by wardrobeViewModel.state.collectAsState()
    val profileState by profileViewModel.state.collectAsState()
    val context = androidx.compose.ui.platform.LocalContext.current
    LaunchedEffect(travelState.packingList) {
        val packing = travelState.packingList ?: return@LaunchedEffect
        val snapshot = travelState
        val trip = buildTripFromPlan(packing, snapshot, context.getString(R.string.trip_no_destination))
        val outfits = buildOutfitsForTrip(packing, trip.id)
        // Persist outfits to the active closet first, then the trip JSON.
        stylesViewModel.addOutfits(outfits) { ok ->
            if (!ok) return@addOutfits
            tripsViewModel.createAndOpenTrip(
                trip.copy(outfitIds = outfits.map { it.id }),
            )
        }
        travelViewModel.consumePackingList()
    }

    // Entering the planner defaults its source closets to whatever closet the user is viewing
    // (null = All → all closets). The user can still change this in the closet picker.
    LaunchedEffect(plannerMode) {
        if (plannerMode) travelViewModel.seedSourceFolders(locationViewModel.activeFolderId)
    }

    // Open the viewer (a NavHost destination above this tab) when TripsViewModel emits a
    // navigate event.
    LaunchedEffect(Unit) {
        tripsViewModel.navigateToTrip.collect { tripId ->
            onPlannerModeChangeState.value(false)
            onOpenTripState.value(tripId)
        }
    }

    if (!plannerMode) {
        TravelOutfitsView(
            wardrobeViewModel = wardrobeViewModel,
            profileViewModel = profileViewModel,
            stylesViewModel = stylesViewModel,
            tripsViewModel = tripsViewModel,
            locationViewModel = locationViewModel,
            onOpenPlanner = { onPlannerModeChange(true) },
            onOpenTrip = onOpenTrip,
            onSettingsClick = onSettingsClick,
            modifier = modifier,
        )
        return
    }
    androidx.activity.compose.BackHandler { onPlannerModeChange(false) }
    TravelPlannerContent(
        travelViewModel = travelViewModel,
        wardrobeViewModel = wardrobeViewModel,
        profileViewModel = profileViewModel,
        stylesViewModel = stylesViewModel,
        locationViewModel = locationViewModel,
        onBack = { onPlannerModeChange(false) },
        modifier = modifier,
    )
}

/** Default name for an auto-created trip — destination + start date. */
private fun buildTripFromPlan(packing: PackingList, snapshot: TravelUiState, fallbackName: String): Trip {
    val nameParts = listOf(
        snapshot.resolvedDestination.ifBlank { snapshot.destination }.takeIf { it.isNotBlank() } ?: fallbackName,
        snapshot.startDate.toString(),
    )
    return Trip(
        name = nameParts.joinToString(" • "),
        destination = snapshot.resolvedDestination.ifBlank { snapshot.destination },
        startDate = snapshot.startDate.toString(),
        days = snapshot.days,
        forecast = snapshot.forecast,
        isHistoricalForecast = snapshot.isHistoricalForecast,
        historicalReferenceYear = snapshot.historicalReferenceYear,
        considerations = snapshot.considerationsOverride ?: com.librelookai.settings.AiConsiderations(),
        vibes = snapshot.vibes,
        goal = snapshot.goal,
        extraItems = packing.extraItems,
        reason = packing.reason,
    )
}

/**
 * Strips a leading "Day N" / "Day 1 & 2" / "Days 1-3" prefix (with an optional ":"/"-"/"–"
 * separator) that Gemini sometimes prepends to a trip outfit's occasion despite the prompt.
 * Conservative and English-only — the day label belongs in the viewer's UI, not the name.
 */
private val DAY_PREFIX = Regex("""^days?\s*\d+(\s*[&,\-–]\s*\d+)*\s*[:\-–]?\s*""", RegexOption.IGNORE_CASE)

private fun buildOutfitsForTrip(packing: PackingList, tripId: String): List<Outfit> =
    packing.outfits.map { p ->
        Outfit(
            name = p.occasion.replace(DAY_PREFIX, "").trim().ifBlank { p.occasion },
            description = p.description,
            itemIds = p.itemIds,
            tags = listOf("travel"),
            tripId = tripId,
        )
    }

/** Labelled chip showing which closets items are sourced from; tap opens the [ClosetPickerSheet]. */
@Composable
internal fun SourceClosetRow(
    closetNames: List<String>,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val label = closetNames.takeIf { it.isNotEmpty() }?.joinToString(" · ")
        ?: stringResource(R.string.composer_closets_all)
    Row(
        modifier = modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Text(
            stringResource(R.string.composer_section_sources),
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.weight(1f),
        )
        AssistChip(
            onClick = onClick,
            label = { Text(label, style = MaterialTheme.typography.labelSmall) },
            leadingIcon = {
                Icon(Icons.Default.Place, contentDescription = null, modifier = Modifier.size(16.dp))
            },
        )
    }
}

