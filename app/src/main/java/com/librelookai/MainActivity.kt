package com.librelookai

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.content.res.Configuration
import android.net.Uri
import android.os.Bundle
import android.os.PowerManager
import android.provider.Settings as AndroidSettings
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.IntentSenderRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Checkroom
import androidx.compose.material.icons.filled.FlightTakeoff
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.DoorSliding
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.ShoppingBag
import androidx.compose.material.icons.filled.Style
import androidx.compose.material.icons.filled.Insights
import androidx.compose.material.icons.filled.CloudOff
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.activity.compose.LocalActivityResultRegistryOwner
import androidx.activity.compose.LocalOnBackPressedDispatcherOwner
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.viewmodel.compose.viewModel
import com.librelookai.ui.theme.LibreLookAITheme

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        EmbeddingService.init(this)
        setContent {
            LibreLookAITheme {
                val authViewModel: AuthViewModel = viewModel()
                val isSignedIn by authViewModel.isSignedIn.collectAsState()
                val authError by authViewModel.signInErrorCode.collectAsState()
                val pendingAuthIntent by authViewModel.pendingAuthIntent.collectAsState()

                val authorizationLauncher = rememberLauncherForActivityResult(
                    ActivityResultContracts.StartIntentSenderForResult(),
                ) { result -> authViewModel.onAuthorizationResult(result.data) }

                LaunchedEffect(pendingAuthIntent) {
                    pendingAuthIntent?.let { intent ->
                        authorizationLauncher.launch(
                            IntentSenderRequest.Builder(intent.intentSender).build()
                        )
                    }
                }

                LaunchedEffect(isSignedIn) {
                    if (isSignedIn) authViewModel.attemptFirebaseSignIn(this@MainActivity)
                }

                if (!isSignedIn) {
                    Scaffold(modifier = Modifier.fillMaxSize()) { innerPadding ->
                        SignInScreen(
                            onSignIn = { authViewModel.startSignIn() },
                            signInErrorCode = authError,
                            modifier = Modifier.padding(innerPadding),
                        )
                    }
                } else {
                    val networkMonitor = remember { NetworkMonitor(this@MainActivity) }
                    DisposableEffect(networkMonitor) {
                        onDispose { networkMonitor.unregister() }
                    }
                    val isOnline by networkMonitor.isOnline.collectAsState()
                    val isOffline = !isOnline

                    var selectedTab by rememberSaveable { mutableIntStateOf(1) }
                    // Increments on every nav button tap (incl. re-tap and gear icon)
                    // so screens with sub-tabs can reset to their default tab.
                    var navResetTick by remember { mutableIntStateOf(0) }
                    val locationViewModel: LocationViewModel = viewModel()
                    val stylesViewModel: OutfitsViewModel = viewModel()
                    val wardrobeViewModel: WardrobeViewModel = viewModel()
                    val outfitEventsViewModel: OutfitEventsViewModel = viewModel()
                    val profileViewModel: ProfileViewModel = viewModel()
                    val weatherViewModel: WeatherViewModel = viewModel()
                    val travelViewModel: TravelViewModel = viewModel()
                    val gapViewModel: WardrobeGapViewModel = viewModel()
                    val creditsViewModel: CreditsViewModel = viewModel()
                    val tryOnViewModel: TryOnViewModel = viewModel()
                    val shoppingViewModel: ShoppingHelperViewModel = viewModel()
                    val shoppingClosetViewModel: ShoppingClosetViewModel = viewModel()
                    val locationState by locationViewModel.state.collectAsState()
                    val weatherState by weatherViewModel.state.collectAsState()
                    val profileState by profileViewModel.state.collectAsState()
                    val canTryOn = profileState.tryOnLocalPaths.isNotEmpty()

                    // Reload wardrobe/styles/outfits whenever the active location changes
                    val activeLocationId = locationState.activeLocationId
                    val locationList = locationState.locations
                    val activeFolderId = if (activeLocationId != LocationViewModel.ALL_LOCATIONS_ID && activeLocationId.isNotEmpty())
                        locationList.find { it.folderId == activeLocationId }?.folderId else null
                    val shoppingClosetState by shoppingClosetViewModel.state.collectAsState()
                    LaunchedEffect(activeLocationId, locationList, shoppingClosetState.folderId) {
                        val closetFolderIds = locationList.map { it.folderId }
                        // Always tell the wardrobe VM about every configured closet — and the
                        // shopping closet — so the cross-closet snapshot (used by all
                        // similarity-search call sites) covers wishlist items too. The shopping
                        // folder is never a Location and never appears in `closetFolderIds`.
                        val folderIds = closetFolderIds
                        val snapshotFolderIds = shoppingClosetState.folderId
                            ?.let { closetFolderIds + it } ?: closetFolderIds
                        wardrobeViewModel.setAllConfiguredLocations(snapshotFolderIds)
                        // Styles always loads from ALL locations — never filtered by the settings default.
                        stylesViewModel.setAllLocations(folderIds)
                        // Track which folder new styles should be saved to.
                        val saveTarget = activeFolderId ?: folderIds.firstOrNull()
                        if (saveTarget != null) stylesViewModel.updateSaveFolder(saveTarget)
                        if (activeLocationId == LocationViewModel.ALL_LOCATIONS_ID) {
                            wardrobeViewModel.setAllLocations(folderIds)
                            outfitEventsViewModel.setAllLocations(folderIds)
                        } else {
                            activeFolderId?.let { folderId ->
                                wardrobeViewModel.setLocation(folderId)
                                outfitEventsViewModel.setLocation(folderId)
                            }
                        }
                        // Imports always go to the active location (first location when all are shown).
                        wardrobeViewModel.setDefaultImportFolderId(activeFolderId ?: folderIds.firstOrNull())
                    }

                    // Keep wardrobe tagging language in sync with the profile language
                    val geminiLanguage = AppLanguage.toGeminiName(profileState.preferences.language)
                    LaunchedEffect(geminiLanguage) {
                        wardrobeViewModel.setLanguage(geminiLanguage)
                        shoppingClosetViewModel.setLanguage(geminiLanguage)
                    }

                    // Mirror similarity-check preferences into the wardrobe VM
                    val dedupeOnImport = profileState.preferences.dedupeOnImport
                    val dedupeThreshold = profileState.preferences.dedupeThreshold
                    LaunchedEffect(dedupeOnImport, dedupeThreshold) {
                        wardrobeViewModel.setDedupeSettings(dedupeOnImport, dedupeThreshold)
                    }

                    // Mirror the bg-removal threshold into the live segmenter so changes from the
                    // AI tab take effect immediately on the next capture/import.
                    val bgRemovalThreshold = profileState.preferences.bgRemovalThreshold
                    LaunchedEffect(bgRemovalThreshold) {
                        runCatching { EmbeddingService.segmenter.foregroundThreshold = bgRemovalThreshold }
                    }

                    // Mirror the local-bg-removal pref into the wardrobe VM so camera/gallery
                    // imports route through the on-device segmenter review when enabled.
                    val preferLocalBg = profileState.preferences.preferLocalBgRemoval
                    LaunchedEffect(preferLocalBg) {
                        wardrobeViewModel.setPreferLocalBgRemoval(preferLocalBg)
                    }

                    // Apply selected language as the Compose context locale
                    val language = profileState.preferences.language
                    val baseContext = LocalContext.current
                    val localizedContext = remember(language) {
                        val locale = AppLanguage.toLocale(language)
                        val config = Configuration(baseContext.resources.configuration)
                        config.setLocale(locale)
                        baseContext.createConfigurationContext(config)
                    }

                    CompositionLocalProvider(
                        LocalContext provides localizedContext,
                        LocalActivityResultRegistryOwner provides this@MainActivity,
                        LocalOnBackPressedDispatcherOwner provides this@MainActivity,
                        LocalIsOffline provides isOffline,
                    ) {

                        // Battery optimization exemption — block all interactions until granted
                        val pm = remember {
                            this@MainActivity.getSystemService(POWER_SERVICE) as PowerManager
                        }
                        val pkgName = this@MainActivity.packageName
                        var isBatteryExempt by remember {
                            mutableStateOf(pm.isIgnoringBatteryOptimizations(pkgName))
                        }
                        // Re-check when the user comes back from system settings
                        DisposableEffect(Unit) {
                            val observer = LifecycleEventObserver { _, event ->
                                if (event == Lifecycle.Event.ON_RESUME) {
                                    isBatteryExempt = pm.isIgnoringBatteryOptimizations(pkgName)
                                }
                            }
                            this@MainActivity.lifecycle.addObserver(observer)
                            onDispose { this@MainActivity.lifecycle.removeObserver(observer) }
                        }

                        if (!isBatteryExempt) {
                            val batteryContext = LocalContext.current
                            AlertDialog(
                                onDismissRequest = { /* non-dismissable */ },
                                title = { Text(stringResource(R.string.battery_exempt_title)) },
                                text = { Text(stringResource(R.string.battery_exempt_text)) },
                                confirmButton = {
                                    TextButton(
                                        onClick = {
                                            fun launchIntent(vararg intents: Intent) {
                                                for (intent in intents) {
                                                    try {
                                                        batteryContext.startActivity(intent)
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
                                    ) { Text(stringResource(R.string.battery_exempt_action)) }
                                },
                            )
                            return@CompositionLocalProvider
                        }

                        // Location permission — request once; refresh weather when granted
                        var hasLocationPermission by remember {
                            mutableStateOf(
                                ContextCompat.checkSelfPermission(
                                    this, Manifest.permission.ACCESS_COARSE_LOCATION,
                                ) == PackageManager.PERMISSION_GRANTED,
                            )
                        }
                        val locationPermLauncher = rememberLauncherForActivityResult(
                            ActivityResultContracts.RequestPermission(),
                        ) { granted ->
                            hasLocationPermission = granted
                            if (granted) weatherViewModel.refresh()
                        }
                        LaunchedEffect(Unit) {
                            if (!hasLocationPermission) {
                                locationPermLauncher.launch(Manifest.permission.ACCESS_COARSE_LOCATION)
                            }
                        }

                        var dismissWardrobeViewerTrigger by remember { mutableIntStateOf(0) }

                        Scaffold(
                            modifier = Modifier.fillMaxSize(),
                            bottomBar = {
                                AppNavBar(
                                    selectedTab = selectedTab,
                                    onTabSelected = {
                                        selectedTab = it
                                        navResetTick++
                                    },
                                    onTabReselected = { tab ->
                                        if (tab == 1) dismissWardrobeViewerTrigger++
                                        navResetTick++
                                    },
                                )
                            },
                        ) { innerPadding ->
                            Column(Modifier.fillMaxSize().padding(innerPadding)) {
                                // Offline banner
                                androidx.compose.animation.AnimatedVisibility(visible = isOffline) {
                                    Row(
                                        modifier = Modifier
                                            .fillMaxWidth()
                                            .background(MaterialTheme.colorScheme.errorContainer)
                                            .padding(horizontal = 16.dp, vertical = 6.dp),
                                        verticalAlignment = Alignment.CenterVertically,
                                        horizontalArrangement = Arrangement.Center,
                                    ) {
                                        Icon(
                                            Icons.Default.CloudOff,
                                            contentDescription = null,
                                            modifier = Modifier.size(16.dp),
                                            tint = MaterialTheme.colorScheme.onErrorContainer,
                                        )
                                        Spacer(Modifier.width(8.dp))
                                        Text(
                                            stringResource(R.string.offline_banner),
                                            style = MaterialTheme.typography.labelMedium,
                                            color = MaterialTheme.colorScheme.onErrorContainer,
                                        )
                                    }
                                }

                                Box(Modifier.fillMaxSize()) {
                                    val onSettingsClick: () -> Unit = {
                                        selectedTab = 5
                                        navResetTick++
                                    }
                                    val runTryOn: (Set<String>) -> Unit = { itemIds ->
                                        tryOnViewModel.openComposer(itemIds)
                                    }
                                    when (selectedTab) {
                                        0 -> OutfitsScreen(
                                            outfitsViewModel = stylesViewModel,
                                            wardrobeViewModel = wardrobeViewModel,
                                            outfitEventsViewModel = outfitEventsViewModel,
                                            profileViewModel = profileViewModel,
                                            weatherViewModel = weatherViewModel,
                                            locationViewModel = locationViewModel,
                                            tryOnViewModel = tryOnViewModel,
                                            onTryOnStyle = { style ->
                                                stylesViewModel.clearOutfitSelection()
                                                runTryOn(style.itemIds.toSet())
                                            },
                                            canTryOn = canTryOn,
                                            onSettingsClick = onSettingsClick,
                                            navResetTick = navResetTick,
                                        )
                                        1 -> WardrobeScreen(
                                            viewModel = wardrobeViewModel,
                                            outfitEventsViewModel = outfitEventsViewModel,
                                            stylesViewModel = stylesViewModel,
                                            locationViewModel = locationViewModel,
                                            shoppingClosetViewModel = shoppingClosetViewModel,
                                            profileViewModel = profileViewModel,
                                            onCreateOutfitFromSelection = { itemIds ->
                                                stylesViewModel.openComposer(
                                                    seedItemIds = itemIds,
                                                    images      = wardrobeViewModel.state.value.images,
                                                    prefs       = profileViewModel.state.value.preferences,
                                                )
                                                wardrobeViewModel.clearSelection()
                                            },
                                            onTryOnSelection = { itemIds ->
                                                runTryOn(itemIds)
                                                wardrobeViewModel.clearSelection()
                                            },
                                            onSuggestReplacements = { itemIds ->
                                                val all = wardrobeViewModel.state.value.images
                                                val selected = all.filter { it.driveId in itemIds }
                                                gapViewModel.suggestReplacements(
                                                    selected  = selected,
                                                    allImages = all,
                                                    prefs     = profileViewModel.state.value.preferences,
                                                )
                                                wardrobeViewModel.clearSelection()
                                            },
                                            canTryOn = canTryOn,
                                            dismissViewerTrigger = dismissWardrobeViewerTrigger,
                                            onSettingsClick = onSettingsClick,
                                        )
                                        2 -> ShoppingHelperScreen(
                                            shoppingViewModel = shoppingViewModel,
                                            shoppingClosetViewModel = shoppingClosetViewModel,
                                            wardrobeViewModel = wardrobeViewModel,
                                            gapViewModel = gapViewModel,
                                            profileViewModel = profileViewModel,
                                            locationViewModel = locationViewModel,
                                            onSettingsClick = onSettingsClick,
                                            onShowInWardrobe = { image ->
                                                val matchFolder = image.folderId
                                                val viewingAll = locationState.activeLocationId == LocationViewModel.ALL_LOCATIONS_ID
                                                if (!viewingAll && matchFolder.isNotEmpty()
                                                    && matchFolder != locationState.activeLocationId
                                                ) {
                                                    locationViewModel.setActiveLocation(matchFolder)
                                                }
                                                wardrobeViewModel.requestScrollToImage(image.driveId)
                                                selectedTab = 1
                                                navResetTick++
                                            },
                                            navResetTick = navResetTick,
                                        )
                                        3 -> TravelScreen(
                                            travelViewModel = travelViewModel,
                                            wardrobeViewModel = wardrobeViewModel,
                                            profileViewModel = profileViewModel,
                                            stylesViewModel = stylesViewModel,
                                            locationViewModel = locationViewModel,
                                            onSettingsClick = onSettingsClick,
                                        )
                                        4 -> InsightsScreen(
                                            wardrobeViewModel = wardrobeViewModel,
                                            outfitEventsViewModel = outfitEventsViewModel,
                                            stylesViewModel = stylesViewModel,
                                            locationViewModel = locationViewModel,
                                            onEditOutfit = { style ->
                                                stylesViewModel.startEditing(
                                                    style  = style,
                                                    images = wardrobeViewModel.state.value.images,
                                                    prefs  = profileViewModel.state.value.preferences,
                                                )
                                                selectedTab = 0
                                            },
                                            onSettingsClick = onSettingsClick,
                                            navResetTick = navResetTick,
                                        )
                                        5 -> SettingsScreen(
                                            profileViewModel = profileViewModel,
                                            wardrobeViewModel = wardrobeViewModel,
                                            locationViewModel = locationViewModel,
                                            creditsViewModel = creditsViewModel,
                                            onBack = { selectedTab = 1 },
                                            navResetTick = navResetTick,
                                        )
                                    }

                                    // Weather badge — bottom-left, floating above the nav bar
                                    weatherState.data?.let { weather ->
                                        WeatherBadge(
                                            data = weather,
                                            modifier = Modifier
                                                .align(Alignment.BottomStart)
                                                .padding(start = 12.dp, bottom = 8.dp),
                                        )
                                    }
                                }

                                TryOnComposerScreen(
                                    tryOnViewModel   = tryOnViewModel,
                                    wardrobeViewModel = wardrobeViewModel,
                                    profileViewModel  = profileViewModel,
                                )

                                // Unified style composer — opened from any screen that seeds items.
                                OutfitComposerScreen(
                                    stylesViewModel   = stylesViewModel,
                                    wardrobeViewModel = wardrobeViewModel,
                                    profileViewModel  = profileViewModel,
                                    weatherViewModel  = weatherViewModel,
                                )

                                // Replacements result dialog — opened from Wardrobe selection FAB.
                                ReplacementsResultDialog(gapViewModel = gapViewModel)

                                // Local background-removal review — full-screen dialog shown when
                                // an import is queued for on-device cutout refinement.
                                LocalBgRemovalScreen(viewModel = wardrobeViewModel)
                            }
                        }
                    }
                }
            }
        }
    }
}

/**
 * Consistent top header bar used across all main screens.
 *
 * Renders a [title] row with an optional [leadingIcon] and an optional
 * [trailingContent] slot (e.g. a sort/filter button), followed by a divider.
 * Typography and colors are always sourced from [MaterialTheme] so the bar
 * automatically adapts to light/dark mode and dynamic colour.
 */
@Composable
fun AppScreenHeader(
    title: String,
    modifier: Modifier = Modifier,
    leadingIcon: ImageVector? = null,
    trailingContent: @Composable (() -> Unit)? = null,
    onSettingsClick: (() -> Unit)? = null,
) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .padding(
                start = if (leadingIcon != null) 12.dp else 16.dp,
                end = if (trailingContent != null || onSettingsClick != null) 4.dp else 16.dp,
                top = 8.dp,
                bottom = 8.dp,
            ),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (leadingIcon != null) {
            Icon(
                imageVector = leadingIcon,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.primary,
                modifier = Modifier.size(22.dp),
            )
            Spacer(Modifier.width(8.dp))
        }
        Text(
            text = title,
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.SemiBold,
            color = MaterialTheme.colorScheme.onSurface,
            modifier = Modifier.weight(1f),
        )
        trailingContent?.invoke()
        if (onSettingsClick != null) {
            androidx.compose.material3.IconButton(onClick = onSettingsClick) {
                Icon(
                    Icons.Default.Settings,
                    contentDescription = "Settings",
                    modifier = Modifier.size(22.dp),
                )
            }
        }
    }
    HorizontalDivider()
}

/**
 * Location dropdown button for the top bar — mirrors [SortButton] style.
 * Only renders content when there are 2+ locations.
 */
@Composable
fun LocationButton(
    locations: List<Location>,
    activeLocationId: String,
    onSetActiveLocation: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    if (locations.size < 2) return
    var expanded by remember { mutableStateOf(false) }
    val allLocationsLabel = stringResource(R.string.filter_all_locations)
    val activeName = when (activeLocationId) {
        LocationViewModel.ALL_LOCATIONS_ID -> allLocationsLabel
        else -> locations.find { it.folderId == activeLocationId }?.name ?: allLocationsLabel
    }
    Box(modifier = modifier) {
        IconButton(onClick = { expanded = true }) {
            Icon(Icons.Default.DoorSliding, contentDescription = activeName, modifier = Modifier.size(22.dp))
        }
        DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
            // "All" option first
            val allChecked = activeLocationId == LocationViewModel.ALL_LOCATIONS_ID
            DropdownMenuItem(
                text = {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        if (allChecked) Icon(Icons.Default.Check, null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(18.dp))
                        else Spacer(Modifier.size(18.dp))
                        Text(allLocationsLabel)
                    }
                },
                onClick = {
                    onSetActiveLocation(LocationViewModel.ALL_LOCATIONS_ID)
                    expanded = false
                },
            )
            locations.sortedBy { it.name }.forEach { loc ->
                val checked = loc.folderId == activeLocationId
                DropdownMenuItem(
                    text = {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                        ) {
                            if (checked) Icon(Icons.Default.Check, null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(18.dp))
                            else Spacer(Modifier.size(18.dp))
                            Text(loc.name)
                        }
                    },
                    onClick = {
                        onSetActiveLocation(loc.folderId)
                        expanded = false
                    },
                )
            }
        }
    }
}

@Composable
private fun AppNavBar(
    selectedTab: Int,
    onTabSelected: (Int) -> Unit,
    onTabReselected: (Int) -> Unit = {},
) {
    data class NavItem(val labelRes: Int, val icon: androidx.compose.ui.graphics.vector.ImageVector)
    val items = listOf(
        NavItem(R.string.nav_styles,   Icons.Default.Style),
        NavItem(R.string.nav_wardrobe, Icons.Default.Checkroom),
        NavItem(R.string.nav_shopping, Icons.Default.ShoppingBag),
        NavItem(R.string.nav_travel,   Icons.Default.FlightTakeoff),
        NavItem(R.string.nav_insights, Icons.Default.Insights),
    )
    NavigationBar {
        items.forEachIndexed { index, item ->
            val label = stringResource(item.labelRes)
            NavigationBarItem(
                selected = selectedTab == index,
                onClick = {
                    if (index == selectedTab) onTabReselected(index) else onTabSelected(index)
                },
                icon = { Icon(item.icon, contentDescription = label) },
                label = { Text(label) },
            )
        }
    }
}
