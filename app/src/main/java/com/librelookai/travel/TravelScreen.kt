package com.librelookai.travel
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.AutoAwesome
import androidx.compose.material.icons.filled.Cake
import androidx.compose.material.icons.filled.CalendarMonth
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material.icons.filled.FlightTakeoff
import androidx.compose.material.icons.filled.MoveToInbox
import androidx.compose.material.icons.filled.Person
import androidx.compose.material.icons.filled.Place
import androidx.compose.material.icons.filled.Remove
import androidx.compose.material.icons.automirrored.filled.TrendingUp
import androidx.compose.material.icons.filled.WbSunny
import androidx.compose.material.icons.outlined.Checkroom
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DatePicker
import androidx.compose.material3.DatePickerDialog
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExposedDropdownMenuBox
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.InputChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedCard
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Snackbar
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberDatePickerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.res.stringResource
import androidx.lifecycle.viewmodel.compose.viewModel
import coil.compose.AsyncImage
import coil.request.ImageRequest
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import com.librelookai.AppScreenHeader
import com.librelookai.LocationButton
import com.librelookai.data.model.DayForecast
import com.librelookai.data.model.Outfit
import com.librelookai.data.model.PackingList
import com.librelookai.data.model.PackingOutfit
import com.librelookai.data.model.Trip
import com.librelookai.outfit.OutfitsViewModel
import com.librelookai.settings.AiConsiderationsStrip
import com.librelookai.settings.ProfileViewModel
import com.librelookai.util.AiProcessingOverlay
import com.librelookai.util.LocalIsOffline
import com.librelookai.wardrobe.DriveImage
import com.librelookai.wardrobe.LocationViewModel
import com.librelookai.wardrobe.WardrobeViewModel
import com.librelookai.weather.wmoEmoji
import com.librelookai.R
import com.librelookai.outfit.OutfitsScreen


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
    tripViewerTripId: String? = null,
    onOpenTrip: (String) -> Unit = {},
    onCloseTripViewer: () -> Unit = {},
    modifier: Modifier = Modifier,
) {
    // ---- Auto-create Trip + navigate when planner returns a packing list. ----
    val onOpenTripState = rememberUpdatedState(onOpenTrip)
    val onPlannerModeChangeState = rememberUpdatedState(onPlannerModeChange)
    val travelState by travelViewModel.state.collectAsState()
    val wardrobeState by wardrobeViewModel.state.collectAsState()
    val profileState by profileViewModel.state.collectAsState()
    LaunchedEffect(travelState.packingList) {
        val packing = travelState.packingList ?: return@LaunchedEffect
        val snapshot = travelState
        val trip = buildTripFromPlan(packing, snapshot)
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

    // Open the viewer when TripsViewModel emits a navigate event.
    LaunchedEffect(Unit) {
        tripsViewModel.navigateToTrip.collect { tripId ->
            onPlannerModeChangeState.value(false)
            onOpenTripState.value(tripId)
        }
    }

    if (tripViewerTripId != null) {
        androidx.activity.compose.BackHandler { onCloseTripViewer() }
        TripViewerScreen(
            tripId = tripViewerTripId,
            tripsViewModel = tripsViewModel,
            outfitsViewModel = stylesViewModel,
            wardrobeViewModel = wardrobeViewModel,
            profileViewModel = profileViewModel,
            onClose = onCloseTripViewer,
            modifier = modifier,
        )
        return
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
private fun buildTripFromPlan(packing: PackingList, snapshot: TravelUiState): Trip {
    val nameParts = listOf(
        snapshot.resolvedDestination.ifBlank { snapshot.destination }.takeIf { it.isNotBlank() } ?: "Trip",
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

private fun buildOutfitsForTrip(packing: PackingList, tripId: String): List<Outfit> =
    packing.outfits.map { p ->
        Outfit(
            name = p.occasion,
            description = p.description,
            itemIds = p.itemIds,
            tags = listOf("travel"),
            tripId = tripId,
        )
    }

@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
private fun TravelPlannerContent(
    travelViewModel: TravelViewModel,
    wardrobeViewModel: WardrobeViewModel,
    profileViewModel: ProfileViewModel,
    stylesViewModel: OutfitsViewModel,
    locationViewModel: LocationViewModel,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val isOffline = LocalIsOffline.current
    val state        by travelViewModel.state.collectAsState()
    val wardrobeState by wardrobeViewModel.state.collectAsState()
    val profileState  by profileViewModel.state.collectAsState()
    val outfitsState   by stylesViewModel.state.collectAsState()
    val locationState by locationViewModel.state.collectAsState()

    val isWorking = state.isLoadingForecast || state.isGenerating
    val keyboardController = LocalSoftwareKeyboardController.current

    Column(modifier = modifier.fillMaxSize()) {
        PlannerHeader(onClose = onBack)
        Box(modifier = Modifier.weight(1f).fillMaxWidth()) {
        LazyColumn(
            contentPadding = PaddingValues(bottom = 12.dp),
            verticalArrangement = Arrangement.spacedBy(0.dp),
        ) {
            // ---- Plan-a-trip input section ----
            item {
                val geoLanguage = com.librelookai.settings.AppLanguage.toBcp47(profileState.preferences.language)
                val prefsConsiderations = profileState.preferences.aiConsiderations
                val effectiveConsiderations = state.considerationsOverride ?: prefsConsiderations
                PlanTripSection(
                    state = state,
                    considerations = effectiveConsiderations,
                    geoLanguage = geoLanguage,
                    onUpdateDestination = travelViewModel::updateDestination,
                    onPickSuggestion = {
                        travelViewModel.pickDestination(it)
                        keyboardController?.hide()
                    },
                    onClearDestinationSuggestions = travelViewModel::clearDestinationSuggestions,
                    onUpdateDays = travelViewModel::updateDays,
                    onUpdateStartDate = travelViewModel::updateStartDate,
                    onUpdateOutfitCount = travelViewModel::updateOutfitCount,
                    onUpdateGoal = travelViewModel::updateGoal,
                    onToggleVibe = travelViewModel::toggleVibe,
                    onToggleConsideration = { transform ->
                        travelViewModel.setConsideration(prefsConsiderations, transform)
                    },
                )
            }

            // ---- Forecast strip ----
            if (state.forecast.isNotEmpty()) {
                item {
                    Column(modifier = Modifier.fillMaxWidth()) {
                        HorizontalDivider()
                        Text(
                            if (state.isHistoricalForecast && state.historicalReferenceYear != null)
                                "Historical climate (${state.historicalReferenceYear}) — ${state.resolvedDestination}"
                            else
                                "${state.resolvedDestination}",
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
                        )
                        if (state.isHistoricalForecast) {
                            Text(
                                stringResource(R.string.travel_historical_note),
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.padding(horizontal = 16.dp).padding(bottom = 4.dp),
                            )
                        }
                        LazyRow(
                            contentPadding = PaddingValues(horizontal = 16.dp),
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                        ) {
                            itemsIndexed(state.forecast) { index, day ->
                                ForecastDayChip(dayIndex = index + 1, forecast = day)
                            }
                        }
                        Spacer(Modifier.height(8.dp))
                    }
                }
            }

            // Note: The generated packing list is no longer rendered inline. Generate now
            // auto-creates a Trip + outfits and navigates to the Trip viewer (see TravelScreen).
        }

        // AI processing overlay
        if (isWorking) {
            AiProcessingOverlay(
                label = when {
                    state.isLoadingForecast -> stringResource(R.string.ai_fetching_weather)
                    else                   -> stringResource(R.string.ai_generating_packing)
                },
                modifier = Modifier.fillMaxSize(),
            )
        }

        // Error snackbar
        state.error?.let { msg ->
            Snackbar(
                modifier = Modifier
                    .align(Alignment.TopCenter)
                    .statusBarsPadding()
                    .padding(start = 8.dp, end = 8.dp, top = 64.dp),
                action = { TextButton(onClick = travelViewModel::clearError) { Text(stringResource(R.string.action_ok)) } },
            ) { Text(msg) }
        }
        } // Box
        // Sticky generate button — pinned at the bottom of the planner.
        Box(modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp)) {
            GenerateButton(
                hasResult = state.packingList != null,
                enabled = state.destination.isNotBlank() && !isWorking && !isOffline,
                onClick = {
                    keyboardController?.hide()
                    travelViewModel.generate(
                        prefs  = profileState.preferences,
                        images = wardrobeState.images,
                        styles = outfitsState.outfits,
                    )
                },
            )
        }
    } // Column
}

// ---------- Travel-outfits list view ----------

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun TravelOutfitsView(
    wardrobeViewModel: WardrobeViewModel,
    profileViewModel: ProfileViewModel,
    stylesViewModel: OutfitsViewModel,
    tripsViewModel: TripsViewModel,
    locationViewModel: LocationViewModel,
    onOpenPlanner: () -> Unit,
    onOpenTrip: (String) -> Unit,
    onSettingsClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val isOffline = LocalIsOffline.current
    val outfitsState   by stylesViewModel.state.collectAsState()
    val wardrobeState  by wardrobeViewModel.state.collectAsState()
    val profileState   by profileViewModel.state.collectAsState()
    val locationState  by locationViewModel.state.collectAsState()
    val tripsState     by tripsViewModel.state.collectAsState()

    val outfitsById = remember(outfitsState.outfits) { outfitsState.outfits.associateBy { it.id } }
    val tripOutfitIds = remember(tripsState.trips) { tripsState.trips.flatMap { it.outfitIds }.toSet() }
    // "Other travel outfits": travel-tagged outfits NOT in any trip (legacy data).
    val orphanTravelOutfits = remember(outfitsState.outfits, tripOutfitIds) {
        outfitsState.outfits.filter { o ->
            o.id !in tripOutfitIds && o.tags.any { it.equals("travel", ignoreCase = true) }
        }
    }
    val imagesById = remember(wardrobeState.images) { wardrobeState.images.associateBy { it.driveId } }

    Column(modifier = modifier.fillMaxSize()) {
        AppScreenHeader(
            title = stringResource(R.string.travel_outfits_section),
            leadingIcon = Icons.Default.FlightTakeoff,
            trailingContent = {
                LocationButton(
                    locations = locationState.locations,
                    activeLocationId = locationState.activeLocationId,
                    onSetActiveLocation = locationViewModel::setActiveLocation,
                )
            },
            onSettingsClick = onSettingsClick,
        )
        Box(modifier = Modifier.fillMaxSize()) {
            if (tripsState.trips.isEmpty() && orphanTravelOutfits.isEmpty()) {
                Column(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(top = 64.dp, start = 32.dp, end = 32.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    Icon(
                        Icons.Default.FlightTakeoff,
                        contentDescription = null,
                        modifier = Modifier.size(48.dp),
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Text(
                        stringResource(R.string.outfits_empty),
                        style = MaterialTheme.typography.bodyLarge,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        textAlign = TextAlign.Center,
                    )
                    Text(
                        stringResource(R.string.travel_outfits_empty_hint),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        textAlign = TextAlign.Center,
                    )
                }
            } else {
                LazyColumn(
                    contentPadding = PaddingValues(vertical = 12.dp, horizontal = 16.dp),
                    verticalArrangement = Arrangement.spacedBy(10.dp),
                    modifier = Modifier.fillMaxSize(),
                ) {
                    if (tripsState.trips.isNotEmpty()) {
                        items(tripsState.trips, key = { it.id }) { trip ->
                            TripCard(
                                trip = trip,
                                outfitsById = outfitsById,
                                imagesById = imagesById,
                                onClick = { onOpenTrip(trip.id) },
                            )
                        }
                    }
                    if (orphanTravelOutfits.isNotEmpty()) {
                        item {
                            Text(
                                stringResource(R.string.trip_section_other_outfits),
                                style = MaterialTheme.typography.titleSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.padding(top = 8.dp),
                            )
                        }
                        items(orphanTravelOutfits, key = { it.id }) { outfit ->
                            TravelOutfitListCard(
                                outfit = outfit,
                                imagesById = imagesById,
                                onClick = {
                                    stylesViewModel.startEditing(
                                        outfit,
                                        wardrobeState.images,
                                        profileState.preferences,
                                    )
                                },
                            )
                        }
                    }
                }
            }

            if (!isOffline) {
                androidx.compose.material3.FloatingActionButton(
                    onClick = onOpenPlanner,
                    modifier = Modifier
                        .align(Alignment.BottomEnd)
                        .padding(16.dp),
                ) {
                    Icon(Icons.Default.Add, contentDescription = stringResource(R.string.travel_plan_trip))
                }
            }
        }
    }
}

@Composable
private fun TripCard(
    trip: com.librelookai.data.model.Trip,
    outfitsById: Map<String, com.librelookai.data.model.Outfit>,
    imagesById: Map<String, DriveImage>,
    onClick: () -> Unit,
) {
    val ctx = LocalContext.current
    val previewImages = remember(trip.outfitIds, outfitsById, imagesById) {
        trip.outfitIds.asSequence()
            .mapNotNull { outfitsById[it] }
            .flatMap { it.itemIds.asSequence() }
            .mapNotNull { imagesById[it] }
            .distinct()
            .take(6)
            .toList()
    }
    OutlinedCard(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick),
    ) {
        Column(
            modifier = Modifier.padding(12.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(
                    Icons.Default.FlightTakeoff,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.size(16.dp),
                )
                Spacer(Modifier.width(6.dp))
                Text(
                    trip.name.ifBlank { trip.destination.ifBlank { stringResource(R.string.trip_viewer_title) } },
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.SemiBold,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            Text(
                stringResource(R.string.trip_meta_dates, trip.startDate.ifBlank { "?" }, trip.days),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            if (previewImages.isNotEmpty()) {
                LazyRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    items(previewImages, key = { it.driveId }) { image ->
                        AsyncImage(
                            model = remember(image.driveId, image.version) {
                                ImageRequest.Builder(ctx)
                                    .data(image.localPath)
                                    .memoryCacheKey("${image.driveId}_${image.version}")
                                    .build()
                            },
                            contentDescription = image.name,
                            modifier = Modifier
                                .size(56.dp)
                                .clip(MaterialTheme.shapes.small),
                            contentScale = ContentScale.Crop,
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun TravelOutfitListCard(
    outfit: com.librelookai.data.model.Outfit,
    imagesById: Map<String, DriveImage>,
    onClick: () -> Unit,
) {
    val ctx = LocalContext.current
    val items = outfit.itemIds.mapNotNull { imagesById[it] }
    OutlinedCard(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick),
    ) {
        Column(
            modifier = Modifier.padding(12.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            if (outfit.name.isNotBlank()) {
                Text(
                    outfit.name,
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.SemiBold,
                )
            }
            if (outfit.description.isNotBlank()) {
                Text(
                    outfit.description,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            if (items.isNotEmpty()) {
                LazyRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    items(items, key = { it.driveId }) { image ->
                        AsyncImage(
                            model = remember(image.driveId, image.version) {
                                ImageRequest.Builder(ctx)
                                    .data(image.localPath)
                                    .memoryCacheKey("${image.driveId}_${image.version}")
                                    .build()
                            },
                            contentDescription = image.name,
                            modifier = Modifier
                                .size(64.dp)
                                .clip(MaterialTheme.shapes.small),
                            contentScale = ContentScale.Crop,
                        )
                    }
                }
            } else {
                Text(
                    stringResource(R.string.outfits_missing_items),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.outline,
                )
            }
        }
    }
}

// ---------- Forecast day chip ----------

@Composable
private fun ForecastDayChip(dayIndex: Int, forecast: DayForecast) {
    Surface(
        shape = MaterialTheme.shapes.small,
        tonalElevation = 2.dp,
    ) {
        Column(
            modifier = Modifier.padding(horizontal = 10.dp, vertical = 8.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(2.dp),
        ) {
            Text(stringResource(R.string.travel_day, dayIndex), style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant)
            Text(wmoEmoji(forecast.weatherCode), style = MaterialTheme.typography.bodyMedium)
            Text(
                "${forecast.minTempC.toInt()}–${forecast.maxTempC.toInt()}°",
                style = MaterialTheme.typography.labelSmall,
            )
        }
    }
}

// ---------- Plan-trip section ----------

private val TRAVEL_VIBES: List<Pair<String, Int>> = listOf(
    "Casual" to R.string.composer_vibe_casual,
    "Sporty" to R.string.composer_vibe_sporty,
    "Formal" to R.string.composer_vibe_formal,
    "Business" to R.string.composer_vibe_business,
    "Streetwear" to R.string.composer_vibe_streetwear,
    "Minimalist" to R.string.composer_vibe_minimalist,
    "Classic" to R.string.composer_vibe_classic,
    "Elegant" to R.string.composer_vibe_elegant,
)

@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
private fun PlanTripSection(
    state: TravelUiState,
    considerations: com.librelookai.settings.AiConsiderations,
    geoLanguage: String,
    onUpdateDestination: (String, String) -> Unit,
    onPickSuggestion: (com.librelookai.weather.DestinationSuggestion) -> Unit,
    onClearDestinationSuggestions: () -> Unit,
    onUpdateDays: (Int) -> Unit,
    onUpdateStartDate: (LocalDate) -> Unit,
    onUpdateOutfitCount: (Int?) -> Unit,
    onUpdateGoal: (String) -> Unit,
    onToggleVibe: (String) -> Unit,
    onToggleConsideration: ((com.librelookai.settings.AiConsiderations) -> com.librelookai.settings.AiConsiderations) -> Unit,
) {
    Column(modifier = Modifier.fillMaxWidth()) {
        SkyHero(
            destination = state.destination,
            suggestions = state.destinationSuggestions,
            geoLanguage = geoLanguage,
            onUpdateDestination = onUpdateDestination,
            onPickSuggestion = onPickSuggestion,
            onClearSuggestions = onClearDestinationSuggestions,
        )
        Column(
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            DatesAndDaysCard(
                startDate = state.startDate,
                days = state.days,
                onUpdateDays = onUpdateDays,
                onUpdateStartDate = onUpdateStartDate,
            )
            OutfitCountCard(
                days = state.days,
                outfitCount = state.outfitCount,
                onUpdateOutfitCount = onUpdateOutfitCount,
            )
            GoalAiCard(
                goal = state.goal,
                onUpdateGoal = onUpdateGoal,
            )
            VibeChips(
                selected = state.vibes,
                onToggle = onToggleVibe,
            )
            AiConsidersChips(
                considerations = considerations,
                onToggle = onToggleConsideration,
            )
        }
    }
}

// Mirrors the OutfitComposer header style: close button + headlineSmall title + subtitle.
@Composable
private fun PlannerHeader(onClose: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(start = 8.dp, end = 8.dp, top = 4.dp, bottom = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        IconButton(onClick = onClose, modifier = Modifier.size(40.dp)) {
            Icon(Icons.Default.Close, contentDescription = stringResource(R.string.action_cancel))
        }
        Spacer(Modifier.width(4.dp))
        Column(modifier = Modifier.weight(1f)) {
            Text(
                stringResource(R.string.travel_plan_title),
                style = MaterialTheme.typography.headlineSmall.copy(
                    fontSize = 36.sp,
                    fontWeight = FontWeight.Bold,
                ),
            )
            Text(
                stringResource(R.string.travel_plan_eyebrow),
                style = MaterialTheme.typography.bodySmall.copy(fontSize = 11.sp),
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
    HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun SkyHero(
    destination: String,
    suggestions: List<com.librelookai.weather.DestinationSuggestion>,
    geoLanguage: String,
    onUpdateDestination: (String, String) -> Unit,
    onPickSuggestion: (com.librelookai.weather.DestinationSuggestion) -> Unit,
    onClearSuggestions: () -> Unit,
) {
    val palette = com.librelookai.ui.theme.LocalWardrobePalette.current
    val keyboardController = LocalSoftwareKeyboardController.current
    Box(
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            var suggestionsExpanded by remember { mutableStateOf(false) }
            val showSuggestions = suggestionsExpanded && suggestions.isNotEmpty()
            var editing by remember { mutableStateOf(destination.isBlank()) }

            // Glass destination card
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(16.dp))
                    .background(palette.surface.copy(alpha = 0.87f))
                    .border(1.dp, palette.divider.copy(alpha = 0.5f), RoundedCornerShape(16.dp))
                    .padding(horizontal = 12.dp, vertical = 10.dp),
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(
                        Icons.Default.Place,
                        contentDescription = null,
                        tint = palette.textMuted,
                        modifier = Modifier.size(12.dp),
                    )
                    Spacer(Modifier.width(4.dp))
                    Text(
                        stringResource(R.string.travel_label_destination).uppercase(),
                        fontSize = 9.sp,
                        fontWeight = FontWeight.Bold,
                        color = palette.textMuted,
                    )
                }
                Spacer(Modifier.height(4.dp))
                if (editing || destination.isBlank()) {
                    ExposedDropdownMenuBox(
                        expanded = showSuggestions,
                        onExpandedChange = { suggestionsExpanded = it },
                    ) {
                        OutlinedTextField(
                            value = destination,
                            onValueChange = {
                                onUpdateDestination(it, geoLanguage)
                                suggestionsExpanded = true
                            },
                            placeholder = { Text(stringResource(R.string.travel_destination_placeholder)) },
                            trailingIcon = if (destination.isNotEmpty()) {
                                { IconButton(onClick = {
                                    onUpdateDestination("", geoLanguage)
                                    suggestionsExpanded = false
                                }) { Icon(Icons.Default.Close, contentDescription = "Clear") } }
                            } else null,
                            singleLine = true,
                            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
                            modifier = Modifier
                                .fillMaxWidth()
                                .menuAnchor(androidx.compose.material3.MenuAnchorType.PrimaryEditable, enabled = true),
                        )
                        ExposedDropdownMenu(
                            expanded = showSuggestions,
                            onDismissRequest = { suggestionsExpanded = false },
                        ) {
                            suggestions.forEach { sug ->
                                DropdownMenuItem(
                                    text = {
                                        Column {
                                            Text(sug.name, style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.Medium)
                                            val sub = listOf(sug.admin1, sug.country).filter { it.isNotBlank() }.joinToString(", ")
                                            if (sub.isNotEmpty()) {
                                                Text(sub, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                            }
                                        }
                                    },
                                    onClick = {
                                        onPickSuggestion(sug)
                                        suggestionsExpanded = false
                                        editing = false
                                        keyboardController?.hide()
                                    },
                                )
                            }
                        }
                    }
                } else {
                    Text(
                        destination,
                        fontSize = 18.sp,
                        fontWeight = FontWeight.Bold,
                        color = palette.text,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable {
                                editing = true
                                onClearSuggestions()
                            }
                            .padding(vertical = 6.dp),
                    )
                }
            }
        }
    }
}

@Composable
private fun SectionCard(
    modifier: Modifier = Modifier,
    content: @Composable () -> Unit,
) {
    val palette = com.librelookai.ui.theme.LocalWardrobePalette.current
    Box(
        modifier = modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(14.dp))
            .background(palette.surface)
            .border(1.dp, palette.divider, RoundedCornerShape(14.dp))
            .padding(horizontal = 14.dp, vertical = 12.dp),
    ) { content() }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun DatesAndDaysCard(
    startDate: LocalDate,
    days: Int,
    onUpdateDays: (Int) -> Unit,
    onUpdateStartDate: (LocalDate) -> Unit,
) {
    val palette = com.librelookai.ui.theme.LocalWardrobePalette.current
    var showDialog by remember { mutableStateOf(false) }
    val endDate = startDate.plusDays((days - 1).toLong())
    val rangeFmt = DateTimeFormatter.ofPattern("MMM d")
    SectionCard {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(
                modifier = Modifier
                    .weight(1f)
                    .clickable { showDialog = true },
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(
                        Icons.Default.CalendarMonth,
                        contentDescription = null,
                        tint = palette.textMuted,
                        modifier = Modifier.size(11.dp),
                    )
                    Spacer(Modifier.width(4.dp))
                    Text(
                        stringResource(R.string.travel_label_dates).uppercase(),
                        fontSize = 10.sp,
                        fontWeight = FontWeight.Bold,
                        color = palette.textMuted,
                    )
                }
                Spacer(Modifier.height(2.dp))
                Text(
                    "${startDate.format(rangeFmt)} → ${endDate.format(rangeFmt)}",
                    fontSize = 14.sp,
                    fontWeight = FontWeight.Bold,
                    color = palette.text,
                )
            }
            Spacer(Modifier.width(12.dp))
            Box(
                modifier = Modifier
                    .width(1.dp)
                    .height(32.dp)
                    .background(palette.divider),
            )
            Spacer(Modifier.width(12.dp))
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Text(
                    stringResource(R.string.travel_label_days).uppercase(),
                    fontSize = 10.sp,
                    fontWeight = FontWeight.Bold,
                    color = palette.textMuted,
                )
                Spacer(Modifier.height(4.dp))
                StepperRow(
                    value = days,
                    onDec = { onUpdateDays(days - 1) },
                    onInc = { onUpdateDays(days + 1) },
                    canDec = days > 1,
                    canInc = days < 21,
                    buttonSize = 24.dp,
                )
            }
        }
    }
    if (showDialog) {
        val datePickerState = rememberDatePickerState(
            initialSelectedDateMillis = startDate.toEpochDay() * 86_400_000L,
        )
        DatePickerDialog(
            onDismissRequest = { showDialog = false },
            confirmButton = {
                TextButton(onClick = {
                    datePickerState.selectedDateMillis?.let {
                        onUpdateStartDate(LocalDate.ofEpochDay(it / 86_400_000L))
                    }
                    showDialog = false
                }) { Text(stringResource(R.string.action_ok)) }
            },
            dismissButton = {
                TextButton(onClick = { showDialog = false }) { Text(stringResource(R.string.action_cancel)) }
            },
        ) {
            DatePicker(state = datePickerState)
        }
    }
}

@Composable
private fun OutfitCountCard(
    days: Int,
    outfitCount: Int?,
    onUpdateOutfitCount: (Int?) -> Unit,
) {
    val palette = com.librelookai.ui.theme.LocalWardrobePalette.current
    val effective = outfitCount ?: days
    val daysPerOutfit = if (effective > 0) {
        val perOutfit = days.toFloat() / effective.toFloat()
        if (perOutfit >= 1f) String.format("%.1f", perOutfit).trimEnd('0').trimEnd('.')
        else String.format("%.1f", perOutfit)
    } else "1"
    SectionCard {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(
                modifier = Modifier
                    .size(32.dp)
                    .clip(RoundedCornerShape(10.dp))
                    .background(palette.primaryDim),
                contentAlignment = Alignment.Center,
            ) {
                Icon(
                    Icons.Outlined.Checkroom,
                    contentDescription = null,
                    tint = palette.primary,
                    modifier = Modifier.size(16.dp),
                )
            }
            Spacer(Modifier.width(10.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    stringResource(R.string.travel_label_outfits).uppercase(),
                    fontSize = 9.sp,
                    fontWeight = FontWeight.Bold,
                    color = palette.textMuted,
                )
                Text(
                    stringResource(R.string.travel_outfit_count_summary, effective, daysPerOutfit),
                    fontSize = 13.sp,
                    fontWeight = FontWeight.Bold,
                    color = palette.text,
                )
            }
            StepperRow(
                value = effective,
                onDec = { onUpdateOutfitCount(effective - 1) },
                onInc = { onUpdateOutfitCount(effective + 1) },
                canDec = effective > 1,
                canInc = effective < 21,
                buttonSize = 28.dp,
            )
        }
    }
}

@Composable
private fun StepperRow(
    value: Int,
    onDec: () -> Unit,
    onInc: () -> Unit,
    canDec: Boolean,
    canInc: Boolean,
    buttonSize: androidx.compose.ui.unit.Dp,
) {
    val palette = com.librelookai.ui.theme.LocalWardrobePalette.current
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        StepperButton(symbol = "−", enabled = canDec, onClick = onDec, size = buttonSize)
        Text(
            value.toString(),
            fontSize = 14.sp,
            fontWeight = FontWeight.Bold,
            color = palette.text,
            textAlign = TextAlign.Center,
            modifier = Modifier.widthIn(min = 20.dp),
        )
        StepperButton(symbol = "+", enabled = canInc, onClick = onInc, size = buttonSize)
    }
}

@Composable
private fun StepperButton(
    symbol: String,
    enabled: Boolean,
    onClick: () -> Unit,
    size: androidx.compose.ui.unit.Dp,
) {
    val palette = com.librelookai.ui.theme.LocalWardrobePalette.current
    val alpha = if (enabled) 1f else 0.4f
    Box(
        modifier = Modifier
            .size(size)
            .clip(CircleShape)
            .background(palette.surface2.copy(alpha = alpha))
            .border(1.dp, palette.border.copy(alpha = alpha), CircleShape)
            .clickable(enabled = enabled, onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            symbol,
            fontSize = 14.sp,
            fontWeight = FontWeight.Bold,
            color = palette.textMid.copy(alpha = alpha),
        )
    }
}

@Composable
private fun GoalAiCard(
    goal: String,
    onUpdateGoal: (String) -> Unit,
) {
    val palette = com.librelookai.ui.theme.LocalWardrobePalette.current
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(16.dp))
            .background(
                Brush.linearGradient(listOf(palette.primaryDim, palette.chipBg)),
            )
            .border(1.dp, palette.primary.copy(alpha = 0.33f), RoundedCornerShape(16.dp))
            .padding(14.dp),
    ) {
        Column(modifier = Modifier.fillMaxWidth()) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(
                    Icons.Default.AutoAwesome,
                    contentDescription = null,
                    tint = palette.primary,
                    modifier = Modifier.size(12.dp),
                )
                Spacer(Modifier.width(4.dp))
                Text(
                    stringResource(R.string.travel_label_about).uppercase(),
                    fontSize = 10.sp,
                    fontWeight = FontWeight.Bold,
                    color = palette.primary,
                )
            }
            Spacer(Modifier.height(8.dp))
            OutlinedTextField(
                value = goal,
                onValueChange = onUpdateGoal,
                placeholder = { Text(stringResource(R.string.travel_goal_placeholder)) },
                trailingIcon = {
                    Icon(
                        Icons.Default.Edit,
                        contentDescription = null,
                        tint = palette.textMuted,
                        modifier = Modifier.size(13.dp),
                    )
                },
                minLines = 1,
                maxLines = 2,
                modifier = Modifier.fillMaxWidth(),
            )
        }
    }
}

@Composable
private fun VibeChips(
    selected: Set<String>,
    onToggle: (String) -> Unit,
) {
    val palette = com.librelookai.ui.theme.LocalWardrobePalette.current
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Text(
            "${stringResource(R.string.travel_label_vibe).uppercase()} (${selected.size})",
            fontSize = 10.sp,
            fontWeight = FontWeight.Bold,
            color = palette.textMuted,
        )
        LazyRow(horizontalArrangement = Arrangement.spacedBy(5.dp)) {
            items(TRAVEL_VIBES, key = { it.first }) { (value, labelRes) ->
                SmallPillChip(
                    label = stringResource(labelRes),
                    icon = null,
                    active = value in selected,
                    onClick = { onToggle(value) },
                )
            }
        }
    }
}

@Composable
private fun AiConsidersChips(
    considerations: com.librelookai.settings.AiConsiderations,
    onToggle: ((com.librelookai.settings.AiConsiderations) -> com.librelookai.settings.AiConsiderations) -> Unit,
) {
    val palette = com.librelookai.ui.theme.LocalWardrobePalette.current
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Text(
            stringResource(R.string.travel_label_ai_considers).uppercase(),
            fontSize = 10.sp,
            fontWeight = FontWeight.Bold,
            color = palette.textMuted,
        )
        LazyRow(horizontalArrangement = Arrangement.spacedBy(5.dp)) {
            item {
                SmallPillChip(
                    label = stringResource(R.string.ai_consider_weather),
                    icon = Icons.Default.WbSunny,
                    active = considerations.weather,
                ) { onToggle { it.copy(weather = !it.weather) } }
            }
            item {
                SmallPillChip(
                    label = stringResource(R.string.ai_consider_location),
                    icon = Icons.Default.Place,
                    active = considerations.location,
                ) { onToggle { it.copy(location = !it.location) } }
            }
            item {
                SmallPillChip(
                    label = stringResource(R.string.ai_consider_trends),
                    icon = Icons.AutoMirrored.Filled.TrendingUp,
                    active = considerations.trends,
                ) { onToggle { it.copy(trends = !it.trends) } }
            }
            item {
                SmallPillChip(
                    label = stringResource(R.string.ai_consider_gender),
                    icon = Icons.Default.Person,
                    active = considerations.gender,
                ) { onToggle { it.copy(gender = !it.gender) } }
            }
            item {
                SmallPillChip(
                    label = stringResource(R.string.ai_consider_age),
                    icon = Icons.Default.Cake,
                    active = considerations.age,
                ) { onToggle { it.copy(age = !it.age) } }
            }
            item {
                SmallPillChip(
                    label = stringResource(R.string.ai_consider_preferences),
                    icon = Icons.Default.Favorite,
                    active = considerations.preferences,
                ) { onToggle { it.copy(preferences = !it.preferences) } }
            }
        }
    }
}

@Composable
private fun SmallPillChip(
    label: String,
    icon: ImageVector?,
    active: Boolean,
    onClick: () -> Unit,
) {
    val palette = com.librelookai.ui.theme.LocalWardrobePalette.current
    val bg = if (active) palette.primary else palette.chipBg
    val fg = if (active) palette.onPrimary else palette.chipFg
    val borderColor = if (active) palette.primary else palette.border
    val borderWidth = if (active) 1.5.dp else 1.dp
    Row(
        modifier = Modifier
            .clip(RoundedCornerShape(999.dp))
            .background(bg)
            .border(borderWidth, borderColor, RoundedCornerShape(999.dp))
            .clickable(onClick = onClick)
            .padding(horizontal = 10.dp, vertical = 5.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        if (icon != null) {
            Icon(icon, contentDescription = null, tint = fg, modifier = Modifier.size(12.dp))
        }
        Text(label, fontSize = 11.sp, fontWeight = FontWeight.SemiBold, color = fg)
    }
}

@Composable
private fun GenerateButton(
    hasResult: Boolean,
    enabled: Boolean,
    onClick: () -> Unit,
) {
    val palette = com.librelookai.ui.theme.LocalWardrobePalette.current
    val alpha = if (enabled) 1f else 0.4f
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .height(52.dp)
            .clip(RoundedCornerShape(26.dp))
            .background(
                Brush.linearGradient(
                    listOf(
                        palette.primary.copy(alpha = alpha),
                        palette.primary.copy(alpha = alpha * 0.85f),
                    ),
                ),
            )
            .clickable(enabled = enabled, onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            // Single-call generation: the packing list returns every outfit in one Gemini
            // round-trip, so the cost barely scales with `outfitCount`. Match the
            // OutfitComposer's "Generate with AI" badge and pass bulkCount = 1.
            com.librelookai.billing.CostBadge(com.librelookai.gemini.GeminiActionId.GENERATE_TEXT)
            Icon(
                Icons.Default.AutoAwesome,
                contentDescription = null,
                tint = palette.onPrimary,
                modifier = Modifier.size(18.dp),
            )
            Text(
                if (hasResult) stringResource(R.string.travel_regenerate) else stringResource(R.string.travel_generate),
                fontSize = 15.sp,
                fontWeight = FontWeight.Bold,
                color = palette.onPrimary,
            )
        }
    }
}
