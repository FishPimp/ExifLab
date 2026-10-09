package io.github.fishpimp.exiflab.ui.format

import com.google.common.truth.Truth.assertThat
import java.time.LocalDateTime
import java.time.ZoneOffset
import java.util.Locale
import org.junit.Test

class LocationAndTimeFormatTest {
    @Test
    fun `degrees minutes seconds in the northern and eastern hemispheres`() {
        val latitude = 59 + 19 / 60.0 + 35.29 / 3600
        val longitude = 18 + 4 / 60.0 + 18.67 / 3600
        assertThat(LocationFormat.degreesMinutesSeconds(latitude, longitude)).isEqualTo("59°19′35.3″N 18°04′18.7″E")
    }

    @Test
    fun `southern and western coordinates use S and W instead of a sign`() {
        // Sydney Opera House and Rio de Janeiro.
        assertThat(LocationFormat.degreesMinutesSeconds(-33.856784, 151.215297)).isEqualTo("33°51′24.4″S 151°12′55.1″E")
        assertThat(LocationFormat.degreesMinutesSeconds(-22.951916, -43.210487)).isEqualTo("22°57′06.9″S 43°12′37.8″W")
        assertThat(LocationFormat.dmsPart(-0.5, 'N', 'S')).isEqualTo("0°30′00.0″S")
    }

    @Test
    fun `rounding never produces sixty seconds or minutes`() {
        // 59.99999 degrees is 59°59′59.96″, which rounds up to a whole degree.
        assertThat(LocationFormat.dmsPart(59.99999, 'N', 'S')).isEqualTo("60°00′00.0″N")
        assertThat(LocationFormat.dmsPart(10 + 59.99 / 3600, 'E', 'W')).isEqualTo("10°01′00.0″E")
    }

    @Test
    fun `decimal degrees keep six decimals and dots in every language`() {
        val original = Locale.getDefault()
        Locale.setDefault(Locale.forLanguageTag("sv-SE"))
        try {
            assertThat(LocationFormat.decimalDegrees(59.326469, 18.071853)).isEqualTo("59.326469, 18.071853")
            assertThat(LocationFormat.decimalDegrees(-22.951916, -43.210487)).isEqualTo("-22.951916, -43.210487")
        } finally {
            Locale.setDefault(original)
        }
    }

    @Test
    fun `bearings map to the nearest compass point`() {
        assertThat(LocationFormat.compassPoint(0.0)).isEqualTo(CompassPoint.N)
        assertThat(LocationFormat.compassPoint(22.4)).isEqualTo(CompassPoint.N)
        assertThat(LocationFormat.compassPoint(22.6)).isEqualTo(CompassPoint.NE)
        assertThat(LocationFormat.compassPoint(247.0)).isEqualTo(CompassPoint.SW)
        assertThat(LocationFormat.compassPoint(359.0)).isEqualTo(CompassPoint.N)
        assertThat(LocationFormat.compassPoint(-90.0)).isEqualTo(CompassPoint.W)
        assertThat(LocationFormat.bearingDegrees(359.7)).isEqualTo(0)
        assertThat(LocationFormat.bearingDegrees(-10.0)).isEqualTo(350)
    }

    @Test
    fun `altitude is whole metres without a sign`() {
        assertThat(LocationFormat.altitudeMeters(18.4)).isEqualTo(18)
        assertThat(LocationFormat.altitudeMeters(-3.6)).isEqualTo(4)
    }

    @Test
    fun `UTC offsets always show hours and minutes`() {
        assertThat(CaptureTimeFormat.utcOffset(ZoneOffset.ofHours(2))).isEqualTo("+02:00")
        assertThat(CaptureTimeFormat.utcOffset(ZoneOffset.ofHoursMinutes(5, 45))).isEqualTo("+05:45")
        assertThat(CaptureTimeFormat.utcOffset(ZoneOffset.ofHoursMinutes(-3, -30))).isEqualTo("−03:30")
        assertThat(CaptureTimeFormat.utcOffset(ZoneOffset.ofHours(-10))).isEqualTo("−10:00")
        assertThat(CaptureTimeFormat.utcOffset(ZoneOffset.UTC)).isEqualTo("+00:00")
    }

    @Test
    fun `capture dates follow the app locale`() {
        val taken = LocalDateTime.of(2026, 6, 14, 19, 42, 7)
        assertThat(CaptureTimeFormat.date(taken, Locale.forLanguageTag("sv-SE"))).isEqualTo("söndag 14 juni 2026")
        assertThat(CaptureTimeFormat.date(taken, Locale.UK)).contains("14 June 2026")
        assertThat(CaptureTimeFormat.time(taken, Locale.forLanguageTag("sv-SE"))).isEqualTo("19:42:07")
        assertThat(CaptureTimeFormat.time(taken, Locale.US)).contains("7:42:07")
    }

    @Test
    fun `camera names do not repeat the brand`() {
        assertThat(CameraName.of("Google", "Pixel 8 Pro")).isEqualTo(CameraName("Google", "Pixel 8 Pro"))
        assertThat(CameraName.of("Canon", "Canon EOS R5")).isEqualTo(CameraName(null, "Canon EOS R5"))
        assertThat(CameraName.of("NIKON CORPORATION", "NIKON Z 6")).isEqualTo(CameraName(null, "NIKON Z 6"))
        assertThat(CameraName.of(" FUJIFILM ", "X-T5")).isEqualTo(CameraName("FUJIFILM", "X-T5"))
        assertThat(CameraName.of("samsung", null)).isEqualTo(CameraName(null, "samsung"))
        assertThat(CameraName.of(null, "iPhone 15 Pro")).isEqualTo(CameraName(null, "iPhone 15 Pro"))
        assertThat(CameraName.of("", "  ")).isNull()
    }
}
