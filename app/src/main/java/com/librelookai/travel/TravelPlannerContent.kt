package com.librelookai.travel

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Snackbar
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.librelookai.R
import com.librelookai.data.model.Trip
import com.librelookai.outfit.OutfitsViewModel
import com.librelookai.settings.ProfileViewModel
import com.librelookai.util.AiProcessingOverlay
import com.librelookai.util.LocalIsOffline
import com.librelookai.wardrobe.LocationViewModel
import com.librelookai.wardrobe.WardrobeViewModel

@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
internal fun TravelPlannerContent(
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
    var showClosetSheet by remember { mutableStateOf(false) }

    // Restrict the item pool (and inspiration styles) to the chosen closets. Empty = all.
    val sourceImages = remember(wardrobeState.images, state.sourceFolderIds) {
        if (state.sourceFolderIds.isEmpty()) wardrobeState.images
        else wardrobeState.images.filter { it.folderId in state.sourceFolderIds }
    }
    val sourceStyles = remember(outfitsState.outfits, state.sourceFolderIds) {
        if (state.sourceFolderIds.isEmpty()) outfitsState.outfits
        else outfitsState.outfits.filter { it.folderId in state.sourceFolderIds }
    }
    val selectedClosetNames = remember(state.sourceFolderIds, locationState.locations) {
        locationState.locations.filter { it.folderId in state.sourceFolderIds }.map { it.name }
    }

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

            // ---- Source-closet selector (only when there's more than one closet) ----
            if (locationState.locations.size >= 2) {
                item {
                    SourceClosetRow(
                        closetNames = selectedClosetNames,
                        onClick = { showClosetSheet = true },
                        modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp),
                    )
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
            val packingTokens by androidx.compose.runtime.produceState<com.librelookai.gemini.CostTokens?>(
                initialValue = null,
                sourceImages,
                sourceStyles,
                profileState.preferences,
                state.days,
                state.outfitCount,
                state.goal,
                state.vibes,
                state.considerationsOverride,
                outfitsState.wearHistory,
            ) {
                value = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.Default) {
                    travelViewModel.estimatePackingTokens(
                        prefs = profileState.preferences,
                        images = sourceImages,
                        styles = sourceStyles,
                        wearHistory = outfitsState.wearHistory,
                    )
                }
            }
            GenerateButton(
                hasResult = state.packingList != null,
                enabled = state.destination.isNotBlank() && !isWorking && !isOffline,
                onClick = {
                    keyboardController?.hide()
                    travelViewModel.generate(
                        prefs  = profileState.preferences,
                        images = sourceImages,
                        styles = sourceStyles,
                        wearHistory = outfitsState.wearHistory,
                    )
                },
                tokens = packingTokens,
            )
        }
    } // Column

    if (showClosetSheet) {
        com.librelookai.outfit.ClosetPickerSheet(
            locations = locationState.locations,
            selected = state.sourceFolderIds,
            onToggle = travelViewModel::toggleSourceFolder,
            onDismiss = { showClosetSheet = false },
        )
    }
}

// ---------- Travel-outfits list view ----------

