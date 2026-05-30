package com.librelookai.settings

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.HelpOutline
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.StarOutline
import androidx.compose.runtime.Composable
import androidx.compose.ui.res.stringResource
import com.librelookai.BuildConfig
import com.librelookai.LocalStartTour
import com.librelookai.R

/**
 * "More" card — Advanced, Help & FAQ, and About rows. See README §"More" card.
 */
@Composable
fun MoreCard(
    onAdvanced: () -> Unit,
    onHelp: () -> Unit,
    onAbout: () -> Unit,
) {
    SettingsCard {
        SettingsRow(
            icon = Icons.Filled.Settings,
            label = stringResource(R.string.settings_more_advanced),
            sub = stringResource(R.string.settings_more_advanced_subtitle),
            onClick = onAdvanced,
        )
        SettingsRow(
            icon = Icons.AutoMirrored.Filled.HelpOutline,
            label = stringResource(R.string.settings_more_help),
            onClick = onHelp,
        )
        LocalStartTour.current?.let { startTour ->
            SettingsRow(
                icon = Icons.Filled.PlayArrow,
                label = stringResource(R.string.onboarding_tour_replay),
                sub = stringResource(R.string.onboarding_tour_replay_sub),
                onClick = startTour,
            )
        }
        SettingsRow(
            icon = Icons.Filled.StarOutline,
            label = stringResource(R.string.settings_more_about),
            value = "v${BuildConfig.VERSION_NAME}",
            isLast = true,
            onClick = onAbout,
        )
    }
}
