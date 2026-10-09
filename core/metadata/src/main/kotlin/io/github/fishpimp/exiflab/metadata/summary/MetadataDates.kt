package io.github.fishpimp.exiflab.metadata.summary

import java.time.DateTimeException
import java.time.LocalDateTime
import java.time.ZoneOffset

/** A local date-time with the UTC offset written next to it, if any. */
internal data class DatedValue(val local: LocalDateTime, val offset: ZoneOffset?)

/** Lenient parsers for the date formats found in metadata. */
internal object MetadataDates {
    // Exif "2024:05:01 14:03:22", plus the dashes, slashes and "T" separators some writers use.
    private val exifPattern =
        Regex("""^(\d{4})[:\-/](\d{2})[:\-/](\d{2})[ T](\d{2}):(\d{2})(?::(\d{2}))?(?:[.,](\d{1,9}))?\s*(Z|[+-]\d{2}:?\d{2})?$""")

    // XMP/ISO 8601 "2024-05-01T14:03:22.5+02:00"; a time is required to be useful as a capture time.
    private val xmpPattern =
        Regex("""^(\d{4})-(\d{2})-(\d{2})T(\d{2}):(\d{2})(?::(\d{2})(?:[.,](\d{1,9}))?)?(Z|[+-]\d{2}:?\d{2})?$""")

    private val offsetPattern = Regex("""^([+-])(\d{2}):?(\d{2})$""")

    /** Parses an Exif date, applying [subSeconds] (the SubSecTime* digits) when the date has none. */
    fun parseExif(text: String?, subSeconds: String? = null): DatedValue? {
        val match = text?.trim()?.let(exifPattern::matchEntire) ?: return null
        val fraction = match.groupValues[7].ifEmpty { subSeconds?.trim()?.takeIf { it.matches(Regex("\\d{1,9}")) }.orEmpty() }
        return build(match.groupValues, fraction, match.groupValues[8])
    }

    /** Parses an XMP (ISO 8601) date-time; date-only values return null. */
    fun parseXmp(text: String?): DatedValue? {
        val match = text?.trim()?.let(xmpPattern::matchEntire) ?: return null
        return build(match.groupValues, match.groupValues[7], match.groupValues[8])
    }

    /** Parses "+02:00", "-0530" or "Z"; blank and out-of-range values return null. */
    fun parseOffset(text: String?): ZoneOffset? {
        val trimmed = text?.trim()?.takeIf { it.isNotEmpty() } ?: return null
        if (trimmed == "Z") return ZoneOffset.UTC
        val match = offsetPattern.matchEntire(trimmed) ?: return null
        val (sign, hours, minutes) = match.destructured
        val total = (hours.toInt() * 60 + minutes.toInt()) * if (sign == "-") -1 else 1
        return offsetOfMinutes(total)
    }

    /** A [ZoneOffset] of [minutes], or null outside the real-world range of -12:00 to +14:00. */
    fun offsetOfMinutes(minutes: Int): ZoneOffset? =
        if (minutes in -12 * 60..14 * 60) ZoneOffset.ofTotalSeconds(minutes * 60) else null

    private fun build(groups: List<String>, fraction: String, offset: String): DatedValue? = try {
        val nanos = if (fraction.isEmpty()) 0 else fraction.padEnd(9, '0').toInt()
        val local = LocalDateTime.of(
            groups[1].toInt(), groups[2].toInt(), groups[3].toInt(),
            groups[4].toInt(), groups[5].toInt(), groups[6].ifEmpty { "0" }.toInt(), nanos,
        )
        if (local.year < 1) null else DatedValue(local, parseOffset(offset))
    } catch (_: DateTimeException) {
        // "0000:00:00 00:00:00" and other placeholders written by cameras with unset clocks.
        null
    }
}
