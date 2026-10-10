package io.github.fishpimp.exiflab.ui.format

import java.time.LocalDateTime
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle
import java.util.Locale
import kotlin.math.abs

/** Capture date and time as recorded by the camera, which is local time with an optional UTC offset. */
object CaptureTimeFormat {
    /** "Wednesday, 1 May 2024" in the conventions of [locale]. */
    fun date(local: LocalDateTime, locale: Locale): String =
        DateTimeFormatter.ofLocalizedDate(FormatStyle.FULL).withLocale(locale).format(local)

    /** "14:03:22" or "2:03:22 PM", with seconds, in the conventions of [locale]. */
    fun time(local: LocalDateTime, locale: Locale): String =
        DateTimeFormatter.ofLocalizedTime(FormatStyle.MEDIUM).withLocale(locale).format(local)

    /**
     * The offset part of "UTC+02:00": "+02:00", "−03:30", "+00:00". Always hours and minutes, with
     * a typographic minus, so it reads the same in every language.
     */
    fun utcOffset(offset: ZoneOffset): String {
        val total = offset.totalSeconds
        val sign = if (total < 0) ExposureFormat.MINUS else "+"
        val minutes = abs(total) / 60
        return String.format(Locale.ROOT, "%s%02d:%02d", sign, minutes / 60, minutes % 60)
    }
}
