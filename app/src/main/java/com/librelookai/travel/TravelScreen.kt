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
import androidx.compose.material.icons.filled.TrendingUp
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
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
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
import com.librelookai.data.model.PackingOutfit
import com.librelookai.outfit.OutfitsViewModel
import com.librelookai.outfit.RefinementSection
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

@Composable
private fun travelPresets() = listOf(
    stringResource(R.string.travel_refine_lighter),
    stringResource(R.string.travel_refine_formal),
    stringResource(R.string.travel_refine_casual),
    stringResource(R.string.travel_refine_rain),
    stringResource(R.string.travel_refine_evening),
    stringResource(R.string.travel_refine_reuse),
)

@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
fun TravelScreen(
    travelViewModel: TravelViewModel = viewModel(),
    wardrobeViewModel: WardrobeViewModel = viewModel(),
    profileViewModel: ProfileViewModel = viewModel(),
    stylesViewModel: OutfitsViewModel = viewModel(),
    locationViewModel: LocationViewModel = viewModel(),
    onSettingsClick: () -> Unit = {},
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
    var isMoveInProgress by remember { mutableStateOf(false) }
    var moveMessage by remember { mutableStateOf<String?>(null) }

    Column(modifier = modifier.fillMaxSize()) {
        AppScreenHeader(
            title = stringResource(R.string.travel_title),
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
        LazyColumn(
            contentPadding = PaddingValues(bottom = 24.dp),
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
                    isWorking = isWorking,
                    isOffline = isOffline,
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
                    onGenerate = {
                        keyboardController?.hide()
                        travelViewModel.generate(
                            prefs  = profileState.preferences,
                            images = wardrobeState.images,
                            styles = outfitsState.outfits,
                        )
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

            // ---- Packing list ----
            state.packingList?.let { packing ->
                item {
                    HorizontalDivider()
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 16.dp, vertical = 12.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                stringResource(R.string.travel_packing_list),
                                style = MaterialTheme.typography.titleSmall,
                                fontWeight = FontWeight.SemiBold,
                            )
                            if (packing.reason.isNotEmpty()) {
                                Text(
                                    packing.reason,
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                        }
                        TextButton(onClick = travelViewModel::clearResult) { Text(stringResource(R.string.travel_clear)) }
                    }
                    val allPackedIds = packing.outfits.flatMap { it.itemIds }.distinct()
                    if (allPackedIds.isNotEmpty()) {
                        val moveDoneLabel = stringResource(R.string.travel_move_done)
                        val moveErrorLabel = stringResource(R.string.travel_move_error)
                        OutlinedButton(
                            onClick = {
                                isMoveInProgress = true
                                locationViewModel.getOrCreateLocation("Travel") { toFolderId ->
                                    if (toFolderId == null) {
                                        isMoveInProgress = false
                                        moveMessage = moveErrorLabel
                                        return@getOrCreateLocation
                                    }
                                    wardrobeViewModel.moveItemsToFolder(allPackedIds, toFolderId) { success ->
                                        isMoveInProgress = false
                                        moveMessage = if (success) moveDoneLabel else moveErrorLabel
                                    }
                                }
                            },
                            enabled = !isMoveInProgress && !isOffline,
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(horizontal = 16.dp)
                                .padding(bottom = 8.dp),
                        ) {
                            if (isMoveInProgress) {
                                CircularProgressIndicator(modifier = Modifier.size(16.dp), strokeWidth = 2.dp)
                                Spacer(Modifier.width(8.dp))
                            } else {
                                Icon(Icons.Default.MoveToInbox, contentDescription = null, modifier = Modifier.size(16.dp))
                                Spacer(Modifier.width(8.dp))
                            }
                            Text(stringResource(R.string.travel_move_to_travel))
                        }
                    }
                }

                itemsIndexed(packing.outfits) { index, outfit ->
                    PackingOutfitCard(
                        outfit = outfit,
                        imagesById = wardrobeState.images.associateBy { it.driveId },
                        onSaveAsStyle = if (outfit.itemIds.isNotEmpty()) {
                            {
                                stylesViewModel.openComposer(
                                    seedItemIds        = outfit.itemIds.toSet(),
                                    images             = wardrobeState.images,
                                    prefs              = profileState.preferences,
                                    initialName        = outfit.occasion,
                                    initialDescription = outfit.description,
                                )
                            }
                        } else null,
                        modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp),
                    )
                }

                if (packing.extraItems.isNotEmpty()) {
                    item {
                        ExtraItemsCard(
                            items = packing.extraItems,
                            modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp),
                        )
                    }
                }

                // ---- Refinement section (hidden when offline) ----
                if (!isOffline) {
                    item {
                        Spacer(Modifier.height(8.dp))
                        HorizontalDivider()
                        RefinementSection(
                            input = state.refinementInput,
                            feedbackHistory = state.feedbackHistory,
                            presets = travelPresets(),
                            onInputChange = travelViewModel::updateRefinementInput,
                            onSubmitFreetext = {
                                travelViewModel.refine(profileState.preferences, wardrobeState.images, outfitsState.outfits)
                            },
                            onSubmitPreset = { preset ->
                                travelViewModel.submitPreset(preset, profileState.preferences, wardrobeState.images, outfitsState.outfits)
                            },
                            modifier = Modifier.padding(16.dp),
                        )
                    }
                }
            }

            // ---- Empty state ----
            if (!isWorking && state.packingList == null && state.error == null) {
                item {
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(top = 48.dp),
                        contentAlignment = Alignment.Center,
                    ) {
                        Column(
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
                                stringResource(R.string.travel_empty),
                                style = MaterialTheme.typography.bodyLarge,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                }
            }
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
        // Move-to-travel result snackbar
        moveMessage?.let { msg ->
            Snackbar(
                modifier = Modifier
                    .align(Alignment.TopCenter)
                    .statusBarsPadding()
                    .padding(start = 8.dp, end = 8.dp, top = 64.dp),
                action = { TextButton(onClick = { moveMessage = null }) { Text(stringResource(R.string.action_ok)) } },
            ) { Text(msg) }
        }
        } // Box
    } // Column
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

// ---------- Packing outfit card ----------

@Composable
private fun PackingOutfitCard(
    outfit: PackingOutfit,
    imagesById: Map<String, DriveImage>,
    onSaveAsStyle: (() -> Unit)?,
    modifier: Modifier = Modifier,
) {
    val ctx = LocalContext.current
    val images = outfit.itemIds.mapNotNull { imagesById[it] }

    OutlinedCard(modifier = modifier.fillMaxWidth()) {
        Column(
            modifier = Modifier.padding(12.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    outfit.occasion,
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.SemiBold,
                    modifier = Modifier.weight(1f),
                )
                if (onSaveAsStyle != null) {
                    InputChip(
                        selected = false,
                        onClick = { onSaveAsStyle() },
                        label = {
                            Text(
                                stringResource(R.string.travel_save_as_style),
                                style = MaterialTheme.typography.labelSmall,
                            )
                        },
                    )
                }
            }

            if (images.isNotEmpty()) {
                LazyRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    items(images, key = { it.driveId }) { image ->
                        Column(
                            horizontalAlignment = Alignment.CenterHorizontally,
                            verticalArrangement = Arrangement.spacedBy(2.dp),
                        ) {
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
                            Text(
                                image.tags?.type?.ifEmpty { image.name } ?: image.name,
                                style = MaterialTheme.typography.labelSmall,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                                modifier = Modifier.width(64.dp),
                            )
                        }
                    }
                }
            } else {
                Text(
                    stringResource(R.string.outfits_missing_items),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.outline,
                )
            }

            if (outfit.description.isNotEmpty()) {
                Text(
                    outfit.description,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

// ---------- Extra items card ----------

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun ExtraItemsCard(
    items: List<String>,
    modifier: Modifier = Modifier,
) {
    OutlinedCard(modifier = modifier.fillMaxWidth()) {
        Column(
            modifier = Modifier.padding(12.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Text(
                stringResource(R.string.travel_extra_items),
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.SemiBold,
            )
            FlowRow(
                horizontalArrangement = Arrangement.spacedBy(6.dp),
                verticalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                items.forEach { item ->
                    AssistChip(onClick = {}, label = { Text(item) })
                }
            }
        }
    }
}

// ---------- Refinement section (shared composable reuse) ----------
// RefinementSection is defined in OutfitsScreen.kt and is internal to the package.

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
    isWorking: Boolean,
    isOffline: Boolean,
    onUpdateDestination: (String, String) -> Unit,
    onPickSuggestion: (com.librelookai.weather.DestinationSuggestion) -> Unit,
    onClearDestinationSuggestions: () -> Unit,
    onUpdateDays: (Int) -> Unit,
    onUpdateStartDate: (LocalDate) -> Unit,
    onUpdateOutfitCount: (Int?) -> Unit,
    onUpdateGoal: (String) -> Unit,
    onToggleVibe: (String) -> Unit,
    onToggleConsideration: ((com.librelookai.settings.AiConsiderations) -> com.librelookai.settings.AiConsiderations) -> Unit,
    onGenerate: () -> Unit,
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
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
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
            GenerateButton(
                hasResult = state.packingList != null,
                enabled = state.destination.isNotBlank() && !isWorking && !isOffline,
                onClick = onGenerate,
            )
        }
    }
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
    val skyTop = palette.primaryDim
    val keyboardController = LocalSoftwareKeyboardController.current
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .background(Brush.verticalGradient(listOf(skyTop, palette.bg))),
    ) {
        // Sun decoration
        Box(
            modifier = Modifier
                .align(Alignment.TopEnd)
                .padding(top = 14.dp, end = 24.dp)
                .size(64.dp)
                .clip(CircleShape)
                .background(
                    Brush.radialGradient(
                        listOf(
                            palette.primary.copy(alpha = 0.30f),
                            palette.primary.copy(alpha = 0.18f),
                            Color.Transparent,
                        ),
                    ),
                ),
        )
        // Cloud decoration
        Box(
            modifier = Modifier
                .padding(start = 0.dp, top = 50.dp)
                .size(width = 130.dp, height = 46.dp)
                .clip(RoundedCornerShape(50))
                .background(palette.divider.copy(alpha = 0.5f)),
        )
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 18.dp, vertical = 16.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            Column {
                Text(
                    stringResource(R.string.travel_plan_eyebrow).uppercase(),
                    fontSize = 9.sp,
                    fontWeight = FontWeight.Bold,
                    color = palette.textMuted,
                )
                Text(
                    stringResource(R.string.travel_plan_title),
                    fontSize = 18.sp,
                    fontWeight = FontWeight.Bold,
                    color = palette.text,
                )
            }

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
                                .menuAnchor(),
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
                maxLines = 3,
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(min = 54.dp),
            )
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
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
        FlowRow(
            horizontalArrangement = Arrangement.spacedBy(5.dp),
            verticalArrangement = Arrangement.spacedBy(5.dp),
        ) {
            TRAVEL_VIBES.forEach { (value, labelRes) ->
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

@OptIn(ExperimentalLayoutApi::class)
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
        FlowRow(
            horizontalArrangement = Arrangement.spacedBy(5.dp),
            verticalArrangement = Arrangement.spacedBy(5.dp),
        ) {
            SmallPillChip(
                label = stringResource(R.string.ai_consider_weather),
                icon = Icons.Default.WbSunny,
                active = considerations.weather,
            ) { onToggle { it.copy(weather = !it.weather) } }
            SmallPillChip(
                label = stringResource(R.string.ai_consider_location),
                icon = Icons.Default.Place,
                active = considerations.location,
            ) { onToggle { it.copy(location = !it.location) } }
            SmallPillChip(
                label = stringResource(R.string.ai_consider_trends),
                icon = Icons.Default.TrendingUp,
                active = considerations.trends,
            ) { onToggle { it.copy(trends = !it.trends) } }
            SmallPillChip(
                label = stringResource(R.string.ai_consider_gender),
                icon = Icons.Default.Person,
                active = considerations.gender,
            ) { onToggle { it.copy(gender = !it.gender) } }
            SmallPillChip(
                label = stringResource(R.string.ai_consider_age),
                icon = Icons.Default.Cake,
                active = considerations.age,
            ) { onToggle { it.copy(age = !it.age) } }
            SmallPillChip(
                label = stringResource(R.string.ai_consider_preferences),
                icon = Icons.Default.Favorite,
                active = considerations.preferences,
            ) { onToggle { it.copy(preferences = !it.preferences) } }
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
