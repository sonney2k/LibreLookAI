package com.librelookai.travel
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.CalendarMonth
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.FlightTakeoff
import androidx.compose.material.icons.filled.MoveToInbox
import androidx.compose.material.icons.filled.Remove
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DatePicker
import androidx.compose.material3.DatePickerDialog
import androidx.compose.material3.ExperimentalMaterial3Api
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
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
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
            // ---- Input section ----
            item {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp, vertical = 16.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    OutlinedTextField(
                        value = state.destination,
                        onValueChange = travelViewModel::updateDestination,
                        label = { Text(stringResource(R.string.travel_destination)) },
                        placeholder = { Text(stringResource(R.string.travel_destination_placeholder)) },
                        leadingIcon = { Icon(Icons.Default.FlightTakeoff, contentDescription = null) },
                        trailingIcon = if (state.destination.isNotEmpty()) {
                            { IconButton(onClick = { travelViewModel.updateDestination("") }) {
                                Icon(Icons.Default.Close, contentDescription = "Clear")
                            } }
                        } else null,
                        singleLine = true,
                        keyboardOptions = KeyboardOptions(imeAction = ImeAction.Next),
                        modifier = Modifier.fillMaxWidth(),
                    )

                    // Duration + Start date
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(12.dp),
                    ) {
                        // Duration stepper (left)
                        Column(modifier = Modifier.weight(1f)) {
                            Text(stringResource(R.string.travel_duration), style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant)
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(4.dp),
                            ) {
                                IconButton(
                                    onClick = { travelViewModel.updateDays(state.days - 1) },
                                    enabled = state.days > 1,
                                ) {
                                    Icon(Icons.Default.Remove, contentDescription = "Fewer days")
                                }
                                Text(
                                    "${state.days}d",
                                    style = MaterialTheme.typography.bodyLarge,
                                    fontWeight = FontWeight.Medium,
                                    textAlign = TextAlign.Center,
                                )
                                IconButton(
                                    onClick = { travelViewModel.updateDays(state.days + 1) },
                                    enabled = state.days < 21,
                                ) {
                                    Icon(Icons.Default.Add, contentDescription = "More days")
                                }
                            }
                        }
                        // Start date picker (right)
                        Column(modifier = Modifier.weight(1f)) {
                            Text(stringResource(R.string.travel_start_date), style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant)
                            Spacer(Modifier.height(4.dp))
                            StartDatePicker(
                                selectedDate = state.startDate,
                                onDateSelected = travelViewModel::updateStartDate,
                            )
                        }
                    }

                    // Outfit-count stepper. Default tracks `days` (one outfit per day) until the
                    // user explicitly sets a value; from then on it's independent of duration so
                    // changing days doesn't silently overwrite a custom count.
                    val effectiveOutfitCount = state.outfitCount ?: state.days
                    Column {
                        Text(stringResource(R.string.travel_outfit_count), style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant)
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(4.dp),
                        ) {
                            IconButton(
                                onClick = { travelViewModel.updateOutfitCount(effectiveOutfitCount - 1) },
                                enabled = effectiveOutfitCount > 1,
                            ) {
                                Icon(Icons.Default.Remove, contentDescription = null)
                            }
                            Text(
                                effectiveOutfitCount.toString(),
                                style = MaterialTheme.typography.bodyLarge,
                                fontWeight = FontWeight.Medium,
                                textAlign = TextAlign.Center,
                            )
                            IconButton(
                                onClick = { travelViewModel.updateOutfitCount(effectiveOutfitCount + 1) },
                                enabled = effectiveOutfitCount < 21,
                            ) {
                                Icon(Icons.Default.Add, contentDescription = null)
                            }
                            if (state.outfitCount != null && state.outfitCount != state.days) {
                                TextButton(onClick = { travelViewModel.updateOutfitCount(null) }) {
                                    Text(stringResource(R.string.travel_outfit_count_reset))
                                }
                            }
                        }
                    }

                    // Free-text AI prompt — extra context like "business meetings + evening dinners".
                    OutlinedTextField(
                        value = state.goal,
                        onValueChange = travelViewModel::updateGoal,
                        label = { Text(stringResource(R.string.travel_goal)) },
                        placeholder = { Text(stringResource(R.string.travel_goal_placeholder)) },
                        minLines = 1,
                        maxLines = 3,
                        modifier = Modifier.fillMaxWidth(),
                    )

                    // Per-trip override of the AI considerations (location, trends, gender, age,
                    // preferences). Same widget as Find/Create-with-AI.
                    val prefsConsiderations = profileState.preferences.aiConsiderations
                    val effectiveConsiderations = state.considerationsOverride ?: prefsConsiderations
                    AiConsiderationsStrip(
                        considerations = effectiveConsiderations,
                        onToggle = { transform ->
                            travelViewModel.setConsideration(prefsConsiderations, transform)
                        },
                    )

                    Button(
                        onClick = {
                            keyboardController?.hide()
                            travelViewModel.generate(
                                prefs  = profileState.preferences,
                                images = wardrobeState.images,
                                styles = outfitsState.outfits,
                            )
                        },
                        enabled = state.destination.isNotEmpty() && !isWorking && !isOffline,
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        Text(if (state.packingList != null) stringResource(R.string.travel_regenerate) else stringResource(R.string.travel_generate))
                    }
                }
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

// ---------- Start date picker ----------

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun StartDatePicker(
    selectedDate: LocalDate,
    onDateSelected: (LocalDate) -> Unit,
) {
    var showDialog by remember { mutableStateOf(false) }
    val datePickerState = rememberDatePickerState(
        initialSelectedDateMillis = selectedDate.toEpochDay() * 86_400_000L,
    )

    OutlinedButton(
        onClick = { showDialog = true },
        modifier = Modifier.fillMaxWidth(),
        contentPadding = PaddingValues(horizontal = 12.dp, vertical = 8.dp),
    ) {
        Icon(Icons.Default.CalendarMonth, contentDescription = null, modifier = Modifier.size(16.dp))
        Spacer(Modifier.width(6.dp))
        Text(selectedDate.format(DateTimeFormatter.ofPattern("MMM d, yyyy")))
    }

    if (showDialog) {
        DatePickerDialog(
            onDismissRequest = { showDialog = false },
            confirmButton = {
                TextButton(onClick = {
                    datePickerState.selectedDateMillis?.let {
                        onDateSelected(LocalDate.ofEpochDay(it / 86_400_000L))
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

// ---------- Refinement section (shared composable reuse) ----------
// RefinementSection is defined in OutfitsScreen.kt and is internal to the package.
