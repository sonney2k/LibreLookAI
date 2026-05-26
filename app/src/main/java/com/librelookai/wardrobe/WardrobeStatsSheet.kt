package com.librelookai.wardrobe

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.librelookai.R

/**
 * Wardrobe composition by tag category — the former Insights "Wardrobe Stats" tab, now reached via
 * the chart icon in the Wardrobe header. A [ModalBottomSheet] opens its own window and severs the
 * locale-overridden context chain, so [LocalContext]/[LocalConfiguration] are re-provided inside
 * (see CLAUDE.md → Dialog quirks).
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun WardrobeStatsSheet(
    images: List<DriveImage>,
    onDismiss: () -> Unit,
) {
    val parentContext = LocalContext.current
    val parentConfiguration = LocalConfiguration.current
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)

    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = sheetState) {
        CompositionLocalProvider(
            LocalContext provides parentContext,
            LocalConfiguration provides parentConfiguration,
        ) {
            WardrobeStatsContent(images = images)
        }
    }
}

@Composable
private fun WardrobeStatsContent(images: List<DriveImage>) {
    val counts = remember(images) { images.tagCategoryCounts() }
    val untagged = remember(images) {
        images.count { img ->
            val t = img.tags
            t == null || (t.type.isBlank() && t.category.isBlank())
        }
    }

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .verticalScroll(rememberScrollState())
            .navigationBarsPadding()
            .padding(horizontal = 20.dp)
            .padding(top = 4.dp, bottom = 24.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        Text(
            stringResource(R.string.insights_tab_wardrobe_stats),
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.SemiBold,
        )
        Text(
            stringResource(R.string.wardrobe_stats_total, images.size),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        if (images.isNotEmpty() && counts.isEmpty()) {
            Text(
                stringResource(R.string.wardrobe_empty),
                style = MaterialTheme.typography.bodyMedium,
            )
        }
        counts.forEach { categoryCounts ->
            val maxCount = categoryCounts.counts.maxOf { it.count }
            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(
                    tagCategoryDisplayLabel(categoryCounts.label),
                    style = MaterialTheme.typography.titleSmall,
                )
                categoryCounts.counts.forEach { tc ->
                    StatsBarRow(
                        label = tc.value.localizedTagValue(),
                        count = tc.count,
                        fraction = tc.count.toFloat() / maxCount.toFloat(),
                    )
                }
            }
        }
        if (untagged > 0) {
            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(
                    stringResource(R.string.wardrobe_stats_untagged),
                    style = MaterialTheme.typography.titleSmall,
                )
                StatsBarRow(
                    label = stringResource(R.string.wardrobe_stats_untagged),
                    count = untagged,
                    fraction = untagged.toFloat() / images.size.toFloat(),
                )
            }
        }
    }
}

@Composable
private fun StatsBarRow(label: String, count: Int, fraction: Float) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Text(
            label,
            style = MaterialTheme.typography.bodySmall,
            modifier = Modifier.width(110.dp),
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
        LinearProgressIndicator(
            progress = { fraction.coerceIn(0f, 1f) },
            modifier = Modifier
                .weight(1f)
                .height(6.dp),
        )
        Text(
            count.toString(),
            style = MaterialTheme.typography.labelSmall,
            modifier = Modifier.width(28.dp),
        )
    }
}
