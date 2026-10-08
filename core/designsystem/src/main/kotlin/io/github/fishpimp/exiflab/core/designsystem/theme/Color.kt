package io.github.fishpimp.exiflab.core.designsystem.theme

import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.ui.graphics.Color

// Generated from the ExifLab seeds (deep teal 0E6E64, citrus lime C9E265, coral E4573D) with
// material-color-utilities (tonal palettes, 2021 spec). Do not edit by hand; regenerate instead.

internal val LightScheme = lightColorScheme(
    primary = Color(0xFF006A60),
    onPrimary = Color(0xFFFFFFFF),
    primaryContainer = Color(0xFF74F8E6),
    onPrimaryContainer = Color(0xFF005048),
    secondary = Color(0xFF466465),
    onSecondary = Color(0xFFFFFFFF),
    secondaryContainer = Color(0xFFC8E9EA),
    onSecondaryContainer = Color(0xFF2E4C4D),
    tertiary = Color(0xFF546500),
    onTertiary = Color(0xFFFFFFFF),
    tertiaryContainer = Color(0xFFD4EE69),
    onTertiaryContainer = Color(0xFF3F4C00),
    error = Color(0xFFAF301A),
    onError = Color(0xFFFFFFFF),
    errorContainer = Color(0xFFFFDAD3),
    onErrorContainer = Color(0xFF8D1704),
    background = Color(0xFFF6FAF8),
    onBackground = Color(0xFF181D1B),
    surface = Color(0xFFF6FAF8),
    onSurface = Color(0xFF181D1B),
    surfaceVariant = Color(0xFFD9E5E2),
    onSurfaceVariant = Color(0xFF3E4947),
    outline = Color(0xFF6E7A77),
    outlineVariant = Color(0xFFBDC9C6),
    inverseSurface = Color(0xFF2C3130),
    inverseOnSurface = Color(0xFFEDF2EF),
    inversePrimary = Color(0xFF53DBCA),
    surfaceDim = Color(0xFFD6DBD9),
    surfaceBright = Color(0xFFF6FAF8),
    surfaceContainerLowest = Color(0xFFFFFFFF),
    surfaceContainerLow = Color(0xFFF0F5F2),
    surfaceContainer = Color(0xFFEAEFED),
    surfaceContainerHigh = Color(0xFFE5E9E7),
    surfaceContainerHighest = Color(0xFFDFE3E1),
    scrim = Color(0xFF000000),
)

internal val DarkScheme = darkColorScheme(
    primary = Color(0xFF53DBCA),
    onPrimary = Color(0xFF003732),
    primaryContainer = Color(0xFF005048),
    onPrimaryContainer = Color(0xFF74F8E6),
    secondary = Color(0xFFADCCCE),
    onSecondary = Color(0xFF173536),
    secondaryContainer = Color(0xFF2E4C4D),
    onSecondaryContainer = Color(0xFFC8E9EA),
    tertiary = Color(0xFFB8D250),
    onTertiary = Color(0xFF2B3400),
    tertiaryContainer = Color(0xFF3F4C00),
    onTertiaryContainer = Color(0xFFD4EE69),
    error = Color(0xFFFFB4A5),
    onError = Color(0xFF650A00),
    errorContainer = Color(0xFF8D1704),
    onErrorContainer = Color(0xFFFFDAD3),
    background = Color(0xFF0F1413),
    onBackground = Color(0xFFDFE3E1),
    surface = Color(0xFF0F1413),
    onSurface = Color(0xFFDFE3E1),
    surfaceVariant = Color(0xFF3E4947),
    onSurfaceVariant = Color(0xFFBDC9C6),
    outline = Color(0xFF879390),
    outlineVariant = Color(0xFF3E4947),
    inverseSurface = Color(0xFFDFE3E1),
    inverseOnSurface = Color(0xFF2C3130),
    inversePrimary = Color(0xFF006A60),
    surfaceDim = Color(0xFF0F1413),
    surfaceBright = Color(0xFF353A39),
    surfaceContainerLowest = Color(0xFF0A0F0E),
    surfaceContainerLow = Color(0xFF181D1B),
    surfaceContainer = Color(0xFF1C211F),
    surfaceContainerHigh = Color(0xFF262B2A),
    surfaceContainerHighest = Color(0xFF313635),
    scrim = Color(0xFF000000),
)

// Accent palettes used for directory categories and highlights.
internal object Accent {
    val Teal = Tones(Color(0xFF003732), Color(0xFF005048), Color(0xFF006A60), Color(0xFF53DBCA), Color(0xFF74F8E6), Color(0xFFB4FFF2))
    val Lime = Tones(Color(0xFF2B3400), Color(0xFF3F4C00), Color(0xFF546500), Color(0xFFB8D250), Color(0xFFD4EE69), Color(0xFFE2FD75))
    val Amber = Tones(Color(0xFF472A00), Color(0xFF653E00), Color(0xFF855303), Color(0xFFFDBA67), Color(0xFFFFDDB8), Color(0xFFFFEEDE))
    val Sky = Tones(Color(0xFF00344D), Color(0xFF004C6D), Color(0xFF0C658E), Color(0xFF8ACEFD), Color(0xFFC8E6FF), Color(0xFFE5F2FF))
    val Rose = Tones(Color(0xFF591927), Color(0xFF76303D), Color(0xFF934753), Color(0xFFFFB2BC), Color(0xFFFFD9DD), Color(0xFFFFECED))
    val Moss = Tones(Color(0xFF0F3903), Color(0xFF275018), Color(0xFF3E692E), Color(0xFFA3D48D), Color(0xFFBEF0A7), Color(0xFFCCFFB4))
    val Sand = Tones(Color(0xFF3B2F15), Color(0xFF53452A), Color(0xFF6C5C3F), Color(0xFFD8C4A0), Color(0xFFF5E0BB), Color(0xFFFFEFD5))
    val Slate = Tones(Color(0xFF1D343B), Color(0xFF344A51), Color(0xFF4B626A), Color(0xFFB2CAD3), Color(0xFFCEE7F0), Color(0xFFDCF5FE))
    val Iris = Tones(Color(0xFF282968), Color(0xFF3F4080), Color(0xFF565899), Color(0xFFC1C1FF), Color(0xFFE1DFFF), Color(0xFFF2EFFF))
}

/** Tones 20, 30, 40, 80, 90, 95 of one hue. */
internal data class Tones(val t20: Color, val t30: Color, val t40: Color, val t80: Color, val t90: Color, val t95: Color)
