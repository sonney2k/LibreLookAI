package com.librelookai.ui.theme

import androidx.compose.runtime.compositionLocalOf
import androidx.compose.ui.graphics.Color

val Purple80 = Color(0xFFD0BCFF)
val PurpleGrey80 = Color(0xFFCCC2DC)
val Pink80 = Color(0xFFEFB8C8)

val Purple40 = Color(0xFF6650A4)
val PurpleGrey40 = Color(0xFF625B71)
val Pink40 = Color(0xFF7D5260)

/**
 * Extended palette that mirrors the wardrobe hi-fi design tokens. Material3
 * primaries are mapped from these too, but several wardrobe-specific surfaces
 * (chip backgrounds, FAB mini surfaces, selection overlays, sheet backgrounds)
 * have no direct M3 equivalent and live here.
 */
data class WardrobePalette(
    val id: String,
    val isDark: Boolean,
    val bg: Color,
    val surface: Color,
    val surface2: Color,
    val primary: Color,
    val primaryDim: Color,
    val onPrimary: Color,
    val text: Color,
    val textMid: Color,
    val textMuted: Color,
    val border: Color,
    val divider: Color,
    val navBg: Color,
    val navIndicator: Color,
    val chipActiveBg: Color,
    val chipActiveFg: Color,
    val chipBg: Color,
    val chipFg: Color,
    val fabBg: Color,
    val fabFg: Color,
    val fabMini: Color,
    val fabMiniFg: Color,
    val fabMiniBd: Color,
    val selBorder: Color,
    val selOverlay: Color,
    val selCheck: Color,
    val toastBg: Color,
    val toastFg: Color,
    val sheetBg: Color,
    val error: Color,
)

val GreenLightPalette = WardrobePalette(
    id = "green-light", isDark = false,
    bg = Color(0xFFF3F6EF), surface = Color(0xFFEBF1E5), surface2 = Color(0xFFE0EAD8),
    primary = Color(0xFF4E7844), primaryDim = Color(0xFFD0E4C8), onPrimary = Color(0xFFFFFFFF),
    text = Color(0xFF1A2618), textMid = Color(0xFF3D5438), textMuted = Color(0xFF6A8060),
    border = Color(0xFFBDD0B2), divider = Color(0xFFD4E0CC),
    navBg = Color(0xFFEBF1E5), navIndicator = Color(0xFFD0E4C8),
    chipActiveBg = Color(0xFF4E7844), chipActiveFg = Color(0xFFFFFFFF),
    chipBg = Color(0xFFDFE9D8), chipFg = Color(0xFF2E4A28),
    fabBg = Color(0xFF4E7844), fabFg = Color(0xFFFFFFFF),
    fabMini = Color(0xFFEBF1E5), fabMiniFg = Color(0xFF4E7844), fabMiniBd = Color(0xFF4E7844),
    selBorder = Color(0xFF4E7844), selOverlay = Color(0x2E4E7844), selCheck = Color(0xFF4E7844),
    toastBg = Color(0xFF1A2618), toastFg = Color(0xFFEBF1E5),
    sheetBg = Color(0xFFEBF1E5), error = Color(0xFFC0392B),
)

val GreenDarkPalette = WardrobePalette(
    id = "green-dark", isDark = true,
    bg = Color(0xFF131A11), surface = Color(0xFF1B231A), surface2 = Color(0xFF243024),
    primary = Color(0xFF9CC58F), primaryDim = Color(0xFF2E4A28), onPrimary = Color(0xFF0D1209),
    text = Color(0xFFE9F0E4), textMid = Color(0xFFB8C9B0), textMuted = Color(0xFF8A9D81),
    border = Color(0xFF334033), divider = Color(0xFF2A352A),
    navBg = Color(0xFF1B231A), navIndicator = Color(0xFF2E4A28),
    chipActiveBg = Color(0xFF9CC58F), chipActiveFg = Color(0xFF0D1209),
    chipBg = Color(0xFF243024), chipFg = Color(0xFFD0E4C8),
    fabBg = Color(0xFF9CC58F), fabFg = Color(0xFF0D1209),
    fabMini = Color(0xFF243024), fabMiniFg = Color(0xFF9CC58F), fabMiniBd = Color(0xFF9CC58F),
    selBorder = Color(0xFF9CC58F), selOverlay = Color(0x2E9CC58F), selCheck = Color(0xFF9CC58F),
    toastBg = Color(0xFFE9F0E4), toastFg = Color(0xFF131A11),
    sheetBg = Color(0xFF1B231A), error = Color(0xFFE57368),
)

val SandLightPalette = WardrobePalette(
    id = "sand-light", isDark = false,
    bg = Color(0xFFF8F4EC), surface = Color(0xFFF1EADC), surface2 = Color(0xFFE7DCC8),
    primary = Color(0xFF8B6A3F), primaryDim = Color(0xFFE8D9BE), onPrimary = Color(0xFFFFFFFF),
    text = Color(0xFF2A2114), textMid = Color(0xFF54452C), textMuted = Color(0xFF8A7556),
    border = Color(0xFFD2C2A2), divider = Color(0xFFDFD2B7),
    navBg = Color(0xFFF1EADC), navIndicator = Color(0xFFE8D9BE),
    chipActiveBg = Color(0xFF8B6A3F), chipActiveFg = Color(0xFFFFFFFF),
    chipBg = Color(0xFFEDE1C9), chipFg = Color(0xFF54452C),
    fabBg = Color(0xFF8B6A3F), fabFg = Color(0xFFFFFFFF),
    fabMini = Color(0xFFF1EADC), fabMiniFg = Color(0xFF8B6A3F), fabMiniBd = Color(0xFF8B6A3F),
    selBorder = Color(0xFF8B6A3F), selOverlay = Color(0x2E8B6A3F), selCheck = Color(0xFF8B6A3F),
    toastBg = Color(0xFF2A2114), toastFg = Color(0xFFF1EADC),
    sheetBg = Color(0xFFF1EADC), error = Color(0xFFC0392B),
)

val IndigoDarkPalette = WardrobePalette(
    id = "indigo-dark", isDark = true,
    bg = Color(0xFF12131C), surface = Color(0xFF1B1D2A), surface2 = Color(0xFF252839),
    primary = Color(0xFF9AA8FF), primaryDim = Color(0xFF2C3358), onPrimary = Color(0xFF0A0C16),
    text = Color(0xFFE6E8F4), textMid = Color(0xFFB6BAD0), textMuted = Color(0xFF858AA5),
    border = Color(0xFF353A55), divider = Color(0xFF2A2E45),
    navBg = Color(0xFF1B1D2A), navIndicator = Color(0xFF2C3358),
    chipActiveBg = Color(0xFF9AA8FF), chipActiveFg = Color(0xFF0A0C16),
    chipBg = Color(0xFF252839), chipFg = Color(0xFFC8CEEB),
    fabBg = Color(0xFF9AA8FF), fabFg = Color(0xFF0A0C16),
    fabMini = Color(0xFF252839), fabMiniFg = Color(0xFF9AA8FF), fabMiniBd = Color(0xFF9AA8FF),
    selBorder = Color(0xFF9AA8FF), selOverlay = Color(0x2E9AA8FF), selCheck = Color(0xFF9AA8FF),
    toastBg = Color(0xFFE6E8F4), toastFg = Color(0xFF12131C),
    sheetBg = Color(0xFF1B1D2A), error = Color(0xFFE57368),
)

val PastelMintPalette = WardrobePalette(
    id = "pastel-mint", isDark = false,
    bg = Color(0xFFEFF8F2), surface = Color(0xFFE3F1E7), surface2 = Color(0xFFD4E8D8),
    primary = Color(0xFF4FA37A), primaryDim = Color(0xFFCDEAD8), onPrimary = Color(0xFFFFFFFF),
    text = Color(0xFF18261D), textMid = Color(0xFF345040), textMuted = Color(0xFF6E8A78),
    border = Color(0xFFB7D6BF), divider = Color(0xFFCFE2D2),
    navBg = Color(0xFFE3F1E7), navIndicator = Color(0xFFCDEAD8),
    chipActiveBg = Color(0xFF4FA37A), chipActiveFg = Color(0xFFFFFFFF),
    chipBg = Color(0xFFD9EDDE), chipFg = Color(0xFF2C5040),
    fabBg = Color(0xFF4FA37A), fabFg = Color(0xFFFFFFFF),
    fabMini = Color(0xFFE3F1E7), fabMiniFg = Color(0xFF4FA37A), fabMiniBd = Color(0xFF4FA37A),
    selBorder = Color(0xFF4FA37A), selOverlay = Color(0x2E4FA37A), selCheck = Color(0xFF4FA37A),
    toastBg = Color(0xFF18261D), toastFg = Color(0xFFE3F1E7),
    sheetBg = Color(0xFFE3F1E7), error = Color(0xFFC0392B),
)

val PastelBlushPalette = WardrobePalette(
    id = "pastel-blush", isDark = false,
    bg = Color(0xFFFBF1F1), surface = Color(0xFFF5E2E2), surface2 = Color(0xFFEDD0D0),
    primary = Color(0xFFC56F7B), primaryDim = Color(0xFFF1D2D7), onPrimary = Color(0xFFFFFFFF),
    text = Color(0xFF2A1A1C), textMid = Color(0xFF5A3438), textMuted = Color(0xFF8E6A6E),
    border = Color(0xFFE3BCC1), divider = Color(0xFFEDD0D3),
    navBg = Color(0xFFF5E2E2), navIndicator = Color(0xFFF1D2D7),
    chipActiveBg = Color(0xFFC56F7B), chipActiveFg = Color(0xFFFFFFFF),
    chipBg = Color(0xFFF2D8DB), chipFg = Color(0xFF5A3438),
    fabBg = Color(0xFFC56F7B), fabFg = Color(0xFFFFFFFF),
    fabMini = Color(0xFFF5E2E2), fabMiniFg = Color(0xFFC56F7B), fabMiniBd = Color(0xFFC56F7B),
    selBorder = Color(0xFFC56F7B), selOverlay = Color(0x2EC56F7B), selCheck = Color(0xFFC56F7B),
    toastBg = Color(0xFF2A1A1C), toastFg = Color(0xFFF5E2E2),
    sheetBg = Color(0xFFF5E2E2), error = Color(0xFFC0392B),
)

val PastelLavenderPalette = WardrobePalette(
    id = "pastel-lavender", isDark = false,
    bg = Color(0xFFF4F0FB), surface = Color(0xFFE9E1F4), surface2 = Color(0xFFDBCFEB),
    primary = Color(0xFF7E6BC4), primaryDim = Color(0xFFDED1F0), onPrimary = Color(0xFFFFFFFF),
    text = Color(0xFF1F1A2A), textMid = Color(0xFF44395A), textMuted = Color(0xFF7A6C95),
    border = Color(0xFFC9B9E1), divider = Color(0xFFDDCEEC),
    navBg = Color(0xFFE9E1F4), navIndicator = Color(0xFFDED1F0),
    chipActiveBg = Color(0xFF7E6BC4), chipActiveFg = Color(0xFFFFFFFF),
    chipBg = Color(0xFFE0D2F0), chipFg = Color(0xFF44395A),
    fabBg = Color(0xFF7E6BC4), fabFg = Color(0xFFFFFFFF),
    fabMini = Color(0xFFE9E1F4), fabMiniFg = Color(0xFF7E6BC4), fabMiniBd = Color(0xFF7E6BC4),
    selBorder = Color(0xFF7E6BC4), selOverlay = Color(0x2E7E6BC4), selCheck = Color(0xFF7E6BC4),
    toastBg = Color(0xFF1F1A2A), toastFg = Color(0xFFE9E1F4),
    sheetBg = Color(0xFFE9E1F4), error = Color(0xFFC0392B),
)

val WardrobePalettes: List<WardrobePalette> = listOf(
    GreenLightPalette, GreenDarkPalette, SandLightPalette, IndigoDarkPalette,
    PastelMintPalette, PastelBlushPalette, PastelLavenderPalette,
)

fun wardrobePaletteById(id: String?): WardrobePalette =
    WardrobePalettes.firstOrNull { it.id == id } ?: GreenLightPalette

val LocalWardrobePalette = compositionLocalOf { GreenLightPalette }
