package io.github.fishpimp.exiflab.data.export.pdf

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.test.core.app.ApplicationProvider
import com.google.common.truth.Truth.assertThat
import com.materialkolor.dynamicColorScheme
import io.github.fishpimp.exiflab.designsystem.theme.BrandPalette
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.GraphicsMode

/** The report's map gets the app's pin, in the primary color, with its tip and ring on the position. */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class PdfMapPinTest {
    private val context: Context = ApplicationProvider.getApplicationContext()
    private val scheme = BrandPalette.Lagoon.let { dynamicColorScheme(seedColor = it.seed, isDark = false, style = it.style) }
    private val painter = PdfPainter(PdfFonts.load(context), PdfPalette.from(scheme, isDark = false, sensitiveColor = Color(0xFFD9480F)))
    private val land = Color(0xFFEFF1EC).toArgb()

    private fun draw(mark: Boolean, map: Bitmap?): Bitmap {
        val page = Bitmap.createBitmap(400, 200, Bitmap.Config.ARGB_8888)
        painter.draw(Canvas(page), listOf(PdfOp.Image(PdfImage.Map, 0f, 0f, 400f, 200f, radius = 0f, locationMark = mark))) { map }
        return page
    }

    private fun map() = Bitmap.createBitmap(800, 400, Bitmap.Config.ARGB_8888).apply { eraseColor(land) }

    @Test
    fun `the pin stands on the map's center, which is the photo's position`() {
        val page = draw(mark = true, map = map())
        val primary = scheme.primary.toArgb()
        // The pin's head sits above the point (its tip), beside the onPrimary hole.
        assertThat(page.getPixel(200 - 9, 100 - 25)).isEqualTo(primary)
        // The ring around the point is primary at low alpha over the land, so neither land nor solid.
        val ring = page.getPixel(200 + 6, 100 + 3)
        assertThat(ring).isNotEqualTo(land)
        assertThat(ring).isNotEqualTo(primary)
        // Away from the mark the map is untouched.
        assertThat(page.getPixel(40, 40)).isEqualTo(land)
    }

    @Test
    fun `no pin without the mark or without a map`() {
        assertThat(draw(mark = false, map = map()).getPixel(200 - 9, 100 - 25)).isEqualTo(land)
        val placeholder = draw(mark = true, map = null)
        assertThat(placeholder.getPixel(200 - 9, 100 - 25)).isEqualTo(placeholder.getPixel(40, 40))
    }
}
