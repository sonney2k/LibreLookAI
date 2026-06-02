package com.librelookai.wardrobe

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
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
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.automirrored.filled.RotateRight
import androidx.compose.material.icons.filled.AutoFixHigh
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.ImageSearch
import androidx.compose.material.icons.filled.Photo
import androidx.compose.material.icons.filled.Place
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.compose.ui.window.DialogWindowProvider
import com.librelookai.R
import com.librelookai.data.model.Location
import com.librelookai.gemini.ClothingTags
import com.librelookai.gemini.CutoutFixActions
import com.librelookai.util.AiProcessingOverlay
import com.librelookai.util.Analytics
import com.librelookai.util.LocalIsOffline
import com.librelookai.util.LocalSystemBarsPadding

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
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = 8.dp, start = 56.dp, end = 56.dp, bottom = 8.dp),
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

        // Close button — hide-tags chip lives inline under the tag row instead.
        IconButton(
            onClick = onDismiss,
            modifier = Modifier.align(Alignment.TopStart).padding(8.dp),
        ) {
            Icon(Icons.Default.Close, contentDescription = "Close", tint = MaterialTheme.colorScheme.onBackground)
        }

        // View-original toggle (top-end). Shown only when the item has an original on Drive
        // and the host wired up a loader.
        if (onLoadOriginal != null && currentImage.originalDriveId != null) {
            LaunchedEffect(showOriginal, currentImage.driveId) {
                if (showOriginal && originalPaths[currentImage.driveId] == null) {
                    loadingOriginal = true
                    val path = runCatching { onLoadOriginal(currentImage.driveId) }.getOrNull()
                    if (path != null) originalPaths[currentImage.driveId] = path
                    loadingOriginal = false
                }
            }
            IconButton(
                onClick = {
                    Analytics.action("ItemViewer", if (showOriginal) "hide_original" else "show_original")
                    showOriginal = !showOriginal
                },
                modifier = Modifier.align(Alignment.TopEnd).padding(8.dp),
            ) {
                Icon(
                    imageVector = if (showOriginal) Icons.Default.ImageSearch else Icons.Default.Photo,
                    contentDescription = stringResource(
                        if (showOriginal) R.string.wardrobe_view_cutout else R.string.wardrobe_view_original
                    ),
                    tint = MaterialTheme.colorScheme.onBackground,
                )
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
                        text = {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Text(stringResource(R.string.wardrobe_create_style))
                                Spacer(Modifier.width(4.dp))
                                Icon(
                                    Icons.AutoMirrored.Filled.KeyboardArrowRight,
                                    contentDescription = null,
                                    modifier = Modifier.size(18.dp),
                                )
                            }
                        },
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
                                com.librelookai.billing.CostBadge(
                                    com.librelookai.gemini.GeminiActionId.CLASSIFY_CLOTHING,
                                    tokens = com.librelookai.billing.rememberClassifyCostTokens(currentImage.localPath),
                                )
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
                                com.librelookai.billing.CostBadge(
                                    com.librelookai.gemini.GeminiActionId.REMOVE_BACKGROUND,
                                    tokens = com.librelookai.billing.rememberRemoveBgCostTokens(currentImage.localPath),
                                )
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
                        text = {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Text(stringResource(R.string.wardrobe_fix_cutout_bg))
                                Spacer(Modifier.width(4.dp))
                                Icon(
                                    Icons.AutoMirrored.Filled.KeyboardArrowRight,
                                    contentDescription = null,
                                    modifier = Modifier.size(18.dp),
                                )
                            }
                        },
                    )
                    if (locations.any { it.folderId != currentImage.folderId }) {
                        ExtendedFloatingActionButton(
                            onClick = {
                                Analytics.action("ItemViewer", "open_move_dialog")
                                showEditMenu = false
                                showMoveDialog = true
                            },
                            containerColor = MaterialTheme.colorScheme.primaryContainer,
                            contentColor = MaterialTheme.colorScheme.onPrimaryContainer,
                            icon = { Icon(Icons.Default.Place, contentDescription = null) },
                            text = {
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    Text(stringResource(R.string.wardrobe_move_to))
                                    Spacer(Modifier.width(4.dp))
                                    Icon(
                                        Icons.AutoMirrored.Filled.KeyboardArrowRight,
                                        contentDescription = null,
                                        modifier = Modifier.size(18.dp),
                                    )
                                }
                            },
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
        // Offer only closets the item isn't already in (its own folder, not the active filter,
        // which is "All" when browsing across closets).
        val otherLocations = locations.filter { it.folderId != currentImage.folderId }
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

