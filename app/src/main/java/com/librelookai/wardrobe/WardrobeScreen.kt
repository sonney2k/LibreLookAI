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
import com.librelookai.core.designsystem.R as DsR
import com.librelookai.data.model.Location
import com.librelookai.data.model.Outfit
import com.librelookai.data.model.OutfitEvent
import com.librelookai.data.model.TryOn
import com.librelookai.settings.UserPreferences
import com.librelookai.settings.DestructiveAction
import com.librelookai.settings.DestructiveConfirmDialog
import com.librelookai.util.Analytics

@Composable
fun WardrobeScreen(
    viewModel: WardrobeViewModel = viewModel(),
    // Cross-feature data + callbacks threaded from the shell (feature modules never import
    // another feature's VM — § 1 slice 6 narrowing precedent). WardrobeScreen is cross-cutting
    // because deleting a wardrobe item cascades into outfits / try-ons.
    outfitEvents: List<OutfitEvent> = emptyList(),
    outfits: List<Outfit> = emptyList(),
    onDeleteOutfitsByIds: (List<String>) -> Unit = {},
    tryOnHistory: List<TryOn> = emptyList(),
    onDeleteTryOns: (List<TryOn>) -> Unit = {},
    onImportQuery: (String) -> Unit = {},
    preferences: UserPreferences = UserPreferences(),
    locations: List<Location> = emptyList(),
    activeLocationId: String = "",
    locationLoading: Boolean = false,
    locationError: String? = null,
    onSetActiveLocation: (String) -> Unit = {},
    creditsBalance: Int = 0,
    onCreateOutfitFromSelection: (Set<String>) -> Unit = {},
    onTryOnSelection: (Set<String>) -> Unit = {},
    onSuggestReplacements: (Set<String>) -> Unit = {},
    canTryOn: Boolean = false,
    onOpenItemViewer: (List<String>, String) -> Unit = { _, _ -> },
    onSettingsClick: () -> Unit = {},
    modifier: Modifier = Modifier,
) {
    val state         by viewModel.state.collectAsState()
    // Progress flows read straight off the owning singletons (§ 5 slice 7 prune).
    val ingestion     by viewModel.ingestionProgress.collectAsState()
    val retag         by viewModel.retagProgress.collectAsState()
    val convert       by viewModel.convertProgress.collectAsState()
    val context = LocalContext.current

    // Power-feature bulk maintenance ops (re-remove backgrounds / fix leftover cutout pixels),
    // surfaced behind FeatureFlags.powerFeatures in the Wardrobe header overflow menu. Reuses the
    // same friendly confirm + credit-cost dialog as the Settings ▸ Advanced "Fix AI mistakes" rows.
    var pendingMaintenance by remember { mutableStateOf<DestructiveAction?>(null) }
    pendingMaintenance?.let { action ->
        DestructiveConfirmDialog(
            action = action,
            itemCount = state.images.size,
            balance = creditsBalance,
            onConfirm = {
                when (action) {
                    DestructiveAction.REMOVE_BG -> viewModel.removeAllBackgrounds()
                    DestructiveAction.CUTOUT_FIX ->
                        viewModel.startCutoutBgFixScan(locations.map { it.folderId })
                    else -> Unit
                }
                pendingMaintenance = null
            },
            onBuyCredits = { pendingMaintenance = null; onSettingsClick() },
            onDismiss = { pendingMaintenance = null },
        )
    }

    // driveId → number of calendar wear events that include this item
    val popularityMap = remember(outfitEvents, outfits) {
        val outfitWearCount = outfitEvents.groupingBy { it.outfitId }.eachCount()
        val itemCount = mutableMapOf<String, Int>()
        outfits.forEach { style ->
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
        Analytics.action("Wardrobe", "open_gallery", mapOf("locations" to locations.size.toString()))
        if (locations.size >= 2) showGalleryClosetPicker = true
        else galleryLauncher.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly))
    }
    val openUrlImport: () -> Unit = {
        Analytics.action("Wardrobe", "open_url_import_dialog")
        showUrlImportDialog = true
    }

    ingestion.duplicateCheck?.let { check ->
        DuplicateCheckSheet(
            check = check,
            debugSimilarityPreview = preferences.debugSimilarityPreview,
            onConfirm = viewModel::confirmDuplicateImport,
            onCancel = viewModel::cancelDuplicateImport,
            onShowMatchInWardrobe = { image ->
                viewModel.cancelDuplicateImport()
                viewModel.requestScrollToImage(image.driveId)
            },
            onAddQueryToShoppingList = { queryPath ->
                onImportQuery(queryPath)
                viewModel.cancelDuplicateImport()
            },
        )
    }

    when (state.view) {
        WardrobeView.GRID -> GridContent(
            state = state,
            ingestion = ingestion,
            retag = retag,
            convert = convert,
            popularityMap = popularityMap,
            locations = locations,
            activeLocationId = activeLocationId,
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
            onToggleSelection = viewModel::toggleSelection,
            onSelectAll = viewModel::selectAll,
            onClearSelection = viewModel::clearSelection,
            onDeleteItems = viewModel::deleteItems,
            outfits = outfits,
            tryOns = tryOnHistory,
            onDeleteOutfits = { ids -> onDeleteOutfitsByIds(ids) },
            onDeleteTryOns = { tryOns -> onDeleteTryOns(tryOns) },
            onMoveToLocation = viewModel::moveItemsToLocation,
            onSetActiveLocation = onSetActiveLocation,
            onCreateOutfitFromSelection = onCreateOutfitFromSelection,
            onTryOnSelection = onTryOnSelection,
            onSuggestReplacements = onSuggestReplacements,
            canTryOn = canTryOn,
            onDismissBatteryExemption = viewModel::dismissBatteryExemptionWarning,
            onSetImportTarget = viewModel::setDefaultImportFolderId,
            processingImageId = state.processingImageId,
            isLocationLoading = locationLoading,
            locationError = locationError,
            onOpenItemViewer = onOpenItemViewer,
            onSettingsClick = onSettingsClick,
            onBulkRemoveBackgrounds = { pendingMaintenance = DestructiveAction.REMOVE_BG },
            onBulkFixCutoutBg = { pendingMaintenance = DestructiveAction.CUTOUT_FIX },
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
            scrollEvents = viewModel.events,
            onAddMatchToShoppingList = onImportQuery,
            debugSimilarityPreview = preferences.debugSimilarityPreview,
            modifier = modifier,
        )
        WardrobeView.CAPTURE -> CaptureScreen(
            onPhotoTaken = viewModel::uploadPhoto,
            onCancel = viewModel::closeCapture,
            locations = locations,
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
            locations = locations,
            initialFolderId = state.importTargetFolderId ?: locations.firstOrNull()?.folderId,
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
                    selectedFolderId?.let { viewModel.setDefaultImportFolderId(it) }
                    showGalleryClosetPicker = false
                    galleryLauncher.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly))
                }) { Text(stringResource(DsR.string.action_continue)) }
            },
            dismissButton = {
                TextButton(onClick = { showGalleryClosetPicker = false }) {
                    Text(stringResource(DsR.string.action_cancel))
                }
            },
        )
    }
}

