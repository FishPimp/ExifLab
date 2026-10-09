package io.github.fishpimp.exiflab.metadata.model

import io.github.fishpimp.exiflab.metadata.ImageFormat
import java.time.Instant
import java.time.LocalDateTime
import java.time.ZoneOffset

/**
 * Everything ExifLab knows about one image file, normalized from all metadata blocks.
 * Produced by [io.github.fishpimp.exiflab.metadata.MetadataReader]; consumed by the viewer,
 * exporters and (later) the editor.
 */
data class MetadataReport(
    val format: ImageFormat,
    val fileName: String?,
    val fileSize: Long?,
    val summary: PhotoSummary,
    /** Decoded GPS position, or null when absent or redacted. */
    val location: GeoLocation?,
    val locationStatus: LocationStatus,
    /** All metadata directories in display order (summary-relevant groups first). */
    val directories: List<MetadataDirectory>,
    /** Privacy-sensitive data found in this file, one entry per category. */
    val sensitiveFindings: List<SensitiveFinding>,
    /** Non-fatal problems met while parsing (corrupt segments, unknown structures). English, for display. */
    val warnings: List<String>,
) {
    val tagCount: Int get() = directories.sumOf { it.tags.size }
}

/** Broad family a directory belongs to; drives ordering, icons and strip categories. */
enum class DirectoryGroup {
    Exif,
    Gps,
    MakerNote,
    Xmp,
    Iptc,
    Icc,
    /** Container-level info: JPEG/JFIF, PNG chunks, WebP, HEIF boxes, RAW headers. */
    Container,
    /** File properties (name, size, type). */
    File,
    Other,
}

data class MetadataDirectory(
    /** Stable unique id within a report, e.g. "exif-ifd0", "exif-subifd", "gps", "makernote-fujifilm", "xmp", "icc". */
    val id: String,
    /** Display name in English, e.g. "Exif IFD0", "Fujifilm Makernote". */
    val name: String,
    val group: DirectoryGroup,
    val tags: List<MetadataTag>,
)

data class MetadataTag(
    /** Unique within a report; "${directoryId}:${id or xmp path}". */
    val key: String,
    val directoryId: String,
    /** Numeric tag id for TIFF/IPTC-style tags; null for XMP properties and synthetic fields. */
    val id: Int?,
    /** Tag name in English, e.g. "Exposure Time" or "dc:creator". */
    val name: String,
    /** Human-readable value, e.g. "1/250 sec", "f/1.8", "Fired, return detected". */
    val displayValue: String,
    /** Raw value as stored, e.g. "1/250", "18/10", "0x0019", array values space-separated. */
    val rawValue: String,
    /** Set when this tag can identify a person, place or device. */
    val sensitivity: SensitivityCategory? = null,
) {
    val hexId: String? get() = id?.let { "0x%04X".format(it) }
}

/** Kinds of privacy-sensitive metadata highlighted with badges and offered for stripping. */
enum class SensitivityCategory {
    /** GPS coordinates, altitude, place names, location in XMP/IPTC. */
    Location,
    /** Camera body, lens or other hardware serial numbers. */
    SerialNumber,
    /** Artist, owner, creator, copyright holder and other person names or contact details. */
    PersonName,
    /** Unique device or image identifiers (image unique id, device id, internal ids in MakerNotes). */
    DeviceIdentifier,
    /** Face or people regions and names (MWG/MP regions, Apple/Google face data). */
    People,
}

data class SensitiveFinding(val category: SensitivityCategory, val tagKeys: List<String>)

data class Rational(val numerator: Long, val denominator: Long) {
    val value: Double get() = if (denominator == 0L) Double.NaN else numerator.toDouble() / denominator
    override fun toString(): String = "$numerator/$denominator"
}

/** Where the UTC offset of [PhotoSummary.capturedAt] came from. */
enum class OffsetSource { ExifOffsetTime, Xmp, GpsTimestamp, MakerNote }

/** The facts shown in the viewer header. Every field is optional because files vary wildly. */
data class PhotoSummary(
    val make: String? = null,
    val model: String? = null,
    val lens: String? = null,
    val fNumber: Double? = null,
    val exposureTime: Rational? = null,
    val iso: Int? = null,
    val focalLengthMm: Double? = null,
    val focalLength35mm: Int? = null,
    val exposureBiasEv: Double? = null,
    val flashFired: Boolean? = null,
    val width: Int? = null,
    val height: Int? = null,
    /** EXIF orientation 1-8. */
    val orientation: Int? = null,
    /** Local capture time as recorded by the camera (DateTimeOriginal, falling back to other dates). */
    val capturedAt: LocalDateTime? = null,
    val utcOffset: ZoneOffset? = null,
    val utcOffsetSource: OffsetSource? = null,
    val software: String? = null,
    /** ICC profile description, e.g. "Display P3". */
    val colorProfile: String? = null,
)

data class GeoLocation(
    val latitude: Double,
    val longitude: Double,
    val altitudeMeters: Double? = null,
    /** Direction the camera faced, degrees from north. */
    val directionDegrees: Double? = null,
    val timestampUtc: Instant? = null,
)

enum class LocationStatus {
    /** Valid coordinates found. */
    Present,
    /** No location fields at all. */
    Absent,
    /** Location fields exist but were blanked (all zero / empty), as Android does when redacting. */
    Redacted,
}
