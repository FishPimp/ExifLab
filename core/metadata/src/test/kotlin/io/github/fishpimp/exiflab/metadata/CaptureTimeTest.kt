package io.github.fishpimp.exiflab.metadata

import com.google.common.truth.Truth.assertThat
import io.github.fishpimp.exiflab.metadata.fixtures.Containers
import io.github.fishpimp.exiflab.metadata.fixtures.Photos
import io.github.fishpimp.exiflab.metadata.fixtures.TiffBuilder
import io.github.fishpimp.exiflab.metadata.fixtures.Xmp
import io.github.fishpimp.exiflab.metadata.model.OffsetSource
import io.github.fishpimp.exiflab.metadata.model.PhotoSummary
import java.time.LocalDateTime
import java.time.ZoneOffset
import org.junit.Test

class CaptureTimeTest {
    private fun summary(
        ifd0: TiffBuilder.Ifd.() -> Unit = {},
        exif: TiffBuilder.Ifd.() -> Unit = {},
        gps: TiffBuilder.Ifd? = null,
        makerNote: TiffBuilder.Ifd? = null,
        xmp: Map<String, String>? = null,
    ): PhotoSummary {
        val exifIfd = TiffBuilder.ifd {
            exif()
            makerNote?.let { makerNoteIfd(Photos.MAKER_NOTE, it) }
        }
        val root = TiffBuilder.ifd {
            ascii(Photos.MAKE, if (makerNote != null) "Canon" else "Google")
            subIfd(Photos.EXIF_IFD, exifIfd)
            gps?.let { subIfd(Photos.GPS_IFD, it) }
            ifd0()
        }
        val segments = listOfNotNull(xmp?.let { Containers.xmpSegment(Xmp.packet(Xmp.description(it))) })
        return Photos.read(Photos.jpeg(TiffBuilder().build(root), *segments.toTypedArray())).summary
    }

    @Test fun dateTimeOriginalWithSubSecondsAndOffset() {
        val summary = summary(exif = {
            ascii(Photos.DATE_TIME_ORIGINAL, "2023:12:24 18:30:05")
            ascii(Photos.SUB_SEC_TIME_ORIGINAL, "5")
            ascii(Photos.OFFSET_TIME_ORIGINAL, "-05:00")
        })
        assertThat(summary.capturedAt).isEqualTo(LocalDateTime.of(2023, 12, 24, 18, 30, 5, 500_000_000))
        assertThat(summary.utcOffset).isEqualTo(ZoneOffset.ofHours(-5))
        assertThat(summary.utcOffsetSource).isEqualTo(OffsetSource.ExifOffsetTime)
    }

    @Test fun digitizedBeatsModificationDate() {
        val summary = summary(
            ifd0 = { ascii(Photos.DATE_TIME, "2024:01:02 10:00:00") },
            exif = { ascii(Photos.DATE_TIME_DIGITIZED, "2024:01:01 09:00:00") },
        )
        assertThat(summary.capturedAt).isEqualTo(LocalDateTime.of(2024, 1, 1, 9, 0))
    }

    @Test fun fallsBackToDateTimeAndItsOffset() {
        val summary = summary(
            ifd0 = { ascii(Photos.DATE_TIME, "2024:01:02 10:00:00") },
            exif = { ascii(Photos.OFFSET_TIME, "+05:30") },
        )
        assertThat(summary.capturedAt).isEqualTo(LocalDateTime.of(2024, 1, 2, 10, 0))
        assertThat(summary.utcOffset).isEqualTo(ZoneOffset.ofHoursMinutes(5, 30))
    }

    @Test fun placeholderDatesAreIgnored() {
        val summary = summary(
            ifd0 = { ascii(Photos.DATE_TIME, "2024:01:02 10:00:00") },
            exif = { ascii(Photos.DATE_TIME_ORIGINAL, "0000:00:00 00:00:00") },
        )
        assertThat(summary.capturedAt).isEqualTo(LocalDateTime.of(2024, 1, 2, 10, 0))
    }

    @Test fun xmpDateWhenExifHasNone() {
        val summary = summary(xmp = mapOf("photoshop:DateCreated" to "2022-07-14T08:15:30.25+01:00"))
        assertThat(summary.capturedAt).isEqualTo(LocalDateTime.of(2022, 7, 14, 8, 15, 30, 250_000_000))
        assertThat(summary.utcOffset).isEqualTo(ZoneOffset.ofHours(1))
        assertThat(summary.utcOffsetSource).isEqualTo(OffsetSource.Xmp)
    }

    @Test fun xmpOffsetAppliesOnlyToTheSameLocalTime() {
        val matching = summary(
            exif = { ascii(Photos.DATE_TIME_ORIGINAL, "2022:07:14 08:15:30") },
            xmp = mapOf("xmp:CreateDate" to "2022-07-14T08:15:30+01:00"),
        )
        assertThat(matching.utcOffset).isEqualTo(ZoneOffset.ofHours(1))
        assertThat(matching.utcOffsetSource).isEqualTo(OffsetSource.Xmp)

        val different = summary(
            exif = { ascii(Photos.DATE_TIME_ORIGINAL, "2022:07:14 08:15:30") },
            xmp = mapOf("xmp:CreateDate" to "2022-07-14T07:15:30Z"),
        )
        assertThat(different.utcOffset).isNull()
        assertThat(different.utcOffsetSource).isNull()
    }

    @Test fun offsetDerivedFromGpsTimestamp() {
        // Local 14:03:22, GPS (UTC) 12:01:40 -> +02:00 after rounding to 15 minutes.
        val gps = Photos.gpsIfd {
            rational(0x0007, 12L to 1L, 1L to 1L, 40L to 1L)
            ascii(0x001D, "2024:05:01")
        }
        val summary = summary(exif = { ascii(Photos.DATE_TIME_ORIGINAL, "2024:05:01 14:03:22") }, gps = gps)
        assertThat(summary.utcOffset).isEqualTo(ZoneOffset.ofHours(2))
        assertThat(summary.utcOffsetSource).isEqualTo(OffsetSource.GpsTimestamp)
    }

    @Test fun gpsOffsetAcrossMidnightAndNegative() {
        // Local 2024-05-01 20:00 in New York (-04:00) is 2024-05-02 00:00 UTC.
        val gps = Photos.gpsIfd {
            rational(0x0007, 0L to 1L, 0L to 1L, 12L to 1L)
            ascii(0x001D, "2024:05:02")
        }
        val summary = summary(exif = { ascii(Photos.DATE_TIME_ORIGINAL, "2024:05:01 20:00:00") }, gps = gps)
        assertThat(summary.utcOffset).isEqualTo(ZoneOffset.ofHours(-4))
    }

    @Test fun gpsTimestampTooFarAwayIsIgnored() {
        val gps = Photos.gpsIfd {
            rational(0x0007, 12L to 1L, 0L to 1L, 0L to 1L)
            ascii(0x001D, "2024:04:20")
        }
        val summary = summary(exif = { ascii(Photos.DATE_TIME_ORIGINAL, "2024:05:01 14:00:00") }, gps = gps)
        assertThat(summary.utcOffset).isNull()
    }

    @Test fun exifOffsetWinsOverGps() {
        val gps = Photos.gpsIfd {
            rational(0x0007, 12L to 1L, 0L to 1L, 0L to 1L)
            ascii(0x001D, "2024:05:01")
        }
        val summary = summary(
            exif = { ascii(Photos.DATE_TIME_ORIGINAL, "2024:05:01 14:00:00"); ascii(Photos.OFFSET_TIME_ORIGINAL, "+02:00") },
            gps = gps,
        )
        assertThat(summary.utcOffsetSource).isEqualTo(OffsetSource.ExifOffsetTime)
    }

    @Test fun canonTimeInfoMakerNote() {
        // TimeInfo: size, TimeZone +60 minutes, city, daylight saving +60 minutes.
        val makerNote = TiffBuilder.ifd { slong(0x0035, 16, 60, 23, 60) }
        val summary = summary(exif = { ascii(Photos.DATE_TIME_ORIGINAL, "2024:07:01 10:00:00") }, makerNote = makerNote)
        assertThat(summary.utcOffset).isEqualTo(ZoneOffset.ofHours(2))
        assertThat(summary.utcOffsetSource).isEqualTo(OffsetSource.MakerNote)
    }

    @Test fun noDatesAtAll() {
        val summary = summary()
        assertThat(summary.capturedAt).isNull()
        assertThat(summary.utcOffset).isNull()
    }
}
