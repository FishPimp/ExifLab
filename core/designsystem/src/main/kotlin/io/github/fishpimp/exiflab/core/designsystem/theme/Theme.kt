package io.github.fishpimp.exiflab.core.designsystem.theme

import android.os.Build
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.ColorScheme
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.MaterialExpressiveTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.MotionScheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.ui.platform.LocalContext

/** User-selectable theme mode. */
public enum class ThemeMode { SYSTEM, LIGHT, DARK }

/**
 * ExifLab theme: Material 3 Expressive with the ExifLab palette, or wallpaper colours when [dynamicColor] is on
 * (Android 12+). Typography uses Bricolage Grotesque for display styles and JetBrains Mono for raw values.
 */
@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
public fun ExifLabTheme(
    themeMode: ThemeMode = ThemeMode.SYSTEM,
    dynamicColor: Boolean = false,
    content: @Composable () -> Unit,
) {
    val dark = when (themeMode) {
        ThemeMode.SYSTEM -> isSystemInDarkTheme()
        ThemeMode.LIGHT -> false
        ThemeMode.DARK -> true
    }
    val context = LocalContext.current
    val colors: ColorScheme = when {
        dynamicColor && Build.VERSION.SDK_INT >= Build.VERSION_CODES.S ->
            if (dark) dynamicDarkColorScheme(context) else dynamicLightColorScheme(context)
        dark -> DarkScheme
        else -> LightScheme
    }
    CompositionLocalProvider(
        LocalExtendedColors provides if (dark) darkExtendedColors() else lightExtendedColors(),
        LocalMonoTypography provides ExifLabMonoTypography,
        LocalIsDarkTheme provides dark,
    ) {
        MaterialExpressiveTheme(
            colorScheme = colors,
            motionScheme = MotionScheme.expressive(),
            shapes = ExifLabShapes,
            typography = ExifLabTypography,
            content = content,
        )
    }
}

public val LocalIsDarkTheme: androidx.compose.runtime.ProvidableCompositionLocal<Boolean> =
    androidx.compose.runtime.staticCompositionLocalOf { false }

/** Accessors for ExifLab-specific tokens, mirroring [MaterialTheme]. */
public object ExifLabTheme {
    public val extendedColors: ExtendedColors
        @Composable @ReadOnlyComposable get() = LocalExtendedColors.current

    public val mono: MonoTypography
        @Composable @ReadOnlyComposable get() = LocalMonoTypography.current

    public val isDark: Boolean
        @Composable @ReadOnlyComposable get() = LocalIsDarkTheme.current
}
