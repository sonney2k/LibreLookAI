package com.librelookai.settings

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.filled.Compress
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.StarOutline
import androidx.compose.runtime.Composable
import androidx.compose.ui.res.stringResource
import com.librelookai.BuildConfig
import com.librelookai.LocalStartTour
import com.librelookai.R
import com.librelookai.util.FeatureFlags
import com.librelookai.util.LocalIsOffline

/**
 * "Help" card — Convert-to-WebP maintenance (power-only), Take the tour, Send feedback,
 * and About. The "Advanced" door lives separately at the very bottom ([AdvancedCard]).
 */
@Composable
fun HelpCard(
    onConvertWebp: () -> Unit,
    onSendFeedback: () -> Unit,
    onAbout: () -> Unit,
) {
    val isOffline = LocalIsOffline.current
    SettingsCard {
        if (FeatureFlags.powerFeatures) {
            SettingsRow(
                icon = Icons.Filled.Compress,
                label = stringResource(R.string.settings_convert_webp_row),
                sub = stringResource(R.string.settings_convert_webp_row_sub),
                onClick = if (isOffline) null else onConvertWebp,
            )
        }
        LocalStartTour.current?.let { startTour ->
            SettingsRow(
                icon = Icons.Filled.PlayArrow,
                label = stringResource(R.string.onboarding_tour_replay),
                sub = stringResource(R.string.onboarding_tour_replay_sub),
                onClick = startTour,
            )
        }
        SettingsRow(
            icon = Icons.AutoMirrored.Filled.Send,
            label = stringResource(R.string.settings_send_feedback),
            onClick = onSendFeedback,
        )
        SettingsRow(
            icon = Icons.Filled.StarOutline,
            label = stringResource(R.string.settings_more_about),
            value = "v${BuildConfig.VERSION_NAME}",
            isLast = true,
            onClick = onAbout,
        )
    }
}

/**
 * Standalone "Advanced" entry pinned to the very bottom of the Settings page, under its own
 * "Advanced" section header. The header carries the title, so the row shows the descriptive
 * subtitle of what's inside rather than repeating the word.
 */
@Composable
fun AdvancedCard(onAdvanced: () -> Unit) {
    SettingsCard {
        SettingsRow(
            icon = Icons.Filled.Settings,
            label = stringResource(R.string.settings_more_advanced_subtitle),
            isLast = true,
            onClick = onAdvanced,
        )
    }
}
