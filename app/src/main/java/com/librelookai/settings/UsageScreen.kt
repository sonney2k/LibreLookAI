package com.librelookai.settings
import android.app.Application
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
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
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedCard
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewmodel.compose.viewModel
import java.text.SimpleDateFormat
import java.time.LocalDate
import java.time.ZoneId
import java.util.Locale
import java.util.concurrent.TimeUnit
import com.librelookai.R
import com.librelookai.gemini.TokenUsageRepository
import com.librelookai.gemini.UsageAggregator
import com.librelookai.gemini.UsageCategory
import com.librelookai.gemini.UsageWindowTotals
import com.librelookai.data.model.Outfit
import com.librelookai.gemini.GeminiPricing
import com.librelookai.outfit.OutfitEventsViewModel
import com.librelookai.outfit.OutfitsViewModel
import com.librelookai.tryon.TryOnViewModel
import com.librelookai.wardrobe.WardrobeViewModel

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

@Composable
fun UsageSection(modifier: Modifier = Modifier) {
    val vm: UsageViewModel = viewModel()
    val events by vm.events.collectAsState()

    val now = System.currentTimeMillis()
    val dayMs = TimeUnit.DAYS.toMillis(1)
    val today = startOfToday()
    val total = UsageAggregator.totals(events)
    val todayTotals = UsageAggregator.totals(events, sinceMs = today)
    val weekTotals = UsageAggregator.totals(events, sinceMs = now - 7 * dayMs)

    Column(modifier = modifier) {
        HorizontalDivider(modifier = Modifier.padding(bottom = 10.dp))
        Text(
            "Gemini API usage",
            style = MaterialTheme.typography.titleSmall,
            fontWeight = FontWeight.SemiBold,
            modifier = Modifier.padding(horizontal = 20.dp),
        )
        Text(
            "Tokens consumed by your BYOK key. Sync'd to Drive.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(horizontal = 20.dp),
        )

        com.librelookai.billing.CostTierLegend(
            modifier = Modifier.padding(horizontal = 20.dp, vertical = 8.dp),
        )

        if (events.isEmpty()) {
            Text(
                "No usage recorded yet.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(horizontal = 20.dp, vertical = 12.dp),
            )
            return@Column
        }

        // Headline summary cards
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 10.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            HeadlineCard("Today", todayTotals.tokens, todayTotals.usd, modifier = Modifier.weight(1f))
            HeadlineCard("7 days", weekTotals.tokens, weekTotals.usd, modifier = Modifier.weight(1f))
            HeadlineCard("Total", total.tokens, total.usd, modifier = Modifier.weight(1f))
        }

        // 14-day daily bar chart
        Text(
            "Daily tokens (last 14 days)",
            style = MaterialTheme.typography.labelMedium,
            fontWeight = FontWeight.SemiBold,
            modifier = Modifier.padding(horizontal = 20.dp, vertical = 4.dp),
        )
        DailyBarChart(
            daily = UsageAggregator.dailyTokens(events, days = 14),
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 4.dp)
                .height(120.dp),
        )

        // Per-category breakdown
        Text(
            "By use",
            style = MaterialTheme.typography.labelMedium,
            fontWeight = FontWeight.SemiBold,
            modifier = Modifier.padding(horizontal = 20.dp, vertical = 6.dp),
        )
        CategoryTable(total)
    }
}

@Composable
private fun HeadlineCard(label: String, tokens: Int, usd: Double, modifier: Modifier = Modifier) {
    OutlinedCard(modifier = modifier) {
        Column(
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 10.dp),
            horizontalAlignment = Alignment.Start,
        ) {
            Text(
                label,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.height(2.dp))
            Text(
                formatTokens(tokens),
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold,
            )
            Text(
                "${formatUsd(usd)} • ${formatEur(usd * GeminiPricing.USD_TO_EUR)}",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun DailyBarChart(daily: List<Pair<Long, Int>>, modifier: Modifier = Modifier) {
    val barColor = MaterialTheme.colorScheme.primary
    val gridColor = MaterialTheme.colorScheme.outlineVariant
    val labelColor = MaterialTheme.colorScheme.onSurfaceVariant
    val maxVal = (daily.maxOfOrNull { it.second } ?: 0).coerceAtLeast(1)
    val dateFmt = SimpleDateFormat("d/M", Locale.getDefault())

    Column(modifier = modifier) {
        Canvas(modifier = Modifier.fillMaxWidth().weight(1f)) {
            val bars = daily.size
            if (bars == 0) return@Canvas
            val gap = 4.dp.toPx()
            val barW = (size.width - gap * (bars - 1)) / bars
            // baseline
            drawRect(
                color = gridColor,
                topLeft = Offset(0f, size.height - 1f),
                size = Size(size.width, 1f),
            )
            daily.forEachIndexed { i, (_, v) ->
                val h = (v.toFloat() / maxVal) * (size.height - 4f)
                val x = i * (barW + gap)
                val y = size.height - h
                drawRoundRect(
                    color = barColor,
                    topLeft = Offset(x, y),
                    size = Size(barW, h.coerceAtLeast(0.5f)),
                    cornerRadius = androidx.compose.ui.geometry.CornerRadius(2f, 2f),
                )
            }
        }
        Spacer(Modifier.height(2.dp))
        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            // Show first, middle, last labels only to avoid clutter
            val first = daily.firstOrNull()?.first
            val mid = daily.getOrNull(daily.size / 2)?.first
            val last = daily.lastOrNull()?.first
            Text(first?.let { dateFmt.format(it) } ?: "", fontSize = 10.sp, color = labelColor)
            Text(mid?.let { dateFmt.format(it) } ?: "", fontSize = 10.sp, color = labelColor)
            Text(last?.let { dateFmt.format(it) } ?: "", fontSize = 10.sp, color = labelColor)
        }
    }
}

@Composable
private fun CategoryTable(window: UsageWindowTotals) {
    val rows = window.perCategory.entries
        .sortedByDescending { it.value.tokens }
    Column(modifier = Modifier.padding(horizontal = 20.dp, vertical = 4.dp)) {
        val labelStyle = MaterialTheme.typography.labelSmall
        val labelColor = MaterialTheme.colorScheme.onSurfaceVariant
        // Header
        Row(modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp)) {
            Text("Use", modifier = Modifier.weight(2.2f), style = labelStyle, color = labelColor)
            Text("Calls", modifier = Modifier.weight(0.9f), textAlign = TextAlign.End, style = labelStyle, color = labelColor)
            Text("In", modifier = Modifier.weight(1.1f), textAlign = TextAlign.End, style = labelStyle, color = labelColor)
            Text("Out", modifier = Modifier.weight(1.1f), textAlign = TextAlign.End, style = labelStyle, color = labelColor)
            Text("Cost", modifier = Modifier.weight(1.3f), textAlign = TextAlign.End, style = labelStyle, color = labelColor)
        }
        HorizontalDivider()
        rows.forEach { (cat, totals) ->
            Row(
                modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Row(modifier = Modifier.weight(2.2f), verticalAlignment = Alignment.CenterVertically) {
                    Box(
                        modifier = Modifier
                            .size(8.dp)
                            .clip(RoundedCornerShape(2.dp))
                            .background(colorForCategory(cat)),
                    )
                    Spacer(Modifier.width(6.dp))
                    Text(prettyName(cat), style = MaterialTheme.typography.bodySmall)
                }
                Text("${totals.calls}",
                    modifier = Modifier.weight(0.9f), textAlign = TextAlign.End,
                    style = MaterialTheme.typography.bodySmall)
                Text(formatTokens(totals.inputTokens),
                    modifier = Modifier.weight(1.1f), textAlign = TextAlign.End,
                    style = MaterialTheme.typography.bodySmall)
                Text(formatTokens(totals.outputTokens),
                    modifier = Modifier.weight(1.1f), textAlign = TextAlign.End,
                    style = MaterialTheme.typography.bodySmall)
                Text(formatUsd(totals.usd),
                    modifier = Modifier.weight(1.3f), textAlign = TextAlign.End,
                    style = MaterialTheme.typography.bodySmall)
            }
        }

        // Row totals
        HorizontalDivider()
        Row(modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp)) {
            Text("Total", modifier = Modifier.weight(2.2f),
                fontWeight = FontWeight.SemiBold, style = MaterialTheme.typography.bodySmall)
            Text("${rows.sumOf { it.value.calls }}",
                modifier = Modifier.weight(0.9f), textAlign = TextAlign.End,
                fontWeight = FontWeight.SemiBold, style = MaterialTheme.typography.bodySmall)
            Text(formatTokens(window.inputTokens),
                modifier = Modifier.weight(1.1f), textAlign = TextAlign.End,
                fontWeight = FontWeight.SemiBold, style = MaterialTheme.typography.bodySmall)
            Text(formatTokens(window.outputTokens),
                modifier = Modifier.weight(1.1f), textAlign = TextAlign.End,
                fontWeight = FontWeight.SemiBold, style = MaterialTheme.typography.bodySmall)
            Text(formatUsd(window.usd),
                modifier = Modifier.weight(1.3f), textAlign = TextAlign.End,
                fontWeight = FontWeight.SemiBold, style = MaterialTheme.typography.bodySmall)
        }
    }
}

private fun colorForCategory(c: UsageCategory): Color = when (c) {
    UsageCategory.BG_REMOVAL    -> Color(0xFF4F8EF7)
    UsageCategory.TAGGING       -> Color(0xFF36B37E)
    UsageCategory.TRY_ON        -> Color(0xFFE0648A)
    UsageCategory.OUTFIT_PREDICT -> Color(0xFFB07AE6)
    UsageCategory.OUTFIT_COMPOSE -> Color(0xFF7E57C2)
    UsageCategory.GAP_ANALYSIS  -> Color(0xFFF5A524)
    UsageCategory.REPLACEMENTS  -> Color(0xFFEE7B33)
    UsageCategory.TRENDS        -> Color(0xFF26A69A)
    UsageCategory.TRAVEL        -> Color(0xFF42A5F5)
    UsageCategory.OTHER         -> Color(0xFF9E9E9E)
}

private fun prettyName(c: UsageCategory): String = when (c) {
    UsageCategory.BG_REMOVAL     -> "Background removal"
    UsageCategory.TAGGING        -> "Tagging"
    UsageCategory.TRY_ON         -> "Try-on"
    UsageCategory.OUTFIT_PREDICT -> "Outfit prediction"
    UsageCategory.OUTFIT_COMPOSE -> "Outfit composition"
    UsageCategory.GAP_ANALYSIS   -> "Gap analysis"
    UsageCategory.REPLACEMENTS   -> "Suggest replacements"
    UsageCategory.TRENDS         -> "Fashion trends"
    UsageCategory.TRAVEL         -> "Travel packing"
    UsageCategory.OTHER          -> "Other"
}

private fun formatTokens(n: Int): String = when {
    n >= 1_000_000 -> String.format(Locale.US, "%.1fM", n / 1_000_000.0)
    n >= 1_000     -> String.format(Locale.US, "%.1fk", n / 1_000.0)
    else           -> "$n"
}

private fun formatUsd(usd: Double): String =
    if (usd < 0.01 && usd > 0) "<\$0.01" else String.format(Locale.US, "\$%.2f", usd)

private fun formatEur(eur: Double): String =
    if (eur < 0.01 && eur > 0) "<€0.01" else String.format(Locale.US, "€%.2f", eur)

private fun startOfToday(): Long {
    val cal = java.util.Calendar.getInstance()
    cal.set(java.util.Calendar.HOUR_OF_DAY, 0)
    cal.set(java.util.Calendar.MINUTE, 0)
    cal.set(java.util.Calendar.SECOND, 0)
    cal.set(java.util.Calendar.MILLISECOND, 0)
    return cal.timeInMillis
}

