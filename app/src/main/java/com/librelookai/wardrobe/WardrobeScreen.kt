package com.librelookai.wardrobe

import android.Manifest
import android.content.pm.PackageManager
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RadioButton
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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.lifecycle.viewmodel.compose.viewModel
import com.librelookai.R
import com.librelookai.outfit.OutfitEventsViewModel
import com.librelookai.outfit.OutfitsViewModel
import com.librelookai.settings.ProfileViewModel
import com.librelookai.shopping.ShoppingClosetViewModel
import com.librelookai.tryon.TryOnViewModel
import com.librelookai.util.Analytics

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

