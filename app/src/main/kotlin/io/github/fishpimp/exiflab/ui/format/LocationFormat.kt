package io.github.fishpimp.exiflab.ui.format

import java.util.Locale
import kotlin.math.abs
import kotlin.math.floor
import kotlin.math.roundToInt
import kotlin.math.roundToLong

/** The eight compass points, clockwise from north. */
enum class CompassPoint { N, NE, E, SE, S, SW, W, NW }

/**
 * Coordinates in the forms people paste into maps and GPS tools. These stay in international
 * notation (dot decimals, N/S/E/W) in every language so they can be copied as they are.
 */
object LocationFormat {
    private const val TENTHS_PER_DEGREE = 36_000L
    private const val TENTHS_PER_MINUTE = 600L

    /** "59°19′35.3″N 18°04′18.7″E": degrees, minutes and seconds to a tenth. */
    fun degreesMinutesSeconds(latitude: Double, longitude: Double): String =
        "${dmsPart(latitude, 'N', 'S')} ${dmsPart(longitude, 'E', 'W')}"

    /**
     * One axis in degrees, minutes and seconds. Works in whole tenths of an arcsecond so rounding
     * can never produce 60 seconds or 60 minutes.
     */
    fun dmsPart(value: Double, positive: Char, negative: Char): String {
        val hemisphere = if (value < 0) negative else positive
        val tenths = (abs(value) * TENTHS_PER_DEGREE).roundToLong()
        val degrees = tenths / TENTHS_PER_DEGREE
        val minutes = (tenths % TENTHS_PER_DEGREE) / TENTHS_PER_MINUTE
        val secondTenths = tenths % TENTHS_PER_MINUTE
        return String.format(
            Locale.ROOT,
            "%d°%02d′%02d.%d″%c",
            degrees,
            minutes,
            secondTenths / 10,
            secondTenths % 10,
            hemisphere,
        )
    }

    /** "59.326470, 18.071850": signed decimal degrees with six decimals (about 10 cm). */
    fun decimalDegrees(latitude: Double, longitude: Double): String =
        String.format(Locale.ROOT, "%.6f, %.6f", latitude, longitude)

    /** Whole metres, sign dropped; the caller says whether it is above or below sea level. */
    fun altitudeMeters(meters: Double): Int = abs(meters).roundToInt()

    /** Compass bearing rounded to whole degrees in 0..359. */
    fun bearingDegrees(degrees: Double): Int = normalize(degrees).roundToInt() % 360

    /** The nearest of the eight compass points for a bearing in degrees. */
    fun compassPoint(degrees: Double): CompassPoint {
        val index = floor(normalize(degrees) / 45.0 + 0.5).toInt() % CompassPoint.entries.size
        return CompassPoint.entries[index]
    }

    private fun normalize(degrees: Double): Double = ((degrees % 360) + 360) % 360
}
