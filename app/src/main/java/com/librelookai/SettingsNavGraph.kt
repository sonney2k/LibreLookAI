package com.librelookai

import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.lifecycle.viewmodel.compose.LocalViewModelStoreOwner
import androidx.navigation.NavGraphBuilder
import androidx.navigation.compose.composable
import com.librelookai.billing.BuyCreditsScreen
import com.librelookai.settings.AboutScreen
import com.librelookai.settings.DestructiveAction
import com.librelookai.settings.ProfileEditScreen
import com.librelookai.settings.ProfileViewModel
import com.librelookai.settings.SettingsAdvancedScreen
import com.librelookai.settings.SettingsDestructiveConfirmHost
import com.librelookai.settings.rememberDisplayName
import com.librelookai.billing.CreditsViewModel
import com.librelookai.gemini.ApiKeyStore
import com.librelookai.util.Analytics
import com.librelookai.util.ImageEncoding
import com.librelookai.wardrobe.LocationViewModel
import com.librelookai.wardrobe.WardrobeViewModel

/**
 * Settings sub-screens as destinations of the nested Home tab NavHost — pushed over
 * `SettingsTabRoute`, replacing the old local `SettingsRoute` enum back-stack inside
 * `SettingsScreen` (system back now pops them; the bar keeps Settings highlighted via
 * `homeTabIndex`). Each destination pins VM resolution to the activity, same rule as
 * every other destination.
 */
internal fun NavGraphBuilder.settingsDestinations(
    activity: ComponentActivity,
    profileViewModel: ProfileViewModel,
    wardrobeViewModel: WardrobeViewModel,
    locationViewModel: LocationViewModel,
    creditsViewModel: CreditsViewModel,
    navigate: (Any) -> Unit,
    onBack: () -> Unit,
) {
    composable<SettingsProfileEditRoute> {
        CompositionLocalProvider(LocalViewModelStoreOwner provides activity) {
            LaunchedEffect(Unit) { Analytics.screen("Settings/PROFILE_EDIT") }
            val profileState by profileViewModel.state.collectAsState()
            ProfileEditScreen(
                displayName = rememberDisplayName(),
                preferences = profileState.preferences,
                onSave = profileViewModel::savePreferences,
                onBack = onBack,
            )
        }
    }

    composable<SettingsAdvancedRoute> {
        CompositionLocalProvider(LocalViewModelStoreOwner provides activity) {
            LaunchedEffect(Unit) { Analytics.screen("Settings/ADVANCED") }
            val profileState by profileViewModel.state.collectAsState()
            val context = LocalContext.current
            var currentApiKey by remember { mutableStateOf(ApiKeyStore.get(context)) }
            var pendingAction by remember { mutableStateOf<DestructiveAction?>(null) }
            SettingsAdvancedScreen(
                preferences = profileState.preferences,
                currentApiKey = currentApiKey,
                onSaveApiKey = { key -> ApiKeyStore.set(context, key); currentApiKey = key },
                onDestructive = { pendingAction = it },
                onToggleDedupe = {
                    profileViewModel.savePreferences(profileState.preferences.copy(dedupeOnImport = it))
                },
                onTogglePreferLocalBg = {
                    profileViewModel.savePreferences(profileState.preferences.copy(preferLocalBgRemoval = it))
                },
                onToggleSimilarityPreview = {
                    profileViewModel.savePreferences(profileState.preferences.copy(debugSimilarityPreview = it))
                },
                onSelectImageQuality = {
                    profileViewModel.savePreferences(profileState.preferences.copy(imageQuality = it))
                },
                onOpenUsage = { navigate(SettingsUsageRoute) },
                onBack = onBack,
            )
            pendingAction?.let { action ->
                SettingsDestructiveConfirmHost(
                    action = action,
                    wardrobeViewModel = wardrobeViewModel,
                    locationViewModel = locationViewModel,
                    creditsViewModel = creditsViewModel,
                    onBuyCredits = { pendingAction = null; navigate(SettingsBuyCreditsRoute) },
                    onDismiss = { pendingAction = null },
                )
            }
        }
    }

    composable<SettingsAboutRoute> {
        CompositionLocalProvider(LocalViewModelStoreOwner provides activity) {
            LaunchedEffect(Unit) { Analytics.screen("Settings/ABOUT") }
            AboutScreen(
                onOpenUsage = { navigate(SettingsUsageRoute) },
                onBack = onBack,
            )
        }
    }

    composable<SettingsUsageRoute> {
        CompositionLocalProvider(LocalViewModelStoreOwner provides activity) {
            LaunchedEffect(Unit) { Analytics.screen("Settings/USAGE") }
            SettingsSubScreen(
                title = stringResource(R.string.settings_usage_charts_row),
                onBack = onBack,
            ) {
                UsageCostsTab()
            }
        }
    }

    composable<SettingsBuyCreditsRoute> {
        CompositionLocalProvider(LocalViewModelStoreOwner provides activity) {
            LaunchedEffect(Unit) { Analytics.screen("Settings/BUY_CREDITS") }
            val context = LocalContext.current
            var currentApiKey by remember { mutableStateOf(ApiKeyStore.get(context)) }
            SettingsSubScreen(
                title = stringResource(R.string.settings_section_ai_credits),
                onBack = onBack,
            ) {
                BuyCreditsScreen(
                    creditsViewModel = creditsViewModel,
                    currentApiKey = currentApiKey,
                    onSaveApiKey = { key -> ApiKeyStore.set(context, key); currentApiKey = key },
                    modifier = Modifier.fillMaxSize(),
                )
            }
        }
    }
}

/** Wraps a parameter-free child screen (Usage / Buy credits) with a back header. */
@Composable
internal fun SettingsSubScreen(title: String, onBack: () -> Unit, content: @Composable () -> Unit) {
    Column(modifier = Modifier.fillMaxSize()) {
        AppScreenHeader(
            title = title,
            trailingContent = {
                IconButton(onClick = onBack) {
                    Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = stringResource(R.string.action_back))
                }
            },
        )
        content()
    }
}
