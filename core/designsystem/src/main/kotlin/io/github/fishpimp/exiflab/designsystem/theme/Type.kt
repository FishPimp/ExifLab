package io.github.fishpimp.exiflab.designsystem.theme

import androidx.compose.material3.Typography
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontVariation
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp
import io.github.fishpimp.exiflab.designsystem.R

private fun bricolage(weight: Int, width: Float, opticalSize: Float) = Font(
    resId = R.font.bricolage_grotesque,
    weight = FontWeight(weight),
    variationSettings = FontVariation.Settings(
        FontVariation.weight(weight),
        FontVariation.width(width),
        FontVariation.Setting("opsz", opticalSize),
    ),
)

private fun jetBrainsMono(weight: Int) = Font(
    resId = R.font.jetbrains_mono,
    weight = FontWeight(weight),
    variationSettings = FontVariation.Settings(FontVariation.weight(weight)),
)

/** Expressive display face for large headings and hero numbers. */
val DisplayFontFamily = FontFamily(
    bricolage(weight = 500, width = 100f, opticalSize = 48f),
    bricolage(weight = 600, width = 95f, opticalSize = 72f),
    bricolage(weight = 700, width = 90f, opticalSize = 96f),
    bricolage(weight = 800, width = 85f, opticalSize = 96f),
)

/** Monospace face for raw values, tag IDs and technical numbers. */
val MonoFontFamily = FontFamily(
    jetBrainsMono(400),
    jetBrainsMono(500),
    jetBrainsMono(700),
)

private val base = Typography()

val ExifLabTypography = Typography(
    displayLarge = base.displayLarge.copy(fontFamily = DisplayFontFamily, fontWeight = FontWeight(800)),
    displayMedium = base.displayMedium.copy(fontFamily = DisplayFontFamily, fontWeight = FontWeight(800)),
    displaySmall = base.displaySmall.copy(fontFamily = DisplayFontFamily, fontWeight = FontWeight(700)),
    headlineLarge = base.headlineLarge.copy(fontFamily = DisplayFontFamily, fontWeight = FontWeight(700)),
    headlineMedium = base.headlineMedium.copy(fontFamily = DisplayFontFamily, fontWeight = FontWeight(700)),
    headlineSmall = base.headlineSmall.copy(fontFamily = DisplayFontFamily, fontWeight = FontWeight(600)),
    titleLarge = base.titleLarge.copy(fontFamily = DisplayFontFamily, fontWeight = FontWeight(600)),
    titleMedium = base.titleMedium.copy(fontWeight = FontWeight.SemiBold),
    titleSmall = base.titleSmall.copy(fontWeight = FontWeight.SemiBold),
    bodyLarge = base.bodyLarge,
    bodyMedium = base.bodyMedium,
    bodySmall = base.bodySmall,
    labelLarge = base.labelLarge.copy(fontWeight = FontWeight.SemiBold),
    labelMedium = base.labelMedium.copy(fontWeight = FontWeight.SemiBold),
    labelSmall = base.labelSmall.copy(fontWeight = FontWeight.SemiBold),
)

/** Text styles that Material's type scale does not cover. */
@Immutable
data class ExtendedTypography(
    /** Big technical numbers, e.g. the exposure triangle in the photo header. */
    val numeral: TextStyle,
    val monoLarge: TextStyle,
    val monoMedium: TextStyle,
    val monoSmall: TextStyle,
)

val DefaultExtendedTypography = ExtendedTypography(
    numeral = TextStyle(fontFamily = MonoFontFamily, fontWeight = FontWeight.Bold, fontSize = 28.sp, lineHeight = 34.sp),
    monoLarge = TextStyle(fontFamily = MonoFontFamily, fontWeight = FontWeight.Medium, fontSize = 16.sp, lineHeight = 22.sp),
    monoMedium = TextStyle(fontFamily = MonoFontFamily, fontWeight = FontWeight.Normal, fontSize = 14.sp, lineHeight = 20.sp),
    monoSmall = TextStyle(fontFamily = MonoFontFamily, fontWeight = FontWeight.Normal, fontSize = 12.sp, lineHeight = 16.sp),
)

val LocalExtendedTypography = staticCompositionLocalOf { DefaultExtendedTypography }
