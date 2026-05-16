package com.librelookai.outfit
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
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.LockOpen
import androidx.compose.material.icons.filled.MoreHoriz
import androidx.compose.material.icons.filled.Place
import androidx.compose.material.icons.filled.SwapHoriz
import androidx.compose.material.icons.filled.Tune
import androidx.compose.material.icons.filled.WbSunny
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.OutlinedButton
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
import androidx.compose.ui.draw.blur
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.offset
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
import com.librelookai.data.model.Location
import com.librelookai.settings.ProfileViewModel
import com.librelookai.shopping.ShoppingClosetViewModel
import com.librelookai.util.AiProcessingOverlay
import com.librelookai.util.Analytics
import com.librelookai.util.LocalIsOffline
import com.librelookai.util.LocalSystemBarsPadding
import com.librelookai.wardrobe.DriveImage
import com.librelookai.wardrobe.LocationViewModel
import com.librelookai.wardrobe.WardrobeViewModel
import com.librelookai.weather.WeatherData
import com.librelookai.weather.WeatherViewModel
import com.librelookai.R
import com.librelookai.weather.wmoEmoji

private fun DriveImage.displayLabel(): String =
    tags?.label?.ifEmpty { null }
        ?: tags?.type?.ifEmpty { null }
        ?: name

/**
 * Unified full-screen composer for creating/viewing an outfit.
 * In VIEW mode: read-only slot cards with images or silhouettes.
 * In EDIT mode: lock toggles, exchange buttons, "+ Add slot", Generate-with-AI, Save.
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

    val isEditMode = s.composerMode == ComposerMode.EDIT
    val sourceFolders = s.composerSourceFolderIds
    val crossClosetImages = wardrobe.allLocationImages.ifEmpty { wardrobe.images }
    val composerImages = remember(crossClosetImages, shoppingState.items, sourceFolders) {
        val filteredWardrobe = if (sourceFolders.isEmpty()) crossClosetImages
        else crossClosetImages.filter { it.folderId in sourceFolders }
        filteredWardrobe + shoppingState.items
    }
    val byId = remember(composerImages) { composerImages.associateBy { it.driveId } }

    var showAddSlotSheet by remember { mutableStateOf(false) }
    var exchangeSlotId by remember { mutableStateOf<String?>(null) }
    var showWeatherSheet by remember { mutableStateOf(false) }
    var showClosetSheet by remember { mutableStateOf(false) }
    var showDiscardDialog by remember { mutableStateOf(false) }

    val hasContent = s.composerSlots.any { it.selectedItemId != null } ||
        s.composerTags.isNotEmpty() || s.composerVibes.isNotEmpty()
    val requestClose: () -> Unit = {
        if (isEditMode && hasContent) showDiscardDialog = true
        else {
            Analytics.action("OutfitComposer", "close")
            stylesViewModel.closeComposer()
        }
    }

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

    val filledSlots = s.composerSlots.count { it.selectedItemId != null }

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
                        ComposerHeader(
                            filledSlots = filledSlots,
                            totalSlots = s.composerSlots.size,
                            isEditMode = isEditMode,
                            topPadding = effectiveTop,
                            onClose = requestClose,
                            onToggleMode = {
                                stylesViewModel.setComposerMode(
                                    if (isEditMode) ComposerMode.VIEW else ComposerMode.EDIT
                                )
                            },
                        )

                        ComposerStackedView(
                            slots = s.composerSlots,
                            byId = byId,
                            locations = locationState.locations,
                            isEditMode = isEditMode,
                            onPickItem = { slotId -> exchangeSlotId = slotId },
                            onToggleLock = { slotId -> stylesViewModel.toggleSlotLock(slotId) },
                            onRemove = { slotId -> stylesViewModel.removeSlot(slotId) },
                            modifier = Modifier
                                .weight(1f)
                                .fillMaxWidth()
                                .padding(horizontal = 12.dp, vertical = 8.dp),
                        )

                        if (isEditMode || s.composerReason.isNotBlank() || s.composerError != null) {
                            Column(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(horizontal = 16.dp)
                                    .padding(bottom = 8.dp),
                                verticalArrangement = Arrangement.spacedBy(8.dp),
                            ) {
                                if (isEditMode) {
                                    TextButton(
                                        onClick = { showAddSlotSheet = true },
                                        modifier = Modifier.fillMaxWidth(),
                                    ) {
                                        Icon(Icons.Default.Add, contentDescription = null, modifier = Modifier.size(16.dp))
                                        Spacer(Modifier.width(4.dp))
                                        Text(stringResource(R.string.outfit_slot_add))
                                    }
                                }
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
                        }

                        if (isEditMode) {
                            ComposerEditBottomBar(
                                saveEnabled = s.composerSlots.any { it.selectedItemId != null },
                                isOffline = isOffline,
                                onGenerateWithAi = {
                                    Analytics.action("OutfitComposer", "generate_with_ai")
                                    stylesViewModel.openPredictionSetup(
                                        defaultSourceFolderId = null,
                                        source = PredictionSetupSource.COMPOSER,
                                    )
                                },
                                onSave = {
                                    Analytics.action("OutfitComposer", "save")
                                    stylesViewModel.prepareSave()
                                },
                                bottomPadding = effectiveBottom,
                            )
                        }
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

    if (showDiscardDialog) {
        DiscardChangesDialog(
            parentContext = parentContext,
            parentConfiguration = parentConfiguration,
            onConfirm = {
                Analytics.action("OutfitComposer", "close")
                stylesViewModel.closeComposer()
            },
            onDismiss = { showDiscardDialog = false },
        )
    }

    if (s.isSaveDialogOpen) {
        val existingOutfit = s.composerEditingOutfitId?.let { id -> s.outfits.find { it.id == id } }
        SaveOutfitDialog(
            initialName = s.composerName.ifBlank {
                existingOutfit?.name ?: s.composerAiSuggestedName
            },
            initialDescription = s.composerDescription.ifBlank {
                existingOutfit?.description ?: s.composerAiSuggestedDescription
            },
            initialTags = s.composerTags.ifEmpty {
                existingOutfit?.tags ?: s.composerAiSuggestedTags
            },
            parentContext = parentContext,
            parentConfiguration = parentConfiguration,
            onConfirm = { name, description, tags ->
                stylesViewModel.dismissSaveDialog()
                stylesViewModel.commitOutfit(name, description, tags)
            },
            onDismiss = { stylesViewModel.dismissSaveDialog() },
        )
    }

    exchangeSlotId?.let { slotId ->
        val slot = s.composerSlots.find { it.id == slotId }
        if (slot != null) {
            CompositionLocalProvider(
                LocalContext provides parentContext,
                LocalConfiguration provides parentConfiguration,
            ) {
                val layerItems = composerImages.filter { layerFor(it) == slot.category }
                val alreadyChosen = s.composerSlots
                    .filter { it.id != slotId }
                    .mapNotNull { it.selectedItemId }
                    .toSet()
                AddItemSheet(
                    allItems = layerItems,
                    alreadyChosen = alreadyChosen,
                    onConfirm = { picked ->
                        picked.firstOrNull()?.let { stylesViewModel.setSlotItem(slotId, it) }
                        exchangeSlotId = null
                    },
                    onDismiss = { exchangeSlotId = null },
                )
            }
        }
    }

    if (showAddSlotSheet) {
        CompositionLocalProvider(
            LocalContext provides parentContext,
            LocalConfiguration provides parentConfiguration,
        ) {
            CategoryPickerSheet(
                onSelect = { layer ->
                    stylesViewModel.addSlot(layer)
                    showAddSlotSheet = false
                },
                onDismiss = { showAddSlotSheet = false },
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
private fun ComposerHeader(
    filledSlots: Int,
    totalSlots: Int,
    isEditMode: Boolean,
    topPadding: Dp,
    onClose: () -> Unit,
    onToggleMode: () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(start = 8.dp, end = 8.dp, top = topPadding, bottom = 4.dp),
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
                stringResource(R.string.composer_header_subtitle, filledSlots),
                style = MaterialTheme.typography.bodySmall.copy(fontSize = 11.sp),
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        TextButton(onClick = onToggleMode) {
            Text(
                if (isEditMode) stringResource(R.string.outfit_mode_view)
                else stringResource(R.string.outfit_mode_edit),
                style = MaterialTheme.typography.labelMedium,
            )
        }
    }
    HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
}


// ─── Context strip ──────────────────────────────────────────────────────────

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun ContextStrip(
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




// ─── Bottom bar (edit mode) ──────────────────────────────────────────────────

@Composable
private fun ComposerEditBottomBar(
    saveEnabled: Boolean,
    isOffline: Boolean,
    onGenerateWithAi: () -> Unit,
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
        if (!isOffline) {
            OutlinedButton(
                onClick = onGenerateWithAi,
                modifier = Modifier.weight(1f).height(48.dp),
                shape = RoundedCornerShape(24.dp),
            ) {
                Icon(Icons.Default.AutoAwesome, contentDescription = null, modifier = Modifier.size(16.dp))
                Spacer(Modifier.width(6.dp))
                Text(
                    stringResource(R.string.outfit_generate_with_ai),
                    style = MaterialTheme.typography.labelLarge.copy(fontSize = 13.sp, fontWeight = FontWeight.Bold),
                )
            }
        }
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

// ─── Stacked composer view (overlapping tiles with drop shadows) ────────────

@Composable
private fun ComposerStackedView(
    slots: List<OutfitSlot>,
    byId: Map<String, DriveImage>,
    locations: List<Location>,
    isEditMode: Boolean,
    onPickItem: (slotId: String) -> Unit,
    onToggleLock: (slotId: String) -> Unit,
    onRemove: (slotId: String) -> Unit,
    modifier: Modifier = Modifier,
) {
    // In view mode, hide empty silhouettes — match OutfitFullScreenViewer rendering.
    val visibleSlots = remember(slots, isEditMode) {
        if (isEditMode) slots else slots.filter { it.selectedItemId != null }
    }
    val rows: List<List<OutfitSlot>> = remember(visibleSlots) {
        val grouped = visibleSlots.groupBy { it.category }
        Layer.values().mapNotNull { layer -> grouped[layer]?.takeIf { it.isNotEmpty() } }
    }
    BoxWithConstraints(modifier = modifier) {
        if (rows.isEmpty()) {
            Text(
                stringResource(R.string.outfits_missing_items),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.outline,
                modifier = Modifier.align(Alignment.Center),
            )
            return@BoxWithConstraints
        }
        val rowOverlap = 0.28f
        val itemOverlap = 0.18f
        val n = rows.size
        val m = (rows.maxOfOrNull { it.size } ?: 1).coerceAtLeast(1)
        val availW = maxWidth
        val availH = maxHeight
        val byH = availH / (1f + (1f - rowOverlap) * (n - 1))
        val byW = availW / (1f + (1f - itemOverlap) * (m - 1))
        val tileSize = minOf(byH, byW).coerceIn(96.dp, 320.dp)
        val rowStride = tileSize * (1f - rowOverlap)
        val itemStride = tileSize * (1f - itemOverlap)
        val totalContentH = tileSize + rowStride * (n - 1)
        val topOffset = ((availH - totalContentH) / 2).coerceAtLeast(0.dp)

        rows.forEachIndexed { rowIdx, rowSlots ->
            val rowWidth = tileSize + itemStride * (rowSlots.size - 1)
            val rowLeft = ((availW - rowWidth) / 2).coerceAtLeast(0.dp)
            val rowTop = topOffset + rowStride * rowIdx
            rowSlots.forEachIndexed { itemIdx, slot ->
                val left = rowLeft + itemStride * itemIdx
                val image = slot.selectedItemId?.let { byId[it] }
                val locName = image?.let {
                    if (locations.size > 1) locations.find { l -> l.folderId == it.folderId }?.name else null
                }
                ComposerStackedTile(
                    slot = slot,
                    image = image,
                    locationName = locName,
                    isEditMode = isEditMode,
                    size = tileSize,
                    onTap = { onPickItem(slot.id) },
                    onToggleLock = { onToggleLock(slot.id) },
                    onRemove = { onRemove(slot.id) },
                    modifier = Modifier.offset(x = left, y = rowTop),
                )
            }
        }
    }
}

@Composable
private fun ComposerStackedTile(
    slot: OutfitSlot,
    image: DriveImage?,
    locationName: String?,
    isEditMode: Boolean,
    size: Dp,
    onTap: () -> Unit,
    onToggleLock: () -> Unit,
    onRemove: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val ctx = LocalContext.current
    Box(
        modifier = modifier
            .size(size)
            .then(if (isEditMode) Modifier.clickable(onClick = onTap) else Modifier),
    ) {
        if (image != null) {
            val model = remember(image.driveId, image.version) {
                ImageRequest.Builder(ctx)
                    .data(image.localPath)
                    .memoryCacheKey("${image.driveId}_${image.version}")
                    .build()
            }
            AsyncImage(
                model = model,
                contentDescription = null,
                modifier = Modifier
                    .fillMaxSize()
                    .offset(x = 3.dp, y = 6.dp)
                    .blur(radius = 8.dp)
                    .graphicsLayer { alpha = 0.45f },
                contentScale = ContentScale.Fit,
                colorFilter = ColorFilter.tint(Color.Black, BlendMode.SrcIn),
            )
            AsyncImage(
                model = model,
                contentDescription = image.name,
                modifier = Modifier.fillMaxSize(),
                contentScale = ContentScale.Fit,
            )
            if (locationName != null) {
                Box(
                    modifier = Modifier
                        .align(Alignment.TopEnd)
                        .padding(4.dp)
                        .background(
                            color = MaterialTheme.colorScheme.secondaryContainer.copy(alpha = 0.85f),
                            shape = MaterialTheme.shapes.extraSmall,
                        )
                        .padding(horizontal = 4.dp, vertical = 1.dp),
                ) {
                    Text(
                        text = locationName,
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSecondaryContainer,
                        fontSize = 8.sp,
                        lineHeight = 10.sp,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
        } else {
            // Empty slot silhouette (edit mode only — view mode hides empty slots).
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .dashedBorder(
                        color = MaterialTheme.colorScheme.outline.copy(alpha = 0.55f),
                        width = 1.5.dp,
                        radius = 16.dp,
                    ),
                contentAlignment = Alignment.Center,
            ) {
                Column(
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    Icon(
                        painter = androidx.compose.ui.res.painterResource(slot.category.iconRes),
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.45f),
                        modifier = Modifier.size(size * 0.32f),
                    )
                    Text(
                        stringResource(slot.category.labelRes),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f),
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
        }

        if (isEditMode) {
            // Top-left: remove. Top-right: lock toggle (filled only).
            IconButton(
                onClick = onRemove,
                modifier = Modifier
                    .align(Alignment.TopStart)
                    .size(28.dp)
                    .background(
                        MaterialTheme.colorScheme.background.copy(alpha = 0.85f),
                        RoundedCornerShape(50),
                    ),
            ) {
                Icon(
                    Icons.Default.Close,
                    contentDescription = stringResource(R.string.outfit_slot_remove),
                    modifier = Modifier.size(16.dp),
                    tint = MaterialTheme.colorScheme.error,
                )
            }
            if (image != null) {
                IconButton(
                    onClick = onToggleLock,
                    modifier = Modifier
                        .align(Alignment.TopEnd)
                        .size(28.dp)
                        .background(
                            MaterialTheme.colorScheme.background.copy(alpha = 0.85f),
                            RoundedCornerShape(50),
                        ),
                ) {
                    Icon(
                        if (slot.isLocked) Icons.Default.Lock else Icons.Default.LockOpen,
                        contentDescription = stringResource(
                            if (slot.isLocked) R.string.outfit_slot_unlock else R.string.outfit_slot_lock
                        ),
                        modifier = Modifier.size(16.dp),
                        tint = if (slot.isLocked) MaterialTheme.colorScheme.primary
                               else MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }
    }
}

// ─── Category picker sheet ──────────────────────────────────────────────────

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun CategoryPickerSheet(
    onSelect: (Layer) -> Unit,
    onDismiss: () -> Unit,
) {
    val parentContext = LocalContext.current
    val parentConfiguration = LocalConfiguration.current
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
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
                verticalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                Text(
                    stringResource(R.string.outfit_pick_category),
                    style = MaterialTheme.typography.titleMedium,
                    modifier = Modifier.padding(bottom = 8.dp),
                )
                Layer.values().forEach { layer ->
                    TextButton(
                        onClick = { onSelect(layer) },
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        Icon(
                            painter = androidx.compose.ui.res.painterResource(layer.iconRes),
                            contentDescription = null,
                            modifier = Modifier.size(18.dp),
                        )
                        Spacer(Modifier.width(12.dp))
                        Text(
                            stringResource(layer.labelRes),
                            modifier = Modifier.weight(1f),
                        )
                    }
                }
            }
        }
    }
}

// ─── Save outfit dialog ──────────────────────────────────────────────────────

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun SaveOutfitDialog(
    initialName: String,
    initialDescription: String,
    initialTags: List<String>,
    parentContext: android.content.Context,
    parentConfiguration: android.content.res.Configuration,
    onConfirm: (name: String, description: String, tags: List<String>) -> Unit,
    onDismiss: () -> Unit,
) {
    var name by remember(initialName) { mutableStateOf(initialName) }
    var description by remember(initialDescription) { mutableStateOf(initialDescription) }
    var tags by remember(initialTags) { mutableStateOf(initialTags) }
    var newTagInput by remember { mutableStateOf("") }

    val locale: @Composable (@Composable () -> Unit) -> Unit = { content ->
        CompositionLocalProvider(
            LocalContext provides parentContext,
            LocalConfiguration provides parentConfiguration,
        ) { content() }
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { locale { Text(stringResource(R.string.outfit_save_dialog_title)) } },
        text = {
            locale {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedTextField(
                        value = name,
                        onValueChange = { name = it },
                        label = { Text(stringResource(R.string.outfit_save_dialog_name)) },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth(),
                    )
                    OutlinedTextField(
                        value = description,
                        onValueChange = { description = it },
                        label = { Text(stringResource(R.string.outfit_save_dialog_description)) },
                        singleLine = false,
                        minLines = 2,
                        maxLines = 4,
                        modifier = Modifier.fillMaxWidth(),
                    )
                    Text(
                        stringResource(R.string.outfit_save_dialog_tags),
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.primary,
                    )
                    OutfitTagsEditor(
                        tags = tags,
                        onAdd = { t -> if (t.isNotBlank()) tags = (tags + t).distinctBy { it.lowercase() } },
                        onRemove = { t -> tags = tags - t },
                    )
                }
            }
        },
        confirmButton = {
            locale {
                TextButton(onClick = { onConfirm(name.trim(), description.trim(), tags) }) {
                    Text(stringResource(R.string.outfit_save_dialog_confirm))
                }
            }
        },
        dismissButton = {
            locale {
                TextButton(onClick = onDismiss) {
                    Text(stringResource(R.string.action_cancel))
                }
            }
        },
    )
}

// ─── Discard changes dialog ──────────────────────────────────────────────────

@Composable
internal fun DiscardChangesDialog(
    parentContext: android.content.Context,
    parentConfiguration: android.content.res.Configuration,
    onConfirm: () -> Unit,
    onDismiss: () -> Unit,
) {
    val locale: @Composable (@Composable () -> Unit) -> Unit = { content ->
        CompositionLocalProvider(
            LocalContext provides parentContext,
            LocalConfiguration provides parentConfiguration,
        ) { content() }
    }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { locale { Text(stringResource(R.string.outfit_discard_changes_title)) } },
        text = { locale { Text(stringResource(R.string.outfit_discard_changes_body)) } },
        confirmButton = {
            locale {
                TextButton(onClick = onConfirm) {
                    Text(stringResource(R.string.outfit_discard_changes_confirm))
                }
            }
        },
        dismissButton = {
            locale {
                TextButton(onClick = onDismiss) {
                    Text(stringResource(R.string.action_cancel))
                }
            }
        },
    )
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
internal fun WeatherPickerSheet(
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
    val parentContext = LocalContext.current
    val parentConfiguration = LocalConfiguration.current
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
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
}

@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
internal fun ClosetPickerSheet(
    locations: List<Location>,
    selected: Set<String>,
    onToggle: (String) -> Unit,
    onDismiss: () -> Unit,
) {
    val parentContext = LocalContext.current
    val parentConfiguration = LocalConfiguration.current
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
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
}
