package com.librelookai.outfit

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.ExperimentalFoundationApi
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
import androidx.compose.material.icons.filled.AutoAwesome
import androidx.compose.material.icons.filled.CalendarMonth
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.compose.ui.window.DialogWindowProvider
import com.librelookai.R
import com.librelookai.data.model.Location
import com.librelookai.data.model.Outfit
import com.librelookai.util.Analytics
import com.librelookai.util.LocalIsOffline
import com.librelookai.util.LocalSystemBarsPadding
import com.librelookai.wardrobe.DriveImage
import com.librelookai.wardrobe.FullScreenViewer
import com.librelookai.wardrobe.WardrobeViewModel
import com.librelookai.wardrobe.tagCategories
import com.librelookai.wardrobe.fixCutoutBgForItem

@OptIn(ExperimentalFoundationApi::class, ExperimentalLayoutApi::class)
@Composable
internal fun OutfitFullScreenViewer(
    outfits: List<Outfit>,
    initialIndex: Int,
    itemsById: Map<String, DriveImage>,
    locations: List<Location>,
    activeLocationId: String,
    onDismiss: () -> Unit,
    onEdit: (Outfit) -> Unit,
    onWear: (Outfit) -> Unit,
    onDelete: (Outfit) -> Unit,
    onSuggestTags: (Outfit) -> Unit = {},
    onEditTags: (Outfit) -> Unit = {},
    onTryOn: (Outfit) -> Unit = {},
    canTryOn: Boolean = false,
    wardrobeViewModel: WardrobeViewModel,
) {
    val wardrobeState by wardrobeViewModel.state.collectAsState()
    val allTagCategories = remember(itemsById) { itemsById.values.toList().tagCategories() }
    val isOffline = LocalIsOffline.current
    val barInsets = LocalSystemBarsPadding.current
    val parentContext = LocalContext.current
    val parentConfiguration = LocalConfiguration.current
    val parentView = LocalView.current
    val density = androidx.compose.ui.platform.LocalDensity.current
    val rootInsetBottomDp = remember(parentView) {
        val raw = parentView.rootWindowInsets
        val bottomPx = if (raw != null) {
            androidx.core.view.WindowInsetsCompat
                .toWindowInsetsCompat(raw, parentView)
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
    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(
            usePlatformDefaultWidth = false,
            decorFitsSystemWindows = false,
        ),
    ) {
        val dialogView = LocalView.current
        SideEffect {
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
            val pagerState = rememberPagerState(
                initialPage = initialIndex.coerceIn(0, (outfits.size - 1).coerceAtLeast(0)),
                pageCount = { outfits.size },
            )
            var showEditMenu by remember { mutableStateOf(false) }
            var showDeleteDialog by remember { mutableStateOf(false) }
            var viewerImage by remember { mutableStateOf<DriveImage?>(null) }
            var hideTags by rememberSaveable { mutableStateOf(false) }

            val current = outfits[pagerState.currentPage]

            if (showDeleteDialog) {
                AlertDialog(
                    onDismissRequest = { showDeleteDialog = false },
                    title = { Text(stringResource(R.string.outfits_delete_title)) },
                    text = { Text(stringResource(R.string.outfits_delete_text, current.name)) },
                    confirmButton = {
                        TextButton(onClick = {
                            Analytics.action("OutfitViewer", "confirm_delete")
                            showDeleteDialog = false
                            onDelete(current)
                        }) {
                            Text(stringResource(R.string.action_delete), color = MaterialTheme.colorScheme.error)
                        }
                    },
                    dismissButton = {
                        TextButton(onClick = { showDeleteDialog = false }) { Text(stringResource(R.string.action_cancel)) }
                    },
                )
            }

            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .background(MaterialTheme.colorScheme.background),
            ) {
                Column(modifier = Modifier.fillMaxSize()) {
                    // Header — collapsed to a minimal page indicator when this outfit has no
                    // name AND no tags (e.g. a fresh AI suggestion). Composer fullscreen view
                    // mirrors this minimalist treatment.
                    val hasAnyMetadata = outfits.any { it.name.isNotBlank() || it.tags.isNotEmpty() }
                    if (hasAnyMetadata) {
                        Column(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(top = 8.dp, start = 56.dp, end = 56.dp, bottom = 8.dp),
                            horizontalAlignment = Alignment.CenterHorizontally,
                            verticalArrangement = Arrangement.spacedBy(4.dp),
                        ) {
                            Text(
                                text = current.name.ifBlank { stringResource(R.string.outfits_unnamed) },
                                color = MaterialTheme.colorScheme.onBackground,
                                style = MaterialTheme.typography.titleMedium,
                                maxLines = 2,
                                overflow = TextOverflow.Ellipsis,
                            )
                            Text(
                                text = "${pagerState.currentPage + 1} / ${outfits.size}",
                                color = MaterialTheme.colorScheme.onBackground.copy(alpha = 0.7f),
                                style = MaterialTheme.typography.labelMedium,
                            )
                            if (current.description.isNotBlank()) {
                                Text(
                                    text = current.description,
                                    color = MaterialTheme.colorScheme.onBackground.copy(alpha = 0.85f),
                                    style = MaterialTheme.typography.bodySmall,
                                    maxLines = 3,
                                    overflow = TextOverflow.Ellipsis,
                                )
                            }
                            if (!hideTags) {
                                val maxWidth = LocalConfiguration.current.screenWidthDp.dp * 0.85f
                                val tagsClickable = Modifier
                                    .widthIn(max = maxWidth)
                                    .then(if (!isOffline) Modifier.clickable {
                                        Analytics.action("OutfitViewer", "edit_tags")
                                        onEditTags(current)
                                    } else Modifier)
                                if (current.tags.isNotEmpty()) {
                                    FlowRow(
                                        modifier = tagsClickable,
                                        horizontalArrangement = Arrangement.spacedBy(4.dp, Alignment.CenterHorizontally),
                                        verticalArrangement = Arrangement.spacedBy(4.dp),
                                    ) {
                                        current.tags.forEach { OutfitTagChip(it) }
                                    }
                                } else if (!isOffline) {
                                    Text(
                                        text = stringResource(R.string.outfits_tag_add),
                                        modifier = Modifier.clickable {
                                            Analytics.action("OutfitViewer", "edit_tags_empty")
                                            onEditTags(current)
                                        },
                                        style = MaterialTheme.typography.labelMedium,
                                        color = MaterialTheme.colorScheme.primary,
                                    )
                                }
                            }
                            // Hide-tags chip sits inline below the tag row so it doesn't crowd
                            // the close-X at top-left and reads as a clear "toggle tags" affordance.
                            com.librelookai.wardrobe.HideTagsChip(
                                hideTags = hideTags,
                                onToggle = {
                                    Analytics.action("OutfitViewer", if (hideTags) "show_tags" else "hide_tags")
                                    hideTags = !hideTags
                                },
                            )
                        }
                    } else if (outfits.size > 1) {
                        // Pager indicator only — keep the close-X room (start padding) so the
                        // page count doesn't overlap the close button at top-left.
                        Text(
                            text = "${pagerState.currentPage + 1} / ${outfits.size}",
                            color = MaterialTheme.colorScheme.onBackground.copy(alpha = 0.7f),
                            style = MaterialTheme.typography.labelMedium,
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(top = 16.dp, bottom = 4.dp),
                            textAlign = androidx.compose.ui.text.style.TextAlign.Center,
                        )
                    }

                    HorizontalPager(
                        state = pagerState,
                        modifier = Modifier
                            .fillMaxWidth()
                            .weight(1f),
                    ) { page ->
                        val outfit = outfits[page]
                        OutfitPageBody(
                            outfit = outfit,
                            itemsById = itemsById,
                            locations = locations,
                            onItemClick = { viewerImage = it },
                            bottomPadding = effectiveBottom,
                        )
                    }
                }

                // Close button — hide-tags chip lives inline under the tag row instead.
                IconButton(
                    onClick = onDismiss,
                    modifier = Modifier.align(Alignment.TopStart).padding(8.dp),
                ) {
                    Icon(Icons.Default.Close, contentDescription = stringResource(R.string.action_dismiss),
                        tint = MaterialTheme.colorScheme.onBackground)
                }

                // Speed-dial FAB (wear / edit / delete) — hidden offline (writes only).
                if (!isOffline) {
                    Column(
                        modifier = Modifier
                            .align(Alignment.BottomEnd)
                            .padding(bottom = effectiveBottom)
                            .padding(16.dp),
                        horizontalAlignment = Alignment.End,
                        verticalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        if (showEditMenu) {
                            ExtendedFloatingActionButton(
                                onClick = {
                                    Analytics.action("OutfitViewer", "wear_today")
                                    onWear(current); showEditMenu = false
                                },
                                containerColor = MaterialTheme.colorScheme.primaryContainer,
                                contentColor = MaterialTheme.colorScheme.onPrimaryContainer,
                                icon = { Icon(Icons.Default.CalendarMonth, contentDescription = null) },
                                text = { Text(stringResource(R.string.outfits_wear_today)) },
                            )
                            if (canTryOn) {
                                ExtendedFloatingActionButton(
                                    onClick = {
                                        Analytics.action("OutfitViewer", "try_on")
                                        showEditMenu = false
                                        onTryOn(current)
                                    },
                                    containerColor = MaterialTheme.colorScheme.primaryContainer,
                                    contentColor = MaterialTheme.colorScheme.onPrimaryContainer,
                                    icon = { Icon(Icons.Default.AutoAwesome, contentDescription = null) },
                                    text = {
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    Text(stringResource(R.string.tryon_fab))
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
                                    Analytics.action("OutfitViewer", "edit")
                                    showEditMenu = false
                                    onEdit(current)
                                },
                                containerColor = MaterialTheme.colorScheme.primaryContainer,
                                contentColor = MaterialTheme.colorScheme.onPrimaryContainer,
                                icon = { Icon(Icons.Default.Edit, contentDescription = null) },
                                text = { Text(stringResource(R.string.action_edit)) },
                            )
                            ExtendedFloatingActionButton(
                                onClick = {
                                    Analytics.action("OutfitViewer", "suggest_tags")
                                    showEditMenu = false
                                    onSuggestTags(current)
                                },
                                containerColor = MaterialTheme.colorScheme.primaryContainer,
                                contentColor = MaterialTheme.colorScheme.onPrimaryContainer,
                                icon = { Icon(Icons.Default.AutoAwesome, contentDescription = null) },
                                text = {
                                    Row(verticalAlignment = Alignment.CenterVertically) {
                                        Text(stringResource(R.string.outfits_suggest_tags))
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
                                    Analytics.action("OutfitViewer", "open_delete_dialog")
                                    showEditMenu = false
                                    showDeleteDialog = true
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
                                contentDescription = stringResource(R.string.action_edit),
                            )
                        }
                    }
                }
            }

            viewerImage?.let { img ->
                val viewerImages = remember(current.itemIds, itemsById) {
                    current.itemIds.mapNotNull { itemsById[it] }
                }
                val startIdx = viewerImages.indexOfFirst { it.driveId == img.driveId }
                    .coerceAtLeast(0)
                FullScreenViewer(
                    images = viewerImages,
                    initialIndex = startIdx,
                    allTagCategories = allTagCategories,
                    onDismiss = { viewerImage = null },
                    onTagImage = wardrobeViewModel::tagImage,
                    onRemoveBackground = wardrobeViewModel::reprocessBackground,
                    onRotateImage = wardrobeViewModel::rotateImage,
                    onUpdateTags = wardrobeViewModel::updateTags,
                    onDeleteItem = { driveId -> wardrobeViewModel.deleteItems(setOf(driveId)) },
                    onMoveToLocation = wardrobeViewModel::moveItemsToLocation,
                    onCreateOutfitFromSelection = {},
                    onFixCutoutBg = wardrobeViewModel::fixCutoutBgForItem,
                    onLoadOriginal = wardrobeViewModel::ensureOriginalCached,
                    locations = locations,
                    activeLocationId = activeLocationId,
                    processingImageId = wardrobeState.processingImageId,
                    writeMode = true,
                )
            }
        }
    }
}

/**
 * Body of one outfit page in the fullscreen viewer. Items are grouped by
 * tag category so the layout reads top → bottom → footwear → outerwear →
 * accessories → other.
 */
