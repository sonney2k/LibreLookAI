package com.librelookai.shopping

import android.graphics.BitmapFactory
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.calculatePan
import androidx.compose.foundation.gestures.calculateZoom
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Tab
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import coil.compose.AsyncImage
import coil.request.ImageRequest
import com.librelookai.ml.ColorHistogram
import com.librelookai.ml.EmbeddingIndex
import com.librelookai.ml.EmbeddingRepository
import com.librelookai.ml.EmbeddingService
import com.librelookai.ml.HIST_DIM
import com.librelookai.ml.HIST_H_BINS
import com.librelookai.ml.HIST_S_BINS
import com.librelookai.ml.HIST_V_BINS
import com.librelookai.ml.PHash
import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

@Composable
fun MatchDebugPage(
    match: ShopMatch,
    queryRawPath: String?,
    queryProcessedPath: String?,
    querySegmented: Boolean,
    queryHist: FloatArray?,
    queryVec: FloatArray?,
    queryPHash: Long?,
) {
    // Pull the match's stored embedding + histogram from the persistent index. produceState
    // re-fetches whenever the visible page changes so HorizontalPager works smoothly.
    val matchEntry by produceState<EmbeddingIndex.Entry?>(initialValue = null, match.image.driveId) {
        value = withContext(Dispatchers.IO) { EmbeddingService.index.entry(match.image.driveId) }
    }

    val combinedPercent = (match.score.coerceIn(0f, 1f) * 100f).toInt()
    val embCos = remember(queryVec, matchEntry) {
        val q = queryVec; val e = matchEntry?.vec
        if (q != null && e != null && q.size == e.size) dot(q, e) else null
    }
    val histCos = remember(queryHist, matchEntry) {
        val q = queryHist; val h = matchEntry?.hist
        if (q != null && h != null) ColorHistogram.intersection(q, h) else null
    }
    // Best Hamming similarity between the query's canonical pHash and the match's 24 rotated
    // hashes. Mirrors the rotation-invariant pHash term that `EmbeddingIndex.search` folds into
    // the combined score.
    val pHashSim = remember(queryPHash, matchEntry) {
        val q = queryPHash; val hashes = matchEntry?.pHashes
        if (q != null && hashes != null && hashes.isNotEmpty()) PHash.bestSimilarity(q, hashes) else null
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 12.dp, vertical = 8.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Surface(
            color = Color(0x22FFFFFF),
            shape = RoundedCornerShape(8.dp),
            modifier = Modifier.fillMaxWidth(),
        ) {
            Column(modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp)) {
                Text(
                    "Combined match: $combinedPercent%",
                    color = Color.White,
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.SemiBold,
                )
                Text(
                    "embedding cos = ${formatCos(embCos)}   ·   histogram ∩ = ${formatCos(histCos)}   ·   pHash sim = ${formatCos(pHashSim)}",
                    color = Color.White.copy(alpha = 0.8f),
                    style = MaterialTheme.typography.labelSmall,
                )
            }
        }

        val processedLabel = if (querySegmented) {
            "Query  (raw → segmented + composited + cropped)"
        } else {
            "Query  (raw → cropped — segmentation FAILED, embedded raw frame)"
        }
        Text(
            processedLabel,
            color = if (querySegmented) Color.White.copy(alpha = 0.85f)
                    else Color(0xFFFFB4A0),
            style = MaterialTheme.typography.labelMedium,
            fontWeight = FontWeight.SemiBold,
        )
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            DebugThumbAsync(
                label = "raw",
                path = queryRawPath,
                modifier = Modifier.weight(1f),
            )
            ProcessedBboxThumb(
                label = "processed",
                file = queryProcessedPath?.let { File(it) },
                alreadyPrepared = true,
                fallbackText = "(segmentation failed)",
                modifier = Modifier.weight(1f),
            )
        }

        Text(
            "Match  (cutout → composited + cropped)",
            color = Color.White.copy(alpha = 0.85f),
            style = MaterialTheme.typography.labelMedium,
            fontWeight = FontWeight.SemiBold,
        )
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            DebugThumbAsync(
                label = "raw",
                path = match.image.localPath,
                modifier = Modifier.weight(1f),
            )
            ProcessedBboxThumb(
                label = "processed",
                file = File(match.image.localPath),
                alreadyPrepared = false,
                modifier = Modifier.weight(1f),
            )
        }

        Text(
            "Hue histograms  (12 bins, summed across S × V)",
            color = Color.White.copy(alpha = 0.85f),
            style = MaterialTheme.typography.labelMedium,
            fontWeight = FontWeight.SemiBold,
        )
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            HueHistogramPanel(
                title = "query",
                hist = queryHist,
                modifier = Modifier.weight(1f),
            )
            HueHistogramPanel(
                title = "match",
                hist = matchEntry?.hist,
                modifier = Modifier.weight(1f),
            )
        }

        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(280.dp)
                .clip(RoundedCornerShape(8.dp))
                .background(Color(0x11FFFFFF)),
            contentAlignment = Alignment.Center,
        ) {
            ZoomableMatchImage(file = File(match.image.localPath))
        }
    }
}

@Composable
private fun DebugThumbAsync(
    label: String,
    path: String?,
    modifier: Modifier = Modifier,
    fallbackText: String? = null,
) {
    val context = LocalContext.current
    Column(
        modifier = modifier,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text(
            label,
            color = Color.White.copy(alpha = 0.7f),
            style = MaterialTheme.typography.labelSmall,
            maxLines = 1,
        )
        Spacer(Modifier.height(2.dp))
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .aspectRatio(1f)
                .clip(RoundedCornerShape(6.dp))
                .background(Color(0x22FFFFFF)),
            contentAlignment = Alignment.Center,
        ) {
            if (path != null) {
                AsyncImage(
                    model = ImageRequest.Builder(context).data(File(path)).crossfade(true).build(),
                    contentDescription = null,
                    contentScale = ContentScale.Fit,
                    modifier = Modifier.fillMaxSize(),
                )
            } else if (fallbackText != null) {
                Text(
                    fallbackText,
                    color = Color.White.copy(alpha = 0.6f),
                    style = MaterialTheme.typography.labelSmall,
                )
            }
        }
    }
}

/**
 * Renders a similarity-debug "processed" thumb with the histogram-considered bbox drawn on top.
 * If [alreadyPrepared] is true the file is loaded as-is (already composited on black by
 * `EmbeddingService.findSimilarWithDebug`); otherwise it is run through `prepareForEmbedding`.
 */
@Composable
private fun ProcessedBboxThumb(
    label: String,
    file: File?,
    alreadyPrepared: Boolean,
    modifier: Modifier = Modifier,
    fallbackText: String? = null,
) {
    data class Prepared(val bitmap: android.graphics.Bitmap, val bbox: android.graphics.Rect?)
    val prepared by produceState<Prepared?>(initialValue = null, file?.absolutePath, alreadyPrepared) {
        value = withContext(Dispatchers.IO) {
            val path = file?.absolutePath ?: return@withContext null
            val src = runCatching { BitmapFactory.decodeFile(path) }.getOrNull()
                ?: return@withContext null
            val bm = if (alreadyPrepared) src else {
                try { EmbeddingRepository.prepareForEmbedding(src) }
                finally { if (!src.isRecycled) src.recycle() }
            } ?: return@withContext null
            Prepared(bm, ColorHistogram.consideredBbox(bm))
        }
    }

    Column(
        modifier = modifier,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text(
            label,
            color = Color.White.copy(alpha = 0.7f),
            style = MaterialTheme.typography.labelSmall,
            maxLines = 1,
        )
        Spacer(Modifier.height(2.dp))
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .aspectRatio(1f)
                .clip(RoundedCornerShape(6.dp))
                .background(Color.Black),
            contentAlignment = Alignment.Center,
        ) {
            val p = prepared
            if (p != null) {
                val bm = p.bitmap
                Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    Image(
                        bitmap = bm.asImageBitmap(),
                        contentDescription = null,
                        contentScale = ContentScale.Fit,
                        modifier = Modifier.fillMaxSize(),
                    )
                    val bbox = p.bbox
                    if (bbox != null) {
                        androidx.compose.foundation.Canvas(modifier = Modifier.fillMaxSize()) {
                            val bw = bm.width.toFloat()
                            val bh = bm.height.toFloat()
                            if (bw <= 0f || bh <= 0f) return@Canvas
                            val scale = minOf(size.width / bw, size.height / bh)
                            val drawW = bw * scale
                            val drawH = bh * scale
                            val offX = (size.width - drawW) / 2f
                            val offY = (size.height - drawH) / 2f
                            val left = offX + bbox.left * scale
                            val top = offY + bbox.top * scale
                            val w = (bbox.right - bbox.left) * scale
                            val h = (bbox.bottom - bbox.top) * scale
                            drawRect(
                                color = Color(0xFF00E676),
                                topLeft = androidx.compose.ui.geometry.Offset(left, top),
                                size = androidx.compose.ui.geometry.Size(w, h),
                                style = androidx.compose.ui.graphics.drawscope.Stroke(width = 2.dp.toPx()),
                            )
                        }
                    }
                }
            } else if (file == null && fallbackText != null) {
                Text(
                    fallbackText,
                    color = Color.White.copy(alpha = 0.6f),
                    style = MaterialTheme.typography.labelSmall,
                )
            } else {
                CircularProgressIndicator(
                    modifier = Modifier.size(24.dp),
                    strokeWidth = 2.dp,
                )
            }
        }
    }
}

@Composable
private fun HueHistogramPanel(
    title: String,
    hist: FloatArray?,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text(
            title,
            color = Color.White.copy(alpha = 0.7f),
            style = MaterialTheme.typography.labelSmall,
        )
        Spacer(Modifier.height(2.dp))
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(80.dp)
                .clip(RoundedCornerShape(6.dp))
                .background(Color(0x22FFFFFF))
                .padding(horizontal = 4.dp, vertical = 4.dp),
            contentAlignment = Alignment.BottomCenter,
        ) {
            if (hist != null && hist.size == HIST_DIM) {
                HueHistogramBars(hist = hist)
            } else {
                Text(
                    "(none)",
                    color = Color.White.copy(alpha = 0.5f),
                    style = MaterialTheme.typography.labelSmall,
                )
            }
        }
    }
}

@Composable
private fun HueHistogramBars(hist: FloatArray) {
    val hueWeights = remember(hist) {
        val out = FloatArray(HIST_H_BINS)
        for (hb in 0 until HIST_H_BINS) {
            var sum = 0f
            for (sb in 0 until HIST_S_BINS) {
                for (vb in 0 until HIST_V_BINS) {
                    sum += hist[(hb * HIST_S_BINS + sb) * HIST_V_BINS + vb]
                }
            }
            out[hb] = sum
        }
        out
    }
    val maxW = hueWeights.maxOrNull() ?: 0f
    val safeMax = if (maxW <= 0f) 1f else maxW
    Row(
        modifier = Modifier.fillMaxSize(),
        verticalAlignment = Alignment.Bottom,
        horizontalArrangement = Arrangement.spacedBy(2.dp),
    ) {
        for (hb in 0 until HIST_H_BINS) {
            val hue = (hb + 0.5f) / HIST_H_BINS * 360f
            val color = Color.hsv(hue, 0.85f, 0.95f)
            val frac = (hueWeights[hb] / safeMax).coerceIn(0f, 1f)
            Column(
                modifier = Modifier.weight(1f).fillMaxHeight(),
                verticalArrangement = Arrangement.Bottom,
            ) {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .fillMaxHeight(if (frac < 0.02f) 0.02f else frac)
                        .background(color, RoundedCornerShape(topStart = 2.dp, topEnd = 2.dp)),
                )
            }
        }
    }
}

@Composable
fun ZoomableMatchImage(file: File) {
    val context = LocalContext.current
    var scale by remember { mutableFloatStateOf(1f) }
    var offset by remember { mutableStateOf(Offset.Zero) }
    AsyncImage(
        model = ImageRequest.Builder(context).data(file).crossfade(true).build(),
        contentDescription = null,
        contentScale = ContentScale.Fit,
        modifier = Modifier
            .fillMaxSize()
            // Only intercept pinch (2 pointers) for zoom and single-finger pan once zoomed.
            // At scale 1, single-finger horizontal drags pass through to the parent
            // HorizontalPager so the user can swipe between matches.
            .pointerInput(Unit) {
                awaitEachGesture {
                    awaitFirstDown(requireUnconsumed = false)
                    do {
                        val event = awaitPointerEvent()
                        val pointerCount = event.changes.count { it.pressed }
                        if (pointerCount >= 2) {
                            val zoom = event.calculateZoom()
                            val pan = event.calculatePan()
                            val newScale = (scale * zoom).coerceIn(1f, 6f)
                            scale = newScale
                            offset = if (newScale <= 1.01f) Offset.Zero
                                     else Offset(offset.x + pan.x, offset.y + pan.y)
                            event.changes.forEach { it.consume() }
                        } else if (scale > 1.01f) {
                            val pan = event.calculatePan()
                            offset = Offset(offset.x + pan.x, offset.y + pan.y)
                            event.changes.forEach { it.consume() }
                        }
                        // else: single-finger drag at scale 1 — leave changes unconsumed so
                        // the HorizontalPager receives them.
                    } while (event.changes.any { it.pressed })
                }
            }
            .graphicsLayer {
                scaleX = scale
                scaleY = scale
                translationX = offset.x
                translationY = offset.y
            },
    )
}

private fun dot(a: FloatArray, b: FloatArray): Float {
    var s = 0f
    for (i in a.indices) s += a[i] * b[i]
    return s
}

private fun formatCos(value: Float?): String =
    if (value == null) "?" else String.format("%.3f", value)

// ============================================================================
//  Tab 2: Identify Gaps
// ============================================================================

