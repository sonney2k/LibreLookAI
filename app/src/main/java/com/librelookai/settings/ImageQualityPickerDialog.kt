package com.librelookai.settings

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import com.librelookai.R
import com.librelookai.util.ImageQuality

/** Human label for an [ImageQuality] tier. */
@Composable
fun imageQualityLabel(q: ImageQuality): String = stringResource(
    when (q) {
        ImageQuality.BALANCED -> R.string.settings_image_quality_balanced
        ImageQuality.HIGH -> R.string.settings_image_quality_high
        ImageQuality.MAXIMUM -> R.string.settings_image_quality_maximum
    }
)

/** Short size/fidelity hint for an [ImageQuality] tier. */
@Composable
fun imageQualityDesc(q: ImageQuality): String = stringResource(
    when (q) {
        ImageQuality.BALANCED -> R.string.settings_image_quality_balanced_desc
        ImageQuality.HIGH -> R.string.settings_image_quality_high_desc
        ImageQuality.MAXIMUM -> R.string.settings_image_quality_maximum_desc
    }
)

/**
 * Single-select picker for the wardrobe image quality / storage trade-off. Re-provides the
 * locale-overridden [LocalContext] / [LocalConfiguration] inside the [Dialog] window per the
 * Dialog locale quirk documented in CLAUDE.md.
 */
@Composable
fun ImageQualityPickerDialog(
    current: ImageQuality,
    onSelect: (ImageQuality) -> Unit,
    onDismiss: () -> Unit,
) {
    val parentContext = LocalContext.current
    val parentConfiguration = LocalConfiguration.current
    Dialog(onDismissRequest = onDismiss) {
        CompositionLocalProvider(
            LocalContext provides parentContext,
            LocalConfiguration provides parentConfiguration,
        ) {
            Surface(
                shape = RoundedCornerShape(22.dp),
                color = MaterialTheme.colorScheme.background,
                modifier = Modifier.fillMaxWidth(),
            ) {
                LazyColumn(modifier = Modifier.heightIn(max = 480.dp)) {
                    item {
                        Text(
                            text = stringResource(R.string.settings_image_quality_dialog_title),
                            fontSize = 18.sp,
                            fontWeight = FontWeight.Bold,
                            color = MaterialTheme.colorScheme.onBackground,
                            modifier = Modifier.padding(start = 20.dp, end = 20.dp, top = 18.dp, bottom = 8.dp),
                        )
                    }
                    items(ImageQuality.entries) { tier ->
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable { onSelect(tier); onDismiss() }
                                .padding(horizontal = 16.dp, vertical = 12.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                        ) {
                            RadioButton(selected = tier == current, onClick = { onSelect(tier); onDismiss() })
                            Column {
                                Text(
                                    text = imageQualityLabel(tier),
                                    fontSize = 15.sp,
                                    fontWeight = FontWeight.SemiBold,
                                    color = MaterialTheme.colorScheme.onBackground,
                                )
                                Text(
                                    text = imageQualityDesc(tier),
                                    fontSize = 12.5.sp,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}
