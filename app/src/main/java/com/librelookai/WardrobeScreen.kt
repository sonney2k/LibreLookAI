package com.librelookai

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.provider.Settings
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.itemsIndexed
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.material.icons.Icons
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.ArrowDropDown
import androidx.compose.material.icons.filled.AutoAwesome
import androidx.compose.material.icons.filled.AutoFixHigh
import androidx.compose.material.icons.filled.CameraAlt
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.ImageSearch
import androidx.compose.material.icons.filled.Link
import androidx.compose.material.icons.filled.PhotoLibrary
import androidx.compose.material.icons.filled.Place
import androidx.compose.material.icons.filled.ShoppingBag
import androidx.compose.material.icons.automirrored.filled.RotateRight
import androidx.compose.material.icons.automirrored.filled.Sort
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.InputChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.SmallFloatingActionButton
import androidx.compose.material3.Snackbar
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import androidx.compose.ui.res.stringResource
import androidx.lifecycle.viewmodel.compose.viewModel
import coil.compose.AsyncImage
import coil.request.ImageRequest

@Composable
fun WardrobeScreen(
    viewModel: WardrobeViewModel = viewModel(),
    outfitEventsViewModel: OutfitEventsViewModel = viewModel(),
    stylesViewModel: OutfitsViewModel = viewModel(),
    locationViewModel: LocationViewModel = viewModel(),
    shoppingClosetViewModel: ShoppingClosetViewModel = viewModel(),
    profileViewModel: ProfileViewModel = viewModel(),
    onCreateOutfitFromSelection: (Set<String>) -> Unit = {},
    onTryOnSelection: (Set<String>) -> Unit = {},
    canTryOn: Boolean = false,
    dismissViewerTrigger: Int = 0,
    onSettingsClick: () -> Unit = {},
    modifier: Modifier = Modifier,
) {
    val state         by viewModel.state.collectAsState()
    val outfitEventsState  by outfitEventsViewModel.state.collectAsState()
    val outfitsState   by stylesViewModel.state.collectAsState()
    val locationState by locationViewModel.state.collectAsState()
    val profileState  by profileViewModel.state.collectAsState()
    val context = LocalContext.current

    // driveId → number of calendar wear events that include this item
    val popularityMap = remember(outfitEventsState.events, outfitsState.outfits) {
        val outfitWearCount = outfitEventsState.events.groupingBy { it.outfitId }.eachCount()
        val itemCount = mutableMapOf<String, Int>()
        outfitsState.outfits.forEach { style ->
            val count = outfitWearCount[style.id] ?: 0
            if (count > 0) style.itemIds.forEach { id -> itemCount[id] = (itemCount[id] ?: 0) + count }
        }
        itemCount as Map<String, Int>
    }

    var hasCameraPermission by remember {
        mutableStateOf(
            ContextCompat.checkSelfPermission(context, Manifest.permission.CAMERA)
                == PackageManager.PERMISSION_GRANTED,
        )
    }
    var pendingCameraAction by remember { mutableStateOf<(() -> Unit)?>(null) }
    val permissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission(),
    ) { granted ->
        hasCameraPermission = granted
        if (granted) {
            (pendingCameraAction ?: { viewModel.openCapture() }).invoke()
        }
        pendingCameraAction = null
    }

    val galleryLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.PickMultipleVisualMedia(),
    ) { uris -> viewModel.uploadGalleryPhotos(uris) }

    state.duplicateCheck?.let { check ->
        DuplicateCheckSheet(
            check = check,
            onConfirm = viewModel::confirmDuplicateImport,
            onCancel = viewModel::cancelDuplicateImport,
        )
    }

    when (state.view) {
        WardrobeView.GRID -> GridContent(
            state = state,
            popularityMap = popularityMap,
            locations = locationState.locations,
            activeLocationId = locationState.activeLocationId,
            onOpenCamera = {
                if (hasCameraPermission) viewModel.openCapture()
                else {
                    pendingCameraAction = { viewModel.openCapture() }
                    permissionLauncher.launch(Manifest.permission.CAMERA)
                }
            },
            onOpenGallery = {
                galleryLauncher.launch(
                    PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly),
                )
            },
            onImportUrl = viewModel::importFromUrl,
            onDismissError = viewModel::clearError,
            onTagImage = viewModel::tagImage,
            onRemoveBackground = viewModel::reprocessBackground,
            onRotateImage = viewModel::rotateImage,
            onUpdateTags = viewModel::updateTags,
            onToggleSelection = viewModel::toggleSelection,
            onSelectAll = viewModel::selectAll,
            onClearSelection = viewModel::clearSelection,
            onDeleteSelected = viewModel::deleteSelected,
            onDeleteItem = { driveId -> viewModel.deleteItems(setOf(driveId)) },
            onMoveToLocation = viewModel::moveItemsToLocation,
            onSetActiveLocation = locationViewModel::setActiveLocation,
            onCreateOutfitFromSelection = onCreateOutfitFromSelection,
            onTryOnSelection = onTryOnSelection,
            canTryOn = canTryOn,
            onDismissBatteryExemption = viewModel::dismissBatteryExemptionWarning,
            onSetImportTarget = viewModel::setDefaultImportFolderId,
            processingImageId = state.processingImageId,
            isLocationLoading = locationState.isLoading,
            locationError = locationState.error,
            dismissViewerTrigger = dismissViewerTrigger,
            onSettingsClick = onSettingsClick,
            onOpenFindByPhoto = {
                if (hasCameraPermission) viewModel.openFindByPhoto()
                else {
                    pendingCameraAction = { viewModel.openFindByPhoto() }
                    permissionLauncher.launch(Manifest.permission.CAMERA)
                }
            },
            onDismissFindByPhoto = viewModel::dismissFindByPhoto,
            onConsumePendingScroll = viewModel::consumePendingScroll,
            onAddMatchToShoppingList = shoppingClosetViewModel::importQuery,
            debugSimilarityPreview = profileState.preferences.debugSimilarityPreview,
            modifier = modifier,
        )
        WardrobeView.CAPTURE -> CaptureScreen(
            onPhotoTaken = viewModel::uploadPhoto,
            onCancel = viewModel::closeCapture,
            locations = locationState.locations,
            importTargetFolderId = state.importTargetFolderId,
            onSetImportTarget = viewModel::setDefaultImportFolderId,
            modifier = modifier,
        )
        WardrobeView.FIND_BY_PHOTO_CAPTURE -> CaptureScreen(
            onPhotoTaken = viewModel::onFindByPhotoCaptured,
            onCancel = viewModel::closeFindByPhoto,
            locations = emptyList(),
            showCenterCrosshair = true,
            modifier = modifier,
        )
    }
}

internal enum class SortOption { DATE_DESC, DATE_ASC, POPULARITY, TYPE, CATEGORY }

@Composable
internal fun SortOption.displayLabel(): String = when (this) {
    SortOption.DATE_DESC  -> stringResource(R.string.wardrobe_sort_newest)
    SortOption.DATE_ASC   -> stringResource(R.string.wardrobe_sort_oldest)
    SortOption.POPULARITY -> stringResource(R.string.wardrobe_sort_most_worn)
    SortOption.TYPE       -> stringResource(R.string.wardrobe_sort_type)
    SortOption.CATEGORY   -> stringResource(R.string.wardrobe_sort_category)
}

internal data class TagCategory(val label: String, val tags: List<String>)

@Composable
internal fun tagCategoryDisplayLabel(key: String): String = when (key) {
    "Type"        -> stringResource(R.string.tag_type)
    "Category"    -> stringResource(R.string.tag_category)
    "Uses"        -> stringResource(R.string.tag_uses)
    "Colors"      -> stringResource(R.string.tag_colors)
    "Seasonality" -> stringResource(R.string.tag_seasonality)
    "Aesthetic"   -> stringResource(R.string.tag_aesthetic)
    "Fit"         -> stringResource(R.string.tag_fit)
    "Material"    -> stringResource(R.string.tag_material)
    "Pattern"     -> stringResource(R.string.tag_pattern)
    else          -> key
}

/** Maps a stored English tag value to its localized display string. Unknown values pass through. */
@Composable
internal fun String.localizedTagValue(): String = when (this.lowercase()) {
    // Uses
    "casual"          -> stringResource(R.string.tag_val_casual)
    "formal"          -> stringResource(R.string.tag_val_formal)
    "business"        -> stringResource(R.string.tag_val_business)
    "sport"           -> stringResource(R.string.tag_val_sport)
    "outdoor"         -> stringResource(R.string.tag_val_outdoor)
    "beach"           -> stringResource(R.string.tag_val_beach)
    "evening"         -> stringResource(R.string.tag_val_evening)
    // Seasonality
    "spring"          -> stringResource(R.string.tag_val_spring)
    "summer"          -> stringResource(R.string.tag_val_summer)
    "fall"            -> stringResource(R.string.tag_val_fall)
    "winter"          -> stringResource(R.string.tag_val_winter)
    // Aesthetic
    "minimalist"      -> stringResource(R.string.tag_val_minimalist)
    "streetwear"      -> stringResource(R.string.tag_val_streetwear)
    "preppy"          -> stringResource(R.string.tag_val_preppy)
    "bohemian"        -> stringResource(R.string.tag_val_bohemian)
    "classic"         -> stringResource(R.string.tag_val_classic)
    "sporty"          -> stringResource(R.string.tag_val_sporty)
    "romantic"        -> stringResource(R.string.tag_val_romantic)
    "edgy"            -> stringResource(R.string.tag_val_edgy)
    "business-casual" -> stringResource(R.string.tag_val_business_casual)
    "luxury"          -> stringResource(R.string.tag_val_luxury)
    // Fit
    "slim"            -> stringResource(R.string.tag_val_slim)
    "regular"         -> stringResource(R.string.tag_val_regular)
    "relaxed"         -> stringResource(R.string.tag_val_relaxed)
    "oversized"       -> stringResource(R.string.tag_val_oversized)
    "tailored"        -> stringResource(R.string.tag_val_tailored)
    // Material
    "cotton"          -> stringResource(R.string.tag_val_cotton)
    "denim"           -> stringResource(R.string.tag_val_denim)
    "wool"            -> stringResource(R.string.tag_val_wool)
    "leather"         -> stringResource(R.string.tag_val_leather)
    "polyester"       -> stringResource(R.string.tag_val_polyester)
    "linen"           -> stringResource(R.string.tag_val_linen)
    "silk"            -> stringResource(R.string.tag_val_silk)
    "knit"            -> stringResource(R.string.tag_val_knit)
    // Pattern
    "solid"           -> stringResource(R.string.tag_val_solid)
    "stripes"         -> stringResource(R.string.tag_val_stripes)
    "plaid"           -> stringResource(R.string.tag_val_plaid)
    "floral"          -> stringResource(R.string.tag_val_floral)
    "geometric"       -> stringResource(R.string.tag_val_geometric)
    "animal-print"    -> stringResource(R.string.tag_val_animal_print)
    "graphic"         -> stringResource(R.string.tag_val_graphic)
    "camo"            -> stringResource(R.string.tag_val_camo)
    "abstract"        -> stringResource(R.string.tag_val_abstract)
    // Category
    "tops"            -> stringResource(R.string.tag_val_tops)
    "bottoms"         -> stringResource(R.string.tag_val_bottoms)
    "outerwear"       -> stringResource(R.string.tag_val_outerwear)
    "footwear"        -> stringResource(R.string.tag_val_footwear)
    "accessories"     -> stringResource(R.string.tag_val_accessories)
    "dress"           -> stringResource(R.string.tag_val_dress)
    "suit"            -> stringResource(R.string.tag_val_suit)
    // Colors
    "black"           -> stringResource(R.string.tag_val_black)
    "white"           -> stringResource(R.string.tag_val_white)
    "grey"            -> stringResource(R.string.tag_val_grey)
    "gray"            -> stringResource(R.string.tag_val_gray)
    "charcoal"        -> stringResource(R.string.tag_val_charcoal)
    "brown"           -> stringResource(R.string.tag_val_brown)
    "beige"           -> stringResource(R.string.tag_val_beige)
    "cream"           -> stringResource(R.string.tag_val_cream)
    "ivory"           -> stringResource(R.string.tag_val_ivory)
    "tan"             -> stringResource(R.string.tag_val_tan)
    "camel"           -> stringResource(R.string.tag_val_camel)
    "red"             -> stringResource(R.string.tag_val_red)
    "burgundy"        -> stringResource(R.string.tag_val_burgundy)
    "wine"            -> stringResource(R.string.tag_val_wine)
    "coral"           -> stringResource(R.string.tag_val_coral)
    "pink"            -> stringResource(R.string.tag_val_pink)
    "blush"           -> stringResource(R.string.tag_val_blush)
    "magenta"         -> stringResource(R.string.tag_val_magenta)
    "fuchsia"         -> stringResource(R.string.tag_val_fuchsia)
    "orange"          -> stringResource(R.string.tag_val_orange)
    "rust"            -> stringResource(R.string.tag_val_rust)
    "yellow"          -> stringResource(R.string.tag_val_yellow)
    "mustard"         -> stringResource(R.string.tag_val_mustard)
    "gold"            -> stringResource(R.string.tag_val_gold)
    "green"           -> stringResource(R.string.tag_val_green)
    "olive"           -> stringResource(R.string.tag_val_olive)
    "khaki"           -> stringResource(R.string.tag_val_khaki)
    "sage"            -> stringResource(R.string.tag_val_sage)
    "mint"            -> stringResource(R.string.tag_val_mint)
    "emerald"         -> stringResource(R.string.tag_val_emerald)
    "forest", "forest green" -> stringResource(R.string.tag_val_forest)
    "teal"            -> stringResource(R.string.tag_val_teal)
    "blue"            -> stringResource(R.string.tag_val_blue)
    "navy"            -> stringResource(R.string.tag_val_navy)
    "cobalt"          -> stringResource(R.string.tag_val_cobalt)
    "sky", "sky blue" -> stringResource(R.string.tag_val_sky)
    "denim blue"      -> stringResource(R.string.tag_val_denim_blue)
    "purple"          -> stringResource(R.string.tag_val_purple)
    "lavender"        -> stringResource(R.string.tag_val_lavender)
    "violet"          -> stringResource(R.string.tag_val_violet)
    "lilac"           -> stringResource(R.string.tag_val_lilac)
    "silver"          -> stringResource(R.string.tag_val_silver)
    "multicolor", "multi-color", "multicolour" -> stringResource(R.string.tag_val_multicolor)
    "printed"         -> stringResource(R.string.tag_val_printed)
    else              -> this
}

internal fun List<DriveImage>.tagCategories(): List<TagCategory> {
    fun collect(vararg lists: List<String>) = lists.flatMap { it }.toSortedSet().toList()
    return listOfNotNull(
        TagCategory("Type",        mapNotNull { it.tags?.type?.normalizeType() }.filter { it.isNotEmpty() }.toSortedSet().toList()).takeIf { it.tags.isNotEmpty() },
        TagCategory("Category",    mapNotNull { it.tags?.category?.lowercase()?.trim() }.filter { it.isNotEmpty() }.toSortedSet().toList()).takeIf { it.tags.isNotEmpty() },
        TagCategory("Uses",        collect(flatMap { it.tags?.uses?.map { v -> v.normalizeEnumTag() } ?: emptyList() })).takeIf { it.tags.isNotEmpty() },
        TagCategory("Colors",      collect(flatMap { it.tags?.colors?.map { v -> v.normalizeColor() } ?: emptyList() })).takeIf { it.tags.isNotEmpty() },
        TagCategory("Seasonality", collect(flatMap { it.tags?.seasonality?.map { v -> v.normalizeEnumTag() } ?: emptyList() })).takeIf { it.tags.isNotEmpty() },
        TagCategory("Aesthetic",   collect(flatMap { it.tags?.aesthetic?.map { v -> v.normalizeAesthetic() } ?: emptyList() })).takeIf { it.tags.isNotEmpty() },
        TagCategory("Fit",         collect(flatMap { it.tags?.fit?.map { v -> v.normalizeEnumTag() } ?: emptyList() })).takeIf { it.tags.isNotEmpty() },
        TagCategory("Material",    collect(flatMap { it.tags?.material?.map { v -> v.normalizeMaterial() } ?: emptyList() })).takeIf { it.tags.isNotEmpty() },
        TagCategory("Pattern",     collect(flatMap { it.tags?.pattern?.map { v -> v.normalizePattern() } ?: emptyList() })).takeIf { it.tags.isNotEmpty() },
    )
}

internal fun DriveImage.allTagStrings() = buildSet {
    tags?.normalize()?.let { t ->
        if (t.type.isNotEmpty()) add(t.type)
        if (t.category.isNotEmpty()) add(t.category)
        addAll(t.uses); addAll(t.colors); addAll(t.seasonality)
        addAll(t.aesthetic); addAll(t.fit); addAll(t.material); addAll(t.pattern)
    }
}

internal data class TagCount(val value: String, val count: Int)
internal data class TagCategoryCounts(val label: String, val counts: List<TagCount>)

internal fun List<DriveImage>.tagCategoryCounts(): List<TagCategoryCounts> {
    fun counted(values: List<String>): List<TagCount> =
        values.filter { it.isNotEmpty() }
            .groupingBy { it }.eachCount()
            .map { TagCount(it.key, it.value) }
            .sortedWith(compareByDescending<TagCount> { it.count }.thenBy { it.value })

    return listOfNotNull(
        counted(mapNotNull { it.tags?.type?.normalizeType() })
            .takeIf { it.isNotEmpty() }?.let { TagCategoryCounts("Type", it) },
        counted(mapNotNull { it.tags?.category?.lowercase()?.trim() })
            .takeIf { it.isNotEmpty() }?.let { TagCategoryCounts("Category", it) },
        counted(flatMap { it.tags?.uses?.map { v -> v.normalizeEnumTag() } ?: emptyList() })
            .takeIf { it.isNotEmpty() }?.let { TagCategoryCounts("Uses", it) },
        counted(flatMap { it.tags?.colors?.map { v -> v.normalizeColor() } ?: emptyList() })
            .takeIf { it.isNotEmpty() }?.let { TagCategoryCounts("Colors", it) },
        counted(flatMap { it.tags?.seasonality?.map { v -> v.normalizeEnumTag() } ?: emptyList() })
            .takeIf { it.isNotEmpty() }?.let { TagCategoryCounts("Seasonality", it) },
        counted(flatMap { it.tags?.aesthetic?.map { v -> v.normalizeAesthetic() } ?: emptyList() })
            .takeIf { it.isNotEmpty() }?.let { TagCategoryCounts("Aesthetic", it) },
        counted(flatMap { it.tags?.fit?.map { v -> v.normalizeEnumTag() } ?: emptyList() })
            .takeIf { it.isNotEmpty() }?.let { TagCategoryCounts("Fit", it) },
        counted(flatMap { it.tags?.material?.map { v -> v.normalizeMaterial() } ?: emptyList() })
            .takeIf { it.isNotEmpty() }?.let { TagCategoryCounts("Material", it) },
        counted(flatMap { it.tags?.pattern?.map { v -> v.normalizePattern() } ?: emptyList() })
            .takeIf { it.isNotEmpty() }?.let { TagCategoryCounts("Pattern", it) },
    )
}

internal fun DriveImage.tagStringsForCategory(categoryLabel: String): Set<String> {
    val t = tags?.normalize() ?: return emptySet()
    return when (categoryLabel) {
        "Type"        -> if (t.type.isNotEmpty()) setOf(t.type) else emptySet()
        "Category"    -> if (t.category.isNotEmpty()) setOf(t.category) else emptySet()
        "Uses"        -> t.uses.toSet()
        "Colors"      -> t.colors.toSet()
        "Seasonality" -> t.seasonality.toSet()
        "Aesthetic"   -> t.aesthetic.toSet()
        "Fit"         -> t.fit.toSet()
        "Material"    -> t.material.toSet()
        "Pattern"     -> t.pattern.toSet()
        else          -> emptySet()
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun TagFilterBar(
    tagCategories: List<TagCategory>,
    selectedTags: Map<String, Set<String>>,
    onTagsChanged: (Map<String, Set<String>>) -> Unit,
    modifier: Modifier = Modifier,
) {
    var expandedCategory by remember { mutableStateOf<String?>(null) }
    if (tagCategories.isEmpty()) return
    LazyRow(
        modifier = modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 4.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        items(tagCategories) { category ->
            val catSelected = selectedTags[category.label] ?: emptySet()
            val activeCount = catSelected.size
            Box {
                FilterChip(
                    selected = activeCount > 0,
                    onClick = {
                        expandedCategory = if (expandedCategory == category.label) null else category.label
                    },
                    label = { val displayLabel = tagCategoryDisplayLabel(category.label); Text(if (activeCount > 0) "$displayLabel ($activeCount)" else displayLabel) },
                    trailingIcon = { Icon(Icons.Default.ArrowDropDown, null, Modifier.size(16.dp)) },
                )
                DropdownMenu(
                    expanded = expandedCategory == category.label,
                    onDismissRequest = { expandedCategory = null },
                ) {
                    category.tags.forEach { tag ->
                        val checked = tag in catSelected
                        DropdownMenuItem(
                            text = {
                                Row(
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                                ) {
                                    if (checked) Icon(Icons.Default.Check, null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(18.dp))
                                    else Spacer(Modifier.size(18.dp))
                                    Text(tag.localizedTagValue())
                                }
                            },
                            onClick = {
                                val updated = if (checked) catSelected - tag else catSelected + tag
                                onTagsChanged(selectedTags + (category.label to updated))
                            },
                        )
                    }
                }
            }
        }
        if (selectedTags.values.any { it.isNotEmpty() }) {
            item { TextButton(onClick = { onTagsChanged(emptyMap()) }) { Text(stringResource(R.string.action_clear)) } }
        }
    }
}

// ---------- Grid ----------

@OptIn(ExperimentalMaterial3Api::class, ExperimentalFoundationApi::class)
@Composable
private fun GridContent(
    state: WardrobeUiState,
    popularityMap: Map<String, Int> = emptyMap(),
    locations: List<Location> = emptyList(),
    activeLocationId: String = "",
    isLocationLoading: Boolean = false,
    locationError: String? = null,
    onOpenCamera: () -> Unit,
    onOpenGallery: () -> Unit,
    onImportUrl: (String) -> Unit = {},
    onDismissError: () -> Unit,
    onTagImage: (String) -> Unit,
    onRemoveBackground: (String) -> Unit,
    onRotateImage: (String) -> Unit,
    onUpdateTags: (String, ClothingTags) -> Unit,
    onToggleSelection: (String) -> Unit,
    onSelectAll: (List<String>) -> Unit,
    onClearSelection: () -> Unit,
    onDeleteSelected: () -> Unit,
    onDeleteItem: (String) -> Unit,
    onMoveToLocation: (Set<String>, String) -> Unit,
    onSetActiveLocation: (String) -> Unit,
    onCreateOutfitFromSelection: (Set<String>) -> Unit,
    onTryOnSelection: (Set<String>) -> Unit = {},
    canTryOn: Boolean = false,
    onDismissBatteryExemption: () -> Unit = {},
    onSetImportTarget: (String) -> Unit = {},
    processingImageId: String?,
    dismissViewerTrigger: Int = 0,
    onSettingsClick: () -> Unit = {},
    onOpenFindByPhoto: () -> Unit = {},
    onDismissFindByPhoto: () -> Unit = {},
    onConsumePendingScroll: () -> Unit = {},
    onAddMatchToShoppingList: (String) -> Unit = {},
    debugSimilarityPreview: Boolean = false,
    modifier: Modifier = Modifier,
) {
    val isOffline = LocalIsOffline.current
    var cellSizeDp by rememberSaveable { mutableFloatStateOf(120f) }
    var pinchVisualScale by remember { mutableFloatStateOf(1f) }
    var selectedIndex by remember { mutableStateOf<Int?>(null) }
    var showDeleteDialog by remember { mutableStateOf(false) }
    var showMoveDialog by remember { mutableStateOf(false) }
    // Filter + sort state
    var selectedTags by remember { mutableStateOf(emptyMap<String, Set<String>>()) }
    var sortBy by remember { mutableStateOf(SortOption.DATE_DESC) }

    val tagCategories = remember(state.images) { state.images.tagCategories() }

    // Close the viewer when the wardrobe nav tab is re-tapped from the nav bar.
    LaunchedEffect(dismissViewerTrigger) { if (dismissViewerTrigger > 0) selectedIndex = null }

    // OR within each category, AND across categories
    val filteredImages = remember(state.images, selectedTags) {
        val activeFilters = selectedTags.filter { (_, tags) -> tags.isNotEmpty() }
        if (activeFilters.isEmpty()) state.images
        else state.images.filter { img ->
            activeFilters.all { (categoryLabel, catTags) ->
                catTags.any { it in img.tagStringsForCategory(categoryLabel) }
            }
        }
    }

    val displayedImages = remember(filteredImages, sortBy, popularityMap) {
        when (sortBy) {
            SortOption.DATE_DESC  -> filteredImages
            SortOption.DATE_ASC   -> filteredImages.reversed()
            SortOption.POPULARITY -> filteredImages.sortedByDescending { popularityMap[it.driveId] ?: 0 }
            SortOption.TYPE       -> filteredImages.sortedBy { it.tags?.type?.lowercase() ?: "" }
            SortOption.CATEGORY   -> filteredImages.sortedBy { it.tags?.category?.lowercase() ?: "" }
        }
    }

    // Clear viewer when filter/sort changes to avoid stale index
    LaunchedEffect(selectedTags, sortBy) { selectedIndex = null }

    // After find-by-photo (or "Show in wardrobe" from Similarity Finder): scroll the grid to
    // the matched item and pulse a highlight ring on it. The local var seeds either from a
    // viewer-driven request (find-by-photo's onPickMatch) or from the VM-owned state.
    val gridState = androidx.compose.foundation.lazy.grid.rememberLazyGridState()
    var pendingScrollDriveId by remember { mutableStateOf<String?>(null) }
    var highlightedDriveId by remember { mutableStateOf<String?>(null) }
    LaunchedEffect(state.pendingScrollDriveId) {
        val external = state.pendingScrollDriveId ?: return@LaunchedEffect
        pendingScrollDriveId = external
        onConsumePendingScroll()
    }
    LaunchedEffect(pendingScrollDriveId, displayedImages) {
        val target = pendingScrollDriveId ?: return@LaunchedEffect
        val idx = displayedImages.indexOfFirst { it.driveId == target }
        if (idx >= 0) {
            runCatching { gridState.animateScrollToItem(idx) }
            highlightedDriveId = target
            pendingScrollDriveId = null
            kotlinx.coroutines.delay(2000)
            if (highlightedDriveId == target) highlightedDriveId = null
        }
    }

    val isSelectionMode = state.selectedIds.isNotEmpty()
    if (isSelectionMode) BackHandler(onBack = onClearSelection)

    Box(modifier = modifier.fillMaxSize()) {
        Column(Modifier.fillMaxSize()) {
            // ---- Screen header with sort button ----
            AppScreenHeader(
                title = stringResource(R.string.nav_wardrobe),
                trailingContent = {
                    LocationButton(
                        locations = locations,
                        activeLocationId = activeLocationId,
                        onSetActiveLocation = onSetActiveLocation,
                    )
                    IconButton(onClick = onOpenFindByPhoto) {
                        Icon(
                            Icons.Default.ImageSearch,
                            contentDescription = stringResource(R.string.wardrobe_find_by_photo),
                        )
                    }
                    SortButton(
                        sortBy = sortBy,
                        onSortChanged = { sortBy = it },
                        modifier = Modifier.padding(end = 4.dp),
                    )
                },
                onSettingsClick = onSettingsClick,
            )
            // ---- Tag filter chips ----
            TagFilterBar(
                tagCategories = tagCategories,
                selectedTags = selectedTags,
                onTagsChanged = { selectedTags = it },
            )

            // ---- Filtered item count ----
            if (state.images.isNotEmpty() && !isSelectionMode) {
                val hasFilter = selectedTags.values.any { it.isNotEmpty() }
                Text(
                    text = if (hasFilter) {
                        stringResource(R.string.wardrobe_filtered_count, displayedImages.size, state.images.size)
                    } else {
                        stringResource(R.string.wardrobe_item_count, state.images.size)
                    },
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(horizontal = 12.dp, vertical = 2.dp),
                )
            }

            // ---- Selection bar (shown when at least one item is selected) ----
            if (isSelectionMode) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 12.dp, vertical = 2.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        stringResource(R.string.wardrobe_selected_count, state.selectedIds.size),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.weight(1f),
                    )
                    if (displayedImages.any { it.driveId !in state.selectedIds }) {
                        TextButton(
                            onClick = { onSelectAll(displayedImages.map { it.driveId }) },
                            contentPadding = PaddingValues(horizontal = 8.dp, vertical = 0.dp),
                        ) {
                            Text(stringResource(R.string.wardrobe_select_all_count, displayedImages.size))
                        }
                    }
                    TextButton(
                        onClick = onClearSelection,
                        contentPadding = PaddingValues(horizontal = 8.dp, vertical = 0.dp),
                    ) {
                        Text(stringResource(R.string.action_deselect_all))
                    }
                }
            }

            // ---- Sync progress bar (Phase 2 downloading, even when cached items visible) ----
            if (state.syncTotal > 0) {
                Column(Modifier.fillMaxWidth()) {
                    LinearProgressIndicator(
                        progress = { if (state.syncTotal > 0) state.syncDone.toFloat() / state.syncTotal else 0f },
                        modifier = Modifier.fillMaxWidth(),
                    )
                    Text(
                        stringResource(R.string.wardrobe_sync_progress, state.syncDone, state.syncTotal),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(horizontal = 12.dp, vertical = 2.dp),
                    )
                }
            }

            // ---- Main content ----
            when {
                state.isLoading && state.syncTotal == 0 -> {
                    // Phase 1: reading local cache (very fast) or no network — show spinner
                    Box(Modifier.weight(1f).fillMaxWidth(), contentAlignment = Alignment.Center) {
                        CircularProgressIndicator()
                    }
                }
                state.isLoading && state.syncTotal > 0 -> {
                    // Phase 2 with empty cache: progress bar above is already visible; fill the rest
                    Box(Modifier.weight(1f).fillMaxWidth())
                }
                isLocationLoading && state.images.isEmpty() -> {
                    // Locations not yet loaded from Drive (first launch after install/reinstall)
                    Box(Modifier.weight(1f).fillMaxWidth(), contentAlignment = Alignment.Center) {
                        CircularProgressIndicator()
                    }
                }
                locationError != null && state.images.isEmpty() -> {
                    Box(Modifier.weight(1f).fillMaxWidth(), contentAlignment = Alignment.Center) {
                        Text(
                            text = locationError,
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.error,
                        )
                    }
                }
                displayedImages.isEmpty() && !state.isProcessing && !state.isUploading -> {
                    Box(Modifier.weight(1f).fillMaxWidth(), contentAlignment = Alignment.Center) {
                        Column(
                            horizontalAlignment = Alignment.CenterHorizontally,
                            verticalArrangement = Arrangement.Center,
                        ) {
                            if (selectedTags.values.any { it.isNotEmpty() }) {
                                Text(stringResource(R.string.wardrobe_empty_filter), style = MaterialTheme.typography.bodyLarge)
                            } else {
                                Text(stringResource(R.string.wardrobe_empty), style = MaterialTheme.typography.bodyLarge)
                                Spacer(Modifier.height(4.dp))
                                Text(
                                    stringResource(R.string.wardrobe_empty_hint),
                                    style = MaterialTheme.typography.bodyMedium,
                                )
                            }
                        }
                    }
                }
                else -> {
                    Box(
                        modifier = Modifier
                            .weight(1f)
                            .fillMaxWidth()
                            .pointerInput(Unit) {
                                awaitEachGesture {
                                    awaitFirstDown(requireUnconsumed = false, pass = PointerEventPass.Initial)
                                    var prevDistance = -1f
                                    do {
                                        val event = awaitPointerEvent(PointerEventPass.Initial)
                                        val pressed = event.changes.filter { it.pressed }
                                        if (pressed.size >= 2) {
                                            val dist = (pressed[1].position - pressed[0].position).getDistance()
                                            if (prevDistance > 0f) pinchVisualScale *= (dist / prevDistance)
                                            prevDistance = dist
                                            pressed.forEach { it.consume() }
                                        } else prevDistance = -1f
                                    } while (event.changes.any { it.pressed })
                                    cellSizeDp = (cellSizeDp * pinchVisualScale).coerceIn(56f, 320f)
                                    pinchVisualScale = 1f
                                }
                            },
                    ) {
                        LazyVerticalGrid(
                            state = gridState,
                            columns = GridCells.Adaptive(cellSizeDp.dp),
                            modifier = Modifier.fillMaxSize().graphicsLayer {
                                clip = false
                                scaleX = pinchVisualScale
                                scaleY = pinchVisualScale
                            },
                        ) {
                            itemsIndexed(displayedImages, key = { _, img -> img.driveId }) { index, image ->
                                val isSelected = state.selectedIds.contains(image.driveId)
                                val isHighlighted = image.driveId == highlightedDriveId
                                val ctx = LocalContext.current
                                // Show the item's actual location whenever multiple locations exist
                                val itemLocationName = if (locations.size > 1) {
                                    remember(image.folderId, locations) {
                                        locations.find { it.folderId == image.folderId }?.name
                                    }
                                } else null
                                Column(
                                    modifier = Modifier
                                        .padding(1.dp)
                                        .combinedClickable(
                                            onClick = {
                                                if (isSelectionMode) onToggleSelection(image.driveId)
                                                else selectedIndex = index
                                            },
                                            onLongClick = { onToggleSelection(image.driveId) },
                                        ),
                                ) {
                                    Box(
                                        modifier = Modifier
                                            .aspectRatio(1f)
                                            .then(
                                                if (isHighlighted) Modifier.border(
                                                    width = 3.dp,
                                                    color = MaterialTheme.colorScheme.primary,
                                                ) else Modifier,
                                            ),
                                    ) {
                                        AsyncImage(
                                            model = remember(image.driveId, image.version) {
                                                ImageRequest.Builder(ctx)
                                                    .data(image.localPath)
                                                    .memoryCacheKey("${image.driveId}_${image.version}")
                                                    .build()
                                            },
                                            contentDescription = image.tags?.label?.ifEmpty { image.name } ?: image.name,
                                            modifier = Modifier.fillMaxSize(),
                                            contentScale = ContentScale.Crop,
                                        )
                                        // Location badge — top-right, only in "All locations" mode
                                        if (itemLocationName != null) {
                                            Box(
                                                modifier = Modifier
                                                    .align(Alignment.TopEnd)
                                                    .padding(3.dp)
                                                    .background(
                                                        color = Color.Black.copy(alpha = 0.45f),
                                                        shape = MaterialTheme.shapes.extraSmall,
                                                    )
                                                    .padding(horizontal = 4.dp, vertical = 1.dp),
                                            ) {
                                                Text(
                                                    text = itemLocationName,
                                                    color = Color.White,
                                                    fontSize = 8.sp,
                                                    lineHeight = 10.sp,
                                                    maxLines = 1,
                                                    overflow = TextOverflow.Ellipsis,
                                                )
                                            }
                                        }
                                        if (isSelected) {
                                            Box(
                                                modifier = Modifier
                                                    .fillMaxSize()
                                                    .background(Color.White.copy(alpha = 0.4f)),
                                                contentAlignment = Alignment.TopEnd,
                                            ) {
                                                Icon(
                                                    imageVector = Icons.Default.CheckCircle,
                                                    contentDescription = null,
                                                    tint = MaterialTheme.colorScheme.primary,
                                                    modifier = Modifier.padding(4.dp),
                                                )
                                            }
                                        }
                                    }
                                    val itemLabel = image.tags?.label?.ifEmpty { null }
                                    if (itemLabel != null) {
                                        Text(
                                            text = itemLabel,
                                            style = MaterialTheme.typography.labelSmall,
                                            maxLines = 1,
                                            overflow = TextOverflow.Ellipsis,
                                            modifier = Modifier
                                                .fillMaxWidth()
                                                .padding(horizontal = 4.dp, vertical = 2.dp),
                                        )
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }

        // Progress pill
        val retagLabel = if (state.isRetagging) stringResource(R.string.settings_rescanning, state.retagDone + 1, state.retagTotal) else null
        val overlayLabel = when {
            state.isRetagging -> retagLabel
            state.isMoving -> stringResource(R.string.wardrobe_progress_moving)
            state.isProcessing && state.batchTotal > 1 ->
                stringResource(R.string.wardrobe_progress_removing_bg_batch, state.batchDone + 1, state.batchTotal)
            state.isUploading && state.batchTotal > 1 ->
                stringResource(R.string.wardrobe_progress_uploading_batch, state.batchDone + 1, state.batchTotal)
            state.isProcessing -> stringResource(R.string.wardrobe_progress_removing_bg)
            state.isUploading  -> stringResource(R.string.wardrobe_progress_uploading)
            state.pendingJobs > 0 -> stringResource(R.string.wardrobe_processing_photos, state.pendingJobs)
            else -> null
        }
        if (overlayLabel != null) {
            Surface(
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .padding(bottom = 88.dp),
                shape = MaterialTheme.shapes.medium,
                tonalElevation = 4.dp,
            ) {
                Row(
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 10.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    CircularProgressIndicator(modifier = Modifier.size(18.dp), strokeWidth = 2.dp)
                    Text(overlayLabel, style = MaterialTheme.typography.bodyMedium)
                }
            }
        }

        // Speed-dial FAB
        if (isSelectionMode) {
            Column(
                modifier = Modifier.align(Alignment.BottomEnd).padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
                horizontalAlignment = Alignment.End,
            ) {
                if (!isOffline) {
                    ExtendedFloatingActionButton(
                        onClick = { onCreateOutfitFromSelection(state.selectedIds) },
                        containerColor = MaterialTheme.colorScheme.primaryContainer,
                        contentColor = MaterialTheme.colorScheme.onPrimaryContainer,
                        icon = { Icon(Icons.Default.AutoFixHigh, contentDescription = null) },
                        text = { Text(stringResource(R.string.wardrobe_create_style)) },
                    )
                    if (canTryOn) {
                        ExtendedFloatingActionButton(
                            onClick = { onTryOnSelection(state.selectedIds) },
                            containerColor = MaterialTheme.colorScheme.primaryContainer,
                            contentColor = MaterialTheme.colorScheme.onPrimaryContainer,
                            icon = { Icon(Icons.Default.AutoAwesome, contentDescription = null) },
                            text = { Text(stringResource(R.string.tryon_fab)) },
                        )
                    }
                }
                if (locations.size > 1 && !isOffline) {
                    ExtendedFloatingActionButton(
                        onClick = { showMoveDialog = true },
                        containerColor = MaterialTheme.colorScheme.secondaryContainer,
                        contentColor = MaterialTheme.colorScheme.onSecondaryContainer,
                        icon = { Icon(Icons.Default.Place, contentDescription = null) },
                        text = { Text(stringResource(R.string.wardrobe_move_to)) },
                    )
                }
                if (!isOffline) {
                    ExtendedFloatingActionButton(
                        onClick = { showDeleteDialog = true },
                        containerColor = MaterialTheme.colorScheme.error,
                        contentColor = MaterialTheme.colorScheme.onError,
                        icon = { Icon(Icons.Default.Delete, contentDescription = null) },
                        text = { Text(stringResource(R.string.action_delete)) },
                    )
                }
            }
        } else if (!isOffline) {
            // Show closet picker before gallery when 2+ closets exist
            var showGalleryClosetPicker by remember { mutableStateOf(false) }
            var showUrlImportDialog by remember { mutableStateOf(false) }

            Column(
                modifier = Modifier.align(Alignment.BottomEnd).padding(16.dp),
                horizontalAlignment = Alignment.End,
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                FloatingActionButton(onClick = { showUrlImportDialog = true }) {
                    Icon(Icons.Default.Add, contentDescription = null)
                    Icon(Icons.Default.Link, contentDescription = stringResource(R.string.wardrobe_add_url))
                }
                FloatingActionButton(onClick = {
                    if (locations.size >= 2) showGalleryClosetPicker = true else onOpenGallery()
                }) {
                    Icon(Icons.Default.Add, contentDescription = null)
                    Icon(Icons.Default.PhotoLibrary, contentDescription = stringResource(R.string.wardrobe_add_gallery))
                }
                FloatingActionButton(onClick = onOpenCamera) {
                    Icon(Icons.Default.Add, contentDescription = null)
                    Icon(Icons.Default.CameraAlt, contentDescription = stringResource(R.string.wardrobe_add_camera))
                }
            }

            if (showUrlImportDialog) {
                UrlImportDialog(
                    locations = locations,
                    initialFolderId = state.importTargetFolderId ?: locations.firstOrNull()?.folderId,
                    onSubmit = { url, folderId ->
                        folderId?.let { onSetImportTarget(it) }
                        showUrlImportDialog = false
                        onImportUrl(url)
                    },
                    onDismiss = { showUrlImportDialog = false },
                )
            }

            if (showGalleryClosetPicker) {
                val initialFolderId = state.importTargetFolderId ?: locations.firstOrNull()?.folderId
                var selectedFolderId by remember { mutableStateOf(initialFolderId) }
                AlertDialog(
                    onDismissRequest = { showGalleryClosetPicker = false },
                    title = { Text(stringResource(R.string.wardrobe_add_to_closet_title)) },
                    text = {
                        Column {
                            locations.sortedBy { it.name }.forEach { loc ->
                                val selected = loc.folderId == selectedFolderId
                                Row(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .clickable { selectedFolderId = loc.folderId }
                                        .padding(vertical = 10.dp, horizontal = 4.dp),
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                                ) {
                                    RadioButton(selected = selected, onClick = { selectedFolderId = loc.folderId })
                                    Text(loc.name, style = MaterialTheme.typography.bodyLarge)
                                }
                            }
                        }
                    },
                    confirmButton = {
                        TextButton(onClick = {
                            selectedFolderId?.let { onSetImportTarget(it) }
                            showGalleryClosetPicker = false
                            onOpenGallery()
                        }) { Text(stringResource(R.string.action_continue)) }
                    },
                    dismissButton = {
                        TextButton(onClick = { showGalleryClosetPicker = false }) {
                            Text(stringResource(R.string.action_cancel))
                        }
                    },
                )
            }
        }

        state.error?.let { msg ->
            Snackbar(
                modifier = Modifier
                    .align(Alignment.BottomStart)
                    .padding(start = 8.dp, end = 96.dp, bottom = 8.dp),
                action = { TextButton(onClick = onDismissError) { Text(stringResource(R.string.action_dismiss)) } },
            ) { Text(msg) }
        }

        // Full-screen viewer rendered as the last (topmost) child of the padded Box so that
        // the Scaffold's bottomBar insets are already consumed — the FAB at BottomEnd appears
        // above the NavigationBar without any extra manual inset arithmetic.
        selectedIndex?.let { startIndex ->
            FullScreenViewer(
                images = displayedImages,
                initialIndex = startIndex.coerceIn(0, (displayedImages.size - 1).coerceAtLeast(0)),
                allTagCategories = tagCategories,
                onDismiss = { selectedIndex = null },
                onTagImage = onTagImage,
                onRemoveBackground = onRemoveBackground,
                onRotateImage = onRotateImage,
                onUpdateTags = onUpdateTags,
                onDeleteItem = { driveId ->
                    onDeleteItem(driveId)
                    if (displayedImages.size <= 1) selectedIndex = null
                },
                onMoveToLocation = { ids, folderId ->
                    onMoveToLocation(ids, folderId)
                    if (displayedImages.size <= 1) selectedIndex = null
                },
                onCreateOutfitFromSelection = onCreateOutfitFromSelection,
                locations = locations,
                activeLocationId = activeLocationId,
                processingImageId = processingImageId,
            )
        }
    }

    if (showDeleteDialog) {
        AlertDialog(
            onDismissRequest = { showDeleteDialog = false },
            title = { Text(stringResource(R.string.wardrobe_delete_title)) },
            text = { Text(stringResource(R.string.wardrobe_delete_text, state.selectedIds.size)) },
            confirmButton = {
                TextButton(
                    onClick = {
                        onDeleteSelected()
                        showDeleteDialog = false
                    }
                ) {
                    Text(stringResource(R.string.action_delete), color = MaterialTheme.colorScheme.error)
                }
            },
            dismissButton = {
                TextButton(onClick = { showDeleteDialog = false }) {
                    Text(stringResource(R.string.action_cancel))
                }
            }
        )
    }

    if (state.needsBatteryExemption) {
        val batteryContext = LocalContext.current
        AlertDialog(
            onDismissRequest = onDismissBatteryExemption,
            title = { Text(stringResource(R.string.battery_exempt_title)) },
            text = { Text(stringResource(R.string.battery_exempt_text)) },
            confirmButton = {
                TextButton(
                    onClick = {
                        onDismissBatteryExemption()
                        fun launchIntent(vararg intents: Intent) {
                            for (intent in intents) {
                                try {
                                    batteryContext.startActivity(intent)
                                    return
                                } catch (_: Exception) { }
                            }
                        }
                        launchIntent(
                            Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS).apply {
                                data = Uri.parse("package:${batteryContext.packageName}")
                                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                            },
                            Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS).apply {
                                data = Uri.parse("package:${batteryContext.packageName}")
                                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                            },
                            Intent(Settings.ACTION_SETTINGS).apply {
                                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                            },
                        )
                    }
                ) { Text(stringResource(R.string.battery_exempt_action)) }
            },
            dismissButton = {
                TextButton(onClick = onDismissBatteryExemption) {
                    Text(stringResource(R.string.action_cancel))
                }
            },
        )
    }

    if (showMoveDialog) {
        val otherLocations = locations.filter { it.folderId != activeLocationId }
        AlertDialog(
            onDismissRequest = { showMoveDialog = false },
            title = { Text(stringResource(R.string.wardrobe_move_to_title, state.selectedIds.size)) },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    otherLocations.forEach { location ->
                        Surface(
                            shape = MaterialTheme.shapes.small,
                            tonalElevation = 1.dp,
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable {
                                    onMoveToLocation(state.selectedIds, location.folderId)
                                    showMoveDialog = false
                                },
                        ) {
                            Row(
                                modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(8.dp),
                            ) {
                                Icon(Icons.Default.Place, contentDescription = null, modifier = Modifier.size(18.dp))
                                Text(location.name, style = MaterialTheme.typography.bodyMedium)
                            }
                        }
                    }
                }
            },
            confirmButton = {},
            dismissButton = {
                TextButton(onClick = { showMoveDialog = false }) {
                    Text(stringResource(R.string.action_cancel))
                }
            },
        )
    }

    state.findByPhoto?.let { fbp ->
        FindByPhotoResultsSheet(
            findByPhoto = fbp,
            debugSimilarityPreview = debugSimilarityPreview,
            onPickMatch = { image ->
                selectedTags = emptyMap()
                // If a single closet is active and the match lives in a different one, switch
                // closets so the grid will contain the item once it reloads. In All Locations
                // mode the grid already shows every closet — leave the filter alone. The
                // pending-scroll LaunchedEffect retries when [displayedImages] updates, so the
                // highlight lands on the right tile either way.
                val matchFolder = image.folderId
                val viewingAll = activeLocationId == LocationViewModel.ALL_LOCATIONS_ID
                if (!viewingAll && matchFolder.isNotEmpty() && matchFolder != activeLocationId) {
                    onSetActiveLocation(matchFolder)
                }
                pendingScrollDriveId = image.driveId
                onDismissFindByPhoto()
            },
            onAddToShoppingList = { queryPath ->
                onAddMatchToShoppingList(queryPath)
                onDismissFindByPhoto()
            },
            onDismiss = onDismissFindByPhoto,
        )
    }

}

/**
 * URL import dialog: paste a shopping URL, optionally pick the target closet (when 2+ closets
 * exist). Submit only enables once a non-blank URL is entered.
 */
@Composable
private fun UrlImportDialog(
    locations: List<Location>,
    initialFolderId: String?,
    onSubmit: (url: String, folderId: String?) -> Unit,
    onDismiss: () -> Unit,
) {
    var url by remember { mutableStateOf("") }
    var selectedFolderId by remember { mutableStateOf(initialFolderId) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.wardrobe_url_import_title)) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(
                    stringResource(R.string.wardrobe_url_import_hint),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                OutlinedTextField(
                    value = url,
                    onValueChange = { url = it },
                    placeholder = { Text("https://…") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
                if (locations.size >= 2) {
                    Spacer(Modifier.height(4.dp))
                    Text(
                        stringResource(R.string.wardrobe_add_to_closet_title),
                        style = MaterialTheme.typography.labelLarge,
                    )
                    locations.sortedBy { it.name }.forEach { loc ->
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable { selectedFolderId = loc.folderId }
                                .padding(vertical = 6.dp, horizontal = 4.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(12.dp),
                        ) {
                            RadioButton(
                                selected = loc.folderId == selectedFolderId,
                                onClick = { selectedFolderId = loc.folderId },
                            )
                            Text(loc.name, style = MaterialTheme.typography.bodyMedium)
                        }
                    }
                }
            }
        },
        confirmButton = {
            TextButton(
                enabled = url.isNotBlank(),
                onClick = { onSubmit(url.trim(), selectedFolderId) },
            ) { Text(stringResource(R.string.action_continue)) }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text(stringResource(R.string.action_cancel)) }
        },
    )
}

// ---------- Full-screen image viewer ----------

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun FullScreenViewer(
    images: List<DriveImage>,
    initialIndex: Int,
    allTagCategories: List<TagCategory>,
    onDismiss: () -> Unit,
    onTagImage: (String) -> Unit,
    onRemoveBackground: (String) -> Unit,
    onRotateImage: (String) -> Unit,
    onUpdateTags: (String, ClothingTags) -> Unit,
    onDeleteItem: (String) -> Unit,
    onMoveToLocation: (Set<String>, String) -> Unit,
    onCreateOutfitFromSelection: (Set<String>) -> Unit,
    locations: List<Location>,
    activeLocationId: String,
    processingImageId: String?,
) {
    BackHandler(onBack = onDismiss)

    val isOffline = LocalIsOffline.current
    var pageScale by remember { mutableFloatStateOf(1f) }
    val pagerState = rememberPagerState(
        initialPage = initialIndex,
        pageCount = { images.size },
    )
    LaunchedEffect(pagerState.currentPage) { pageScale = 1f }

    var showTagEdit by remember { mutableStateOf(false) }
    var showItemActions by remember { mutableStateOf(false) }
    var showMoveDialog by remember { mutableStateOf(false) }
    var showDeleteDialog by remember { mutableStateOf(false) }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Color.Black),
    ) {
        val haptic = LocalHapticFeedback.current
        HorizontalPager(
            state = pagerState,
            userScrollEnabled = pageScale <= 1.01f,
            modifier = Modifier.fillMaxSize(),
        ) { page ->
            ZoomableImage(
                localPath = images[page].localPath,
                name = images[page].name,
                cacheKey = "${images[page].driveId}_${images[page].version}",
                onScaleChanged = { s -> if (page == pagerState.currentPage) pageScale = s },
                onLongPress = {
                    if (!isOffline) {
                        haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                        showItemActions = true
                    }
                },
            )
        }

        Text(
            text = "${pagerState.currentPage + 1} / ${images.size}",
            color = Color.White,
            style = MaterialTheme.typography.labelMedium,
            modifier = Modifier.align(Alignment.TopCenter).statusBarsPadding().padding(top = 12.dp),
        )

        val currentImage = images[pagerState.currentPage]
        if (currentImage.driveId == processingImageId) {
            AiProcessingOverlay(modifier = Modifier.fillMaxSize())
        }

        TagsOverlay(
            tags = currentImage.tags,
            hasOriginal = currentImage.originalDriveId != null,
            onTagImage = { onTagImage(currentImage.driveId) },
            onRemoveBackground = { onRemoveBackground(currentImage.driveId) },
            onEditTags = { showTagEdit = true },
            modifier = Modifier
                .align(Alignment.TopEnd)
                .statusBarsPadding()
                .padding(top = 8.dp, end = 8.dp),
        )

        // Close button — second-to-last so rotate is the topmost element.
        IconButton(
            onClick = onDismiss,
            modifier = Modifier.align(Alignment.TopStart).statusBarsPadding().padding(8.dp),
        ) {
            Icon(Icons.Default.Close, contentDescription = "Close", tint = Color.White)
        }

        // Rotate button — LAST child = highest Z-order. Explicit white/black colours so it is
        // always visible against the black viewer background regardless of dynamic theming.
        if (!isOffline) {
            SmallFloatingActionButton(
                onClick = { onRotateImage(currentImage.driveId) },
                modifier = Modifier
                    .align(Alignment.BottomEnd)
                    .padding(16.dp),
                containerColor = Color.White,
                contentColor = Color.Black,
            ) {
                Icon(
                    Icons.AutoMirrored.Filled.RotateRight,
                    contentDescription = stringResource(R.string.wardrobe_tag_rotate),
                )
            }
        }
    }

    if (showTagEdit) {
        val currentImage = images[pagerState.currentPage]
        TagEditSheet(
            image = currentImage,
            allTagCategories = allTagCategories,
            onSave = { tags ->
                onUpdateTags(currentImage.driveId, tags)
                showTagEdit = false
            },
            onDismiss = { showTagEdit = false },
        )
    }

    if (showItemActions) {
        val currentImage = images[pagerState.currentPage]
        val otherLocations = locations.filter { it.folderId != activeLocationId }
        ModalBottomSheet(
            onDismissRequest = { showItemActions = false },
        ) {
            Column(modifier = Modifier.navigationBarsPadding()) {
                val label = currentImage.tags?.label ?: currentImage.name
                Text(
                    text = label,
                    style = MaterialTheme.typography.titleSmall,
                    modifier = Modifier.padding(start = 16.dp, end = 16.dp, bottom = 8.dp),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                HorizontalDivider()
                Surface(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable {
                            showItemActions = false
                            onCreateOutfitFromSelection(setOf(currentImage.driveId))
                        },
                ) {
                    Row(
                        modifier = Modifier.padding(horizontal = 16.dp, vertical = 14.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(12.dp),
                    ) {
                        Icon(Icons.Default.AutoFixHigh, contentDescription = null, modifier = Modifier.size(20.dp))
                        Text(stringResource(R.string.wardrobe_create_style), style = MaterialTheme.typography.bodyLarge)
                    }
                }
                if (otherLocations.isNotEmpty()) {
                    Surface(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable {
                                showItemActions = false
                                showMoveDialog = true
                            },
                    ) {
                        Row(
                            modifier = Modifier.padding(horizontal = 16.dp, vertical = 14.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(12.dp),
                        ) {
                            Icon(Icons.Default.Place, contentDescription = null, modifier = Modifier.size(20.dp))
                            Text(stringResource(R.string.wardrobe_move_to), style = MaterialTheme.typography.bodyLarge)
                        }
                    }
                }
                Surface(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable {
                            showItemActions = false
                            showDeleteDialog = true
                        },
                ) {
                    Row(
                        modifier = Modifier.padding(horizontal = 16.dp, vertical = 14.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(12.dp),
                    ) {
                        Icon(Icons.Default.Delete, contentDescription = null, tint = MaterialTheme.colorScheme.error, modifier = Modifier.size(20.dp))
                        Text(stringResource(R.string.action_delete), style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.error)
                    }
                }
                Spacer(Modifier.height(8.dp))
            }
        }
    }

    if (showDeleteDialog) {
        val currentImage = images[pagerState.currentPage]
        AlertDialog(
            onDismissRequest = { showDeleteDialog = false },
            title = { Text(stringResource(R.string.wardrobe_delete_title)) },
            text = { Text(stringResource(R.string.wardrobe_delete_text, 1)) },
            confirmButton = {
                TextButton(onClick = {
                    onDeleteItem(currentImage.driveId)
                    showDeleteDialog = false
                    if (images.size <= 1) onDismiss()
                }) {
                    Text(stringResource(R.string.action_delete), color = MaterialTheme.colorScheme.error)
                }
            },
            dismissButton = {
                TextButton(onClick = { showDeleteDialog = false }) {
                    Text(stringResource(R.string.action_cancel))
                }
            },
        )
    }

    if (showMoveDialog) {
        val currentImage = images[pagerState.currentPage]
        val otherLocations = locations.filter { it.folderId != activeLocationId }
        AlertDialog(
            onDismissRequest = { showMoveDialog = false },
            title = { Text(stringResource(R.string.wardrobe_move_to_title, 1)) },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    otherLocations.forEach { location ->
                        Surface(
                            shape = MaterialTheme.shapes.small,
                            tonalElevation = 1.dp,
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable {
                                    onMoveToLocation(setOf(currentImage.driveId), location.folderId)
                                    showMoveDialog = false
                                    if (images.size <= 1) onDismiss()
                                },
                        ) {
                            Row(
                                modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(8.dp),
                            ) {
                                Icon(Icons.Default.Place, contentDescription = null, modifier = Modifier.size(18.dp))
                                Text(location.name, style = MaterialTheme.typography.bodyMedium)
                            }
                        }
                    }
                }
            },
            confirmButton = {},
            dismissButton = {
                TextButton(onClick = { showMoveDialog = false }) {
                    Text(stringResource(R.string.action_cancel))
                }
            },
        )
    }
}

@Composable
private fun ZoomableImage(
    localPath: String,
    name: String,
    cacheKey: String = localPath,
    onScaleChanged: (Float) -> Unit = {},
    onLongPress: () -> Unit = {},
) {
    var scale by remember { mutableFloatStateOf(1f) }
    var offset by remember { mutableStateOf(Offset.Zero) }
    val ctx = LocalContext.current

    AsyncImage(
        model = remember(cacheKey) {
            ImageRequest.Builder(ctx)
                .data(localPath)
                .memoryCacheKey(cacheKey)
                .build()
        },
        contentDescription = name,
        modifier = Modifier
            .fillMaxSize()
            .pointerInput(Unit) {
                detectTapGestures(onLongPress = { if (scale <= 1.01f) onLongPress() })
            }
            .pointerInput(Unit) {
                awaitEachGesture {
                    awaitFirstDown(requireUnconsumed = false, pass = PointerEventPass.Initial)
                    var prevDistance = -1f
                    do {
                        val event = awaitPointerEvent(PointerEventPass.Initial)
                        val pressed = event.changes.filter { it.pressed }
                        when {
                            pressed.size >= 2 -> {
                                val dist = (pressed[1].position - pressed[0].position).getDistance()
                                if (prevDistance > 0f) {
                                    val focal = Offset(
                                        (pressed[0].position.x + pressed[1].position.x) / 2f,
                                        (pressed[0].position.y + pressed[1].position.y) / 2f,
                                    )
                                    val newScale = (scale * (dist / prevDistance)).coerceIn(1f, 8f)
                                    val delta = newScale / scale
                                    // Shift translation so the focal point stays fixed
                                    val cx = size.width / 2f
                                    val cy = size.height / 2f
                                    offset = Offset(
                                        (focal.x - cx) * (1f - delta) + offset.x * delta,
                                        (focal.y - cy) * (1f - delta) + offset.y * delta,
                                    )
                                    scale = newScale
                                    if (scale <= 1f) offset = Offset.Zero
                                    onScaleChanged(scale)
                                }
                                prevDistance = dist
                                pressed.forEach { it.consume() }
                            }
                            // Pan while zoomed — also consumes so pager doesn't swipe.
                            pressed.size == 1 && scale > 1.01f -> {
                                val delta = pressed[0].position - pressed[0].previousPosition
                                offset = Offset(offset.x + delta.x, offset.y + delta.y)
                                pressed[0].consume()
                                prevDistance = -1f
                            }
                            else -> prevDistance = -1f
                        }
                    } while (event.changes.any { it.pressed })
                }
            }
            .graphicsLayer {
                scaleX = scale
                scaleY = scale
                translationX = offset.x
                translationY = offset.y
            },
        contentScale = ContentScale.Fit,
    )
}

// AiProcessingOverlay lives in AiProcessingOverlay.kt (shared)

// ---------- Tags overlay ----------

@Composable
private fun TagsOverlay(
    tags: ClothingTags?,
    hasOriginal: Boolean = false,
    onTagImage: () -> Unit,
    onRemoveBackground: () -> Unit,
    onEditTags: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Surface(
        modifier = modifier,
        shape = MaterialTheme.shapes.medium,
        color = Color.Black.copy(alpha = 0.55f),
    ) {
        Column(
            modifier = Modifier.padding(horizontal = 10.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp),
            horizontalAlignment = Alignment.End,
        ) {
            if (tags != null) {
                Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                    if (tags.type.isNotEmpty()) TagChip(tags.type.localizedTagValue())
                    if (tags.category.isNotEmpty()) TagChip(tags.category.localizedTagValue())
                }
                if (tags.uses.isNotEmpty()) {
                    Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                        tags.uses.forEach { TagChip(it.localizedTagValue()) }
                    }
                }
                if (tags.colors.isNotEmpty()) {
                    Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                        tags.colors.forEach { TagChip(it.localizedTagValue()) }
                    }
                }
            }
            val isOffline = LocalIsOffline.current
            Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                TextButton(onClick = onTagImage, enabled = !isOffline, contentPadding = PaddingValues(horizontal = 8.dp, vertical = 4.dp)) {
                    Text(stringResource(R.string.wardrobe_tag_detect), color = if (isOffline) Color.White.copy(alpha = 0.38f) else Color.White, style = MaterialTheme.typography.labelSmall)
                }
                TextButton(onClick = onRemoveBackground, enabled = !isOffline, contentPadding = PaddingValues(horizontal = 8.dp, vertical = 4.dp)) {
                    Text(
                        stringResource(if (hasOriginal) R.string.wardrobe_tag_re_remove_bg else R.string.wardrobe_tag_remove_bg),
                        color = if (isOffline) Color.White.copy(alpha = 0.38f) else Color.White,
                        style = MaterialTheme.typography.labelSmall,
                    )
                }
                TextButton(onClick = onEditTags, contentPadding = PaddingValues(horizontal = 8.dp, vertical = 4.dp)) {
                    Text(stringResource(R.string.wardrobe_tag_edit), color = Color.White, style = MaterialTheme.typography.labelSmall)
                }
            }
        }
    }
}

// ---------- Tag edit sheet ----------

// Predefined suggestion lists matching the Gemini classify prompt
private val PRESET_USES        = listOf("casual", "formal", "business", "sport", "outdoor", "beach", "evening")
private val PRESET_SEASONALITY = listOf("spring", "summer", "fall", "winter")
private val PRESET_AESTHETIC   = listOf("minimalist", "streetwear", "preppy", "bohemian", "classic", "sporty", "romantic", "edgy", "business-casual", "luxury")
private val PRESET_FIT         = listOf("slim", "regular", "relaxed", "oversized", "tailored")
private val PRESET_MATERIAL    = listOf("cotton", "denim", "wool", "leather", "polyester", "linen", "silk", "knit")
private val PRESET_PATTERN     = listOf("solid", "stripes", "plaid", "floral", "geometric", "animal-print", "graphic", "camo", "abstract")

@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
private fun TagEditSheet(
    image: DriveImage,
    allTagCategories: List<TagCategory>,
    onSave: (ClothingTags) -> Unit,
    onDismiss: () -> Unit,
) {
    var label       by remember { mutableStateOf(image.tags?.label       ?: "") }
    var type        by remember { mutableStateOf(image.tags?.type        ?: "") }
    var category    by remember { mutableStateOf(image.tags?.category    ?: "") }
    var uses        by remember { mutableStateOf(image.tags?.uses        ?: emptyList()) }
    var colors      by remember { mutableStateOf(image.tags?.colors      ?: emptyList()) }
    var seasonality by remember { mutableStateOf(image.tags?.seasonality ?: emptyList()) }
    var aesthetic   by remember { mutableStateOf(image.tags?.aesthetic   ?: emptyList()) }
    var fit         by remember { mutableStateOf(image.tags?.fit         ?: emptyList()) }
    var material    by remember { mutableStateOf(image.tags?.material    ?: emptyList()) }
    var pattern     by remember { mutableStateOf(image.tags?.pattern     ?: emptyList()) }

    // Merge presets with anything already in the wardrobe for richer suggestions
    fun suggestions(label: String, presets: List<String>): List<String> {
        val fromWardrobe = allTagCategories.find { it.label == label }?.tags.orEmpty()
        return (presets + fromWardrobe).distinct().sorted()
    }

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .navigationBarsPadding()
                .imePadding()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 20.dp)
                .padding(bottom = 24.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            // Header
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(stringResource(R.string.wardrobe_tag_sheet_title), style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f))
                TextButton(onClick = {
                    onSave(ClothingTags(
                        label       = label.trim(),
                        type        = type.trim(),
                        category    = category.trim(),
                        uses        = uses.map { it.trim() }.filter { it.isNotEmpty() },
                        colors      = colors.map { it.trim() }.filter { it.isNotEmpty() },
                        seasonality = seasonality.map { it.trim() }.filter { it.isNotEmpty() },
                        aesthetic   = aesthetic.map { it.trim() }.filter { it.isNotEmpty() },
                        fit         = fit.map { it.trim() }.filter { it.isNotEmpty() },
                        material    = material.map { it.trim() }.filter { it.isNotEmpty() },
                        pattern     = pattern.map { it.trim() }.filter { it.isNotEmpty() },
                    ))
                }) { Text(stringResource(R.string.action_save)) }
            }

            HorizontalDivider()

            OutlinedTextField(
                value = label,
                onValueChange = { label = it },
                label = { Text(stringResource(R.string.tag_label)) },
                placeholder = { Text(stringResource(R.string.tag_label_placeholder)) },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
            )

            OutlinedTextField(
                value = type,
                onValueChange = { type = it },
                label = { Text(stringResource(R.string.tag_type)) },
                placeholder = { Text(stringResource(R.string.tag_type_placeholder)) },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
            )

            OutlinedTextField(
                value = category,
                onValueChange = { category = it },
                label = { Text(stringResource(R.string.tag_category)) },
                placeholder = { Text(stringResource(R.string.tag_category_placeholder)) },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
            )

            ChipListEditor(
                label = stringResource(R.string.tag_uses),
                values = uses,
                suggestions = suggestions("Uses", PRESET_USES).filter { it !in uses },
                onAdd    = { uses = uses + it },
                onRemove = { uses = uses - it },
            )

            ChipListEditor(
                label = stringResource(R.string.tag_colors),
                values = colors,
                suggestions = suggestions("Colors", emptyList()).filter { it !in colors },
                onAdd    = { colors = colors + it },
                onRemove = { colors = colors - it },
            )

            ChipListEditor(
                label = stringResource(R.string.tag_seasonality),
                values = seasonality,
                suggestions = suggestions("Seasonality", PRESET_SEASONALITY).filter { it !in seasonality },
                onAdd    = { seasonality = seasonality + it },
                onRemove = { seasonality = seasonality - it },
            )

            ChipListEditor(
                label = stringResource(R.string.tag_aesthetic),
                values = aesthetic,
                suggestions = suggestions("Aesthetic", PRESET_AESTHETIC).filter { it !in aesthetic },
                onAdd    = { aesthetic = aesthetic + it },
                onRemove = { aesthetic = aesthetic - it },
            )

            ChipListEditor(
                label = stringResource(R.string.tag_fit),
                values = fit,
                suggestions = suggestions("Fit", PRESET_FIT).filter { it !in fit },
                onAdd    = { fit = fit + it },
                onRemove = { fit = fit - it },
            )

            ChipListEditor(
                label = stringResource(R.string.tag_material),
                values = material,
                suggestions = suggestions("Material", PRESET_MATERIAL).filter { it !in material },
                onAdd    = { material = material + it },
                onRemove = { material = material - it },
            )

            ChipListEditor(
                label = stringResource(R.string.tag_pattern),
                values = pattern,
                suggestions = suggestions("Pattern", PRESET_PATTERN).filter { it !in pattern },
                onAdd    = { pattern = pattern + it },
                onRemove = { pattern = pattern - it },
            )
        }
    }
}

@OptIn(ExperimentalLayoutApi::class, ExperimentalMaterial3Api::class)
@Composable
private fun ChipListEditor(
    label: String,
    values: List<String>,
    suggestions: List<String>,
    onAdd: (String) -> Unit,
    onRemove: (String) -> Unit,
) {
    var inputText by remember { mutableStateOf("") }
    var showSuggestions by remember { mutableStateOf(false) }

    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Text(label, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)

        // Current values as removable chips
        FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            values.forEach { value ->
                InputChip(
                    selected = false,
                    onClick = {},
                    label = { Text(value.localizedTagValue()) },
                    trailingIcon = {
                        Icon(
                            Icons.Default.Close,
                            contentDescription = "Remove $value",
                            modifier = Modifier.size(14.dp).clickable { onRemove(value) },
                        )
                    },
                )
            }
        }

        // Add field
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedTextField(
                value = inputText,
                onValueChange = { inputText = it; showSuggestions = it.isNotEmpty() },
                placeholder = { Text(stringResource(R.string.tag_add_custom)) },
                singleLine = true,
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
                keyboardActions = KeyboardActions(onDone = {
                    if (inputText.isNotBlank()) {
                        onAdd(inputText.trim())
                        inputText = ""
                        showSuggestions = false
                    }
                }),
                trailingIcon = if (inputText.isNotBlank()) {
                    {
                        IconButton(onClick = {
                            onAdd(inputText.trim())
                            inputText = ""
                            showSuggestions = false
                        }) {
                            Icon(Icons.Default.Add, contentDescription = "Add")
                        }
                    }
                } else null,
                modifier = Modifier.weight(1f),
            )
        }

        // Existing suggestions (filtered by input if any); match on both English key and localized label
        val filtered = if (inputText.isBlank()) suggestions
                       else suggestions.filter {
                           it.contains(inputText, ignoreCase = true) ||
                           it.localizedTagValue().contains(inputText, ignoreCase = true)
                       }
        if (filtered.isNotEmpty()) {
            FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                filtered.forEach { suggestion ->
                    FilterChip(
                        selected = false,
                        onClick = { onAdd(suggestion); inputText = "" },
                        label = { Text(suggestion.localizedTagValue()) },
                    )
                }
            }
        }
    }
}

@Composable
private fun TagChip(label: String) {
    Surface(
        shape = MaterialTheme.shapes.extraSmall,
        color = Color.White.copy(alpha = 0.18f),
    ) {
        Text(
            text = label,
            modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp),
            style = MaterialTheme.typography.labelSmall,
            color = Color.White,
        )
    }
}

// ---------- Sort button ----------

@Composable
private fun SortButton(
    sortBy: SortOption,
    onSortChanged: (SortOption) -> Unit,
    modifier: Modifier = Modifier,
) {
    var expanded by remember { mutableStateOf(false) }
    Box(modifier = modifier) {
        IconButton(onClick = { expanded = true }) {
            Icon(Icons.AutoMirrored.Filled.Sort, contentDescription = "Sort")
        }
        DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
            SortOption.values().forEach { option ->
                DropdownMenuItem(
                    text = {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                        ) {
                            if (option == sortBy) Icon(Icons.Default.Check, null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(18.dp))
                            else Spacer(Modifier.size(18.dp))
                            Text(option.displayLabel())
                        }
                    },
                    onClick = { onSortChanged(option); expanded = false },
                )
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun DuplicateCheckSheet(
    check: DuplicateCheck,
    onConfirm: () -> Unit,
    onCancel: () -> Unit,
) {
    val context = LocalContext.current
    ModalBottomSheet(
        onDismissRequest = onCancel,
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .navigationBarsPadding()
                .padding(horizontal = 16.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text(
                stringResource(R.string.dedupe_dialog_title),
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold,
            )
            Text(
                stringResource(R.string.dedupe_dialog_desc, check.matches.size),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )

            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Box(
                    modifier = Modifier
                        .weight(1f)
                        .aspectRatio(1f)
                        .background(MaterialTheme.colorScheme.surfaceVariant, MaterialTheme.shapes.small),
                ) {
                    AsyncImage(
                        model = ImageRequest.Builder(context)
                            .data(java.io.File(check.rawFilePath))
                            .crossfade(true)
                            .build(),
                        contentDescription = stringResource(R.string.shop_your_photo),
                        contentScale = androidx.compose.ui.layout.ContentScale.Fit,
                        modifier = Modifier.fillMaxSize(),
                    )
                }
                LazyRow(
                    modifier = Modifier.weight(2f),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    items(check.matches, key = { it.image.driveId }) { m ->
                        Column(
                            modifier = Modifier.width(96.dp),
                            horizontalAlignment = Alignment.CenterHorizontally,
                        ) {
                            Box(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .aspectRatio(1f)
                                    .background(MaterialTheme.colorScheme.surfaceVariant, MaterialTheme.shapes.small),
                            ) {
                                AsyncImage(
                                    model = ImageRequest.Builder(context)
                                        .data(java.io.File(m.image.localPath))
                                        .crossfade(true)
                                        .build(),
                                    contentDescription = m.image.name,
                                    contentScale = androidx.compose.ui.layout.ContentScale.Fit,
                                    modifier = Modifier.fillMaxSize(),
                                )
                            }
                            val pct = (m.score.coerceIn(-1f, 1f) * 100f).toInt().coerceIn(0, 100)
                            Text(
                                stringResource(R.string.dedupe_score, pct),
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                }
            }

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                TextButton(onClick = onCancel, modifier = Modifier.weight(1f)) {
                    Text(stringResource(R.string.dedupe_dialog_cancel))
                }
                androidx.compose.material3.Button(onClick = onConfirm, modifier = Modifier.weight(1f)) {
                    Text(stringResource(R.string.dedupe_dialog_import_anyway))
                }
            }
        }
    }
}

/**
 * Find-by-photo bottom sheet. Shares the [MatchRow] + [MatchPreviewDialog] pattern with the
 * Shopping helper's Similarity Finder so both flows feel like a single feature with two entry
 * points: tapping a row opens the same preview dialog with the same "Show in wardrobe" /
 * "Add to shopping list" actions.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun FindByPhotoResultsSheet(
    findByPhoto: FindByPhoto,
    debugSimilarityPreview: Boolean,
    onPickMatch: (DriveImage) -> Unit,
    onAddToShoppingList: (queryPath: String) -> Unit,
    onDismiss: () -> Unit,
) {
    val context = LocalContext.current
    val shopMatches = remember(findByPhoto.matches) {
        findByPhoto.matches.map { ShopMatch(image = it.image, score = it.score) }
    }
    var previewIndex by remember { mutableStateOf<Int?>(null) }

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .navigationBarsPadding()
                .padding(horizontal = 16.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text(
                stringResource(R.string.wardrobe_find_by_photo),
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold,
            )

            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(160.dp)
                    .background(MaterialTheme.colorScheme.surfaceVariant, MaterialTheme.shapes.small),
            ) {
                AsyncImage(
                    model = ImageRequest.Builder(context)
                        .data(java.io.File(findByPhoto.queryPath))
                        .crossfade(true)
                        .build(),
                    contentDescription = stringResource(R.string.shop_your_photo),
                    contentScale = androidx.compose.ui.layout.ContentScale.Fit,
                    modifier = Modifier.fillMaxSize(),
                )
            }

            when {
                findByPhoto.isSearching -> Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    CircularProgressIndicator(modifier = Modifier.size(18.dp), strokeWidth = 2.dp)
                    Text(
                        stringResource(R.string.wardrobe_find_by_photo_searching),
                        style = MaterialTheme.typography.bodyMedium,
                    )
                }
                shopMatches.isEmpty() -> Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    Text(
                        stringResource(R.string.wardrobe_find_by_photo_empty),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Button(
                        onClick = { onAddToShoppingList(findByPhoto.queryPath) },
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        Icon(Icons.Default.ShoppingBag, contentDescription = null, modifier = Modifier.size(18.dp))
                        Spacer(Modifier.width(8.dp))
                        Text(stringResource(R.string.shop_add_to_shopping_list))
                    }
                }
                else -> Column(
                    modifier = Modifier.heightIn(max = 480.dp).verticalScroll(rememberScrollState()),
                ) {
                    shopMatches.forEachIndexed { idx, match ->
                        MatchRow(match = match, onClick = { previewIndex = idx })
                    }
                }
            }
        }
    }

    previewIndex?.let { idx ->
        if (idx in shopMatches.indices) {
            MatchPreviewDialog(
                matches = shopMatches,
                initialIndex = idx,
                queryRawPath = findByPhoto.queryPath,
                queryProcessedPath = null,
                querySegmented = false,
                queryHist = null,
                queryVec = null,
                showDebug = debugSimilarityPreview,
                onShowInWardrobe = { image ->
                    previewIndex = null
                    onPickMatch(image)
                },
                onAddToShoppingList = {
                    previewIndex = null
                    onAddToShoppingList(findByPhoto.queryPath)
                },
                canAddToShoppingList = true,
                onDismiss = { previewIndex = null },
            )
        }
    }
}


