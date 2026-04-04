package com.librelookai

import android.Manifest
import android.content.pm.PackageManager
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AddAPhoto
import androidx.compose.material.icons.filled.PhotoLibrary
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SmallFloatingActionButton
import androidx.compose.material3.Snackbar
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.lifecycle.viewmodel.compose.viewModel
import coil.compose.AsyncImage

@Composable
fun WardrobeScreen(
    viewModel: WardrobeViewModel = viewModel(),
    modifier: Modifier = Modifier,
) {
    val state by viewModel.state.collectAsState()
    val context = LocalContext.current

    var hasCameraPermission by remember {
        mutableStateOf(
            ContextCompat.checkSelfPermission(context, Manifest.permission.CAMERA)
                == PackageManager.PERMISSION_GRANTED,
        )
    }
    val permissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission(),
    ) { granted ->
        hasCameraPermission = granted
        if (granted) viewModel.openCapture()
    }

    val galleryLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.PickMultipleVisualMedia(),
    ) { uris -> viewModel.uploadGalleryPhotos(uris) }

    when (state.view) {
        WardrobeView.GRID -> GridContent(
            state = state,
            onOpenCamera = {
                if (hasCameraPermission) viewModel.openCapture()
                else permissionLauncher.launch(Manifest.permission.CAMERA)
            },
            onOpenGallery = {
                galleryLauncher.launch(
                    PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly),
                )
            },
            onDismissError = viewModel::clearError,
            modifier = modifier,
        )
        WardrobeView.CAPTURE -> CaptureScreen(
            onPhotoTaken = viewModel::uploadPhoto,
            onCancel = viewModel::closeCapture,
            modifier = modifier,
        )
    }
}

@Composable
private fun GridContent(
    state: WardrobeUiState,
    onOpenCamera: () -> Unit,
    onOpenGallery: () -> Unit,
    onDismissError: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Box(modifier = modifier.fillMaxSize()) {
        when {
            state.isLoading -> {
                CircularProgressIndicator(modifier = Modifier.align(Alignment.Center))
            }
            state.images.isEmpty() && !state.isProcessing && !state.isUploading -> {
                Column(
                    modifier = Modifier.align(Alignment.Center),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.Center,
                ) {
                    Text("No outfits yet", style = MaterialTheme.typography.bodyLarge)
                    Spacer(Modifier.height(4.dp))
                    Text(
                        "Take a photo or pick from your gallery",
                        style = MaterialTheme.typography.bodyMedium,
                    )
                }
            }
            else -> {
                LazyVerticalGrid(
                    columns = GridCells.Fixed(3),
                    modifier = Modifier.fillMaxSize(),
                ) {
                    items(state.images, key = { it.driveId }) { image ->
                        AsyncImage(
                            model = image.localPath,
                            contentDescription = image.name,
                            modifier = Modifier
                                .aspectRatio(1f)
                                .padding(1.dp),
                            contentScale = ContentScale.Crop,
                        )
                    }
                }
            }
        }

        // Progress pill
        val overlayLabel = when {
            state.isProcessing && state.batchTotal > 1 ->
                "Removing background (${state.batchDone + 1}/${state.batchTotal})…"
            state.isUploading && state.batchTotal > 1 ->
                "Uploading (${state.batchDone + 1}/${state.batchTotal})…"
            state.isProcessing -> "Removing background…"
            state.isUploading  -> "Uploading to Drive…"
            else -> null
        }
        if (overlayLabel != null) {
            Surface(
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .padding(bottom = 88.dp),
                shape = MaterialTheme.shapes.medium,
                tonalElevation = 4.dp,
            ) {
                Row(
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 10.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    CircularProgressIndicator(modifier = Modifier.size(18.dp), strokeWidth = 2.dp)
                    Text(overlayLabel, style = MaterialTheme.typography.bodyMedium)
                }
            }
        }

        // Stacked FABs: gallery (small) above camera (main)
        Column(
            modifier = Modifier
                .align(Alignment.BottomEnd)
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
            horizontalAlignment = Alignment.End,
        ) {
            SmallFloatingActionButton(onClick = onOpenGallery) {
                Icon(Icons.Default.PhotoLibrary, contentDescription = "Pick from gallery")
            }
            FloatingActionButton(onClick = onOpenCamera) {
                Icon(Icons.Default.AddAPhoto, contentDescription = "Take photo")
            }
        }

        state.error?.let { msg ->
            Snackbar(
                modifier = Modifier
                    .align(Alignment.BottomStart)
                    .padding(start = 8.dp, end = 96.dp, bottom = 8.dp),
                action = { TextButton(onClick = onDismissError) { Text("Dismiss") } },
            ) { Text(msg) }
        }
    }
}
