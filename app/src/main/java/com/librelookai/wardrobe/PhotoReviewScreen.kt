package com.librelookai.wardrobe

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Matrix
import android.media.ExifInterface
import androidx.activity.compose.BackHandler
import androidx.camera.view.PreviewView
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.RotateRight
import androidx.compose.material.icons.filled.CameraAlt
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.unit.dp
import com.librelookai.data.model.Location
import com.librelookai.util.Analytics
import java.io.File
import java.io.FileOutputStream
import kotlin.math.abs
import kotlin.math.roundToInt
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

@Composable
internal fun PhotoReviewScreen(
    file: File,
    userRotation: Int,
    onRotate: () -> Unit,
    onConfirm: (File, Int) -> Unit,
    onRetake: () -> Unit,
    onClose: () -> Unit,
    locations: List<Location> = emptyList(),
    importTargetFolderId: String? = null,
    onSetImportTarget: (String) -> Unit = {},
    modifier: Modifier = Modifier,
) {
    val scope = rememberCoroutineScope()
    var isProcessing by remember { mutableStateOf(false) }

    // Load the bitmap with EXIF orientation already baked in (once per file)
    var displayBitmap by remember { mutableStateOf<Bitmap?>(null) }
    LaunchedEffect(file) {
        withContext(Dispatchers.IO) {
            displayBitmap = loadBitmapWithExif(file)
        }
    }

    // Back press in the review step should return to the viewfinder (retake), not exit the
    // camera entirely — the user has already invested in framing the shot.
    BackHandler(enabled = !isProcessing) { onRetake() }

    Box(
        modifier = modifier
            .fillMaxSize()
            .background(Color.Black),
    ) {
        val bm = displayBitmap
        if (bm != null) {
            androidx.compose.foundation.Image(
                bitmap = bm.asImageBitmap(),
                contentDescription = null,
                contentScale = ContentScale.Fit,
                modifier = Modifier
                    .fillMaxSize()
                    .rotate(userRotation.toFloat()),
            )
        } else {
            CircularProgressIndicator(
                modifier = Modifier.align(Alignment.Center),
                color = Color.White,
            )
        }

        // Close camera (top-left)
        IconButton(
            onClick = onClose,
            enabled = !isProcessing,
            modifier = Modifier
                .align(Alignment.TopStart)
                .padding(8.dp),
        ) {
            Icon(Icons.Default.Close, contentDescription = "Close camera", tint = Color.White)
        }

        // Inline closet selector — top-center, visible when 2+ closets
        if (locations.size >= 2) {
            ClosetChip(
                locations = locations,
                importTargetFolderId = importTargetFolderId,
                onSetImportTarget = onSetImportTarget,
                modifier = Modifier
                    .align(Alignment.TopCenter)
                    .padding(8.dp),
            )
        }

        // Retake (top-right)
        IconButton(
            onClick = {
                Analytics.action("Capture/Review", "retake")
                onRetake()
            },
            enabled = !isProcessing,
            modifier = Modifier
                .align(Alignment.TopEnd)
                .padding(8.dp),
        ) {
            Icon(Icons.Default.CameraAlt, contentDescription = "Retake", tint = Color.White)
        }

        // Rotate button (bottom-left)
        IconButton(
            onClick = {
                Analytics.action("Capture/Review", "rotate")
                onRotate()
            },
            enabled = !isProcessing && bm != null,
            modifier = Modifier
                .align(Alignment.BottomStart)
                .navigationBarsPadding()
                .padding(start = 24.dp, bottom = 32.dp)
                .size(56.dp)
                .clip(CircleShape)
                .background(Color.Black.copy(alpha = 0.5f)),
        ) {
            Icon(
                Icons.AutoMirrored.Filled.RotateRight,
                contentDescription = "Rotate 90°",
                tint = Color.White,
                modifier = Modifier.size(28.dp),
            )
        }

        // Confirm button (bottom-right) with the imminent pipeline cost badge above it.
        // After confirm, the camera flow runs Gemini BG removal + classify automatically; the
        // badge surfaces that cost up-front so the user isn't surprised in Insights later.
        Column(
            modifier = Modifier
                .align(Alignment.BottomEnd)
                .navigationBarsPadding()
                .padding(end = 24.dp, bottom = 32.dp),
            horizontalAlignment = Alignment.End,
        ) {
            com.librelookai.billing.CostBadge(
                com.librelookai.gemini.GeminiActionId.REMOVE_BACKGROUND,
                tokens = com.librelookai.billing.rememberRemoveBgCostTokens(bm?.width ?: 0, bm?.height ?: 0),
            )
            Spacer(Modifier.height(6.dp))
            IconButton(
                onClick = {
                    if (!isProcessing && bm != null) {
                        Analytics.action("Capture/Review", "confirm")
                        isProcessing = true
                        val rotation = userRotation
                        scope.launch(Dispatchers.IO) {
                            val outFile = bakeRotation(bm, rotation, file)
                            withContext(Dispatchers.Main) {
                                isProcessing = false
                                onConfirm(outFile, rotation)
                            }
                        }
                    }
                },
                enabled = !isProcessing && bm != null,
                modifier = Modifier
                    .size(56.dp)
                    .clip(CircleShape)
                    .background(MaterialTheme.colorScheme.primary),
            ) {
                if (isProcessing) {
                    CircularProgressIndicator(modifier = Modifier.size(24.dp), color = Color.White)
                } else {
                    Icon(
                        Icons.Default.Check,
                        contentDescription = "Use photo",
                        tint = Color.White,
                        modifier = Modifier.size(28.dp),
                    )
                }
            }
        }
    }
}

/**
 * Decode [file] as a Bitmap with any EXIF orientation already applied to the pixels.
 * Result orientation tag is NORMAL.
 */
private fun loadBitmapWithExif(file: File): Bitmap {
    val exif = ExifInterface(file.absolutePath)
    val orientation = exif.getAttributeInt(
        ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_NORMAL
    )
    val raw = BitmapFactory.decodeFile(file.absolutePath)
    val matrix = Matrix()
    when (orientation) {
        ExifInterface.ORIENTATION_ROTATE_90 -> matrix.postRotate(90f)
        ExifInterface.ORIENTATION_ROTATE_180 -> matrix.postRotate(180f)
        ExifInterface.ORIENTATION_ROTATE_270 -> matrix.postRotate(270f)
        ExifInterface.ORIENTATION_FLIP_HORIZONTAL -> matrix.preScale(-1f, 1f)
        ExifInterface.ORIENTATION_FLIP_VERTICAL -> matrix.preScale(1f, -1f)
        ExifInterface.ORIENTATION_TRANSPOSE -> { matrix.preScale(-1f, 1f); matrix.postRotate(90f) }
        ExifInterface.ORIENTATION_TRANSVERSE -> { matrix.preScale(-1f, 1f); matrix.postRotate(-90f) }
        else -> return raw
    }
    return Bitmap.createBitmap(raw, 0, 0, raw.width, raw.height, matrix, true)
        .also { if (it !== raw) raw.recycle() }
}

/**
 * Apply [userRotation] degrees (CW) to [exifNormalBitmap] (which already has EXIF baked in),
 * compress to JPEG, and write to a new cache file. The original [source] file is deleted.
 * Returns the new file.
 */
private fun bakeRotation(exifNormalBitmap: Bitmap, userRotation: Int, source: File): File {
    // Don't recycle [exifNormalBitmap]: it's still referenced by Compose's review screen until
    // the next frame, and recycling here races with the UI thread (crashes "trying to use a
    // recycled bitmap"). Let GC reclaim it once the screen is gone.
    val final = if (userRotation != 0) {
        val m = Matrix().apply { postRotate(userRotation.toFloat()) }
        Bitmap.createBitmap(exifNormalBitmap, 0, 0, exifNormalBitmap.width, exifNormalBitmap.height, m, true)
    } else {
        exifNormalBitmap
    }
    val outFile = File(source.parent, "capture_${System.currentTimeMillis()}_baked.jpg")
    FileOutputStream(outFile).use { out ->
        final.compress(Bitmap.CompressFormat.JPEG, 95, out)
    }
    // Ensure orientation tag is NORMAL so downstream code never needs to re-read EXIF
    val exif = ExifInterface(outFile.absolutePath)
    exif.setAttribute(ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_NORMAL.toString())
    exif.saveAttributes()
    source.delete()
    return outFile
}

/**
 * Crop [file] to match the preview aspect ratio (center-crop, matching PreviewView FILL_CENTER)
 * and resize so max(width, height) ≤ 1280. Overwrites the file in place with EXIF orientation NORMAL.
 */
internal fun cropToPreviewAndResize(file: File, previewWidth: Int, previewHeight: Int) {
    val bitmap = loadBitmapWithExif(file)
    val imgW = bitmap.width
    val imgH = bitmap.height

    // Center-crop to match the preview's aspect ratio
    val cropped = if (previewWidth > 0 && previewHeight > 0) {
        val previewRatio = previewWidth.toFloat() / previewHeight.toFloat()
        val imgRatio = imgW.toFloat() / imgH.toFloat()
        if (abs(imgRatio - previewRatio) > 0.01f) {
            val cropW: Int
            val cropH: Int
            if (imgRatio > previewRatio) {
                // Image wider than preview → crop sides
                cropH = imgH
                cropW = (imgH * previewRatio).roundToInt()
            } else {
                // Image taller than preview → crop top/bottom
                cropW = imgW
                cropH = (imgW / previewRatio).roundToInt()
            }
            val x = (imgW - cropW) / 2
            val y = (imgH - cropH) / 2
            Bitmap.createBitmap(bitmap, x, y, cropW, cropH).also {
                if (it !== bitmap) bitmap.recycle()
            }
        } else {
            bitmap
        }
    } else {
        bitmap
    }

    // Resize so max(width, height) ≤ 1280
    val maxDim = maxOf(cropped.width, cropped.height)
    val resized = if (maxDim > 1280) {
        val scale = 1280f / maxDim
        val newW = (cropped.width * scale).roundToInt()
        val newH = (cropped.height * scale).roundToInt()
        Bitmap.createScaledBitmap(cropped, newW, newH, true).also {
            if (it !== cropped) cropped.recycle()
        }
    } else {
        cropped
    }

    FileOutputStream(file).use { out ->
        resized.compress(Bitmap.CompressFormat.JPEG, 95, out)
    }
    resized.recycle()

    val exif = ExifInterface(file.absolutePath)
    exif.setAttribute(ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_NORMAL.toString())
    exif.saveAttributes()
}

/**
 * Crosshair overlay shown at the viewfinder center to communicate that the center pixel is used as
 * the seed point for on-device foreground segmentation. Caller positions it via the alignment
 * modifier — its own size is fixed (~64 dp).
 */
