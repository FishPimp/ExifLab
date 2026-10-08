package io.github.fishpimp.exiflab.core.designsystem.theme

import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Shapes
import androidx.compose.ui.unit.dp

internal val ExifLabShapes: Shapes = Shapes(
    extraSmall = RoundedCornerShape(8.dp),
    small = RoundedCornerShape(14.dp),
    medium = RoundedCornerShape(20.dp),
    large = RoundedCornerShape(28.dp),
    extraLarge = RoundedCornerShape(36.dp),
)

/** Spacing scale (4 dp grid). */
public object Spacing {
    public val xxs: androidx.compose.ui.unit.Dp = 2.dp
    public val xs: androidx.compose.ui.unit.Dp = 4.dp
    public val s: androidx.compose.ui.unit.Dp = 8.dp
    public val m: androidx.compose.ui.unit.Dp = 12.dp
    public val l: androidx.compose.ui.unit.Dp = 16.dp
    public val xl: androidx.compose.ui.unit.Dp = 24.dp
    public val xxl: androidx.compose.ui.unit.Dp = 32.dp
    public val screen: androidx.compose.ui.unit.Dp = 16.dp
}
