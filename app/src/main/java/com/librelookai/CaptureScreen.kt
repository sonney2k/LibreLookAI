package com.librelookai

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Matrix
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
import androidx.compose.material.icons.automirrored.filled.RotateRight
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
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

@Composable
fun CaptureScreen(
    onPhotoTaken: (File) -> Unit,
    onCancel: () -> Unit,
    locations: List<Location> = emptyList(),
    importTargetFolderId: String? = null,
    onSetImportTarget: (String) -> Unit = {},
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    var imageCapture by remember { mutableStateOf<ImageCapture?>(null) }
    var isCapturing by remember { mutableStateOf(false) }

    // Non-null → show review screen
    var capturedFile by remember { mutableStateOf<File?>(null) }
    var userRotation by remember { mutableIntStateOf(0) }

    if (capturedFile == null) {
        // ── Camera viewfinder ──────────────────────────────────────────────
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
                modifier = Modifier.fillMaxSize(),
            )

            IconButton(
                onClick = onCancel,
                modifier = Modifier
                    .align(Alignment.TopStart)
                    .padding(8.dp),
            ) {
                Icon(Icons.Default.Close, contentDescription = "Cancel", tint = Color.White)
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
                        isCapturing = true
                        val file = File(context.cacheDir, "capture_${System.currentTimeMillis()}.jpg")
                        cap.takePicture(
                            ImageCapture.OutputFileOptions.Builder(file).build(),
                            ContextCompat.getMainExecutor(context),
                            object : ImageCapture.OnImageSavedCallback {
                                override fun onImageSaved(output: ImageCapture.OutputFileResults) {
                                    isCapturing = false
                                    capturedFile = file
                                    userRotation = 0
                                }
                                override fun onError(exc: ImageCaptureException) {
                                    isCapturing = false
                                }
                            },
                        )
                    },
            )

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
            onClick = onRetake,
            enabled = !isProcessing,
            modifier = Modifier
                .align(Alignment.TopEnd)
                .padding(8.dp),
        ) {
            Icon(Icons.Default.CameraAlt, contentDescription = "Retake", tint = Color.White)
        }

        // Rotate button (bottom-left)
        IconButton(
            onClick = onRotate,
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
