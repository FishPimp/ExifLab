package io.github.fishpimp.exiflab.metadata.edit

import io.github.fishpimp.exiflab.metadata.model.Rational
import java.time.DateTimeException
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime
import java.time.ZoneOffset
import kotlin.math.abs

/**
 * An XMP (ISO 8601) date as written, so a rewritten value keeps its precision: [fields] counts the
 * parts present (1 year, 2 month, 3 day, 4 minutes, 5 seconds), [fractionDigits] the sub-second
 * digits, and [zulu] whether UTC was written as "Z".
 */
internal data class XmpDate(
    val local: LocalDateTime,
    val fields: Int,
    val fractionDigits: Int,
    val offset: ZoneOffset?,
    val zulu: Boolean = false,
) {
    val hasTime: Boolean get() = fields >= 4
}

/** Parsing and formatting of the date and time encodings found in EXIF, XMP, IPTC and GPS tags. */
internal object EditDates {
    private val xmpPattern =
        Regex("""^(\d{4})(?:-(\d{2})(?:-(\d{2})(?:T(\d{2}):(\d{2})(?::(\d{2})(?:[.,](\d{1,9}))?)?\s*(Z|[+-]\d{2}:?\d{2})?)?)?)?$""")
    private val iptcDatePattern = Regex("""^(\d{4})[-:]?(\d{2})[-:]?(\d{2})$""")
    private val iptcTimePattern = Regex("""^(\d{2}):?(\d{2}):?(\d{2})(?:([+-])(\d{2}):?(\d{2}))?$""")
    private val gpsDatePattern = Regex("""^(\d{4})[:\-](\d{2})[:\-](\d{2})$""")

    /** "2026:06:14 17:42:07", the EXIF DateTime* format. */
    fun formatExif(local: LocalDateTime): String =
        "%04d:%02d:%02d %02d:%02d:%02d".format(local.year, local.monthValue, local.dayOfMonth, local.hour, local.minute, local.second)

    /** "+02:00", the EXIF OffsetTime* and XMP format; [zulu] writes UTC as "Z". */
    fun formatOffset(offset: ZoneOffset, zulu: Boolean = false): String {
        if (zulu && offset.totalSeconds == 0) return "Z"
        val total = offset.totalSeconds
        val minutes = abs(total) / 60
        return "%s%02d:%02d".format(if (total < 0) "-" else "+", minutes / 60, minutes % 60)
    }

    /** Sub-second digits of [nanos], at least [minDigits] long (keeping an existing tag's width). */
    fun subSecond(nanos: Int, minDigits: Int = 0): String {
        val needed = fractionDigits(nanos)
        val digits = maxOf(needed, minDigits.coerceIn(0, 9)).coerceAtLeast(1)
        return "%09d".format(nanos).substring(0, digits)
    }

    /** How many digits [nanos] needs, 0 for whole seconds. */
    fun fractionDigits(nanos: Int): Int = if (nanos == 0) 0 else "%09d".format(nanos).trimEnd('0').length

    fun parseXmp(text: String?): XmpDate? {
        val match = text?.trim()?.let(xmpPattern::matchEntire) ?: return null
        val g = match.groupValues
        val fields = when {
            g[6].isNotEmpty() -> 5
            g[4].isNotEmpty() -> 4
            g[3].isNotEmpty() -> 3
            g[2].isNotEmpty() -> 2
            else -> 1
        }
        return try {
            val fraction = g[7]
            val local = LocalDateTime.of(
                g[1].toInt(), g[2].ifEmpty { "1" }.toInt(), g[3].ifEmpty { "1" }.toInt(),
                g[4].ifEmpty { "0" }.toInt(), g[5].ifEmpty { "0" }.toInt(), g[6].ifEmpty { "0" }.toInt(),
                if (fraction.isEmpty()) 0 else fraction.padEnd(9, '0').toInt(),
            )
            val offset = g[8].takeIf { it.isNotEmpty() }?.let(::parseOffset)
            if (g[8].isNotEmpty() && offset == null) return null
            XmpDate(local, fields, fraction.length, offset, zulu = g[8] == "Z")
        } catch (_: DateTimeException) {
            null
        }
    }

    fun formatXmp(date: XmpDate): String = buildString {
        val local = date.local
        append("%04d".format(local.year))
        if (date.fields >= 2) append("-%02d".format(local.monthValue))
        if (date.fields >= 3) append("-%02d".format(local.dayOfMonth))
        if (date.fields >= 4) {
            append("T%02d:%02d".format(local.hour, local.minute))
            if (date.fields >= 5) {
                append(":%02d".format(local.second))
                val digits = maxOf(date.fractionDigits, fractionDigits(local.nano))
                if (digits > 0) append('.').append(subSecond(local.nano, digits))
            }
            date.offset?.let { append(formatOffset(it, date.zulu)) }
        }
    }

    /** A full XMP date-time for [local] with [offset], seconds always and sub-seconds when present. */
    fun xmpDateTime(local: LocalDateTime, offset: ZoneOffset?, zulu: Boolean = false): XmpDate =
        XmpDate(local, 5, fractionDigits(local.nano), offset, zulu)

    /** "+02:00", "-0530" or "Z"; null when malformed or outside -18:00..+18:00. */
    fun parseOffset(text: String?): ZoneOffset? {
        val trimmed = text?.trim()?.takeIf { it.isNotEmpty() } ?: return null
        if (trimmed == "Z") return ZoneOffset.UTC
        val match = Regex("""^([+-])(\d{1,2}):?(\d{2})?$""").matchEntire(trimmed) ?: return null
        val (sign, hours, minutes) = match.destructured
        return try {
            val h = hours.toInt()
            val m = minutes.ifEmpty { "0" }.toInt()
            if (sign == "-") ZoneOffset.ofHoursMinutes(-h, -m) else ZoneOffset.ofHoursMinutes(h, m)
        } catch (_: DateTimeException) {
            null
        }
    }

    fun parseIptcDate(text: String?): LocalDate? {
        val match = text?.trim()?.let(iptcDatePattern::matchEntire) ?: return null
        val (y, m, d) = match.destructured
        return try {
            LocalDate.of(y.toInt(), m.toInt(), d.toInt())
        } catch (_: DateTimeException) {
            null
        }
    }

    /** IPTC "HHMMSS±HHMM": the time and its offset, if written. */
    fun parseIptcTime(text: String?): Pair<LocalTime, ZoneOffset?>? {
        val match = text?.trim()?.let(iptcTimePattern::matchEntire) ?: return null
        val g = match.groupValues
        return try {
            val time = LocalTime.of(g[1].toInt(), g[2].toInt(), g[3].toInt())
            val offset = if (g[4].isEmpty()) null else parseOffset("${g[4]}${g[5]}:${g[6]}")
            time to offset
        } catch (_: DateTimeException) {
            null
        }
    }

    fun formatIptcDate(date: LocalDate): String = "%04d%02d%02d".format(date.year, date.monthValue, date.dayOfMonth)

    fun formatIptcTime(time: LocalTime, offset: ZoneOffset?): String {
        val base = "%02d%02d%02d".format(time.hour, time.minute, time.second)
        return if (offset == null) base else base + formatOffset(offset).replace(":", "")
    }

    fun parseGpsDate(text: String?): LocalDate? {
        val match = text?.trim()?.let(gpsDatePattern::matchEntire) ?: return null
        val (y, m, d) = match.destructured
        return try {
            LocalDate.of(y.toInt(), m.toInt(), d.toInt())
        } catch (_: DateTimeException) {
            null
        }
    }

    fun formatGpsDate(date: LocalDate): String = "%04d:%02d:%02d".format(date.year, date.monthValue, date.dayOfMonth)

    /** GPSTimeStamp rationals ("12/1 3/1 2250/100") as a time of day; null when malformed or out of range. */
    fun parseGpsTime(raw: String?): LocalTime? {
        val parts = raw?.let(::parseRationals)?.takeIf { it.size == 3 } ?: return null
        val seconds = parts[0] * 3600 + parts[1] * 60 + parts[2]
        if (!seconds.isFinite() || seconds < 0 || seconds >= 86400) return null
        val nanos = Math.round(seconds * 1_000_000_000.0)
        return LocalTime.ofNanoOfDay(nanos.coerceAtMost(86_399_999_999_999L))
    }

    /** GPSTimeStamp for [time]: whole hours and minutes, seconds with millisecond precision when needed. */
    fun gpsTime(time: LocalTime): List<Rational> {
        val millis = Math.round(time.nano / 1_000_000.0)
        val seconds = if (millis == 0L) Rational(time.second.toLong(), 1) else Rational(time.second * 1000L + millis, 1000)
        return listOf(Rational(time.hour.toLong(), 1), Rational(time.minute.toLong(), 1), seconds)
    }

    /** Space-separated "n/d" (or plain number) values as doubles. */
    fun parseRationals(raw: String): List<Double>? {
        val parts = raw.trim().split(Regex("\\s+")).filter { it.isNotEmpty() }
        if (parts.isEmpty()) return null
        return parts.map { part ->
            val n = part.substringBefore('/').toDoubleOrNull() ?: return null
            val d = if ('/' in part) part.substringAfter('/').toDoubleOrNull() ?: return null else 1.0
            if (d == 0.0) return null
            n / d
        }
    }
}
