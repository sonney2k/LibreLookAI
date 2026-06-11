package com.librelookai.shopping

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ShoppingBag
import androidx.compose.material3.Tab
import androidx.compose.material3.TabRow
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.lifecycle.viewmodel.compose.viewModel
import com.librelookai.AppScreenHeader
import com.librelookai.LocationButton
import com.librelookai.R
import com.librelookai.settings.ProfileViewModel
import com.librelookai.util.Analytics
import com.librelookai.wardrobe.CaptureScreen
import com.librelookai.wardrobe.DriveImage
import com.librelookai.wardrobe.LocationViewModel
import com.librelookai.wardrobe.UrlImportPicker
import com.librelookai.wardrobe.WardrobeGapViewModel
import com.librelookai.wardrobe.WardrobeViewModel

@Composable
fun ShoppingHelperScreen(
    shoppingViewModel: ShoppingHelperViewModel = viewModel(),
    shoppingClosetViewModel: ShoppingClosetViewModel = viewModel(),
    wardrobeViewModel: WardrobeViewModel = viewModel(),
    gapViewModel: WardrobeGapViewModel = viewModel(),
    profileViewModel: ProfileViewModel = viewModel(),
    locationViewModel: LocationViewModel = viewModel(),
    onSettingsClick: () -> Unit = {},
    /** Switch to the wardrobe tab and scroll/highlight the picked match. */
    onShowInWardrobe: (DriveImage) -> Unit = {},
    onCreateOutfitFromSelection: (Set<String>) -> Unit = {},
    onTryOnSelection: (Set<String>) -> Unit = {},
    canTryOn: Boolean = false,
    onOpenItemViewer: (List<String>, String) -> Unit = { _, _ -> },
    navResetTick: Int = 0,
    modifier: Modifier = Modifier,
) {
    val shoppingState by shoppingViewModel.state.collectAsState()
    val wardrobeState by wardrobeViewModel.state.collectAsState()
    val profileStateTop by profileViewModel.state.collectAsState()
    val locationState by locationViewModel.state.collectAsState()
    val shoppingClosetStateTop by shoppingClosetViewModel.state.collectAsState()

    // The URL-import picker (candidate grid + WebView fallback) is hosted at the screen root so
    // it survives the camera early-returns that early-`return` below.
    shoppingClosetStateTop.urlImportPicker?.let { picker ->
        UrlImportPicker(
            pageUrl = picker.pageUrl,
            candidates = picker.candidates,
            isDownloading = picker.isDownloading,
            onPick = shoppingClosetViewModel::confirmUrlImportPick,
            onDismiss = shoppingClosetViewModel::cancelUrlImport,
        )
    }

    // Hoisted above the camera early-returns so the active tab survives across capture —
    // otherwise the rememberSaveable slot is never visited and resets to 0 on return.
    var selectedTab by rememberSaveable { mutableIntStateOf(0) }
    LaunchedEffect(navResetTick) { selectedTab = 0 }
    var isClosetCapturing by rememberSaveable { mutableStateOf(false) }
    var showClosetUrlDialog by remember { mutableStateOf(false) }
    val closetGalleryLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.PickMultipleVisualMedia(),
    ) { uris -> if (uris.isNotEmpty()) shoppingClosetViewModel.addFromGallery(uris) }

    // Similarity Finder takes the camera over the whole screen. The closet selector is hidden:
    // similarity search reads from every closet and never imports.
    if (shoppingState.isCapturing) {
        CaptureScreen(
            onPhotoTaken = { file ->
                shoppingViewModel.onCapturedFile(
                    file,
                    wardrobeState.allLocationImages,
                    debug = profileStateTop.preferences.debugSimilarityPreview,
                )
            },
            onCancel = shoppingViewModel::cancelCapture,
            locations = emptyList(),
            importTargetFolderId = null,
            onSetImportTarget = {},
            showCenterCrosshair = true,
            modifier = modifier,
        )
        return
    }

    // Shopping List camera capture (separate flag — local to this screen).
    if (isClosetCapturing) {
        CaptureScreen(
            onPhotoTaken = { file ->
                isClosetCapturing = false
                shoppingClosetViewModel.addFromCamera(file)
            },
            onCancel = { isClosetCapturing = false },
            locations = emptyList(),
            importTargetFolderId = null,
            onSetImportTarget = {},
            onOpenGallery = {
                closetGalleryLauncher.launch(
                    PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly),
                )
            },
            onOpenUrlImport = { showClosetUrlDialog = true },
            modifier = modifier,
        )
        if (showClosetUrlDialog) {
            ShopUrlImportDialog(
                onSubmit = { url ->
                    showClosetUrlDialog = false
                    shoppingClosetViewModel.addFromUrl(url)
                },
                onDismiss = { showClosetUrlDialog = false },
            )
        }
        return
    }

    // Pull the wishlist as soon as the screen first composes.
    LaunchedEffect(Unit) { shoppingClosetViewModel.loadItems() }

    Column(modifier = modifier.fillMaxSize()) {
        AppScreenHeader(
            title = stringResource(R.string.nav_shopping),
            leadingIcon = Icons.Default.ShoppingBag,
            trailingContent = {
                // Shopping List is location-independent; hide the LocationButton there.
                if (selectedTab != 0) {
                    LocationButton(
                        locations = locationState.locations,
                        activeLocationId = locationState.activeLocationId,
                        onSetActiveLocation = locationViewModel::setActiveLocation,
                    )
                }
            },
            onSettingsClick = onSettingsClick,
        )

        LaunchedEffect(selectedTab) {
            val name = when (selectedTab) {
                0 -> "Shopping/List"; 1 -> "Shopping/Similarity"; 2 -> "Shopping/Gaps"
                else -> "Shopping/Tab$selectedTab"
            }
            Analytics.screen(name)
        }
        TabRow(selectedTabIndex = selectedTab) {
            Tab(
                selected = selectedTab == 0,
                onClick = {
                    Analytics.action("Shopping", "subtab_list")
                    selectedTab = 0
                },
                text = { Text(stringResource(R.string.shopping_tab_list)) },
            )
            Tab(
                selected = selectedTab == 1,
                onClick = {
                    Analytics.action("Shopping", "subtab_similarity")
                    selectedTab = 1
                },
                text = { Text(stringResource(R.string.shopping_tab_similarity)) },
            )
            Tab(
                selected = selectedTab == 2,
                onClick = {
                    Analytics.action("Shopping", "subtab_gaps")
                    selectedTab = 2
                },
                text = { Text(stringResource(R.string.shopping_tab_gaps)) },
            )
        }

        when (selectedTab) {
            0 -> ShoppingListTab(
                shoppingClosetViewModel = shoppingClosetViewModel,
                locations = locationState.locations,
                onCaptureClick = { isClosetCapturing = true },
                onItemsMovedToCloset = wardrobeViewModel::notifyItemsMovedTo,
                onItemsMoveFailed = wardrobeViewModel::undoItemsMovedTo,
                onCreateOutfitFromSelection = onCreateOutfitFromSelection,
                onTryOnSelection = onTryOnSelection,
                canTryOn = canTryOn,
                onOpenItemViewer = onOpenItemViewer,
            )
            1 -> SimilarityFinderTab(
                shoppingViewModel = shoppingViewModel,
                shoppingClosetViewModel = shoppingClosetViewModel,
                wardrobeViewModel = wardrobeViewModel,
                profileViewModel = profileViewModel,
                onShowInWardrobe = onShowInWardrobe,
            )
            2 -> IdentifyGapsTab(
                gapViewModel = gapViewModel,
                wardrobeViewModel = wardrobeViewModel,
                profileViewModel = profileViewModel,
            )
        }
    }
}

// ============================================================================
//  Tab 0: Shopping List (wishlist)
// ============================================================================

