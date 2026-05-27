package com.librelookai.outfit

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowLeft
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.compose.ui.window.DialogWindowProvider
import com.librelookai.R
import com.librelookai.data.model.Location
import com.librelookai.data.model.Outfit
import com.librelookai.util.LocalSystemBarsPadding
import com.librelookai.wardrobe.DriveImage

@Composable
internal fun ComposerSuggestionSwiper(
    index: Int,
    count: Int,
    onPrev: () -> Unit,
    onNext: () -> Unit,
    onOpenFullscreen: (() -> Unit)? = null,
) {
    Surface(
        shape = RoundedCornerShape(20.dp),
        color = MaterialTheme.colorScheme.surfaceVariant,
        modifier = Modifier.fillMaxWidth(),
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 4.dp, vertical = 2.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            IconButton(onClick = onPrev) {
                Icon(
                    Icons.AutoMirrored.Filled.KeyboardArrowLeft,
                    contentDescription = stringResource(R.string.outfits_prediction_prev),
                )
            }
            val indicatorMod = if (onOpenFullscreen != null) {
                Modifier
                    .weight(1f)
                    .clickable(onClick = onOpenFullscreen)
                    .padding(vertical = 8.dp)
            } else {
                Modifier.weight(1f)
            }
            Text(
                text = stringResource(R.string.outfits_prediction_indicator, index + 1, count),
                style = MaterialTheme.typography.labelLarge,
                modifier = indicatorMod,
                textAlign = androidx.compose.ui.text.style.TextAlign.Center,
            )
            IconButton(onClick = onNext) {
                Icon(
                    Icons.AutoMirrored.Filled.KeyboardArrowRight,
                    contentDescription = stringResource(R.string.outfits_prediction_next),
                )
            }
        }
    }
}

/**
 * Fullscreen pager preview of the AI-generated outfit suggestions. Mirrors
 * [OutfitFullScreenViewer]'s layout (header + HorizontalPager + close-X) but with a single
 * "Use this outfit" FAB instead of the wear/edit/delete dial. Returns the picked index to
 * the caller which then applies it via [OutfitsViewModel.showComposerSuggestionAt].
 */
@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
internal fun ComposerSuggestionsViewer(
    suggestions: List<ComposerSuggestion>,
    initialIndex: Int,
    slots: List<OutfitSlot>,
    itemsById: Map<String, DriveImage>,
    locations: List<com.librelookai.data.model.Location>,
    onSelect: (Int) -> Unit,
    onDismiss: () -> Unit,
) {
    if (suggestions.isEmpty()) return
    val parentContext = LocalContext.current
    val parentConfiguration = LocalConfiguration.current
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

    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(
            usePlatformDefaultWidth = false,
            dismissOnBackPress = true,
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
            val pagerState = rememberPagerState(
                initialPage = initialIndex.coerceIn(0, suggestions.lastIndex),
                pageCount = { suggestions.size },
            )

            // Build a draft Outfit per suggestion using the same locked-slot + assignment
            // resolution used by applyComposerSuggestionToSlots.
            val outfits = remember(suggestions, slots) {
                suggestions.map { sug ->
                    val itemIds = slots.mapNotNull { slot ->
                        if (slot.isLocked && slot.selectedItemId != null) slot.selectedItemId
                        else sug.slotAssignments[slot.id]
                    }
                    Outfit(
                        id = "draft-${slots.hashCode()}-${sug.hashCode()}",
                        name = sug.name,
                        description = sug.description,
                        tags = sug.tags,
                        itemIds = itemIds,
                    )
                }
            }
            val current = outfits[pagerState.currentPage]

            Surface(
                modifier = Modifier.fillMaxSize(),
                color = MaterialTheme.colorScheme.background,
            ) {
                Box(modifier = Modifier.fillMaxSize()) {
                    Column(modifier = Modifier.fillMaxSize()) {
                        Column(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(top = 8.dp, start = 56.dp, end = 56.dp, bottom = 8.dp),
                            horizontalAlignment = Alignment.CenterHorizontally,
                            verticalArrangement = Arrangement.spacedBy(4.dp),
                        ) {
                            if (current.name.isNotBlank()) {
                                Text(
                                    text = current.name,
                                    color = MaterialTheme.colorScheme.onBackground,
                                    style = MaterialTheme.typography.titleMedium,
                                    maxLines = 2,
                                    overflow = TextOverflow.Ellipsis,
                                )
                            }
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
                            if (current.tags.isNotEmpty()) {
                                val maxWidth = LocalConfiguration.current.screenWidthDp.dp * 0.85f
                                FlowRow(
                                    modifier = Modifier.widthIn(max = maxWidth),
                                    horizontalArrangement = Arrangement.spacedBy(4.dp, Alignment.CenterHorizontally),
                                    verticalArrangement = Arrangement.spacedBy(4.dp),
                                ) {
                                    current.tags.forEach { OutfitTagChip(it) }
                                }
                            }
                        }
                        HorizontalPager(
                            state = pagerState,
                            modifier = Modifier
                                .fillMaxWidth()
                                .weight(1f),
                        ) { page ->
                            OutfitPageBody(
                                outfit = outfits[page],
                                itemsById = itemsById,
                                locations = locations,
                                onItemClick = {},
                                // Reserve room for the FAB at the bottom-right.
                                bottomPadding = effectiveBottom + 72.dp,
                            )
                        }
                    }

                    IconButton(
                        onClick = onDismiss,
                        modifier = Modifier.align(Alignment.TopStart).padding(8.dp),
                    ) {
                        Icon(
                            Icons.Default.Close,
                            contentDescription = stringResource(R.string.action_dismiss),
                            tint = MaterialTheme.colorScheme.onBackground,
                        )
                    }

                    ExtendedFloatingActionButton(
                        onClick = { onSelect(pagerState.currentPage) },
                        modifier = Modifier
                            .align(Alignment.BottomEnd)
                            .padding(bottom = effectiveBottom)
                            .padding(16.dp),
                        containerColor = MaterialTheme.colorScheme.primary,
                        contentColor = MaterialTheme.colorScheme.onPrimary,
                        icon = { Icon(Icons.Default.Check, contentDescription = null) },
                        text = { Text(stringResource(R.string.composer_select_suggestion)) },
                    )
                }
            }
        }
    }
}
