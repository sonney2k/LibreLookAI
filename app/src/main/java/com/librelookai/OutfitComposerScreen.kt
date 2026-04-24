package com.librelookai

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.itemsIndexed
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.AutoAwesome
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.InputChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.SuggestionChip
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import coil.compose.AsyncImage
import coil.request.ImageRequest

/**
 * Unified full-screen composer for creating a new style.
 * Phase 1 entry point: Wardrobe-selection "Create style" FAB + FullScreenViewer action.
 */
@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
fun OutfitComposerScreen(
    stylesViewModel: OutfitsViewModel,
    wardrobeViewModel: WardrobeViewModel,
    profileViewModel: ProfileViewModel,
    weatherViewModel: WeatherViewModel,
) {
    val s by stylesViewModel.state.collectAsState()
    val wardrobe by wardrobeViewModel.state.collectAsState()
    val profile by profileViewModel.state.collectAsState()
    val weather by weatherViewModel.state.collectAsState()
    val ctx = LocalContext.current
    val isOffline = LocalIsOffline.current

    if (!s.isComposerOpen) return

    var showAddItemSheet by remember { mutableStateOf(false) }

    Dialog(
        onDismissRequest = { stylesViewModel.closeComposer() },
        properties = DialogProperties(usePlatformDefaultWidth = false, dismissOnBackPress = true, dismissOnClickOutside = false),
    ) {
        Surface(
            modifier = Modifier.fillMaxSize(),
            color = MaterialTheme.colorScheme.surface,
        ) {
            Column(modifier = Modifier.fillMaxSize().navigationBarsPadding().imePadding()) {
                // Header
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .statusBarsPadding()
                        .padding(horizontal = 8.dp, vertical = 4.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    IconButton(onClick = { stylesViewModel.closeComposer() }) {
                        Icon(Icons.Default.Close, contentDescription = stringResource(R.string.action_cancel))
                    }
                    Text(
                        stringResource(R.string.composer_title),
                        style = MaterialTheme.typography.titleMedium,
                        modifier = Modifier.weight(1f).padding(start = 4.dp),
                    )
                    Button(
                        onClick = { stylesViewModel.saveComposer() },
                        enabled = s.composerItemIds.isNotEmpty(),
                    ) { Text(stringResource(R.string.composer_save)) }
                }
                HorizontalDivider()

                // Scrollable body
                Column(
                    modifier = Modifier
                        .weight(1f)
                        .verticalScroll(rememberScrollState())
                        .padding(horizontal = 16.dp, vertical = 8.dp),
                    verticalArrangement = Arrangement.spacedBy(16.dp),
                ) {
                    // 1. Items
                    SectionHeader(stringResource(R.string.composer_section_items))
                    ItemsGrid(
                        itemIds = s.composerItemIds,
                        wardrobe = wardrobe.images,
                        onRemove = { stylesViewModel.removeComposerItem(it) },
                        onAddClick = { showAddItemSheet = true },
                    )

                    // 2. Weather
                    SectionHeader(stringResource(R.string.composer_section_weather))
                    WeatherSection(
                        mode = s.composerWeatherMode,
                        onModeChange = { stylesViewModel.setComposerWeatherMode(it) },
                        autoWeather = weather.data,
                        season = s.composerManualSeason,
                        onSeason = { stylesViewModel.setComposerManualSeason(it) },
                        tempC = s.composerManualTempC,
                        onTempC = { stylesViewModel.setComposerManualTempC(it) },
                        precip = s.composerManualPrecip,
                        onPrecip = { stylesViewModel.setComposerManualPrecip(it) },
                    )

                    // 3. Vibe
                    SectionHeader(stringResource(R.string.composer_section_vibe))
                    VibeSection(
                        selected = s.composerVibes,
                        onToggle = { stylesViewModel.toggleComposerVibe(it) },
                    )

                    // 4. Composition targets
                    SectionHeader(stringResource(R.string.composer_section_targets))
                    TargetsSection(
                        targets = s.composerTargets,
                        onChange = { stylesViewModel.setComposerTargets(it) },
                    )

                    // 5. Preference override
                    SectionHeader(stringResource(R.string.composer_section_pref))
                    OutlinedTextField(
                        value = s.composerPrefOverride,
                        onValueChange = { stylesViewModel.updateComposerPrefOverride(it) },
                        modifier = Modifier.fillMaxWidth(),
                        placeholder = { Text(stringResource(R.string.composer_pref_placeholder)) },
                        minLines = 2,
                        maxLines = 5,
                    )

                    // 6. Name & description
                    SectionHeader(stringResource(R.string.composer_section_name))
                    OutlinedTextField(
                        value = s.composerName,
                        onValueChange = { stylesViewModel.updateComposerName(it) },
                        modifier = Modifier.fillMaxWidth(),
                        singleLine = true,
                        placeholder = { Text(stringResource(R.string.composer_name_placeholder)) },
                    )
                    OutlinedTextField(
                        value = s.composerDescription,
                        onValueChange = { stylesViewModel.updateComposerDescription(it) },
                        modifier = Modifier.fillMaxWidth(),
                        placeholder = { Text(stringResource(R.string.composer_desc_placeholder)) },
                        minLines = 2,
                        maxLines = 4,
                    )

                    // 7. AI enhance
                    if (!isOffline) {
                        SectionHeader(stringResource(R.string.composer_section_ai))
                        if (s.composerReason.isNotBlank()) {
                            Surface(
                                tonalElevation = 2.dp,
                                shape = MaterialTheme.shapes.small,
                                modifier = Modifier.fillMaxWidth(),
                            ) {
                                Text(
                                    s.composerReason,
                                    style = MaterialTheme.typography.bodySmall,
                                    modifier = Modifier.padding(12.dp),
                                )
                            }
                        }
                        // Applied feedback history chips
                        if (s.composerFeedbackHistory.isNotEmpty()) {
                            FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                                s.composerFeedbackHistory.forEach { fb ->
                                    InputChip(
                                        selected = true,
                                        onClick = {},
                                        label = { Text(fb, style = MaterialTheme.typography.labelSmall) },
                                    )
                                }
                            }
                        }

                        // Preset quick-pick chips — tapping one immediately enhances with that feedback
                        val presets = listOf(
                            stringResource(R.string.outfits_refine_casual),
                            stringResource(R.string.outfits_refine_formal),
                            stringResource(R.string.outfits_refine_diff_colors),
                            stringResource(R.string.outfits_refine_warmer),
                            stringResource(R.string.outfits_refine_lighter),
                            stringResource(R.string.outfits_refine_trendy),
                            stringResource(R.string.outfits_refine_simpler),
                            stringResource(R.string.outfits_refine_bold),
                        )
                        FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                            presets.forEach { preset ->
                                SuggestionChip(
                                    onClick = {
                                        if (!s.isComposerEnhancing) {
                                            stylesViewModel.updateComposerFeedback(preset)
                                            stylesViewModel.enhanceComposerWithAi(
                                                prefs   = profile.preferences,
                                                weather = weather.data,
                                                images  = wardrobe.images,
                                            )
                                        }
                                    },
                                    label = { Text(preset, style = MaterialTheme.typography.labelSmall) },
                                    enabled = !s.isComposerEnhancing,
                                )
                            }
                        }

                        OutlinedTextField(
                            value = s.composerFeedback,
                            onValueChange = { stylesViewModel.updateComposerFeedback(it) },
                            modifier = Modifier.fillMaxWidth(),
                            placeholder = { Text(stringResource(R.string.composer_feedback_placeholder)) },
                            minLines = 2,
                            maxLines = 4,
                            enabled = !s.isComposerEnhancing,
                        )

                        if (s.isComposerEnhancing) {
                            Row(
                                modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.Center,
                            ) {
                                CircularProgressIndicator(
                                    modifier = Modifier.size(28.dp),
                                    strokeWidth = 3.dp,
                                )
                                Spacer(Modifier.width(12.dp))
                                Text(
                                    stringResource(R.string.composer_enhancing),
                                    style = MaterialTheme.typography.bodyMedium,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                        }

                        Button(
                            onClick = {
                                stylesViewModel.enhanceComposerWithAi(
                                    prefs   = profile.preferences,
                                    weather = weather.data,
                                    images  = wardrobe.images,
                                )
                            },
                            enabled = !s.isComposerEnhancing,
                            modifier = Modifier.fillMaxWidth(),
                        ) {
                            if (s.isComposerEnhancing) {
                                CircularProgressIndicator(
                                    modifier = Modifier.size(18.dp),
                                    strokeWidth = 2.dp,
                                    color = MaterialTheme.colorScheme.onPrimary,
                                )
                                Spacer(Modifier.width(8.dp))
                            } else {
                                Icon(Icons.Default.AutoAwesome, contentDescription = null, modifier = Modifier.size(18.dp))
                                Spacer(Modifier.width(8.dp))
                            }
                            Text(stringResource(R.string.composer_enhance))
                        }
                        s.composerError?.let {
                            Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
                        }
                    }

                    Spacer(Modifier.height(16.dp))
                }
            }
        }
    }

    if (showAddItemSheet) {
        AddItemSheet(
            allItems = wardrobe.images,
            alreadyChosen = s.composerItemIds.toSet(),
            onConfirm = { newIds ->
                stylesViewModel.addComposerItems(newIds)
                showAddItemSheet = false
            },
            onDismiss = { showAddItemSheet = false },
        )
    }
}

@Composable
private fun SectionHeader(text: String) {
    Text(
        text,
        style = MaterialTheme.typography.titleSmall,
        color = MaterialTheme.colorScheme.primary,
    )
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun ItemsGrid(
    itemIds: List<String>,
    wardrobe: List<DriveImage>,
    onRemove: (String) -> Unit,
    onAddClick: () -> Unit,
) {
    val ctx = LocalContext.current
    val byId = remember(wardrobe) { wardrobe.associateBy { it.driveId } }
    FlowRow(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        itemIds.forEach { id ->
            val img = byId[id] ?: return@forEach
            Box(
                modifier = Modifier
                    .size(96.dp)
                    .clip(MaterialTheme.shapes.small)
                    .background(MaterialTheme.colorScheme.surfaceVariant),
            ) {
                AsyncImage(
                    model = remember(img.driveId, img.version) {
                        ImageRequest.Builder(ctx)
                            .data(img.localPath)
                            .memoryCacheKey("${img.driveId}_${img.version}")
                            .build()
                    },
                    contentDescription = img.name,
                    modifier = Modifier.fillMaxSize(),
                    contentScale = ContentScale.Crop,
                )
                Surface(
                    modifier = Modifier
                        .align(Alignment.TopEnd)
                        .padding(2.dp)
                        .size(20.dp)
                        .clickable { onRemove(id) },
                    shape = MaterialTheme.shapes.small,
                    color = Color.Black.copy(alpha = 0.55f),
                ) {
                    Icon(
                        Icons.Default.Close,
                        contentDescription = null,
                        tint = Color.White,
                        modifier = Modifier.padding(2.dp),
                    )
                }
            }
        }
        // Add tile
        Box(
            modifier = Modifier
                .size(96.dp)
                .clip(MaterialTheme.shapes.small)
                .background(MaterialTheme.colorScheme.surfaceVariant)
                .clickable(onClick = onAddClick),
            contentAlignment = Alignment.Center,
        ) {
            Icon(Icons.Default.Add, contentDescription = null, modifier = Modifier.size(32.dp))
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun WeatherSection(
    mode: ComposerWeatherMode,
    onModeChange: (ComposerWeatherMode) -> Unit,
    autoWeather: WeatherData?,
    season: String,
    onSeason: (String) -> Unit,
    tempC: Int?,
    onTempC: (Int?) -> Unit,
    precip: String,
    onPrecip: (String) -> Unit,
) {
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        FilterChip(
            selected = mode == ComposerWeatherMode.AUTO,
            onClick = { onModeChange(ComposerWeatherMode.AUTO) },
            label = { Text(stringResource(R.string.composer_weather_auto)) },
        )
        FilterChip(
            selected = mode == ComposerWeatherMode.MANUAL,
            onClick = { onModeChange(ComposerWeatherMode.MANUAL) },
            label = { Text(stringResource(R.string.composer_weather_manual)) },
        )
    }
    if (mode == ComposerWeatherMode.AUTO) {
        val txt = autoWeather?.let {
            "${it.temperatureCelsius.toInt()}°C · ${wmoEmoji(it.weatherCode)} · ${it.cityName.ifEmpty { "—" }}"
        } ?: stringResource(R.string.composer_weather_unknown)
        Text(txt, style = MaterialTheme.typography.bodyMedium)
    } else {
        Text(stringResource(R.string.composer_weather_season), style = MaterialTheme.typography.labelMedium)
        FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            listOf("Spring", "Summer", "Fall", "Winter").forEach { opt ->
                FilterChip(
                    selected = season == opt,
                    onClick = { onSeason(if (season == opt) "" else opt) },
                    label = { Text(opt) },
                )
            }
        }
        Text(stringResource(R.string.composer_weather_temp), style = MaterialTheme.typography.labelMedium)
        FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            listOf(-5, 5, 15, 22, 30).forEach { t ->
                FilterChip(
                    selected = tempC == t,
                    onClick = { onTempC(if (tempC == t) null else t) },
                    label = { Text("${t}°C") },
                )
            }
        }
        Text(stringResource(R.string.composer_weather_precip), style = MaterialTheme.typography.labelMedium)
        FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            listOf("None", "Light", "Heavy").forEach { p ->
                FilterChip(
                    selected = precip == p,
                    onClick = { onPrecip(if (precip == p) "" else p) },
                    label = { Text(p) },
                )
            }
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun VibeSection(
    selected: Set<String>,
    onToggle: (String) -> Unit,
) {
    val vibes = listOf("Casual", "Sporty", "Formal", "Business", "Streetwear", "Minimalist", "Classic", "Elegant")
    FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
        vibes.forEach { v ->
            FilterChip(
                selected = v in selected,
                onClick = { onToggle(v) },
                label = { Text(v) },
            )
        }
    }
}

@Composable
private fun TargetsSection(
    targets: ComposerTargets,
    onChange: (ComposerTargets) -> Unit,
) {
    @Composable
    fun Stepper(label: String, value: Int, onValue: (Int) -> Unit) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(vertical = 2.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(label, modifier = Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium)
            IconButton(onClick = { if (value > 0) onValue(value - 1) }) { Text("–") }
            Text("$value", modifier = Modifier.width(24.dp), style = MaterialTheme.typography.bodyMedium)
            IconButton(onClick = { if (value < 5) onValue(value + 1) }) { Text("+") }
        }
    }
    Column {
        Stepper(stringResource(R.string.composer_layer_top),       targets.top)       { onChange(targets.copy(top = it)) }
        Stepper(stringResource(R.string.composer_layer_bottom),    targets.bottom)    { onChange(targets.copy(bottom = it)) }
        Stepper(stringResource(R.string.composer_layer_footwear),  targets.footwear)  { onChange(targets.copy(footwear = it)) }
        Stepper(stringResource(R.string.composer_layer_outerwear), targets.outerwear) { onChange(targets.copy(outerwear = it)) }
        Stepper(stringResource(R.string.composer_layer_accessory), targets.accessory) { onChange(targets.copy(accessory = it)) }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun AddItemSheet(
    allItems: List<DriveImage>,
    alreadyChosen: Set<String>,
    onConfirm: (Set<String>) -> Unit,
    onDismiss: () -> Unit,
) {
    val ctx = LocalContext.current
    var picked by remember { mutableStateOf(emptySet<String>()) }

    val candidates = remember(allItems, alreadyChosen) {
        allItems.filter { it.driveId !in alreadyChosen }
    }

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
    ) {
        Column(modifier = Modifier.fillMaxWidth().navigationBarsPadding()) {
            Row(
                modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    stringResource(R.string.composer_add_items),
                    style = MaterialTheme.typography.titleSmall,
                    modifier = Modifier.weight(1f),
                )
                Text(
                    stringResource(R.string.composer_picked_count, picked.size),
                    style = MaterialTheme.typography.labelSmall,
                )
            }

            LazyVerticalGrid(
                columns = GridCells.Adaptive(96.dp),
                modifier = Modifier.fillMaxWidth().height(360.dp),
                contentPadding = PaddingValues(4.dp),
            ) {
                itemsIndexed(candidates, key = { _, img -> img.driveId }) { _, image ->
                    val isSelected = image.driveId in picked
                    Box(
                        modifier = Modifier
                            .aspectRatio(1f)
                            .padding(2.dp)
                            .clip(MaterialTheme.shapes.extraSmall)
                            .clickable {
                                picked = if (isSelected) picked - image.driveId else picked + image.driveId
                            },
                    ) {
                        AsyncImage(
                            model = remember(image.driveId, image.version) {
                                ImageRequest.Builder(ctx)
                                    .data(image.localPath)
                                    .memoryCacheKey("${image.driveId}_${image.version}")
                                    .build()
                            },
                            contentDescription = image.name,
                            modifier = Modifier.fillMaxSize(),
                            contentScale = ContentScale.Crop,
                        )
                        if (isSelected) {
                            Box(
                                modifier = Modifier
                                    .fillMaxSize()
                                    .background(MaterialTheme.colorScheme.primary.copy(alpha = 0.35f)),
                                contentAlignment = Alignment.TopEnd,
                            ) {
                                Icon(
                                    Icons.Default.CheckCircle,
                                    contentDescription = null,
                                    tint = MaterialTheme.colorScheme.primary,
                                    modifier = Modifier.padding(4.dp).size(18.dp),
                                )
                            }
                        }
                    }
                }
            }

            Row(
                modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.End),
            ) {
                TextButton(onClick = onDismiss) { Text(stringResource(R.string.action_cancel)) }
                Button(
                    onClick = { onConfirm(picked) },
                    enabled = picked.isNotEmpty(),
                ) {
                    Icon(Icons.Default.Check, contentDescription = null, modifier = Modifier.size(18.dp))
                    Spacer(Modifier.width(6.dp))
                    Text(stringResource(R.string.composer_add_selected))
                }
            }
        }
    }
}
