package io.github.fishpimp.exiflab.core.designsystem.component

import android.graphics.Matrix
import android.graphics.RectF
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Outline
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.asComposePath
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.LayoutDirection
import androidx.graphics.shapes.CornerRounding
import androidx.graphics.shapes.RoundedPolygon
import androidx.graphics.shapes.star
import androidx.graphics.shapes.toPath

/** A [Shape] that draws a [RoundedPolygon] scaled to the layout bounds. */
public class PolygonShape(private val polygon: RoundedPolygon) : Shape {
    override fun createOutline(size: Size, layoutDirection: LayoutDirection, density: Density): Outline {
        val bounds = polygon.calculateBounds()
        val path = polygon.toPath()
        val matrix = Matrix()
        matrix.setRectToRect(
            RectF(bounds[0], bounds[1], bounds[2], bounds[3]),
            RectF(0f, 0f, size.width, size.height),
            Matrix.ScaleToFit.FILL,
        )
        path.transform(matrix)
        return Outline.Generic(path.asComposePath())
    }
}

/** Playful expressive shapes used behind icons, thumbnails and empty states. */
public object ExpressiveShapes {
    /** Soft 9-sided "cookie". */
    public val Cookie: Shape = PolygonShape(
        RoundedPolygon.star(numVerticesPerRadius = 9, innerRadius = 0.82f, rounding = CornerRounding(radius = 0.24f)),
    )

    /** Four-leaf clover. */
    public val Clover: Shape = PolygonShape(
        RoundedPolygon.star(numVerticesPerRadius = 4, innerRadius = 0.42f, rounding = CornerRounding(radius = 0.48f)),
    )

    /** Rounded pentagon "gem". */
    public val Gem: Shape = PolygonShape(RoundedPolygon(numVertices = 5, rounding = CornerRounding(radius = 0.28f)))

    /** Scalloped burst used for success/attention states. */
    public val Burst: Shape = PolygonShape(
        RoundedPolygon.star(numVerticesPerRadius = 12, innerRadius = 0.86f, rounding = CornerRounding(radius = 0.12f)),
    )

    /** Rounded square ("squircle-ish"). */
    public val Pebble: Shape = PolygonShape(RoundedPolygon(numVertices = 4, rounding = CornerRounding(radius = 0.42f)))
}
