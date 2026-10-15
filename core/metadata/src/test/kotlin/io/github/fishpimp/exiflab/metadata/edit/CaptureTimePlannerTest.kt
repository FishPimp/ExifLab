package io.github.fishpimp.exiflab.metadata.edit

import com.google.common.truth.Truth.assertThat
import io.github.fishpimp.exiflab.metadata.edit.EditFixtures.IPTC_RECORD_VERSION
import io.github.fishpimp.exiflab.metadata.edit.EditFixtures.assertIdempotent
import io.github.fishpimp.exiflab.metadata.edit.EditFixtures.edit
import io.github.fishpimp.exiflab.metadata.edit.EditFixtures.fullJpeg
import io.github.fishpimp.exiflab.metadata.edit.EditFixtures.jpeg
import io.github.fishpimp.exiflab.metadata.edit.EditFixtures.plan
import io.github.fishpimp.exiflab.metadata.fixtures.Photos
import io.github.fishpimp.exiflab.metadata.fixtures.TiffBuilder
import io.github.fishpimp.exiflab.metadata.model.DirectoryGroup
import io.github.fishpimp.exiflab.metadata.write.WriteFixtures.assertUntouched
import java.time.Duration
import java.time.LocalDateTime
import java.time.ZoneId
import java.time.ZoneOffset
import org.junit.Test

class CaptureTimePlannerTest {
    private val stockholm = ZoneId.of("Europe/Stockholm")
    private val tokyo = ZoneId.of("Asia/Tokyo")
    private val newYork = ZoneId.of("America/New_York")
    private val sydney = ZoneId.of("Australia/Sydney")

    /** A JPEG whose only dates are DateTimeOriginal/Digitized at [exifDate] (IFD0 DateTime too, as phones write it). */
    private fun dated(exifDate: String, subSec: String? = null, offset: String? = null, gps: TiffBuilder.Ifd? = null, xmp: Map<String, String>? = null, iptc: List<Pair<Int, String>> = emptyList()) =
        jpeg(
            exif = TiffBuilder.ifd {
                undefined(Photos.EXIF_VERSION, "0232".toByteArray())
                ascii(Photos.DATE_TIME_ORIGINAL, exifDate)
                ascii(Photos.DATE_TIME_DIGITIZED, exifDate)
                subSec?.let { ascii(Photos.SUB_SEC_TIME_ORIGINAL, it) }
                offset?.let { ascii(Photos.OFFSET_TIME_ORIGINAL, it) }
            },
            gps = gps,
            xmp = xmp,
            iptc = iptc,
        ) { ascii(Photos.DATE_TIME, exifDate) }

    // ---- SetCaptureTime ----

    @Test fun setCaptureTimeWritesEveryDateCopyWithOffsets() {
        val time = LocalDateTime.of(2026, 6, 14, 17, 42, 7, 500_000_000)
        val edited = edit(fullJpeg(), EditOperation.SetCaptureTime(time, ZoneOffset.ofHours(9), alsoModifyDate = true))

        assertThat(edited.exif("exif-subifd", 0x9003)).isEqualTo("2026:06:14 17:42:07")
        assertThat(edited.exif("exif-subifd", 0x9004)).isEqualTo("2026:06:14 17:42:07")
        assertThat(edited.exif("exif-ifd0", 0x0132)).isEqualTo("2026:06:14 17:42:07")
        // Existing SubSecTime tags keep their three digits; a new one gets what it needs.
        assertThat(edited.exif("exif-subifd", 0x9291)).isEqualTo("500")
        assertThat(edited.exif("exif-subifd", 0x9292)).isEqualTo("500")
        assertThat(edited.exif("exif-subifd", 0x9290)).isEqualTo("5")
        for (tag in listOf(0x9010, 0x9011, 0x9012)) assertThat(edited.exif("exif-subifd", tag)).isEqualTo("+09:00")
        assertThat(edited.xmp("exif:DateTimeOriginal")).isEqualTo("2026-06-14T17:42:07.5+09:00")
        assertThat(edited.xmp("xmp:CreateDate")).isEqualTo("2026-06-14T17:42:07.5+09:00")
        assertThat(edited.xmp("xmp:ModifyDate")).isEqualTo("2026-06-14T17:42:07.5+09:00")
        assertThat(edited.xmp("photoshop:DateCreated")).isEqualTo("2026-06-14T17:42:07.5+09:00")
        assertThat(edited.iptc(55)).isEqualTo("20260614")
        assertThat(edited.iptc(60)).isEqualTo("174207+0900")
        assertThat(edited.after.summary.capturedAt).isEqualTo(time)
        assertThat(edited.after.summary.utcOffset).isEqualTo(ZoneOffset.ofHours(9))

        val original = edited.diff("Date/Time Original")
        assertThat(original.before).isEqualTo("2024:05:01 14:03:22")
        assertThat(original.after).isEqualTo("2026:06:14 17:42:07")
        assertThat(original.group).isEqualTo(DirectoryGroup.Exif)
        assertThat(edited.diff("Sub-Sec Time").kind).isEqualTo(FieldDiff.Kind.Added)
        assertThat(edited.diff("xmp:CreateDate").after).isEqualTo("2026-06-14T17:42:07.5+09:00")
        assertThat(edited.diff("Time Created").after).isEqualTo("17:42:07+0900")
        assertThat(edited.plan.warnings).isEmpty()

        assertUntouched(
            edited.before, edited.after,
            setOf(
                "exif-ifd0:306", "exif-subifd:36867", "exif-subifd:36868", "exif-subifd:36880", "exif-subifd:36881", "exif-subifd:36882",
                "exif-subifd:37520", "exif-subifd:37521", "exif-subifd:37522", "xmp:exif:DateTimeOriginal", "xmp:xmp:CreateDate",
                "xmp:xmp:ModifyDate", "xmp:photoshop:DateCreated", "iptc:567", "iptc:572", IPTC_RECORD_VERSION,
            ),
        )
        edited.assertIdempotent(EditOperation.SetCaptureTime(time, ZoneOffset.ofHours(9), alsoModifyDate = true))
    }

    @Test fun setCaptureTimeWithoutOffsetKeepsOffsetsAndDropsStaleSubSeconds() {
        val time = LocalDateTime.of(2026, 6, 14, 17, 42, 7)
        val edited = edit(fullJpeg(), EditOperation.SetCaptureTime(time, offset = null))

        assertThat(edited.exif("exif-subifd", 0x9003)).isEqualTo("2026:06:14 17:42:07")
        assertThat(edited.exif("exif-subifd", 0x9291)).isNull()
        assertThat(edited.exif("exif-subifd", 0x9292)).isNull()
        assertThat(edited.exif("exif-subifd", 0x9011)).isEqualTo("+02:00")
        // IFD0 DateTime and xmp:ModifyDate record the modification, not the capture.
        assertThat(edited.exif("exif-ifd0", 0x0132)).isEqualTo("2024:05:01 14:03:22")
        assertThat(edited.xmp("xmp:ModifyDate")).isEqualTo("2024-05-01T14:03:22+02:00")
        // XMP keeps the offset it had; the date-only photoshop:DateCreated becomes a full date-time.
        assertThat(edited.xmp("xmp:CreateDate")).isEqualTo("2026-06-14T17:42:07+02:00")
        assertThat(edited.xmp("photoshop:DateCreated")).isEqualTo("2026-06-14T17:42:07")
        assertThat(edited.iptc(60)).isEqualTo("174207+0200")
        assertThat(edited.diff("Sub-Sec Time Original").kind).isEqualTo(FieldDiff.Kind.Removed)
    }

    @Test fun setCaptureTimeCreatesExifWithAVersionThatKnowsOffsets() {
        val edited = edit(jpeg(exif = null), EditOperation.SetCaptureTime(LocalDateTime.of(2026, 1, 2, 3, 4, 5), ZoneOffset.ofHoursMinutes(-3, -30)))
        assertThat(edited.exif("exif-subifd", 0x9003)).isEqualTo("2026:01:02 03:04:05")
        assertThat(edited.exif("exif-subifd", 0x9004)).isEqualTo("2026:01:02 03:04:05")
        assertThat(edited.exif("exif-subifd", 0x9011)).isEqualTo("-03:30")
        assertThat(edited.exif("exif-subifd", 0x9000)).isEqualTo("30 32 33 32")
        assertThat(edited.exif("exif-ifd0", 0x0132)).isNull()
        // No XMP or IPTC is created just to mirror the date.
        assertThat(edited.after.directories.map { it.id }).containsNoneOf("xmp", "iptc")
        assertThat(edited.after.summary.utcOffset).isEqualTo(ZoneOffset.ofHoursMinutes(-3, -30))
    }

    @Test fun offsetTagsBumpAnOldExifVersion() {
        fun withVersion(version: String) = jpeg(exif = TiffBuilder.ifd {
            undefined(Photos.EXIF_VERSION, version.toByteArray())
            ascii(Photos.DATE_TIME_ORIGINAL, "2024:05:01 14:03:22")
        })
        val old = edit(withVersion("0221"), EditOperation.SetUtcOffset(ZoneOffset.ofHours(1)))
        assertThat(old.exif("exif-subifd", 0x9000)).isEqualTo("30 32 33 32")
        assertThat(old.diff("Exif Version").before).isEqualTo("2.21")
        assertThat(old.diff("Exif Version").after).isEqualTo("2.32")
        assertThat(old.exif("exif-subifd", 0x9011)).isEqualTo("+01:00")

        val current = edit(withVersion("0231"), EditOperation.SetUtcOffset(ZoneOffset.ofHours(1)))
        assertThat(current.exif("exif-subifd", 0x9000)).isEqualTo("30 32 33 31")
        // IFD0 DateTime exists too, so its OffsetTime is written as well.
        assertThat(current.plan.diff.map { it.label }).containsExactly("Time Zone Original", "Time Zone")
    }

    // ---- ShiftCaptureTime ----

    @Test fun shiftCrossesMidnightMonthAndYearInEveryCopy() {
        val gps = Photos.gpsIfd {
            rational(0x0007, 22L to 1L, 30L to 1L, 0L to 1L)
            ascii(0x001D, "2024:12:31")
        }
        val source = dated(
            "2024:12:31 23:30:00", subSec = "123", offset = "+01:00", gps = gps,
            xmp = mapOf(
                "exif:DateTimeOriginal" to "2024-12-31T23:30:00.123+01:00",
                "photoshop:DateCreated" to "2024-12-31",
                "xmp:ModifyDate" to "2024-12-31T23:30+01:00",
                "exif:GPSTimeStamp" to "2024-12-31T22:30:00Z",
            ),
            iptc = listOf(55 to "20241231", 60 to "233000+0100"),
        )
        val edited = edit(source, EditOperation.ShiftCaptureTime(Duration.ofMinutes(45)))

        assertThat(edited.exif("exif-subifd", 0x9003)).isEqualTo("2025:01:01 00:15:00")
        assertThat(edited.exif("exif-subifd", 0x9004)).isEqualTo("2025:01:01 00:15:00")
        assertThat(edited.exif("exif-ifd0", 0x0132)).isEqualTo("2025:01:01 00:15:00")
        assertThat(edited.exif("exif-subifd", 0x9291)).isEqualTo("123")
        assertThat(edited.exif("exif-subifd", 0x9011)).isEqualTo("+01:00")
        assertThat(edited.exif("gps", 0x001D)).isEqualTo("2024:12:31")
        assertThat(edited.exif("gps", 0x0007)).isEqualTo("23/1 15/1 0/1")
        assertThat(edited.xmp("exif:DateTimeOriginal")).isEqualTo("2025-01-01T00:15:00.123+01:00")
        assertThat(edited.xmp("photoshop:DateCreated")).isEqualTo("2025-01-01")
        assertThat(edited.xmp("xmp:ModifyDate")).isEqualTo("2025-01-01T00:15+01:00")
        assertThat(edited.xmp("exif:GPSTimeStamp")).isEqualTo("2024-12-31T23:15:00Z")
        assertThat(edited.iptc(55)).isEqualTo("20250101")
        assertThat(edited.iptc(60)).isEqualTo("001500+0100")
    }

    @Test fun negativeShiftCrossesALeapDayAndMovesGpsDate() {
        val gps = Photos.gpsIfd {
            rational(0x0007, 0L to 1L, 30L to 1L, 1525L to 100L)
            ascii(0x001D, "2024:03:01")
        }
        val edited = edit(dated("2024:03:01 01:00:00", gps = gps), EditOperation.ShiftCaptureTime(Duration.ofHours(-26)))
        assertThat(edited.exif("exif-subifd", 0x9003)).isEqualTo("2024:02:28 23:00:00")
        assertThat(edited.exif("gps", 0x001D)).isEqualTo("2024:02:28")
        assertThat(edited.exif("gps", 0x0007)).isEqualTo("22/1 30/1 15250/1000")
    }

    @Test fun shiftCarriesSubSecondsIntoSeconds() {
        val edited = edit(dated("2024:05:01 14:03:22", subSec = "123"), EditOperation.ShiftCaptureTime(Duration.ofMillis(1500)))
        assertThat(edited.exif("exif-subifd", 0x9003)).isEqualTo("2024:05:01 14:03:23")
        assertThat(edited.exif("exif-subifd", 0x9291)).isEqualTo("623")
        // DateTimeDigitized had no sub-seconds before; it gains the half second.
        assertThat(edited.exif("exif-subifd", 0x9004)).isEqualTo("2024:05:01 14:03:23")
        assertThat(edited.exif("exif-subifd", 0x9292)).isEqualTo("5")
        assertThat(edited.after.summary.capturedAt).isEqualTo(LocalDateTime.of(2024, 5, 1, 14, 3, 23, 623_000_000))
    }

    @Test fun shiftWithoutAnyDateWarnsAndChangesNothing() {
        val plan = plan(jpeg(exif = null), EditOperation.ShiftCaptureTime(Duration.ofHours(1)))
        assertThat(plan.warnings).containsExactly(PlanWarning.NoCaptureDate(DateAction.Shift))
        assertThat(plan.changes.isEmpty).isTrue()
        assertThat(plan.diff).isEmpty()
        assertThat(plan(fullJpeg(), EditOperation.ShiftCaptureTime(Duration.ZERO)).changes.isEmpty).isTrue()
    }

    // ---- ChangeTimeZone ----

    @Test fun tokyoCameraClockInStockholmSummer() {
        val source = dated(
            "2026:06:14 22:00:00", offset = "+09:00",
            gps = Photos.gpsIfd { rational(0x0007, 13L to 1L, 0L to 1L, 0L to 1L); ascii(0x001D, "2026:06:14") },
            xmp = mapOf("xmp:CreateDate" to "2026-06-14T22:00:00+09:00", "photoshop:DateCreated" to "2026-06-14"),
            iptc = listOf(55 to "20260614", 60 to "220000+0900"),
        )
        val edited = edit(source, EditOperation.ChangeTimeZone(tokyo, stockholm))
        assertThat(edited.exif("exif-subifd", 0x9003)).isEqualTo("2026:06:14 15:00:00")
        assertThat(edited.exif("exif-ifd0", 0x0132)).isEqualTo("2026:06:14 15:00:00")
        for (tag in listOf(0x9010, 0x9011, 0x9012)) assertThat(edited.exif("exif-subifd", tag)).isEqualTo("+02:00")
        assertThat(edited.xmp("xmp:CreateDate")).isEqualTo("2026-06-14T15:00:00+02:00")
        assertThat(edited.xmp("photoshop:DateCreated")).isEqualTo("2026-06-14")
        assertThat(edited.iptc(60)).isEqualTo("150000+0200")
        // GPS time is UTC and was right all along.
        assertThat(edited.exif("gps", 0x0007)).isEqualTo("13/1 0/1 0/1")
        assertThat(edited.exif("gps", 0x001D)).isEqualTo("2026:06:14")
        assertThat(edited.after.summary.utcOffset).isEqualTo(ZoneOffset.ofHours(2))
        edited.assertIdempotent(EditOperation.SetUtcOffset(ZoneOffset.ofHours(2)))
    }

    @Test fun newYorkCameraInWinterAndSummer() {
        val winter = edit(dated("2026:01:15 08:00:00"), EditOperation.ChangeTimeZone(newYork, stockholm))
        assertThat(winter.exif("exif-subifd", 0x9003)).isEqualTo("2026:01:15 14:00:00")
        assertThat(winter.exif("exif-subifd", 0x9011)).isEqualTo("+01:00")

        val summer = edit(dated("2026:07:15 08:00:00"), EditOperation.ChangeTimeZone(newYork, stockholm))
        assertThat(summer.exif("exif-subifd", 0x9003)).isEqualTo("2026:07:15 14:00:00")
        assertThat(summer.exif("exif-subifd", 0x9011)).isEqualTo("+02:00")

        // The weeks in March when only New York has switched to daylight saving time.
        val march = edit(dated("2026:03:20 08:00:00"), EditOperation.ChangeTimeZone(newYork, stockholm))
        assertThat(march.exif("exif-subifd", 0x9003)).isEqualTo("2026:03:20 13:00:00")
        assertThat(march.exif("exif-subifd", 0x9011)).isEqualTo("+01:00")
    }

    @Test fun tokyoToStockholmAcrossTheSpringDaylightSavingChange() {
        // Stockholm moves to summer time at 01:00 UTC on 29 March 2026.
        val before = edit(dated("2026:03:29 09:30:00"), EditOperation.ChangeTimeZone(tokyo, stockholm))
        assertThat(before.exif("exif-subifd", 0x9003)).isEqualTo("2026:03:29 01:30:00")
        assertThat(before.exif("exif-subifd", 0x9011)).isEqualTo("+01:00")
        val after = edit(dated("2026:03:29 10:30:00"), EditOperation.ChangeTimeZone(tokyo, stockholm))
        assertThat(after.exif("exif-subifd", 0x9003)).isEqualTo("2026:03:29 03:30:00")
        assertThat(after.exif("exif-subifd", 0x9011)).isEqualTo("+02:00")
    }

    @Test fun sydneyDaylightSavingIsInTheSouthernSummer() {
        val january = edit(dated("2026:01:10 02:00:00"), EditOperation.ChangeTimeZone(stockholm, sydney))
        assertThat(january.exif("exif-subifd", 0x9003)).isEqualTo("2026:01:10 12:00:00")
        assertThat(january.exif("exif-subifd", 0x9011)).isEqualTo("+11:00")
        val july = edit(dated("2026:07:10 02:00:00"), EditOperation.ChangeTimeZone(stockholm, sydney))
        assertThat(july.exif("exif-subifd", 0x9003)).isEqualTo("2026:07:10 10:00:00")
        assertThat(july.exif("exif-subifd", 0x9011)).isEqualTo("+10:00")
    }

    @Test fun timeZoneChangeMovesTheDateAndKeepsSubSeconds() {
        val edited = edit(
            dated("2026:06:14 23:30:00", subSec = "250", xmp = mapOf("photoshop:DateCreated" to "2026-06-14")),
            EditOperation.ChangeTimeZone(ZoneId.of("UTC"), tokyo),
        )
        assertThat(edited.exif("exif-subifd", 0x9003)).isEqualTo("2026:06:15 08:30:00")
        assertThat(edited.exif("exif-subifd", 0x9291)).isEqualTo("250")
        assertThat(edited.exif("exif-subifd", 0x9011)).isEqualTo("+09:00")
        // A date without a time follows the capture date.
        assertThat(edited.xmp("photoshop:DateCreated")).isEqualTo("2026-06-15")
    }

    @Test fun wallTimesInADaylightSavingGapMoveForward() {
        // 02:30 does not exist in Stockholm on 29 March 2026; it is read as 03:30 CEST (01:30 UTC).
        val edited = edit(dated("2026:03:29 02:30:00"), EditOperation.ChangeTimeZone(stockholm, ZoneId.of("UTC")))
        assertThat(edited.exif("exif-subifd", 0x9003)).isEqualTo("2026:03:29 01:30:00")
        assertThat(edited.exif("exif-subifd", 0x9011)).isEqualTo("+00:00")
    }

    @Test fun timeZoneChangeWithoutDatesWarns() {
        val plan = plan(jpeg(exif = null), EditOperation.ChangeTimeZone(tokyo, stockholm))
        assertThat(plan.warnings).containsExactly(PlanWarning.NoCaptureDate(DateAction.Convert))
        assertThat(plan.changes.isEmpty).isTrue()
    }

    // ---- SetUtcOffset ----

    @Test fun setUtcOffsetRecordsTheOffsetWithoutShifting() {
        val edited = edit(fullJpeg(), EditOperation.SetUtcOffset(ZoneOffset.ofHours(-5)))
        assertThat(edited.exif("exif-subifd", 0x9003)).isEqualTo("2024:05:01 14:03:22")
        for (tag in listOf(0x9010, 0x9011, 0x9012)) assertThat(edited.exif("exif-subifd", tag)).isEqualTo("-05:00")
        assertThat(edited.xmp("exif:DateTimeOriginal")).isEqualTo("2024-05-01T14:03:22.123-05:00")
        assertThat(edited.xmp("xmp:ModifyDate")).isEqualTo("2024-05-01T14:03:22-05:00")
        // A date without a time has no offset to set.
        assertThat(edited.xmp("photoshop:DateCreated")).isEqualTo("2024-05-01")
        assertThat(edited.iptc(60)).isEqualTo("140322-0500")
        assertThat(edited.after.summary.utcOffset).isEqualTo(ZoneOffset.ofHours(-5))
        assertUntouched(
            edited.before, edited.after,
            setOf(
                "exif-subifd:36880", "exif-subifd:36881", "exif-subifd:36882", "xmp:exif:DateTimeOriginal", "xmp:xmp:CreateDate",
                "xmp:xmp:ModifyDate", "iptc:572", IPTC_RECORD_VERSION,
            ),
        )
        edited.assertIdempotent(EditOperation.SetUtcOffset(ZoneOffset.ofHours(-5)))
    }

    @Test fun setUtcOffsetWithoutDatesWarns() {
        val plan = plan(jpeg(exif = null), EditOperation.SetUtcOffset(ZoneOffset.UTC))
        assertThat(plan.warnings).containsExactly(PlanWarning.NoCaptureDate(DateAction.SetZone))
        assertThat(plan.changes.isEmpty).isTrue()
    }

    @Test fun laterOperationsSeeEarlierOnes() {
        val edited = edit(
            fullJpeg(),
            EditOperation.SetCaptureTime(LocalDateTime.of(2026, 6, 14, 22, 0), ZoneOffset.ofHours(9)),
            EditOperation.ChangeTimeZone(tokyo, stockholm),
            EditOperation.ShiftCaptureTime(Duration.ofMinutes(-5)),
        )
        assertThat(edited.exif("exif-subifd", 0x9003)).isEqualTo("2026:06:14 14:55:00")
        assertThat(edited.exif("exif-subifd", 0x9011)).isEqualTo("+02:00")
        assertThat(edited.xmp("exif:DateTimeOriginal")).isEqualTo("2026-06-14T14:55:00+02:00")
        // One diff line per field, from the original value to the final one.
        assertThat(edited.plan.diff.count { it.label == "Date/Time Original" }).isEqualTo(1)
        assertThat(edited.diff("Date/Time Original").before).isEqualTo("2024:05:01 14:03:22")
    }
}
