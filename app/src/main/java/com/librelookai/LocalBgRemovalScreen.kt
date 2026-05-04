package com.librelookai

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.systemBars
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.activity.compose.BackHandler
import androidx.lifecycle.viewmodel.compose.viewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

/**
 * Full-screen review dialog hosted in [MainActivity]. When [WardrobeViewModel]'s
 * `localBgReviewQueue` is non-empty, it shows the head item: the raw image with the on-device
 * Magic Touch cutout overlaid and a draggable crosshair the user moves onto the item they want
 * to keep. Re-runs segmentation a short debounce after each drag.
 *
 * Buttons:
 *   - **Use this cutout** — saves the current cutout PNG and hands it to the upload pipeline,
 *     bypassing the (paid) Gemini call.
 *   - **Skip (use Gemini)** — proceeds to upload without a local cutout; the worker queue runs
 *     the regular Gemini removeBackground path. Hidden for URL imports.
 *   - **Cancel (X)** — discards the import entirely.
 */
@Composable
fun LocalBgRemovalScreen(viewModel: WardrobeViewModel = viewModel()) {
    val state by viewModel.state.collectAsState()
    val head = state.localBgReviewQueue.firstOrNull() ?: return
    LocalBgRemovalDialog(
        rawFilePath = head.rawFilePath,
        skippable = head.skippable,
        onApply = { cutoutFile -> viewModel.applyLocalBgCutout(cutoutFile) },
        onSkip = { viewModel.skipLocalBgReview() },
        onCancel = { viewModel.cancelLocalBgReview() },
    )
}

@Composable
private fun LocalBgRemovalDialog(
    rawFilePath: String,
    skippable: Boolean,
    onApply: (File) -> Unit,
    onSkip: () -> Unit,
    onCancel: () -> Unit,
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val segmenter = remember { EmbeddingService.segmenter }

    // Decode the source bitmap once. Downsample large photos so segmentation stays snappy on the
    // user's drag — matches our standard 1280px max for AI-bound images.
    val source = remember(rawFilePath) { decodeDownsampled(rawFilePath, 1280) }
    if (source == null) {
        LaunchedEffect(rawFilePath) { onCancel() }
        return
    }

    // Seed point in image (bitmap) pixel coordinates. Initialised at center; the user drags it.
    var seedX by remember(rawFilePath) { mutableStateOf(source.width / 2f) }
    var seedY by remember(rawFilePath) { mutableStateOf(source.height / 2f) }
    var cutout by remember(rawFilePath) { mutableStateOf<Bitmap?>(null) }
    var isComputing by remember(rawFilePath) { mutableStateOf(true) }
    var failed by remember(rawFilePath) { mutableStateOf(false) }
    var displaySize by remember { mutableStateOf(IntSize.Zero) }
    var debounceJob by remember { mutableStateOf<Job?>(null) }

    // On-screen rect of the bitmap (fit-inside aspect-correct), recomputed when the container or
    // source dimensions change. Drag/tap pos is mapped through this back to bitmap coords.
    val containerW = displaySize.width.toFloat()
    val containerH = displaySize.height.toFloat()
    val srcAr = source.width.toFloat() / source.height.toFloat()
    val containerAr = if (containerH == 0f) 1f else containerW / containerH
    val drawW: Float
    val drawH: Float
    if (containerW <= 0f || containerH <= 0f) {
        drawW = 0f; drawH = 0f
    } else if (srcAr > containerAr) {
        drawW = containerW; drawH = containerW / srcAr
    } else {
        drawH = containerH; drawW = containerH * srcAr
    }
    val offX = (containerW - drawW) / 2f
    val offY = (containerH - drawH) / 2f

    fun recompute() {
        debounceJob?.cancel()
        debounceJob = scope.launch {
            delay(150) // debounce drags
            isComputing = true
            failed = false
            val result = segmenter.segmentForegroundTransparent(source, seedX, seedY)
            cutout = result
            failed = result == null
            isComputing = false
        }
    }
    LaunchedEffect(rawFilePath) { recompute() }

    // Render as an in-tree fullscreen overlay rather than a Compose Dialog window. The activity
    // is edge-to-edge, so WindowInsets.systemBars here returns the real status/nav bar values —
    // unlike inside a Dialog window, where insets are not reliably reported and our action row
    // ended up clipped behind the navigation bar.
    BackHandler(enabled = true) { /* require explicit Cancel */ }
    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.surface),
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .windowInsetsPadding(WindowInsets.systemBars),
        ) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 8.dp, vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                IconButton(onClick = onCancel) {
                    Icon(Icons.Default.Close, contentDescription = stringResource(R.string.action_cancel))
                }
                Spacer(Modifier.width(4.dp))
                Text(
                    stringResource(R.string.local_bg_title),
                    style = MaterialTheme.typography.titleLarge,
                )
            }
            Text(
                stringResource(R.string.local_bg_hint),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(horizontal = 16.dp),
            )

            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(1f)
                    .padding(16.dp)
                    .background(Color(0xFF202022))
                    .onSizeChanged { displaySize = it },
                contentAlignment = Alignment.Center,
            ) {
                Canvas(
                    modifier = Modifier
                        .fillMaxSize()
                        .pointerInput(rawFilePath, drawW, drawH, offX, offY) {
                            detectDragGestures { change, _ ->
                                if (drawW <= 0f || drawH <= 0f) return@detectDragGestures
                                val sx = ((change.position.x - offX) / drawW) * source.width
                                val sy = ((change.position.y - offY) / drawH) * source.height
                                seedX = sx.coerceIn(0f, (source.width - 1).toFloat())
                                seedY = sy.coerceIn(0f, (source.height - 1).toFloat())
                                recompute()
                            }
                        }
                        .pointerInput(rawFilePath, drawW, drawH, offX, offY) {
                            detectTapGestures { pos ->
                                if (drawW <= 0f || drawH <= 0f) return@detectTapGestures
                                val sx = ((pos.x - offX) / drawW) * source.width
                                val sy = ((pos.y - offY) / drawH) * source.height
                                seedX = sx.coerceIn(0f, (source.width - 1).toFloat())
                                seedY = sy.coerceIn(0f, (source.height - 1).toFloat())
                                recompute()
                            }
                        },
                    onDraw = {
                        if (drawW <= 0f || drawH <= 0f) return@Canvas

                        // Checkerboard so transparent pixels are visible.
                        val cell = 16f
                        var y = 0f
                        var row = 0
                        while (y < drawH) {
                            var x = 0f
                            var col = 0
                            while (x < drawW) {
                                val dark = (row + col) and 1 == 0
                                drawRect(
                                    color = if (dark) Color(0xFF2A2A2C) else Color(0xFF353538),
                                    topLeft = Offset(offX + x, offY + y),
                                    size = Size(
                                        width = cell.coerceAtMost(drawW - x),
                                        height = cell.coerceAtMost(drawH - y),
                                    ),
                                )
                                x += cell
                                col++
                            }
                            y += cell
                            row++
                        }

                        // Faded raw image so the user can see what's being discarded; cutout on top.
                        drawImage(
                            image = source.asImageBitmap(),
                            dstOffset = IntOffset(offX.toInt(), offY.toInt()),
                            dstSize = IntSize(drawW.toInt(), drawH.toInt()),
                            alpha = 0.18f,
                        )
                        cutout?.let { bm ->
                            drawImage(
                                image = bm.asImageBitmap(),
                                dstOffset = IntOffset(offX.toInt(), offY.toInt()),
                                dstSize = IntSize(drawW.toInt(), drawH.toInt()),
                            )
                        }

                        // Crosshair at the seed point, mapped from bitmap coords back to display.
                        val cx = offX + (seedX / source.width) * drawW
                        val cy = offY + (seedY / source.height) * drawH
                        val arm = 24f
                        val gap = 6f
                        val haloStroke = 5f
                        val stroke = 2.5f
                        drawLine(Color.Black.copy(alpha = 0.55f), Offset(cx - arm, cy), Offset(cx - gap, cy), strokeWidth = haloStroke)
                        drawLine(Color.Black.copy(alpha = 0.55f), Offset(cx + gap, cy), Offset(cx + arm, cy), strokeWidth = haloStroke)
                        drawLine(Color.Black.copy(alpha = 0.55f), Offset(cx, cy - arm), Offset(cx, cy - gap), strokeWidth = haloStroke)
                        drawLine(Color.Black.copy(alpha = 0.55f), Offset(cx, cy + gap), Offset(cx, cy + arm), strokeWidth = haloStroke)
                        drawLine(Color.White, Offset(cx - arm, cy), Offset(cx - gap, cy), strokeWidth = stroke)
                        drawLine(Color.White, Offset(cx + gap, cy), Offset(cx + arm, cy), strokeWidth = stroke)
                        drawLine(Color.White, Offset(cx, cy - arm), Offset(cx, cy - gap), strokeWidth = stroke)
                        drawLine(Color.White, Offset(cx, cy + gap), Offset(cx, cy + arm), strokeWidth = stroke)
                        drawCircle(Color.White, radius = 3f, center = Offset(cx, cy))
                        drawCircle(Color.Black.copy(alpha = 0.55f), radius = 5f, center = Offset(cx, cy), style = Stroke(width = 1.5f))
                    },
                )

                if (isComputing) {
                    Column(
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.spacedBy(6.dp),
                    ) {
                        CircularProgressIndicator()
                        Text(
                            stringResource(R.string.local_bg_recomputing),
                            color = Color.White,
                            style = MaterialTheme.typography.bodySmall,
                        )
                    }
                } else if (failed && cutout == null) {
                    Text(
                        stringResource(R.string.local_bg_failed),
                        color = Color.White,
                        style = MaterialTheme.typography.bodyMedium,
                        modifier = Modifier.padding(16.dp),
                    )
                }
            }

            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(16.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                if (skippable) {
                    OutlinedButton(
                        onClick = onSkip,
                        modifier = Modifier.weight(1f),
                    ) { Text(stringResource(R.string.local_bg_skip)) }
                }
                Button(
                    onClick = {
                        val bm = cutout ?: return@Button
                        scope.launch {
                            val out = withContext(Dispatchers.IO) {
                                val cropped = tightCropToAlpha(bm)
                                val f = File(context.cacheDir, "local_bg_${System.currentTimeMillis()}.png")
                                f.outputStream().buffered().use { cropped.compress(Bitmap.CompressFormat.PNG, 100, it) }
                                if (cropped !== bm) cropped.recycle()
                                f
                            }
                            onApply(out)
                        }
                    },
                    enabled = cutout != null && !isComputing,
                    modifier = Modifier.weight(1f),
                ) { Text(stringResource(R.string.local_bg_apply)) }
            }
        }
    }
}

/**
 * Decode [path] downsampled so its longest edge is at most [maxEdge] pixels. Matches the standard
 * import-time downscaling: keeps segmentation fast while preserving the user's framing.
 */
/**
 * Crop [src] to the bounding box of its non-transparent pixels (with a small safety margin) so the
 * stored cutout doesn't carry the raw photo's empty space around the item — matching the framing
 * Gemini's removeBackground call produces.
 */
private fun tightCropToAlpha(src: Bitmap): Bitmap {
    val w = src.width
    val h = src.height
    if (w <= 0 || h <= 0) return src
    val pixels = IntArray(w * h)
    src.getPixels(pixels, 0, w, 0, 0, w, h)
    var minX = w
    var minY = h
    var maxX = -1
    var maxY = -1
    val alphaCutoff = 8 // ignore near-zero alpha noise
    for (y in 0 until h) {
        val row = y * w
        for (x in 0 until w) {
            val a = (pixels[row + x] ushr 24) and 0xFF
            if (a >= alphaCutoff) {
                if (x < minX) minX = x
                if (x > maxX) maxX = x
                if (y < minY) minY = y
                if (y > maxY) maxY = y
            }
        }
    }
    if (maxX < 0 || maxY < 0) return src
    val pad = (maxOf(w, h) * 0.02f).toInt().coerceAtLeast(2)
    val left = (minX - pad).coerceAtLeast(0)
    val top = (minY - pad).coerceAtLeast(0)
    val right = (maxX + pad).coerceAtMost(w - 1)
    val bottom = (maxY + pad).coerceAtMost(h - 1)
    val cw = right - left + 1
    val ch = bottom - top + 1
    if (cw == w && ch == h) return src
    return Bitmap.createBitmap(src, left, top, cw, ch)
}

private fun decodeDownsampled(path: String, maxEdge: Int): Bitmap? {
    val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
    BitmapFactory.decodeFile(path, bounds)
    if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null
    var sample = 1
    val longest = maxOf(bounds.outWidth, bounds.outHeight)
    while (longest / (sample * 2) >= maxEdge) sample *= 2
    val opts = BitmapFactory.Options().apply {
        inSampleSize = sample
        inPreferredConfig = Bitmap.Config.ARGB_8888
    }
    return BitmapFactory.decodeFile(path, opts)
}
