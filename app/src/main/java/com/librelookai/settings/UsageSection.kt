package com.librelookai.settings

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedCard
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.viewmodel.compose.viewModel
import com.librelookai.data.model.Outfit
import com.librelookai.gemini.UsageAggregator
import com.librelookai.gemini.UsageCategory
import com.librelookai.gemini.UsageWindowTotals
import java.text.SimpleDateFormat
import java.util.Locale
import java.util.concurrent.TimeUnit

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
            HeadlineCard("Today", todayTotals.tokens, todayTotals.eur, modifier = Modifier.weight(1f))
            HeadlineCard("7 days", weekTotals.tokens, weekTotals.eur, modifier = Modifier.weight(1f))
            HeadlineCard("Total", total.tokens, total.eur, modifier = Modifier.weight(1f))
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
private fun HeadlineCard(label: String, tokens: Int, eur: Double, modifier: Modifier = Modifier) {
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
                formatEur(eur),
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
                Text(formatEur(totals.eur),
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
            Text(formatEur(window.eur),
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

