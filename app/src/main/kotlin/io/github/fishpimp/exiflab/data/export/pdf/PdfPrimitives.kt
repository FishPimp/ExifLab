package io.github.fishpimp.exiflab.data.export.pdf

/**
 * Page geometry in PDF points (1/72 inch), which is also the unit of the canvas PdfDocument hands
 * out. A4 by default.
 */
data class PdfPageSpec(
    val width: Float = A4_WIDTH,
    val height: Float = A4_HEIGHT,
    val margin: Float = 40f,
    /** Room at the bottom for the footer line. */
    val footerHeight: Float = 30f,
) {
    val contentLeft: Float get() = margin
    val contentRight: Float get() = width - margin
    val contentWidth: Float get() = width - 2 * margin
    val contentTop: Float get() = margin
    val contentBottom: Float get() = height - margin - footerHeight

    companion object {
        const val A4_WIDTH = 595f
        const val A4_HEIGHT = 842f
    }
}

/** The report's typefaces: Bricolage for headings, the system face for body text, JetBrains Mono for raw values. */
enum class PdfFont { Display, Body, BodyStrong, Mono, MonoStrong }

/** Color roles; the painter maps them to the app's (light) color scheme. */
enum class PdfColor {
    Paper,
    Text,
    Muted,
    Accent,
    AccentContainer,
    OnAccentContainer,
    Panel,
    PanelStrong,
    Line,
    Sensitive,
    SensitiveContainer,
    OnSensitiveContainer,
}

data class PdfTextStyle(
    val font: PdfFont,
    val size: Float,
    val color: PdfColor,
    val lineHeight: Float = size * 1.32f,
) {
    /** Distance from the top of a line box to its baseline. */
    val baselineOffset: Float get() = (lineHeight - size) / 2f + size * 0.8f
}

/** The report's type scale, in points. */
object PdfStyles {
    val Overline = PdfTextStyle(PdfFont.BodyStrong, 7.5f, PdfColor.Accent)
    val Title = PdfTextStyle(PdfFont.Display, 22f, PdfColor.Text, lineHeight = 26f)
    val Subtitle = PdfTextStyle(PdfFont.Body, 10f, PdfColor.Muted)
    val Heading = PdfTextStyle(PdfFont.Display, 14f, PdfColor.Accent, lineHeight = 18f)
    val Subheading = PdfTextStyle(PdfFont.BodyStrong, 10.5f, PdfColor.Text)
    val Camera = PdfTextStyle(PdfFont.Display, 16f, PdfColor.Text, lineHeight = 20f)
    val Body = PdfTextStyle(PdfFont.Body, 9.5f, PdfColor.Text)
    val BodyMuted = PdfTextStyle(PdfFont.Body, 9.5f, PdfColor.Muted)
    val BodyStrong = PdfTextStyle(PdfFont.BodyStrong, 9.5f, PdfColor.Text)
    val Label = PdfTextStyle(PdfFont.BodyStrong, 7.5f, PdfColor.Muted)
    val Value = PdfTextStyle(PdfFont.Body, 10f, PdfColor.Text)
    val ValueMono = PdfTextStyle(PdfFont.Mono, 9.5f, PdfColor.Text)
    val Numeral = PdfTextStyle(PdfFont.MonoStrong, 12f, PdfColor.OnAccentContainer, lineHeight = 15f)
    val NumeralLabel = PdfTextStyle(PdfFont.Body, 7.5f, PdfColor.OnAccentContainer)
    val Coordinates = PdfTextStyle(PdfFont.MonoStrong, 14f, PdfColor.Text, lineHeight = 18f)
    val TableHeader = PdfTextStyle(PdfFont.BodyStrong, 7.5f, PdfColor.Muted)
    val Cell = PdfTextStyle(PdfFont.Body, 8f, PdfColor.Text, lineHeight = 10.4f)
    val CellMono = PdfTextStyle(PdfFont.Mono, 7.5f, PdfColor.Text, lineHeight = 10.4f)
    val CellMuted = PdfTextStyle(PdfFont.Mono, 7.5f, PdfColor.Muted, lineHeight = 10.4f)
    val PrivacyLabel = PdfTextStyle(PdfFont.BodyStrong, 9.5f, PdfColor.OnSensitiveContainer)
    val PrivacyDetail = PdfTextStyle(PdfFont.Body, 8.5f, PdfColor.OnSensitiveContainer)
    val Small = PdfTextStyle(PdfFont.Body, 7f, PdfColor.Muted)
    val Footer = PdfTextStyle(PdfFont.Body, 7.5f, PdfColor.Muted)
}

/** Measures text exactly as the painter will draw it. */
fun interface PdfTextMeasurer {
    fun width(text: String, style: PdfTextStyle): Float
}

/** The images a page can show; the painter is handed the bitmaps for the section it draws. */
enum class PdfImage { Thumbnail, Map }

/** One drawing instruction, in page points with the origin at the top left. */
sealed interface PdfOp {
    /** [text] on one line, starting at [x], its baseline at [baseline]. */
    data class Text(val text: String, val x: Float, val baseline: Float, val style: PdfTextStyle) : PdfOp

    data class Rect(
        val left: Float,
        val top: Float,
        val right: Float,
        val bottom: Float,
        val color: PdfColor,
        val radius: Float = 0f,
    ) : PdfOp

    data class Circle(val centerX: Float, val centerY: Float, val radius: Float, val color: PdfColor) : PdfOp

    data class Line(val x1: Float, val y1: Float, val x2: Float, val y2: Float, val color: PdfColor, val width: Float = 0.5f) : PdfOp

    /**
     * [image] fitted inside the box (aspect kept, centered). When the image is missing the painter
     * draws [placeholder] there instead.
     */
    data class Image(
        val image: PdfImage,
        val left: Float,
        val top: Float,
        val right: Float,
        val bottom: Float,
        val radius: Float,
        val placeholder: PdfColor = PdfColor.PanelStrong,
    ) : PdfOp
}

/** Everything drawn on one page, apart from the footer, which needs the final page count. */
class PdfPageLayout(val ops: List<PdfOp>) {
    /** All text on the page in drawing order, for tests and accessibility checks. */
    val texts: List<String> get() = ops.filterIsInstance<PdfOp.Text>().map { it.text }
}
