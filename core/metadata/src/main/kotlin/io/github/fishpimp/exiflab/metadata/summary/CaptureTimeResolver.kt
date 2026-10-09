package io.github.fishpimp.exiflab.metadata.summary

import com.drew.metadata.exif.makernotes.CanonMakernoteDirectory
import com.drew.metadata.exif.makernotes.NikonType2MakernoteDirectory
import com.drew.metadata.png.PngDirectory
import io.github.fishpimp.exiflab.metadata.model.OffsetSource
import java.time.Duration
import java.time.Instant
import java.time.LocalDateTime
import java.time.ZoneOffset
import kotlin.math.abs
import kotlin.math.roundToLong

/** When a photo was taken, in camera-local time, and how its UTC offset was determined. */
internal data class CaptureTime(val local: LocalDateTime, val offset: ZoneOffset?, val offsetSource: OffsetSource?)

/**
 * Resolves the capture time and its UTC offset.
 *
 * Time: DateTimeOriginal, DateTimeDigitized, DateTime (with their SubSecTime tags), then XMP
 * exif:DateTimeOriginal, xmp:CreateDate, photoshop:DateCreated, then container dates (PNG
 * "Creation Time").
 *
 * Offset: the Exif OffsetTime* tags, then an XMP date carrying an offset for the same local time,
 * then the difference to the GPS UTC timestamp (rounded to 15 minutes, at most 14 hours), then
 * MakerNote time zones (Canon TimeInfo, Nikon WorldTime).
 */
internal class CaptureTimeResolver(private val lookup: MetadataLookup, private val gpsTimestamp: Instant?) {
    private class Candidate(val value: DatedValue, val exifOffsetTag: Int? = null, val fromXmp: Boolean = false)

    fun resolve(): CaptureTime? {
        val candidate = exifCandidate() ?: xmpCandidate() ?: containerCandidate() ?: return null
        val local = candidate.value.local
        exifOffset(candidate.exifOffsetTag)?.let { return CaptureTime(local, it, OffsetSource.ExifOffsetTime) }
        xmpOffset(candidate)?.let { return CaptureTime(local, it, OffsetSource.Xmp) }
        gpsOffset(local)?.let { return CaptureTime(local, it, OffsetSource.GpsTimestamp) }
        makerNoteOffset()?.let { return CaptureTime(local, it, OffsetSource.MakerNote) }
        return CaptureTime(local, null, null)
    }

    private fun exifCandidate(): Candidate? = EXIF_DATES.firstNotNullOfOrNull { (dateTag, subSecTag, offsetTag) ->
        val directory = lookup.exif(dateTag) ?: return@firstNotNullOfOrNull null
        val subSeconds = lookup.exif(subSecTag)?.text(subSecTag)
        MetadataDates.parseExif(directory.text(dateTag), subSeconds)?.let { Candidate(it, exifOffsetTag = offsetTag) }
    }

    private fun xmpCandidate(): Candidate? =
        XMP_DATES.firstNotNullOfOrNull { path -> MetadataDates.parseXmp(lookup.xmp(path)) }?.let { Candidate(it, fromXmp = true) }

    /**
     * The PNG "Creation Time" text chunk. Its offset, if any, is not used (no source category fits),
     * and tIME is skipped: it records the last modification, not the capture.
     */
    private fun containerCandidate(): Candidate? =
        lookup.all<PngDirectory>().flatMap { it.textEntries() }
            .firstOrNull { (key, _) -> key.equals("Creation Time", ignoreCase = true) }
            ?.let { (_, value) -> MetadataDates.parseExif(value) ?: MetadataDates.parseXmp(value) }
            ?.let { Candidate(it) }

    private fun exifOffset(matchingTag: Int?): ZoneOffset? =
        (listOfNotNull(matchingTag) + OFFSET_TAGS).distinct().firstNotNullOfOrNull { tag ->
            lookup.exif(tag)?.text(tag)?.let(MetadataDates::parseOffset)
        }

    /** An offset written with an XMP date, trusted only when that date's local time matches. */
    private fun xmpOffset(candidate: Candidate): ZoneOffset? {
        if (candidate.fromXmp) candidate.value.offset?.let { return it }
        return XMP_DATES.firstNotNullOfOrNull { path ->
            MetadataDates.parseXmp(lookup.xmp(path))
                ?.takeIf { it.offset != null && it.local.withNano(0) == candidate.value.local.withNano(0) }
                ?.offset
        }
    }

    private fun gpsOffset(local: LocalDateTime): ZoneOffset? {
        val gps = gpsTimestamp ?: return null
        val difference = Duration.between(gps, local.toInstant(ZoneOffset.UTC)).seconds
        if (abs(difference) > MAX_GPS_DIFFERENCE_SECONDS) return null
        val quarterHours = (difference / QUARTER_HOUR_SECONDS.toDouble()).roundToLong()
        return MetadataDates.offsetOfMinutes((quarterHours * 15).toInt())
    }

    private fun makerNoteOffset(): ZoneOffset? = canonOffset() ?: nikonOffset()

    /** Canon TimeInfo (0x0035): int32s [size, TimeZone minutes, TimeZoneCity, DaylightSavings minutes]. */
    private fun canonOffset(): ZoneOffset? = lookup.all<CanonMakernoteDirectory>().firstNotNullOfOrNull { directory ->
        directory.ints(CANON_TIME_INFO)?.takeIf { it.size >= 4 }?.let { MetadataDates.offsetOfMinutes(it[1] + it[3]) }
    }

    /**
     * Nikon WorldTime: int16s TimeZone minutes, int8u DaylightSavings, int8u DateDisplayFormat, in
     * the MakerNote's byte order (not exposed), so the order that gives a plausible zone wins.
     */
    private fun nikonOffset(): ZoneOffset? = lookup.all<NikonType2MakernoteDirectory>().firstNotNullOfOrNull { directory ->
        val bytes = directory.bytes(NikonType2MakernoteDirectory.TAG_WORLD_TIME)?.takeIf { it.size >= 4 }
            ?: return@firstNotNullOfOrNull null
        val dst = if (bytes[2].toInt() == 1) 60 else 0
        listOf(true, false).firstNotNullOfOrNull { bigEndian ->
            val zone = if (bigEndian) (bytes[0].toInt() shl 8) or (bytes[1].toInt() and 0xFF)
            else (bytes[1].toInt() shl 8) or (bytes[0].toInt() and 0xFF)
            if (zone % 15 == 0) MetadataDates.offsetOfMinutes(zone + dst) else null
        }
    }

    private companion object {
        /** (date tag, sub-second tag, offset tag) in priority order. */
        val EXIF_DATES = listOf(
            Triple(0x9003, 0x9291, 0x9011), // DateTimeOriginal, SubSecTimeOriginal, OffsetTimeOriginal
            Triple(0x9004, 0x9292, 0x9012), // DateTimeDigitized, SubSecTimeDigitized, OffsetTimeDigitized
            Triple(0x0132, 0x9290, 0x9010), // DateTime, SubSecTime, OffsetTime
        )
        val OFFSET_TAGS = listOf(0x9011, 0x9010, 0x9012)
        val XMP_DATES = listOf("exif:DateTimeOriginal", "xmp:CreateDate", "photoshop:DateCreated")
        const val CANON_TIME_INFO = 0x0035
        const val QUARTER_HOUR_SECONDS = 15 * 60
        const val MAX_GPS_DIFFERENCE_SECONDS = 14L * 60 * 60
    }
}
