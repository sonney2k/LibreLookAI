package com.librelookai.outfit

import androidx.compose.animation.animateContentSize
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AutoAwesome
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Tune
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.librelookai.R
import com.librelookai.core.designsystem.R as DsR
import com.librelookai.settings.ProfileViewModel
import com.librelookai.wardrobe.LocationViewModel
import com.librelookai.wardrobe.WardrobeViewModel
import com.librelookai.weather.WeatherViewModel

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PredictionSetupDialog(
    generationViewModel: OutfitGenerationViewModel,
    profileViewModel: ProfileViewModel,
    weatherViewModel: WeatherViewModel,
    locationViewModel: LocationViewModel,
    wardrobeViewModel: WardrobeViewModel,
) {
    val s by generationViewModel.state.collectAsState()
    val profile by profileViewModel.state.collectAsState()
    val weather by weatherViewModel.state.collectAsState()
    val locationState by locationViewModel.state.collectAsState()
    val wardrobe by wardrobeViewModel.state.collectAsState()
    val parentContext = LocalContext.current
    val parentConfiguration = LocalConfiguration.current

    if (!s.isPredictionSetupOpen) return

    val isComposerSource = s.predictionSetupSource == PredictionSetupSource.COMPOSER
    val titleRes = if (isComposerSource) R.string.prediction_setup_title_compose
                   else R.string.prediction_setup_title
    val ctaRes   = if (isComposerSource) R.string.prediction_setup_create
                   else R.string.prediction_setup_find

    var showWeatherSheet by remember { mutableStateOf(false) }
    var showClosetSheet by remember { mutableStateOf(false) }

    // Refresh 7-day forecast lazily when the dialog appears for the first time.
    androidx.compose.runtime.LaunchedEffect(Unit) {
        weatherViewModel.refreshLocalForecast()
    }

    val sourceFolders = s.composerSourceFolderIds
    val closetNames = remember(sourceFolders, locationState.locations) {
        locationState.locations.filter { it.folderId in sourceFolders }.map { it.name }
    }
    val crossClosetImages = wardrobe.allLocationImages.ifEmpty { wardrobe.images }

    val prefsConsiderations = profile.preferences.aiConsiderations
    val effectiveConsiderations = s.composerConsiderationsOverride ?: prefsConsiderations

    Dialog(
        onDismissRequest = { generationViewModel.closePredictionSetup() },
        properties = DialogProperties(usePlatformDefaultWidth = false, dismissOnBackPress = true, dismissOnClickOutside = true),
    ) {
        CompositionLocalProvider(
            LocalContext provides parentContext,
            LocalConfiguration provides parentConfiguration,
        ) {
            Surface(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 12.dp, vertical = 16.dp),
                shape = RoundedCornerShape(20.dp),
                color = MaterialTheme.colorScheme.background,
            ) {
                Column(modifier = Modifier.fillMaxWidth()) {
                    TuneAiHeader(
                        title = stringResource(titleRes),
                        subtitle = stringResource(R.string.composer_ai_subtitle),
                        onClose = { generationViewModel.closePredictionSetup() },
                        onReset = { generationViewModel.resetComposerAi() },
                    )
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .heightIn(max = 560.dp)
                            .verticalScroll(rememberScrollState())
                            .padding(horizontal = 14.dp, vertical = 12.dp),
                        verticalArrangement = Arrangement.spacedBy(10.dp),
                    ) {
                        OccasionCard(
                            goal = s.composerFeedback,
                            onGoalChange = generationViewModel::updateComposerFeedback,
                        )
                        WeatherCard(
                            weatherMode = s.composerWeatherMode,
                            autoWeather = weather.data,
                            manualTempC = s.composerManualTempC,
                            manualPrecip = s.composerManualPrecip,
                            onModeChange = generationViewModel::setComposerWeatherMode,
                            onTempChange = generationViewModel::setComposerManualTempC,
                            onPrecipChange = generationViewModel::setComposerManualPrecip,
                            onOpenForecastPicker = { showWeatherSheet = true },
                        )
                        StyleVibeCard(
                            selected = s.composerVibes,
                            onToggle = generationViewModel::toggleComposerVibe,
                        )
                        ConsidersCard(
                            considerations = effectiveConsiderations,
                            onToggle = { transform ->
                                generationViewModel.setComposerConsideration(prefsConsiderations, transform)
                            },
                        )
                        ExpertTagsCard(
                            considerations = effectiveConsiderations,
                            onToggleTag = { dim ->
                                generationViewModel.setComposerConsideration(prefsConsiderations) { it.toggleItemTag(dim) }
                            },
                        )
                        if (locationState.locations.size >= 2) {
                            ClosetChipRow(
                                closetNames = closetNames,
                                onClick = { showClosetSheet = true },
                            )
                        }
                        SuggestionCountSelector(
                            count = s.composerSuggestionCount,
                            onCountChange = generationViewModel::setComposerSuggestionCount,
                        )
                    }
                    HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                    // Exact BYOK cost: re-derive the real prediction prompt off the main thread
                    // whenever an input that changes its size changes (wardrobe, styles, prefs,
                    // suggestion count, source filter, vibes, weather mode).
                    // Keys must mirror every prompt-affecting input of buildPrediction/ComposerPrompt
                    // so the estimate refreshes when any Tune-AI pill is tapped — Considers chips and
                    // Expert-tags (e.g. "only consider color") both live in composerConsiderationsOverride,
                    // which shrinks per-item JSON and therefore the cost.
                    val predictionTokens by androidx.compose.runtime.produceState<com.librelookai.gemini.CostTokens?>(
                        initialValue = null,
                        crossClosetImages,
                        s.outfits,
                        profile.preferences,
                        weather.data,
                        s.composerSuggestionCount,
                        s.composerSourceFolderIds,
                        s.composerVibes,
                        s.composerSlots,
                        s.predictionSetupSource,
                        s.composerConsiderationsOverride,
                        s.composerWeatherMode,
                        s.composerManualSeason,
                        s.composerManualTempC,
                        s.composerManualPrecip,
                        s.composerForecastDate,
                        s.composerTripContext,
                        s.wearHistory,
                    ) {
                        value = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.Default) {
                            // Generate-with-AI from the composer routes to enhanceComposerWithAi, so
                            // price the composer prompt; the standalone setup prices the prediction.
                            if (s.predictionSetupSource == PredictionSetupSource.COMPOSER) {
                                generationViewModel.estimateComposerTokens(
                                    prefs = profile.preferences,
                                    weather = weather.data,
                                    images = crossClosetImages,
                                )
                            } else {
                                generationViewModel.estimatePredictionTokens(
                                    prefs = profile.preferences,
                                    weather = weather.data,
                                    images = crossClosetImages,
                                )
                            }
                        }
                    }
                    TuneAiBottomBar(
                        ctaLabel = stringResource(ctaRes),
                        bulkCount = s.composerSuggestionCount,
                        onCancel = { generationViewModel.closePredictionSetup() },
                        onGenerate = {
                            generationViewModel.submitPredictionSetup(
                                prefs = profile.preferences,
                                weather = weather.data,
                                images = crossClosetImages,
                            )
                        },
                        tokens = predictionTokens,
                    )
                }
            }
        }
    }

    if (showWeatherSheet) {
        ForecastDayPickerSheet(
            todayWeather = weather.data,
            forecasts = weather.localForecast,
            selectedDate = s.composerForecastDate,
            weatherMode = s.composerWeatherMode,
            onModeChange = { mode ->
                generationViewModel.setComposerWeatherMode(mode)
                if (mode == com.librelookai.outfit.ComposerWeatherMode.MANUAL) {
                    // Manual override invalidates any picked future date.
                    generationViewModel.setComposerForecastDate(null)
                }
            },
            manualSeason = s.composerManualSeason,
            onManualSeason = generationViewModel::setComposerManualSeason,
            manualTempC = s.composerManualTempC,
            onManualTempC = generationViewModel::setComposerManualTempC,
            manualPrecip = s.composerManualPrecip,
            onManualPrecip = generationViewModel::setComposerManualPrecip,
            onSelect = { date ->
                // Picking a forecast day implies leaving manual mode.
                generationViewModel.setComposerWeatherMode(com.librelookai.outfit.ComposerWeatherMode.AUTO)
                generationViewModel.setComposerForecastDate(date)
                showWeatherSheet = false
            },
            onDismiss = { showWeatherSheet = false },
        )
    }
    if (showClosetSheet) {
        ClosetPickerSheet(
            locations = locationState.locations,
            selected = s.composerSourceFolderIds,
            onToggle = generationViewModel::toggleComposerSourceFolder,
            onDismiss = { showClosetSheet = false },
        )
    }
}

// ─── Tune-AI header / bottom bar ─────────────────────────────────────────────

@Composable
private fun TuneAiHeader(
    title: String,
    subtitle: String,
    onClose: () -> Unit,
    onReset: () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(start = 4.dp, end = 4.dp, top = 4.dp, bottom = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        IconButton(onClick = onClose, modifier = Modifier.size(40.dp)) {
            Icon(Icons.Default.Close, contentDescription = stringResource(DsR.string.action_close))
        }
        Spacer(Modifier.width(4.dp))
        Column(modifier = Modifier.weight(1f)) {
            Text(
                title,
                fontSize = 15.sp,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.onSurface,
            )
            Text(
                subtitle,
                fontSize = 11.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(top = 1.dp),
            )
        }
        TextButton(
            onClick = onReset,
            contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = 10.dp, vertical = 6.dp),
        ) {
            Text(
                stringResource(R.string.action_reset),
                fontSize = 12.sp,
                fontWeight = FontWeight.SemiBold,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
    HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
}

@Composable
private fun TuneAiBottomBar(
    ctaLabel: String,
    bulkCount: Int,
    onCancel: () -> Unit,
    onGenerate: () -> Unit,
    tokens: com.librelookai.gemini.CostTokens? = null,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .background(MaterialTheme.colorScheme.background)
            .padding(start = 14.dp, end = 14.dp, top = 10.dp, bottom = 18.dp)
            .navigationBarsPadding(),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            modifier = Modifier
                .height(48.dp)
                .clip(RoundedCornerShape(24.dp))
                .border(1.5.dp, MaterialTheme.colorScheme.outline, RoundedCornerShape(24.dp))
                .clickable(onClick = onCancel)
                .padding(horizontal = 18.dp),
            contentAlignment = Alignment.Center,
        ) {
            Text(
                stringResource(DsR.string.action_cancel),
                fontSize = 13.sp,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        Button(
            onClick = onGenerate,
            modifier = Modifier
                .weight(1f)
                .height(48.dp),
            shape = RoundedCornerShape(24.dp),
            colors = ButtonDefaults.buttonColors(
                containerColor = MaterialTheme.colorScheme.primary,
                contentColor = MaterialTheme.colorScheme.onPrimary,
            ),
        ) {
            com.librelookai.billing.CostBadge(
                action = com.librelookai.gemini.GeminiActionId.GENERATE_TEXT,
                bulkCount = bulkCount,
                tokens = tokens,
            )
            Icon(Icons.Default.AutoAwesome, contentDescription = null, modifier = Modifier.size(16.dp))
            Spacer(Modifier.width(6.dp))
            Text(ctaLabel, fontSize = 14.sp, fontWeight = FontWeight.Bold)
        }
    }
}

// ─── Section card scaffold ───────────────────────────────────────────────────

@Composable
internal fun SectionCard(
    icon: ImageVector,
    title: String,
    hint: String? = null,
    content: @Composable () -> Unit,
) {
    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(16.dp),
        color = MaterialTheme.colorScheme.surface,
        border = androidx.compose.foundation.BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
    ) {
        Column(
            modifier = Modifier
                .animateContentSize()
                .padding(14.dp),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(
                    modifier = Modifier
                        .size(24.dp)
                        .clip(RoundedCornerShape(8.dp))
                        .background(MaterialTheme.colorScheme.primaryContainer),
                    contentAlignment = Alignment.Center,
                ) {
                    Icon(
                        icon,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.size(14.dp),
                    )
                }
                Spacer(Modifier.width(8.dp))
                Text(
                    title,
                    fontSize = 13.sp,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.onSurface,
                )
            }
            if (hint != null) {
                Spacer(Modifier.height(4.dp))
                Text(
                    hint,
                    fontSize = 11.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Spacer(Modifier.height(10.dp))
            content()
        }
    }
}

// ─── 1) Occasion ─────────────────────────────────────────────────────────────

