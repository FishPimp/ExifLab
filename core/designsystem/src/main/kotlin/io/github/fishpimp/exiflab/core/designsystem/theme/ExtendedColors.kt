package io.github.fishpimp.exiflab.core.designsystem.theme

import androidx.compose.runtime.Immutable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color

/** A container colour with its matching content colour. */
@Immutable
public data class ColorPair(val container: Color, val content: Color)

/** Colours outside the Material scheme: privacy markers and the per-category accents of the viewer. */
@Immutable
public data class ExtendedColors(
    /** Privacy-sensitive marker (amber), distinct from error (coral-red). */
    val privacy: ColorPair,
    val success: ColorPair,
    val file: ColorPair,
    val exif: ColorPair,
    val gps: ColorPair,
    val makerNotes: ColorPair,
    val xmp: ColorPair,
    val iptc: ColorPair,
    val icc: ColorPair,
    val container: ColorPair,
    /** Strong accent used for big numbers (exposure triangle) on light surfaces. */
    val highlight: ColorPair,
)

internal fun lightExtendedColors(): ExtendedColors = ExtendedColors(
    privacy = ColorPair(Accent.Amber.t90, Accent.Amber.t20),
    success = ColorPair(Accent.Moss.t90, Accent.Moss.t20),
    file = ColorPair(Accent.Slate.t90, Accent.Slate.t20),
    exif = ColorPair(Accent.Teal.t90, Accent.Teal.t20),
    gps = ColorPair(Accent.Lime.t90, Accent.Lime.t20),
    makerNotes = ColorPair(Accent.Sand.t90, Accent.Sand.t20),
    xmp = ColorPair(Accent.Iris.t90, Accent.Iris.t20),
    iptc = ColorPair(Accent.Sky.t90, Accent.Sky.t20),
    icc = ColorPair(Accent.Rose.t90, Accent.Rose.t20),
    container = ColorPair(Accent.Slate.t95, Accent.Slate.t30),
    highlight = ColorPair(Accent.Lime.t90, Accent.Teal.t30),
)

internal fun darkExtendedColors(): ExtendedColors = ExtendedColors(
    privacy = ColorPair(Accent.Amber.t30, Accent.Amber.t90),
    success = ColorPair(Accent.Moss.t30, Accent.Moss.t90),
    file = ColorPair(Accent.Slate.t30, Accent.Slate.t90),
    exif = ColorPair(Accent.Teal.t30, Accent.Teal.t90),
    gps = ColorPair(Accent.Lime.t30, Accent.Lime.t90),
    makerNotes = ColorPair(Accent.Sand.t30, Accent.Sand.t90),
    xmp = ColorPair(Accent.Iris.t30, Accent.Iris.t90),
    iptc = ColorPair(Accent.Sky.t30, Accent.Sky.t90),
    icc = ColorPair(Accent.Rose.t30, Accent.Rose.t90),
    container = ColorPair(Accent.Slate.t20, Accent.Slate.t90),
    highlight = ColorPair(Accent.Lime.t30, Accent.Lime.t90),
)

public val LocalExtendedColors: androidx.compose.runtime.ProvidableCompositionLocal<ExtendedColors> =
    staticCompositionLocalOf { lightExtendedColors() }
