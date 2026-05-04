package com.librelookai

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Matrix
import androidx.activity.compose.BackHandler
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageCapture
import androidx.camera.core.ImageCaptureException
import androidx.camera.core.Preview
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CameraAlt
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Link
import androidx.compose.material.icons.filled.PhotoLibrary
import androidx.compose.material.icons.automirrored.filled.RotateRight
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import android.app.Activity
import android.content.pm.ActivityInfo
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
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
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import android.media.ExifInterface
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.material.icons.filled.ArrowDropDown
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.ui.unit.Dp
import androidx.lifecycle.compose.LocalLifecycleOwner
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream
import kotlin.math.abs
import kotlin.math.roundToInt

@Composable
fun CaptureScreen(
    onPhotoTaken: (File) -> Unit,
    onCancel: () -> Unit,
    locations: List<Location> = emptyList(),
    importTargetFolderId: String? = null,
    onSetImportTarget: (String) -> Unit = {},
    showCenterCrosshair: Boolean = false,
    onOpenGallery: (() -> Unit)? = null,
    onOpenUrlImport: (() -> Unit)? = null,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    DisposableEffect(Unit) {
        val activity = context as? Activity
        val previous = activity?.requestedOrientation
        activity?.requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_FULL_SENSOR
        onDispose {
            activity?.requestedOrientation =
                previous ?: ActivityInfo.SCREEN_ORIENTATION_PORTRAIT
        }
    }
    val showImportFabs = onOpenGallery != null || onOpenUrlImport != null
    val lifecycleOwner = LocalLifecycleOwner.current
    var imageCapture by remember { mutableStateOf<ImageCapture?>(null) }
    var isCapturing by remember { mutableStateOf(false) }

    // Non-null → show review screen
    var capturedFile by remember { mutableStateOf<File?>(null) }
    var userRotation by remember { mutableIntStateOf(0) }

    // Track preview dimensions to crop captured image to match viewfinder
    var previewWidth by remember { mutableIntStateOf(0) }
    var previewHeight by remember { mutableIntStateOf(0) }

    // Intermediate state: raw capture file triggers crop+resize before showing review
    var rawCaptureFile by remember { mutableStateOf<File?>(null) }
    LaunchedEffect(rawCaptureFile) {
        val f = rawCaptureFile ?: return@LaunchedEffect
        val pw = previewWidth
        val ph = previewHeight
        withContext(Dispatchers.IO) {
            cropToPreviewAndResize(f, pw, ph)
        }
        isCapturing = false
        capturedFile = f
        userRotation = 0
    }

    if (capturedFile == null) {
        // ── Camera viewfinder ──────────────────────────────────────────────
        BackHandler(enabled = !isCapturing) { onCancel() }
        Box(modifier = modifier.fillMaxSize()) {
            AndroidView(
                factory = { ctx ->
                    PreviewView(ctx).also { previewView ->
                        val future = ProcessCameraProvider.getInstance(ctx)
                        future.addListener({
                            val capture = ImageCapture.Builder()
                                .setCaptureMode(ImageCapture.CAPTURE_MODE_MAXIMIZE_QUALITY)
                                .build()
                            imageCapture = capture
                            val preview = Preview.Builder().build()
                                .also { it.setSurfaceProvider(previewView.surfaceProvider) }
                            try {
                                future.get().unbindAll()
                                future.get().bindToLifecycle(
                                    lifecycleOwner,
                                    CameraSelector.DEFAULT_BACK_CAMERA,
                                    preview,
                                    capture,
                                )
                            } catch (e: Exception) {
                                e.printStackTrace()
                            }
                        }, ContextCompat.getMainExecutor(ctx))
                    }
                },
                modifier = Modifier
                    .fillMaxSize()
                    .onGloballyPositioned { coordinates ->
                        previewWidth = coordinates.size.width
                        previewHeight = coordinates.size.height
                    },
            )

            androidx.compose.foundation.layout.Column(
                modifier = Modifier.align(Alignment.Center),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                CenterCrosshair()
                if (showCenterCrosshair) {
                    Surface(
                        shape = MaterialTheme.shapes.small,
                        color = Color.Black.copy(alpha = 0.55f),
                        contentColor = Color.White,
                    ) {
                        Text(
                            androidx.compose.ui.res.stringResource(R.string.shop_crosshair_hint),
                            style = MaterialTheme.typography.labelSmall,
                            modifier = Modifier.padding(horizontal = 10.dp, vertical = 4.dp),
                        )
                    }
                }
            }

            if (!showImportFabs) {
                IconButton(
                    onClick = onCancel,
                    modifier = Modifier
                        .align(Alignment.TopStart)
                        .padding(8.dp),
                ) {
                    Icon(Icons.Default.Close, contentDescription = "Cancel", tint = Color.White)
                }
            }

            // Inline closet selector — top-end, visible when 2+ closets
            if (locations.size >= 2) {
                ClosetChip(
                    locations = locations,
                    importTargetFolderId = importTargetFolderId,
                    onSetImportTarget = onSetImportTarget,
                    modifier = Modifier
                        .align(Alignment.TopEnd)
                        .padding(8.dp),
                )
            }

            Box(
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .navigationBarsPadding()
                    .padding(bottom = 32.dp)
                    .size(72.dp)
                    .clip(CircleShape)
                    .border(4.dp, Color.White, CircleShape)
                    .clickable(enabled = !isCapturing) {
                        val cap = imageCapture ?: return@clickable
                        Analytics.action("Capture", "shutter")
                        isCapturing = true
                        val file = File(context.cacheDir, "capture_${System.currentTimeMillis()}.jpg")
                        cap.takePicture(
                            ImageCapture.OutputFileOptions.Builder(file).build(),
                            ContextCompat.getMainExecutor(context),
                            object : ImageCapture.OnImageSavedCallback {
                                override fun onImageSaved(output: ImageCapture.OutputFileResults) {
                                    rawCaptureFile = file // triggers crop+resize LaunchedEffect
                                }
                                override fun onError(exc: ImageCaptureException) {
                                    isCapturing = false
                                }
                            },
                        )
                    },
            )

            if (showImportFabs) {
                androidx.compose.foundation.layout.Column(
                    modifier = Modifier
                        .align(Alignment.BottomEnd)
                        .navigationBarsPadding()
                        .padding(16.dp),
                    horizontalAlignment = Alignment.End,
                    verticalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    onOpenUrlImport?.let { action ->
                        FloatingActionButton(onClick = {
                            Analytics.action("Capture", "open_url_import_dialog")
                            action()
                        }) {
                            Icon(Icons.Default.Link, contentDescription = "Import from URL")
                        }
                    }
                    onOpenGallery?.let { action ->
                        FloatingActionButton(onClick = {
                            Analytics.action("Capture", "open_gallery")
                            action()
                        }) {
                            Icon(Icons.Default.PhotoLibrary, contentDescription = "Import from gallery")
                        }
                    }
                    FloatingActionButton(onClick = onCancel) {
                        Icon(Icons.Default.Close, contentDescription = "Cancel")
                    }
                }
            }

            if (isCapturing) {
                CircularProgressIndicator(
                    modifier = Modifier.align(Alignment.Center),
                    color = Color.White,
                )
            }
        }
    } else {
        // ── Review / rotate screen ─────────────────────────────────────────
        PhotoReviewScreen(
            file = capturedFile!!,
            userRotation = userRotation,
            onRotate = { userRotation = (userRotation + 90) % 360 },
            onConfirm = { file, rotation ->
                capturedFile = null
                userRotation = 0
                onPhotoTaken(file)
            },
            onRetake = {
                capturedFile = null
                userRotation = 0
            },
            onClose = onCancel,
            locations = locations,
            importTargetFolderId = importTargetFolderId,
            onSetImportTarget = onSetImportTarget,
            modifier = modifier,
        )
    }
}

@Composable
private fun PhotoReviewScreen(
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

        // Confirm button (bottom-right)
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
                .align(Alignment.BottomEnd)
                .navigationBarsPadding()
                .padding(end = 24.dp, bottom = 32.dp)
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
    val final = if (userRotation != 0) {
        val m = Matrix().apply { postRotate(userRotation.toFloat()) }
        Bitmap.createBitmap(exifNormalBitmap, 0, 0, exifNormalBitmap.width, exifNormalBitmap.height, m, true)
            .also { if (it !== exifNormalBitmap) exifNormalBitmap.recycle() }
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
private fun cropToPreviewAndResize(file: File, previewWidth: Int, previewHeight: Int) {
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
@Composable
private fun CenterCrosshair(modifier: Modifier = Modifier) {
    androidx.compose.foundation.Canvas(
        modifier = modifier.size(64.dp),
    ) {
        val w = size.width
        val h = size.height
        val cx = w / 2f
        val cy = h / 2f
        val arm = w * 0.45f
        val gap = w * 0.12f
        val stroke = 2.dp.toPx()
        val shadowStroke = stroke + 1.5.dp.toPx()
        // Faint dark halo for contrast over light backgrounds
        drawLine(Color.Black.copy(alpha = 0.55f), start = androidx.compose.ui.geometry.Offset(cx - arm, cy), end = androidx.compose.ui.geometry.Offset(cx - gap, cy), strokeWidth = shadowStroke)
        drawLine(Color.Black.copy(alpha = 0.55f), start = androidx.compose.ui.geometry.Offset(cx + gap, cy), end = androidx.compose.ui.geometry.Offset(cx + arm, cy), strokeWidth = shadowStroke)
        drawLine(Color.Black.copy(alpha = 0.55f), start = androidx.compose.ui.geometry.Offset(cx, cy - arm), end = androidx.compose.ui.geometry.Offset(cx, cy - gap), strokeWidth = shadowStroke)
        drawLine(Color.Black.copy(alpha = 0.55f), start = androidx.compose.ui.geometry.Offset(cx, cy + gap), end = androidx.compose.ui.geometry.Offset(cx, cy + arm), strokeWidth = shadowStroke)
        // White arms
        drawLine(Color.White, start = androidx.compose.ui.geometry.Offset(cx - arm, cy), end = androidx.compose.ui.geometry.Offset(cx - gap, cy), strokeWidth = stroke)
        drawLine(Color.White, start = androidx.compose.ui.geometry.Offset(cx + gap, cy), end = androidx.compose.ui.geometry.Offset(cx + arm, cy), strokeWidth = stroke)
        drawLine(Color.White, start = androidx.compose.ui.geometry.Offset(cx, cy - arm), end = androidx.compose.ui.geometry.Offset(cx, cy - gap), strokeWidth = stroke)
        drawLine(Color.White, start = androidx.compose.ui.geometry.Offset(cx, cy + gap), end = androidx.compose.ui.geometry.Offset(cx, cy + arm), strokeWidth = stroke)
        // Center dot
        drawCircle(Color.White, radius = 2.dp.toPx(), center = androidx.compose.ui.geometry.Offset(cx, cy))
        drawCircle(Color.Black.copy(alpha = 0.55f), radius = 3.dp.toPx(), center = androidx.compose.ui.geometry.Offset(cx, cy), style = androidx.compose.ui.graphics.drawscope.Stroke(width = 1.dp.toPx()))
    }
}

@Composable
private fun ClosetChip(
    locations: List<Location>,
    importTargetFolderId: String?,
    onSetImportTarget: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    val targetFolderId = importTargetFolderId ?: locations.firstOrNull()?.folderId
    val targetName = locations.find { it.folderId == targetFolderId }?.name
        ?: locations.firstOrNull()?.name ?: ""
    var expanded by remember { mutableStateOf(false) }
    Box(modifier = modifier) {
        Surface(
            onClick = { expanded = true },
            shape = MaterialTheme.shapes.small,
            color = Color.Black.copy(alpha = 0.5f),
            contentColor = Color.White,
        ) {
            Row(
                modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                Text(targetName, style = MaterialTheme.typography.labelMedium)
                Icon(Icons.Default.ArrowDropDown, contentDescription = null, modifier = Modifier.size(18.dp))
            }
        }
        DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
            locations.sortedBy { it.name }.forEach { loc ->
                val checked = loc.folderId == targetFolderId
                DropdownMenuItem(
                    text = {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                        ) {
                            if (checked) Icon(Icons.Default.Check, null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(18.dp))
                            else androidx.compose.foundation.layout.Spacer(Modifier.size(18.dp))
                            Text(loc.name)
                        }
                    },
                    onClick = {
                        onSetImportTarget(loc.folderId)
                        expanded = false
                    },
                )
            }
        }
    }
}
