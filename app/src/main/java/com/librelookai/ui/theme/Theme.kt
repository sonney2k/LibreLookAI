package com.librelookai.ui.theme

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider

@Composable
fun LibreLookAITheme(
    paletteId: String? = null,
    fontId: String = com.librelookai.AppFont.CAVEAT,
    content: @Composable () -> Unit,
) {
    val palette = wardrobePaletteById(paletteId)
    val colorScheme = if (palette.isDark) {
        darkColorScheme(
            primary = palette.primary,
            onPrimary = palette.onPrimary,
            primaryContainer = palette.primaryDim,
            onPrimaryContainer = palette.text,
            secondary = palette.primary,
            onSecondary = palette.onPrimary,
            secondaryContainer = palette.surface2,
            onSecondaryContainer = palette.text,
            tertiary = palette.primary,
            background = palette.bg,
            onBackground = palette.text,
            surface = palette.surface,
            onSurface = palette.text,
            surfaceVariant = palette.surface2,
            onSurfaceVariant = palette.textMuted,
            outline = palette.border,
            outlineVariant = palette.divider,
            error = palette.error,
        )
    } else {
        lightColorScheme(
            primary = palette.primary,
            onPrimary = palette.onPrimary,
            primaryContainer = palette.primaryDim,
            onPrimaryContainer = palette.text,
            secondary = palette.primary,
            onSecondary = palette.onPrimary,
            secondaryContainer = palette.surface2,
            onSecondaryContainer = palette.text,
            tertiary = palette.primary,
            background = palette.bg,
            onBackground = palette.text,
            surface = palette.surface,
            onSurface = palette.text,
            surfaceVariant = palette.surface2,
            onSurfaceVariant = palette.textMuted,
            outline = palette.border,
            outlineVariant = palette.divider,
            error = palette.error,
        )
    }
    CompositionLocalProvider(
        LocalWardrobePalette provides palette,
        LocalAppFont provides fontId,
    ) {
        MaterialTheme(
            colorScheme = colorScheme,
            typography = typographyFor(fontId),
            content = content,
        )
    }
}
