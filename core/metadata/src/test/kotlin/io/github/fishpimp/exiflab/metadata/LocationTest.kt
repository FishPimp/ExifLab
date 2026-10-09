package io.github.fishpimp.exiflab.metadata

import com.google.common.truth.Truth.assertThat
import io.github.fishpimp.exiflab.metadata.fixtures.Containers
import io.github.fishpimp.exiflab.metadata.fixtures.Photos
import io.github.fishpimp.exiflab.metadata.fixtures.TiffBuilder
import io.github.fishpimp.exiflab.metadata.fixtures.Xmp
import io.github.fishpimp.exiflab.metadata.model.LocationStatus
import io.github.fishpimp.exiflab.metadata.model.MetadataReport
import java.time.Instant
import org.junit.Test

class LocationTest {
    private fun read(gps: TiffBuilder.Ifd?, xmp: Map<String, String>? = null): MetadataReport {
        val tiff = TiffBuilder().build(Photos.ifd0(Photos.exifIfd(), gps))
        val segments = listOfNotNull(xmp?.let { Containers.xmpSegment(Xmp.packet(Xmp.description(it))) })
        return Photos.read(Photos.jpeg(tiff, *segments.toTypedArray()))
    }

    @Test fun northernEasternPosition() {
        val report = read(Photos.gpsIfd())
        assertThat(report.locationStatus).isEqualTo(LocationStatus.Present)
        val location = report.location!!
        assertThat(location.latitude).isWithin(1e-9).of(59 + 19 / 60.0 + 45 / 3600.0)
        assertThat(location.longitude).isWithin(1e-9).of(18 + 4 / 60.0 + 7 / 3600.0)
        assertThat(location.altitudeMeters).isNull()
    }

    @Test fun southernWesternPositionBelowSeaLevelWithDirectionAndTime() {
        val gps = Photos.gpsIfd(Triple(33, 51, 54), "S", Triple(151, 12, 36), "W") {
            byte(0x0005, 1)
            rational(0x0006, 125L to 10L)
            rational(0x0011, 2705L to 10L)
            rational(0x0007, 9L to 1L, 30L to 1L, 15L to 1L)
            ascii(0x001D, "2024:02:29")
        }
        val location = read(gps).location!!
        assertThat(location.latitude).isWithin(1e-9).of(-(33 + 51 / 60.0 + 54 / 3600.0))
        assertThat(location.longitude).isWithin(1e-9).of(-(151 + 12 / 60.0 + 36 / 3600.0))
        assertThat(location.altitudeMeters).isWithin(1e-9).of(-12.5)
        assertThat(location.directionDegrees).isWithin(1e-9).of(270.5)
        assertThat(location.timestampUtc).isEqualTo(Instant.parse("2024-02-29T09:30:15Z"))
    }

    @Test fun zeroedFieldsAreRedacted() {
        // What Android's MediaProvider hands out without ACCESS_MEDIA_LOCATION.
        val gps = TiffBuilder.ifd {
            byte(0x0000, 2, 2, 0, 0)
            ascii(0x0001, "")
            rational(0x0002, 0L to 0L, 0L to 0L, 0L to 0L)
            ascii(0x0003, "")
            rational(0x0004, 0L to 0L, 0L to 0L, 0L to 0L)
        }
        val report = read(gps)
        assertThat(report.locationStatus).isEqualTo(LocationStatus.Redacted)
        assertThat(report.location).isNull()
    }

    @Test fun exactlyZeroZeroIsRedacted() {
        val report = read(Photos.gpsIfd(Triple(0, 0, 0), "N", Triple(0, 0, 0), "E"))
        assertThat(report.locationStatus).isEqualTo(LocationStatus.Redacted)
    }

    @Test fun noLocationFieldsIsAbsent() {
        assertThat(read(null).locationStatus).isEqualTo(LocationStatus.Absent)
        // A GPS IFD with only a version and timestamp has no location fields either.
        val timeOnly = TiffBuilder.ifd { byte(0x0000, 2, 3, 0, 0); ascii(0x001D, "2024:05:01") }
        assertThat(read(timeOnly).locationStatus).isEqualTo(LocationStatus.Absent)
    }

    @Test fun xmpCoordinatesWhenExifHasNone() {
        val report = read(null, mapOf("exif:GPSLatitude" to "59,19.758N", "exif:GPSLongitude" to "18,4.1W", "exif:GPSAltitude" to "305/10"))
        assertThat(report.locationStatus).isEqualTo(LocationStatus.Present)
        val location = report.location!!
        assertThat(location.latitude).isWithin(1e-9).of(59 + 19.758 / 60)
        assertThat(location.longitude).isWithin(1e-9).of(-(18 + 4.1 / 60))
        assertThat(location.altitudeMeters).isWithin(1e-9).of(30.5)
    }

    @Test fun xmpLocationSurvivesRedactedExif() {
        val gps = Photos.gpsIfd(Triple(0, 0, 0), "", Triple(0, 0, 0), "")
        val report = read(gps, mapOf("exif:GPSLatitude" to "59,19,45N", "exif:GPSLongitude" to "18,4,7E"))
        assertThat(report.locationStatus).isEqualTo(LocationStatus.Present)
        assertThat(report.location!!.latitude).isWithin(1e-9).of(59 + 19 / 60.0 + 45 / 3600.0)
    }
}
