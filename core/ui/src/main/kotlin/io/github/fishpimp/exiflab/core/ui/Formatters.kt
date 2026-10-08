package io.github.fishpimp.exiflab.core.ui

import android.content.Context
import android.text.format.Formatter
import io.github.fishpimp.exiflab.model.CapturedTime
import io.github.fishpimp.exiflab.model.GeoPoint
import io.github.fishpimp.exiflab.model.Rational
import java.text.NumberFormat
import java.time.LocalDateTime
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle
import java.util.Locale
import kotlin.math.abs
import kotlin.math.roundToInt

/** Locale-aware formatting of photo facts for headers, lists and exports. */
public object Formatters {
    private val exifDate: DateTimeFormatter = DateTimeFormatter.ofPattern("yyyy:MM:dd HH:mm:ss")

    private fun decimal(value: Double, maxFraction: Int, locale: Locale = Locale.getDefault()): String =
        NumberFormat.getNumberInstance(locale).apply {
            maximumFractionDigits = maxFraction
            minimumFractionDigits = 0
        }.format(value)

    /** `1/250 s`, `0.5 s`, `30 s`. */
    public fun exposure(value: Rational): String {
        val d = value.toDouble()
        if (d.isNaN() || d <= 0) return value.toString()
        return if (d < 0.5) {
            val denominator = (1.0 / d).roundToInt()
            "1/$denominator s"
        } else {
            "${decimal(d, 1)} s"
        }
    }

    /** `f/1.8` (decimal separator follows the locale). */
    public fun aperture(fNumber: Double): String = "f/${decimal(fNumber, 1)}"

    /** `35 mm` or `35 mm (52 mm equiv.)` style with [equivalentTemplate] like "%s equiv.". */
    public fun focalLength(mm: Double, equivalent35: Int?): String {
        val base = "${decimal(mm, 1)} mm"
        return if (equivalent35 != null && equivalent35 > 0 && abs(equivalent35 - mm) >= 1) "$base · ${equivalent35} mm FF" else base
    }

    public fun iso(iso: Int): String = "ISO $iso"

    /** `+0.7 EV` / `-1.3 EV` / `0 EV`. */
    public fun exposureBias(ev: Double): String = when {
        abs(ev) < 0.05 -> "0 EV"
        ev > 0 -> "+${decimal(ev, 1)} EV"
        else -> "-${decimal(-ev, 1)} EV"
    }

    public fun fileSize(context: Context, bytes: Long): String = Formatter.formatShortFileSize(context, bytes)

    public fun megapixels(width: Int, height: Int): String = "${decimal(width.toDouble() * height / 1_000_000.0, 1)} MP"

    /** Parses an EXIF date `YYYY:MM:DD HH:MM:SS`; null if malformed. */
    public fun parseExifDate(value: String): LocalDateTime? = runCatching { LocalDateTime.parse(value.trim().take(19), exifDate) }.getOrNull()

    /** Localised date and time, plus `UTC+02:00` when the offset is known. */
    public fun captured(time: CapturedTime, style: FormatStyle = FormatStyle.MEDIUM): String {
        val local = parseExifDate(time.dateTime) ?: return time.dateTime
        val text = local.format(DateTimeFormatter.ofLocalizedDateTime(style).withLocale(Locale.getDefault()))
        val offset = time.offset?.let { runCatching { ZoneOffset.of(it) }.getOrNull() }
        return if (offset != null) "$text  UTC${if (offset.totalSeconds == 0) "" else offset.id}" else text
    }

    /** `59.32944° N, 18.06861° E` */
    public fun coordinates(point: GeoPoint, digits: Int = 5): String {
        val lat = "${decimal(abs(point.latitude), digits)}° ${if (point.latitude >= 0) "N" else "S"}"
        val lon = "${decimal(abs(point.longitude), digits)}° ${if (point.longitude >= 0) "E" else "W"}"
        return "$lat, $lon"
    }

    /** `59° 19′ 46.0″ N, 18° 4′ 7.0″ E` */
    public fun coordinatesDms(point: GeoPoint): String = "${dms(point.latitude, 'N', 'S')}, ${dms(point.longitude, 'E', 'W')}"

    private fun dms(value: Double, pos: Char, neg: Char): String {
        val a = abs(value)
        val d = a.toInt()
        val mFull = (a - d) * 60
        val m = mFull.toInt()
        val s = (mFull - m) * 60
        return "$d° $m′ ${decimal(s, 1)}″ ${if (value >= 0) pos else neg}"
    }

    /** Machine-readable coordinates for copying: `59.329440, 18.068610` (always a dot). */
    public fun coordinatesForCopy(point: GeoPoint): String =
        String.format(Locale.ROOT, "%.6f, %.6f", point.latitude, point.longitude)

    public fun altitude(metres: Double): String = "${decimal(metres, 1)} m"
}
