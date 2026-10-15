package io.github.fishpimp.exiflab.metadata.edit

import com.google.common.truth.Truth.assertThat
import com.google.common.truth.Truth.assertWithMessage
import io.github.fishpimp.exiflab.metadata.edit.EditFixtures.IPTC_RECORD_VERSION
import io.github.fishpimp.exiflab.metadata.edit.EditFixtures.assertIdempotent
import io.github.fishpimp.exiflab.metadata.edit.EditFixtures.edit
import io.github.fishpimp.exiflab.metadata.edit.EditFixtures.fullJpeg
import io.github.fishpimp.exiflab.metadata.edit.EditFixtures.jpeg
import io.github.fishpimp.exiflab.metadata.edit.EditFixtures.plan
import io.github.fishpimp.exiflab.metadata.edit.EditFixtures.xmpTags
import io.github.fishpimp.exiflab.metadata.fixtures.Photos
import io.github.fishpimp.exiflab.metadata.model.DirectoryGroup
import io.github.fishpimp.exiflab.metadata.model.LocationStatus
import io.github.fishpimp.exiflab.metadata.write.WriteFixtures.assertUntouched
import kotlin.random.Random
import org.junit.Test

class LocationPlannerTest {
    @Test fun setLocationWritesTheGpsIfdAndExistingXmp() {
        val edited = edit(fullJpeg(), EditOperation.SetLocation(48.858222, 2.2945, altitudeMeters = 35.5))
        val location = edited.after.location!!
        assertThat(location.latitude).isWithin(1e-6).of(48.858222)
        assertThat(location.longitude).isWithin(1e-6).of(2.2945)
        assertThat(location.altitudeMeters).isWithin(1e-9).of(35.5)
        assertThat(edited.exif("gps", 0x0000)).isEqualTo("2 3 0 0")
        assertThat(edited.exif("gps", 0x0001)).isEqualTo("N")
        assertThat(edited.exif("gps", 0x0002)).isEqualTo("48/1 51/1 295992/10000")
        assertThat(edited.exif("gps", 0x0003)).isEqualTo("E")
        assertThat(edited.exif("gps", 0x0004)).isEqualTo("2/1 17/1 402000/10000")
        assertThat(edited.exif("gps", 0x0005)).isEqualTo("0")
        assertThat(edited.exif("gps", 0x0006)).isEqualTo("3550/100")
        assertThat(edited.exif("gps", 0x0012)).isEqualTo("WGS-84")
        assertThat(edited.xmp("exif:GPSLatitude")).isEqualTo("48,51.49332N")
        assertThat(edited.xmp("exif:GPSLongitude")).isEqualTo("2,17.6700E")
        assertThat(edited.xmp("exif:GPSAltitude")).isEqualTo("3550/100")
        assertThat(edited.xmp("exif:GPSAltitudeRef")).isEqualTo("0")

        val latitude = edited.diff("GPS Latitude")
        assertThat(latitude.group).isEqualTo(DirectoryGroup.Gps)
        assertThat(latitude.before).isEqualTo("59° 19' 45\"")
        assertThat(latitude.after).startsWith("48° 51' 29.6")
        assertThat(edited.diff("exif:GPSLatitude").after).isEqualTo("48,51.49332N")
        // Unchanged GPS tags (version, map datum, time stamps) are not in the diff.
        assertThat(edited.plan.diff.map { it.label }).containsNoneOf("GPS Version ID", "GPS Map Datum", "GPS Time-Stamp")
        assertUntouched(
            edited.before, edited.after,
            setOf("gps:2", "gps:4", "gps:6", "xmp:exif:GPSLatitude", "xmp:exif:GPSLongitude", "xmp:exif:GPSAltitude", "xmp:exif:GPSAltitudeRef"),
        )
        edited.assertIdempotent(EditOperation.SetLocation(48.858222, 2.2945, altitudeMeters = 35.5))
    }

    @Test fun negativeCoordinatesUseSouthAndWestReferences() {
        val edited = edit(jpeg(), EditOperation.SetLocation(-33.856784, -151.215297, altitudeMeters = -12.25))
        assertThat(edited.exif("gps", 0x0001)).isEqualTo("S")
        assertThat(edited.exif("gps", 0x0003)).isEqualTo("W")
        assertThat(edited.exif("gps", 0x0005)).isEqualTo("1")
        assertThat(edited.exif("gps", 0x0006)).isEqualTo("1225/100")
        val location = edited.after.location!!
        assertThat(location.latitude).isWithin(1e-6).of(-33.856784)
        assertThat(location.longitude).isWithin(1e-6).of(-151.215297)
        assertThat(location.altitudeMeters).isWithin(1e-9).of(-12.25)
        // The file had no XMP, so none is created for the coordinates.
        assertThat(edited.after.directories.map { it.id }).doesNotContain("xmp")
        assertThat(edited.plan.diff.map { it.kind }.distinct()).containsExactly(FieldDiff.Kind.Added)
    }

    @Test fun coordinatesRoundTripThroughTheWriterWithinAMicrodegree() {
        val random = Random(20261010)
        val points = listOf(0.25 to 0.5, 90.0 to 180.0, -90.0 to -180.0, 89.999999999 to 179.999999999, 1e-7 to -1e-7, 59.329444 to 18.068611) +
            List(40) { random.nextDouble(-90.0, 90.0) to random.nextDouble(-180.0, 180.0) }
        val source = jpeg(gps = Photos.gpsIfd())
        for ((latitude, longitude) in points) {
            val edited = edit(source, EditOperation.SetLocation(latitude, longitude))
            val location = edited.after.location
            assertWithMessage("$latitude, $longitude").that(location).isNotNull()
            assertWithMessage("latitude of $latitude, $longitude").that(location!!.latitude).isWithin(1e-6).of(latitude)
            assertWithMessage("longitude of $latitude, $longitude").that(location.longitude).isWithin(1e-6).of(longitude)
        }
    }

    @Test fun altitudeIsKeptOnlyWhileThePositionStays() {
        val source = jpeg(gps = Photos.gpsIfd { byte(0x0005, 0); rational(0x0006, 12345L to 100L) })
        val same = edit(source, EditOperation.SetLocation(59 + 19 / 60.0 + 45 / 3600.0, 18 + 4 / 60.0 + 7 / 3600.0))
        assertThat(same.exif("gps", 0x0006)).isEqualTo("12345/100")
        // The same position written with other rationals is not a change.
        assertThat(same.plan.diff.map { it.label }).containsNoneOf("GPS Altitude", "GPS Latitude", "GPS Longitude")
        assertThat(same.exif("gps", 0x0002)).isEqualTo("59/1 19/1 45/1")

        val moved = edit(source, EditOperation.SetLocation(60.0, 18.0))
        assertThat(moved.exif("gps", 0x0006)).isNull()
        assertThat(moved.exif("gps", 0x0005)).isNull()
        assertThat(moved.diff("GPS Altitude").kind).isEqualTo(FieldDiff.Kind.Removed)
        assertThat(moved.after.location!!.altitudeMeters).isNull()

        val whole = edit(source, EditOperation.SetLocation(60.0, 18.0, altitudeMeters = 300.0))
        assertThat(whole.exif("gps", 0x0006)).isEqualTo("300/1")
    }

    @Test fun outOfRangeCoordinatesWarnAndChangeNothing() {
        for (operation in listOf(
            EditOperation.SetLocation(90.5, 0.0),
            EditOperation.SetLocation(0.0, -180.01),
            EditOperation.SetLocation(Double.NaN, 0.0),
            EditOperation.SetLocation(10.0, 10.0, altitudeMeters = Double.POSITIVE_INFINITY),
        )) {
            val plan = plan(fullJpeg(), operation)
            assertWithMessage("$operation").that(plan.changes.isEmpty).isTrue()
            assertWithMessage("$operation").that(plan.diff).isEmpty()
            assertWithMessage("$operation").that(plan.warnings).hasSize(1)
        }
        assertThat(plan(fullJpeg(), EditOperation.SetLocation(90.5, 0.0)).warnings).containsExactly(PlanWarning.LatitudeOutOfRange(90.5))
        assertThat(plan(fullJpeg(), EditOperation.SetLocation(0.0, -180.01)).warnings).containsExactly(PlanWarning.LongitudeOutOfRange(-180.01))
        assertThat(plan(fullJpeg(), EditOperation.SetLocation(10.0, 10.0, altitudeMeters = 1e7)).warnings)
            .containsExactly(PlanWarning.AltitudeImplausible(1e7))
    }

    @Test fun removeLocationDropsGpsAndPlaceNames() {
        val edited = edit(fullJpeg(), EditOperation.RemoveLocation(includePlaceNames = true))
        assertThat(edited.after.directories.map { it.id }).doesNotContain("gps")
        assertThat(edited.after.locationStatus).isEqualTo(LocationStatus.Absent)
        assertThat(edited.after.xmpTags().map { it.name }.filter { it.startsWith("exif:GPS") }).isEmpty()
        assertThat(edited.xmp("photoshop:City")).isNull()
        assertThat(edited.xmp("photoshop:Country")).isNull()
        assertThat(edited.iptc(90)).isNull()
        assertThat(edited.iptc(101)).isNull()

        val gps = edited.diff("GPS data")
        assertThat(gps.before).isEqualTo("10 tags")
        assertThat(gps.kind).isEqualTo(FieldDiff.Kind.Removed)
        // The coordinates are listed; the rest of the GPS IFD is covered by the block line.
        assertThat(edited.diff("GPS Latitude").kind).isEqualTo(FieldDiff.Kind.Removed)
        assertThat(edited.diff("GPS Altitude").before).isEqualTo("123.45 metres")
        assertThat(edited.plan.diff.map { it.label }).containsNoneOf("GPS Version ID", "GPS Map Datum")
        assertThat(edited.plan.diff.map { it.label }).containsAtLeast("exif:GPSLatitude", "exif:GPSTimeStamp", "photoshop:City", "City", "Country/Primary Location Name")
        assertUntouched(
            edited.before, edited.after,
            setOf(
                "gps:", "exif-ifd0:34853", "xmp:exif:GPSLatitude", "xmp:exif:GPSLongitude", "xmp:exif:GPSTimeStamp", "xmp:photoshop:City",
                "xmp:photoshop:Country", "iptc:602", "iptc:613", IPTC_RECORD_VERSION,
            ),
        )
        edited.assertIdempotent(EditOperation.RemoveLocation(includePlaceNames = true))
    }

    @Test fun removeLocationCanKeepPlaceNames() {
        val edited = edit(fullJpeg(), EditOperation.RemoveLocation(includePlaceNames = false))
        assertThat(edited.after.location).isNull()
        assertThat(edited.xmp("photoshop:City")).isEqualTo("Stockholm")
        assertThat(edited.iptc(90)).isEqualTo("Stockholm")
    }

    @Test fun removingAMissingLocationIsANoOp() {
        val plan = plan(jpeg(), EditOperation.RemoveLocation())
        assertThat(plan.changes.isEmpty).isTrue()
        assertThat(plan.diff).isEmpty()
    }

    @Test fun locationCanBeReplacedAfterItWasRemovedInTheSamePlan() {
        val edited = edit(fullJpeg(), EditOperation.RemoveLocation(includePlaceNames = false), EditOperation.SetLocation(1.5, 2.5))
        assertThat(edited.after.location!!.latitude).isWithin(1e-9).of(1.5)
        // The old IFD went with its pointer, so stale tags such as the GPS time stamp are gone too.
        assertThat(edited.exif("gps", 0x0007)).isNull()
        assertThat(edited.exif("gps", 0x0000)).isEqualTo("2 3 0 0")
        assertThat(edited.xmp("exif:GPSLatitude")).isEqualTo("1,30.0000N")
        assertThat(edited.xmp("exif:GPSTimeStamp")).isNull()
    }
}
