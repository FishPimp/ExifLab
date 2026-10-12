package io.github.fishpimp.exiflab.data.export.pdf

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Path
import android.graphics.Rect
import android.graphics.RectF
import android.graphics.Typeface
import androidx.core.content.res.ResourcesCompat
import io.github.fishpimp.exiflab.designsystem.R as DesignR

/**
 * The report's typefaces, loaded from the app's resources: Bricolage Grotesque for headings, the
 * system face for body text and JetBrains Mono for raw values. Falls back to system faces when a
 * font cannot be loaded.
 */
class PdfFonts(
    val display: Typeface,
    val body: Typeface,
    val bodyStrong: Typeface,
    val mono: Typeface,
) {
    companion object {
        fun load(context: Context): PdfFonts {
            fun font(id: Int, fallback: Typeface) = runCatching { ResourcesCompat.getFont(context, id) }.getOrNull() ?: fallback
            return PdfFonts(
                display = font(DesignR.font.bricolage_grotesque, Typeface.DEFAULT_BOLD),
                body = Typeface.DEFAULT,
                bodyStrong = Typeface.create(Typeface.DEFAULT, SEMI_BOLD, false),
                mono = font(DesignR.font.jetbrains_mono, Typeface.MONOSPACE),
            )
        }

        private const val SEMI_BOLD = 600
    }
}

/**
 * Draws laid-out pages onto a canvas: a PdfDocument page, or a bitmap in tests. Also the measurer
 * the layout uses, so text is measured with exactly the paint it is drawn with.
 */
class PdfPainter(private val fonts: PdfFonts, private val palette: PdfPalette) : PdfTextMeasurer {
    private val paints = HashMap<PdfTextStyle, Paint>()
    private val fill = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }
    private val stroke = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE }
    private val imagePaint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)

    override fun width(text: String, style: PdfTextStyle): Float = paint(style).measureText(text)

    /** Draws [ops] on [canvas], which spans the whole page in points; [images] supplies bitmaps. */
    fun draw(canvas: Canvas, ops: List<PdfOp>, images: (PdfImage) -> Bitmap?) {
        for (op in ops) {
            when (op) {
                is PdfOp.Text -> canvas.drawText(op.text, op.x, op.baseline, paint(op.style))
                is PdfOp.Rect -> {
                    fill.color = palette.argb(op.color)
                    if (op.radius > 0f) {
                        canvas.drawRoundRect(op.left, op.top, op.right, op.bottom, op.radius, op.radius, fill)
                    } else {
                        canvas.drawRect(op.left, op.top, op.right, op.bottom, fill)
                    }
                }
                is PdfOp.Circle -> {
                    fill.color = palette.argb(op.color)
                    canvas.drawCircle(op.centerX, op.centerY, op.radius, fill)
                }
                is PdfOp.Line -> {
                    stroke.color = palette.argb(op.color)
                    stroke.strokeWidth = op.width
                    canvas.drawLine(op.x1, op.y1, op.x2, op.y2, stroke)
                }
                is PdfOp.Image -> drawImage(canvas, op, images(op.image))
            }
        }
    }

    /** Fills the page with the paper color; PdfDocument pages start transparent. */
    fun drawPaper(canvas: Canvas, width: Float, height: Float) {
        fill.color = palette.argb(PdfColor.Paper)
        canvas.drawRect(0f, 0f, width, height, fill)
    }

    private fun drawImage(canvas: Canvas, op: PdfOp.Image, bitmap: Bitmap?) {
        val box = RectF(op.left, op.top, op.right, op.bottom)
        val clip = Path().apply { addRoundRect(box, op.radius, op.radius, Path.Direction.CW) }
        canvas.save()
        canvas.clipPath(clip)
        fill.color = palette.argb(op.placeholder)
        canvas.drawRect(box, fill)
        if (bitmap != null && bitmap.width > 0 && bitmap.height > 0) {
            // Fit inside the box, keeping the aspect ratio, centered.
            val scale = minOf(box.width() / bitmap.width, box.height() / bitmap.height)
            val width = bitmap.width * scale
            val height = bitmap.height * scale
            val left = box.left + (box.width() - width) / 2f
            val top = box.top + (box.height() - height) / 2f
            canvas.drawBitmap(bitmap, Rect(0, 0, bitmap.width, bitmap.height), RectF(left, top, left + width, top + height), imagePaint)
        }
        canvas.restore()
    }

    private fun paint(style: PdfTextStyle): Paint = paints.getOrPut(style) {
        Paint(Paint.ANTI_ALIAS_FLAG or Paint.SUBPIXEL_TEXT_FLAG).apply {
            textSize = style.size
            color = palette.argb(style.color)
            typeface = when (style.font) {
                PdfFont.Display -> fonts.display
                PdfFont.Body -> fonts.body
                PdfFont.BodyStrong -> fonts.bodyStrong
                PdfFont.Mono, PdfFont.MonoStrong -> fonts.mono
            }
            // Variable fonts: pick the instance. Ignored by faces without these axes.
            val variation = when (style.font) {
                PdfFont.Display -> if (style.size >= 18f) "'wght' 720, 'wdth' 90, 'opsz' 48" else "'wght' 640, 'wdth' 95, 'opsz' 24"
                PdfFont.Mono -> "'wght' 400"
                PdfFont.MonoStrong -> "'wght' 700"
                else -> null
            }
            if (variation != null) runCatching { fontVariationSettings = variation }
        }
    }
}
