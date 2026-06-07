package com.librelookai.settings

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.HelpOutline
import androidx.compose.material.icons.filled.Compress
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.StarOutline
import androidx.compose.runtime.Composable
import androidx.compose.ui.res.stringResource
import com.librelookai.BuildConfig
import com.librelookai.LocalStartTour
import com.librelookai.R
import com.librelookai.util.LocalIsOffline

/**
 * "More" card — Convert-to-WebP maintenance, Advanced, Help & FAQ, and About rows.
 * See README §"More" card.
 */
@Composable
fun MoreCard(
    onConvertWebp: () -> Unit,
    onAdvanced: () -> Unit,
    onHelp: () -> Unit,
    onAbout: () -> Unit,
) {
    val isOffline = LocalIsOffline.current
    SettingsCard {
        SettingsRow(
            icon = Icons.Filled.Compress,
            label = stringResource(R.string.settings_convert_webp_row),
            sub = stringResource(R.string.settings_convert_webp_row_sub),
            onClick = if (isOffline) null else onConvertWebp,
        )
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
