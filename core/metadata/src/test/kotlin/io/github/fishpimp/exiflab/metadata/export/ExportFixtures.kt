package io.github.fishpimp.exiflab.metadata.export

import io.github.fishpimp.exiflab.metadata.ImageFormat
import io.github.fishpimp.exiflab.metadata.model.DirectoryGroup
import io.github.fishpimp.exiflab.metadata.model.GeoLocation
import io.github.fishpimp.exiflab.metadata.model.LocationStatus
import io.github.fishpimp.exiflab.metadata.model.MetadataDirectory
import io.github.fishpimp.exiflab.metadata.model.MetadataReport
import io.github.fishpimp.exiflab.metadata.model.MetadataTag
import io.github.fishpimp.exiflab.metadata.model.OffsetSource
import io.github.fishpimp.exiflab.metadata.model.PhotoSummary
import io.github.fishpimp.exiflab.metadata.model.Rational
import io.github.fishpimp.exiflab.metadata.model.SensitiveFinding
import io.github.fishpimp.exiflab.metadata.model.SensitivityCategory
import java.time.Instant
import java.time.LocalDateTime
import java.time.OffsetDateTime
import java.time.ZoneOffset

/** Hand-built reports with the awkward values exports have to survive. */
object ExportFixtures {
    val info = ExportInfo(appVersion = "0.1.0", exportedAt = OffsetDateTime.of(2026, 10, 12, 14, 3, 22, 0, ZoneOffset.ofHours(2)))

    fun tag(directoryId: String, id: Int?, name: String, value: String, raw: String = value, sensitivity: SensitivityCategory? = null) =
        MetadataTag(
            key = "$directoryId:${id?.let { "0x%04X".format(it) } ?: name}",
            directoryId = directoryId,
            id = id,
            name = name,
            displayValue = value,
            rawValue = raw,
            sensitivity = sensitivity,
        )

    val ifd0 = MetadataDirectory(
        id = "exif-ifd0",
        name = "Exif IFD0",
        group = DirectoryGroup.Exif,
        tags = listOf(
            tag("exif-ifd0", 0x010F, "Make", "FUJIFILM"),
            tag("exif-ifd0", 0x0110, "Model", "X-T5"),
            tag("exif-ifd0", 0x013B, "Artist", "Åsa \"Fotograf\" Öberg, Malmö", sensitivity = SensitivityCategory.PersonName),
            tag("exif-ifd0", 0x010E, "Image Description", "Line one\nLine two, with comma\r\nand \"quotes\""),
            tag("exif-ifd0", 0x9204, "Exposure Bias Value", "-1/3 EV", raw = "-1/3"),
        ),
    )

    val gps = MetadataDirectory(
        id = "gps",
        name = "GPS",
        group = DirectoryGroup.Gps,
        tags = listOf(
            tag("gps", 0x0002, "GPS Latitude", "55° 36' 21.12\"", raw = "55/1 36/1 2112/100", sensitivity = SensitivityCategory.Location),
            tag("gps", 0x0004, "GPS Longitude", "13° 0' 7.2\"", raw = "13/1 0/1 72/10", sensitivity = SensitivityCategory.Location),
        ),
    )

    val xmp = MetadataDirectory(
        id = "xmp",
        name = "XMP",
        group = DirectoryGroup.Xmp,
        tags = listOf(
            tag("xmp", null, "dc:title", "東京タワー 📷 night"),
            tag("xmp", null, "dc:description", "=HYPERLINK(\"http://example.com\")"),
            tag("xmp", null, "exif:Note", "tab\there, nul\u0000end, control\u0001, separator "),
        ),
    )

    val report = MetadataReport(
        format = ImageFormat.Jpeg,
        fileName = "DSCF0042.JPG",
        fileSize = 8_388_608,
        summary = PhotoSummary(
            make = "FUJIFILM",
            model = "X-T5",
            lens = "XF23mmF1.4 R LM WR",
            fNumber = 1.4,
            exposureTime = Rational(1, 250),
            iso = 125,
            focalLengthMm = 23.0,
            focalLength35mm = 35,
            exposureBiasEv = -0.33,
            flashFired = false,
            width = 7728,
            height = 5152,
            orientation = 1,
            capturedAt = LocalDateTime.of(2026, 6, 14, 17, 42, 0),
            utcOffset = ZoneOffset.ofHours(2),
            utcOffsetSource = OffsetSource.ExifOffsetTime,
            software = "Digital Camera X-T5 Ver2.00",
            colorProfile = "sRGB IEC61966-2.1",
        ),
        location = GeoLocation(55.6059, 13.002, altitudeMeters = 12.5, directionDegrees = 270.0, timestampUtc = Instant.parse("2026-06-14T15:42:00Z")),
        locationStatus = LocationStatus.Present,
        directories = listOf(ifd0, gps, xmp),
        sensitiveFindings = listOf(
            SensitiveFinding(SensitivityCategory.Location, gps.tags.map { it.key }),
            SensitiveFinding(SensitivityCategory.PersonName, listOf(ifd0.tags[2].key)),
        ),
        warnings = listOf("MakerNote truncated at offset 0x1F00"),
    )

    /** A file with almost nothing in it: no summary facts, no location, no offset. */
    val bare = MetadataReport(
        format = ImageFormat.Png,
        fileName = "screenshot, final.png",
        fileSize = null,
        summary = PhotoSummary(capturedAt = LocalDateTime.of(2025, 1, 2, 3, 4, 5)),
        location = null,
        locationStatus = LocationStatus.Absent,
        directories = listOf(
            MetadataDirectory("png-text", "PNG Text", DirectoryGroup.Container, listOf(tag("png-text", null, "Software", "Screenshot tool"))),
        ),
        sensitiveFindings = emptyList(),
        warnings = emptyList(),
    )
}
