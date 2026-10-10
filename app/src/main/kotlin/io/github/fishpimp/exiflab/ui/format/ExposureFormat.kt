package io.github.fishpimp.exiflab.ui.format

import io.github.fishpimp.exiflab.metadata.model.Rational
import java.math.RoundingMode
import java.text.NumberFormat
import java.util.Locale
import kotlin.math.abs
import kotlin.math.roundToLong

/** How a shutter speed reads: a fraction of a second, or a number of seconds. */
sealed interface ShutterSpeed {
    /** 1/[denominator] of a second. */
    data class Fraction(val denominator: Long) : ShutterSpeed

    /** Whole or decimal seconds: long exposures and values with no clean 1/N form, such as 0.6 s. */
    data class Seconds(val seconds: Double) : ShutterSpeed
}

/**
 * Numbers for the viewer header, without units: the caller wraps them in a localized template
 * such as "f/%1$s". Decimals follow [Locale] conventions ("1.8" in English, "1,8" in Swedish).
 */
object ExposureFormat {
    /** Below this many seconds an exposure always reads as 1/N. */
    private const val FRACTION_ONLY_BELOW = 0.3

    /** Largest relative error for showing a slow exposure (0.3-1 s) as 1/N instead of decimal seconds. */
    private const val FRACTION_TOLERANCE = 0.05

    /** Signals the exact zero of a bias, as cameras show it. */
    const val PLUS_MINUS = "±"

    /** Typographic minus sign, which also lines up with "+" in monospace text. */
    const val MINUS = "−"

    /**
     * Normalizes an exposure time as cameras write it, often unreduced ("2141/1000000",
     * "10/38000", "3000/100"). Under one second it becomes 1/N with N rounded to a whole number;
     * slow exposures that are not close to 1/N (0.4 s, 0.6 s) and anything from one second up
     * stay in seconds. Null for zero, negative or undefined values.
     */
    fun shutterSpeed(exposure: Rational): ShutterSpeed? {
        val seconds = exposure.value
        if (!seconds.isFinite() || seconds <= 0) return null
        if (seconds >= 1) return ShutterSpeed.Seconds(seconds)
        val exact = 1 / seconds
        val rounded = exact.roundToLong()
        val error = abs(rounded - exact) / exact
        return if (rounded < 2 || (seconds >= FRACTION_ONLY_BELOW && error > FRACTION_TOLERANCE)) {
            ShutterSpeed.Seconds(seconds)
        } else {
            ShutterSpeed.Fraction(rounded)
        }
    }

    /** "1/250", "2.5", "30" or "0.6" (unit not included), or null when [exposure] is not a valid time. */
    fun shutterSpeedText(exposure: Rational, locale: Locale): String? = when (val speed = shutterSpeed(exposure)) {
        is ShutterSpeed.Fraction -> "1/${speed.denominator}"
        is ShutterSpeed.Seconds -> decimal(speed.seconds, 1, locale)
        null -> null
    }

    /** "1.8", "2", "11" for an f-number (the "f/" prefix is not included). Null when not positive. */
    fun fNumber(value: Double, locale: Locale): String? =
        value.takeIf { it.isFinite() && it > 0 }?.let { decimal(it, 1, locale) }

    /** "+0.7", "−1.3" or "±0" for an exposure bias in EV. Null when not a number. */
    fun exposureBias(ev: Double, locale: Locale): String? {
        if (!ev.isFinite()) return null
        val magnitude = decimal(abs(ev), 1, locale)
        val isZero = abs(ev) < 0.05
        return when {
            isZero -> "${PLUS_MINUS}0"
            ev > 0 -> "+$magnitude"
            else -> "$MINUS$magnitude"
        }
    }

    /** "6.9", "26" for a focal length in millimetres. Null when not positive. */
    fun focalLength(mm: Double, locale: Locale): String? =
        mm.takeIf { it.isFinite() && it > 0 }?.let { decimal(it, 1, locale) }

    /**
     * "12.2" for a 4032 x 3024 image. Null when the size is unknown or too small to be worth
     * saying (under 0.05 MP).
     */
    fun megapixels(width: Int, height: Int, locale: Locale): String? {
        if (width <= 0 || height <= 0) return null
        val megapixels = width.toLong() * height / 1_000_000.0
        return if (megapixels < 0.05) null else decimal(megapixels, 1, locale)
    }

    /** Width and height as the photo is shown: EXIF orientations 5-8 rotate it by 90 degrees. */
    fun orientedSize(width: Int, height: Int, orientation: Int?): Pair<Int, Int> =
        if (orientation in 5..8) height to width else width to height

    /** [value] with at most [maxFractionDigits] decimals, trailing zeros dropped, no grouping, rounding half up. */
    fun decimal(value: Double, maxFractionDigits: Int, locale: Locale): String {
        val format = NumberFormat.getNumberInstance(locale).apply {
            maximumFractionDigits = maxFractionDigits
            minimumFractionDigits = 0
            isGroupingUsed = false
            roundingMode = RoundingMode.HALF_UP
        }
        return format.format(value)
    }
}
