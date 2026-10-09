package io.github.fishpimp.exiflab.ui.map

import android.graphics.Bitmap
import android.util.DisplayMetrics
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Canvas
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.graphics.drawscope.CanvasDrawScope
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.translate
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import kotlin.math.asin
import kotlin.math.roundToInt

/** The location pin. Its tip marks the point; a ring on the ground surrounds it. */
internal object MapPin {
    val Width = 30.dp
    val Height = 40.dp
    val RingRadius = 9.dp
    val RingStroke = 2.dp
    val Outline = 2.dp

    /** Fill alpha of the ring around the point. */
    const val RING_FILL_ALPHA = 0.22f
}

/**
 * Draws the ring and the pin for a location at [point]: the ring centered on it, the pin's tip
 * touching it. Shared by the drawn fallback and the MapLibre marker bitmap, so both look the same.
 */
internal fun DrawScope.drawLocationMark(point: Offset, palette: MapPalette) {
    drawLocationRing(point, palette)
    val pinSize = Size(MapPin.Width.toPx(), MapPin.Height.toPx())
    translate(left = point.x - pinSize.width / 2f, top = point.y - pinSize.height) {
        drawPin(pinSize, palette)
    }
}

internal fun DrawScope.drawLocationRing(point: Offset, palette: MapPalette) {
    val radius = MapPin.RingRadius.toPx()
    drawCircle(palette.pin.copy(alpha = MapPin.RING_FILL_ALPHA), radius, point)
    drawCircle(palette.pin, radius, point, style = Stroke(MapPin.RingStroke.toPx()))
}

/** Draws a pin filling [size], tip at the bottom center. */
internal fun DrawScope.drawPin(size: Size, palette: MapPalette) {
    val outline = MapPin.Outline.toPx()
    val path = pinPath(size, inset = outline)
    // A stroke twice the inset puts its outer edge exactly on the pin's bounds.
    drawPath(path, palette.pinOutline, style = Stroke(width = outline * 2f, join = StrokeJoin.Round))
    drawPath(path, palette.pin)
    val headRadius = (size.width - 2f * outline) / 2f
    drawCircle(palette.pinCenter, radius = headRadius * PIN_HOLE_RATIO, center = Offset(size.width / 2f, outline + headRadius))
}

/**
 * A teardrop: a circular head with straight sides meeting at the tip. The sides are tangent to
 * the head, so the outline is smooth.
 */
internal fun pinPath(size: Size, inset: Float): Path {
    val radius = (size.width - 2f * inset) / 2f
    val centerX = size.width / 2f
    val centerY = inset + radius
    val tipY = size.height - inset
    // Half the angle at the tip, which is also where the sides meet the head (0 degrees = east,
    // angles grow clockwise because y points down).
    val tangentDegrees = Math.toDegrees(asin(radius / (tipY - centerY)).toDouble()).toFloat()
    val head = Rect(centerX - radius, centerY - radius, centerX + radius, centerY + radius)
    return Path().apply {
        moveTo(centerX, tipY)
        arcTo(head, startAngleDegrees = tangentDegrees, sweepAngleDegrees = -(180f + 2f * tangentDegrees), forceMoveTo = false)
        close()
    }
}

/**
 * The pin as a bitmap for MapLibre's symbol layer. The bitmap's density tells MapLibre its
 * scale, so it shows at the same size as in Compose.
 */
internal fun pinBitmap(density: Density, palette: MapPalette): Bitmap {
    val width = with(density) { MapPin.Width.roundToPx() }
    val height = with(density) { MapPin.Height.roundToPx() }
    val image = ImageBitmap(width, height)
    CanvasDrawScope().draw(density, LayoutDirection.Ltr, Canvas(image), Size(width.toFloat(), height.toFloat())) {
        drawPin(this.size, palette)
    }
    return image.asAndroidBitmap().apply {
        this.density = (density.density * DisplayMetrics.DENSITY_DEFAULT).roundToInt()
    }
}

private const val PIN_HOLE_RATIO = 0.4f
