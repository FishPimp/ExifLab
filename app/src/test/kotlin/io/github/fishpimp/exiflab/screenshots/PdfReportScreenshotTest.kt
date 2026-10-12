package io.github.fishpimp.exiflab.screenshots

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Shader
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.test.core.app.ApplicationProvider
import com.google.common.truth.Truth.assertThat
import com.materialkolor.dynamicColorScheme
import io.github.fishpimp.exiflab.data.export.ExportSettings
import io.github.fishpimp.exiflab.data.export.pdf.PdfFonts
import io.github.fishpimp.exiflab.data.export.pdf.PdfImage
import io.github.fishpimp.exiflab.data.export.pdf.PdfMapMode
import io.github.fishpimp.exiflab.data.export.pdf.PdfPageLayout
import io.github.fishpimp.exiflab.data.export.pdf.PdfPainter
import io.github.fishpimp.exiflab.data.export.pdf.PdfPalette
import io.github.fishpimp.exiflab.data.export.pdf.PdfReportLayout
import io.github.fishpimp.exiflab.data.export.pdf.PdfSectionBuilder
import io.github.fishpimp.exiflab.designsystem.theme.BrandPalette
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.File
import java.time.LocalDate

/**
 * Draws report pages with the exact code the PDF writer uses, onto bitmaps, so the layout can be
 * looked at: build/outputs/roborazzi/pdf_*.png.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class PdfReportScreenshotTest {
    private val context: Context = ApplicationProvider.getApplicationContext()

    @Test fun single_photo_with_map_panel() {
        val pages = render("pdf_single_phone", ExportSettings(), PdfMapMode.Offline) { builder -> builder.section(FakeReports.PHONE_NAME, FakeReports.phoneJpeg, PdfMapMode.Offline) }
        assertThat(pages).isAtLeast(2)
    }

    @Test fun single_photo_with_map() {
        render("pdf_single_phone_map", ExportSettings(), PdfMapMode.Map, maxPages = 1) { builder ->
            builder.section(FakeReports.PHONE_NAME, FakeReports.phoneJpeg, PdfMapMode.Map)
        }
    }

    @Test fun raw_redacted_without_raw_values() {
        val settings = ExportSettings(redactSensitive = true, includeRawValues = false)
        render("pdf_raw_redacted", settings, PdfMapMode.Off) { builder -> builder.section(FakeReports.RAW_NAME, FakeReports.cameraRaw, PdfMapMode.Off) }
    }

    @Test @Config(qualifiers = "sv")
    fun swedish_bare_png() {
        render("pdf_png_sv", ExportSettings(), PdfMapMode.Offline) { builder -> builder.section(FakeReports.PNG_NAME, FakeReports.barePng, PdfMapMode.Offline) }
    }

    @Test fun long_tables_repeat_their_header() {
        val pages = render("pdf_large", ExportSettings(), PdfMapMode.Off, maxPages = 3) { builder ->
            builder.section("large.jpg", FakeReports.large(directoryCount = 3, tagsPerDirectory = 60), PdfMapMode.Off)
        }
        assertThat(pages).isAtLeast(3)
    }

    @Test fun summary_page() {
        val settings = ExportSettings(format = io.github.fishpimp.exiflab.data.export.ExportFormat.Pdf)
        val painter = painter()
        val layout = PdfReportLayout(painter)
        val builder = PdfSectionBuilder(context, settings)
        val entries = listOf(
            builder.summaryEntry(FakeReports.PHONE_NAME, FakeReports.phoneJpeg, 3),
            builder.summaryEntry(FakeReports.RAW_NAME, FakeReports.cameraRaw, 4),
            builder.summaryEntry(FakeReports.PNG_NAME, FakeReports.barePng, 1),
        )
        val summary = builder.summary(entries, listOf(2, 5, 9), notIncluded = listOf("IMG_0042.HEIC"), exportedOn = TODAY)
        val pages = layout.layoutSummary(summary)
        save("pdf_summary", pages.first(), painter, layout, builder, 1, 9)
    }

    private fun palette(): PdfPalette {
        val brand = BrandPalette.Lagoon
        val light = dynamicColorScheme(seedColor = brand.seed, isDark = false, style = brand.style)
        return PdfPalette.from(light, isDark = false, sensitiveColor = Color(0xFFD9480F))
    }

    private fun painter() = PdfPainter(PdfFonts.load(context), palette())

    private fun render(
        name: String,
        settings: ExportSettings,
        @Suppress("UNUSED_PARAMETER") mapMode: PdfMapMode,
        maxPages: Int = 2,
        section: (PdfSectionBuilder) -> io.github.fishpimp.exiflab.data.export.pdf.PdfPhotoSection,
    ): Int {
        val painter = painter()
        val layout = PdfReportLayout(painter)
        val builder = PdfSectionBuilder(context, settings)
        val pages = layout.layoutPhoto(section(builder))
        pages.take(maxPages).forEachIndexed { index, page -> save("${name}_p${index + 1}", page, painter, layout, builder, index + 1, pages.size) }
        return pages.size
    }

    private fun save(name: String, page: PdfPageLayout, painter: PdfPainter, layout: PdfReportLayout, builder: PdfSectionBuilder, number: Int, total: Int) {
        val spec = layout.page
        val bitmap = Bitmap.createBitmap((spec.width * SCALE).toInt(), (spec.height * SCALE).toInt(), Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)
        canvas.scale(SCALE, SCALE)
        painter.drawPaper(canvas, spec.width, spec.height)
        val footer = layout.footer(builder.footerCredit(TODAY), builder.pageLabel(number, total))
        painter.draw(canvas, page.ops + footer) { image -> if (image == PdfImage.Thumbnail) fakePhoto() else fakeMap() }
        val file = File("build/outputs/roborazzi/$name.png")
        file.parentFile?.mkdirs()
        file.outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
    }

    /** A sunset-like gradient standing in for the photo. */
    private fun fakePhoto(): Bitmap {
        val bitmap = Bitmap.createBitmap(400, 300, Bitmap.Config.ARGB_8888)
        val paint = Paint().apply {
            shader = LinearGradient(0f, 0f, 0f, 300f, Color(0xFF3A6EA5).toArgb(), Color(0xFFF2A65A).toArgb(), Shader.TileMode.CLAMP)
        }
        Canvas(bitmap).drawRect(0f, 0f, 400f, 300f, paint)
        return bitmap
    }

    /** Flat land with a river, standing in for a map snapshot. */
    private fun fakeMap(): Bitmap {
        val bitmap = Bitmap.createBitmap(1030, 380, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)
        canvas.drawColor(Color(0xFFEFF1EC).toArgb())
        val water = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color(0xFFB9D3E6).toArgb()
            strokeWidth = 40f
            style = Paint.Style.STROKE
        }
        canvas.drawLine(0f, 300f, 1030f, 120f, water)
        return bitmap
    }

    private companion object {
        const val SCALE = 2f
        val TODAY: LocalDate = LocalDate.of(2026, 10, 12)
    }
}
