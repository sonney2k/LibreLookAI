package com.librelookai.settings

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.filled.AutoFixHigh
import androidx.compose.material.icons.filled.Compress
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.automirrored.filled.ShowChart
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Tune
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.librelookai.AppScreenHeader
import com.librelookai.R
import com.librelookai.settings.UserPreferences
import com.librelookai.util.LocalIsOffline

/**
 * The "Advanced" screen — every power-user, destructive, or developer-grade option,
 * with friendly explanations. Reached only via the "More ▸ Advanced" row.
 * See README §"The screen: V1 — Advanced".
 */
@Composable
fun SettingsAdvancedScreen(
    preferences: UserPreferences,
    currentApiKey: String,
    onSaveApiKey: (String) -> Unit,
    onDestructive: (DestructiveAction) -> Unit,
    onToggleDedupe: (Boolean) -> Unit,
    onTogglePreferLocalBg: (Boolean) -> Unit,
    onToggleSimilarityPreview: (Boolean) -> Unit,
    onSelectImageQuality: (com.librelookai.util.ImageQuality) -> Unit,
    onOpenUsage: () -> Unit,
    onSendFeedback: () -> Unit,
    onExportDiagnostics: () -> Unit,
    onBack: () -> Unit,
) {
    val isOffline = LocalIsOffline.current
    val accentTile = aiAccent().copy(alpha = 0.18f)
    var showQualityDialog by remember { mutableStateOf(false) }
    if (showQualityDialog) {
        ImageQualityPickerDialog(
            current = preferences.imageQuality,
            onSelect = onSelectImageQuality,
            onDismiss = { showQualityDialog = false },
        )
    }
    Column(modifier = Modifier.fillMaxSize()) {
        AppScreenHeader(
            title = stringResource(R.string.settings_advanced_title),
            trailingContent = {
                IconButton(onClick = onBack) {
                    Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = stringResource(R.string.action_back))
                }
            },
        )
        Column(modifier = Modifier.verticalScroll(rememberScrollState())) {
            // Warning pill + intro
            Column(modifier = Modifier.padding(start = 20.dp, end = 20.dp, top = 4.dp)) {
                Row(
                    modifier = Modifier
                        .clip(RoundedCornerShape(999.dp))
                        .background(aiAccent().copy(alpha = 0.18f))
                        .padding(horizontal = 10.dp, vertical = 5.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    Icon(
                        imageVector = Icons.Filled.Warning,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.size(12.dp),
                    )
                    Text(
                        text = stringResource(R.string.settings_advanced_rarely_needed_pill).uppercase(),
                        fontSize = 10.5.sp,
                        fontWeight = FontWeight.Bold,
                        letterSpacing = 0.6.sp,
                        color = MaterialTheme.colorScheme.primary,
                    )
                }
                Text(
                    text = stringResource(R.string.settings_advanced_intro),
                    fontSize = 13.sp,
                    lineHeight = 20.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(top = 10.dp),
                )
            }

            // FIX AI MISTAKES
            SecLabel(stringResource(R.string.settings_section_advanced_fix))
            SettingsCard {
                SettingsRow(
                    icon = Icons.Filled.Refresh,
                    iconBg = accentTile,
                    label = stringResource(R.string.settings_retag_row),
                    sub = stringResource(R.string.settings_retag_row_sub),
                    onClick = if (isOffline) null else ({ onDestructive(DestructiveAction.RETAG) }),
                )
                SettingsRow(
                    icon = Icons.Filled.AutoFixHigh,
                    iconBg = accentTile,
                    label = stringResource(R.string.settings_rebg_row),
                    sub = stringResource(R.string.settings_rebg_row_sub),
                    onClick = if (isOffline) null else ({ onDestructive(DestructiveAction.REMOVE_BG) }),
                )
                SettingsRow(
                    icon = Icons.Filled.Tune,
                    iconBg = accentTile,
                    label = stringResource(R.string.settings_cutout_row),
                    sub = stringResource(R.string.settings_cutout_row_sub),
                    isLast = true,
                    onClick = if (isOffline) null else ({ onDestructive(DestructiveAction.CUTOUT_FIX) }),
                )
            }

            // SKIP THE CREDITS — BYOK
            SecLabel(stringResource(R.string.settings_section_advanced_byok))
            SettingsCard {
                ByokBlock(currentApiKey = currentApiKey, onSaveApiKey = onSaveApiKey)
            }

            // POWER OPTIONS
            SecLabel(stringResource(R.string.settings_section_advanced_power))
            SettingsCard {
                SettingsRow(
                    icon = Icons.Filled.Compress,
                    iconBg = accentTile,
                    label = stringResource(R.string.settings_convert_webp_row),
                    sub = stringResource(R.string.settings_convert_webp_row_sub),
                    onClick = if (isOffline) null else ({ onDestructive(DestructiveAction.CONVERT_WEBP) }),
                )
                SettingsRow(
                    icon = Icons.AutoMirrored.Filled.ShowChart,
                    label = stringResource(R.string.settings_usage_charts_row),
                    sub = stringResource(R.string.settings_usage_charts_sub),
                    onClick = onOpenUsage,
                )
                SettingsRow(
                    label = stringResource(R.string.settings_dedupe_row),
                    sub = stringResource(R.string.settings_dedupe_sub),
                    accessory = RowAccessory.SwitchToggle(preferences.dedupeOnImport, onToggleDedupe),
                )
                SettingsRow(
                    label = stringResource(R.string.settings_local_bg_row),
                    sub = stringResource(R.string.settings_local_bg_sub),
                    accessory = RowAccessory.SwitchToggle(preferences.preferLocalBgRemoval, onTogglePreferLocalBg),
                )
                SettingsRow(
                    label = stringResource(R.string.settings_similarity_preview_row),
                    sub = stringResource(R.string.settings_similarity_preview_sub),
                    accessory = RowAccessory.SwitchToggle(preferences.debugSimilarityPreview, onToggleSimilarityPreview),
                )
                SettingsRow(
                    label = stringResource(R.string.settings_image_quality_row),
                    sub = stringResource(R.string.settings_image_quality_sub),
                    value = imageQualityLabel(preferences.imageQuality),
                    isLast = true,
                    onClick = { showQualityDialog = true },
                )
            }

            // HELP US IMPROVE
            SecLabel(stringResource(R.string.settings_section_advanced_feedback))
            SettingsCard {
                SettingsRow(
                    icon = Icons.AutoMirrored.Filled.Send,
                    label = stringResource(R.string.settings_send_feedback),
                    onClick = onSendFeedback,
                )
                SettingsRow(
                    icon = Icons.Filled.Download,
                    label = stringResource(R.string.settings_export_diagnostics),
                    isLast = true,
                    onClick = onExportDiagnostics,
                )
            }
            Spacer(Modifier.height(18.dp))
        }
    }
}

@Composable
private fun ByokBlock(
    currentApiKey: String,
    onSaveApiKey: (String) -> Unit,
) {
    var editing by remember(currentApiKey) { mutableStateOf(currentApiKey.isBlank()) }
    var draft by remember(currentApiKey) { mutableStateOf(currentApiKey) }
    Column(modifier = Modifier.padding(bottom = 4.dp)) {
        Column(modifier = Modifier.padding(start = 16.dp, end = 16.dp, top = 14.dp, bottom = 8.dp)) {
            Text(
                text = stringResource(R.string.settings_advanced_byok_title),
                fontSize = 13.5.sp,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.onBackground,
            )
            Text(
                text = stringResource(R.string.settings_advanced_byok_body),
                fontSize = 11.5.sp,
                lineHeight = 16.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(top = 4.dp),
            )
        }
        if (editing) {
            Column(modifier = Modifier.padding(start = 16.dp, end = 16.dp, bottom = 14.dp)) {
                OutlinedTextField(
                    value = draft,
                    onValueChange = { draft = it },
                    singleLine = true,
                    placeholder = { Text("AIza…") },
                    modifier = Modifier.fillMaxWidth(),
                )
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(top = 4.dp),
                    horizontalArrangement = Arrangement.End,
                ) {
                    if (currentApiKey.isNotBlank()) {
                        TextButton(onClick = { draft = currentApiKey; editing = false }) {
                            Text(stringResource(R.string.action_cancel))
                        }
                    }
                    TextButton(onClick = { onSaveApiKey(draft.trim()); editing = false }) {
                        Text(stringResource(R.string.action_save))
                    }
                }
            }
        } else {
            Column(modifier = Modifier.padding(start = 16.dp, end = 16.dp, bottom = 14.dp)) {
                Surface(
                    shape = RoundedCornerShape(10.dp),
                    color = MaterialTheme.colorScheme.surfaceVariant,
                    border = androidx.compose.foundation.BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
                ) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 12.dp, vertical = 10.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        Icon(
                            imageVector = Icons.Filled.Lock,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.size(16.dp),
                        )
                        Text(
                            text = maskKey(currentApiKey),
                            modifier = Modifier.weight(1f),
                            fontSize = 12.sp,
                            fontFamily = FontFamily.Monospace,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 1,
                        )
                        Text(
                            text = stringResource(R.string.settings_advanced_byok_saved_badge),
                            fontSize = 11.sp,
                            fontWeight = FontWeight.Bold,
                            color = MaterialTheme.colorScheme.primary,
                        )
                    }
                }
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(top = 4.dp),
                    horizontalArrangement = Arrangement.End,
                ) {
                    TextButton(onClick = { onSaveApiKey(""); draft = ""; editing = true }) {
                        Text(stringResource(R.string.settings_advanced_byok_clear), color = MaterialTheme.colorScheme.error)
                    }
                    TextButton(onClick = { editing = true }) {
                        Text(stringResource(R.string.settings_advanced_byok_change))
                    }
                }
            }
        }
    }
}

/** "AIza" + bullets — never reveals the saved key. */
private fun maskKey(key: String): String {
    if (key.isBlank()) return ""
    val prefix = key.take(4)
    return prefix + "•".repeat((key.length - prefix.length).coerceIn(8, 22))
}
