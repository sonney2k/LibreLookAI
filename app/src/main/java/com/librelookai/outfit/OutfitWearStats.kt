package com.librelookai.outfit

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material.icons.outlined.FavoriteBorder
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import coil.compose.AsyncImage
import coil.request.ImageRequest
import com.librelookai.R
import com.librelookai.data.model.Outfit
import com.librelookai.util.LocalIsOffline
import com.librelookai.wardrobe.DriveImage
import com.librelookai.wardrobe.WardrobeViewModel

private const val TOP_N = 10
@Composable
fun OutfitWearStatsTab(
    outfitEventsViewModel: OutfitEventsViewModel = viewModel(),
    stylesViewModel: OutfitsViewModel = viewModel(),
    wardrobeViewModel: WardrobeViewModel = viewModel(),
) {
    val outfitEventsState by outfitEventsViewModel.state.collectAsState()
    val outfitsState by stylesViewModel.state.collectAsState()
    val wardrobeState by wardrobeViewModel.state.collectAsState()

    val outfitsById = remember(outfitsState.outfits) { outfitsState.outfits.associateBy { it.id } }
    val imagesById = remember(wardrobeState.images) { wardrobeState.images.associateBy { it.driveId } }

    val topStyles = remember(outfitEventsState.events, outfitsById) {
        outfitEventsState.events
            .groupBy { it.outfitId }
            .mapValues { it.value.size }
            .entries
            .sortedByDescending { it.value }
            .take(TOP_N)
            .mapNotNull { (outfitId, count) -> outfitsById[outfitId]?.let { it to count } }
    }

    val topItems = remember(outfitEventsState.events, outfitsById, imagesById) {
        outfitEventsState.events
            .flatMap { event -> outfitsById[event.outfitId]?.itemIds ?: emptyList() }
            .groupingBy { it }
            .eachCount()
            .entries
            .sortedByDescending { it.value }
            .take(TOP_N)
            .mapNotNull { (itemId, count) -> imagesById[itemId]?.let { it to count } }
    }

    if (topStyles.isEmpty() && topItems.isEmpty()) {
        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Text(stringResource(R.string.calendar_empty), style = MaterialTheme.typography.bodyLarge)
                Spacer(Modifier.height(4.dp))
                Text(
                    stringResource(R.string.calendar_empty_hint),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        return
    }

    LazyColumn(
        contentPadding = PaddingValues(bottom = 24.dp),
    ) {
        if (topStyles.isNotEmpty()) {
            item {
                Text(
                    stringResource(R.string.calendar_stats_styles),
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.SemiBold,
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp),
                )
                HorizontalDivider()
            }
            itemsIndexed(topStyles) { index, (style, count) ->
                // Outfit-level favourite — the same heart shown on the outfit list/detail views,
                // backed by Outfit.loved so toggling here stays in sync with those (and vice versa).
                StyleStatRow(
                    rank = index + 1,
                    style = style,
                    wearCount = count,
                    loved = style.loved,
                    onToggleLoved = { stylesViewModel.setOutfitLoved(style.id, !style.loved) },
                    imagesById = imagesById,
                )
                if (index < topStyles.lastIndex) HorizontalDivider(modifier = Modifier.padding(horizontal = 16.dp))
            }
        }

        if (topItems.isNotEmpty()) {
            item {
                Spacer(Modifier.height(8.dp))
                Text(
                    stringResource(R.string.calendar_stats_items),
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.SemiBold,
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp),
                )
                HorizontalDivider()
            }
            itemsIndexed(topItems) { index, (image, count) ->
                ItemStatRow(rank = index + 1, image = image, wearCount = count)
                if (index < topItems.lastIndex) HorizontalDivider(modifier = Modifier.padding(horizontal = 16.dp))
            }
        }
    }
}

@Composable
private fun StyleStatRow(
    rank: Int,
    style: Outfit,
    wearCount: Int,
    loved: Boolean,
    onToggleLoved: () -> Unit,
    imagesById: Map<String, DriveImage>,
) {
    val ctx = LocalContext.current
    val styleItems = style.itemIds.mapNotNull { imagesById[it] }
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            modifier = Modifier.width(36.dp),
        ) {
            Text(
                "#$rank",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Text(
                "${wearCount}×",
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.primary,
            )
        }

        Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(
                    style.name,
                    style = MaterialTheme.typography.bodyMedium,
                    fontWeight = FontWeight.Medium,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f, fill = false),
                )
                if (!LocalIsOffline.current) {
                    IconButton(onClick = onToggleLoved, modifier = Modifier.size(28.dp)) {
                        Icon(
                            imageVector = if (loved) Icons.Filled.Favorite else Icons.Outlined.FavoriteBorder,
                            contentDescription = stringResource(R.string.calendar_loved),
                            tint = if (loved) MaterialTheme.colorScheme.primary
                                   else MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.size(16.dp),
                        )
                    }
                } else if (loved) {
                    Icon(
                        Icons.Filled.Favorite,
                        contentDescription = stringResource(R.string.calendar_loved),
                        tint = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.size(14.dp),
                    )
                }
            }
            if (styleItems.isNotEmpty()) {
                LazyRow(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                    itemsIndexed(styleItems, key = { index, image -> "${image.driveId}_${index}" }) { _, image ->
                        AsyncImage(
                            model = remember(image.driveId, image.version) {
                                ImageRequest.Builder(ctx)
                                    .data(image.localPath)
                                    .memoryCacheKey("${image.driveId}_${image.version}")
                                    .build()
                            },
                            contentDescription = image.name,
                            modifier = Modifier
                                .size(48.dp)
                                .clip(MaterialTheme.shapes.small),
                            contentScale = ContentScale.Crop,
                        )
                    }
                }
            } else {
                Text(
                    stringResource(R.string.insights_items_removed),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.outline,
                )
            }
        }
    }
}

@Composable
private fun ItemStatRow(
    rank: Int,
    image: DriveImage,
    wearCount: Int,
) {
    val ctx = LocalContext.current
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            modifier = Modifier.width(36.dp),
        ) {
            Text(
                "#$rank",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Text(
                "${wearCount}×",
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.primary,
            )
        }

        AsyncImage(
            model = remember(image.driveId, image.version) {
                ImageRequest.Builder(ctx)
                    .data(image.localPath)
                    .memoryCacheKey("${image.driveId}_${image.version}")
                    .build()
            },
            contentDescription = image.name,
            modifier = Modifier
                .size(56.dp)
                .clip(MaterialTheme.shapes.small),
            contentScale = ContentScale.Crop,
        )

        Column(modifier = Modifier.weight(1f)) {
            val displayName = image.tags?.label?.ifEmpty { null }
                ?: image.tags?.type?.ifEmpty { null }
                ?: image.name
            Text(
                displayName,
                style = MaterialTheme.typography.bodyMedium,
                fontWeight = FontWeight.Medium,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            image.tags?.let { t ->
                val subtitle = listOfNotNull(
                    t.type.takeIf { it.isNotEmpty() && t.label.isNotEmpty() },
                    t.category.takeIf { it.isNotEmpty() },
                ).joinToString(" · ")
                if (subtitle.isNotEmpty()) {
                    Text(
                        subtitle,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
        }
    }
}
