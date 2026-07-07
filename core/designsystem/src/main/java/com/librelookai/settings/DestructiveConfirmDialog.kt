package com.librelookai.settings

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AutoFixHigh
import androidx.compose.material.icons.filled.Compress
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Tune
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.librelookai.core.designsystem.R
import com.librelookai.billing.CreditPacks
import kotlin.math.ceil

/** Which destructive "Fix AI mistakes" action a [DestructiveConfirmDialog] confirms. */
enum class DestructiveAction(
    val heroIcon: ImageVector,
    val titleRes: Int,
    val bodyRes: Int,
    val confirmLabelRes: Int,
    val perItemCost: Int,
    val secondsPerItem: Int,
) {
    RETAG(
        heroIcon = Icons.Filled.Refresh,
        titleRes = R.string.settings_retag_title,
        bodyRes = R.string.settings_retag_body,
        confirmLabelRes = R.string.settings_retag_confirm,
        perItemCost = CreditPacks.COST_CLASSIFY,
        secondsPerItem = 2,
    ),
    REMOVE_BG(
        heroIcon = Icons.Filled.AutoFixHigh,
        titleRes = R.string.settings_rebg_confirm_title,
        bodyRes = R.string.settings_rebg_body,
        confirmLabelRes = R.string.settings_rebg_dialog_confirm,
        perItemCost = CreditPacks.COST_BG_REMOVAL,
        secondsPerItem = 3,
    ),
    CUTOUT_FIX(
        heroIcon = Icons.Filled.Tune,
        titleRes = R.string.settings_cutout_title,
        bodyRes = R.string.settings_cutout_body,
        confirmLabelRes = R.string.settings_cutout_confirm,
        perItemCost = CreditPacks.COST_BG_REMOVAL,
        secondsPerItem = 3,
    ),
    CONVERT_WEBP(
        heroIcon = Icons.Filled.Compress,
        titleRes = R.string.settings_convert_title,
        bodyRes = R.string.settings_convert_body,
        confirmLabelRes = R.string.settings_convert_confirm,
        // Pure local re-encode + Drive PATCHes — no Gemini, no credits.
        perItemCost = 0,
        secondsPerItem = 3,
    ),
}

/**
 * Friendly destructive-action confirm dialog (replaces the terse AlertDialogs).
 * Shows computed item count, estimated time, and credit cost; the primary action
 * flips to "Buy credits & continue" when the balance is short. See README §confirm.
 */
@Composable
fun DestructiveConfirmDialog(
    action: DestructiveAction,
    itemCount: Int,
    balance: Int,
    onConfirm: () -> Unit,
    onBuyCredits: () -> Unit,
    onDismiss: () -> Unit,
) {
    val parentContext = LocalContext.current
    val parentConfiguration = LocalConfiguration.current
    // BYOK-only builds have no coin economy: show time/count, but no coin cost or buy CTA.
    val managed = com.librelookai.billing.ManagedBilling.enabled
    val creditsNeeded = itemCount * action.perItemCost
    val shortBy = (creditsNeeded - balance).coerceAtLeast(0)
    val canAfford = !managed || shortBy == 0
    val minutes = ceil(itemCount * action.secondsPerItem / 60.0).toInt().coerceAtLeast(1)

    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false),
    ) {
        CompositionLocalProvider(
            LocalContext provides parentContext,
            LocalConfiguration provides parentConfiguration,
        ) {
            Surface(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 18.dp),
                shape = RoundedCornerShape(22.dp),
                color = MaterialTheme.colorScheme.background,
                border = androidx.compose.foundation.BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
                tonalElevation = 6.dp,
            ) {
                Column(
                    modifier = Modifier.padding(start = 22.dp, end = 22.dp, top = 22.dp, bottom = 14.dp),
                    verticalArrangement = Arrangement.spacedBy(10.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                ) {
                    Box(
                        modifier = Modifier
                            .size(54.dp)
                            .clip(CircleShape)
                            .background(aiGrad()),
                        contentAlignment = Alignment.Center,
                    ) {
                        Icon(
                            imageVector = action.heroIcon,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.primary,
                            modifier = Modifier.size(26.dp),
                        )
                    }
                    Text(
                        text = stringResource(action.titleRes, itemCount),
                        fontSize = 18.sp,
                        fontWeight = FontWeight.ExtraBold,
                        textAlign = TextAlign.Center,
                        color = MaterialTheme.colorScheme.onBackground,
                    )
                    Text(
                        text = stringResource(action.bodyRes),
                        fontSize = 13.sp,
                        lineHeight = 19.sp,
                        textAlign = TextAlign.Center,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )

                    // Cost breakdown card
                    Surface(
                        modifier = Modifier.fillMaxWidth(),
                        shape = RoundedCornerShape(12.dp),
                        color = MaterialTheme.colorScheme.surface,
                        border = androidx.compose.foundation.BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
                    ) {
                        Column(modifier = Modifier.padding(horizontal = 14.dp, vertical = 12.dp)) {
                            KeyValueRow(
                                key = stringResource(R.string.settings_confirm_takes_about),
                                value = stringResource(R.string.settings_confirm_minutes, minutes),
                            )
                            if (managed) {
                                androidx.compose.foundation.layout.Spacer(Modifier.size(6.dp))
                                KeyValueRow(
                                    key = stringResource(R.string.settings_confirm_credits_used),
                                    value = stringResource(R.string.settings_confirm_credits_value, creditsNeeded, balance),
                                )
                            }
                            if (!canAfford) {
                                Box(
                                    modifier = Modifier
                                        .padding(top = 8.dp)
                                        .fillMaxWidth()
                                        .clip(RoundedCornerShape(8.dp))
                                        .background(MaterialTheme.colorScheme.error.copy(alpha = 0.08f))
                                        .padding(horizontal = 9.dp, vertical = 7.dp),
                                ) {
                                    Row(
                                        verticalAlignment = Alignment.CenterVertically,
                                        horizontalArrangement = Arrangement.spacedBy(6.dp),
                                    ) {
                                        Icon(
                                            imageVector = Icons.Filled.Warning,
                                            contentDescription = null,
                                            tint = MaterialTheme.colorScheme.error,
                                            modifier = Modifier.size(13.dp),
                                        )
                                        Text(
                                            text = stringResource(R.string.settings_confirm_short_format, shortBy),
                                            fontSize = 11.5.sp,
                                            fontWeight = FontWeight.SemiBold,
                                            color = MaterialTheme.colorScheme.error,
                                        )
                                    }
                                }
                            }
                        }
                    }

                    // Actions
                    Button(
                        onClick = { if (canAfford) onConfirm() else onBuyCredits() },
                        modifier = Modifier.fillMaxWidth(),
                        shape = RoundedCornerShape(12.dp),
                        colors = ButtonDefaults.buttonColors(
                            containerColor = MaterialTheme.colorScheme.primary,
                            contentColor = MaterialTheme.colorScheme.onPrimary,
                        ),
                    ) {
                        Text(
                            text = if (canAfford) stringResource(action.confirmLabelRes)
                            else stringResource(R.string.settings_confirm_buy_and_continue),
                            fontWeight = FontWeight.Bold,
                        )
                    }
                    TextButton(onClick = onDismiss, modifier = Modifier.fillMaxWidth()) {
                        Text(
                            text = stringResource(R.string.action_cancel),
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun KeyValueRow(key: String, value: String) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(text = key, fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Text(
            text = value,
            fontSize = 13.sp,
            fontWeight = FontWeight.Bold,
            color = MaterialTheme.colorScheme.onBackground,
        )
    }
}
