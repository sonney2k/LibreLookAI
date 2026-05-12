package com.librelookai

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.itemsIndexed
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.AutoAwesome
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Checkroom
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.ExpandLess
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material.icons.filled.MoreHoriz
import androidx.compose.material.icons.filled.Place
import androidx.compose.material.icons.filled.Tune
import androidx.compose.material.icons.filled.WbSunny
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.InputChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TextFieldDefaults
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import coil.compose.AsyncImage
import coil.request.ImageRequest

/** Garment layers, in display order top→bottom in the look board. */
private enum class Layer(
    val labelRes: Int,
    val optional: Boolean,
    val iconRes: Int,
) {
    Outerwear(R.string.outfit_layer_outerwear,   optional = true,  iconRes = R.drawable.ic_layer_jacket),
    Top(R.string.outfit_layer_tops,              optional = false, iconRes = R.drawable.ic_layer_shirt),
    Bottom(R.string.outfit_layer_bottoms,        optional = false, iconRes = R.drawable.ic_layer_pants),
    Footwear(R.string.outfit_layer_footwear,     optional = false, iconRes = R.drawable.ic_layer_shoe),
    Accessory(R.string.outfit_layer_accessories, optional = true,  iconRes = R.drawable.ic_layer_bag),
}

private fun DriveImage.displayLabel(): String =
    tags?.label?.ifEmpty { null }
        ?: tags?.type?.ifEmpty { null }
        ?: name

private fun ComposerTargets.countFor(layer: Layer): Int = when (layer) {
    Layer.Outerwear -> outerwear
    Layer.Top -> top
    Layer.Bottom -> bottom
    Layer.Footwear -> footwear
    Layer.Accessory -> accessory
}

private fun ComposerTargets.withLayer(layer: Layer, value: Int): ComposerTargets = when (layer) {
    Layer.Outerwear -> copy(outerwear = value)
    Layer.Top -> copy(top = value)
    Layer.Bottom -> copy(bottom = value)
    Layer.Footwear -> copy(footwear = value)
    Layer.Accessory -> copy(accessory = value)
}

/** Map a wardrobe item to a layer slot using its category (best-effort). */
private fun layerFor(image: DriveImage): Layer? {
    val cat = image.tags?.category?.lowercase().orEmpty()
    return when {
        cat.contains("outer") -> Layer.Outerwear
        cat.contains("foot") || cat.contains("shoe") -> Layer.Footwear
        cat.contains("bottom") || cat == "pants" || cat == "skirt" -> Layer.Bottom
        cat.contains("accessor") -> Layer.Accessory
        cat.contains("top") || cat.contains("shirt") || cat == "dress" || cat == "suit" -> Layer.Top
        else -> null
    }
}

/**
 * Unified full-screen composer for creating a new outfit — "layered look board" layout.
 * One row per garment layer with a filmstrip of alternatives. AI fills missing slots from
 * the goal/occasion strip at the top.
 */
@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
fun OutfitComposerScreen(
    stylesViewModel: OutfitsViewModel,
    wardrobeViewModel: WardrobeViewModel,
    profileViewModel: ProfileViewModel,
    weatherViewModel: WeatherViewModel,
    shoppingClosetViewModel: ShoppingClosetViewModel = androidx.lifecycle.viewmodel.compose.viewModel(),
    locationViewModel: LocationViewModel = androidx.lifecycle.viewmodel.compose.viewModel(),
) {
    val s by stylesViewModel.state.collectAsState()
    val wardrobe by wardrobeViewModel.state.collectAsState()
    val profile by profileViewModel.state.collectAsState()
    val weather by weatherViewModel.state.collectAsState()
    val shoppingState by shoppingClosetViewModel.state.collectAsState()
    val locationState by locationViewModel.state.collectAsState()
    val parentContext = LocalContext.current
    val parentConfiguration = LocalConfiguration.current
    val isOffline = LocalIsOffline.current

    if (!s.isComposerOpen) return

    val sourceFolders = s.composerSourceFolderIds
    val crossClosetImages = wardrobe.allLocationImages.ifEmpty { wardrobe.images }
    val composerImages = remember(crossClosetImages, shoppingState.items, sourceFolders) {
        val filteredWardrobe = if (sourceFolders.isEmpty()) crossClosetImages
        else crossClosetImages.filter { it.folderId in sourceFolders }
        filteredWardrobe + shoppingState.items
    }

    var showAddItemSheet by remember { mutableStateOf<Layer?>(null) }
    var showWeatherSheet by remember { mutableStateOf(false) }
    var showClosetSheet by remember { mutableStateOf(false) }
    var showAddTagDialog by remember { mutableStateOf(false) }
    var showDiscardDialog by remember { mutableStateOf(false) }
    val hasComposerContent = s.composerItemIds.isNotEmpty() ||
        s.composerTags.isNotEmpty() ||
        s.composerVibes.isNotEmpty() ||
        s.composerDescription.isNotBlank()
    val requestClose: () -> Unit = {
        if (hasComposerContent) showDiscardDialog = true
        else {
            Analytics.action("OutfitComposer", "close")
            stylesViewModel.closeComposer()
        }
    }

    // Fullscreen Dialog: read insets via LocalSystemBarsPadding with a 48dp nav-bar floor.
    // (See CLAUDE.md – Compose Dialog Quirks.)
    val barInsets = LocalSystemBarsPadding.current
    val view = androidx.compose.ui.platform.LocalView.current
    val density = LocalDensity.current
    val rootInsetBottomDp = remember(view) {
        val raw = view.rootWindowInsets
        val bottomPx = if (raw != null) {
            androidx.core.view.WindowInsetsCompat
                .toWindowInsetsCompat(raw, view)
                .getInsets(androidx.core.view.WindowInsetsCompat.Type.systemBars())
                .bottom
        } else 0
        with(density) { bottomPx.toDp() }
    }
    val effectiveBottom = maxOf(barInsets.calculateBottomPadding(), rootInsetBottomDp, 48.dp)
    val effectiveTop = maxOf(barInsets.calculateTopPadding(), 0.dp)

    // Group composer items by layer. Multiple items per layer are allowed — order matches
    // their order in composerItemIds so user-added picks stay where the user put them.
    val byId = remember(composerImages) { composerImages.associateBy { it.driveId } }
    val currentByLayer: Map<Layer, List<DriveImage>> = remember(s.composerItemIds, byId) {
        val out = Layer.values().associateWith { mutableListOf<DriveImage>() }
        s.composerItemIds.forEach { id ->
            val img = byId[id] ?: return@forEach
            val layer = layerFor(img) ?: return@forEach
            out[layer]?.add(img)
        }
        out
    }
    // Alternatives per layer = wardrobe items of that layer not yet picked.
    val alternativesByLayer: Map<Layer, List<DriveImage>> = remember(composerImages, currentByLayer) {
        Layer.values().associateWith { layer ->
            val pickedIds = currentByLayer[layer].orEmpty().map { it.driveId }.toSet()
            composerImages.filter { layerFor(it) == layer && it.driveId !in pickedIds }
        }
    }
    val filledLayers = currentByLayer.count { it.value.isNotEmpty() }

    Dialog(
        onDismissRequest = requestClose,
        properties = DialogProperties(usePlatformDefaultWidth = false, dismissOnBackPress = true, dismissOnClickOutside = false),
    ) {
        CompositionLocalProvider(
            LocalContext provides parentContext,
            LocalConfiguration provides parentConfiguration,
        ) {
            Surface(
                modifier = Modifier.fillMaxSize(),
                color = MaterialTheme.colorScheme.background,
            ) {
                Box(modifier = Modifier.fillMaxSize()) {
                    Column(modifier = Modifier.fillMaxSize().imePadding()) {
                        // ── App header ───────────────────────────────────────
                        Header(
                            filledLayers = filledLayers,
                            topPadding = effectiveTop,
                            onClose = requestClose,
                        )

                        Column(
                            modifier = Modifier
                                .weight(1f)
                                .verticalScroll(rememberScrollState())
                                .padding(horizontal = 16.dp)
                                .padding(top = 0.dp, bottom = 8.dp),
                            verticalArrangement = Arrangement.spacedBy(8.dp),
                        ) {
                            // ── Goal pill ───────────────────────────────────
                            if (!isOffline) {
                                GoalPill(
                                    goal = s.composerFeedback,
                                    onGoalChange = { stylesViewModel.updateComposerFeedback(it) },
                                    enhancing = s.isComposerEnhancing,
                                    onFillMissing = {
                                        Analytics.action("OutfitComposer", "fill_missing")
                                        stylesViewModel.enhanceComposerWithAi(
                                            prefs   = profile.preferences,
                                            weather = weather.data,
                                            images  = composerImages,
                                        )
                                    },
                                )

                                // ── Context strip ───────────────────────────
                                ContextStrip(
                                    weatherMode = s.composerWeatherMode,
                                    autoWeather = weather.data,
                                    manualTempC = s.composerManualTempC,
                                    closetNames = remember(sourceFolders, locationState.locations) {
                                        locationState.locations.filter { it.folderId in sourceFolders }.map { it.name }
                                    },
                                    closetPickerAvailable = locationState.locations.size >= 2,
                                    selectedVibes = s.composerVibes,
                                    onToggleVibe = { stylesViewModel.toggleComposerVibe(it) },
                                    onClickWeather = { showWeatherSheet = true },
                                    onClickCloset = { showClosetSheet = true },
                                )
                            }

                            // ── Layer rows ──────────────────────────────────
                            Layer.values().forEach { layer ->
                                val layerItems = currentByLayer[layer].orEmpty()
                                val enabled = layerItems.isNotEmpty() ||
                                    s.composerTargets.countFor(layer) > 0
                                LayerCard(
                                    layer = layer,
                                    enabled = enabled,
                                    current = layerItems,
                                    alternatives = alternativesByLayer[layer].orEmpty(),
                                    onSetEnabled = { newEnabled ->
                                        if (newEnabled) {
                                            if (s.composerTargets.countFor(layer) == 0) {
                                                stylesViewModel.setComposerTargets(
                                                    s.composerTargets.withLayer(layer, 1),
                                                )
                                            }
                                        } else {
                                            layerItems.forEach {
                                                stylesViewModel.removeComposerItem(it.driveId)
                                            }
                                            stylesViewModel.setComposerTargets(
                                                s.composerTargets.withLayer(layer, 0),
                                            )
                                        }
                                        Analytics.action(
                                            "OutfitComposer",
                                            "layer_enabled",
                                            mapOf("layer" to layer.name, "on" to newEnabled.toString()),
                                        )
                                    },
                                    onAdd = { id ->
                                        if (s.composerTargets.countFor(layer) == 0) {
                                            stylesViewModel.setComposerTargets(
                                                s.composerTargets.withLayer(layer, 1),
                                            )
                                        }
                                        stylesViewModel.addComposerItems(listOf(id))
                                        Analytics.action("OutfitComposer", "layer_add", mapOf("layer" to layer.name))
                                    },
                                    onClear = { id -> stylesViewModel.removeComposerItem(id) },
                                    onSeeAll = { showAddItemSheet = layer },
                                )
                            }

                            // ── Tags (chip picker over all known outfit tags) ──
                            SectionHeader(stringResource(R.string.composer_tags_optional))
                            val knownTags = remember(s.outfits, s.composerTags) {
                                (s.outfits.flatMap { it.tags } + s.composerTags)
                                    .map { it.trim() }
                                    .filter { it.isNotEmpty() }
                                    .distinctBy { it.lowercase() }
                                    .sortedBy { it.lowercase() }
                            }
                            val composerTagsSet = s.composerTags.map { it.lowercase() }.toSet()
                            FlowRow(
                                horizontalArrangement = Arrangement.spacedBy(6.dp),
                                verticalArrangement = Arrangement.spacedBy(6.dp),
                            ) {
                                knownTags.forEach { tag ->
                                    val active = tag.lowercase() in composerTagsSet
                                    ContextChip(
                                        label = tag,
                                        icon = null,
                                        active = active,
                                        onClick = {
                                            if (active) stylesViewModel.removeComposerTag(tag)
                                            else stylesViewModel.addComposerTag(tag)
                                        },
                                    )
                                }
                                ContextChip(
                                    label = stringResource(R.string.outfits_tag_add),
                                    icon = Icons.Default.Add,
                                    active = false,
                                    onClick = { showAddTagDialog = true },
                                )
                            }

                            // ── Description (inline, no longer behind Advanced) ──
                            OutlinedTextField(
                                value = s.composerDescription,
                                onValueChange = { stylesViewModel.updateComposerDescription(it) },
                                modifier = Modifier.fillMaxWidth(),
                                placeholder = { Text(stringResource(R.string.composer_desc_placeholder)) },
                                singleLine = false,
                                minLines = 1,
                                maxLines = 3,
                            )

                            // AI reason / error after enhancement.
                            if (s.composerReason.isNotBlank()) {
                                Surface(
                                    shape = RoundedCornerShape(12.dp),
                                    color = MaterialTheme.colorScheme.surface,
                                    border = androidx.compose.foundation.BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
                                    modifier = Modifier.fillMaxWidth(),
                                ) {
                                    Text(
                                        s.composerReason,
                                        style = MaterialTheme.typography.bodySmall,
                                        modifier = Modifier.padding(12.dp),
                                    )
                                }
                            }
                            s.composerError?.let {
                                Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
                            }
                        }

                        // ── Bottom bar ────────────────────────────────────
                        BottomBar(
                            name = s.composerName,
                            onNameChange = { stylesViewModel.updateComposerName(it) },
                            saveEnabled = s.composerItemIds.isNotEmpty(),
                            onSave = {
                                Analytics.action("OutfitComposer", "save", mapOf("count" to s.composerItemIds.size.toString()))
                                stylesViewModel.saveComposer()
                            },
                            bottomPadding = effectiveBottom,
                        )
                    }
                    if (s.isComposerEnhancing) {
                        AiProcessingOverlay(
                            label = stringResource(R.string.composer_enhancing),
                            modifier = Modifier.fillMaxSize(),
                        )
                    }
                }
            }
        }
    }

    showAddItemSheet?.let { layer ->
        CompositionLocalProvider(
            LocalContext provides parentContext,
            LocalConfiguration provides parentConfiguration,
        ) {
            AddItemSheet(
                allItems = composerImages.filter { layerFor(it) == layer },
                alreadyChosen = s.composerItemIds.toSet(),
                onConfirm = { newIds ->
                    if (newIds.isNotEmpty() && s.composerTargets.countFor(layer) == 0) {
                        stylesViewModel.setComposerTargets(
                            s.composerTargets.withLayer(layer, 1),
                        )
                    }
                    stylesViewModel.addComposerItems(newIds)
                    showAddItemSheet = null
                },
                onDismiss = { showAddItemSheet = null },
            )
        }
    }

    if (showWeatherSheet) {
        CompositionLocalProvider(
            LocalContext provides parentContext,
            LocalConfiguration provides parentConfiguration,
        ) {
            WeatherPickerSheet(
                mode = s.composerWeatherMode,
                onModeChange = { stylesViewModel.setComposerWeatherMode(it) },
                autoWeather = weather.data,
                season = s.composerManualSeason,
                onSeason = { stylesViewModel.setComposerManualSeason(it) },
                tempC = s.composerManualTempC,
                onTempC = { stylesViewModel.setComposerManualTempC(it) },
                precip = s.composerManualPrecip,
                onPrecip = { stylesViewModel.setComposerManualPrecip(it) },
                onDismiss = { showWeatherSheet = false },
            )
        }
    }

    // Locale providers must wrap each slot lambda individually — AlertDialog
    // opens its own window which re-derives LocalContext/LocalConfiguration.
    // Wrapping the AlertDialog() call from outside does NOT propagate into slots.
    val locale: @Composable (@Composable () -> Unit) -> Unit = { content ->
        CompositionLocalProvider(
            LocalContext provides parentContext,
            LocalConfiguration provides parentConfiguration,
        ) { content() }
    }

    if (showDiscardDialog) {
        AlertDialog(
            onDismissRequest = { showDiscardDialog = false },
            title = { locale { Text(stringResource(R.string.composer_discard_title)) } },
            text = { locale { Text(stringResource(R.string.composer_discard_message)) } },
            confirmButton = {
                locale {
                    TextButton(onClick = {
                        showDiscardDialog = false
                        Analytics.action("OutfitComposer", "close")
                        stylesViewModel.closeComposer()
                    }) { Text(stringResource(R.string.composer_discard_confirm)) }
                }
            },
            dismissButton = {
                locale {
                    TextButton(onClick = { showDiscardDialog = false }) {
                        Text(stringResource(R.string.action_cancel))
                    }
                }
            },
        )
    }

    if (showAddTagDialog) {
        var newTag by remember { mutableStateOf("") }
        AlertDialog(
            onDismissRequest = { showAddTagDialog = false },
            title = { locale { Text(stringResource(R.string.outfits_tag_add)) } },
            text = {
                locale {
                    OutlinedTextField(
                        value = newTag,
                        onValueChange = { newTag = it },
                        placeholder = { Text(stringResource(R.string.outfits_tag_add_placeholder)) },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth(),
                    )
                }
            },
            confirmButton = {
                locale {
                    TextButton(
                        onClick = {
                            val t = newTag.trim()
                            if (t.isNotEmpty()) stylesViewModel.addComposerTag(t)
                            showAddTagDialog = false
                        },
                        enabled = newTag.trim().isNotEmpty(),
                    ) { Text(stringResource(R.string.outfits_tag_add)) }
                }
            },
            dismissButton = {
                locale {
                    TextButton(onClick = { showAddTagDialog = false }) {
                        Text(stringResource(R.string.action_cancel))
                    }
                }
            },
        )
    }

    if (showClosetSheet) {
        CompositionLocalProvider(
            LocalContext provides parentContext,
            LocalConfiguration provides parentConfiguration,
        ) {
            ClosetPickerSheet(
                locations = locationState.locations,
                selected = sourceFolders,
                onToggle = { stylesViewModel.toggleComposerSourceFolder(it) },
                onDismiss = { showClosetSheet = false },
            )
        }
    }
}

// ─── Header ─────────────────────────────────────────────────────────────────

@Composable
private fun Header(filledLayers: Int, topPadding: Dp, onClose: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(start = 8.dp, end = 8.dp, top = 0.dp, bottom = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        IconButton(onClick = onClose, modifier = Modifier.size(40.dp)) {
            Icon(Icons.Default.Close, contentDescription = stringResource(R.string.action_cancel))
        }
        Spacer(Modifier.width(4.dp))
        Column(modifier = Modifier.weight(1f)) {
            Text(
                stringResource(R.string.composer_title),
                style = MaterialTheme.typography.headlineSmall.copy(
                    fontSize = 36.sp,
                    fontWeight = FontWeight.Bold,
                ),
            )
            Text(
                stringResource(R.string.composer_header_subtitle, filledLayers),
                style = MaterialTheme.typography.bodySmall.copy(fontSize = 11.sp),
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
    HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
}

// ─── Goal pill ──────────────────────────────────────────────────────────────

@Composable
private fun GoalPill(
    goal: String,
    onGoalChange: (String) -> Unit,
    enhancing: Boolean,
    onFillMissing: () -> Unit,
) {
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
            .clip(RoundedCornerShape(16.dp))
            .background(gradient)
            .border(1.dp, primary.copy(alpha = 0.33f), RoundedCornerShape(16.dp))
            .padding(horizontal = 12.dp, vertical = 6.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(
                Icons.Default.AutoAwesome,
                contentDescription = null,
                tint = primary,
                modifier = Modifier.size(16.dp),
            )
            Spacer(Modifier.width(8.dp))
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
                enabled = !enhancing,
                textStyle = MaterialTheme.typography.bodyMedium.copy(fontSize = 13.sp, fontWeight = FontWeight.SemiBold),
                modifier = Modifier.weight(1f),
                colors = TextFieldDefaults.colors(
                    focusedContainerColor = Color.Transparent,
                    unfocusedContainerColor = Color.Transparent,
                    disabledContainerColor = Color.Transparent,
                    focusedIndicatorColor = Color.Transparent,
                    unfocusedIndicatorColor = Color.Transparent,
                    disabledIndicatorColor = Color.Transparent,
                ),
            )
            Spacer(Modifier.width(6.dp))
            Button(
                onClick = onFillMissing,
                enabled = !enhancing,
                shape = RoundedCornerShape(14.dp),
                contentPadding = PaddingValues(horizontal = 10.dp, vertical = 6.dp),
                colors = ButtonDefaults.buttonColors(
                    containerColor = primary,
                    contentColor = MaterialTheme.colorScheme.onPrimary,
                ),
            ) {
                if (enhancing) {
                    CircularProgressIndicator(
                        modifier = Modifier.size(12.dp),
                        strokeWidth = 2.dp,
                        color = MaterialTheme.colorScheme.onPrimary,
                    )
                } else {
                    Icon(Icons.Default.AutoAwesome, contentDescription = null, modifier = Modifier.size(12.dp))
                }
                Spacer(Modifier.width(4.dp))
                Text(
                    stringResource(R.string.composer_fill_missing),
                    style = MaterialTheme.typography.labelMedium.copy(fontSize = 11.sp, fontWeight = FontWeight.Bold),
                )
            }
        }
    }
}

// ─── Context strip ──────────────────────────────────────────────────────────

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ContextStrip(
    weatherMode: ComposerWeatherMode,
    autoWeather: WeatherData?,
    manualTempC: Int?,
    closetNames: List<String>,
    closetPickerAvailable: Boolean,
    selectedVibes: Set<String>,
    onToggleVibe: (String) -> Unit,
    onClickWeather: () -> Unit,
    onClickCloset: () -> Unit,
) {
    val weatherLabel: String = when (weatherMode) {
        ComposerWeatherMode.AUTO -> autoWeather?.let { "${it.temperatureCelsius.toInt()}°" }
            ?: stringResource(R.string.composer_factor_weather_auto)
        // Manual mode always identifies itself as "Manual" so users can tell their override is in
        // effect; if a temp is set we append it ("Manual · 22°").
        ComposerWeatherMode.MANUAL -> {
            val base = stringResource(R.string.composer_weather_manual)
            manualTempC?.let { "$base · ${it}°" } ?: base
        }
    }
    val vibes = listOf(
        "Casual" to R.string.composer_vibe_casual,
        "Business" to R.string.composer_vibe_business,
        "Formal" to R.string.composer_vibe_formal,
        "Streetwear" to R.string.composer_vibe_streetwear,
        "Minimalist" to R.string.composer_vibe_minimalist,
        "Sporty" to R.string.composer_vibe_sporty,
        "Elegant" to R.string.composer_vibe_elegant,
        "Classic" to R.string.composer_vibe_classic,
    )
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .horizontalScroll(rememberScrollState()),
        horizontalArrangement = Arrangement.spacedBy(6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        ContextChip(
            label = weatherLabel,
            icon = Icons.Default.WbSunny,
            active = true,
            onClick = onClickWeather,
        )
        if (closetPickerAvailable) {
            ContextChip(
                label = closetNames.takeIf { it.isNotEmpty() }?.joinToString(" · ")
                    ?: stringResource(R.string.composer_closets_all),
                icon = Icons.Default.Place,
                active = closetNames.isNotEmpty(),
                onClick = onClickCloset,
            )
        }
        vibes.forEach { (value, labelRes) ->
            ContextChip(
                label = stringResource(labelRes),
                icon = null,
                active = value in selectedVibes,
                onClick = { onToggleVibe(value) },
            )
        }
    }
}

@Composable
private fun ContextChip(
    label: String,
    icon: ImageVector?,
    active: Boolean,
    onClick: () -> Unit,
) {
    val bg = if (active) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.surface
    val fg = if (active) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.onSurface
    val borderColor = if (active) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outlineVariant
    Row(
        modifier = Modifier
            .clip(RoundedCornerShape(999.dp))
            .background(bg)
            .border(if (active) 1.5.dp else 1.dp, borderColor, RoundedCornerShape(999.dp))
            .clickable(onClick = onClick)
            .padding(horizontal = 12.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        if (icon != null) {
            Icon(icon, contentDescription = null, tint = fg, modifier = Modifier.size(12.dp))
        }
        Text(
            label,
            style = MaterialTheme.typography.labelMedium.copy(fontSize = 12.sp, fontWeight = FontWeight.SemiBold),
            color = fg,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

@Composable
private fun RoundCheckbox(checked: Boolean, onCheckedChange: (Boolean) -> Unit) {
    val shape = RoundedCornerShape(8.dp)
    val primary = MaterialTheme.colorScheme.primary
    val outline = MaterialTheme.colorScheme.outline
    Box(
        modifier = Modifier
            .size(24.dp)
            .clip(shape)
            .background(if (checked) primary else Color.Transparent)
            .border(width = if (checked) 0.dp else 1.5.dp, color = if (checked) primary else outline, shape = shape)
            .clickable { onCheckedChange(!checked) },
        contentAlignment = Alignment.Center,
    ) {
        if (checked) {
            Icon(
                Icons.Default.Check,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onPrimary,
                modifier = Modifier.size(16.dp),
            )
        }
    }
}

// ─── Layer card ─────────────────────────────────────────────────────────────

@Composable
private fun LayerCard(
    layer: Layer,
    enabled: Boolean,
    current: List<DriveImage>,
    alternatives: List<DriveImage>,
    onSetEnabled: (Boolean) -> Unit,
    onAdd: (String) -> Unit,
    onClear: (String) -> Unit,
    onSeeAll: () -> Unit,
) {
    val filled = current.isNotEmpty()
    Surface(
        shape = RoundedCornerShape(16.dp),
        color = MaterialTheme.colorScheme.surface,
        border = androidx.compose.foundation.BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
        modifier = Modifier
            .fillMaxWidth()
            .alpha(if (enabled) 1f else 0.5f),
    ) {
        Column(
            modifier = Modifier.padding(10.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            // Header row
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(
                    modifier = Modifier
                        .size(24.dp)
                        .clip(RoundedCornerShape(6.dp))
                        .background(
                            if (filled) MaterialTheme.colorScheme.primaryContainer
                            else MaterialTheme.colorScheme.background
                        ),
                    contentAlignment = Alignment.Center,
                ) {
                    Icon(
                        painter = androidx.compose.ui.res.painterResource(layer.iconRes),
                        contentDescription = null,
                        tint = if (filled) MaterialTheme.colorScheme.primary
                            else MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.size(14.dp),
                    )
                }
                Spacer(Modifier.width(8.dp))
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        stringResource(layer.labelRes),
                        style = MaterialTheme.typography.labelLarge.copy(fontSize = 12.sp, fontWeight = FontWeight.Bold),
                    )
                    if (filled) {
                        val label = if (current.size == 1) current.first().displayLabel()
                            else stringResource(R.string.composer_layer_picked_count, current.size)
                        Text(
                            label,
                            style = MaterialTheme.typography.bodySmall.copy(fontSize = 11.sp),
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                }
                Spacer(Modifier.width(8.dp))
                RoundCheckbox(checked = enabled, onCheckedChange = onSetEnabled)
            }

            // Filmstrip — current picks (with × to remove) then alternatives (tap to add).
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .horizontalScroll(rememberScrollState()),
                horizontalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                current.forEach { img ->
                    FilmstripTile(
                        image = img,
                        selected = true,
                        onClick = { onClear(img.driveId) },
                    )
                }
                alternatives.take(8).forEach { alt ->
                    FilmstripTile(image = alt, selected = false, onClick = { onAdd(alt.driveId) })
                }
                SeeAllTile(count = alternatives.size + current.size, onClick = onSeeAll)
            }
        }
    }
}

@Composable
private fun FilmstripTile(image: DriveImage, selected: Boolean, onClick: () -> Unit) {
    val ctx = LocalContext.current
    val shape = RoundedCornerShape(10.dp)
    Box(
        modifier = Modifier
            .size(72.dp)
            .clip(shape)
            .background(MaterialTheme.colorScheme.background)
            .then(
                if (selected) Modifier.border(2.dp, MaterialTheme.colorScheme.primary, shape)
                else Modifier.border(1.dp, MaterialTheme.colorScheme.outlineVariant, shape)
            )
            .clickable(onClick = onClick),
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
        if (selected) {
            // Tap the tile to remove — overlay shows × so the affordance is obvious.
            Box(
                modifier = Modifier
                    .align(Alignment.TopEnd)
                    .padding(3.dp)
                    .size(18.dp)
                    .clip(RoundedCornerShape(999.dp))
                    .background(MaterialTheme.colorScheme.primary),
                contentAlignment = Alignment.Center,
            ) {
                Icon(
                    Icons.Default.Close,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.onPrimary,
                    modifier = Modifier.size(12.dp),
                )
            }
        }
    }
}

@Composable
private fun SeeAllTile(count: Int, onClick: () -> Unit) {
    val shape = RoundedCornerShape(10.dp)
    val color = MaterialTheme.colorScheme.outline
    Column(
        modifier = Modifier
            .size(72.dp)
            .clip(shape)
            .background(MaterialTheme.colorScheme.background)
            .dashedBorder(color = color, width = 1.dp, radius = 10.dp)
            .clickable(onClick = onClick),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Icon(
            Icons.Default.MoreHoriz,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.size(16.dp),
        )
        Spacer(Modifier.height(2.dp))
        Text(
            stringResource(R.string.composer_see_all_count, count),
            style = MaterialTheme.typography.labelSmall.copy(fontSize = 9.sp, fontWeight = FontWeight.Bold),
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

// ─── Bottom bar ─────────────────────────────────────────────────────────────

@Composable
private fun BottomBar(
    name: String,
    onNameChange: (String) -> Unit,
    saveEnabled: Boolean,
    onSave: () -> Unit,
    bottomPadding: Dp,
) {
    HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .background(MaterialTheme.colorScheme.background.copy(alpha = 0.95f))
            .padding(bottom = bottomPadding)
            .padding(start = 16.dp, end = 16.dp, top = 10.dp, bottom = 4.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        OutlinedTextField(
            value = name,
            onValueChange = onNameChange,
            placeholder = { Text(stringResource(R.string.composer_name_placeholder)) },
            singleLine = true,
            shape = RoundedCornerShape(14.dp),
            leadingIcon = { Icon(Icons.Default.Edit, contentDescription = null, modifier = Modifier.size(14.dp)) },
            modifier = Modifier.weight(1f).defaultMinSize(minHeight = 48.dp),
        )
        Button(
            onClick = onSave,
            enabled = saveEnabled,
            shape = RoundedCornerShape(24.dp),
            contentPadding = PaddingValues(horizontal = 18.dp, vertical = 10.dp),
            modifier = Modifier.height(48.dp),
        ) {
            Icon(Icons.Default.Check, contentDescription = null, modifier = Modifier.size(16.dp))
            Spacer(Modifier.width(6.dp))
            Text(
                stringResource(R.string.composer_save),
                style = MaterialTheme.typography.labelLarge.copy(fontSize = 13.sp, fontWeight = FontWeight.Bold),
            )
        }
    }
}

// ─── Dashed border helper ───────────────────────────────────────────────────

private fun Modifier.dashedBorder(color: Color, width: Dp, radius: Dp): Modifier =
    this.then(
        Modifier.drawBehind {
            val strokePx = width.toPx()
            val radiusPx = radius.toPx()
            val pe = PathEffect.dashPathEffect(floatArrayOf(strokePx * 3, strokePx * 3), 0f)
            drawRoundRect(
                color = color,
                cornerRadius = CornerRadius(radiusPx, radiusPx),
                size = Size(size.width - strokePx, size.height - strokePx),
                topLeft = androidx.compose.ui.geometry.Offset(strokePx / 2, strokePx / 2),
                style = Stroke(width = strokePx, pathEffect = pe),
            )
        }
    )

// ─── Section header (used inside advanced block) ───────────────────────────

@Composable
private fun SectionHeader(text: String) {
    Text(
        text,
        style = MaterialTheme.typography.titleSmall,
        color = MaterialTheme.colorScheme.primary,
    )
}

// ─── Existing helpers (unchanged) ──────────────────────────────────────────

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
            val seasons = listOf(
                "Spring" to R.string.composer_season_spring,
                "Summer" to R.string.composer_season_summer,
                "Fall" to R.string.composer_season_fall,
                "Winter" to R.string.composer_season_winter,
            )
            seasons.forEach { (value, labelRes) ->
                FilterChip(
                    selected = season == value,
                    onClick = { onSeason(if (season == value) "" else value) },
                    label = { Text(stringResource(labelRes)) },
                )
            }
        }
        Text(stringResource(R.string.composer_weather_temp), style = MaterialTheme.typography.labelMedium)
        FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            listOf(-5, 5, 15, 22, 30).forEach { t ->
                FilterChip(
                    selected = tempC == t,
                    onClick = { onTempC(if (tempC == t) null else t) },
                    label = { Text(stringResource(R.string.composer_temp_value, t)) },
                )
            }
        }
        Text(stringResource(R.string.composer_weather_precip), style = MaterialTheme.typography.labelMedium)
        FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            val precips = listOf(
                "None" to R.string.composer_precip_none,
                "Light" to R.string.composer_precip_light,
                "Heavy" to R.string.composer_precip_heavy,
            )
            precips.forEach { (value, labelRes) ->
                FilterChip(
                    selected = precip == value,
                    onClick = { onPrecip(if (precip == value) "" else value) },
                    label = { Text(stringResource(labelRes)) },
                )
            }
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
            IconButton(onClick = { if (value > 0) onValue(value - 1) }) { Text(stringResource(R.string.composer_stepper_decrement)) }
            Text("$value", modifier = Modifier.width(24.dp), style = MaterialTheme.typography.bodyMedium)
            IconButton(onClick = { if (value < 5) onValue(value + 1) }) { Text(stringResource(R.string.composer_stepper_increment)) }
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

@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
internal fun OutfitTagsEditor(
    tags: List<String>,
    onAdd: (String) -> Unit,
    onRemove: (String) -> Unit,
) {
    var input by remember { mutableStateOf("") }
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        if (tags.isNotEmpty()) {
            FlowRow(
                horizontalArrangement = Arrangement.spacedBy(6.dp),
                verticalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                tags.forEach { tag ->
                    InputChip(
                        selected = true,
                        onClick = { onRemove(tag) },
                        label = { Text(tag, style = MaterialTheme.typography.labelSmall) },
                        trailingIcon = {
                            Icon(
                                Icons.Default.Close,
                                contentDescription = stringResource(R.string.outfits_tag_remove, tag),
                                modifier = Modifier.size(14.dp),
                            )
                        },
                    )
                }
            }
        }
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            OutlinedTextField(
                value = input,
                onValueChange = { input = it },
                placeholder = { Text(stringResource(R.string.outfits_tag_add_placeholder)) },
                singleLine = true,
                modifier = Modifier.weight(1f),
            )
            TextButton(
                onClick = {
                    val t = input.trim()
                    if (t.isNotEmpty()) { onAdd(t); input = "" }
                },
                enabled = input.trim().isNotEmpty(),
            ) {
                Icon(Icons.Default.Add, contentDescription = null, modifier = Modifier.size(18.dp))
                Spacer(Modifier.width(4.dp))
                Text(stringResource(R.string.outfits_tag_add))
            }
        }
    }
}

// ─── Context-strip sheets ──────────────────────────────────────────────────

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun WeatherPickerSheet(
    mode: ComposerWeatherMode,
    onModeChange: (ComposerWeatherMode) -> Unit,
    autoWeather: WeatherData?,
    season: String,
    onSeason: (String) -> Unit,
    tempC: Int?,
    onTempC: (Int?) -> Unit,
    precip: String,
    onPrecip: (String) -> Unit,
    onDismiss: () -> Unit,
) {
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .navigationBarsPadding()
                .padding(horizontal = 16.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    stringResource(R.string.composer_weather_title),
                    style = MaterialTheme.typography.titleMedium,
                    modifier = Modifier.weight(1f),
                )
                TextButton(onClick = onDismiss) { Text(stringResource(R.string.composer_sheet_done)) }
            }
            WeatherSection(
                mode = mode,
                onModeChange = onModeChange,
                autoWeather = autoWeather,
                season = season,
                onSeason = onSeason,
                tempC = tempC,
                onTempC = onTempC,
                precip = precip,
                onPrecip = onPrecip,
            )
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
private fun ClosetPickerSheet(
    locations: List<Location>,
    selected: Set<String>,
    onToggle: (String) -> Unit,
    onDismiss: () -> Unit,
) {
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .navigationBarsPadding()
                .padding(horizontal = 16.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    stringResource(R.string.composer_closets_title),
                    style = MaterialTheme.typography.titleMedium,
                    modifier = Modifier.weight(1f),
                )
                TextButton(onClick = onDismiss) { Text(stringResource(R.string.composer_sheet_done)) }
            }
            FlowRow(
                horizontalArrangement = Arrangement.spacedBy(6.dp),
                verticalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                // "All" chip: selected when nothing is explicitly picked (= all closets in scope).
                // Tapping it clears the selection back to "all".
                FilterChip(
                    selected = selected.isEmpty(),
                    onClick = {
                        if (selected.isNotEmpty()) selected.forEach { onToggle(it) }
                    },
                    label = { Text(stringResource(R.string.composer_closets_all)) },
                )
                locations.forEach { loc ->
                    FilterChip(
                        selected = loc.folderId in selected,
                        onClick = { onToggle(loc.folderId) },
                        label = { Text(loc.name) },
                    )
                }
            }
        }
    }
}
