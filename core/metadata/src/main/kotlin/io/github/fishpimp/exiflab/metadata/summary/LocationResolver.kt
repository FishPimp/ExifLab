package io.github.fishpimp.exiflab.metadata.summary

import com.drew.lang.Rational
import com.drew.metadata.exif.GpsDirectory
import io.github.fishpimp.exiflab.metadata.model.GeoLocation
import io.github.fishpimp.exiflab.metadata.model.LocationStatus
import java.time.DateTimeException
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneOffset
import kotlin.math.abs

/** The decoded position, whether location data exists at all, and the GPS clock reading. */
internal data class LocationResult(val location: GeoLocation?, val status: LocationStatus, val gpsTimestamp: Instant?)

/**
 * Decodes the GPS position from the Exif GPS IFD, falling back to XMP exif:GPS* properties.
 *
 * Android's MediaProvider hands apps without the media-location permission a copy whose GPS
 * values are zeroed (0/0 rationals, empty references); such files report [LocationStatus.Redacted]
 * rather than [LocationStatus.Absent] so the app can explain why the location is missing.
 */
internal class LocationResolver(private val lookup: MetadataLookup) {
    private sealed interface Coordinate {
        data object Missing : Coordinate
        data object Blank : Coordinate
        data class Value(val degrees: Double) : Coordinate
    }

    private class Decoded(val location: GeoLocation?, val hasFields: Boolean)

    fun resolve(): LocationResult {
        val gps = lookup.all<GpsDirectory>()
        val gpsTimestamp = gps.firstNotNullOfOrNull(::timestamp)
        val candidates = gps.map(::decodeGps) + decodeXmp()
        val present = candidates.firstNotNullOfOrNull { it.location }
        val status = when {
            present != null -> LocationStatus.Present
            candidates.any { it.hasFields } -> LocationStatus.Redacted
            else -> LocationStatus.Absent
        }
        return LocationResult(present, status, gpsTimestamp)
    }

    private fun decodeGps(directory: GpsDirectory): Decoded {
        val hasFields = LOCATION_TAGS.any(directory::containsTag)
        val latitude = coordinate(directory.rationals(GpsDirectory.TAG_LATITUDE), directory.text(GpsDirectory.TAG_LATITUDE_REF), "S")
        val longitude = coordinate(directory.rationals(GpsDirectory.TAG_LONGITUDE), directory.text(GpsDirectory.TAG_LONGITUDE_REF), "W")
        val altitude = directory.double(GpsDirectory.TAG_ALTITUDE)?.let { meters ->
            if (directory.int(GpsDirectory.TAG_ALTITUDE_REF) == 1) -abs(meters) else meters
        }
        return Decoded(
            location = geoLocation(latitude, longitude, altitude, directory.double(GpsDirectory.TAG_IMG_DIRECTION), timestamp(directory)),
            hasFields = hasFields,
        )
    }

    private fun decodeXmp(): Decoded {
        val latitudeText = lookup.xmp("exif:GPSLatitude")
        val longitudeText = lookup.xmp("exif:GPSLongitude")
        val altitude = lookup.xmp("exif:GPSAltitude")?.let(::parseRationalText)?.let { meters ->
            if (lookup.xmp("exif:GPSAltitudeRef") == "1") -abs(meters) else meters
        }
        return Decoded(
            location = geoLocation(
                xmpCoordinate(latitudeText),
                xmpCoordinate(longitudeText),
                altitude,
                lookup.xmp("exif:GPSImgDirection")?.let(::parseRationalText),
                lookup.xmp("exif:GPSTimeStamp")?.let { MetadataDates.parseXmp(it) }
                    ?.let { it.local.toInstant(it.offset ?: ZoneOffset.UTC) },
            ),
            hasFields = latitudeText != null || longitudeText != null,
        )
    }

    private fun geoLocation(
        latitude: Coordinate,
        longitude: Coordinate,
        altitude: Double?,
        direction: Double?,
        timestamp: Instant?,
    ): GeoLocation? {
        val lat = (latitude as? Coordinate.Value)?.degrees ?: return null
        val lon = (longitude as? Coordinate.Value)?.degrees ?: return null
        // Exactly 0,0 is what blanked tags decode to; "null island" is never a real capture position.
        if (lat !in -90.0..90.0 || lon !in -180.0..180.0 || (lat == 0.0 && lon == 0.0)) return null
        return GeoLocation(lat, lon, altitude, direction?.takeIf { it in 0.0..360.0 }, timestamp)
    }

    /** Degrees, minutes, seconds rationals with an N/S or E/W reference. */
    private fun coordinate(parts: List<Rational>?, ref: String?, negativeRef: String): Coordinate {
        if (parts.isNullOrEmpty()) return Coordinate.Missing
        if (parts.all { it.numerator == 0L }) return Coordinate.Blank
        val values = parts.take(3).map { it.finiteValue() ?: return Coordinate.Blank }
        val degrees = values[0] + values.getOrElse(1) { 0.0 } / 60 + values.getOrElse(2) { 0.0 } / 3600
        return Coordinate.Value(if (ref.equals(negativeRef, ignoreCase = true)) -degrees else degrees)
    }

    /** XMP GPS coordinates: "59,19.758N", "59,19,45.5N" or plain signed decimal degrees. */
    private fun xmpCoordinate(text: String?): Coordinate {
        val value = text?.trim() ?: return Coordinate.Missing
        value.toDoubleOrNull()?.let { return Coordinate.Value(it) }
        val match = XMP_COORDINATE.matchEntire(value) ?: return Coordinate.Blank
        val (deg, min, sec, ref) = match.destructured
        val degrees = deg.toDouble() + min.toDouble() / 60 + (sec.toDoubleOrNull() ?: 0.0) / 3600
        return if (degrees == 0.0) Coordinate.Blank else Coordinate.Value(if (ref.uppercase() in NEGATIVE_REFS) -degrees else degrees)
    }

    /** GPSDateStamp ("2024:05:01") plus GPSTimeStamp (hour, minute, second rationals) as UTC. */
    private fun timestamp(directory: GpsDirectory): Instant? {
        val date = directory.text(GpsDirectory.TAG_DATE_STAMP)
            ?.let { DATE_STAMP.matchEntire(it) }
            ?.destructured
            ?.let { (y, m, d) ->
                try {
                    LocalDate.of(y.toInt(), m.toInt(), d.toInt())
                } catch (_: DateTimeException) {
                    null
                }
            } ?: return null
        val time = directory.rationals(GpsDirectory.TAG_TIME_STAMP)?.takeIf { it.size == 3 } ?: return null
        val (hours, minutes, seconds) = time.map { it.finiteValue() ?: return null }
        if (hours !in 0.0..<24.0 || minutes !in 0.0..<60.0 || seconds !in 0.0..<61.0) return null
        val nanos = ((hours * 3600 + minutes * 60 + seconds) * 1e9).toLong()
        return date.atStartOfDay().toInstant(ZoneOffset.UTC).plusNanos(nanos)
    }

    private fun parseRationalText(text: String): Double? {
        val parts = text.split('/')
        return when (parts.size) {
            1 -> parts[0].trim().toDoubleOrNull()
            2 -> {
                val numerator = parts[0].trim().toDoubleOrNull()
                val denominator = parts[1].trim().toDoubleOrNull()
                if (numerator == null || denominator == null || denominator == 0.0) null else numerator / denominator
            }
            else -> null
        }?.takeIf { it.isFinite() }
    }

    private companion object {
        val LOCATION_TAGS = intArrayOf(
            GpsDirectory.TAG_LATITUDE_REF, GpsDirectory.TAG_LATITUDE,
            GpsDirectory.TAG_LONGITUDE_REF, GpsDirectory.TAG_LONGITUDE,
        )
        val XMP_COORDINATE = Regex("""^(\d+(?:\.\d+)?),(\d+(?:\.\d+)?)(?:,(\d+(?:\.\d+)?))?([NSEWnsew])$""")
        val DATE_STAMP = Regex("""^(\d{4})[:\-](\d{2})[:\-](\d{2})$""")
        val NEGATIVE_REFS = setOf("S", "W")
    }
}
