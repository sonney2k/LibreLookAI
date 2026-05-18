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
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.systemBars
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Checkroom
import androidx.compose.material.icons.filled.FlightTakeoff
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.DoorSliding
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.Place
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
import androidx.compose.runtime.rememberCoroutineScope
import kotlinx.coroutines.launch
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.viewmodel.compose.viewModel
import com.librelookai.ui.theme.LibreLookAITheme
import com.librelookai.auth.AuthViewModel
import com.librelookai.auth.GoogleAuthManager
import com.librelookai.auth.SignInScreen
import com.librelookai.billing.CreditsViewModel
import com.librelookai.data.drive.DriveRepository
import com.librelookai.data.model.Location
import com.librelookai.gemini.TokenUsageRepository
import com.librelookai.insights.InsightsScreen
import com.librelookai.ml.EmbeddingService
import com.librelookai.outfit.OutfitComposerScreen
import com.librelookai.outfit.OutfitEventsViewModel
import com.librelookai.outfit.OutfitsScreen
import com.librelookai.outfit.OutfitsViewModel
import com.librelookai.settings.AppFont
import com.librelookai.settings.AppLanguage
import com.librelookai.settings.FixCutoutBgDialog
import com.librelookai.settings.ProfileViewModel
import com.librelookai.settings.SettingsScreen
import com.librelookai.shopping.ShoppingClosetViewModel
import com.librelookai.shopping.ShoppingHelperScreen
import com.librelookai.shopping.ShoppingHelperViewModel
import com.librelookai.travel.TravelScreen
import com.librelookai.travel.TravelViewModel
import com.librelookai.tryon.TryOnComposerScreen
import com.librelookai.tryon.TryOnViewModel
import com.librelookai.util.Analytics
import com.librelookai.util.LocalIsOffline
import com.librelookai.util.LocalSystemBarsPadding
import com.librelookai.util.NetworkMonitor
import com.librelookai.wardrobe.LocalBgRemovalScreen
import com.librelookai.wardrobe.LocationViewModel
import com.librelookai.wardrobe.ReplacementsResultDialog
import com.librelookai.wardrobe.WardrobeGapViewModel
import com.librelookai.wardrobe.WardrobeScreen
import com.librelookai.wardrobe.WardrobeViewModel
import com.librelookai.weather.WeatherBadge
import com.librelookai.weather.WeatherViewModel
import com.librelookai.data.model.TryOn
import com.librelookai.wardrobe.SortButton

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        Analytics.init(applicationContext)
        EmbeddingService.init(this)
        com.librelookai.gemini.PricingClient.start(applicationContext)
        com.librelookai.gemini.ModelPricingClient.start(applicationContext)
        setContent {
            LibreLookAITheme(
                paletteId = ProfileViewModel.cachedTheme(this),
                fontId = ProfileViewModel.cachedFont(this),
            ) {
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
                    if (isSignedIn) {
                        authViewModel.attemptFirebaseSignIn(this@MainActivity)
                        Analytics.event("sign_in_success")
                    }
                }

                if (!isSignedIn) {
                    Scaffold(modifier = Modifier.fillMaxSize()) { innerPadding ->
                        SignInScreen(
                            onSignIn = {
                                Analytics.action("SignIn", "start_sign_in")
                                authViewModel.startSignIn()
                            },
                            signInErrorCode = authError,
                            modifier = Modifier.padding(innerPadding),
                        )
                    }
                } else {
                    val networkMonitor = remember { NetworkMonitor(this@MainActivity) }
                    val usageRepo = remember { TokenUsageRepository.get(application) }
                    val driveRepo = remember { DriveRepository(this@MainActivity, GoogleAuthManager(this@MainActivity)) }
                    val usageScope = rememberCoroutineScope()
                    DisposableEffect(networkMonitor) {
                        val observer = LifecycleEventObserver { _, event ->
                            if (event == Lifecycle.Event.ON_RESUME) {
                                networkMonitor.recheck()
                                usageScope.launch { usageRepo.syncWithDrive(driveRepo) }
                            } else if (event == Lifecycle.Event.ON_PAUSE) {
                                usageScope.launch { usageRepo.flushToDrive(driveRepo) }
                            }
                        }
                        this@MainActivity.lifecycle.addObserver(observer)
                        onDispose {
                            this@MainActivity.lifecycle.removeObserver(observer)
                            networkMonitor.unregister()
                        }
                    }
                    val isOnline by networkMonitor.isOnline.collectAsState()
                    val isOffline = !isOnline

                    var selectedTab by rememberSaveable { mutableIntStateOf(0) }
                    LaunchedEffect(selectedTab) {
                        val name = when (selectedTab) {
                            0 -> "Outfits"; 1 -> "Wardrobe"; 2 -> "Shopping"
                            3 -> "Travel"; 4 -> "Insights"; 5 -> "Settings"
                            else -> "Tab$selectedTab"
                        }
                        Analytics.screen(name)
                    }
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
                    LaunchedEffect(activeLocationId, locationList, locationState.defaultClosetFolderId, shoppingClosetState.folderId) {
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
                        // Imports go to the persisted default closet (Settings → Data/Closets),
                        // independent of the closet filter. Falls back to the first location.
                        val defaultClosetId = locationState.defaultClosetFolderId
                            ?.takeIf { id -> folderIds.contains(id) }
                            ?: folderIds.firstOrNull()
                        wardrobeViewModel.setDefaultImportFolderId(defaultClosetId)
                    }

                    // Keep wardrobe tagging language in sync with the profile language
                    val geminiLanguage = AppLanguage.toGeminiName(profileState.preferences.language)
                    LaunchedEffect(geminiLanguage) {
                        wardrobeViewModel.setLanguage(geminiLanguage)
                        shoppingClosetViewModel.setLanguage(geminiLanguage)
                    }

                    // Pre-warm the Shopping wishlist and Try-On history at app start so that
                    // tapping those tabs paints from the local cache immediately instead of
                    // kicking off the load on first composition. Both view models are
                    // two-phase (local cache → Drive refresh), so this is cheap.
                    LaunchedEffect(Unit) {
                        shoppingClosetViewModel.loadItems()
                        tryOnViewModel.loadHistory()
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

                    val debugSimilarityPreview = profileState.preferences.debugSimilarityPreview
                    LaunchedEffect(debugSimilarityPreview) {
                        wardrobeViewModel.setDebugSimilarityPreview(debugSimilarityPreview)
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

                    val systemBarsPadding = WindowInsets.systemBars.asPaddingValues()

                    CompositionLocalProvider(
                        LocalContext provides localizedContext,
                        LocalConfiguration provides localizedContext.resources.configuration,
                        LocalActivityResultRegistryOwner provides this@MainActivity,
                        LocalOnBackPressedDispatcherOwner provides this@MainActivity,
                        LocalIsOffline provides isOffline,
                        LocalSystemBarsPadding provides systemBarsPadding,
                    ) { LibreLookAITheme(
                        paletteId = profileState.preferences.wardrobeTheme,
                        fontId = profileState.preferences.appFont,
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
                        } else {

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
                                        Analytics.action("NavBar", "tab_select", mapOf("index" to it.toString()))
                                        selectedTab = it
                                        navResetTick++
                                    },
                                    onTabReselected = { tab ->
                                        Analytics.action("NavBar", "tab_reselect", mapOf("index" to tab.toString()))
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
                                        Analytics.action("Toolbar", "open_settings")
                                        selectedTab = 5
                                        navResetTick++
                                    }
                                    val runTryOn: (Set<String>, String?) -> Unit = { itemIds, sourceOutfitId ->
                                        Analytics.action("TryOn", "open_composer", mapOf("count" to itemIds.size.toString()))
                                        tryOnViewModel.openComposer(itemIds, sourceOutfitId)
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
                                                // Preserve the outfit link so the saved try-on
                                                // can jump back here from the detail view.
                                                runTryOn(style.itemIds.toSet(), style.id)
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
                                                Analytics.action("Wardrobe", "create_outfit_from_selection", mapOf("count" to itemIds.size.toString()))
                                                stylesViewModel.openComposer(
                                                    seedItemIds = itemIds,
                                                    images      = wardrobeViewModel.state.value.images,
                                                    prefs       = profileViewModel.state.value.preferences,
                                                    defaultSourceFolderId = locationViewModel.effectiveDefaultClosetFolderId,
                                                )
                                                wardrobeViewModel.clearSelection()
                                            },
                                            onTryOnSelection = { itemIds ->
                                                runTryOn(itemIds, null)
                                                wardrobeViewModel.clearSelection()
                                            },
                                            onSuggestReplacements = { itemIds ->
                                                Analytics.action("Wardrobe", "suggest_replacements", mapOf("count" to itemIds.size.toString()))
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
                                                Analytics.action("Shopping", "show_in_wardrobe")
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
                                            onCreateOutfitFromSelection = { itemIds ->
                                                Analytics.action("Shopping", "create_outfit_from_selection", mapOf("count" to itemIds.size.toString()))
                                                stylesViewModel.openComposer(
                                                    seedItemIds = itemIds,
                                                    images      = wardrobeViewModel.state.value.images +
                                                        shoppingClosetState.items,
                                                    prefs       = profileViewModel.state.value.preferences,
                                                    defaultSourceFolderId = locationViewModel.effectiveDefaultClosetFolderId,
                                                )
                                                shoppingClosetViewModel.clearSelection()
                                            },
                                            onTryOnSelection = { itemIds ->
                                                runTryOn(itemIds, null)
                                                shoppingClosetViewModel.clearSelection()
                                            },
                                            canTryOn = canTryOn,
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
                                            tryOnViewModel = tryOnViewModel,
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

                                    // Local background-removal review — fullscreen overlay shown
                                    // when an import is queued for on-device cutout refinement.
                                    // Rendered inside this Box so it stacks on top of content.
                                    LocalBgRemovalScreen(viewModel = wardrobeViewModel)
                                }

                                val stylesState by stylesViewModel.state.collectAsState()
                                TryOnComposerScreen(
                                    tryOnViewModel   = tryOnViewModel,
                                    wardrobeViewModel = wardrobeViewModel,
                                    profileViewModel  = profileViewModel,
                                    shoppingClosetViewModel = shoppingClosetViewModel,
                                    onShowItemInWardrobe = { image ->
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
                                    outfits = stylesState.outfits,
                                    locations = locationState.locations,
                                    onOpenSourceOutfit = { outfit ->
                                        // Close the Try-On dialog, jump to Outfits, and ask the
                                        // list to scroll the picked outfit into view + highlight.
                                        tryOnViewModel.close()
                                        stylesViewModel.requestScrollToOutfit(outfit.id)
                                        selectedTab = 0
                                        navResetTick++
                                    },
                                )

                                // Unified style composer — opened from any screen that seeds items.
                                OutfitComposerScreen(
                                    stylesViewModel   = stylesViewModel,
                                    wardrobeViewModel = wardrobeViewModel,
                                    profileViewModel  = profileViewModel,
                                    weatherViewModel  = weatherViewModel,
                                    shoppingClosetViewModel = shoppingClosetViewModel,
                                    locationViewModel = locationViewModel,
                                )

                                // Prediction-setup dialog — globally hosted so it appears
                                // regardless of which tab is on top (the composer can be opened
                                // from Wardrobe and ask for AI suggestions before the user has
                                // ever visited the Outfits tab).
                                com.librelookai.outfit.PredictionSetupDialog(
                                    outfitsViewModel  = stylesViewModel,
                                    profileViewModel  = profileViewModel,
                                    weatherViewModel  = weatherViewModel,
                                    locationViewModel = locationViewModel,
                                    wardrobeViewModel = wardrobeViewModel,
                                )

                                // Replacements result dialog — opened from Wardrobe selection FAB.
                                ReplacementsResultDialog(gapViewModel = gapViewModel)

                                // Global insufficient-credits handler — listens for 402s emitted
                                // by GeminiRepository.throwIf402 and shows the dialog regardless
                                // of which ViewModel triggered the call. "Buy" jumps to Settings.
                                var topUpEvent by remember {
                                    mutableStateOf<com.librelookai.billing.InsufficientCreditsException?>(null)
                                }
                                LaunchedEffect(Unit) {
                                    com.librelookai.billing.CreditsEvents.topUp.collect { topUpEvent = it }
                                }
                                topUpEvent?.let { ex ->
                                    com.librelookai.billing.InsufficientCreditsDialog(
                                        needed = ex.needed,
                                        have = ex.have,
                                        onBuy = {
                                            topUpEvent = null
                                            selectedTab = 5 // Settings → Credits tab
                                        },
                                        onDismiss = { topUpEvent = null },
                                    )
                                }

                                // Cutout-background fix confirmation — globally hosted so it
                                // appears whether the scan was started from Wardrobe header or
                                // Settings → Data.
                                val cutoutBgFix = wardrobeViewModel.state.collectAsState().value.cutoutBgFix
                                if (cutoutBgFix?.awaitingConfirmation == true) {
                                    FixCutoutBgDialog(
                                        state = cutoutBgFix,
                                        onToggleSelection = wardrobeViewModel::toggleCutoutFixSelection,
                                        onSetSelection = wardrobeViewModel::setCutoutFixSelection,
                                        onSetShowAll = wardrobeViewModel::setCutoutFixShowAll,
                                        onSetAction = wardrobeViewModel::setCutoutFixAction,
                                        fetchThumbnail = wardrobeViewModel::fetchCutoutFixThumbnail,
                                        onFix = { wardrobeViewModel.continueCutoutBgFix(true) },
                                        onCancel = { wardrobeViewModel.continueCutoutBgFix(false) },
                                    )
                                }
                            }
                        }
                        }
                    } }
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
        val isCaveat = com.librelookai.ui.theme.LocalAppFont.current == AppFont.CAVEAT
        Text(
            text = title,
            style = if (isCaveat) MaterialTheme.typography.headlineLarge else MaterialTheme.typography.titleMedium,
            fontWeight = if (isCaveat) FontWeight.Bold else FontWeight.SemiBold,
            color = MaterialTheme.colorScheme.onSurface,
            // weight(1f) lets the title take remaining space; maxLines=1 + Ellipsis prevents
            // wrapping when trailing content (e.g. a long closet name) gets squeezed in.
            maxLines = 1,
            overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis,
            softWrap = false,
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
    val palette = com.librelookai.ui.theme.LocalWardrobePalette.current
    val shape = RoundedCornerShape(20.dp)
    Box(modifier = modifier) {
        Row(
            modifier = Modifier
                .clip(shape)
                .background(palette.chipBg)
                .border(BorderStroke(1.dp, palette.border), shape)
                .clickable { expanded = true }
                .padding(horizontal = 10.dp, vertical = 6.dp),
            horizontalArrangement = Arrangement.spacedBy(4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(
                Icons.Default.Place,
                contentDescription = null,
                tint = palette.chipFg,
                modifier = Modifier.size(14.dp),
            )
            val isCaveat = com.librelookai.ui.theme.LocalAppFont.current == AppFont.CAVEAT
            Text(
                activeName,
                color = palette.chipFg,
                fontSize = if (isCaveat) 16.sp else 12.sp,
                fontWeight = if (isCaveat) FontWeight.SemiBold else FontWeight.Medium,
                // Cap chip width so a long closet name can't push the screen title to
                // wrap or ellipsize away. The title still owns the remaining space.
                maxLines = 1,
                overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis,
                modifier = Modifier.widthIn(max = 120.dp),
            )
            Icon(
                Icons.Default.KeyboardArrowDown,
                contentDescription = null,
                tint = palette.chipFg,
                modifier = Modifier.size(14.dp),
            )
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
                label = {
                    val isCaveat = com.librelookai.ui.theme.LocalAppFont.current == AppFont.CAVEAT
                    if (isCaveat) {
                        Text(
                            label,
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.SemiBold,
                        )
                    } else {
                        Text(label)
                    }
                },
            )
        }
    }
}
