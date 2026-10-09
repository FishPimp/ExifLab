package io.github.fishpimp.exiflab.designsystem.theme

import android.app.UiModeManager
import android.content.Context
import android.os.Build
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.ColorScheme
import androidx.compose.material3.MaterialExpressiveTheme
import androidx.compose.material3.MotionScheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.remember
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import com.materialkolor.PaletteStyle
import com.materialkolor.dynamicColorScheme
import com.materialkolor.ktx.harmonize

/**
 * Color roles beyond the Material scheme. Every role comes as a container/on-container pair
 * generated from a tonal palette, so text on it always meets contrast requirements.
 */
@Immutable
data class ExtendedColors(
    /** Privacy-sensitive fields (location, serial numbers, owner names). */
    val sensitive: Color,
    val onSensitive: Color,
    val sensitiveContainer: Color,
    val onSensitiveContainer: Color,
    /** Diff preview: a value that will be added. */
    val addedContainer: Color,
    val onAddedContainer: Color,
    /** Diff preview: a value that will change. */
    val changedContainer: Color,
    val onChangedContainer: Color,
    /** Diff preview: a value that will be removed. */
    val removedContainer: Color,
    val onRemovedContainer: Color,
)

val LocalExtendedColors = staticCompositionLocalOf<ExtendedColors> {
    error("ExtendedColors not provided; wrap content in ExifLabTheme")
}

/** Whether the resolved theme is dark, independent of the system setting. */
val LocalDarkTheme = staticCompositionLocalOf { false }

private val SensitiveSeed = Color(0xFFD9480F)
private val AddedSeed = Color(0xFF2E8B57)
private val ChangedSeed = Color(0xFFE0A100)
private val RemovedSeed = Color(0xFFC62828)

@Composable
fun ExifLabTheme(
    themeMode: ThemeMode = ThemeMode.System,
    dynamicColor: Boolean = true,
    palette: BrandPalette = BrandPalette.Lagoon,
    contrast: ContrastPreference = ContrastPreference.System,
    content: @Composable () -> Unit,
) {
    val dark = when (themeMode) {
        ThemeMode.System -> isSystemInDarkTheme()
        ThemeMode.Light -> false
        ThemeMode.Dark -> true
    }
    val context = LocalContext.current
    val contrastLevel = remember(contrast, context) { resolveContrastLevel(context, contrast) }
    val useDynamic = dynamicColor && Build.VERSION.SDK_INT >= Build.VERSION_CODES.S

    val colorScheme = remember(dark, useDynamic, palette, contrast, contrastLevel, context) {
        when {
            useDynamic && contrast == ContrastPreference.System -> systemDynamicScheme(context, dark)
            useDynamic -> dynamicColorScheme(
                seedColor = systemDynamicScheme(context, dark).primary,
                isDark = dark,
                style = PaletteStyle.TonalSpot,
                contrastLevel = contrastLevel,
            )
            else -> dynamicColorScheme(
                seedColor = palette.seed,
                isDark = dark,
                style = palette.style,
                contrastLevel = contrastLevel,
            )
        }
    }
    val extendedColors = remember(colorScheme, dark, contrastLevel) {
        extendedColors(colorScheme, dark, contrastLevel)
    }

    MaterialExpressiveTheme(
        colorScheme = colorScheme,
        motionScheme = MotionScheme.expressive(),
        shapes = ExifLabShapes,
        typography = ExifLabTypography,
    ) {
        CompositionLocalProvider(
            LocalExtendedColors provides extendedColors,
            LocalExtendedTypography provides DefaultExtendedTypography,
            LocalDarkTheme provides dark,
            content = content,
        )
    }
}

private fun systemDynamicScheme(context: Context, dark: Boolean): ColorScheme =
    if (dark) dynamicDarkColorScheme(context) else dynamicLightColorScheme(context)

private fun resolveContrastLevel(context: Context, preference: ContrastPreference): Double = when (preference) {
    ContrastPreference.Standard -> 0.0
    ContrastPreference.Medium -> 0.5
    ContrastPreference.High -> 1.0
    ContrastPreference.System ->
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            val uiModeManager = context.getSystemService(UiModeManager::class.java)
            uiModeManager?.contrast?.toDouble()?.coerceIn(-1.0, 1.0) ?: 0.0
        } else {
            0.0
        }
}

private fun extendedColors(scheme: ColorScheme, dark: Boolean, contrastLevel: Double): ExtendedColors {
    fun roleScheme(seed: Color) = dynamicColorScheme(
        seedColor = seed.harmonize(scheme.primary),
        isDark = dark,
        style = PaletteStyle.Fidelity,
        contrastLevel = contrastLevel,
    )
    val sensitive = roleScheme(SensitiveSeed)
    val added = roleScheme(AddedSeed)
    val changed = roleScheme(ChangedSeed)
    val removed = roleScheme(RemovedSeed)
    return ExtendedColors(
        sensitive = sensitive.primary,
        onSensitive = sensitive.onPrimary,
        sensitiveContainer = sensitive.primaryContainer,
        onSensitiveContainer = sensitive.onPrimaryContainer,
        addedContainer = added.primaryContainer,
        onAddedContainer = added.onPrimaryContainer,
        changedContainer = changed.primaryContainer,
        onChangedContainer = changed.onPrimaryContainer,
        removedContainer = removed.primaryContainer,
        onRemovedContainer = removed.onPrimaryContainer,
    )
}

/** Convenience accessors mirroring [androidx.compose.material3.MaterialTheme]. */
object ExifLabTheme {
    val extendedColors: ExtendedColors
        @Composable get() = LocalExtendedColors.current
    val extendedTypography: ExtendedTypography
        @Composable get() = LocalExtendedTypography.current
    val isDark: Boolean
        @Composable get() = LocalDarkTheme.current
}
