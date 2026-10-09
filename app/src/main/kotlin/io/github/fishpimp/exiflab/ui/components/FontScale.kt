package io.github.fishpimp.exiflab.ui.components

import androidx.compose.runtime.Composable
import androidx.compose.ui.platform.LocalDensity

/** Font scale from which compact side-by-side layouts switch to roomier stacked ones. */
private const val LARGE_FONT_SCALE = 1.5f

/** True when the user's font size is large enough that compact layouts would squeeze text. */
@Composable
fun isLargeFontScale(): Boolean = LocalDensity.current.fontScale >= LARGE_FONT_SCALE
