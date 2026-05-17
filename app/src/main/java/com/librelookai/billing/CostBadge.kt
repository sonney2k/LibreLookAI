package com.librelookai.billing

import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.librelookai.R
import com.librelookai.gemini.ApiKeyStore
import com.librelookai.gemini.GeminiActionId
import com.librelookai.gemini.PricingClient

/**
 * Small leading-icon cost indicator meant to be placed **inside** an existing
 * button's label row, e.g.:
 *
 *     Button(onClick = ...) {
 *         CostBadge(GeminiActionId.CLASSIFY_CLOTHING)
 *         Text("Auto-tag")
 *     }
 *
 * Renders:
 *   - "🪙 N"         — managed mode, where N is the live coin cost from
 *                      [PricingClient]. [bulkCount] multiplies it for batches.
 *   - "~Nk tokens"   — BYOK mode (user-supplied Gemini key). Shown as a rough
 *                      estimate so the user can reason about their own quota.
 *   - nothing        — when no key and no proxy are configured.
 */
@Composable
fun CostBadge(
    action: GeminiActionId,
    bulkCount: Int = 1,
    modifier: Modifier = Modifier,
) {
    val ctx = LocalContext.current
    // Re-collecting on every recomposition is cheap: StateFlow is a snapshot.
    val costs by PricingClient.costsState.collectAsState()
    val byok = ApiKeyStore.get(ctx).isNotBlank()
    val managedAvailable = com.librelookai.BuildConfig.PROXY_BASE_URL.isNotBlank()

    val text: String? = when {
        byok -> {
            val tokens = estimateTokens(action) * bulkCount
            stringResource(R.string.cost_badge_tokens_estimate, formatTokens(tokens))
        }
        managedAvailable -> {
            val perCall = costs[action.key] ?: action.fallbackCost
            stringResource(R.string.cost_badge_coins, perCall * bulkCount)
        }
        else -> null
    }
    if (text == null) return

    Surface(
        modifier = modifier.padding(end = 6.dp),
        shape = RoundedCornerShape(8.dp),
        color = MaterialTheme.colorScheme.secondaryContainer,
        contentColor = MaterialTheme.colorScheme.onSecondaryContainer,
    ) {
        Text(
            text = text,
            style = MaterialTheme.typography.labelSmall.copy(fontWeight = FontWeight.SemiBold),
            modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp),
        )
    }
}

/**
 * True when an AI action should prompt the user before spending coins.
 * Callers query this in event handlers (not during composition) to decide
 * whether to show a [ConfirmSpendDialog] or fire the action immediately.
 *
 * Triggers when, in managed mode:
 *   - [bulkCount] >= [bulkThreshold]                 (default 5), OR
 *   - total coin cost >= [coinThreshold]             (default 20).
 *
 * BYOK mode never requires confirmation — users are spending their own tokens.
 */
fun requiresSpendConfirm(
    context: android.content.Context,
    action: GeminiActionId,
    bulkCount: Int,
    bulkThreshold: Int = 5,
    coinThreshold: Int = 20,
): Boolean {
    val byok = ApiKeyStore.get(context).isNotBlank()
    if (byok) return false
    val perCall = PricingClient.costsState.value[action.key] ?: action.fallbackCost
    val total = perCall * bulkCount
    return bulkCount >= bulkThreshold || total >= coinThreshold
}

/**
 * Confirmation dialog. Always renders when invoked — the visibility decision
 * (whether to call this at all) belongs to the caller; use [requiresSpendConfirm]
 * to make that call in event handlers.
 */
@Composable
fun ConfirmSpendDialog(
    action: GeminiActionId,
    bulkCount: Int,
    onConfirm: () -> Unit,
    onDismiss: () -> Unit,
) {
    val costs by PricingClient.costsState.collectAsState()
    val perCall = costs[action.key] ?: action.fallbackCost
    val total = perCall * bulkCount

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.confirm_spend_title)) },
        text = {
            Text(
                stringResource(
                    if (bulkCount > 1) R.string.confirm_spend_body_bulk else R.string.confirm_spend_body,
                    total,
                    bulkCount,
                ),
            )
        },
        confirmButton = {
            TextButton(onClick = { onDismiss(); onConfirm() }) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Surface(
                        shape = RoundedCornerShape(8.dp),
                        color = MaterialTheme.colorScheme.secondaryContainer,
                        contentColor = MaterialTheme.colorScheme.onSecondaryContainer,
                    ) {
                        Text(
                            stringResource(R.string.cost_badge_coins, total),
                            style = MaterialTheme.typography.labelSmall.copy(fontWeight = FontWeight.SemiBold),
                            modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp),
                        )
                    }
                    Text(
                        text = "  " + stringResource(R.string.confirm_spend_continue),
                    )
                }
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text(stringResource(R.string.action_cancel)) }
        },
    )
}

/**
 * Insufficient-credits dialog — caller passes the parsed `needed`/`have`
 * from [InsufficientCreditsException]. The "Buy" button hand-off is the
 * caller's responsibility (typically navigating to BuyCreditsScreen).
 */
@Composable
fun InsufficientCreditsDialog(
    needed: Int,
    have: Int,
    onBuy: () -> Unit,
    onDismiss: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.insufficient_credits_title)) },
        text = { Text(stringResource(R.string.insufficient_credits_body, needed, have)) },
        confirmButton = {
            TextButton(onClick = { onDismiss(); onBuy() }) {
                Text(stringResource(R.string.insufficient_credits_buy))
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text(stringResource(R.string.action_cancel)) }
        },
    )
}

// ---------- helpers ----------

/**
 * Coarse token-count estimate per action, used only for BYOK badge labels.
 * Picked to be conservative (rounded up) so users aren't surprised.
 */
private fun estimateTokens(action: GeminiActionId): Int = when (action) {
    GeminiActionId.REMOVE_BACKGROUND -> 4_000
    GeminiActionId.CLASSIFY_CLOTHING -> 2_000
    GeminiActionId.GENERATE_TEXT -> 1_000
    GeminiActionId.SEARCH_TRENDS -> 2_500
    GeminiActionId.TRY_ON_OUTFIT -> 6_000
    GeminiActionId.OUTFIT_SUGGESTION -> 2_500
}

private fun formatTokens(n: Int): String =
    if (n >= 1_000) "${n / 1_000}k" else n.toString()
