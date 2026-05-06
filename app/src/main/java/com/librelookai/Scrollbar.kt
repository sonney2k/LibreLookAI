package com.librelookai

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.tween
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.grid.LazyGridState
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Modifier
import androidx.compose.ui.composed
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.launch

private val ScrollbarWidth: Dp = 10.dp
/** Touch-target width on the right edge that captures drag/click for scrubbing. */
private val ScrollbarTouchStrip: Dp = 28.dp
private val ScrollbarMinHeight: Dp = 32.dp
private val ScrollbarColor: Color = Color(0xFF888888)

private data class BarMetrics(val offsetPx: Float, val sizePx: Float)

private fun gridMetrics(state: LazyGridState, viewportPx: Float, minSizePx: Float): BarMetrics? {
    val info = state.layoutInfo
    val total = info.totalItemsCount
    if (total == 0 || info.visibleItemsInfo.isEmpty() || viewportPx <= 0f) return null
    val rows = info.visibleItemsInfo.groupBy { it.offset.y }.toSortedMap()
    val visibleRows = rows.size
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
    alphaProvider: () -> Float,
): Modifier = drawWithContent {
    drawContent()
    val widthPx = ScrollbarWidth.toPx()
    val minSizePx = ScrollbarMinHeight.toPx()
    val m = metricsProvider(size.height, minSizePx) ?: return@drawWithContent
    val a = alphaProvider()
    if (a <= 0f) return@drawWithContent
    val x = size.width - widthPx - 1f
    drawRoundRect(
        color = ScrollbarColor.copy(alpha = a * 0.85f),
        topLeft = Offset(x, m.offsetPx),
        size = Size(widthPx, m.sizePx),
        cornerRadius = CornerRadius(widthPx / 2f, widthPx / 2f),
    )
}

/** Scroll the grid so the scrollbar thumb is centered on [fingerY] within [viewportPx]. */
private fun scrubGrid(
    state: LazyGridState,
    scope: CoroutineScope,
    fingerY: Float,
    viewportPx: Float,
    minSizePx: Float,
) {
    val info = state.layoutInfo
    val total = info.totalItemsCount
    if (total == 0 || info.visibleItemsInfo.isEmpty() || viewportPx <= 0f) return
    val perRow = info.visibleItemsInfo.groupBy { it.offset.y }.maxOf { it.value.size }.coerceAtLeast(1)
    val totalRows = ((total + perRow - 1) / perRow).coerceAtLeast(1)
    val firstRowEntry = info.visibleItemsInfo.groupBy { it.offset.y }.toSortedMap().entries.first()
    val rowHeight = firstRowEntry.value.first().size.height.toFloat().coerceAtLeast(1f)
    val contentPx = totalRows * rowHeight
    if (contentPx <= viewportPx) return
    val barSizePx = (viewportPx * (viewportPx / contentPx)).coerceAtLeast(minSizePx)
    val maxOffset = (viewportPx - barSizePx).coerceAtLeast(1f)
    val targetTop = (fingerY - barSizePx / 2f).coerceIn(0f, maxOffset)
    val scrolledPx = (targetTop / maxOffset) * (contentPx - viewportPx)
    val targetRow = (scrolledPx / rowHeight).toInt().coerceIn(0, totalRows - 1)
    val offsetWithinRow = (scrolledPx - targetRow * rowHeight).toInt()
    scope.launch { state.scrollToItem(targetRow * perRow, offsetWithinRow) }
}

private fun scrubList(
    state: LazyListState,
    scope: CoroutineScope,
    fingerY: Float,
    viewportPx: Float,
    minSizePx: Float,
) {
    val info = state.layoutInfo
    val total = info.totalItemsCount
    if (total == 0 || info.visibleItemsInfo.isEmpty() || viewportPx <= 0f) return
    val avgItem = info.visibleItemsInfo.map { it.size }.average().toFloat().coerceAtLeast(1f)
    val contentPx = avgItem * total
    if (contentPx <= viewportPx) return
    val barSizePx = (viewportPx * (viewportPx / contentPx)).coerceAtLeast(minSizePx)
    val maxOffset = (viewportPx - barSizePx).coerceAtLeast(1f)
    val targetTop = (fingerY - barSizePx / 2f).coerceIn(0f, maxOffset)
    val scrolledPx = (targetTop / maxOffset) * (contentPx - viewportPx)
    val targetIndex = (scrolledPx / avgItem).toInt().coerceIn(0, total - 1)
    val offsetWithinItem = (scrolledPx - targetIndex * avgItem).toInt()
    scope.launch { state.scrollToItem(targetIndex, offsetWithinItem) }
}

fun Modifier.scrollbar(state: LazyGridState): Modifier = composed {
    val alpha = remember { Animatable(0f) }
    val scope = rememberCoroutineScope()
    var dragging by remember { mutableStateOf(false) }
    LaunchedEffect(state) {
        snapshotFlow { state.isScrollInProgress || dragging }
            .distinctUntilChanged()
            .collect { active ->
                if (active) {
                    alpha.snapTo(1f)
                } else {
                    alpha.animateTo(0f, animationSpec = tween(durationMillis = 600, delayMillis = 400))
                }
            }
    }
    Modifier
        .pointerInput(state) {
            val stripPx = ScrollbarTouchStrip.toPx()
            val minSizePx = ScrollbarMinHeight.toPx()
            awaitEachGesture {
                val down = awaitFirstDown(requireUnconsumed = true, pass = PointerEventPass.Initial)
                if (down.position.x < size.width - stripPx) return@awaitEachGesture
                down.consume()
                dragging = true
                scrubGrid(state, scope, down.position.y, size.height.toFloat(), minSizePx)
                try {
                    while (true) {
                        val event = awaitPointerEvent(PointerEventPass.Initial)
                        val change = event.changes.firstOrNull { it.id == down.id } ?: break
                        if (!change.pressed) break
                        change.consume()
                        scrubGrid(state, scope, change.position.y, size.height.toFloat(), minSizePx)
                    }
                } finally {
                    dragging = false
                }
            }
        }
        .drawTransientScrollbar(
            metricsProvider = { v, m -> gridMetrics(state, v, m) },
            alphaProvider = { alpha.value },
        )
}

fun Modifier.scrollbar(state: LazyListState): Modifier = composed {
    val alpha = remember { Animatable(0f) }
    val scope = rememberCoroutineScope()
    var dragging by remember { mutableStateOf(false) }
    LaunchedEffect(state) {
        snapshotFlow { state.isScrollInProgress || dragging }
            .distinctUntilChanged()
            .collect { active ->
                if (active) {
                    alpha.snapTo(1f)
                } else {
                    alpha.animateTo(0f, animationSpec = tween(durationMillis = 600, delayMillis = 400))
                }
            }
    }
    Modifier
        .pointerInput(state) {
            val stripPx = ScrollbarTouchStrip.toPx()
            val minSizePx = ScrollbarMinHeight.toPx()
            awaitEachGesture {
                val down = awaitFirstDown(requireUnconsumed = true, pass = PointerEventPass.Initial)
                if (down.position.x < size.width - stripPx) return@awaitEachGesture
                down.consume()
                dragging = true
                scrubList(state, scope, down.position.y, size.height.toFloat(), minSizePx)
                try {
                    while (true) {
                        val event = awaitPointerEvent(PointerEventPass.Initial)
                        val change = event.changes.firstOrNull { it.id == down.id } ?: break
                        if (!change.pressed) break
                        change.consume()
                        scrubList(state, scope, change.position.y, size.height.toFloat(), minSizePx)
                    }
                } finally {
                    dragging = false
                }
            }
        }
        .drawTransientScrollbar(
            metricsProvider = { v, m -> listMetrics(state, v, m) },
            alphaProvider = { alpha.value },
        )
}
