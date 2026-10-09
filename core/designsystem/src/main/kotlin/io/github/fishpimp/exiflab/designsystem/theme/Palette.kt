package io.github.fishpimp.exiflab.designsystem.theme

import androidx.compose.ui.graphics.Color
import com.materialkolor.PaletteStyle

/** How the app picks light or dark colors. */
enum class ThemeMode { System, Light, Dark }

/** User contrast preference. [System] follows the Android 14+ contrast setting. */
enum class ContrastPreference { System, Standard, Medium, High }

/**
 * Curated seed palettes used when dynamic (wallpaper) color is off or unavailable.
 * Each palette is expanded into a full Material color scheme at runtime, so every
 * palette supports light, dark and all contrast levels.
 */
enum class BrandPalette(val seed: Color, val style: PaletteStyle) {
    Lagoon(Color(0xFF1E8C93), PaletteStyle.TonalSpot),
    Citrus(Color(0xFF9DBB2A), PaletteStyle.Vibrant),
    Ember(Color(0xFFE2603A), PaletteStyle.TonalSpot),
    Iris(Color(0xFF7356C8), PaletteStyle.Vibrant),
    Moss(Color(0xFF4F7A3A), PaletteStyle.TonalSpot),
    Graphite(Color(0xFF5E6870), PaletteStyle.Neutral),
}
