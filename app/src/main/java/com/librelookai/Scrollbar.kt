package com.librelookai

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.tween
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.grid.LazyGridState
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Modifier
import androidx.compose.ui.composed
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.flow.distinctUntilChanged

private val ScrollbarWidth: Dp = 4.dp
private val ScrollbarMinHeight: Dp = 24.dp
private val ScrollbarColor: Color = Color(0xFF888888)

private data class BarMetrics(val offsetPx: Float, val sizePx: Float)

private fun gridMetrics(state: LazyGridState, viewportPx: Float, minSizePx: Float): BarMetrics? {
    val info = state.layoutInfo
    val total = info.totalItemsCount
    if (total == 0 || info.visibleItemsInfo.isEmpty() || viewportPx <= 0f) return null
    // Group visible items by row using their y offset (vertical grid).
    val rows = info.visibleItemsInfo.groupBy { it.offset.y }.toSortedMap()
    val visibleRows = rows.size
    // Estimate total rows by columns count.
    val perRow = info.visibleItemsInfo.groupBy { it.offset.y }.maxOf { it.value.size }.coerceAtLeast(1)
    val totalRows = ((total + perRow - 1) / perRow).coerceAtLeast(1)
    if (visibleRows >= totalRows) return null
    val firstRowIndex = state.firstVisibleItemIndex / perRow
    val firstRowEntry = rows.entries.first()
    val rowHeight = (rows.entries.toList().getOrNull(1)?.key ?: (firstRowEntry.key + (firstRowEntry.value.first().size.height))) - firstRowEntry.key
    val rowHeightF = if (rowHeight <= 0) firstRowEntry.value.first().size.height.toFloat() else rowHeight.toFloat()
    val scrolledPx = firstRowIndex * rowHeightF - firstRowEntry.key
    val contentPx = totalRows * rowHeightF
    val sizePx = (viewportPx * (viewportPx / contentPx)).coerceAtLeast(minSizePx)
    val maxOffset = (viewportPx - sizePx).coerceAtLeast(0f)
    val offsetPx = if (contentPx <= viewportPx) 0f else (scrolledPx / (contentPx - viewportPx)) * maxOffset
    return BarMetrics(offsetPx.coerceIn(0f, maxOffset), sizePx)
}

private fun listMetrics(state: LazyListState, viewportPx: Float, minSizePx: Float): BarMetrics? {
    val info = state.layoutInfo
    val total = info.totalItemsCount
    if (total == 0 || info.visibleItemsInfo.isEmpty() || viewportPx <= 0f) return null
    val avgItem = info.visibleItemsInfo.map { it.size }.average().toFloat().coerceAtLeast(1f)
    val contentPx = avgItem * total
    if (contentPx <= viewportPx) return null
    val first = info.visibleItemsInfo.first()
    val scrolledPx = state.firstVisibleItemIndex * avgItem - first.offset
    val sizePx = (viewportPx * (viewportPx / contentPx)).coerceAtLeast(minSizePx)
    val maxOffset = (viewportPx - sizePx).coerceAtLeast(0f)
    val offsetPx = (scrolledPx / (contentPx - viewportPx)) * maxOffset
    return BarMetrics(offsetPx.coerceIn(0f, maxOffset), sizePx)
}

private fun Modifier.drawTransientScrollbar(
    metricsProvider: (viewportPx: Float, minPx: Float) -> BarMetrics?,
    alphaAnim: Animatable<Float, *>,
): Modifier = drawWithContent {
    drawContent()
    val widthPx = ScrollbarWidth.toPx()
    val minSizePx = ScrollbarMinHeight.toPx()
    val m = metricsProvider(size.height, minSizePx) ?: return@drawWithContent
    val a = alphaAnim.value
    if (a <= 0f) return@drawWithContent
    val x = size.width - widthPx
    drawRoundRect(
        color = ScrollbarColor.copy(alpha = a * 0.7f),
        topLeft = Offset(x, m.offsetPx),
        size = Size(widthPx, m.sizePx),
        cornerRadius = CornerRadius(widthPx / 2f, widthPx / 2f),
    )
}

fun Modifier.scrollbar(state: LazyGridState): Modifier = composed {
    val alpha = remember { Animatable(0f) }
    LaunchedEffect(state) {
        snapshotFlow { state.isScrollInProgress }
            .distinctUntilChanged()
            .collect { scrolling ->
                if (scrolling) {
                    alpha.snapTo(1f)
                } else {
                    alpha.animateTo(0f, animationSpec = tween(durationMillis = 600, delayMillis = 400))
                }
            }
    }
    drawTransientScrollbar(
        metricsProvider = { v, m -> gridMetrics(state, v, m) },
        alphaAnim = alpha,
    )
}

fun Modifier.scrollbar(state: LazyListState): Modifier = composed {
    val alpha = remember { Animatable(0f) }
    LaunchedEffect(state) {
        snapshotFlow { state.isScrollInProgress }
            .distinctUntilChanged()
            .collect { scrolling ->
                if (scrolling) {
                    alpha.snapTo(1f)
                } else {
                    alpha.animateTo(0f, animationSpec = tween(durationMillis = 600, delayMillis = 400))
                }
            }
    }
    drawTransientScrollbar(
        metricsProvider = { v, m -> listMetrics(state, v, m) },
        alphaAnim = alpha,
    )
}
