package com.librelookai.wardrobe

import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import coil.compose.AsyncImage
import coil.request.ImageRequest
import com.librelookai.R
import com.librelookai.gemini.ClothingTags
import com.librelookai.util.AiProcessingOverlay
import com.librelookai.util.LocalIsOffline

@Composable
internal fun ZoomableImage(
    localPath: String,
    name: String,
    cacheKey: String = localPath,
    onScaleChanged: (Float) -> Unit = {},
    onLongPress: () -> Unit = {},
) {
    var scale by remember { mutableFloatStateOf(1f) }
    var offset by remember { mutableStateOf(Offset.Zero) }
    val ctx = LocalContext.current

    AsyncImage(
        model = remember(cacheKey) {
            ImageRequest.Builder(ctx)
                .data(localPath)
                .memoryCacheKey(cacheKey)
                .build()
        },
        contentDescription = name,
        modifier = Modifier
            .fillMaxSize()
            .pointerInput(Unit) {
                detectTapGestures(onLongPress = { if (scale <= 1.01f) onLongPress() })
            }
            .pointerInput(Unit) {
                awaitEachGesture {
                    awaitFirstDown(requireUnconsumed = false, pass = PointerEventPass.Initial)
                    var prevDistance = -1f
                    do {
                        val event = awaitPointerEvent(PointerEventPass.Initial)
                        val pressed = event.changes.filter { it.pressed }
                        when {
                            pressed.size >= 2 -> {
                                val dist = (pressed[1].position - pressed[0].position).getDistance()
                                if (prevDistance > 0f) {
                                    val focal = Offset(
                                        (pressed[0].position.x + pressed[1].position.x) / 2f,
                                        (pressed[0].position.y + pressed[1].position.y) / 2f,
                                    )
                                    val newScale = (scale * (dist / prevDistance)).coerceIn(1f, 8f)
                                    val delta = newScale / scale
                                    // Shift translation so the focal point stays fixed
                                    val cx = size.width / 2f
                                    val cy = size.height / 2f
                                    offset = Offset(
                                        (focal.x - cx) * (1f - delta) + offset.x * delta,
                                        (focal.y - cy) * (1f - delta) + offset.y * delta,
                                    )
                                    scale = newScale
                                    if (scale <= 1f) offset = Offset.Zero
                                    onScaleChanged(scale)
                                }
                                prevDistance = dist
                                pressed.forEach { it.consume() }
                            }
                            // Pan while zoomed — also consumes so pager doesn't swipe.
                            pressed.size == 1 && scale > 1.01f -> {
                                val delta = pressed[0].position - pressed[0].previousPosition
                                offset = Offset(offset.x + delta.x, offset.y + delta.y)
                                pressed[0].consume()
                                prevDistance = -1f
                            }
                            else -> prevDistance = -1f
                        }
                    } while (event.changes.any { it.pressed })
                }
            }
            .graphicsLayer {
                scaleX = scale
                scaleY = scale
                translationX = offset.x
                translationY = offset.y
            },
        contentScale = ContentScale.Fit,
    )
}

// AiProcessingOverlay lives in AiProcessingOverlay.kt (shared)

// ---------- Tags overlay ----------

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun TagsOverlay(
    tags: ClothingTags?,
    hasOriginal: Boolean = false,
    showActions: Boolean = true,
    onTagImage: () -> Unit,
    onRemoveBackground: () -> Unit,
    onEditTags: () -> Unit,
    modifier: Modifier = Modifier,
) {
    if (tags == null && !showActions) return
    // Cap width so chips wrap downward instead of letting the overlay swell
    // across the screen on large font scales.
    val maxWidth = LocalConfiguration.current.screenWidthDp.dp * 0.6f
    Surface(
        modifier = modifier.widthIn(max = maxWidth),
        shape = MaterialTheme.shapes.medium,
        color = Color.Black.copy(alpha = 0.55f),
    ) {
        Column(
            modifier = Modifier.padding(horizontal = 10.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp),
            horizontalAlignment = Alignment.End,
        ) {
            if (tags != null) {
                FlowRow(horizontalArrangement = Arrangement.spacedBy(4.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    if (tags.type.isNotEmpty()) TagChip(tags.type.localizedTagValue())
                    if (tags.category.isNotEmpty()) TagChip(tags.category.localizedTagValue())
                }
                if (tags.uses.isNotEmpty()) {
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(4.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        tags.uses.forEach { TagChip(it.localizedTagValue()) }
                    }
                }
                if (tags.colors.isNotEmpty()) {
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(4.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        tags.colors.forEach { TagChip(it.localizedTagValue()) }
                    }
                }
            }
            val isOffline = LocalIsOffline.current
            if (showActions) FlowRow(horizontalArrangement = Arrangement.spacedBy(4.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                TextButton(onClick = onTagImage, enabled = !isOffline, contentPadding = PaddingValues(horizontal = 8.dp, vertical = 4.dp)) {
                    Text(stringResource(R.string.wardrobe_tag_detect), color = if (isOffline) Color.White.copy(alpha = 0.38f) else Color.White, style = MaterialTheme.typography.labelSmall)
                }
                TextButton(onClick = onRemoveBackground, enabled = !isOffline, contentPadding = PaddingValues(horizontal = 8.dp, vertical = 4.dp)) {
                    Text(
                        stringResource(if (hasOriginal) R.string.wardrobe_tag_re_remove_bg else R.string.wardrobe_tag_remove_bg),
                        color = if (isOffline) Color.White.copy(alpha = 0.38f) else Color.White,
                        style = MaterialTheme.typography.labelSmall,
                    )
                }
                TextButton(onClick = onEditTags, contentPadding = PaddingValues(horizontal = 8.dp, vertical = 4.dp)) {
                    Text(stringResource(R.string.wardrobe_tag_edit), color = Color.White, style = MaterialTheme.typography.labelSmall)
                }
            }
        }
    }
}

