package com.librelookai.ui.theme

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import com.librelookai.settings.AppFont

@Composable
fun LibreLookAITheme(
    paletteId: String? = null,
    fontId: String = AppFont.CAVEAT,
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
            // The tonal `surfaceContainer*` ladder (used by default filled Card, ModalBottomSheet,
            // menus, etc.) is NOT covered by `surface`/`surfaceVariant`; left unset it falls back to
            // Material's baseline purple. Map it onto the palette so every default M3 surface stays
            // on-theme. Ordered dimmest→brightest: in dark mode that's bg (darkest) → surface2.
            surfaceContainerLowest = palette.bg,
            surfaceContainerLow = palette.surface,
            surfaceContainer = palette.surface,
            surfaceContainerHigh = palette.surface2,
            surfaceContainerHighest = palette.surface2,
            surfaceBright = palette.surface2,
            surfaceDim = palette.bg,
            inverseSurface = palette.toastBg,
            inverseOnSurface = palette.toastFg,
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
            // See the dark branch above. In light mode the order flips: bg is the brightest tone
            // and surface2 the dimmest, but the `surfaceContainer*` ladder still runs lowest→highest
            // as bg → surface2 (lowest = closest to the page background).
            surfaceContainerLowest = palette.bg,
            surfaceContainerLow = palette.surface,
            surfaceContainer = palette.surface,
            surfaceContainerHigh = palette.surface2,
            surfaceContainerHighest = palette.surface2,
            surfaceBright = palette.bg,
            surfaceDim = palette.surface2,
            inverseSurface = palette.toastBg,
            inverseOnSurface = palette.toastFg,
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
