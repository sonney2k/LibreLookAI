package com.librelookai.wardrobe
import android.content.Intent
import android.net.Uri
import android.util.Log
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ShoppingCart
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedCard
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.librelookai.data.model.GapSuggestion
import com.librelookai.R
import com.librelookai.shopping.ShoppingHelperScreen
import com.librelookai.BuildConfig

// Note: the screen-level entry composable lived here; it has been folded into
// `ShoppingHelperScreen` (Shopping → Identify Gaps tab). The card composables
// below are kept for that tab to reuse.

// ---------- Suggestion card ----------

@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun GapSuggestionCard(
    rank: Int,
    suggestion: GapSuggestion,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    val searchQuery = buildString {
        append(suggestion.colors.take(2).joinToString(" "))
        if (isNotEmpty()) append(" ")
        append(suggestion.missingItem)
    }
    val amazonUrl = buildString {
        append("https://www.amazon.com/s?k=")
        append(Uri.encode(searchQuery))
        val tag = BuildConfig.AMAZON_AFFILIATE_TAG
        if (tag.isNotBlank()) { append("&tag="); append(tag) }
    }
    val shopStyleUrl = buildString {
        val pid = BuildConfig.SHOPSTYLE_PUBLISHER_ID
        if (pid.isNotBlank()) {
            append("https://www.shopstyle.com/browse?q=")
            append(Uri.encode(searchQuery))
            append("&pid="); append(pid)
        } else {
            append("https://www.shopstyle.com/browse?q=")
            append(Uri.encode(searchQuery))
        }
    }
    Log.d("ShopButtons", "searchQuery='$searchQuery'")
    Log.d("ShopButtons", "amazonUrl='$amazonUrl'")
    Log.d("ShopButtons", "shopStyleUrl='$shopStyleUrl'")
    Log.d("ShopButtons", "AMAZON_AFFILIATE_TAG='${BuildConfig.AMAZON_AFFILIATE_TAG}'")
    Log.d("ShopButtons", "SHOPSTYLE_PUBLISHER_ID='${BuildConfig.SHOPSTYLE_PUBLISHER_ID}'")

    OutlinedCard(modifier = modifier.fillMaxWidth()) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            // Rank badge + item name + outfit count pill
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                Surface(
                    shape = MaterialTheme.shapes.small,
                    color = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.size(28.dp),
                ) {
                    Box(contentAlignment = Alignment.Center) {
                        Text(
                            "#$rank",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onPrimary,
                            fontWeight = FontWeight.Bold,
                        )
                    }
                }
                Text(
                    suggestion.missingItem,
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.SemiBold,
                    modifier = Modifier.weight(1f),
                )
                if (suggestion.outfitCount > 0) {
                    Card(
                        colors = CardDefaults.cardColors(
                            containerColor = MaterialTheme.colorScheme.primaryContainer,
                        ),
                        shape = MaterialTheme.shapes.small,
                    ) {
                        Text(
                            stringResource(R.string.gap_outfits_pill, suggestion.outfitCount),
                            style = MaterialTheme.typography.labelSmall,
                            fontWeight = FontWeight.SemiBold,
                            color = MaterialTheme.colorScheme.onPrimaryContainer,
                            modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
                        )
                    }
                }
            }

            // Category + color chips
            FlowRow(
                horizontalArrangement = Arrangement.spacedBy(6.dp),
                verticalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                if (suggestion.category.isNotEmpty()) {
                    AssistChip(onClick = {}, label = { Text(suggestion.category) })
                }
                suggestion.colors.forEach { color -> ColorChip(color) }
            }

            // Reason
            if (suggestion.reason.isNotEmpty()) {
                HorizontalDivider()
                Text(
                    suggestion.reason,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }

            // Shop buttons
            HorizontalDivider()
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(
                    onClick = {
                        Log.d("ShopButtons", "Amazon button tapped, launching: $amazonUrl")
                        runCatching {
                            val intent = Intent(Intent.ACTION_VIEW, Uri.parse(amazonUrl))
                            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                            context.startActivity(intent)
                        }.onFailure { e ->
                            Log.e("ShopButtons", "Failed to open Amazon URL: $amazonUrl", e)
                        }
                    },
                    modifier = Modifier.weight(1f),
                    contentPadding = PaddingValues(horizontal = 8.dp, vertical = 8.dp),
                ) {
                    Icon(Icons.Default.ShoppingCart, contentDescription = null, modifier = Modifier.size(14.dp))
                    Spacer(Modifier.width(4.dp))
                    Text(stringResource(R.string.gap_shop_amazon), style = MaterialTheme.typography.labelMedium)
                }
                OutlinedButton(
                    onClick = {
                        Log.d("ShopButtons", "ShopStyle button tapped, launching: $shopStyleUrl")
                        runCatching {
                            val intent = Intent(Intent.ACTION_VIEW, Uri.parse(shopStyleUrl))
                            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                            context.startActivity(intent)
                        }.onFailure { e ->
                            Log.e("ShopButtons", "Failed to open ShopStyle URL: $shopStyleUrl", e)
                        }
                    },
                    modifier = Modifier.weight(1f),
                    contentPadding = PaddingValues(horizontal = 8.dp, vertical = 8.dp),
                ) {
                    Icon(Icons.Default.ShoppingCart, contentDescription = null, modifier = Modifier.size(14.dp))
                    Spacer(Modifier.width(4.dp))
                    Text(stringResource(R.string.gap_shop_shopstyle), style = MaterialTheme.typography.labelMedium)
                }
            }
        }
    }
}

// ---------- Colour dot chip ----------

@Composable
private fun ColorChip(colorName: String) {
    val dot = COLOR_MAP[colorName.lowercase()] ?: MaterialTheme.colorScheme.outline
    AssistChip(
        onClick = {},
        label = { Text(colorName) },
        leadingIcon = {
            Surface(
                shape = MaterialTheme.shapes.extraSmall,
                color = dot,
                modifier = Modifier.size(10.dp),
            ) {}
        },
    )
}

private val COLOR_MAP = mapOf(
    "black"      to Color(0xFF212121),
    "white"      to Color(0xFFF5F5F5),
    "navy"       to Color(0xFF1A237E),
    "blue"       to Color(0xFF1565C0),
    "grey"       to Color(0xFF757575),
    "gray"       to Color(0xFF757575),
    "beige"      to Color(0xFFF5F0E8),
    "cream"      to Color(0xFFFFFDD0),
    "brown"      to Color(0xFF5D4037),
    "tan"        to Color(0xFFD2B48C),
    "camel"      to Color(0xFFC19A6B),
    "red"        to Color(0xFFC62828),
    "burgundy"   to Color(0xFF880E4F),
    "green"      to Color(0xFF2E7D32),
    "olive"      to Color(0xFF827717),
    "khaki"      to Color(0xFFBDB76B),
    "yellow"     to Color(0xFFF9A825),
    "orange"     to Color(0xFFE65100),
    "pink"       to Color(0xFFE91E63),
    "purple"     to Color(0xFF6A1B9A),
    "gold"       to Color(0xFFFFD700),
    "silver"     to Color(0xFFC0C0C0),
)
