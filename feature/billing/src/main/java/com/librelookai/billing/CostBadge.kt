package com.librelookai.billing

import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.PlainTooltip
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TooltipBox
import androidx.compose.material3.TooltipDefaults
import androidx.compose.material3.rememberTooltipState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import com.librelookai.feature.billing.R
import com.librelookai.core.designsystem.R as DsR
import com.librelookai.gemini.ApiKeyStore
import com.librelookai.gemini.CostTokens
import com.librelookai.gemini.GeminiActionId
import com.librelookai.gemini.ModelPricingClient
import com.librelookai.gemini.PricingClient

/**
 * Cost tier badge for AI actions. Placed **inside** an existing button's
 * label row, e.g.:
 *
 *     Button(onClick = ...) {
 *         CostBadge(GeminiActionId.CLASSIFY_CLOTHING)
 *         Text("Auto-tag")
 *     }
 *
 * Renders the action's tier (1–4) as four `€` glyphs with the active count
 * painted in the primary colour. Long-press → tooltip with the exact value
 * (coin count in managed mode, EUR amount in BYOK). Hidden when no API key
 * and no proxy are configured.
 *
 * In BYOK mode the badge prices the **actual upcoming payload** when the caller
 * passes [tokens] (built via `TokenEstimator` from the real prompt + images);
 * otherwise it falls back to the coarse per-action [estimateTokensFor] table.
 */
@Composable
fun CostBadge(
    action: GeminiActionId,
    bulkCount: Int = 1,
    tokens: CostTokens? = null,
    modifier: Modifier = Modifier,
) {
    val ctx = LocalContext.current
    val costs by PricingClient.costsState.collectAsState()
    val perItemCosts by PricingClient.perItemCostsState.collectAsState()
    val rates by ModelPricingClient.snapshotState.collectAsState()
    val byok = ApiKeyStore.get(ctx).isNotBlank()
    val managedAvailable = ManagedBilling.enabled && com.librelookai.feature.billing.BuildConfig.PROXY_BASE_URL.isNotBlank()

    val items = bulkCount.coerceAtLeast(1)
    val tier: Int
    val tooltip: String
    when {
        byok -> {
            val t = tokens ?: estimateTokensFor(action, items).let { (inT, outT) ->
                CostTokens(inT, outT, ModelPricingClient.isImageOutputModel(action.model))
            }
            val eur = rates.ratesFor(action.model)
                .eurFor(t.inputTokens, t.outputTokens, t.outputIsImage)
            tier = tierForEur(eur)
            tooltip = formatEurBadge(eur)
        }
        managedAvailable -> {
            val perCall = costs[action.key] ?: action.fallbackCost
            val perItem = perItemCosts[action.key] ?: action.fallbackPerItemCost
            val total = perCall + perItem * (items - 1)
            tier = tierForCoins(total)
            tooltip = "🪙 $total"
        }
        else -> return
    }

    TierBadgeWithTooltip(tier = tier, tooltip = tooltip, modifier = modifier)
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun TierBadgeWithTooltip(
    tier: Int,
    tooltip: String,
    modifier: Modifier = Modifier,
) {
    val tooltipState = rememberTooltipState(isPersistent = false)
    TooltipBox(
        positionProvider = TooltipDefaults.rememberPlainTooltipPositionProvider(),
        tooltip = { PlainTooltip { Text(tooltip) } },
        state = tooltipState,
    ) {
        TierBadge(tier = tier, modifier = modifier)
    }
}

@Composable
private fun TierBadge(tier: Int, modifier: Modifier = Modifier) {
    val active = MaterialTheme.colorScheme.onSecondaryContainer
    val inactive = active.copy(alpha = 0.30f)
    val label = buildAnnotatedString {
        repeat(4) { i ->
            withStyle(SpanStyle(color = if (i < tier) active else inactive)) {
                append("€")
            }
        }
    }
    Surface(
        modifier = modifier.padding(end = 6.dp),
        shape = RoundedCornerShape(8.dp),
        color = MaterialTheme.colorScheme.secondaryContainer,
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.labelSmall.copy(fontWeight = FontWeight.Bold),
            modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp),
        )
    }
}

/**
 * Legend explaining the four cost tiers. Designed for the Insights screen header so users
 * can map the small "€€€€" badges they see throughout the app back to a concrete spend.
 * Adapts to managed vs BYOK mode: shows coin thresholds for managed, EUR for BYOK.
 */
@Composable
fun CostTierLegend(modifier: Modifier = Modifier) {
    val ctx = LocalContext.current
    val byok = ApiKeyStore.get(ctx).isNotBlank()
    val rows = if (byok) {
        listOf(
            1 to "< 1¢ per call",
            2 to "1–5¢",
            3 to "5–20¢",
            4 to "> 20¢",
        )
    } else {
        listOf(
            1 to "≤ 2 coins per call",
            2 to "≤ 15 coins",
            3 to "≤ 100 coins",
            4 to "> 100 coins",
        )
    }
    Surface(
        modifier = modifier,
        shape = RoundedCornerShape(12.dp),
        color = MaterialTheme.colorScheme.surfaceVariant,
    ) {
        androidx.compose.foundation.layout.Column(
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 10.dp),
        ) {
            Text(
                stringResource(R.string.cost_legend_title),
                style = MaterialTheme.typography.labelSmall.copy(fontWeight = FontWeight.SemiBold),
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(bottom = 6.dp),
            )
            rows.forEach { (tier, label) ->
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier.padding(vertical = 2.dp),
                ) {
                    TierBadge(tier = tier)
                    Text(
                        label,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
            Text(
                stringResource(R.string.cost_legend_hint),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f),
                modifier = Modifier.padding(top = 6.dp),
            )
        }
    }
}

/**
 * Public tier mapping so the legend renders the same scale. Round-cent decades:
 * `€ <1¢ · €€ 1–5¢ · €€€ 5–20¢ · €€€€ >20¢` per call — cleanly separates text
 * actions (€), single-image actions (€€€), and bulk/multi-image (€€€€).
 */
fun tierForEur(eur: Double): Int = when {
    eur < 0.01 -> 1
    eur < 0.05 -> 2
    eur < 0.20 -> 3
    else -> 4
}

/** Coin-based tier — managed mode uses post-multiplier coins, not EUR. */
fun tierForCoins(coins: Int): Int = when {
    coins <= 2 -> 1
    coins <= 15 -> 2
    coins <= 100 -> 3
    else -> 4
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
    val total = PricingClient.coinCostForItems(action, bulkCount)
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
    val perItemCosts by PricingClient.perItemCostsState.collectAsState()
    val items = bulkCount.coerceAtLeast(1)
    val perCall = costs[action.key] ?: action.fallbackCost
    val perItem = perItemCosts[action.key] ?: action.fallbackPerItemCost
    val total = perCall + perItem * (items - 1)

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
            TextButton(onClick = onDismiss) { Text(stringResource(DsR.string.action_cancel)) }
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
            TextButton(onClick = onDismiss) { Text(stringResource(DsR.string.action_cancel)) }
        },
    )
}

// ---------- helpers ----------

/**
 * Coarse token estimate per action — used only for the BYOK € badge.
 * Returned as four numbers: `(fixedInput, inputPerItem, fixedOutput, outputPerItem)`.
 *
 * Image-out actions report their image-token count under `fixedOutput` (e.g. ~1290 per image
 * for Gemini's image models), so when multiplied by the output EUR rate the badge reflects
 * actual spend rather than text-equivalent tokens. Picked to be conservative (rounded up).
 */
private data class TokenEstimate(
    val fixedInput: Int,
    val inputPerItem: Int,
    val fixedOutput: Int,
    val outputPerItem: Int,
)

private fun estimateTokens(action: GeminiActionId): TokenEstimate = when (action) {
    // BG removal: short text prompt + one tiled image input (~500 tokens), one image out (1290).
    GeminiActionId.REMOVE_BACKGROUND -> TokenEstimate(fixedInput = 700, inputPerItem = 0, fixedOutput = 1290, outputPerItem = 0)
    // Try-on: prompt + ~2 image inputs + one image out.
    GeminiActionId.TRY_ON_OUTFIT -> TokenEstimate(fixedInput = 1200, inputPerItem = 0, fixedOutput = 1290, outputPerItem = 0)
    // Classify: prompt + one image input + small JSON out.
    GeminiActionId.CLASSIFY_CLOTHING -> TokenEstimate(fixedInput = 1000, inputPerItem = 0, fixedOutput = 400, outputPerItem = 0)
    // Multi-suggestion text: large wardrobe-JSON prompt up front; each extra suggestion adds
    // a handful of slot ids + a short caption.
    GeminiActionId.GENERATE_TEXT -> TokenEstimate(fixedInput = 800, inputPerItem = 0, fixedOutput = 150, outputPerItem = 200)
    GeminiActionId.SEARCH_TRENDS -> TokenEstimate(fixedInput = 200, inputPerItem = 0, fixedOutput = 600, outputPerItem = 0)
    GeminiActionId.OUTFIT_SUGGESTION -> TokenEstimate(fixedInput = 800, inputPerItem = 0, fixedOutput = 400, outputPerItem = 0)
}

private fun estimateTokensFor(action: GeminiActionId, items: Int): Pair<Int, Int> {
    val n = items.coerceAtLeast(1)
    val e = estimateTokens(action)
    val input = e.fixedInput + e.inputPerItem * n
    val output = e.fixedOutput + e.outputPerItem * n
    return input to output
}

/** Tooltip text for the BYOK badge — concrete estimate, with a sub-cent floor. */
private fun formatEurBadge(eur: Double): String = when {
    eur < 0.01 -> "< €0.01"
    eur < 100.0 -> "≈ €" + String.format("%.2f", eur)
    else -> "≈ €" + String.format("%.0f", eur)
}

