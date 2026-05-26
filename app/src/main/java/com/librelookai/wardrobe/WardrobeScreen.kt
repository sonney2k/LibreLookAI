package com.librelookai.wardrobe
import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.provider.Settings
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.animateContentSize
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
import androidx.compose.foundation.layout.offset
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
import androidx.compose.foundation.layout.widthIn
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
import androidx.compose.material.icons.filled.Checkroom
import androidx.compose.material.icons.filled.AutoFixHigh
import androidx.compose.material.icons.filled.CameraAlt
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.ImageSearch
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Photo
import androidx.compose.material.icons.filled.FilterAlt
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.draw.clip
import androidx.compose.material.icons.filled.Place
import androidx.compose.material.icons.filled.ShoppingBag
import androidx.compose.material.icons.filled.SwapHoriz
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.material.icons.filled.VisibilityOff
import androidx.compose.material.icons.automirrored.filled.RotateRight
import androidx.compose.material.icons.automirrored.filled.Sort
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Checkbox
import androidx.compose.material3.Switch
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
import androidx.compose.material3.Snackbar
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.compose.ui.window.DialogWindowProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateMapOf
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
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import androidx.compose.ui.res.stringResource
import androidx.lifecycle.viewmodel.compose.viewModel
import coil.compose.AsyncImage
import coil.request.ImageRequest
import com.librelookai.AppScreenHeader
import com.librelookai.LocationButton
import com.librelookai.data.model.Location
import com.librelookai.gemini.ClothingTags
import com.librelookai.gemini.CutoutFixActions
import com.librelookai.gemini.normalize
import com.librelookai.gemini.normalizeAesthetic
import com.librelookai.gemini.normalizeColor
import com.librelookai.gemini.normalizeEnumTag
import com.librelookai.gemini.normalizeMaterial
import com.librelookai.gemini.normalizePattern
import com.librelookai.gemini.normalizeType
import com.librelookai.data.model.Outfit
import com.librelookai.data.model.TryOn
import com.librelookai.outfit.OutfitEventsViewModel
import com.librelookai.outfit.OutfitsViewModel
import com.librelookai.settings.ProfileViewModel
import com.librelookai.tryon.TryOnViewModel
import com.librelookai.shopping.MatchPreviewDialog
import com.librelookai.shopping.MatchRow
import com.librelookai.shopping.ShopMatch
import com.librelookai.shopping.ShoppingClosetViewModel
import com.librelookai.util.AiProcessingOverlay
import com.librelookai.util.Analytics
import com.librelookai.util.LocalIsOffline
import com.librelookai.util.LocalSystemBarsPadding
import com.librelookai.R

@Composable
fun WardrobeScreen(
    viewModel: WardrobeViewModel = viewModel(),
    outfitEventsViewModel: OutfitEventsViewModel = viewModel(),
    stylesViewModel: OutfitsViewModel = viewModel(),
    locationViewModel: LocationViewModel = viewModel(),
    shoppingClosetViewModel: ShoppingClosetViewModel = viewModel(),
    profileViewModel: ProfileViewModel = viewModel(),
    tryOnViewModel: TryOnViewModel = viewModel(),
    onCreateOutfitFromSelection: (Set<String>) -> Unit = {},
    onTryOnSelection: (Set<String>) -> Unit = {},
    onSuggestReplacements: (Set<String>) -> Unit = {},
    canTryOn: Boolean = false,
    dismissViewerTrigger: Int = 0,
    onSettingsClick: () -> Unit = {},
    modifier: Modifier = Modifier,
) {
    val state         by viewModel.state.collectAsState()
    val outfitEventsState  by outfitEventsViewModel.state.collectAsState()
    val outfitsState   by stylesViewModel.state.collectAsState()
    val tryOnState    by tryOnViewModel.state.collectAsState()
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

    var showUrlImportDialog by remember { mutableStateOf(false) }
    var showGalleryClosetPicker by remember { mutableStateOf(false) }
    val openGallery: () -> Unit = {
        Analytics.action("Wardrobe", "open_gallery", mapOf("locations" to locationState.locations.size.toString()))
        if (locationState.locations.size >= 2) showGalleryClosetPicker = true
        else galleryLauncher.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly))
    }
    val openUrlImport: () -> Unit = {
        Analytics.action("Wardrobe", "open_url_import_dialog")
        showUrlImportDialog = true
    }

    state.duplicateCheck?.let { check ->
        DuplicateCheckSheet(
            check = check,
            debugSimilarityPreview = profileState.preferences.debugSimilarityPreview,
            onConfirm = viewModel::confirmDuplicateImport,
            onCancel = viewModel::cancelDuplicateImport,
            onShowMatchInWardrobe = { image ->
                viewModel.cancelDuplicateImport()
                viewModel.requestScrollToImage(image.driveId)
            },
            onAddQueryToShoppingList = { queryPath ->
                shoppingClosetViewModel.importQuery(queryPath)
                viewModel.cancelDuplicateImport()
            },
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
            onOpenGallery = openGallery,
            onImportUrl = viewModel::importFromUrl,
            onDismissError = viewModel::clearError,
            onTagImage = viewModel::tagImage,
            onRemoveBackground = viewModel::reprocessBackground,
            onRotateImage = viewModel::rotateImage,
            onFixCutoutBg = viewModel::fixCutoutBgForItem,
            onLoadOriginal = viewModel::ensureOriginalCached,
            onUpdateTags = viewModel::updateTags,
            onToggleSelection = viewModel::toggleSelection,
            onSelectAll = viewModel::selectAll,
            onClearSelection = viewModel::clearSelection,
            onDeleteItems = viewModel::deleteItems,
            outfits = outfitsState.outfits,
            tryOns = tryOnState.history,
            onDeleteOutfits = { ids -> stylesViewModel.deleteOutfitsByIds(ids) },
            onDeleteTryOns = { tryOns -> tryOnViewModel.deleteTryOns(tryOns) },
            onMoveToLocation = viewModel::moveItemsToLocation,
            onSetActiveLocation = locationViewModel::setActiveLocation,
            onCreateOutfitFromSelection = onCreateOutfitFromSelection,
            onTryOnSelection = onTryOnSelection,
            onSuggestReplacements = onSuggestReplacements,
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
            onSearchByText = viewModel::searchByText,
            onTextFilter = viewModel::fuzzyFilterByText,
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
            onOpenGallery = {
                galleryLauncher.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly))
            },
            onOpenUrlImport = openUrlImport,
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

    if (showUrlImportDialog) {
        UrlImportDialog(
            locations = locationState.locations,
            initialFolderId = state.importTargetFolderId ?: locationState.locations.firstOrNull()?.folderId,
            onSubmit = { url, folderId ->
                folderId?.let { viewModel.setDefaultImportFolderId(it) }
                showUrlImportDialog = false
                viewModel.importFromUrl(url)
            },
            onDismiss = { showUrlImportDialog = false },
        )
    }

    state.urlImportPicker?.let { picker ->
        UrlImportPicker(
            pageUrl = picker.pageUrl,
            candidates = picker.candidates,
            isDownloading = picker.isDownloading,
            onPick = viewModel::confirmUrlImportPick,
            onDismiss = viewModel::cancelUrlImport,
        )
    }

    if (showGalleryClosetPicker) {
        val initialFolderId = state.importTargetFolderId ?: locationState.locations.firstOrNull()?.folderId
        var selectedFolderId by remember { mutableStateOf(initialFolderId) }
        AlertDialog(
            onDismissRequest = { showGalleryClosetPicker = false },
            title = { Text(stringResource(R.string.wardrobe_add_to_closet_title)) },
            text = {
                Column {
                    locationState.locations.sortedBy { it.name }.forEach { loc ->
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
                    selectedFolderId?.let { viewModel.setDefaultImportFolderId(it) }
                    showGalleryClosetPicker = false
                    galleryLauncher.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly))
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

/** Returns the string-resource id for a canonical English tag value, or null when unmapped.
 *  Shared by the Composable display path and the non-Composable search path. */
internal fun tagValueResId(canonical: String): Int? = when (canonical.lowercase()) {
    "casual" -> R.string.tag_val_casual
    "formal" -> R.string.tag_val_formal
    "business" -> R.string.tag_val_business
    "sport" -> R.string.tag_val_sport
    "outdoor" -> R.string.tag_val_outdoor
    "beach" -> R.string.tag_val_beach
    "evening" -> R.string.tag_val_evening
    "spring" -> R.string.tag_val_spring
    "summer" -> R.string.tag_val_summer
    "fall" -> R.string.tag_val_fall
    "winter" -> R.string.tag_val_winter
    "minimalist" -> R.string.tag_val_minimalist
    "streetwear" -> R.string.tag_val_streetwear
    "preppy" -> R.string.tag_val_preppy
    "bohemian" -> R.string.tag_val_bohemian
    "classic" -> R.string.tag_val_classic
    "sporty" -> R.string.tag_val_sporty
    "romantic" -> R.string.tag_val_romantic
    "edgy" -> R.string.tag_val_edgy
    "business-casual" -> R.string.tag_val_business_casual
    "luxury" -> R.string.tag_val_luxury
    "slim" -> R.string.tag_val_slim
    "regular" -> R.string.tag_val_regular
    "relaxed" -> R.string.tag_val_relaxed
    "oversized" -> R.string.tag_val_oversized
    "tailored" -> R.string.tag_val_tailored
    "cotton" -> R.string.tag_val_cotton
    "denim" -> R.string.tag_val_denim
    "wool" -> R.string.tag_val_wool
    "leather" -> R.string.tag_val_leather
    "polyester" -> R.string.tag_val_polyester
    "linen" -> R.string.tag_val_linen
    "silk" -> R.string.tag_val_silk
    "knit" -> R.string.tag_val_knit
    "solid" -> R.string.tag_val_solid
    "stripes" -> R.string.tag_val_stripes
    "plaid" -> R.string.tag_val_plaid
    "floral" -> R.string.tag_val_floral
    "geometric" -> R.string.tag_val_geometric
    "animal-print" -> R.string.tag_val_animal_print
    "graphic" -> R.string.tag_val_graphic
    "camo" -> R.string.tag_val_camo
    "abstract" -> R.string.tag_val_abstract
    "tops" -> R.string.tag_val_tops
    "bottoms" -> R.string.tag_val_bottoms
    "outerwear" -> R.string.tag_val_outerwear
    "footwear" -> R.string.tag_val_footwear
    "accessories" -> R.string.tag_val_accessories
    "dress" -> R.string.tag_val_dress
    "suit" -> R.string.tag_val_suit
    "jumpsuit" -> R.string.tag_val_jumpsuit
    "black" -> R.string.tag_val_black
    "white" -> R.string.tag_val_white
    "grey" -> R.string.tag_val_grey
    "gray" -> R.string.tag_val_gray
    "charcoal" -> R.string.tag_val_charcoal
    "brown" -> R.string.tag_val_brown
    "beige" -> R.string.tag_val_beige
    "cream" -> R.string.tag_val_cream
    "ivory" -> R.string.tag_val_ivory
    "tan" -> R.string.tag_val_tan
    "camel" -> R.string.tag_val_camel
    "red" -> R.string.tag_val_red
    "burgundy" -> R.string.tag_val_burgundy
    "wine" -> R.string.tag_val_wine
    "coral" -> R.string.tag_val_coral
    "pink" -> R.string.tag_val_pink
    "blush" -> R.string.tag_val_blush
    "magenta" -> R.string.tag_val_magenta
    "fuchsia" -> R.string.tag_val_fuchsia
    "orange" -> R.string.tag_val_orange
    "rust" -> R.string.tag_val_rust
    "yellow" -> R.string.tag_val_yellow
    "mustard" -> R.string.tag_val_mustard
    "gold" -> R.string.tag_val_gold
    "green" -> R.string.tag_val_green
    "olive" -> R.string.tag_val_olive
    "khaki" -> R.string.tag_val_khaki
    "sage" -> R.string.tag_val_sage
    "mint" -> R.string.tag_val_mint
    "emerald" -> R.string.tag_val_emerald
    "forest", "forest green" -> R.string.tag_val_forest
    "teal" -> R.string.tag_val_teal
    "blue" -> R.string.tag_val_blue
    "navy" -> R.string.tag_val_navy
    "cobalt" -> R.string.tag_val_cobalt
    "sky", "sky blue" -> R.string.tag_val_sky
    "denim blue" -> R.string.tag_val_denim_blue
    "purple" -> R.string.tag_val_purple
    "lavender" -> R.string.tag_val_lavender
    "violet" -> R.string.tag_val_violet
    "lilac" -> R.string.tag_val_lilac
    "silver" -> R.string.tag_val_silver
    "multicolor", "multi-color", "multicolour" -> R.string.tag_val_multicolor
    "printed" -> R.string.tag_val_printed
    else -> null
}

/** Maps a stored English tag value to its localized display string. Unknown values pass through. */
@Composable
internal fun String.localizedTagValue(): String =
    tagValueResId(this)?.let { stringResource(it) } ?: this

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
    onFixCutoutBg: (String, CutoutFixActions) -> Unit = { _, _ -> },
    onLoadOriginal: (suspend (String) -> String?)? = null,
    onUpdateTags: (String, ClothingTags) -> Unit,
    onToggleSelection: (String) -> Unit,
    onSelectAll: (List<String>) -> Unit,
    onClearSelection: () -> Unit,
    onDeleteItems: (Set<String>) -> Unit,
    outfits: List<Outfit> = emptyList(),
    tryOns: List<TryOn> = emptyList(),
    onDeleteOutfits: (List<String>) -> Unit = {},
    onDeleteTryOns: (List<TryOn>) -> Unit = {},
    onMoveToLocation: (Set<String>, String) -> Unit,
    onSetActiveLocation: (String) -> Unit,
    onCreateOutfitFromSelection: (Set<String>) -> Unit,
    onTryOnSelection: (Set<String>) -> Unit = {},
    onSuggestReplacements: (Set<String>) -> Unit = {},
    canTryOn: Boolean = false,
    onDismissBatteryExemption: () -> Unit = {},
    onSetImportTarget: (String) -> Unit = {},
    processingImageId: String?,
    dismissViewerTrigger: Int = 0,
    onSettingsClick: () -> Unit = {},
    onOpenFindByPhoto: () -> Unit = {},
    onSearchByText: (String) -> Unit = {},
    onTextFilter: (String, List<DriveImage>) -> List<DriveImage> = { _, items -> items },
    onDismissFindByPhoto: () -> Unit = {},
    onConsumePendingScroll: () -> Unit = {},
    onAddMatchToShoppingList: (String) -> Unit = {},
    debugSimilarityPreview: Boolean = false,
    modifier: Modifier = Modifier,
) {
    val isOffline = LocalIsOffline.current
    var selectedIndex by remember { mutableStateOf<Int?>(null) }
    // Non-null while the delete-confirm dialog is open; holds the item driveIds about to be deleted
    // (the multi-select set, or a single item from the full-screen viewer).
    var pendingDeleteIds by remember { mutableStateOf<Set<String>?>(null) }
    var showMoveDialog by remember { mutableStateOf(false) }
    // Filter + sort state
    var selectedTags by remember { mutableStateOf(emptyMap<String, Set<String>>()) }
    var sortBy by remember { mutableStateOf(SortOption.DATE_DESC) }
    var filterSheetOpen by remember { mutableStateOf(false) }
    var textQuery by remember { mutableStateOf("") }
    val appliedFilterCount = selectedTags.values.sumOf { it.size } + (if (textQuery.isNotBlank()) 1 else 0)

    val tagCategories = remember(state.images) { state.images.tagCategories() }

    // Close the viewer when the wardrobe nav tab is re-tapped from the nav bar.
    LaunchedEffect(dismissViewerTrigger) { if (dismissViewerTrigger > 0) selectedIndex = null }

    // OR within each category, AND across categories
    val filteredImages = remember(state.images, selectedTags, textQuery) {
        val activeFilters = selectedTags.filter { (_, tags) -> tags.isNotEmpty() }
        val byTags = if (activeFilters.isEmpty()) state.images
        else state.images.filter { img ->
            activeFilters.all { (categoryLabel, catTags) ->
                catTags.any { it in img.tagStringsForCategory(categoryLabel) }
            }
        }
        if (textQuery.isBlank()) byTags else onTextFilter(textQuery, byTags)
    }

    val displayedImages = remember(filteredImages, sortBy, popularityMap) {
        when (sortBy) {
            SortOption.DATE_DESC  -> filteredImages.sortedByDescending { it.createdTimeMs }
            SortOption.DATE_ASC   -> filteredImages.sortedBy { it.createdTimeMs }
            SortOption.POPULARITY -> filteredImages.sortedByDescending { popularityMap[it.driveId] ?: 0 }
            SortOption.TYPE       -> filteredImages.sortedBy { it.tags?.type?.lowercase() ?: "" }
            SortOption.CATEGORY   -> filteredImages.sortedBy { it.tags?.category?.lowercase() ?: "" }
        }
    }

    // Clear viewer when filter/sort changes to avoid stale index
    LaunchedEffect(selectedTags, sortBy, textQuery) { selectedIndex = null }

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
                leadingIcon = Icons.Default.Checkroom,
                trailingContent = {
                    LocationButton(
                        locations = locations,
                        activeLocationId = activeLocationId,
                        onSetActiveLocation = onSetActiveLocation,
                    )
                },
                onSettingsClick = onSettingsClick,
            )
            // ---- Filter + search + sort row ----
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(com.librelookai.ui.theme.LocalWardrobePalette.current.surface),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                QuickCategoryRow(
                    totalCount = state.images.size,
                    filteredCount = filteredImages.size,
                    appliedFilterCount = appliedFilterCount,
                    filtersEnabled = tagCategories.isNotEmpty(),
                    onClearFilters = {
                        selectedTags = emptyMap()
                        textQuery = ""
                    },
                    onOpenFilters = {
                        Analytics.action("Wardrobe", "open_filter_sheet", mapOf("active" to appliedFilterCount.toString()))
                        filterSheetOpen = true
                    },
                    modifier = Modifier.weight(1f),
                )
                IconButton(onClick = {
                    Analytics.action("Wardrobe", "open_find_by_photo")
                    onOpenFindByPhoto()
                }) {
                    Icon(
                        Icons.Default.ImageSearch,
                        contentDescription = stringResource(R.string.wardrobe_search),
                    )
                }
                SortButton(
                    sortBy = sortBy,
                    onSortChanged = {
                        Analytics.action("Wardrobe", "sort_changed", mapOf("option" to it.name))
                        sortBy = it
                    },
                    modifier = Modifier.padding(end = 4.dp),
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
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    if (displayedImages.any { it.driveId !in state.selectedIds }) {
                        TextButton(
                            onClick = {
                                Analytics.action("Wardrobe", "select_all", mapOf("count" to displayedImages.size.toString()))
                                onSelectAll(displayedImages.map { it.driveId })
                            },
                            contentPadding = PaddingValues(horizontal = 8.dp, vertical = 0.dp),
                        ) {
                            Text(
                                stringResource(R.string.wardrobe_select_all_count, displayedImages.size),
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                        }
                    }
                    TextButton(
                        onClick = {
                            Analytics.action("Wardrobe", "clear_selection")
                            onClearSelection()
                        },
                        contentPadding = PaddingValues(horizontal = 8.dp, vertical = 0.dp),
                    ) {
                        Text(
                            stringResource(R.string.action_deselect_all),
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
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
                            if (selectedTags.values.any { it.isNotEmpty() } || textQuery.isNotBlank()) {
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
                    WardrobeZoomableItemGrid(
                        images = displayedImages,
                        selectedIds = state.selectedIds,
                        onClick = { index, image ->
                            if (isSelectionMode) {
                                Analytics.action("Wardrobe", "toggle_selection")
                                onToggleSelection(image.driveId)
                            } else {
                                Analytics.action("Wardrobe", "open_item_viewer")
                                selectedIndex = index
                            }
                        },
                        onLongClick = { image ->
                            Analytics.action("Wardrobe", "long_press_select")
                            onToggleSelection(image.driveId)
                        },
                        modifier = Modifier.weight(1f).fillMaxWidth(),
                        gridState = gridState,
                        locationLookup = if (locations.size > 1) {
                            { image -> locations.find { it.folderId == image.folderId }?.name }
                        } else {
                            { null }
                        },
                        highlightedDriveId = highlightedDriveId,
                        processingDriveId = state.processingImageId,
                    )
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
                    ExtendedFloatingActionButton(
                        onClick = { onSuggestReplacements(state.selectedIds) },
                        containerColor = MaterialTheme.colorScheme.primaryContainer,
                        contentColor = MaterialTheme.colorScheme.onPrimaryContainer,
                        icon = { Icon(Icons.Default.SwapHoriz, contentDescription = null) },
                        text = {
                            androidx.compose.foundation.layout.Row(
                                verticalAlignment = androidx.compose.ui.Alignment.CenterVertically,
                            ) {
                                com.librelookai.billing.CostBadge(com.librelookai.gemini.GeminiActionId.GENERATE_TEXT)
                                Text(stringResource(R.string.wardrobe_suggest_replacements))
                            }
                        },
                    )
                }
                if (locations.size > 1 && !isOffline) {
                    ExtendedFloatingActionButton(
                        onClick = {
                            Analytics.action("Wardrobe", "open_move_dialog", mapOf("count" to state.selectedIds.size.toString()))
                            showMoveDialog = true
                        },
                        containerColor = MaterialTheme.colorScheme.secondaryContainer,
                        contentColor = MaterialTheme.colorScheme.onSecondaryContainer,
                        icon = { Icon(Icons.Default.Place, contentDescription = null) },
                        text = { Text(stringResource(R.string.wardrobe_move_to)) },
                    )
                }
                if (!isOffline) {
                    ExtendedFloatingActionButton(
                        onClick = {
                            Analytics.action("Wardrobe", "open_delete_dialog", mapOf("count" to state.selectedIds.size.toString()))
                            pendingDeleteIds = state.selectedIds
                        },
                        containerColor = MaterialTheme.colorScheme.error,
                        contentColor = MaterialTheme.colorScheme.onError,
                        icon = { Icon(Icons.Default.Delete, contentDescription = null) },
                        text = { Text(stringResource(R.string.action_delete)) },
                    )
                }
            }
        } else if (!isOffline) {
            FloatingActionButton(
                onClick = {
                    Analytics.action("Wardrobe", "open_camera")
                    onOpenCamera()
                },
                modifier = Modifier.align(Alignment.BottomEnd).padding(16.dp),
            ) {
                Icon(Icons.Default.Add, contentDescription = stringResource(R.string.wardrobe_add_camera))
            }
        }

        state.error?.let { msg ->
            Snackbar(
                modifier = Modifier
                    .align(Alignment.TopCenter)
                    .statusBarsPadding()
                    .padding(start = 8.dp, end = 8.dp, top = 64.dp),
                action = { TextButton(onClick = onDismissError) { Text(stringResource(R.string.action_dismiss)) } },
            ) { Text(msg) }
        }

        if (filterSheetOpen) {
            WardrobeFilterSheet(
                tagCategories = tagCategories,
                selectedTags = selectedTags,
                appliedCount = displayedImages.size,
                onTagsChanged = { selectedTags = it },
                textQuery = textQuery,
                onTextQueryChanged = { textQuery = it },
                onDismiss = { filterSheetOpen = false },
            )
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
                    // Route single deletes through the same cascade-aware confirm dialog.
                    pendingDeleteIds = setOf(driveId)
                    selectedIndex = null
                },
                onMoveToLocation = { ids, folderId ->
                    onMoveToLocation(ids, folderId)
                    if (displayedImages.size <= 1) selectedIndex = null
                },
                onCreateOutfitFromSelection = onCreateOutfitFromSelection,
                onFixCutoutBg = onFixCutoutBg,
                onLoadOriginal = onLoadOriginal,
                locations = locations,
                activeLocationId = activeLocationId,
                processingImageId = processingImageId,
            )
        }
    }

    pendingDeleteIds?.let { ids ->
        // Items being deleted, by Drive ID and by stable cutout filename — outfits/try-ons
        // reference items by both, so match on either.
        val deletingNames = remember(ids, state.images) {
            state.images.filter { it.driveId in ids }.map { it.name }.toSet()
        }
        val affectedOutfits = remember(ids, deletingNames, outfits) {
            outfits.filter { o -> o.itemIds.any { it in ids } || o.itemNames.any { it in deletingNames } }
        }
        val affectedTryOns = remember(ids, deletingNames, tryOns) {
            tryOns.filter { t -> t.itemIds.any { it in ids } || t.itemNames.any { it in deletingNames } }
        }
        var cascadeOutfits by remember(ids) { mutableStateOf(true) }
        var cascadeTryOns by remember(ids) { mutableStateOf(true) }
        // Re-provide parent context/config so the dialog window honours the in-app language
        // toggle (an AlertDialog opens its own window; see CLAUDE.md → Dialog quirks).
        val parentContext = LocalContext.current
        val parentConfiguration = LocalConfiguration.current
        AlertDialog(
            onDismissRequest = { pendingDeleteIds = null },
            title = {
                CompositionLocalProvider(
                    LocalContext provides parentContext,
                    LocalConfiguration provides parentConfiguration,
                ) { Text(stringResource(R.string.wardrobe_delete_title)) }
            },
            text = {
                CompositionLocalProvider(
                    LocalContext provides parentContext,
                    LocalConfiguration provides parentConfiguration,
                ) {
                    Column {
                        Text(stringResource(R.string.wardrobe_delete_text, ids.size))
                        if (affectedOutfits.isNotEmpty()) {
                            Spacer(Modifier.height(8.dp))
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .clickable { cascadeOutfits = !cascadeOutfits },
                            ) {
                                Checkbox(checked = cascadeOutfits, onCheckedChange = { cascadeOutfits = it })
                                Text(stringResource(R.string.wardrobe_delete_cascade_outfits, affectedOutfits.size))
                            }
                        }
                        if (affectedTryOns.isNotEmpty()) {
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .clickable { cascadeTryOns = !cascadeTryOns },
                            ) {
                                Checkbox(checked = cascadeTryOns, onCheckedChange = { cascadeTryOns = it })
                                Text(stringResource(R.string.wardrobe_delete_cascade_tryons, affectedTryOns.size))
                            }
                        }
                    }
                }
            },
            confirmButton = {
                CompositionLocalProvider(
                    LocalContext provides parentContext,
                    LocalConfiguration provides parentConfiguration,
                ) {
                    TextButton(
                        onClick = {
                            Analytics.action(
                                "Wardrobe", "confirm_delete_selected",
                                mapOf(
                                    "count" to ids.size.toString(),
                                    "outfits" to (if (cascadeOutfits) affectedOutfits.size else 0).toString(),
                                    "tryons" to (if (cascadeTryOns) affectedTryOns.size else 0).toString(),
                                ),
                            )
                            onDeleteItems(ids)
                            if (cascadeOutfits && affectedOutfits.isNotEmpty()) onDeleteOutfits(affectedOutfits.map { it.id })
                            if (cascadeTryOns && affectedTryOns.isNotEmpty()) onDeleteTryOns(affectedTryOns)
                            pendingDeleteIds = null
                        }
                    ) {
                        Text(stringResource(R.string.action_delete), color = MaterialTheme.colorScheme.error)
                    }
                }
            },
            dismissButton = {
                CompositionLocalProvider(
                    LocalContext provides parentContext,
                    LocalConfiguration provides parentConfiguration,
                ) {
                    TextButton(onClick = { pendingDeleteIds = null }) {
                        Text(stringResource(R.string.action_cancel))
                    }
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
                                    Analytics.action("Wardrobe", "confirm_move_selected", mapOf("count" to state.selectedIds.size.toString()))
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
                queryPath?.let { onAddMatchToShoppingList(it) }
                onDismissFindByPhoto()
            },
            onSearchAgain = { q -> onSearchByText(q) },
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
                onClick = {
                    Analytics.action("Wardrobe", "submit_url_import")
                    onSubmit(url.trim(), selectedFolderId)
                },
            ) { Text(stringResource(R.string.action_continue)) }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text(stringResource(R.string.action_cancel)) }
        },
    )
}

// ---------- Full-screen image viewer ----------

@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
internal fun FullScreenViewer(
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
    onFixCutoutBg: (String, CutoutFixActions) -> Unit = { _, _ -> },
    onLoadOriginal: (suspend (String) -> String?)? = null,
    locations: List<Location>,
    activeLocationId: String,
    processingImageId: String?,
    writeMode: Boolean = true,
) {
    val parentContext = LocalContext.current
    val parentConfiguration = LocalConfiguration.current
    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(
            usePlatformDefaultWidth = false,
            decorFitsSystemWindows = false,
        ),
    ) {
        val dialogView = androidx.compose.ui.platform.LocalView.current
        androidx.compose.runtime.SideEffect {
            val window = (dialogView.parent as? DialogWindowProvider)?.window ?: return@SideEffect
            window.setLayout(
                android.view.ViewGroup.LayoutParams.MATCH_PARENT,
                android.view.ViewGroup.LayoutParams.MATCH_PARENT,
            )
            androidx.core.view.WindowCompat.setDecorFitsSystemWindows(window, false)
        }
        CompositionLocalProvider(
            LocalContext provides parentContext,
            LocalConfiguration provides parentConfiguration,
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
    var showMoveDialog by remember { mutableStateOf(false) }
    var showEditMenu by remember { mutableStateOf(false) }
    var showFixCutoutBgDialog by remember { mutableStateOf(false) }
    var showOriginal by remember { mutableStateOf(false) }
    var hideTags by rememberSaveable { mutableStateOf(false) }
    // driveId → resolved original local path. Cached across page swipes so revisiting the
    // same item doesn't redownload.
    val originalPaths = remember { mutableStateMapOf<String, String>() }
    var loadingOriginal by remember { mutableStateOf(false) }

    val currentImage = images[pagerState.currentPage]
    val headerLabel = currentImage.tags?.label?.takeIf { it.isNotBlank() } ?: currentImage.name
    val importedDateText = remember(currentImage.createdTimeMs) {
        if (currentImage.createdTimeMs > 0L) {
            java.text.DateFormat
                .getDateInstance(java.text.DateFormat.MEDIUM, java.util.Locale.getDefault())
                .format(java.util.Date(currentImage.createdTimeMs))
        } else null
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background),
    ) {
        Column(modifier = Modifier.fillMaxSize()) {
            // Top bar: Close on the left, the standard right-side cluster (closet selector +
            // Insights + Settings) on the right — parity with AppScreenHeader so these stay
            // reachable while viewing an item. The view-original toggle (when available) sits
            // just left of the cluster.
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(start = 4.dp, end = 4.dp, top = 4.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                IconButton(onClick = onDismiss) {
                    Icon(
                        Icons.Default.Close,
                        contentDescription = "Close",
                        tint = MaterialTheme.colorScheme.onBackground,
                    )
                }
                Spacer(Modifier.weight(1f))
                if (onLoadOriginal != null && currentImage.originalDriveId != null) {
                    IconButton(onClick = {
                        Analytics.action("ItemViewer", if (showOriginal) "hide_original" else "show_original")
                        showOriginal = !showOriginal
                    }) {
                        Icon(
                            imageVector = if (showOriginal) Icons.Default.ImageSearch else Icons.Default.Photo,
                            contentDescription = stringResource(
                                if (showOriginal) R.string.wardrobe_view_cutout else R.string.wardrobe_view_original
                            ),
                            tint = MaterialTheme.colorScheme.onBackground,
                        )
                    }
                }
                com.librelookai.ViewerHeaderActions(onBeforeNavigate = onDismiss)
            }
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = 4.dp, start = 16.dp, end = 16.dp, bottom = 8.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                Text(
                    text = headerLabel,
                    color = MaterialTheme.colorScheme.onBackground,
                    style = MaterialTheme.typography.titleMedium,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
                if (importedDateText != null) {
                    Text(
                        text = stringResource(R.string.wardrobe_imported_on, importedDateText),
                        color = MaterialTheme.colorScheme.onBackground.copy(alpha = 0.7f),
                        style = MaterialTheme.typography.labelSmall,
                    )
                }
                Text(
                    text = "${pagerState.currentPage + 1} / ${images.size}",
                    color = MaterialTheme.colorScheme.onBackground,
                    style = MaterialTheme.typography.labelMedium,
                )
                if (!hideTags) currentImage.tags?.let { tags ->
                    val maxWidth = LocalConfiguration.current.screenWidthDp.dp * 0.7f
                    FlowRow(
                        modifier = Modifier
                            .widthIn(max = maxWidth)
                            .then(if (writeMode) Modifier.clickable {
                                Analytics.action("ItemViewer", "edit_tags")
                                showTagEdit = true
                            } else Modifier),
                        horizontalArrangement = Arrangement.spacedBy(4.dp, Alignment.CenterHorizontally),
                        verticalArrangement = Arrangement.spacedBy(4.dp),
                    ) {
                        if (tags.type.isNotEmpty()) DetailTagChip(tags.type.localizedTagValue())
                        if (tags.category.isNotEmpty()) DetailTagChip(tags.category.localizedTagValue())
                        tags.uses.forEach { DetailTagChip(it.localizedTagValue()) }
                        tags.colors.forEach { DetailTagChip(it.localizedTagValue()) }
                        tags.seasonality.forEach { DetailTagChip(it.localizedTagValue()) }
                        tags.aesthetic.forEach { DetailTagChip(it.localizedTagValue()) }
                        tags.fit.forEach { DetailTagChip(it.localizedTagValue()) }
                        tags.material.forEach { DetailTagChip(it.localizedTagValue()) }
                        tags.pattern.forEach { DetailTagChip(it.localizedTagValue()) }
                    }
                }
                // Hide-tags chip sits inline below the tags (or alone when hidden) so it
                // doesn't crowd the close-X at top-left and is unambiguously linked to tags.
                if (currentImage.tags != null) {
                    HideTagsChip(
                        hideTags = hideTags,
                        onToggle = {
                            Analytics.action("ItemViewer", if (hideTags) "show_tags" else "hide_tags")
                            hideTags = !hideTags
                        },
                    )
                }
            }
            HorizontalPager(
                state = pagerState,
                userScrollEnabled = pageScale <= 1.01f,
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(1f),
            ) { page ->
                val img = images[page]
                val origPath = originalPaths[img.driveId]
                val useOriginal = showOriginal && page == pagerState.currentPage && origPath != null
                ZoomableImage(
                    localPath = if (useOriginal) origPath!! else img.localPath,
                    name = img.name,
                    cacheKey = if (useOriginal) "${img.driveId}_original" else "${img.driveId}_${img.version}",
                    onScaleChanged = { s -> if (page == pagerState.currentPage) pageScale = s },
                )
            }
        }

        if (currentImage.driveId == processingImageId) {
            AiProcessingOverlay(modifier = Modifier.fillMaxSize())
        }

        // Lazily fetch the original when the top-bar toggle is switched on. The toggle button
        // itself now lives in the top header row; this only owns the fetch + loading spinner.
        if (onLoadOriginal != null && currentImage.originalDriveId != null) {
            LaunchedEffect(showOriginal, currentImage.driveId) {
                if (showOriginal && originalPaths[currentImage.driveId] == null) {
                    loadingOriginal = true
                    val path = runCatching { onLoadOriginal(currentImage.driveId) }.getOrNull()
                    if (path != null) originalPaths[currentImage.driveId] = path
                    loadingOriginal = false
                }
            }
            if (showOriginal && loadingOriginal) {
                CircularProgressIndicator(
                    modifier = Modifier.align(Alignment.Center),
                    color = MaterialTheme.colorScheme.onBackground,
                )
            }
        }

        // Edit speed-dial — same style as Wardrobe + FAB. Expands to rotate / detect tags / remove bg.
        if (!isOffline && writeMode) {
            val barInsets = LocalSystemBarsPadding.current
            val view = androidx.compose.ui.platform.LocalView.current
            val density = androidx.compose.ui.platform.LocalDensity.current
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
            val effectiveBottom = maxOf(
                barInsets.calculateBottomPadding(),
                rootInsetBottomDp,
                48.dp,
            )
            Column(
                modifier = Modifier.align(Alignment.BottomEnd).padding(bottom = effectiveBottom).padding(16.dp),
                horizontalAlignment = Alignment.End,
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                if (showEditMenu) {
                    ExtendedFloatingActionButton(
                        onClick = {
                            Analytics.action("ItemViewer", "create_style_from_item")
                            showEditMenu = false
                            onCreateOutfitFromSelection(setOf(currentImage.driveId))
                        },
                        containerColor = MaterialTheme.colorScheme.primaryContainer,
                        contentColor = MaterialTheme.colorScheme.onPrimaryContainer,
                        icon = { Icon(Icons.Default.AutoFixHigh, contentDescription = null) },
                        text = { Text(stringResource(R.string.wardrobe_create_style)) },
                    )
                    ExtendedFloatingActionButton(
                        onClick = {
                            Analytics.action("ItemViewer", "rotate_image")
                            onRotateImage(currentImage.driveId)
                            showEditMenu = false
                        },
                        containerColor = MaterialTheme.colorScheme.primaryContainer,
                        contentColor = MaterialTheme.colorScheme.onPrimaryContainer,
                        icon = { Icon(Icons.AutoMirrored.Filled.RotateRight, contentDescription = null) },
                        text = { Text(stringResource(R.string.wardrobe_tag_rotate)) },
                    )
                    ExtendedFloatingActionButton(
                        onClick = {
                            Analytics.action("ItemViewer", "tag_image")
                            onTagImage(currentImage.driveId)
                            showEditMenu = false
                        },
                        containerColor = MaterialTheme.colorScheme.primaryContainer,
                        contentColor = MaterialTheme.colorScheme.onPrimaryContainer,
                        icon = { Icon(Icons.Default.AutoFixHigh, contentDescription = null) },
                        text = {
                            androidx.compose.foundation.layout.Row(
                                verticalAlignment = androidx.compose.ui.Alignment.CenterVertically,
                            ) {
                                com.librelookai.billing.CostBadge(com.librelookai.gemini.GeminiActionId.CLASSIFY_CLOTHING)
                                Text(stringResource(R.string.wardrobe_tag_detect))
                            }
                        },
                    )
                    ExtendedFloatingActionButton(
                        onClick = {
                            Analytics.action("ItemViewer", "remove_background")
                            onRemoveBackground(currentImage.driveId)
                            showEditMenu = false
                        },
                        containerColor = MaterialTheme.colorScheme.error,
                        contentColor = MaterialTheme.colorScheme.onError,
                        icon = { Icon(Icons.Default.ImageSearch, contentDescription = null) },
                        text = {
                            androidx.compose.foundation.layout.Row(
                                verticalAlignment = androidx.compose.ui.Alignment.CenterVertically,
                            ) {
                                com.librelookai.billing.CostBadge(com.librelookai.gemini.GeminiActionId.REMOVE_BACKGROUND)
                                Text(stringResource(
                                    if (currentImage.originalDriveId != null) R.string.wardrobe_tag_re_remove_bg
                                    else R.string.wardrobe_tag_remove_bg
                                ))
                            }
                        },
                    )
                    ExtendedFloatingActionButton(
                        onClick = {
                            Analytics.action("ItemViewer", "fix_cutout_bg")
                            showFixCutoutBgDialog = true
                            showEditMenu = false
                        },
                        containerColor = MaterialTheme.colorScheme.error,
                        contentColor = MaterialTheme.colorScheme.onError,
                        icon = { Icon(Icons.Default.AutoFixHigh, contentDescription = null) },
                        text = { Text(stringResource(R.string.wardrobe_fix_cutout_bg)) },
                    )
                    if (locations.any { it.folderId != activeLocationId }) {
                        ExtendedFloatingActionButton(
                            onClick = {
                                Analytics.action("ItemViewer", "open_move_dialog")
                                showEditMenu = false
                                showMoveDialog = true
                            },
                            containerColor = MaterialTheme.colorScheme.primaryContainer,
                            contentColor = MaterialTheme.colorScheme.onPrimaryContainer,
                            icon = { Icon(Icons.Default.Place, contentDescription = null) },
                            text = { Text(stringResource(R.string.wardrobe_move_to)) },
                        )
                    }
                    ExtendedFloatingActionButton(
                        onClick = {
                            Analytics.action("ItemViewer", "open_delete_dialog")
                            showEditMenu = false
                            // The grid hosts the cascade-aware confirm dialog (it can see the
                            // affected outfits/try-ons), so delegate rather than confirm here.
                            onDeleteItem(currentImage.driveId)
                        },
                        containerColor = MaterialTheme.colorScheme.error,
                        contentColor = MaterialTheme.colorScheme.onError,
                        icon = { Icon(Icons.Default.Delete, contentDescription = null) },
                        text = { Text(stringResource(R.string.action_delete)) },
                    )
                }
                FloatingActionButton(onClick = { showEditMenu = !showEditMenu }) {
                    Icon(
                        if (showEditMenu) Icons.Default.Close else Icons.Default.Edit,
                        contentDescription = stringResource(R.string.wardrobe_tag_edit),
                    )
                }
            }
        }
    }

    if (showTagEdit) {
        val currentImage = images[pagerState.currentPage]
        TagEditScreen(
            image = currentImage,
            allTagCategories = allTagCategories,
            onUpdate = { tags -> onUpdateTags(currentImage.driveId, tags) },
            onClassify = { onTagImage(currentImage.driveId) },
            isProcessing = currentImage.driveId == processingImageId,
            onDismiss = { showTagEdit = false },
        )
    }

    if (showFixCutoutBgDialog) {
        FixCutoutBgItemDialog(
            onConfirm = { actions ->
                showFixCutoutBgDialog = false
                onFixCutoutBg(currentImage.driveId, actions)
            },
            onDismiss = { showFixCutoutBgDialog = false },
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
                                    Analytics.action("ItemViewer", "confirm_move_item")
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

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun TagsOverlay(
    tags: ClothingTags?,
    hasOriginal: Boolean = false,
    showActions: Boolean = true,
    onTagImage: () -> Unit,
    onRemoveBackground: () -> Unit,
    onEditTags: () -> Unit,
    modifier: Modifier = Modifier,
) {
    if (tags == null && !showActions) return
    // Cap width so chips wrap downward instead of letting the overlay swell
    // across the screen on large font scales.
    val maxWidth = LocalConfiguration.current.screenWidthDp.dp * 0.6f
    Surface(
        modifier = modifier.widthIn(max = maxWidth),
        shape = MaterialTheme.shapes.medium,
        color = Color.Black.copy(alpha = 0.55f),
    ) {
        Column(
            modifier = Modifier.padding(horizontal = 10.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp),
            horizontalAlignment = Alignment.End,
        ) {
            if (tags != null) {
                FlowRow(horizontalArrangement = Arrangement.spacedBy(4.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    if (tags.type.isNotEmpty()) TagChip(tags.type.localizedTagValue())
                    if (tags.category.isNotEmpty()) TagChip(tags.category.localizedTagValue())
                }
                if (tags.uses.isNotEmpty()) {
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(4.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        tags.uses.forEach { TagChip(it.localizedTagValue()) }
                    }
                }
                if (tags.colors.isNotEmpty()) {
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(4.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        tags.colors.forEach { TagChip(it.localizedTagValue()) }
                    }
                }
            }
            val isOffline = LocalIsOffline.current
            if (showActions) FlowRow(horizontalArrangement = Arrangement.spacedBy(4.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
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

// Color swatch map: tag value → swatch color. Falls back to chipBg for unknown values.
// "multicolor" renders a rainbow gradient (handled at draw time).

private enum class SaveState { Saved, Saving, Edited }

@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
private fun TagEditScreen(
    image: DriveImage,
    allTagCategories: List<TagCategory>,
    onUpdate: (ClothingTags) -> Unit,
    onClassify: () -> Unit,
    isProcessing: Boolean,
    onDismiss: () -> Unit,
) {
    val parentContext = LocalContext.current
    val parentConfiguration = LocalConfiguration.current
    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(
            usePlatformDefaultWidth = false,
            decorFitsSystemWindows = false,
        ),
    ) {
        val dialogView = androidx.compose.ui.platform.LocalView.current
        androidx.compose.runtime.SideEffect {
            val window = (dialogView.parent as? DialogWindowProvider)?.window ?: return@SideEffect
            window.setLayout(
                android.view.ViewGroup.LayoutParams.MATCH_PARENT,
                android.view.ViewGroup.LayoutParams.MATCH_PARENT,
            )
            androidx.core.view.WindowCompat.setDecorFitsSystemWindows(window, false)
        }
        CompositionLocalProvider(
            LocalContext provides parentContext,
            LocalConfiguration provides parentConfiguration,
        ) {
            TagEditScreenContent(
                image = image,
                allTagCategories = allTagCategories,
                onUpdate = onUpdate,
                onClassify = onClassify,
                isProcessing = isProcessing,
                onDismiss = onDismiss,
            )
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun TagEditScreenContent(
    image: DriveImage,
    allTagCategories: List<TagCategory>,
    onUpdate: (ClothingTags) -> Unit,
    onClassify: () -> Unit,
    isProcessing: Boolean,
    onDismiss: () -> Unit,
) {
    BackHandler(onBack = onDismiss)
    val isOffline = LocalIsOffline.current
    val barInsets = LocalSystemBarsPadding.current

    var tags by remember(image.driveId) {
        mutableStateOf(image.tags ?: ClothingTags())
    }
    var saveState by remember { mutableStateOf(SaveState.Saved) }
    var openRow by remember { mutableStateOf<String?>("colors") }
    var pendingChange by remember { mutableStateOf(0) }
    // 0 = immediate (chip toggle), 1 = debounced text (free-text edit)
    var pendingMode by remember { mutableStateOf(0) }

    fun mutate(mode: Int, transform: (ClothingTags) -> ClothingTags) {
        tags = transform(tags)
        saveState = SaveState.Saving
        pendingMode = mode
        pendingChange += 1
    }

    LaunchedEffect(pendingChange) {
        if (pendingChange == 0) return@LaunchedEffect
        kotlinx.coroutines.delay(if (pendingMode == 1) 600L else 300L)
        runCatching { onUpdate(tags) }
            .onSuccess {
                kotlinx.coroutines.delay(400L) // min display so the pill doesn't flicker
                saveState = SaveState.Saved
            }
            .onFailure { saveState = SaveState.Edited }
    }

    fun suggestions(label: String, presets: List<String>): List<String> {
        val fromWardrobe = allTagCategories.find { it.label == label }?.tags.orEmpty()
        return (presets + fromWardrobe).distinct().sorted()
    }

    val scheme = MaterialTheme.colorScheme
    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(scheme.background),
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(top = barInsets.calculateTopPadding())
                .padding(bottom = barInsets.calculateBottomPadding())
                .imePadding(),
        ) {
            // App header
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(start = 4.dp, end = 12.dp, top = 4.dp, bottom = 6.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                IconButton(onClick = onDismiss, modifier = Modifier.size(40.dp)) {
                    Icon(
                        androidx.compose.material.icons.Icons.AutoMirrored.Filled.ArrowBack,
                        contentDescription = stringResource(R.string.action_cancel),
                        tint = scheme.onSurface,
                    )
                }
                Text(
                    stringResource(R.string.wardrobe_tag_sheet_title),
                    fontSize = 15.sp,
                    fontWeight = FontWeight.Bold,
                    color = scheme.onSurface,
                    modifier = Modifier.weight(1f),
                )
                SaveIndicator(state = saveState)
            }

            Column(
                modifier = Modifier
                    .weight(1f)
                    .verticalScroll(rememberScrollState())
                    .padding(horizontal = 14.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                IdentityCard(
                    image = image,
                    tags = tags,
                    isOffline = isOffline,
                    isProcessing = isProcessing,
                    onUpdateText = { newTags -> mutate(1) { newTags } },
                    onClassify = onClassify,
                )

                TagsTableCard(
                    tags = tags,
                    openRow = openRow,
                    onToggleRow = { key -> openRow = if (openRow == key) null else key },
                    onToggleValue = { setter ->
                        mutate(0) { setter(it) }
                    },
                    suggest = ::suggestions,
                )

                Spacer(Modifier.height(8.dp))
            }
        }

        if (isProcessing) {
            AiProcessingOverlay(modifier = Modifier.fillMaxSize())
        }
    }
}

@Composable
private fun SaveIndicator(state: SaveState) {
    val scheme = MaterialTheme.colorScheme
    val icon = when (state) {
        SaveState.Saved  -> androidx.compose.material.icons.Icons.Default.CheckCircle
        SaveState.Saving -> androidx.compose.material.icons.Icons.Default.Refresh
        SaveState.Edited -> androidx.compose.material.icons.Icons.Default.Info
    }
    val labelRes = when (state) {
        SaveState.Saved  -> R.string.wardrobe_tag_saved
        SaveState.Saving -> R.string.wardrobe_tag_saving
        SaveState.Edited -> R.string.wardrobe_tag_unsaved
    }
    val fg = if (state == SaveState.Saved) scheme.primary else scheme.onSurfaceVariant
    val bg = if (state == SaveState.Saved) scheme.primary.copy(alpha = 0.12f) else scheme.surfaceVariant
    Surface(shape = RoundedCornerShape(999.dp), color = bg) {
        Row(
            modifier = Modifier.padding(horizontal = 10.dp, vertical = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(5.dp),
        ) {
            Icon(icon, contentDescription = null, tint = fg, modifier = Modifier.size(12.dp))
            Text(stringResource(labelRes), fontSize = 11.sp, fontWeight = FontWeight.SemiBold, color = fg)
        }
    }
}

@Composable
private fun IdentityCard(
    image: DriveImage,
    tags: ClothingTags,
    isOffline: Boolean,
    isProcessing: Boolean,
    onUpdateText: (ClothingTags) -> Unit,
    onClassify: () -> Unit,
) {
    val scheme = MaterialTheme.colorScheme
    var editingName by remember { mutableStateOf(false) }
    var editingType by remember { mutableStateOf(false) }
    var editingCategory by remember { mutableStateOf(false) }
    Surface(
        shape = RoundedCornerShape(18.dp),
        color = scheme.surface,
        border = BorderStroke(1.dp, scheme.outlineVariant),
    ) {
        Column(modifier = Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                AsyncImage(
                    model = ImageRequest.Builder(LocalContext.current)
                        .data(image.localPath)
                        .memoryCacheKey("${image.driveId}_${image.version}")
                        .build(),
                    contentDescription = null,
                    contentScale = ContentScale.Crop,
                    modifier = Modifier
                        .size(96.dp)
                        .clip(RoundedCornerShape(14.dp))
                        .background(scheme.surfaceVariant)
                        .border(1.dp, scheme.outlineVariant, RoundedCornerShape(14.dp)),
                )
                Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.Center) {
                    EyebrowLabel(stringResource(R.string.tag_label))
                    if (editingName) {
                        OutlinedTextField(
                            value = tags.label,
                            onValueChange = { onUpdateText(tags.copy(label = it)) },
                            singleLine = true,
                            modifier = Modifier.fillMaxWidth(),
                            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
                            keyboardActions = KeyboardActions(onDone = { editingName = false }),
                        )
                    } else {
                        Text(
                            text = tags.label.ifBlank { stringResource(R.string.wardrobe_tag_not_set) },
                            fontSize = 15.sp,
                            fontWeight = FontWeight.Bold,
                            color = if (tags.label.isBlank()) scheme.onSurfaceVariant else scheme.onSurface,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable { editingName = true },
                        )
                    }
                    Spacer(Modifier.height(8.dp))
                    Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        SubField(
                            modifier = Modifier.weight(1f),
                            label = stringResource(R.string.tag_type),
                            value = tags.type,
                            editing = editingType,
                            onStartEdit = { editingType = true },
                            onChange = { onUpdateText(tags.copy(type = it)) },
                            onDone = { editingType = false },
                        )
                        SubField(
                            modifier = Modifier.weight(1f),
                            label = stringResource(R.string.tag_category),
                            value = tags.category.replaceFirstChar { it.uppercase() },
                            editing = editingCategory,
                            onStartEdit = { editingCategory = true },
                            onChange = { onUpdateText(tags.copy(category = it)) },
                            onDone = { editingCategory = false },
                        )
                    }
                }
            }

            // AI re-tag CTA
            val ctaEnabled = !isOffline && !isProcessing
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(12.dp))
                    .background(scheme.primary.copy(alpha = if (ctaEnabled) 0.10f else 0.05f))
                    .border(1.dp, scheme.primary.copy(alpha = 0.33f), RoundedCornerShape(12.dp))
                    .clickable(enabled = ctaEnabled) {
                        Analytics.action("TagEdit", "redetect_ai")
                        onClassify()
                    }
                    .padding(horizontal = 12.dp, vertical = 8.dp)
                    .graphicsLayer { alpha = if (ctaEnabled) 1f else 0.5f },
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                com.librelookai.billing.CostBadge(com.librelookai.gemini.GeminiActionId.CLASSIFY_CLOTHING)
                Icon(
                    androidx.compose.material.icons.Icons.Default.AutoAwesome,
                    contentDescription = null,
                    tint = scheme.primary,
                    modifier = Modifier.size(13.dp),
                )
                Text(
                    stringResource(R.string.wardrobe_tag_redetect_ai),
                    color = scheme.primary,
                    fontSize = 12.sp,
                    fontWeight = FontWeight.Bold,
                    modifier = Modifier.weight(1f),
                )
            }
        }
    }
}

@Composable
private fun SubField(
    modifier: Modifier,
    label: String,
    value: String,
    editing: Boolean,
    onStartEdit: () -> Unit,
    onChange: (String) -> Unit,
    onDone: () -> Unit,
) {
    val scheme = MaterialTheme.colorScheme
    Column(modifier = modifier) {
        EyebrowLabel(label, small = true)
        if (editing) {
            OutlinedTextField(
                value = value,
                onValueChange = onChange,
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
                keyboardActions = KeyboardActions(onDone = { onDone() }),
            )
        } else {
            Text(
                text = value.ifBlank { stringResource(R.string.wardrobe_tag_not_set) },
                fontSize = 12.sp,
                fontWeight = FontWeight.SemiBold,
                color = if (value.isBlank()) scheme.onSurfaceVariant else scheme.onSurface,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = 1.dp)
                    .clickable { onStartEdit() },
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

@Composable
private fun EyebrowLabel(text: String, small: Boolean = false) {
    Text(
        text = text.uppercase(),
        fontSize = if (small) 9.sp else 10.sp,
        fontWeight = FontWeight.Bold,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        letterSpacing = (if (small) 0.3 else 0.4).sp,
    )
}

private data class TagRowSpec(
    val key: String,
    val labelRes: Int,
    val sectionLabel: String,
    val presets: List<String>,
    val isColor: Boolean = false,
)

private val TAG_ROWS = listOf(
    TagRowSpec("colors",      R.string.tag_colors,      "Colors",      emptyList(),         isColor = true),
    TagRowSpec("uses",        R.string.tag_uses,        "Uses",        PRESET_USES),
    TagRowSpec("seasonality", R.string.tag_seasonality, "Seasonality", PRESET_SEASONALITY),
    TagRowSpec("aesthetic",   R.string.tag_aesthetic,   "Aesthetic",   PRESET_AESTHETIC),
    TagRowSpec("fit",         R.string.tag_fit,         "Fit",         PRESET_FIT),
    TagRowSpec("material",    R.string.tag_material,    "Material",    PRESET_MATERIAL),
    TagRowSpec("pattern",     R.string.tag_pattern,     "Pattern",     PRESET_PATTERN),
)

private fun ClothingTags.valuesFor(key: String): List<String> = when (key) {
    "colors"      -> colors
    "uses"        -> uses
    "seasonality" -> seasonality
    "aesthetic"   -> aesthetic
    "fit"         -> fit
    "material"    -> material
    "pattern"     -> pattern
    else          -> emptyList()
}

private fun ClothingTags.toggleValue(key: String, value: String): ClothingTags {
    val current = valuesFor(key)
    val next = if (value in current) current - value else current + value
    return when (key) {
        "colors"      -> copy(colors = next)
        "uses"        -> copy(uses = next)
        "seasonality" -> copy(seasonality = next)
        "aesthetic"   -> copy(aesthetic = next)
        "fit"         -> copy(fit = next)
        "material"    -> copy(material = next)
        "pattern"     -> copy(pattern = next)
        else          -> this
    }
}

private fun ClothingTags.addCustom(key: String, value: String): ClothingTags {
    val trimmed = value.trim()
    if (trimmed.isEmpty()) return this
    val current = valuesFor(key)
    if (trimmed in current) return this
    val next = current + trimmed
    return when (key) {
        "colors"      -> copy(colors = next)
        "uses"        -> copy(uses = next)
        "seasonality" -> copy(seasonality = next)
        "aesthetic"   -> copy(aesthetic = next)
        "fit"         -> copy(fit = next)
        "material"    -> copy(material = next)
        "pattern"     -> copy(pattern = next)
        else          -> this
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun TagsTableCard(
    tags: ClothingTags,
    openRow: String?,
    onToggleRow: (String) -> Unit,
    onToggleValue: ((ClothingTags) -> ClothingTags) -> Unit,
    suggest: (String, List<String>) -> List<String>,
) {
    val scheme = MaterialTheme.colorScheme
    Surface(
        shape = RoundedCornerShape(18.dp),
        color = scheme.surface,
        border = BorderStroke(1.dp, scheme.outlineVariant),
        modifier = Modifier.clip(RoundedCornerShape(18.dp)),
    ) {
        Column {
            TAG_ROWS.forEachIndexed { idx, spec ->
                val isOpen = openRow == spec.key
                val values = tags.valuesFor(spec.key)
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .background(if (isOpen) scheme.surfaceVariant else Color.Transparent)
                        .then(Modifier.animateContentSize(
                            animationSpec = androidx.compose.animation.core.tween(220),
                        )),
                ) {
                    TagRowCollapsed(
                        labelRes = spec.labelRes,
                        values = values,
                        isColor = spec.isColor,
                        isOpen = isOpen,
                        onClick = { onToggleRow(spec.key) },
                    )
                    if (isOpen) {
                        val merged = suggest(spec.sectionLabel, spec.presets)
                        if (spec.isColor) {
                            ColorDrawer(
                                active = values,
                                options = merged.ifEmpty { FilterColorKeys },
                                onToggle = { v -> onToggleValue { it.toggleValue(spec.key, v) } },
                                onAddCustom = { v -> onToggleValue { it.addCustom(spec.key, v) } },
                            )
                        } else {
                            ChipDrawer(
                                active = values,
                                options = merged,
                                onToggle = { v -> onToggleValue { it.toggleValue(spec.key, v) } },
                                onAddCustom = { v -> onToggleValue { it.addCustom(spec.key, v) } },
                            )
                        }
                    }
                }
                if (idx != TAG_ROWS.lastIndex) {
                    HorizontalDivider(color = scheme.outlineVariant, thickness = 1.dp)
                }
            }
        }
    }
}

@Composable
private fun TagRowCollapsed(
    labelRes: Int,
    values: List<String>,
    isColor: Boolean,
    isOpen: Boolean,
    onClick: () -> Unit,
) {
    val scheme = MaterialTheme.colorScheme
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(horizontal = 14.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Text(
            stringResource(labelRes),
            modifier = Modifier.width(88.dp),
            fontSize = 12.sp,
            fontWeight = FontWeight.Bold,
            color = scheme.onSurface,
        )
        Box(modifier = Modifier.weight(1f)) {
            when {
                values.isEmpty() -> Text(
                    stringResource(R.string.wardrobe_tag_not_set),
                    fontSize = 12.sp,
                    color = scheme.onSurfaceVariant,
                    style = MaterialTheme.typography.bodySmall.copy(fontStyle = androidx.compose.ui.text.font.FontStyle.Italic),
                )
                isColor -> ColorSummary(values)
                else -> {
                    val localized = values.take(3).map { it.localizedTagValue() }
                    val shown = localized.joinToString(", ")
                    val rest = values.size - 3
                    val text = if (rest > 0) "$shown +$rest" else shown
                    Text(
                        text,
                        fontSize = 12.sp,
                        fontWeight = FontWeight.SemiBold,
                        color = scheme.onSurface,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
        }
        Icon(
            if (isOpen) androidx.compose.material.icons.Icons.Default.KeyboardArrowUp
            else androidx.compose.material.icons.Icons.Default.KeyboardArrowDown,
            contentDescription = null,
            tint = scheme.onSurfaceVariant,
            modifier = Modifier.size(16.dp),
        )
    }
}

@Composable
private fun ColorSummary(values: List<String>) {
    val scheme = MaterialTheme.colorScheme
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        Row {
            values.take(5).forEachIndexed { i, v ->
                Box(
                    modifier = Modifier
                        .offset(x = ((-6) * i).dp)
                        .size(18.dp)
                        .clip(RoundedCornerShape(999.dp))
                        .background(colorFor(v) ?: scheme.surfaceVariant)
                        .border(2.dp, scheme.surface, RoundedCornerShape(999.dp)),
                )
            }
        }
        val localized = values.take(2).map { it.localizedTagValue() }
        val named = localized.joinToString(", ")
        val rest = values.size - 2
        val text = if (rest > 0) "$named +$rest" else named
        Text(
            text,
            fontSize = 12.sp,
            fontWeight = FontWeight.SemiBold,
            color = scheme.onSurface,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

private fun colorFor(name: String): Color? = colorSwatchOrNull(name.normalizeColor()) ?: colorSwatchOrNull(name)

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun ColorDrawer(
    active: List<String>,
    options: List<String>,
    onToggle: (String) -> Unit,
    onAddCustom: (String) -> Unit,
) {
    val scheme = MaterialTheme.colorScheme
    val all = (FilterColorKeys + options).distinct()
    Column(modifier = Modifier.padding(start = 14.dp, end = 14.dp, top = 4.dp, bottom = 14.dp)) {
        FlowRow(
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
            maxItemsInEachRow = 7,
        ) {
            all.forEach { value ->
                val isActive = value in active
                val swatch = colorFor(value) ?: scheme.surfaceVariant
                Column(
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(3.dp),
                    modifier = Modifier.width(36.dp),
                ) {
                    Box(
                        modifier = Modifier
                            .size(32.dp)
                            .clip(RoundedCornerShape(999.dp))
                            .background(swatch)
                            .border(
                                width = if (isActive) 2.5.dp else 1.dp,
                                color = if (isActive) scheme.primary else scheme.outlineVariant,
                                shape = RoundedCornerShape(999.dp),
                            )
                            .clickable { onToggle(value) },
                        contentAlignment = Alignment.Center,
                    ) {
                        if (isActive) {
                            val onSwatch = if (value == "white" || value == "cream") Color(0xFF222222) else Color.White
                            Icon(
                                androidx.compose.material.icons.Icons.Default.Check,
                                contentDescription = null,
                                tint = onSwatch,
                                modifier = Modifier.size(14.dp),
                            )
                        }
                    }
                    Text(
                        text = value.localizedTagValue(),
                        fontSize = 9.sp,
                        fontWeight = if (isActive) FontWeight.Bold else FontWeight.Medium,
                        color = if (isActive) scheme.primary else scheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
        }
        AddCustomField(onAdd = onAddCustom)
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun ChipDrawer(
    active: List<String>,
    options: List<String>,
    onToggle: (String) -> Unit,
    onAddCustom: (String) -> Unit,
) {
    val scheme = MaterialTheme.colorScheme
    val all = (active + options).distinct()
    Column(modifier = Modifier.padding(start = 14.dp, end = 14.dp, top = 4.dp, bottom = 14.dp)) {
        FlowRow(
            horizontalArrangement = Arrangement.spacedBy(5.dp),
            verticalArrangement = Arrangement.spacedBy(5.dp),
        ) {
            all.forEach { value ->
                val isActive = value in active
                Surface(
                    shape = RoundedCornerShape(999.dp),
                    color = if (isActive) scheme.primary else scheme.surface,
                    border = BorderStroke(
                        width = if (isActive) 1.5.dp else 1.dp,
                        color = if (isActive) scheme.primary else scheme.outlineVariant,
                    ),
                    onClick = { onToggle(value) },
                ) {
                    Text(
                        text = value.localizedTagValue(),
                        modifier = Modifier.padding(horizontal = 10.dp, vertical = 5.dp),
                        fontSize = 12.sp,
                        fontWeight = FontWeight.SemiBold,
                        color = if (isActive) scheme.onPrimary else scheme.onSurface,
                    )
                }
            }
        }
        AddCustomField(onAdd = onAddCustom)
    }
}

@Composable
private fun AddCustomField(onAdd: (String) -> Unit) {
    var expanded by remember { mutableStateOf(false) }
    var text by remember { mutableStateOf("") }
    val scheme = MaterialTheme.colorScheme
    Spacer(Modifier.height(8.dp))
    if (!expanded) {
        Surface(
            shape = RoundedCornerShape(999.dp),
            color = Color.Transparent,
            border = BorderStroke(1.dp, scheme.outlineVariant),
            onClick = { expanded = true },
        ) {
            Row(
                modifier = Modifier.padding(horizontal = 10.dp, vertical = 5.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                Icon(
                    androidx.compose.material.icons.Icons.Default.Add,
                    contentDescription = null,
                    tint = scheme.onSurfaceVariant,
                    modifier = Modifier.size(12.dp),
                )
                Text(
                    stringResource(R.string.tag_add_custom),
                    fontSize = 12.sp,
                    fontWeight = FontWeight.SemiBold,
                    color = scheme.onSurfaceVariant,
                )
            }
        }
    } else {
        OutlinedTextField(
            value = text,
            onValueChange = { text = it },
            placeholder = { Text(stringResource(R.string.tag_add_custom)) },
            singleLine = true,
            modifier = Modifier.fillMaxWidth(),
            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
            keyboardActions = KeyboardActions(onDone = {
                if (text.isNotBlank()) onAdd(text.trim())
                text = ""
                expanded = false
            }),
            trailingIcon = {
                IconButton(onClick = {
                    if (text.isNotBlank()) onAdd(text.trim())
                    text = ""
                    expanded = false
                }) { Icon(androidx.compose.material.icons.Icons.Default.Check, contentDescription = null) }
            },
        )
    }
}

@Composable
private fun DetailTagChip(label: String) {
    Surface(
        shape = MaterialTheme.shapes.extraSmall,
        color = MaterialTheme.colorScheme.secondaryContainer,
    ) {
        Text(
            text = label,
            modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp),
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSecondaryContainer,
        )
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

// ---------- Hide-tags chip (shared by item + outfit fullscreen viewers) ----------

@Composable
internal fun HideTagsChip(
    hideTags: Boolean,
    onToggle: () -> Unit,
) {
    androidx.compose.material3.Surface(
        onClick = onToggle,
        shape = RoundedCornerShape(20.dp),
        color = MaterialTheme.colorScheme.surface.copy(alpha = 0.85f),
        contentColor = MaterialTheme.colorScheme.onBackground,
        tonalElevation = 2.dp,
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            Icon(
                imageVector = if (hideTags) Icons.Default.Visibility else Icons.Default.VisibilityOff,
                contentDescription = null,
                modifier = Modifier.size(16.dp),
            )
            Text(
                stringResource(if (hideTags) R.string.viewer_show_tags else R.string.viewer_hide_tags),
                style = MaterialTheme.typography.labelMedium,
            )
        }
    }
}

// ---------- Sort button ----------

@Composable
internal fun SortButton(
    sortBy: SortOption,
    onSortChanged: (SortOption) -> Unit,
    modifier: Modifier = Modifier,
) {
    var expanded by remember { mutableStateOf(false) }
    // DropdownMenu renders in its own popup window; re-provide LocalContext/LocalConfiguration
    // so stringResource() honors the in-app language toggle.
    val parentContext = LocalContext.current
    val parentConfiguration = LocalConfiguration.current
    Box(modifier = modifier) {
        IconButton(onClick = { expanded = true }) {
            Icon(Icons.AutoMirrored.Filled.Sort, contentDescription = stringResource(R.string.wardrobe_sort))
        }
        DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
            CompositionLocalProvider(
                LocalContext provides parentContext,
                LocalConfiguration provides parentConfiguration,
            ) {
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
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun DuplicateCheckSheet(
    check: DuplicateCheck,
    debugSimilarityPreview: Boolean,
    onConfirm: () -> Unit,
    onCancel: () -> Unit,
    onShowMatchInWardrobe: (DriveImage) -> Unit,
    onAddQueryToShoppingList: (queryPath: String) -> Unit,
) {
    val context = LocalContext.current
    // Capture parent locale-aware context/configuration: ModalBottomSheet renders in its own
    // window, so without this re-provide stringResource() would fall back to the system locale
    // and ignore the in-app language toggle.
    val parentContext = LocalContext.current
    val parentConfiguration = LocalConfiguration.current
    val shopMatches = remember(check.matches) {
        check.matches.map { ShopMatch(image = it.image, score = it.score) }
    }
    var previewIndex by remember { mutableStateOf<Int?>(null) }

    ModalBottomSheet(
        onDismissRequest = onCancel,
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

            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(160.dp)
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

            Column(
                modifier = Modifier.heightIn(max = 360.dp).verticalScroll(rememberScrollState()),
            ) {
                shopMatches.forEachIndexed { idx, match ->
                    MatchRow(match = match, onClick = { previewIndex = idx })
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

    previewIndex?.let { idx ->
        if (idx in shopMatches.indices) {
            MatchPreviewDialog(
                matches = shopMatches,
                initialIndex = idx,
                queryRawPath = check.rawFilePath,
                queryProcessedPath = check.processedPath,
                querySegmented = check.segmented,
                queryHist = check.hist,
                queryVec = check.vec,
                queryPHash = check.pHash,
                showDebug = debugSimilarityPreview,
                onShowInWardrobe = { image ->
                    previewIndex = null
                    onShowMatchInWardrobe(image)
                },
                onAddToShoppingList = {
                    previewIndex = null
                    onAddQueryToShoppingList(check.rawFilePath)
                },
                canAddToShoppingList = true,
                showActions = false,
                onDismiss = { previewIndex = null },
            )
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
    onAddToShoppingList: (queryPath: String?) -> Unit,
    onSearchAgain: (String) -> Unit,
    onDismiss: () -> Unit,
) {
    val isTextQuery = findByPhoto.textQuery != null
    val context = LocalContext.current
    // ModalBottomSheet hosts its content in a separate sub-composition that doesn't always
    // propagate the activity's locale-overridden LocalContext/LocalConfiguration. Capture both
    // out here and re-provide them inside the sheet so stringResource follows the app language.
    val parentContext = LocalContext.current
    val parentConfiguration = LocalConfiguration.current
    val shopMatches = remember(findByPhoto.matches) {
        findByPhoto.matches.map { ShopMatch(image = it.image, score = it.score) }
    }
    var previewIndex by remember { mutableStateOf<Int?>(null) }
    var queryDraft by remember(findByPhoto.textQuery) {
        mutableStateOf(findByPhoto.textQuery.orEmpty())
    }

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
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text(
                stringResource(
                    if (isTextQuery) R.string.wardrobe_search_text_results
                    else R.string.wardrobe_find_by_photo
                ),
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold,
            )

            if (isTextQuery) {
                val keyboard = LocalSoftwareKeyboardController.current
                OutlinedTextField(
                    value = queryDraft,
                    onValueChange = { queryDraft = it },
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true,
                    placeholder = { Text(stringResource(R.string.wardrobe_search_placeholder)) },
                    keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                    keyboardActions = KeyboardActions(onSearch = {
                        val q = queryDraft.trim()
                        if (q.isNotEmpty()) {
                            keyboard?.hide()
                            onSearchAgain(q)
                        }
                    }),
                    trailingIcon = {
                        IconButton(
                            onClick = {
                                val q = queryDraft.trim()
                                if (q.isNotEmpty()) {
                                    keyboard?.hide()
                                    onSearchAgain(q)
                                }
                            },
                            enabled = queryDraft.trim().isNotEmpty(),
                        ) {
                            Icon(Icons.Default.Search, contentDescription = stringResource(R.string.wardrobe_search_text_results))
                        }
                    },
                )
            } else {
                val qPath = findByPhoto.queryPath
                if (qPath != null) {
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(160.dp)
                            .background(MaterialTheme.colorScheme.surfaceVariant, MaterialTheme.shapes.small),
                    ) {
                        AsyncImage(
                            model = ImageRequest.Builder(context)
                                .data(java.io.File(qPath))
                                .crossfade(true)
                                .build(),
                            contentDescription = stringResource(R.string.shop_your_photo),
                            contentScale = androidx.compose.ui.layout.ContentScale.Fit,
                            modifier = Modifier.fillMaxSize(),
                        )
                    }
                }
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
                    if (!isTextQuery && findByPhoto.queryPath != null) {
                        Button(
                            onClick = { onAddToShoppingList(findByPhoto.queryPath) },
                            modifier = Modifier.fillMaxWidth(),
                        ) {
                            Icon(Icons.Default.ShoppingBag, contentDescription = null, modifier = Modifier.size(18.dp))
                            Spacer(Modifier.width(8.dp))
                            Text(stringResource(R.string.shop_add_to_shopping_list))
                        }
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
    }

    previewIndex?.let { idx ->
        if (idx in shopMatches.indices) {
            MatchPreviewDialog(
                matches = shopMatches,
                initialIndex = idx,
                queryRawPath = findByPhoto.queryPath,
                queryProcessedPath = findByPhoto.processedPath,
                querySegmented = findByPhoto.segmented,
                queryHist = findByPhoto.hist,
                queryVec = findByPhoto.vec,
                queryPHash = findByPhoto.pHash,
                showDebug = debugSimilarityPreview,
                onShowInWardrobe = { image ->
                    previewIndex = null
                    onPickMatch(image)
                },
                onAddToShoppingList = {},
                canAddToShoppingList = false,
                onDismiss = { previewIndex = null },
            )
        }
    }
}

@Composable
private fun FixCutoutBgItemDialog(
    onConfirm: (CutoutFixActions) -> Unit,
    onDismiss: () -> Unit,
) {
    var clearAlpha by remember { mutableStateOf(false) }
    var blackToAlpha by remember { mutableStateOf(true) }
    var despillGreen by remember { mutableStateOf(true) }
    var feather by remember { mutableStateOf(true) }
    var tightCrop by remember { mutableStateOf(true) }
    val any = clearAlpha || blackToAlpha || despillGreen || feather || tightCrop
    // AlertDialog renders in its own popup window; re-provide LocalContext/LocalConfiguration
    // so stringResource() honors the in-app language toggle.
    val parentContext = LocalContext.current
    val parentConfiguration = LocalConfiguration.current
    AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            CompositionLocalProvider(
                LocalContext provides parentContext,
                LocalConfiguration provides parentConfiguration,
            ) { Text(stringResource(R.string.wardrobe_fix_cutout_bg)) }
        },
        text = {
            CompositionLocalProvider(
                LocalContext provides parentContext,
                LocalConfiguration provides parentConfiguration,
            ) {
                Column {
                    @Composable
                    fun Row(labelRes: Int, checked: Boolean, onChange: (Boolean) -> Unit) {
                        androidx.compose.foundation.layout.Row(
                            modifier = Modifier.fillMaxWidth().padding(vertical = 2.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Text(stringResource(labelRes), modifier = Modifier.weight(1f))
                            Switch(checked = checked, onCheckedChange = onChange)
                        }
                    }
                    Row(R.string.settings_cutoutfix_action_clearalpha, clearAlpha) { clearAlpha = it }
                    Row(R.string.settings_cutoutfix_action_blackbg, blackToAlpha) { blackToAlpha = it }
                    Row(R.string.settings_cutoutfix_action_greenhalo, despillGreen) { despillGreen = it }
                    Row(R.string.settings_cutoutfix_action_feather, feather) { feather = it }
                    Row(R.string.settings_cutoutfix_action_tightcrop, tightCrop) { tightCrop = it }
                }
            }
        },
        confirmButton = {
            CompositionLocalProvider(
                LocalContext provides parentContext,
                LocalConfiguration provides parentConfiguration,
            ) {
                TextButton(
                    enabled = any,
                    onClick = {
                        onConfirm(CutoutFixActions(
                            blackToAlpha = blackToAlpha,
                            despillGreen = despillGreen,
                            feather = feather,
                            tightCrop = tightCrop,
                            clearAlpha = clearAlpha,
                        ))
                    },
                ) { Text(stringResource(R.string.action_ok)) }
            }
        },
        dismissButton = {
            CompositionLocalProvider(
                LocalContext provides parentContext,
                LocalConfiguration provides parentConfiguration,
            ) {
                TextButton(onClick = onDismiss) { Text(stringResource(R.string.action_cancel)) }
            }
        },
    )
}

