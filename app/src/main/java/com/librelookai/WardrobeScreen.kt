package com.librelookai

import android.Manifest
import android.content.pm.PackageManager
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AddAPhoto
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Snackbar
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

    val signInLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.StartActivityForResult(),
    ) { result -> viewModel.onSignInResult(result) }

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

    when (state.view) {
        WardrobeView.SIGN_IN -> SignInContent(
            onSignIn = { signInLauncher.launch(viewModel.getSignInIntent()) },
            modifier = modifier,
        )

        WardrobeView.GRID -> GridContent(
            state = state,
            onOpenCamera = {
                if (hasCameraPermission) viewModel.openCapture()
                else permissionLauncher.launch(Manifest.permission.CAMERA)
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

// ---------- Sign-in ----------

@Composable
private fun SignInContent(onSignIn: () -> Unit, modifier: Modifier = Modifier) {
    Column(
        modifier = modifier.fillMaxSize(),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Text("Your Wardrobe", style = MaterialTheme.typography.headlineMedium)
        Spacer(Modifier.height(8.dp))
        Text(
            text = "Sign in to sync your outfits with Google Drive",
            style = MaterialTheme.typography.bodyMedium,
        )
        Spacer(Modifier.height(24.dp))
        Button(onClick = onSignIn) {
            Text("Sign in with Google")
        }
    }
}

// ---------- Wardrobe grid ----------

@Composable
private fun GridContent(
    state: WardrobeUiState,
    onOpenCamera: () -> Unit,
    onDismissError: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Box(modifier = modifier.fillMaxSize()) {

        when {
            state.isLoading -> {
                CircularProgressIndicator(modifier = Modifier.align(Alignment.Center))
            }

            state.images.isEmpty() -> {
                Column(
                    modifier = Modifier.align(Alignment.Center),
                    horizontalAlignment = Alignment.CenterHorizontally,
                ) {
                    Text("No outfits yet", style = MaterialTheme.typography.bodyLarge)
                    Spacer(Modifier.height(4.dp))
                    Text(
                        "Tap the camera button to add your first outfit",
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

        // Upload spinner overlay
        if (state.isUploading) {
            CircularProgressIndicator(modifier = Modifier.align(Alignment.Center))
        }

        // Camera FAB
        FloatingActionButton(
            onClick = onOpenCamera,
            modifier = Modifier
                .align(Alignment.BottomEnd)
                .padding(16.dp),
        ) {
            Icon(Icons.Default.AddAPhoto, contentDescription = "Add outfit")
        }

        // Error snackbar
        state.error?.let { msg ->
            Snackbar(
                modifier = Modifier
                    .align(Alignment.BottomStart)
                    .padding(start = 8.dp, end = 80.dp, bottom = 8.dp),
                action = { TextButton(onClick = onDismissError) { Text("Dismiss") } },
            ) { Text(msg) }
        }
    }
}
