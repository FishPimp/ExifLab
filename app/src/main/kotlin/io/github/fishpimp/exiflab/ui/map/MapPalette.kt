package io.github.fishpimp.exiflab.ui.map

import androidx.compose.material3.ColorScheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.remember
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.lerp
import io.github.fishpimp.exiflab.designsystem.theme.ExifLabTheme

/**
 * Map colors derived from the app's color scheme, so the map reads as part of the app: the
 * style's land, water and parks are tinted towards the theme, and the pin uses its primary color.
 */
@Immutable
internal data class MapPalette(
    val dark: Boolean,
    /** Land and the loading color. */
    val land: Color,
    val water: Color,
    val park: Color,
    val pin: Color,
    /** Edge around the pin, separating it from whatever map colors lie beneath. */
    val pinOutline: Color,
    val pinCenter: Color,
    /** Fallback drawing: the surface, its grid and its contour lines. */
    val sketchSurface: Color,
    val sketchGrid: Color,
    val sketchContour: Color,
    val sketchHill: Color,
)

@Composable
internal fun rememberMapPalette(): MapPalette {
    val scheme = MaterialTheme.colorScheme
    val dark = ExifLabTheme.isDark
    return remember(scheme, dark) { mapPalette(scheme, dark) }
}

internal fun mapPalette(scheme: ColorScheme, dark: Boolean): MapPalette = MapPalette(
    dark = dark,
    // OpenFreeMap's Positron and Dark styles are near-neutral; keep their lightness and borrow
    // the scheme's hue so tiles, labels and halos stay legible.
    land = if (dark) scheme.surfaceContainerLowest else scheme.surfaceContainerLow,
    water = lerp(
        if (dark) scheme.surfaceContainer else scheme.surfaceContainerHighest,
        scheme.primary,
        if (dark) 0.16f else 0.24f,
    ),
    park = lerp(
        if (dark) scheme.surfaceContainerLow else scheme.surfaceContainer,
        scheme.tertiary,
        if (dark) 0.10f else 0.12f,
    ),
    pin = scheme.primary,
    pinOutline = scheme.surface,
    pinCenter = scheme.onPrimary,
    sketchSurface = scheme.surfaceContainerHigh,
    sketchGrid = scheme.outlineVariant.copy(alpha = if (dark) 0.45f else 0.6f),
    sketchContour = lerp(scheme.outlineVariant, scheme.primary, 0.25f).copy(alpha = if (dark) 0.55f else 0.7f),
    // Two overlapping fills shade the hilltop; a light touch of primary keeps it from reading as water.
    sketchHill = scheme.primary.copy(alpha = if (dark) 0.10f else 0.08f),
)
