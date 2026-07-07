package com.librelookai.settings

import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.lerp

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
