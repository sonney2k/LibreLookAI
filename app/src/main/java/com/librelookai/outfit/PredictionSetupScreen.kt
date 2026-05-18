package com.librelookai.outfit
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AutoAwesome
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TextFieldDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.librelookai.settings.AiConsiderations
import com.librelookai.settings.AiConsiderationsStrip
import com.librelookai.settings.ProfileViewModel
import com.librelookai.wardrobe.LocationViewModel
import com.librelookai.wardrobe.WardrobeViewModel
import com.librelookai.weather.WeatherViewModel
import com.librelookai.R

/**
 * Setup dialog launched by the "Find with AI" button on the Outfits tab. Mirrors the upper
 * part of the outfit composer (goal text + weather/closet/vibe chips) and submits to the
 * existing prediction flow when the user taps "Find".
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PredictionSetupDialog(
    outfitsViewModel: OutfitsViewModel,
    profileViewModel: ProfileViewModel,
    weatherViewModel: WeatherViewModel,
    locationViewModel: LocationViewModel,
    wardrobeViewModel: WardrobeViewModel,
) {
    val s by outfitsViewModel.state.collectAsState()
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

    Dialog(
        onDismissRequest = { outfitsViewModel.closePredictionSetup() },
        properties = DialogProperties(usePlatformDefaultWidth = false, dismissOnBackPress = true, dismissOnClickOutside = true),
    ) {
        CompositionLocalProvider(
            LocalContext provides parentContext,
            LocalConfiguration provides parentConfiguration,
        ) {
            Surface(
                modifier = Modifier.fillMaxWidth().padding(16.dp),
                shape = RoundedCornerShape(20.dp),
                color = MaterialTheme.colorScheme.background,
            ) {
                Column(
                    modifier = Modifier.padding(16.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(
                            Icons.Default.AutoAwesome,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.primary,
                        )
                        Spacer(Modifier.width(8.dp))
                        Text(
                            stringResource(titleRes),
                            style = MaterialTheme.typography.titleMedium,
                            modifier = Modifier.weight(1f),
                        )
                        IconButton(onClick = { outfitsViewModel.closePredictionSetup() }) {
                            Icon(Icons.Default.Close, contentDescription = stringResource(R.string.action_cancel))
                        }
                    }

                    GoalInput(
                        goal = s.composerFeedback,
                        onGoalChange = outfitsViewModel::updateComposerFeedback,
                    )

                    ContextStrip(
                        weatherMode = s.composerWeatherMode,
                        autoWeather = weather.data,
                        manualTempC = s.composerManualTempC,
                        forecastDate = s.composerForecastDate,
                        forecastDayPreview = s.composerForecastDate?.let { d ->
                            weather.localForecast.find { it.date == d }
                        },
                        closetNames = closetNames,
                        closetPickerAvailable = locationState.locations.size >= 2,
                        selectedVibes = s.composerVibes,
                        onToggleVibe = outfitsViewModel::toggleComposerVibe,
                        onClickWeather = { showWeatherSheet = true },
                        onClickCloset = { showClosetSheet = true },
                    )

                    val prefsConsiderations = profile.preferences.aiConsiderations
                    val effectiveConsiderations = s.composerConsiderationsOverride ?: prefsConsiderations
                    AiConsiderationsStrip(
                        considerations = effectiveConsiderations,
                        onToggle = { transform ->
                            outfitsViewModel.setComposerConsideration(prefsConsiderations, transform)
                        },
                    )

                    SuggestionCountSelector(
                        count = s.composerSuggestionCount,
                        onCountChange = outfitsViewModel::setComposerSuggestionCount,
                    )

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.End,
                    ) {
                        TextButton(onClick = { outfitsViewModel.closePredictionSetup() }) {
                            Text(stringResource(R.string.action_cancel))
                        }
                        Spacer(Modifier.width(8.dp))
                        Button(
                            onClick = {
                                outfitsViewModel.submitPredictionSetup(
                                    prefs = profile.preferences,
                                    weather = weather.data,
                                    images = crossClosetImages,
                                )
                            },
                            colors = ButtonDefaults.buttonColors(
                                containerColor = MaterialTheme.colorScheme.primary,
                                contentColor = MaterialTheme.colorScheme.onPrimary,
                            ),
                        ) {
                            // GENERATE_TEXT scales with the number of outfits requested.
                            com.librelookai.billing.CostBadge(
                                action = com.librelookai.gemini.GeminiActionId.GENERATE_TEXT,
                                bulkCount = s.composerSuggestionCount,
                            )
                            Icon(Icons.Default.AutoAwesome, contentDescription = null, modifier = Modifier.size(16.dp))
                            Spacer(Modifier.width(6.dp))
                            Text(stringResource(ctaRes))
                        }
                    }
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
                outfitsViewModel.setComposerWeatherMode(mode)
                if (mode == com.librelookai.outfit.ComposerWeatherMode.MANUAL) {
                    // Manual override invalidates any picked future date.
                    outfitsViewModel.setComposerForecastDate(null)
                }
            },
            manualSeason = s.composerManualSeason,
            onManualSeason = outfitsViewModel::setComposerManualSeason,
            manualTempC = s.composerManualTempC,
            onManualTempC = outfitsViewModel::setComposerManualTempC,
            manualPrecip = s.composerManualPrecip,
            onManualPrecip = outfitsViewModel::setComposerManualPrecip,
            onSelect = { date ->
                // Picking a forecast day implies leaving manual mode.
                outfitsViewModel.setComposerWeatherMode(com.librelookai.outfit.ComposerWeatherMode.AUTO)
                outfitsViewModel.setComposerForecastDate(date)
                showWeatherSheet = false
            },
            onDismiss = { showWeatherSheet = false },
        )
    }
    if (showClosetSheet) {
        ClosetPickerSheet(
            locations = locationState.locations,
            selected = s.composerSourceFolderIds,
            onToggle = outfitsViewModel::toggleComposerSourceFolder,
            onDismiss = { showClosetSheet = false },
        )
    }
}

@Composable
private fun GoalInput(goal: String, onGoalChange: (String) -> Unit) {
    val primary = MaterialTheme.colorScheme.primary
    val gradient = Brush.linearGradient(
        colors = listOf(
            MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.55f),
            MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.85f),
        ),
    )
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .background(gradient, RoundedCornerShape(16.dp))
            .border(1.dp, primary.copy(alpha = 0.33f), RoundedCornerShape(16.dp))
            .padding(horizontal = 12.dp, vertical = 6.dp),
    ) {
        OutlinedTextField(
            value = goal,
            onValueChange = onGoalChange,
            placeholder = {
                Text(
                    stringResource(R.string.composer_goal_placeholder),
                    style = MaterialTheme.typography.bodyMedium.copy(fontSize = 13.sp),
                )
            },
            singleLine = true,
            textStyle = MaterialTheme.typography.bodyMedium.copy(fontSize = 13.sp, fontWeight = FontWeight.SemiBold),
            modifier = Modifier.fillMaxWidth(),
            colors = TextFieldDefaults.colors(
                focusedContainerColor = Color.Transparent,
                unfocusedContainerColor = Color.Transparent,
                disabledContainerColor = Color.Transparent,
                focusedIndicatorColor = Color.Transparent,
                unfocusedIndicatorColor = Color.Transparent,
                disabledIndicatorColor = Color.Transparent,
            ),
        )
    }
}

@Composable
private fun SuggestionCountSelector(count: Int, onCountChange: (Int) -> Unit) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Text(
            stringResource(R.string.prediction_setup_suggestions),
            style = MaterialTheme.typography.labelMedium,
            modifier = Modifier.weight(1f),
        )
        IconButton(
            onClick = { if (count > 1) onCountChange(count - 1) },
            enabled = count > 1,
        ) {
            Text("−", style = MaterialTheme.typography.titleLarge)
        }
        Text(
            count.toString(),
            style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold),
            modifier = Modifier.widthIn(min = 28.dp),
        )
        IconButton(
            onClick = { if (count < 10) onCountChange(count + 1) },
            enabled = count < 10,
        ) {
            Text("+", style = MaterialTheme.typography.titleLarge)
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class, androidx.compose.foundation.layout.ExperimentalLayoutApi::class)
@Composable
private fun ForecastDayPickerSheet(
    todayWeather: com.librelookai.weather.WeatherData?,
    forecasts: List<com.librelookai.data.model.DayForecast>,
    selectedDate: String?,
    weatherMode: com.librelookai.outfit.ComposerWeatherMode,
    onModeChange: (com.librelookai.outfit.ComposerWeatherMode) -> Unit,
    manualSeason: String,
    onManualSeason: (String) -> Unit,
    manualTempC: Int?,
    onManualTempC: (Int?) -> Unit,
    manualPrecip: String,
    onManualPrecip: (String) -> Unit,
    onSelect: (String?) -> Unit,
    onDismiss: () -> Unit,
) {
    val parentContext = LocalContext.current
    val parentConfiguration = LocalConfiguration.current
    val sheetState = androidx.compose.material3.rememberModalBottomSheetState(skipPartiallyExpanded = true)
    androidx.compose.material3.ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState,
    ) {
        CompositionLocalProvider(
            LocalContext provides parentContext,
            LocalConfiguration provides parentConfiguration,
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .navigationBarsPadding()
                    .padding(horizontal = 16.dp, vertical = 8.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Text(
                    stringResource(R.string.composer_weather_title),
                    style = MaterialTheme.typography.titleMedium,
                )

                // Mode toggle — Forecast (auto/picked day) vs. Manual override.
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    androidx.compose.material3.FilterChip(
                        selected = weatherMode == com.librelookai.outfit.ComposerWeatherMode.AUTO,
                        onClick = { onModeChange(com.librelookai.outfit.ComposerWeatherMode.AUTO) },
                        label = { Text(stringResource(R.string.composer_weather_auto)) },
                    )
                    androidx.compose.material3.FilterChip(
                        selected = weatherMode == com.librelookai.outfit.ComposerWeatherMode.MANUAL,
                        onClick = { onModeChange(com.librelookai.outfit.ComposerWeatherMode.MANUAL) },
                        label = { Text(stringResource(R.string.composer_weather_manual)) },
                    )
                }

                if (weatherMode == com.librelookai.outfit.ComposerWeatherMode.MANUAL) {
                    ManualWeatherControls(
                        season = manualSeason,
                        onSeason = onManualSeason,
                        tempC = manualTempC,
                        onTempC = onManualTempC,
                        precip = manualPrecip,
                        onPrecip = onManualPrecip,
                    )
                } else {
                    Text(
                        stringResource(R.string.prediction_setup_weather_pick_day),
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )

                    val today = java.time.LocalDate.now()
                    val todayLabel = stringResource(R.string.prediction_setup_weather_today)
                    val tomorrowLabel = stringResource(R.string.prediction_setup_weather_tomorrow)

                    // "Today" row — uses live current-weather data, not the forecast block.
                    ForecastDayRow(
                        label = todayLabel,
                        sub = todayWeather?.cityName?.takeIf { it.isNotEmpty() } ?: "",
                        emoji = todayWeather?.let { com.librelookai.weather.wmoEmoji(it.weatherCode) } ?: "—",
                        tempLine = todayWeather?.let { "${it.temperatureCelsius.toInt()}°C" } ?: "",
                        selected = selectedDate == null,
                        onClick = { onSelect(null) },
                    )

                    forecasts.take(7).forEach { day ->
                        val date = runCatching { java.time.LocalDate.parse(day.date) }.getOrNull()
                        if (date == null || !date.isAfter(today)) return@forEach
                        val daysAhead = java.time.temporal.ChronoUnit.DAYS.between(today, date).toInt()
                        val label = when (daysAhead) {
                            1 -> tomorrowLabel
                            else -> date.format(java.time.format.DateTimeFormatter.ofPattern("EEE, MMM d"))
                        }
                        ForecastDayRow(
                            label = label,
                            sub = if (daysAhead != 1) "" else date.format(java.time.format.DateTimeFormatter.ofPattern("MMM d")),
                            emoji = com.librelookai.weather.wmoEmoji(day.weatherCode),
                            tempLine = "${day.minTempC.toInt()}° / ${day.maxTempC.toInt()}°",
                            selected = selectedDate == day.date,
                            onClick = { onSelect(day.date) },
                        )
                    }
                }

                Spacer(Modifier.height(16.dp))
            }
        }
    }
}

@OptIn(androidx.compose.foundation.layout.ExperimentalLayoutApi::class)
@Composable
private fun ManualWeatherControls(
    season: String,
    onSeason: (String) -> Unit,
    tempC: Int?,
    onTempC: (Int?) -> Unit,
    precip: String,
    onPrecip: (String) -> Unit,
) {
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Text(stringResource(R.string.composer_weather_season), style = MaterialTheme.typography.labelMedium)
        androidx.compose.foundation.layout.FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            val seasons = listOf(
                "Spring" to R.string.composer_season_spring,
                "Summer" to R.string.composer_season_summer,
                "Fall" to R.string.composer_season_fall,
                "Winter" to R.string.composer_season_winter,
            )
            seasons.forEach { (value, labelRes) ->
                androidx.compose.material3.FilterChip(
                    selected = season == value,
                    onClick = { onSeason(if (season == value) "" else value) },
                    label = { Text(stringResource(labelRes)) },
                )
            }
        }
        Text(stringResource(R.string.composer_weather_temp), style = MaterialTheme.typography.labelMedium)
        androidx.compose.foundation.layout.FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            listOf(-5, 5, 15, 22, 30).forEach { t ->
                androidx.compose.material3.FilterChip(
                    selected = tempC == t,
                    onClick = { onTempC(if (tempC == t) null else t) },
                    label = { Text(stringResource(R.string.composer_temp_value, t)) },
                )
            }
        }
        Text(stringResource(R.string.composer_weather_precip), style = MaterialTheme.typography.labelMedium)
        androidx.compose.foundation.layout.FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            val precips = listOf(
                "None" to R.string.composer_precip_none,
                "Light" to R.string.composer_precip_light,
                "Heavy" to R.string.composer_precip_heavy,
            )
            precips.forEach { (value, labelRes) ->
                androidx.compose.material3.FilterChip(
                    selected = precip == value,
                    onClick = { onPrecip(if (precip == value) "" else value) },
                    label = { Text(stringResource(labelRes)) },
                )
            }
        }
    }
}

@Composable
private fun ForecastDayRow(
    label: String,
    sub: String,
    emoji: String,
    tempLine: String,
    selected: Boolean,
    onClick: () -> Unit,
) {
    val borderColor = if (selected) MaterialTheme.colorScheme.primary
                      else MaterialTheme.colorScheme.outlineVariant
    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .border(if (selected) 2.dp else 1.dp, borderColor, RoundedCornerShape(12.dp)),
        shape = RoundedCornerShape(12.dp),
        color = if (selected) MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.4f)
                else MaterialTheme.colorScheme.surface,
        onClick = onClick,
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 12.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text(emoji, fontSize = 24.sp)
            Column(modifier = Modifier.weight(1f)) {
                Text(label, style = MaterialTheme.typography.bodyMedium.copy(fontWeight = FontWeight.SemiBold))
                if (sub.isNotEmpty()) {
                    Text(sub, style = MaterialTheme.typography.bodySmall,
                         color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
            if (tempLine.isNotEmpty()) {
                Text(tempLine, style = MaterialTheme.typography.bodyMedium)
            }
        }
    }
}
