package com.librelookai

import android.Manifest
import android.content.pm.PackageManager
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CalendarMonth
import androidx.compose.material.icons.filled.Checkroom
import androidx.compose.material.icons.filled.FlightTakeoff
import androidx.compose.material.icons.filled.Person
import androidx.compose.material.icons.filled.Style
import androidx.compose.material.icons.filled.TipsAndUpdates
import androidx.compose.material3.Icon
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
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
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.lifecycle.viewmodel.compose.viewModel
import com.librelookai.ui.theme.LibreLookAITheme

private data class NavItem(val label: String, val icon: ImageVector)

private val navItems = listOf(
    NavItem("Styles", Icons.Default.Style),
    NavItem("Wardrobe", Icons.Default.Checkroom),
    NavItem("Calendar", Icons.Default.CalendarMonth),
    NavItem("Travel", Icons.Default.FlightTakeoff),
    NavItem("Gaps", Icons.Default.TipsAndUpdates),
    NavItem("Profile", Icons.Default.Person),
)

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
                    val stylesViewModel: StylesViewModel = viewModel()
                    val wardrobeViewModel: WardrobeViewModel = viewModel()
                    val outfitsViewModel: OutfitsViewModel = viewModel()
                    val profileViewModel: ProfileViewModel = viewModel()
                    val weatherViewModel: WeatherViewModel = viewModel()
                    val travelViewModel: TravelViewModel = viewModel()
                    val gapViewModel: WardrobeGapViewModel = viewModel()
                    val weatherState by weatherViewModel.state.collectAsState()

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

                    Scaffold(
                        modifier = Modifier.fillMaxSize(),
                        bottomBar = {
                            NavigationBar {
                                navItems.forEachIndexed { index, item ->
                                    NavigationBarItem(
                                        selected = selectedTab == index,
                                        onClick = { selectedTab = index },
                                        icon = { Icon(item.icon, contentDescription = item.label) },
                                        label = { Text(item.label) },
                                    )
                                }
                            }
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
                                    modifier = Modifier.padding(innerPadding),
                                )
                                2 -> CalendarScreen(
                                    outfitsViewModel = outfitsViewModel,
                                    stylesViewModel = stylesViewModel,
                                    wardrobeViewModel = wardrobeViewModel,
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
                                5 -> ProfileScreen(
                                    viewModel = profileViewModel,
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
