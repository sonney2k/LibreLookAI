package com.librelookai.onboarding

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.PowerManager
import android.provider.Settings as AndroidSettings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AddAPhoto
import androidx.compose.material.icons.filled.AutoAwesome
import androidx.compose.material.icons.filled.Bolt
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Checkroom
import androidx.compose.material.icons.filled.CloudDone
import androidx.compose.material.icons.filled.Face
import androidx.compose.material.icons.filled.Key
import androidx.compose.material.icons.filled.LocationOn
import androidx.compose.material.icons.filled.Luggage
import androidx.compose.material.icons.automirrored.filled.OpenInNew
import androidx.compose.material.icons.filled.ShoppingBag
import androidx.compose.material.icons.filled.Style
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.core.content.ContextCompat
import com.librelookai.R
import com.librelookai.gemini.ApiKeyStore
import com.librelookai.settings.ProfileViewModel
import com.librelookai.settings.TryOnSlot
import com.librelookai.util.LocalSystemBarsPadding
import kotlinx.coroutines.launch

private data class InfoPage(val icon: ImageVector, val titleRes: Int, val bodyRes: Int)

/**
 * First-run (and re-runnable) walkthrough. A swipeable [HorizontalPager] of value-prop / feature
 * pages followed by the core setup steps: connect Google Drive, allow background processing, add a
 * Gemini API key, style profile, try-on photo, finish.
 *
 * Three steps are required (no "Skip", and Next is soft-blocked until satisfied): connecting Drive
 * ([isSignedIn]) and granting battery-optimization exemption are prerequisites for the app to work
 * at all, and the API-key step soft-blocks until a key is pasted. Profile/photo come after Drive
 * sign-in because they write to Drive. Every other step is skippable.
 *
 * On first run this is the outermost gate (rendered before the sign-in screen) by
 * [com.librelookai.AppContent]; the OAuth authorization machinery (PendingIntent launch + Firebase
 * sign-in) lives there, so this screen only triggers [onStartSignIn] and reacts to [isSignedIn].
 * It does no navigation of its own — [onFinish] reports whether the user asked to jump straight to
 * the wardrobe to add clothes (`goToWardrobe = true`) or just explore.
 */
@Composable
fun OnboardingScreen(
    profileViewModel: ProfileViewModel,
    isSignedIn: Boolean,
    signInErrorCode: Int?,
    onStartSignIn: () -> Unit,
    isOffline: Boolean,
    onFinish: (goToWardrobe: Boolean) -> Unit,
    modifier: Modifier = Modifier,
) {
    val infoPages = listOf(
        InfoPage(Icons.Filled.AutoAwesome, R.string.onboarding_welcome_title, R.string.onboarding_welcome_body),
        InfoPage(Icons.Filled.Checkroom, R.string.onboarding_wardrobe_title, R.string.onboarding_wardrobe_body),
        InfoPage(Icons.Filled.Style, R.string.onboarding_outfits_title, R.string.onboarding_outfits_body),
        InfoPage(Icons.Filled.Face, R.string.onboarding_tryon_title, R.string.onboarding_tryon_body),
        InfoPage(Icons.Filled.Luggage, R.string.onboarding_travel_title, R.string.onboarding_travel_body),
        InfoPage(Icons.Filled.ShoppingBag, R.string.onboarding_shopping_title, R.string.onboarding_shopping_body),
    )
    val drivePage = infoPages.size
    val backgroundPage = infoPages.size + 1
    val locationPage = infoPages.size + 2
    val apiKeyPage = infoPages.size + 3
    val profilePage = infoPages.size + 4
    val photoPage = infoPages.size + 5
    val finishPage = infoPages.size + 6
    val totalPages = infoPages.size + 7

    val context = LocalContext.current
    val state by profileViewModel.state.collectAsState()
    val prefs = state.preferences

    // Background-processing (battery optimization) exemption — required so the foreground sync
    // service isn't killed mid-import. Re-checked when the user returns from system settings.
    val powerManager = remember { context.getSystemService(Context.POWER_SERVICE) as PowerManager }
    var isBatteryExempt by remember {
        mutableStateOf(powerManager.isIgnoringBatteryOptimizations(context.packageName))
    }

    // Location permission (coarse) — optional, powers local weather for outfit suggestions. Like
    // the battery exemption it's re-checked on resume in case the user granted it from system
    // settings. The launcher lets the dedicated page (and the finish summary) request it inline.
    fun hasLocationPermission() = ContextCompat.checkSelfPermission(
        context, Manifest.permission.ACCESS_COARSE_LOCATION,
    ) == PackageManager.PERMISSION_GRANTED
    var locationGranted by remember { mutableStateOf(hasLocationPermission()) }
    val locationPermLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission(),
    ) { granted -> locationGranted = granted }

    val lifecycleOwner = LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) {
                isBatteryExempt = powerManager.isIgnoringBatteryOptimizations(context.packageName)
                locationGranted = hasLocationPermission()
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    // Mandatory steps (Drive sign-in, then background) come first precisely so we can do this:
    // the moment Drive sign-in lands, reload the account's saved preferences. On first run the
    // initial load in ProfileViewModel.init failed (not signed in yet), so an account that already
    // has LibreLookAI data — reinstall, new device — would otherwise show defaults. Reloading here
    // pulls in the existing style profile / theme / language / AI options so the later steps prefill
    // them and finishing the tour merges into them instead of overwriting with onboarding defaults.
    LaunchedEffect(isSignedIn) {
        if (isSignedIn) profileViewModel.loadPreferences()
    }

    // BYOK key — onboarding's only hard requirement (the app does no AI without it). Persisted to
    // device-local storage as the user types; the Next button on [apiKeyPage] soft-blocks until the
    // pasted value looks like an AI Studio key. Top-right "Skip" remains an escape hatch.
    var apiKey by remember { mutableStateOf(ApiKeyStore.get(context)) }
    val apiKeyLooksValid = remember(apiKey) { apiKey.trim().startsWith("AIza") && apiKey.trim().length >= 30 }

    // Seeded from the loaded preferences (re-seeds once when the Drive load lands).
    var gender by remember(prefs) { mutableStateOf(prefs.gender) }
    var yearText by remember(prefs) { mutableStateOf(prefs.yearOfBirth?.toString() ?: "") }
    var style by remember(prefs) { mutableStateOf(prefs.preferences) }

    fun finish(goToWardrobe: Boolean) {
        // Persist the style profile only when the user reaches the end deliberately, we're online,
        // and the existing prefs have finished loading — otherwise the copy below would clobber the
        // account's saved theme / language / AI settings with defaults. We only override the three
        // fields the profile step actually edits and carry everything else (incl. anything loaded
        // after sign-in) through from `prefs`.
        if (!isOffline && !state.isLoading) {
            profileViewModel.savePreferences(
                prefs.copy(
                    gender = gender.trim(),
                    yearOfBirth = yearText.toIntOrNull(),
                    preferences = style.trim(),
                )
            )
        }
        onFinish(goToWardrobe)
    }

    val pagerState = rememberPagerState(pageCount = { totalPages })
    val scope = rememberCoroutineScope()

    Box(
        modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.surface)
            .padding(LocalSystemBarsPadding.current),
    ) {
        Column(Modifier.fillMaxSize()) {
            // Skip — top-right; hidden on the required steps (Drive sign-in, background processing)
            // and on the final page where the CTAs take over.
            Box(
                Modifier.fillMaxWidth().height(48.dp).padding(horizontal = 8.dp),
                contentAlignment = Alignment.CenterEnd,
            ) {
                val page = pagerState.currentPage
                val skippable = page < finishPage && page != drivePage && page != backgroundPage
                if (skippable) {
                    TextButton(onClick = { finish(false) }) {
                        Text(stringResource(R.string.onboarding_skip))
                    }
                }
            }

            HorizontalPager(
                state = pagerState,
                modifier = Modifier.weight(1f).fillMaxWidth(),
            ) { page ->
                when (page) {
                    drivePage -> DriveSignInPage(
                        isSignedIn = isSignedIn,
                        signInErrorCode = signInErrorCode,
                        isOffline = isOffline,
                        onSignIn = onStartSignIn,
                    )
                    backgroundPage -> BackgroundPermissionPage(
                        isExempt = isBatteryExempt,
                    )
                    locationPage -> LocationPermissionPage(
                        granted = locationGranted,
                        onAllow = { locationPermLauncher.launch(Manifest.permission.ACCESS_COARSE_LOCATION) },
                    )
                    apiKeyPage -> ApiKeyPage(
                        apiKey = apiKey,
                        onKey = { apiKey = it; ApiKeyStore.set(context, it) },
                        looksValid = apiKeyLooksValid,
                        isOffline = isOffline,
                    )
                    profilePage -> ProfilePage(
                        gender = gender, onGender = { gender = it },
                        yearText = yearText, onYear = { yearText = it },
                        style = style, onStyle = { style = it },
                    )
                    photoPage -> PhotoPage(
                        photoSet = state.tryOnLocalPaths.containsKey(TryOnSlot.FRONT),
                        uploading = state.tryOnUploading.contains(TryOnSlot.FRONT),
                        isOffline = isOffline,
                        onPick = { uri -> profileViewModel.uploadTryOnPhoto(TryOnSlot.FRONT, uri) },
                    )
                    finishPage -> FinishPage(
                        driveDone = isSignedIn,
                        backgroundDone = isBatteryExempt,
                        apiKeyDone = apiKeyLooksValid,
                        locationDone = locationGranted,
                        onFixDrive = { scope.launch { pagerState.animateScrollToPage(drivePage) } },
                        onFixBackground = { scope.launch { pagerState.animateScrollToPage(backgroundPage) } },
                        onFixApiKey = { scope.launch { pagerState.animateScrollToPage(apiKeyPage) } },
                        onFixLocation = { locationPermLauncher.launch(Manifest.permission.ACCESS_COARSE_LOCATION) },
                        onAddItems = { finish(true) },
                        onExplore = { finish(false) },
                    )
                    else -> InfoPageContent(infoPages[page])
                }
            }

            // Page-indicator dots.
            Row(
                Modifier.fillMaxWidth().padding(vertical = 12.dp),
                horizontalArrangement = Arrangement.Center,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                repeat(totalPages) { i ->
                    val selected = i == pagerState.currentPage
                    Box(
                        Modifier
                            .padding(horizontal = 3.dp)
                            .size(if (selected) 9.dp else 7.dp)
                            .clip(CircleShape)
                            .background(
                                if (selected) MaterialTheme.colorScheme.primary
                                else MaterialTheme.colorScheme.outlineVariant,
                            ),
                    )
                }
            }

            // Back / Next — Next is hidden on the final page (its own CTAs finish the tour).
            Row(
                Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                if (pagerState.currentPage > 0) {
                    TextButton(onClick = {
                        scope.launch { pagerState.animateScrollToPage(pagerState.currentPage - 1) }
                    }) { Text(stringResource(R.string.onboarding_back)) }
                }
                Spacer(Modifier.weight(1f))
                if (pagerState.currentPage < finishPage) {
                    // Soft-block: required steps keep Next disabled until satisfied — Drive sign-in
                    // until connected, background processing until exempt, API key until pasted.
                    val nextEnabled = when (pagerState.currentPage) {
                        drivePage -> isSignedIn
                        backgroundPage -> isBatteryExempt
                        apiKeyPage -> apiKeyLooksValid
                        else -> true
                    }
                    Button(
                        enabled = nextEnabled,
                        onClick = {
                            scope.launch { pagerState.animateScrollToPage(pagerState.currentPage + 1) }
                        },
                    ) { Text(stringResource(R.string.onboarding_next)) }
                }
            }
        }
    }
}

@Composable
private fun InfoPageContent(page: InfoPage) {
    Column(
        Modifier.fillMaxSize().padding(horizontal = 32.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Icon(page.icon, null, Modifier.size(96.dp), tint = MaterialTheme.colorScheme.primary)
        Spacer(Modifier.height(32.dp))
        Text(
            stringResource(page.titleRes),
            style = MaterialTheme.typography.headlineSmall,
            fontWeight = FontWeight.Bold,
            textAlign = TextAlign.Center,
        )
        Spacer(Modifier.height(16.dp))
        Text(
            stringResource(page.bodyRes),
            style = MaterialTheme.typography.bodyLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
        )
    }
}

@Composable
private fun ProfilePage(
    gender: String, onGender: (String) -> Unit,
    yearText: String, onYear: (String) -> Unit,
    style: String, onStyle: (String) -> Unit,
) {
    Column(
        Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .imePadding()
            .padding(horizontal = 24.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        Spacer(Modifier.height(8.dp))
        Text(
            stringResource(R.string.onboarding_profile_title),
            style = MaterialTheme.typography.headlineSmall,
            fontWeight = FontWeight.Bold,
        )
        Text(
            stringResource(R.string.onboarding_profile_body),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        OutlinedTextField(
            value = style, onValueChange = onStyle,
            label = { Text(stringResource(R.string.settings_style_prefs)) },
            minLines = 2, modifier = Modifier.fillMaxWidth(),
        )
        OutlinedTextField(
            value = gender, onValueChange = onGender,
            label = { Text(stringResource(R.string.settings_profile_gender)) },
            singleLine = true, modifier = Modifier.fillMaxWidth(),
        )
        OutlinedTextField(
            value = yearText,
            onValueChange = { v -> onYear(v.filter { it.isDigit() }.take(4)) },
            label = { Text(stringResource(R.string.settings_profile_year)) },
            singleLine = true,
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
            modifier = Modifier.fillMaxWidth(),
        )
    }
}

@Composable
private fun PhotoPage(
    photoSet: Boolean,
    uploading: Boolean,
    isOffline: Boolean,
    onPick: (android.net.Uri) -> Unit,
) {
    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.GetContent()) { uri ->
        uri?.let(onPick)
    }
    Column(
        Modifier.fillMaxSize().padding(horizontal = 32.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Icon(
            if (photoSet) Icons.Filled.CheckCircle else Icons.Filled.AddAPhoto,
            null,
            Modifier.size(96.dp),
            tint = MaterialTheme.colorScheme.primary,
        )
        Spacer(Modifier.height(32.dp))
        Text(
            stringResource(R.string.onboarding_photo_title),
            style = MaterialTheme.typography.headlineSmall,
            fontWeight = FontWeight.Bold,
            textAlign = TextAlign.Center,
        )
        Spacer(Modifier.height(16.dp))
        Text(
            stringResource(R.string.onboarding_photo_body),
            style = MaterialTheme.typography.bodyLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
        )
        Spacer(Modifier.height(24.dp))
        when {
            uploading -> CircularProgressIndicator()
            photoSet -> Text(
                stringResource(R.string.onboarding_photo_added),
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.primary,
            )
            else -> Button(
                onClick = { launcher.launch("image/*") },
                enabled = !isOffline,
            ) { Text(stringResource(R.string.onboarding_photo_add)) }
        }
        if (isOffline && !photoSet) {
            Spacer(Modifier.height(12.dp))
            Text(
                stringResource(R.string.onboarding_photo_offline),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center,
            )
        }
    }
}

@Composable
private fun DriveSignInPage(
    isSignedIn: Boolean,
    signInErrorCode: Int?,
    isOffline: Boolean,
    onSignIn: () -> Unit,
) {
    val errorMessage = when (signInErrorCode) {
        null -> null
        10   -> stringResource(R.string.sign_in_error_not_registered)   // DEVELOPER_ERROR
        7    -> stringResource(R.string.sign_in_error_network)           // NETWORK_ERROR
        else -> stringResource(R.string.sign_in_error_generic, signInErrorCode)
    }
    Column(
        Modifier.fillMaxSize().padding(horizontal = 32.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Icon(
            if (isSignedIn) Icons.Filled.CheckCircle else Icons.Filled.CloudDone,
            null,
            Modifier.size(96.dp),
            tint = MaterialTheme.colorScheme.primary,
        )
        Spacer(Modifier.height(32.dp))
        Text(
            stringResource(R.string.onboarding_drive_title),
            style = MaterialTheme.typography.headlineSmall,
            fontWeight = FontWeight.Bold,
            textAlign = TextAlign.Center,
        )
        Spacer(Modifier.height(16.dp))
        Text(
            stringResource(R.string.onboarding_drive_body),
            style = MaterialTheme.typography.bodyLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
        )
        Spacer(Modifier.height(24.dp))
        if (isSignedIn) {
            Text(
                stringResource(R.string.onboarding_drive_connected),
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.primary,
            )
        } else {
            Button(onClick = onSignIn, enabled = !isOffline) {
                Text(stringResource(R.string.onboarding_drive_connect))
            }
        }
        if (errorMessage != null && !isSignedIn) {
            Spacer(Modifier.height(16.dp))
            Text(
                errorMessage,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.error,
                textAlign = TextAlign.Center,
            )
        }
        if (isOffline && !isSignedIn) {
            Spacer(Modifier.height(12.dp))
            Text(
                stringResource(R.string.onboarding_drive_offline),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center,
            )
        }
    }
}

@Composable
private fun BackgroundPermissionPage(
    isExempt: Boolean,
) {
    val context = LocalContext.current
    val pkgName = context.packageName
    fun requestExemption() {
        fun launchIntent(vararg intents: Intent) {
            for (intent in intents) {
                try {
                    context.startActivity(intent)
                    return
                } catch (_: Exception) { }
            }
        }
        launchIntent(
            Intent(AndroidSettings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS).apply {
                data = Uri.parse("package:$pkgName")
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            },
            Intent(AndroidSettings.ACTION_APPLICATION_DETAILS_SETTINGS).apply {
                data = Uri.parse("package:$pkgName")
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            },
            Intent(AndroidSettings.ACTION_SETTINGS).apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            },
        )
    }
    Column(
        Modifier.fillMaxSize().padding(horizontal = 32.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Icon(
            if (isExempt) Icons.Filled.CheckCircle else Icons.Filled.Bolt,
            null,
            Modifier.size(96.dp),
            tint = MaterialTheme.colorScheme.primary,
        )
        Spacer(Modifier.height(32.dp))
        Text(
            stringResource(R.string.onboarding_background_title),
            style = MaterialTheme.typography.headlineSmall,
            fontWeight = FontWeight.Bold,
            textAlign = TextAlign.Center,
        )
        Spacer(Modifier.height(16.dp))
        Text(
            stringResource(R.string.onboarding_background_body),
            style = MaterialTheme.typography.bodyLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
        )
        Spacer(Modifier.height(24.dp))
        if (isExempt) {
            Text(
                stringResource(R.string.onboarding_background_granted),
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.primary,
            )
        } else {
            Button(onClick = { requestExemption() }) {
                Text(stringResource(R.string.onboarding_background_allow))
            }
        }
    }
}

@Composable
private fun LocationPermissionPage(
    granted: Boolean,
    onAllow: () -> Unit,
) {
    Column(
        Modifier.fillMaxSize().padding(horizontal = 32.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Icon(
            if (granted) Icons.Filled.CheckCircle else Icons.Filled.LocationOn,
            null,
            Modifier.size(96.dp),
            tint = MaterialTheme.colorScheme.primary,
        )
        Spacer(Modifier.height(32.dp))
        Text(
            stringResource(R.string.onboarding_location_title),
            style = MaterialTheme.typography.headlineSmall,
            fontWeight = FontWeight.Bold,
            textAlign = TextAlign.Center,
        )
        Spacer(Modifier.height(16.dp))
        Text(
            stringResource(R.string.onboarding_location_body),
            style = MaterialTheme.typography.bodyLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
        )
        Spacer(Modifier.height(24.dp))
        if (granted) {
            Text(
                stringResource(R.string.onboarding_location_granted),
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.primary,
            )
        } else {
            Button(onClick = onAllow) {
                Text(stringResource(R.string.onboarding_location_allow))
            }
        }
    }
}

@Composable
private fun ApiKeyPage(
    apiKey: String,
    onKey: (String) -> Unit,
    looksValid: Boolean,
    isOffline: Boolean,
) {
    val context = LocalContext.current
    fun open(url: String) {
        context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)))
    }
    Column(
        Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .imePadding()
            .padding(horizontal = 24.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        Spacer(Modifier.height(8.dp))
        Icon(
            if (looksValid) Icons.Filled.CheckCircle else Icons.Filled.Key,
            null,
            Modifier.size(72.dp),
            tint = MaterialTheme.colorScheme.primary,
        )
        Text(
            stringResource(R.string.onboarding_apikey_title),
            style = MaterialTheme.typography.headlineSmall,
            fontWeight = FontWeight.Bold,
        )
        Text(
            stringResource(R.string.onboarding_apikey_body),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Text(
            stringResource(R.string.onboarding_apikey_tiers),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        // Step 1 — open AI Studio to create a key.
        Button(
            onClick = { open("https://aistudio.google.com/app/apikey") },
            enabled = !isOffline,
            modifier = Modifier.fillMaxWidth(),
        ) {
            Icon(Icons.AutoMirrored.Filled.OpenInNew, null, Modifier.size(18.dp))
            Text("  " + stringResource(R.string.onboarding_apikey_get))
        }
        // Step 2 — enable billing so try-on & background removal work.
        OutlinedButton(
            onClick = { open("https://aistudio.google.com/app/billing") },
            enabled = !isOffline,
            modifier = Modifier.fillMaxWidth(),
        ) {
            Icon(Icons.AutoMirrored.Filled.OpenInNew, null, Modifier.size(18.dp))
            Text("  " + stringResource(R.string.onboarding_apikey_billing))
        }
        // Step 3 — paste the key back here.
        OutlinedTextField(
            value = apiKey,
            onValueChange = onKey,
            label = { Text(stringResource(R.string.settings_api_key_label)) },
            placeholder = { Text(stringResource(R.string.settings_api_key_placeholder)) },
            singleLine = true,
            isError = apiKey.isNotEmpty() && !looksValid,
            modifier = Modifier.fillMaxWidth(),
        )
        if (looksValid) {
            Text(
                stringResource(R.string.onboarding_apikey_saved),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.primary,
            )
        }
        if (isOffline) {
            Text(
                stringResource(R.string.onboarding_apikey_offline),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        Spacer(Modifier.height(8.dp))
    }
}

@Composable
private fun FinishPage(
    driveDone: Boolean,
    backgroundDone: Boolean,
    apiKeyDone: Boolean,
    locationDone: Boolean,
    onFixDrive: () -> Unit,
    onFixBackground: () -> Unit,
    onFixApiKey: () -> Unit,
    onFixLocation: () -> Unit,
    onAddItems: () -> Unit,
    onExplore: () -> Unit,
) {
    val anyMissing = !(driveDone && backgroundDone && apiKeyDone && locationDone)
    Column(
        Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Spacer(Modifier.height(16.dp))
        Icon(Icons.Filled.Checkroom, null, Modifier.size(72.dp), tint = MaterialTheme.colorScheme.primary)
        Spacer(Modifier.height(20.dp))
        Text(
            stringResource(R.string.onboarding_closet_title),
            style = MaterialTheme.typography.headlineSmall,
            fontWeight = FontWeight.Bold,
            textAlign = TextAlign.Center,
        )
        Spacer(Modifier.height(12.dp))
        Text(
            stringResource(R.string.onboarding_closet_body),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
        )

        // Setup summary — every required step plus optional location, each with a status icon and
        // an inline fix for anything still outstanding.
        Spacer(Modifier.height(24.dp))
        Text(
            stringResource(R.string.onboarding_finish_setup_title),
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.Bold,
            modifier = Modifier.fillMaxWidth(),
        )
        Spacer(Modifier.height(8.dp))
        SetupChecklistRow(stringResource(R.string.onboarding_finish_drive), driveDone, onFixDrive)
        SetupChecklistRow(stringResource(R.string.onboarding_finish_background), backgroundDone, onFixBackground)
        SetupChecklistRow(stringResource(R.string.onboarding_finish_apikey), apiKeyDone, onFixApiKey)
        SetupChecklistRow(stringResource(R.string.onboarding_finish_location), locationDone, onFixLocation)

        if (anyMissing) {
            Spacer(Modifier.height(16.dp))
            Row(
                Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(12.dp))
                    .background(MaterialTheme.colorScheme.errorContainer)
                    .padding(12.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Icon(
                    Icons.Filled.Warning, null,
                    Modifier.size(20.dp),
                    tint = MaterialTheme.colorScheme.onErrorContainer,
                )
                Spacer(Modifier.size(10.dp))
                Text(
                    stringResource(R.string.onboarding_finish_warning),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onErrorContainer,
                )
            }
        }

        Spacer(Modifier.height(24.dp))
        Button(onClick = onAddItems, modifier = Modifier.fillMaxWidth()) {
            Text(stringResource(R.string.onboarding_closet_add_items))
        }
        Spacer(Modifier.height(8.dp))
        TextButton(onClick = onExplore, modifier = Modifier.fillMaxWidth()) {
            Text(stringResource(R.string.onboarding_closet_explore))
        }
        Spacer(Modifier.height(16.dp))
    }
}

@Composable
private fun SetupChecklistRow(
    label: String,
    done: Boolean,
    onFix: () -> Unit,
) {
    Row(
        Modifier.fillMaxWidth().padding(vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(
            if (done) Icons.Filled.CheckCircle else Icons.Filled.Warning,
            null,
            Modifier.size(22.dp),
            tint = if (done) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.error,
        )
        Spacer(Modifier.size(12.dp))
        Text(
            label,
            style = MaterialTheme.typography.bodyLarge,
            modifier = Modifier.weight(1f),
        )
        if (!done) {
            TextButton(onClick = onFix) {
                Text(stringResource(R.string.onboarding_finish_grant))
            }
        }
    }
}
