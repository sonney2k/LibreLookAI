package com.librelookai

import android.content.Intent
import android.net.Uri
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AutoAwesome
import androidx.compose.material.icons.filled.ShoppingCart
import androidx.compose.material.icons.filled.TipsAndUpdates
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedCard
import androidx.compose.material3.Snackbar
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import coil.compose.AsyncImage

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun WardrobeGapScreen(
    gapViewModel: WardrobeGapViewModel = viewModel(),
    wardrobeViewModel: WardrobeViewModel = viewModel(),
    profileViewModel: ProfileViewModel = viewModel(),
    modifier: Modifier = Modifier,
) {
    val state         by gapViewModel.state.collectAsState()
    val wardrobeState by wardrobeViewModel.state.collectAsState()
    val profileState  by profileViewModel.state.collectAsState()

    Box(modifier = modifier.fillMaxSize()) {
        LazyColumn(
            contentPadding = PaddingValues(bottom = 24.dp),
        ) {
            // ---- Header ----
            item {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp, vertical = 16.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(
                            Icons.Default.TipsAndUpdates,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.primary,
                            modifier = Modifier.size(22.dp),
                        )
                        Spacer(Modifier.width(8.dp))
                        Text(
                            "Wardrobe Gap Analyzer",
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.SemiBold,
                        )
                    }
                    Text(
                        "Discover the 3 missing pieces that would unlock the most new outfit combinations from your existing wardrobe.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Button(
                        onClick = {
                            gapViewModel.analyze(
                                images = wardrobeState.images,
                                prefs  = profileState.preferences,
                            )
                        },
                        enabled = !state.isAnalyzing && wardrobeState.images.isNotEmpty(),
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        Icon(Icons.Default.AutoAwesome, contentDescription = null, modifier = Modifier.size(18.dp))
                        Spacer(Modifier.width(8.dp))
                        Text(if (state.analysis != null) "Re-analyze" else "Analyze My Wardrobe")
                    }
                }
            }

            // ---- Summary ----
            state.analysis?.let { analysis ->
                if (analysis.summary.isNotEmpty()) {
                    item {
                        HorizontalDivider()
                        Surface(
                            color = MaterialTheme.colorScheme.secondaryContainer,
                            modifier = Modifier.fillMaxWidth(),
                        ) {
                            Text(
                                analysis.summary,
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onSecondaryContainer,
                                modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp),
                            )
                        }
                        HorizontalDivider()
                    }
                }

                // ---- Suggestion cards ----
                itemsIndexed(analysis.suggestions) { index, suggestion ->
                    val images = state.suggestionImages[index] ?: emptyList()
                    GapSuggestionCard(
                        rank = index + 1,
                        suggestion = suggestion,
                        imageUrls = images,
                        isLoadingImages = state.isLoadingImages,
                        modifier = Modifier.padding(horizontal = 16.dp, vertical = 6.dp),
                    )
                }
            }

            // ---- Empty state ----
            if (!state.isAnalyzing && state.analysis == null && state.error == null) {
                item {
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(top = 48.dp),
                        contentAlignment = Alignment.Center,
                    ) {
                        Column(
                            horizontalAlignment = Alignment.CenterHorizontally,
                            verticalArrangement = Arrangement.spacedBy(8.dp),
                            modifier = Modifier.padding(horizontal = 32.dp),
                        ) {
                            Icon(
                                Icons.Default.TipsAndUpdates,
                                contentDescription = null,
                                modifier = Modifier.size(48.dp),
                                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                            Text(
                                "Find what's missing",
                                style = MaterialTheme.typography.titleSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                            Text(
                                "Tap \"Analyze\" to let AI identify the foundational pieces that would complete the most outfits from your wardrobe.",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                }
            }
        }

        // AI overlay
        if (state.isAnalyzing) {
            AiProcessingOverlay(
                label = "Analyzing your wardrobe…",
                modifier = Modifier.fillMaxSize(),
            )
        }

        // Error snackbar
        state.error?.let { msg ->
            Snackbar(
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .padding(16.dp),
                action = { TextButton(onClick = gapViewModel::clearError) { Text("OK") } },
            ) { Text(msg) }
        }
    }
}

// ---------- Suggestion card ----------

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun GapSuggestionCard(
    rank: Int,
    suggestion: GapSuggestion,
    imageUrls: List<String>,
    isLoadingImages: Boolean,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    val searchQuery = buildString {
        append(suggestion.colors.take(2).joinToString(" "))
        if (isNotEmpty()) append(" ")
        append(suggestion.missingItem)
    }

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
                            "+${suggestion.outfitCount} outfits",
                            style = MaterialTheme.typography.labelSmall,
                            fontWeight = FontWeight.SemiBold,
                            color = MaterialTheme.colorScheme.onPrimaryContainer,
                            modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
                        )
                    }
                }
            }

            // Product image strip
            when {
                isLoadingImages && imageUrls.isEmpty() -> {
                    Row(
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier.padding(vertical = 4.dp),
                    ) {
                        CircularProgressIndicator(modifier = Modifier.size(20.dp), strokeWidth = 2.dp)
                        Text(
                            "Finding products…",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
                imageUrls.isNotEmpty() -> {
                    LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        items(imageUrls) { url ->
                            AsyncImage(
                                model = url,
                                contentDescription = suggestion.missingItem,
                                modifier = Modifier
                                    .size(88.dp)
                                    .clip(MaterialTheme.shapes.small),
                                contentScale = ContentScale.Crop,
                            )
                        }
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
                        val url = "https://www.amazon.com/s?k=${Uri.encode(searchQuery)}"
                        context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)))
                    },
                    modifier = Modifier.weight(1f),
                    contentPadding = PaddingValues(horizontal = 8.dp, vertical = 8.dp),
                ) {
                    Icon(Icons.Default.ShoppingCart, contentDescription = null, modifier = Modifier.size(14.dp))
                    Spacer(Modifier.width(4.dp))
                    Text("Amazon", style = MaterialTheme.typography.labelMedium)
                }
                OutlinedButton(
                    onClick = {
                        val url = "https://www.google.com/search?q=${Uri.encode(searchQuery)}&tbm=shop"
                        context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)))
                    },
                    modifier = Modifier.weight(1f),
                    contentPadding = PaddingValues(horizontal = 8.dp, vertical = 8.dp),
                ) {
                    Icon(Icons.Default.ShoppingCart, contentDescription = null, modifier = Modifier.size(14.dp))
                    Spacer(Modifier.width(4.dp))
                    Text("Google", style = MaterialTheme.typography.labelMedium)
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
