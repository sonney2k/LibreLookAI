package com.librelookai

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AutoAwesome
import androidx.compose.material.icons.filled.CalendarMonth
import androidx.compose.material.icons.filled.Checkroom
import androidx.compose.material.icons.filled.Person
import androidx.compose.material3.Icon
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.lifecycle.viewmodel.compose.viewModel
import com.librelookai.ui.theme.LibreLookAITheme

private data class NavItem(val label: String, val icon: ImageVector)

private val navItems = listOf(
    NavItem("Styles", Icons.Default.AutoAwesome),
    NavItem("Wardrobe", Icons.Default.Checkroom),
    NavItem("Calendar", Icons.Default.CalendarMonth),
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

                val signInLauncher = rememberLauncherForActivityResult(
                    ActivityResultContracts.StartActivityForResult(),
                ) { result -> authViewModel.onSignInResult(result) }

                if (!isSignedIn) {
                    // Full-screen sign-in gate — shown once, never again after first login
                    Scaffold(modifier = Modifier.fillMaxSize()) { innerPadding ->
                        SignInScreen(
                            onSignIn = { signInLauncher.launch(authViewModel.getSignInIntent()) },
                            modifier = Modifier.padding(innerPadding),
                        )
                    }
                } else {
                    var selectedTab by rememberSaveable { mutableIntStateOf(1) }
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
                        when (selectedTab) {
                            0 -> StylesScreen(modifier = Modifier.padding(innerPadding))
                            1 -> WardrobeScreen(modifier = Modifier.padding(innerPadding))
                            2 -> CalendarScreen(modifier = Modifier.padding(innerPadding))
                            3 -> ProfileScreen(modifier = Modifier.padding(innerPadding))
                        }
                    }
                }
            }
        }
    }
}
