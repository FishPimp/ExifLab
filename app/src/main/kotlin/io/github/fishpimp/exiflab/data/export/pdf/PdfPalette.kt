package io.github.fishpimp.exiflab.data.export.pdf

import androidx.compose.material3.ColorScheme
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import com.materialkolor.PaletteStyle
import com.materialkolor.dynamicColorScheme

/**
 * The report's colors: always a light scheme, since it is printed on paper, derived from the
 * app's current colors so the report matches the app.
 *
 * @property scheme the light scheme the colors come from; the map snapshot is tinted from it too.
 */
class PdfPalette(val scheme: ColorScheme, private val sensitive: ColorScheme) {
    private val colors: Map<PdfColor, Int> = mapOf(
        PdfColor.Paper to scheme.surfaceContainerLowest,
        PdfColor.Text to scheme.onSurface,
        PdfColor.Muted to scheme.onSurfaceVariant,
        PdfColor.Accent to scheme.primary,
        PdfColor.AccentContainer to scheme.primaryContainer,
        PdfColor.OnAccentContainer to scheme.onPrimaryContainer,
        PdfColor.Panel to scheme.surfaceContainerLow,
        PdfColor.PanelStrong to scheme.surfaceContainerHigh,
        PdfColor.Line to scheme.outlineVariant,
        PdfColor.Sensitive to sensitive.primary,
        PdfColor.SensitiveContainer to sensitive.primaryContainer,
        PdfColor.OnSensitiveContainer to sensitive.onPrimaryContainer,
    ).mapValues { (_, color) -> color.toArgb() }

    fun argb(color: PdfColor): Int = colors.getValue(color)

    companion object {
        /**
         * A light palette from the app's colors: [current] itself when it is light, otherwise a light
         * scheme grown from its primary color. [sensitiveColor] is the theme's privacy color.
         */
        fun from(current: ColorScheme, isDark: Boolean, sensitiveColor: Color): PdfPalette {
            val light = if (isDark) dynamicColorScheme(seedColor = current.primary, isDark = false, style = PaletteStyle.TonalSpot) else current
            val sensitive = dynamicColorScheme(seedColor = sensitiveColor, isDark = false, style = PaletteStyle.Fidelity)
            return PdfPalette(light, sensitive)
        }
    }
}
