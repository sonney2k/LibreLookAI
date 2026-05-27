package com.librelookai.settings.v2

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/**
 * Shared building blocks for the redesigned (V1) Settings surface — the Compose
 * equivalent of `settings-shared.jsx`. iOS-Settings idiom: small all-caps section
 * labels above grouped cards of tap rows. See `design_handoff_settings_v1/README.md`.
 */

// ---------- Derived design tokens ----------
//
// The handoff references `primarySoft` / `aiAccent` / `aiGrad` / `aiGradStrong`
// tokens that don't exist as discrete theme entries. They map onto the active
// MaterialTheme colorScheme as documented in the README's "Design tokens" table.

/** primary @ 8% alpha — the active-closet row tint. */
@Composable
fun primarySoft(): Color = MaterialTheme.colorScheme.primary.copy(alpha = 0.08f)

/** Brand accent — a lighter sibling of primary used for AI-flavoured icon tiles. */
@Composable
fun aiAccent(): Color = lerp(MaterialTheme.colorScheme.primary, Color.White, 0.28f)

/** Soft AI gradient (coin / hero-icon tiles). */
@Composable
fun aiGrad(): Brush {
    val primary = MaterialTheme.colorScheme.primary
    return Brush.linearGradient(listOf(aiAccent().copy(alpha = 0.22f), primary.copy(alpha = 0.14f)))
}

/** Strong AI gradient (the "You" hero avatar). */
@Composable
fun aiGradStrong(): Brush =
    Brush.linearGradient(listOf(aiAccent(), MaterialTheme.colorScheme.primary))

// ---------- Section label ----------

/** Small all-caps label above a group of rows, with an optional trailing hint. */
@Composable
fun SecLabel(text: String, modifier: Modifier = Modifier, hint: String? = null) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .padding(start = 20.dp, end = 20.dp, top = 18.dp, bottom = 6.dp),
        verticalAlignment = Alignment.Bottom,
        horizontalArrangement = Arrangement.SpaceBetween,
    ) {
        Text(
            text = text.uppercase(),
            fontSize = 11.sp,
            fontWeight = FontWeight.Bold,
            letterSpacing = 0.8.sp,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        if (hint != null) {
            Text(text = hint, fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

// ---------- Grouped card ----------

/** A rounded grouped card containing rows; 16dp horizontal margin throughout. */
@Composable
fun SettingsCard(
    modifier: Modifier = Modifier,
    content: @Composable () -> Unit,
) {
    Surface(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp),
        shape = RoundedCornerShape(14.dp),
        color = MaterialTheme.colorScheme.surface,
        border = androidx.compose.foundation.BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
    ) {
        Column { content() }
    }
}

// ---------- Tap row ----------

/** Trailing accessory for a [SettingsRow]. */
sealed interface RowAccessory {
    /** A chevron-right hint (default — the row navigates). */
    data object Chevron : RowAccessory
    /** A Material switch reflecting [checked]. */
    data class SwitchToggle(val checked: Boolean, val onCheckedChange: (Boolean) -> Unit) : RowAccessory
    /** No trailing accessory. */
    data object None : RowAccessory
}

/**
 * The settings building block: optional 30dp leading icon tile, label, optional
 * subtitle, optional right-aligned trailing value, and a trailing accessory.
 * Hit target is ≥48dp; the whole row is a semantic Button when [onClick] is set.
 */
@Composable
fun SettingsRow(
    label: String,
    modifier: Modifier = Modifier,
    icon: ImageVector? = null,
    iconBg: Color? = null,
    iconTint: Color? = null,
    sub: String? = null,
    value: String? = null,
    danger: Boolean = false,
    isLast: Boolean = false,
    accessory: RowAccessory = RowAccessory.Chevron,
    onClick: (() -> Unit)? = null,
) {
    val contentColor = if (danger) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onBackground
    val rowSemantics = Modifier.semantics {
        role = Role.Button
        contentDescription = listOfNotNull(label, sub).joinToString(", ")
    }
    Column(modifier = modifier) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .then(if (onClick != null) Modifier.clickable(onClick = onClick).then(rowSemantics) else Modifier)
                .heightIn(min = 48.dp)
                .padding(horizontal = 14.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            if (icon != null) {
                Box(
                    modifier = Modifier
                        .size(30.dp)
                        .clip(RoundedCornerShape(8.dp))
                        .background(iconBg ?: MaterialTheme.colorScheme.primaryContainer),
                    contentAlignment = Alignment.Center,
                ) {
                    Icon(
                        imageVector = icon,
                        contentDescription = null,
                        tint = if (danger) MaterialTheme.colorScheme.error else (iconTint ?: MaterialTheme.colorScheme.primary),
                        modifier = Modifier.size(17.dp),
                    )
                }
            }
            Column(
                modifier = Modifier.weight(1f),
                verticalArrangement = Arrangement.spacedBy(2.dp),
            ) {
                Text(
                    text = label,
                    fontSize = 14.sp,
                    fontWeight = FontWeight.SemiBold,
                    color = contentColor,
                )
                if (sub != null) {
                    Text(
                        text = sub,
                        fontSize = 11.5.sp,
                        lineHeight = 15.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
            if (value != null) {
                Text(
                    text = value,
                    fontSize = 13.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.widthIn(max = 130.dp),
                )
            }
            when (accessory) {
                is RowAccessory.Chevron -> if (onClick != null || value == null) {
                    Icon(
                        imageVector = Icons.AutoMirrored.Filled.KeyboardArrowRight,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                is RowAccessory.SwitchToggle -> Switch(
                    checked = accessory.checked,
                    onCheckedChange = accessory.onCheckedChange,
                )
                RowAccessory.None -> {}
            }
        }
        if (!isLast) {
            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
        }
    }
}
