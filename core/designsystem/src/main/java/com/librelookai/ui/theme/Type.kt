package com.librelookai.ui.theme

import androidx.compose.material3.Typography
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.TextUnitType
import androidx.compose.ui.unit.sp
import com.librelookai.core.designsystem.R
import com.librelookai.settings.AppFont

val CaveatFontFamily = FontFamily(
    Font(R.font.caveat_regular, FontWeight.Normal),
    Font(R.font.caveat_medium, FontWeight.Medium),
    Font(R.font.caveat_semibold, FontWeight.SemiBold),
    Font(R.font.caveat_bold, FontWeight.Bold),
)

/** Selected font id, available to any composable under [LibreLookAITheme]. */
val LocalAppFont = compositionLocalOf { AppFont.CAVEAT }

private val Base = Typography()

private const val CAVEAT_SCALE = 1.35f

private fun TextUnit.scaled(): TextUnit =
    if (type == TextUnitType.Sp) (value * CAVEAT_SCALE).sp else this

private fun TextStyle.toCaveat(): TextStyle = copy(
    fontFamily = CaveatFontFamily,
    fontSize = fontSize.scaled(),
    lineHeight = lineHeight.scaled(),
)

private val CaveatTypography = Typography(
    displayLarge = Base.displayLarge.toCaveat(),
    displayMedium = Base.displayMedium.toCaveat(),
    displaySmall = Base.displaySmall.toCaveat(),
    headlineLarge = Base.headlineLarge.toCaveat(),
    headlineMedium = Base.headlineMedium.toCaveat(),
    headlineSmall = Base.headlineSmall.toCaveat(),
    titleLarge = Base.titleLarge.toCaveat(),
    titleMedium = Base.titleMedium.toCaveat(),
    titleSmall = Base.titleSmall.toCaveat(),
    bodyLarge = Base.bodyLarge.toCaveat(),
    bodyMedium = Base.bodyMedium.toCaveat(),
    bodySmall = Base.bodySmall.toCaveat(),
    labelLarge = Base.labelLarge.toCaveat(),
    labelMedium = Base.labelMedium.toCaveat(),
    labelSmall = Base.labelSmall.toCaveat(),
)

fun typographyFor(fontId: String): Typography = when (fontId) {
    AppFont.CAVEAT -> CaveatTypography
    else -> Base
}
