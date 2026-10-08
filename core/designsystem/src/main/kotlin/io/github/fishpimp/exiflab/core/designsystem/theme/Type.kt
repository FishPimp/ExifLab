package io.github.fishpimp.exiflab.core.designsystem.theme

import androidx.compose.material3.Typography
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.em
import androidx.compose.ui.unit.sp
import io.github.fishpimp.exiflab.core.designsystem.R

/** Bricolage Grotesque (OFL): expressive display face for headers and big numbers. */
public val DisplayFamily: FontFamily = FontFamily(
    Font(R.font.bricolage_grotesque_medium, FontWeight.Medium),
    Font(R.font.bricolage_grotesque_semibold, FontWeight.SemiBold),
    Font(R.font.bricolage_grotesque_bold, FontWeight.Bold),
    Font(R.font.bricolage_grotesque_extrabold, FontWeight.ExtraBold),
)

/** JetBrains Mono (OFL): raw values, tag ids, coordinates. */
public val MonoFamily: FontFamily = FontFamily(
    Font(R.font.jetbrains_mono_regular, FontWeight.Normal),
    Font(R.font.jetbrains_mono_medium, FontWeight.Medium),
    Font(R.font.jetbrains_mono_bold, FontWeight.Bold),
)

private val base = Typography()

internal val ExifLabTypography: Typography = Typography(
    displayLarge = base.displayLarge.copy(fontFamily = DisplayFamily, fontWeight = FontWeight.ExtraBold, letterSpacing = (-0.02).em),
    displayMedium = base.displayMedium.copy(fontFamily = DisplayFamily, fontWeight = FontWeight.ExtraBold, letterSpacing = (-0.02).em),
    displaySmall = base.displaySmall.copy(fontFamily = DisplayFamily, fontWeight = FontWeight.Bold, letterSpacing = (-0.01).em),
    headlineLarge = base.headlineLarge.copy(fontFamily = DisplayFamily, fontWeight = FontWeight.Bold),
    headlineMedium = base.headlineMedium.copy(fontFamily = DisplayFamily, fontWeight = FontWeight.Bold),
    headlineSmall = base.headlineSmall.copy(fontFamily = DisplayFamily, fontWeight = FontWeight.SemiBold),
    titleLarge = base.titleLarge.copy(fontFamily = DisplayFamily, fontWeight = FontWeight.SemiBold),
    titleMedium = base.titleMedium.copy(fontWeight = FontWeight.SemiBold),
    titleSmall = base.titleSmall.copy(fontWeight = FontWeight.SemiBold),
    bodyLarge = base.bodyLarge,
    bodyMedium = base.bodyMedium,
    bodySmall = base.bodySmall,
    labelLarge = base.labelLarge.copy(fontWeight = FontWeight.SemiBold),
    labelMedium = base.labelMedium.copy(fontWeight = FontWeight.SemiBold),
    labelSmall = base.labelSmall,
)

/** Monospace styles for technical values. */
@Immutable
public data class MonoTypography(
    val large: TextStyle,
    val medium: TextStyle,
    val small: TextStyle,
    /** Big numeric readouts (exposure triangle). */
    val numeral: TextStyle,
)

internal val ExifLabMonoTypography: MonoTypography = MonoTypography(
    large = TextStyle(fontFamily = MonoFamily, fontWeight = FontWeight.Medium, fontSize = 16.sp, lineHeight = 22.sp),
    medium = TextStyle(fontFamily = MonoFamily, fontWeight = FontWeight.Normal, fontSize = 14.sp, lineHeight = 20.sp),
    small = TextStyle(fontFamily = MonoFamily, fontWeight = FontWeight.Normal, fontSize = 12.sp, lineHeight = 16.sp),
    numeral = TextStyle(fontFamily = DisplayFamily, fontWeight = FontWeight.ExtraBold, fontSize = 30.sp, lineHeight = 34.sp, letterSpacing = (-0.01).em),
)

public val LocalMonoTypography: androidx.compose.runtime.ProvidableCompositionLocal<MonoTypography> =
    staticCompositionLocalOf { ExifLabMonoTypography }
