package com.librelookai
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Snackbar
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.librelookai.util.AiProcessingOverlay
import com.librelookai.wardrobe.GapSuggestionCard
import com.librelookai.wardrobe.WardrobeGapViewModel
import com.librelookai.core.designsystem.R as DsR

/**
 * Full-screen dialog hosting the "Suggest replacements" result for a set of wardrobe items
 * the user has selected to retire. Mirrors the loading/error UX of the Identify Gaps tab and
 * is hosted at the [MainActivity] level (consistent with the composer routes).
 */
@Composable
fun ReplacementsResultDialog(gapViewModel: WardrobeGapViewModel) {
    val state by gapViewModel.state.collectAsState()
    if (!state.isReplacementsOpen) return

    Dialog(
        onDismissRequest = gapViewModel::closeReplacements,
        properties = DialogProperties(
            usePlatformDefaultWidth = false,
            dismissOnBackPress = true,
            dismissOnClickOutside = false,
        ),
    ) {
        Surface(
            modifier = Modifier.fillMaxSize(),
            color = MaterialTheme.colorScheme.surface,
        ) {
            Column(modifier = Modifier.fillMaxSize().navigationBarsPadding().imePadding()) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .statusBarsPadding()
                        .padding(horizontal = 4.dp, vertical = 4.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    IconButton(onClick = gapViewModel::closeReplacements) {
                        Icon(Icons.Default.Close, contentDescription = stringResource(DsR.string.action_close))
                    }
                    Text(
                        stringResource(R.string.replacements_title, state.replacementsCount),
                        style = MaterialTheme.typography.titleMedium,
                        modifier = Modifier.weight(1f).padding(start = 4.dp),
                    )
                }
                HorizontalDivider()

                Box(modifier = Modifier.fillMaxSize()) {
                    val analysis = state.replacements
                    if (analysis != null) {
                        LazyColumn(contentPadding = PaddingValues(bottom = 24.dp)) {
                            if (analysis.summary.isNotEmpty()) {
                                item {
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
                            itemsIndexed(analysis.suggestions) { index, suggestion ->
                                GapSuggestionCard(
                                    rank = index + 1,
                                    suggestion = suggestion,
                                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 6.dp),
                                )
                            }
                        }
                    }

                    if (state.isSuggestingReplacements) {
                        AiProcessingOverlay(
                            label = stringResource(R.string.replacements_analyzing),
                            modifier = Modifier.fillMaxSize(),
                        )
                    }

                    state.replacementsError?.let { msg ->
                        Snackbar(
                            modifier = Modifier
                                .align(Alignment.BottomCenter)
                                .padding(16.dp),
                            action = {
                                TextButton(onClick = gapViewModel::clearReplacementsError) {
                                    Text(stringResource(DsR.string.action_ok))
                                }
                            },
                        ) { Text(msg) }
                    }

                    if (!state.isSuggestingReplacements && analysis == null && state.replacementsError == null) {
                        Box(
                            modifier = Modifier.fillMaxSize().padding(32.dp),
                            contentAlignment = Alignment.Center,
                        ) {
                            Text(
                                stringResource(R.string.gap_empty_hint),
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                }
            }
        }
    }
}
