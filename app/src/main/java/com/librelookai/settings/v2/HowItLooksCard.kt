package com.librelookai.settings.v2

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.librelookai.R
import com.librelookai.ui.theme.WardrobePalette
import com.librelookai.ui.theme.WardrobePalettes

/**
 * "How it looks" card — a horizontally-scrolling row of theme swatches that apply
 * live on tap. See README §"How it looks" card.
 */
@Composable
fun HowItLooksCard(
    selectedThemeId: String,
    onSelectTheme: (String) -> Unit,
) {
    SettingsCard {
        Column(modifier = Modifier.padding(top = 12.dp, bottom = 16.dp)) {
            Text(
                text = stringResource(R.string.settings_theme_hint),
                fontSize = 11.5.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(start = 16.dp, end = 16.dp, top = 4.dp, bottom = 12.dp),
            )
            LazyRow(
                modifier = Modifier.fillMaxWidth(),
                contentPadding = PaddingValues(horizontal = 16.dp),
                horizontalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                items(WardrobePalettes) { palette ->
                    ThemeSwatch(
                        palette = palette,
                        selected = palette.id == selectedThemeId,
                        onClick = { onSelectTheme(palette.id) },
                    )
                }
            }
        }
    }
}

@Composable
private fun ThemeSwatch(
    palette: WardrobePalette,
    selected: Boolean,
    onClick: () -> Unit,
) {
    val borderColor = if (selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outlineVariant
    val checkScale by animateFloatAsState(targetValue = if (selected) 1f else 0f, label = "swatchCheck")
    Column(
        modifier = Modifier
            .width(78.dp)
            .clickable(onClick = onClick)
            .semantics { role = Role.RadioButton },
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Box(
            modifier = Modifier
                .width(64.dp)
                .height(84.dp)
                .clip(RoundedCornerShape(14.dp))
                .background(palette.bg)
                .border(2.dp, borderColor, RoundedCornerShape(14.dp)),
        ) {
            // Top bar (surface2)
            Box(
                modifier = Modifier
                    .padding(start = 8.dp, end = 8.dp, top = 8.dp)
                    .fillMaxWidth()
                    .height(18.dp)
                    .clip(RoundedCornerShape(6.dp))
                    .background(palette.surface2),
            )
            // Primary circle
            Box(
                modifier = Modifier
                    .padding(start = 8.dp, top = 32.dp)
                    .size(34.dp)
                    .clip(CircleShape)
                    .background(palette.primary),
            )
            // Accent dot (bottom-right)
            Box(
                modifier = Modifier
                    .align(Alignment.BottomEnd)
                    .padding(end = 8.dp, bottom = 8.dp)
                    .size(18.dp)
                    .clip(CircleShape)
                    .background(palette.primaryDim),
            )
            // Selected check badge
            Box(
                modifier = Modifier
                    .align(Alignment.TopEnd)
                    .padding(4.dp)
                    .scale(checkScale)
                    .size(18.dp)
                    .clip(CircleShape)
                    .background(MaterialTheme.colorScheme.primary),
                contentAlignment = Alignment.Center,
            ) {
                Icon(
                    imageVector = Icons.Filled.Check,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.onPrimary,
                    modifier = Modifier.size(12.dp),
                )
            }
        }
        Text(
            text = humanizeThemeId(palette.id),
            fontSize = 11.sp,
            fontWeight = if (selected) FontWeight.Bold else FontWeight.Medium,
            color = if (selected) MaterialTheme.colorScheme.onBackground else MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

private fun humanizeThemeId(id: String): String =
    id.split('-').joinToString(" ") { it.replaceFirstChar { c -> c.uppercaseChar() } }
