package com.librelookai

import android.Manifest
import android.content.pm.PackageManager
import android.content.res.Configuration
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CalendarMonth
import androidx.compose.material.icons.filled.Checkroom
import androidx.compose.material.icons.filled.FlightTakeoff
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Style
import androidx.compose.material.icons.filled.TipsAndUpdates
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.activity.compose.LocalActivityResultRegistryOwner
import androidx.activity.compose.LocalOnBackPressedDispatcherOwner
import androidx.compose.runtime.CompositionLocalProvider
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
import androidx.lifecycle.viewmodel.compose.viewModel
import com.librelookai.ui.theme.LibreLookAITheme

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            LibreLookAITheme {
                val authViewModel: AuthViewModel = viewModel()
                val isSignedIn by authViewModel.isSignedIn.collectAsState()
                val authError by authViewModel.error.collectAsState()

                val signInLauncher = rememberLauncherForActivityResult(
                    ActivityResultContracts.StartActivityForResult(),
                ) { result -> authViewModel.onSignInResult(result) }

                if (!isSignedIn) {
                    Scaffold(modifier = Modifier.fillMaxSize()) { innerPadding ->
                        SignInScreen(
                            onSignIn = { signInLauncher.launch(authViewModel.getSignInIntent()) },
                            error = authError,
                            modifier = Modifier.padding(innerPadding),
                        )
                    }
                } else {
                    var selectedTab by rememberSaveable { mutableIntStateOf(1) }
                    val locationViewModel: LocationViewModel = viewModel()
                    val stylesViewModel: StylesViewModel = viewModel()
                    val wardrobeViewModel: WardrobeViewModel = viewModel()
                    val outfitsViewModel: OutfitsViewModel = viewModel()
                    val profileViewModel: ProfileViewModel = viewModel()
                    val weatherViewModel: WeatherViewModel = viewModel()
                    val travelViewModel: TravelViewModel = viewModel()
                    val gapViewModel: WardrobeGapViewModel = viewModel()
                    val creditsViewModel: CreditsViewModel = viewModel()
                    val locationState by locationViewModel.state.collectAsState()
                    val weatherState by weatherViewModel.state.collectAsState()
                    val profileState by profileViewModel.state.collectAsState()

                    // Reload wardrobe/styles/outfits whenever the active location changes
                    val activeFolderId = locationState.locations
                        .find { it.id == locationState.activeLocationId }?.folderId
                    LaunchedEffect(activeFolderId) {
                        activeFolderId?.let { folderId ->
                            wardrobeViewModel.setLocation(folderId)
                            stylesViewModel.setLocation(folderId)
                            outfitsViewModel.setLocation(folderId)
                        }
                    }

                    // Keep wardrobe tagging language in sync with the profile language
                    val geminiLanguage = AppLanguage.toGeminiName(profileState.preferences.language)
                    LaunchedEffect(geminiLanguage) {
                        wardrobeViewModel.setLanguage(geminiLanguage)
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
                    ) {

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
                                    onTabSelected = { selectedTab = it },
                                    onTabReselected = { tab ->
                                        if (tab == 1) dismissWardrobeViewerTrigger++
                                    },
                                )
                            },
                        ) { innerPadding ->
                            Box(Modifier.fillMaxSize()) {
                                when (selectedTab) {
                                    0 -> StylesScreen(
                                        stylesViewModel = stylesViewModel,
                                        wardrobeViewModel = wardrobeViewModel,
                                        outfitsViewModel = outfitsViewModel,
                                        profileViewModel = profileViewModel,
                                        weatherViewModel = weatherViewModel,
                                        modifier = Modifier.padding(innerPadding),
                                    )
                                    1 -> WardrobeScreen(
                                        viewModel = wardrobeViewModel,
                                        outfitsViewModel = outfitsViewModel,
                                        stylesViewModel = stylesViewModel,
                                        locationViewModel = locationViewModel,
                                        onCreateStyleFromSelection = { itemIds ->
                                            stylesViewModel.startCreatingFromItems(itemIds)
                                            wardrobeViewModel.clearSelection()
                                            selectedTab = 0
                                        },
                                        onComposeStyleFromSelection = { itemIds ->
                                            stylesViewModel.triggerCompositionFromItems(
                                                requiredItemIds = itemIds,
                                                prefs           = profileViewModel.state.value.preferences,
                                                weather         = weatherViewModel.state.value.data,
                                                images          = wardrobeViewModel.state.value.images,
                                            )
                                            wardrobeViewModel.clearSelection()
                                            selectedTab = 0
                                        },
                                        dismissViewerTrigger = dismissWardrobeViewerTrigger,
                                        modifier = Modifier.padding(innerPadding),
                                    )
                                    2 -> CalendarScreen(
                                        outfitsViewModel = outfitsViewModel,
                                        stylesViewModel = stylesViewModel,
                                        wardrobeViewModel = wardrobeViewModel,
                                        locationViewModel = locationViewModel,
                                        modifier = Modifier.padding(innerPadding),
                                    )
                                    3 -> TravelScreen(
                                        travelViewModel = travelViewModel,
                                        wardrobeViewModel = wardrobeViewModel,
                                        profileViewModel = profileViewModel,
                                        stylesViewModel = stylesViewModel,
                                        modifier = Modifier.padding(innerPadding),
                                    )
                                    4 -> WardrobeGapScreen(
                                        gapViewModel = gapViewModel,
                                        wardrobeViewModel = wardrobeViewModel,
                                        profileViewModel = profileViewModel,
                                        modifier = Modifier.padding(innerPadding),
                                    )
                                    5 -> SettingsScreen(
                                        profileViewModel = profileViewModel,
                                        wardrobeViewModel = wardrobeViewModel,
                                        locationViewModel = locationViewModel,
                                        creditsViewModel = creditsViewModel,
                                        modifier = Modifier.padding(innerPadding),
                                    )
                                }

                                // Weather badge — bottom-left, floating above the nav bar
                                weatherState.data?.let { weather ->
                                    WeatherBadge(
                                        data = weather,
                                        modifier = Modifier
                                            .align(Alignment.BottomStart)
                                            .padding(
                                                start = 12.dp,
                                                bottom = innerPadding.calculateBottomPadding() + 8.dp,
                                            ),
                                    )
                                }
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
) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .padding(
                start = if (leadingIcon != null) 12.dp else 16.dp,
                end = if (trailingContent != null) 4.dp else 16.dp,
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
    }
    HorizontalDivider()
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
        NavItem(R.string.nav_calendar, Icons.Default.CalendarMonth),
        NavItem(R.string.nav_travel,   Icons.Default.FlightTakeoff),
        NavItem(R.string.nav_gaps,     Icons.Default.TipsAndUpdates),
        NavItem(R.string.nav_settings, Icons.Default.Settings),
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
