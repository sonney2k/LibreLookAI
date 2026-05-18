package com.librelookai.outfit
import androidx.compose.animation.animateContentSize
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AutoAwesome
import androidx.compose.material.icons.filled.Cake
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material.icons.filled.Person
import androidx.compose.material.icons.filled.Place
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.TrendingUp
import androidx.compose.material.icons.filled.Tune
import androidx.compose.material.icons.filled.WbSunny
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
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
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.librelookai.settings.AiConsiderations
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

    val prefsConsiderations = profile.preferences.aiConsiderations
    val effectiveConsiderations = s.composerConsiderationsOverride ?: prefsConsiderations

    Dialog(
        onDismissRequest = { outfitsViewModel.closePredictionSetup() },
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
                        onClose = { outfitsViewModel.closePredictionSetup() },
                        onReset = { outfitsViewModel.resetComposerAi() },
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
                            onGoalChange = outfitsViewModel::updateComposerFeedback,
                        )
                        WeatherCard(
                            weatherMode = s.composerWeatherMode,
                            autoWeather = weather.data,
                            manualTempC = s.composerManualTempC,
                            manualPrecip = s.composerManualPrecip,
                            onModeChange = outfitsViewModel::setComposerWeatherMode,
                            onTempChange = outfitsViewModel::setComposerManualTempC,
                            onPrecipChange = outfitsViewModel::setComposerManualPrecip,
                            onOpenForecastPicker = { showWeatherSheet = true },
                        )
                        StyleVibeCard(
                            selected = s.composerVibes,
                            onToggle = outfitsViewModel::toggleComposerVibe,
                        )
                        ConsidersCard(
                            considerations = effectiveConsiderations,
                            onToggle = { transform ->
                                outfitsViewModel.setComposerConsideration(prefsConsiderations, transform)
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
                            onCountChange = outfitsViewModel::setComposerSuggestionCount,
                        )
                    }
                    HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                    TuneAiBottomBar(
                        ctaLabel = stringResource(ctaRes),
                        bulkCount = s.composerSuggestionCount,
                        onCancel = { outfitsViewModel.closePredictionSetup() },
                        onGenerate = {
                            outfitsViewModel.submitPredictionSetup(
                                prefs = profile.preferences,
                                weather = weather.data,
                                images = crossClosetImages,
                            )
                        },
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
            Icon(Icons.Default.Close, contentDescription = stringResource(R.string.action_cancel))
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
                stringResource(R.string.action_cancel),
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
            )
            Icon(Icons.Default.AutoAwesome, contentDescription = null, modifier = Modifier.size(16.dp))
            Spacer(Modifier.width(6.dp))
            Text(ctaLabel, fontSize = 14.sp, fontWeight = FontWeight.Bold)
        }
    }
}

// ─── Section card scaffold ───────────────────────────────────────────────────

@Composable
private fun SectionCard(
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

@Composable
private fun OccasionCard(goal: String, onGoalChange: (String) -> Unit) {
    SectionCard(
        icon = Icons.Default.AutoAwesome,
        title = stringResource(R.string.composer_section_occasion),
        hint = stringResource(R.string.composer_occasion_hint),
    ) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(min = 48.dp)
                .clip(RoundedCornerShape(12.dp))
                .background(MaterialTheme.colorScheme.surfaceVariant)
                .border(1.dp, MaterialTheme.colorScheme.outlineVariant, RoundedCornerShape(12.dp))
                .padding(horizontal = 12.dp, vertical = 4.dp),
        ) {
            OutlinedTextField(
                value = goal,
                onValueChange = onGoalChange,
                placeholder = {
                    Text(
                        stringResource(R.string.composer_goal_placeholder),
                        fontSize = 14.sp,
                    )
                },
                trailingIcon = {
                    Icon(
                        Icons.Default.Edit,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.size(14.dp),
                    )
                },
                minLines = 1,
                maxLines = 3,
                textStyle = MaterialTheme.typography.bodyMedium.copy(
                    fontSize = 14.sp,
                    fontWeight = FontWeight.Medium,
                ),
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
}

// ─── 2) Weather ──────────────────────────────────────────────────────────────

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun WeatherCard(
    weatherMode: ComposerWeatherMode,
    autoWeather: com.librelookai.weather.WeatherData?,
    manualTempC: Int?,
    manualPrecip: String,
    onModeChange: (ComposerWeatherMode) -> Unit,
    onTempChange: (Int?) -> Unit,
    onPrecipChange: (String) -> Unit,
    onOpenForecastPicker: () -> Unit,
) {
    SectionCard(
        icon = Icons.Default.WbSunny,
        title = stringResource(R.string.composer_section_weather),
    ) {
        val autoLabel = remember(autoWeather) {
            buildString {
                append("Auto")
                autoWeather?.cityName?.takeIf { it.isNotEmpty() }?.let { append(" · ").append(it) }
                autoWeather?.temperatureCelsius?.let { append(" ").append(it.toInt()).append("°") }
            }
        }
        Row(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
            SmallPillChip(
                label = autoLabel,
                icon = Icons.Default.Refresh,
                active = weatherMode == ComposerWeatherMode.AUTO,
                onClick = {
                    onModeChange(ComposerWeatherMode.AUTO)
                    onTempChange(null)
                    onPrecipChange("")
                },
            )
            SmallPillChip(
                label = stringResource(R.string.composer_weather_manual),
                icon = Icons.Default.Tune,
                active = weatherMode == ComposerWeatherMode.MANUAL,
                onClick = { onModeChange(ComposerWeatherMode.MANUAL) },
            )
            Spacer(Modifier.weight(1f))
            TextButton(
                onClick = onOpenForecastPicker,
                contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = 8.dp, vertical = 4.dp),
            ) {
                Text(
                    stringResource(R.string.prediction_setup_weather_pick_day),
                    fontSize = 11.sp,
                    fontWeight = FontWeight.SemiBold,
                )
            }
        }
        if (weatherMode == ComposerWeatherMode.MANUAL) {
            Spacer(Modifier.height(10.dp))
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text(
                        stringResource(R.string.composer_weather_temp).uppercase(),
                        fontSize = 11.sp,
                        fontWeight = FontWeight.SemiBold,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(5.dp), verticalArrangement = Arrangement.spacedBy(5.dp)) {
                        listOf(-5, 5, 12, 18, 22, 28).forEach { t ->
                            SmallPillChip(
                                label = "${t}°",
                                icon = null,
                                active = manualTempC == t,
                                onClick = { onTempChange(if (manualTempC == t) null else t) },
                            )
                        }
                    }
                }
                Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text(
                        stringResource(R.string.composer_weather_precip).uppercase(),
                        fontSize = 11.sp,
                        fontWeight = FontWeight.SemiBold,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(5.dp), verticalArrangement = Arrangement.spacedBy(5.dp)) {
                        val precips = listOf(
                            "None" to R.string.composer_precip_none,
                            "Light" to R.string.composer_precip_light,
                            "Heavy" to R.string.composer_precip_heavy,
                        )
                        precips.forEach { (value, labelRes) ->
                            SmallPillChip(
                                label = stringResource(labelRes),
                                icon = null,
                                active = manualPrecip == value,
                                onClick = { onPrecipChange(if (manualPrecip == value) "" else value) },
                            )
                        }
                    }
                }
            }
        }
    }
}

// ─── 3) Style vibe ───────────────────────────────────────────────────────────

private val TUNE_VIBES: List<Pair<String, Int>> = listOf(
    "Casual" to R.string.composer_vibe_casual,
    "Sporty" to R.string.composer_vibe_sporty,
    "Formal" to R.string.composer_vibe_formal,
    "Business" to R.string.composer_vibe_business,
    "Streetwear" to R.string.composer_vibe_streetwear,
    "Minimalist" to R.string.composer_vibe_minimalist,
    "Classic" to R.string.composer_vibe_classic,
    "Elegant" to R.string.composer_vibe_elegant,
)

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun StyleVibeCard(selected: Set<String>, onToggle: (String) -> Unit) {
    SectionCard(
        icon = Icons.Default.AutoAwesome,
        title = stringResource(R.string.composer_section_vibe),
        hint = stringResource(R.string.composer_vibe_count, selected.size),
    ) {
        FlowRow(
            horizontalArrangement = Arrangement.spacedBy(5.dp),
            verticalArrangement = Arrangement.spacedBy(5.dp),
        ) {
            TUNE_VIBES.forEach { (value, labelRes) ->
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

// ─── 4) AI considers ─────────────────────────────────────────────────────────

@Composable
private fun ConsidersCard(
    considerations: AiConsiderations,
    onToggle: ((AiConsiderations) -> AiConsiderations) -> Unit,
) {
    val rows = listOf(
        ConsiderationRow(R.string.ai_consider_weather, Icons.Default.WbSunny, considerations.weather) { onToggle { it.copy(weather = !it.weather) } },
        ConsiderationRow(R.string.ai_consider_location, Icons.Default.Place, considerations.location) { onToggle { it.copy(location = !it.location) } },
        ConsiderationRow(R.string.ai_consider_trends, Icons.Default.TrendingUp, considerations.trends) { onToggle { it.copy(trends = !it.trends) } },
        ConsiderationRow(R.string.ai_consider_gender, Icons.Default.Person, considerations.gender) { onToggle { it.copy(gender = !it.gender) } },
        ConsiderationRow(R.string.ai_consider_age, Icons.Default.Cake, considerations.age) { onToggle { it.copy(age = !it.age) } },
        ConsiderationRow(R.string.ai_consider_preferences, Icons.Default.Favorite, considerations.preferences) { onToggle { it.copy(preferences = !it.preferences) } },
    )
    SectionCard(
        icon = Icons.Default.TrendingUp,
        title = stringResource(R.string.composer_section_considerations),
        hint = stringResource(R.string.composer_considerations_hint),
    ) {
        Column {
            rows.forEachIndexed { index, row ->
                ConsiderationToggleRow(row)
                if (index != rows.lastIndex) {
                    HorizontalDivider(
                        color = MaterialTheme.colorScheme.outlineVariant,
                        thickness = 1.dp,
                    )
                }
            }
        }
    }
}

private data class ConsiderationRow(
    val labelRes: Int,
    val icon: ImageVector,
    val active: Boolean,
    val onToggle: () -> Unit,
)

@Composable
private fun ConsiderationToggleRow(row: ConsiderationRow) {
    val iconBg = if (row.active) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surfaceVariant
    val iconFg = if (row.active) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant
    val labelColor = if (row.active) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.onSurfaceVariant
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = row.onToggle)
            .padding(horizontal = 4.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            modifier = Modifier
                .size(28.dp)
                .clip(RoundedCornerShape(8.dp))
                .background(iconBg),
            contentAlignment = Alignment.Center,
        ) {
            Icon(row.icon, contentDescription = null, tint = iconFg, modifier = Modifier.size(14.dp))
        }
        Spacer(Modifier.width(10.dp))
        Text(
            stringResource(row.labelRes),
            fontSize = 13.sp,
            fontWeight = FontWeight.SemiBold,
            color = labelColor,
            modifier = Modifier.weight(1f),
        )
        MiniSwitch(active = row.active, onToggle = row.onToggle)
    }
}

@Composable
private fun MiniSwitch(active: Boolean, onToggle: () -> Unit) {
    val trackBg = if (active) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.surfaceVariant
    val thumbColor = if (active) Color.White else MaterialTheme.colorScheme.onSurfaceVariant
    Box(
        modifier = Modifier
            .width(34.dp)
            .height(20.dp)
            .clip(RoundedCornerShape(10.dp))
            .background(trackBg)
            .border(
                if (active) 0.dp else 1.dp,
                if (active) Color.Transparent else MaterialTheme.colorScheme.outline,
                RoundedCornerShape(10.dp),
            )
            .clickable(onClick = onToggle)
            .padding(2.dp),
        contentAlignment = if (active) Alignment.CenterEnd else Alignment.CenterStart,
    ) {
        Box(
            modifier = Modifier
                .size(14.dp)
                .clip(CircleShape)
                .background(thumbColor),
        )
    }
}

// ─── Shared small chip ───────────────────────────────────────────────────────

@Composable
private fun SmallPillChip(
    label: String,
    icon: ImageVector?,
    active: Boolean,
    onClick: () -> Unit,
) {
    val bg = if (active) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.surface
    val fg = if (active) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.onSurface
    val borderColor = if (active) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outline
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

// ─── Closet chip row ─────────────────────────────────────────────────────────

@Composable
private fun ClosetChipRow(
    closetNames: List<String>,
    onClick: () -> Unit,
) {
    val label = closetNames.takeIf { it.isNotEmpty() }?.joinToString(" · ")
        ?: stringResource(R.string.composer_closets_all)
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Text(
            stringResource(R.string.composer_section_sources),
            fontSize = 11.sp,
            fontWeight = FontWeight.SemiBold,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.weight(1f),
        )
        SmallPillChip(
            label = label,
            icon = Icons.Default.Place,
            active = closetNames.isNotEmpty(),
            onClick = onClick,
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
