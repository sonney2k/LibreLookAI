package com.librelookai.settings

import android.app.Application
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedCard
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewmodel.compose.viewModel
import com.librelookai.R
import com.librelookai.gemini.TokenUsageRepository
import com.librelookai.gemini.UsageCategory
import com.librelookai.outfit.OutfitEventsViewModel
import com.librelookai.outfit.OutfitsViewModel
import com.librelookai.tryon.TryOnViewModel
import com.librelookai.wardrobe.WardrobeViewModel
import java.time.LocalDate
import java.time.ZoneId
import java.util.concurrent.TimeUnit

class UsageViewModel(app: Application) : AndroidViewModel(app) {
    val repo: TokenUsageRepository = TokenUsageRepository.get(app)
    val events = repo.events
}

// ============================================================================
//  Usage & costs — activity counts + 14-day charts + Gemini token usage.
//  Former Insights "Costs" tab, now the Settings "Usage" tab. ViewModels default
//  to viewModel(), resolving to the same activity-scoped instances MainActivity holds.
// ============================================================================

@Composable
fun UsageCostsTab(
    wardrobeViewModel: WardrobeViewModel = viewModel(),
    stylesViewModel: OutfitsViewModel = viewModel(),
    outfitEventsViewModel: OutfitEventsViewModel = viewModel(),
    tryOnViewModel: TryOnViewModel = viewModel(),
) {
    val usageVm: UsageViewModel = viewModel()
    val wardrobe by wardrobeViewModel.state.collectAsState()
    val outfits by stylesViewModel.state.collectAsState()
    val outfitEvents by outfitEventsViewModel.state.collectAsState()
    val tryOnState by tryOnViewModel.state.collectAsState()
    val usageEvents by usageVm.events.collectAsState()

    val days = 14
    val outfitsDaily = remember(outfits.outfits, days) {
        dailyCounts(outfits.outfits.map { it.createdAt }, days)
    }
    val tryOnsDaily = remember(tryOnState.history, days) {
        dailyCounts(tryOnState.history.map { it.createdAt }, days)
    }
    val wearsDaily = remember(outfitEvents.events, days) {
        dailyCounts(
            outfitEvents.events.mapNotNull { e ->
                runCatching {
                    LocalDate.parse(e.date)
                        .atStartOfDay(ZoneId.systemDefault())
                        .toInstant().toEpochMilli()
                }.getOrNull()
            },
            days,
        )
    }
    val importsDaily = remember(usageEvents, days) {
        dailyCounts(
            usageEvents.filter { it.category == UsageCategory.BG_REMOVAL }.map { it.timestampMs },
            days,
        )
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .navigationBarsPadding()
            .padding(bottom = 24.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 10.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            CostsCountCard(stringResource(R.string.costs_count_items), wardrobe.images.size, Modifier.weight(1f))
            CostsCountCard(stringResource(R.string.costs_count_outfits), outfits.outfits.size, Modifier.weight(1f))
            CostsCountCard(stringResource(R.string.costs_count_tryons), tryOnState.history.size, Modifier.weight(1f))
            CostsCountCard(stringResource(R.string.costs_count_wears), outfitEvents.events.size, Modifier.weight(1f))
        }

        Text(
            stringResource(R.string.costs_activity_14d),
            style = MaterialTheme.typography.labelMedium,
            fontWeight = FontWeight.SemiBold,
            modifier = Modifier.padding(horizontal = 20.dp),
        )
        ActivityRow(stringResource(R.string.costs_count_imports), importsDaily, MaterialTheme.colorScheme.primary)
        ActivityRow(stringResource(R.string.costs_count_outfits), outfitsDaily, MaterialTheme.colorScheme.tertiary)
        ActivityRow(stringResource(R.string.costs_count_tryons), tryOnsDaily, MaterialTheme.colorScheme.secondary)
        ActivityRow(stringResource(R.string.costs_count_wears), wearsDaily, MaterialTheme.colorScheme.primary)

        UsageSection(modifier = Modifier.padding(top = 8.dp))
    }
}

@Composable
private fun CostsCountCard(label: String, count: Int, modifier: Modifier = Modifier) {
    OutlinedCard(modifier = modifier) {
        Column(modifier = Modifier.padding(horizontal = 12.dp, vertical = 10.dp)) {
            Text(
                label,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.height(2.dp))
            Text(
                count.toString(),
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold,
            )
        }
    }
}

@Composable
private fun ActivityRow(label: String, daily: List<Pair<Long, Int>>, color: Color) {
    val total = daily.sumOf { it.second }
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Text(
            label,
            style = MaterialTheme.typography.bodySmall,
            modifier = Modifier.width(80.dp),
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
        MiniBarChart(
            daily = daily,
            color = color,
            modifier = Modifier
                .weight(1f)
                .height(36.dp),
        )
        Text(
            total.toString(),
            style = MaterialTheme.typography.labelSmall,
            modifier = Modifier.width(36.dp),
            textAlign = TextAlign.End,
        )
    }
}

@Composable
private fun MiniBarChart(daily: List<Pair<Long, Int>>, color: Color, modifier: Modifier = Modifier) {
    val gridColor = MaterialTheme.colorScheme.outlineVariant
    val maxVal = (daily.maxOfOrNull { it.second } ?: 0).coerceAtLeast(1)
    Canvas(modifier = modifier) {
        val bars = daily.size
        if (bars == 0) return@Canvas
        val gap = 2.dp.toPx()
        val barW = (size.width - gap * (bars - 1)) / bars
        drawRect(
            color = gridColor,
            topLeft = Offset(0f, size.height - 1f),
            size = Size(size.width, 1f),
        )
        daily.forEachIndexed { i, (_, v) ->
            val h = (v.toFloat() / maxVal) * (size.height - 2f)
            val x = i * (barW + gap)
            val y = size.height - h
            drawRoundRect(
                color = color,
                topLeft = Offset(x, y),
                size = Size(barW, h.coerceAtLeast(0.5f)),
                cornerRadius = CornerRadius(2f, 2f),
            )
        }
    }
}

private fun dailyCounts(timestampsMs: List<Long>, days: Int): List<Pair<Long, Int>> {
    val cal = java.util.Calendar.getInstance()
    cal.set(java.util.Calendar.HOUR_OF_DAY, 0)
    cal.set(java.util.Calendar.MINUTE, 0)
    cal.set(java.util.Calendar.SECOND, 0)
    cal.set(java.util.Calendar.MILLISECOND, 0)
    val todayStart = cal.timeInMillis
    val dayMs = TimeUnit.DAYS.toMillis(1)
    val windowStart = todayStart - (days - 1) * dayMs
    val buckets = IntArray(days)
    for (ts in timestampsMs) {
        if (ts < windowStart) continue
        val idx = ((ts - windowStart) / dayMs).toInt()
        if (idx in 0 until days) buckets[idx] += 1
    }
    return (0 until days).map { i -> (windowStart + i * dayMs) to buckets[i] }
}

