package com.librelookai.wardrobe

import android.app.Activity
import android.content.pm.ActivityInfo
import androidx.activity.compose.BackHandler
import androidx.camera.core.Camera
import androidx.camera.core.CameraSelector
import androidx.camera.core.FocusMeteringAction
import androidx.camera.core.ImageCapture
import androidx.camera.core.ImageCaptureException
import androidx.camera.core.Preview
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowDropDown
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Link
import androidx.compose.material.icons.filled.PhotoLibrary
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.librelookai.R
import com.librelookai.core.designsystem.R as DsR
import com.librelookai.data.model.Location
import com.librelookai.util.Analytics
import java.io.File
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

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
    var camera by remember { mutableStateOf<Camera?>(null) }
    var previewViewRef by remember { mutableStateOf<PreviewView?>(null) }
    var isCapturing by remember { mutableStateOf(false) }

    // Crosshair position in preview pixel coords; null → centered.
    var crosshairPx by remember { mutableStateOf<Offset?>(null) }

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
                                camera = future.get().bindToLifecycle(
                                    lifecycleOwner,
                                    CameraSelector.DEFAULT_BACK_CAMERA,
                                    preview,
                                    capture,
                                )
                            } catch (e: Exception) {
                                e.printStackTrace()
                            }
                        }, ContextCompat.getMainExecutor(ctx))
                        previewViewRef = previewView
                    }
                },
                modifier = Modifier
                    .fillMaxSize()
                    .onGloballyPositioned { coordinates ->
                        previewWidth = coordinates.size.width
                        previewHeight = coordinates.size.height
                    },
            )

            // Gesture overlay: tap to move crosshair + AF/AE, pinch to zoom.
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .pointerInput(Unit) {
                        detectTransformGestures { _, _, zoom, _ ->
                            val cam = camera ?: return@detectTransformGestures
                            if (zoom == 1f) return@detectTransformGestures
                            val info = cam.cameraInfo.zoomState.value ?: return@detectTransformGestures
                            val next = (info.zoomRatio * zoom)
                                .coerceIn(info.minZoomRatio, info.maxZoomRatio)
                            cam.cameraControl.setZoomRatio(next)
                        }
                    }
                    .pointerInput(Unit) {
                        detectTapGestures(onTap = { pos ->
                            crosshairPx = pos
                            val pv = previewViewRef
                            val cam = camera
                            if (pv != null && cam != null) {
                                val point = pv.meteringPointFactory.createPoint(pos.x, pos.y)
                                val action = FocusMeteringAction.Builder(
                                    point,
                                    FocusMeteringAction.FLAG_AF or FocusMeteringAction.FLAG_AE,
                                )
                                    .setAutoCancelDuration(3, TimeUnit.SECONDS)
                                    .build()
                                runCatching { cam.cameraControl.startFocusAndMetering(action) }
                            }
                        })
                    },
            )

            val density = LocalDensity.current
            val crosshairSize = 64.dp
            val crosshairSizePx = with(density) { crosshairSize.toPx() }
            val cx = crosshairPx?.x ?: (previewWidth / 2f)
            val cy = crosshairPx?.y ?: (previewHeight / 2f)
            val offsetXDp = with(density) { (cx - crosshairSizePx / 2f).toDp() }
            val offsetYDp = with(density) { (cy - crosshairSizePx / 2f).toDp() }
            Box(
                modifier = Modifier
                    .align(Alignment.TopStart)
                    .offset(offsetXDp, offsetYDp)
                    .size(crosshairSize),
            ) {
                CenterCrosshair(modifier = Modifier.fillMaxSize())
            }
            androidx.compose.foundation.layout.Column(
                modifier = Modifier.align(Alignment.Center),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
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

            // Close X is always top-left, regardless of capture/import mode, so the
            // dismiss affordance never changes position (see CLAUDE.md close-affordance rule).
            IconButton(
                onClick = onCancel,
                modifier = Modifier
                    .align(Alignment.TopStart)
                    .padding(8.dp),
            ) {
                Icon(
                    Icons.Default.Close,
                    contentDescription = androidx.compose.ui.res.stringResource(DsR.string.action_close),
                    tint = Color.White,
                )
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
internal fun ClosetChip(
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
