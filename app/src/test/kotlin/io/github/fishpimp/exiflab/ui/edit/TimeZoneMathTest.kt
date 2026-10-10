package io.github.fishpimp.exiflab.ui.edit

import com.google.common.truth.Truth.assertThat
import io.github.fishpimp.exiflab.ui.edit.time.SelectableOffsets
import io.github.fishpimp.exiflab.ui.edit.time.ShiftUnit
import io.github.fishpimp.exiflab.ui.edit.time.TimeShift
import io.github.fishpimp.exiflab.ui.edit.time.TimeZoneCorrection
import io.github.fishpimp.exiflab.ui.edit.time.ZoneCatalog
import org.junit.Test
import java.time.Duration
import java.time.LocalDateTime
import java.time.ZoneId
import java.time.ZoneOffset
import java.util.Locale

class TimeZoneMathTest {
    private val stockholm = ZoneId.of("Europe/Stockholm")
    private val newYork = ZoneId.of("America/New_York")
    private val kolkata = ZoneId.of("Asia/Kolkata")

    @Test
    fun `summer trip - camera on Stockholm time, photo taken in New York`() {
        val recorded = LocalDateTime.of(2026, 7, 4, 19, 30)
        val fix = TimeZoneCorrection.of(recorded, stockholm, newYork)
        assertThat(fix.corrected).isEqualTo(LocalDateTime.of(2026, 7, 4, 13, 30))
        assertThat(fix.cameraOffset).isEqualTo(ZoneOffset.ofHours(2))
        assertThat(fix.correctedOffset).isEqualTo(ZoneOffset.ofHours(-4))
        assertThat(fix.cameraDaylightSaving).isTrue()
        assertThat(fix.correctedDaylightSaving).isTrue()
        assertThat(fix.shift).isEqualTo(Duration.ofHours(-6))
        assertThat(fix.changesTime).isTrue()
        assertThat(fix.recordedInGap).isFalse()
        assertThat(fix.recordedAmbiguous).isFalse()
    }

    @Test
    fun `daylight saving is resolved for the photo's own date`() {
        val winter = TimeZoneCorrection.of(LocalDateTime.of(2026, 1, 15, 12, 0), stockholm, newYork)
        assertThat(winter.corrected).isEqualTo(LocalDateTime.of(2026, 1, 15, 6, 0))
        assertThat(winter.correctedOffset).isEqualTo(ZoneOffset.ofHours(-5))
        assertThat(winter.correctedDaylightSaving).isFalse()

        // Mid-March: New York is already on summer time, Stockholm is not yet. The gap is 5 hours.
        val march = TimeZoneCorrection.of(LocalDateTime.of(2026, 3, 20, 12, 0), stockholm, newYork)
        assertThat(march.shift).isEqualTo(Duration.ofHours(-5))
        assertThat(march.cameraDaylightSaving).isFalse()
        assertThat(march.correctedDaylightSaving).isTrue()
    }

    @Test
    fun `half-hour zones and crossing midnight`() {
        val fix = TimeZoneCorrection.of(LocalDateTime.of(2026, 2, 1, 22, 15), stockholm, kolkata)
        assertThat(fix.corrected).isEqualTo(LocalDateTime.of(2026, 2, 2, 2, 45))
        assertThat(fix.correctedOffset).isEqualTo(ZoneOffset.ofHoursMinutes(5, 30))
        assertThat(fix.shift).isEqualTo(Duration.ofMinutes(4 * 60 + 30))
    }

    @Test
    fun `the same zone changes nothing`() {
        val fix = TimeZoneCorrection.of(LocalDateTime.of(2026, 6, 14, 19, 42, 7), stockholm, stockholm)
        assertThat(fix.changesTime).isFalse()
        assertThat(fix.corrected).isEqualTo(fix.recorded)
    }

    @Test
    fun `times in a spring gap or an autumn overlap are flagged`() {
        // Clocks in Sweden went from 02:00 to 03:00 on 29 March 2026.
        val gap = TimeZoneCorrection.of(LocalDateTime.of(2026, 3, 29, 2, 30), stockholm, ZoneOffset.UTC)
        assertThat(gap.recordedInGap).isTrue()
        assertThat(gap.corrected).isEqualTo(LocalDateTime.of(2026, 3, 29, 1, 30))
        // And back from 03:00 to 02:00 on 25 October 2026; the earlier (summer time) instant is used.
        val overlap = TimeZoneCorrection.of(LocalDateTime.of(2026, 10, 25, 2, 30), stockholm, ZoneOffset.UTC)
        assertThat(overlap.recordedAmbiguous).isTrue()
        assertThat(overlap.corrected).isEqualTo(LocalDateTime.of(2026, 10, 25, 0, 30))
    }

    @Test
    fun `the zone list holds region zones only, sorted by offset`() {
        val at = LocalDateTime.of(2026, 6, 14, 12, 0)
        val zones = ZoneCatalog.all(at, Locale.ENGLISH)
        assertThat(zones.map { it.id.id }).containsAtLeast("Europe/Stockholm", "America/New_York", "Asia/Kolkata", "Pacific/Auckland")
        assertThat(zones.none { it.id.id.startsWith("Etc/") || it.id.id.startsWith("SystemV/") || it.id.id.startsWith("US/") }).isTrue()
        assertThat(zones.map { it.offset.totalSeconds }).isInOrder()
        val stockholmChoice = zones.single { it.id == stockholm }
        assertThat(stockholmChoice.city).isEqualTo("Stockholm")
        assertThat(stockholmChoice.region).isEqualTo("Europe")
        assertThat(stockholmChoice.offset).isEqualTo(ZoneOffset.ofHours(2))
        assertThat(zones.single { it.id.id == "America/Argentina/Buenos_Aires" }.city).isEqualTo("Buenos Aires")
    }

    @Test
    fun `search finds zones by city, region, name and offset, best matches first`() {
        val zones = ZoneCatalog.all(LocalDateTime.of(2026, 6, 14, 12, 0), Locale.ENGLISH)
        assertThat(ZoneCatalog.search(zones, "stock").first().id).isEqualTo(stockholm)
        assertThat(ZoneCatalog.search(zones, "new york").first().id).isEqualTo(newYork)
        assertThat(ZoneCatalog.search(zones, "Sao").map { it.id.id }).contains("America/Sao_Paulo")
        assertThat(ZoneCatalog.search(zones, "+5:30").map { it.id }).contains(kolkata)
        assertThat(ZoneCatalog.search(zones, "UTC+02").first().offset).isEqualTo(ZoneOffset.ofHours(2))
        assertThat(ZoneCatalog.search(zones, "india").map { it.id }).contains(kolkata)
        assertThat(ZoneCatalog.search(zones, "")).hasSize(zones.size)
        assertThat(ZoneCatalog.search(zones, "no such place")).isEmpty()
    }

    @Test
    fun `offset queries`() {
        assertThat(ZoneCatalog.parseOffsetQuery("+2")).isEqualTo(ZoneOffset.ofHours(2))
        assertThat(ZoneCatalog.parseOffsetQuery("-03")).isEqualTo(ZoneOffset.ofHours(-3))
        assertThat(ZoneCatalog.parseOffsetQuery("UTC+5:30")).isEqualTo(ZoneOffset.ofHoursMinutes(5, 30))
        assertThat(ZoneCatalog.parseOffsetQuery("gmt-0930")).isEqualTo(ZoneOffset.ofHoursMinutes(-9, -30))
        assertThat(ZoneCatalog.parseOffsetQuery("−8")).isEqualTo(ZoneOffset.ofHours(-8))
        assertThat(ZoneCatalog.parseOffsetQuery("Stockholm")).isNull()
        assertThat(ZoneCatalog.parseOffsetQuery("+25")).isNull()
    }

    @Test
    fun `the taken-in zone is suggested from the recorded offset`() {
        val at = LocalDateTime.of(2026, 7, 4, 12, 0)
        val zones = ZoneCatalog.all(at, Locale.ENGLISH)
        // The device zone wins when it matches the offset on that date.
        assertThat(ZoneCatalog.suggestFor(ZoneOffset.ofHours(2), at, stockholm, zones)).isEqualTo(stockholm)
        // Otherwise a well-known zone with that offset.
        assertThat(ZoneCatalog.suggestFor(ZoneOffset.ofHours(-4), at, stockholm, zones)).isEqualTo(newYork)
        assertThat(ZoneCatalog.suggestFor(ZoneOffset.ofHoursMinutes(5, 30), at, stockholm, zones)).isEqualTo(kolkata)
        // An offset no region zone has falls back to the offset itself, and no offset means no suggestion.
        assertThat(ZoneCatalog.suggestFor(ZoneOffset.ofHoursMinutes(-1, -15), at, stockholm, zones)).isEqualTo(ZoneOffset.ofHoursMinutes(-1, -15))
        assertThat(ZoneCatalog.suggestFor(null, at, stockholm, zones)).isNull()
    }

    @Test
    fun `selectable offsets cover whole hours and the fractional ones in use`() {
        assertThat(SelectableOffsets.first()).isEqualTo(ZoneOffset.ofHours(-12))
        assertThat(SelectableOffsets.last()).isEqualTo(ZoneOffset.ofHours(14))
        assertThat(SelectableOffsets).containsAtLeast(
            ZoneOffset.UTC,
            ZoneOffset.ofHoursMinutes(5, 30),
            ZoneOffset.ofHoursMinutes(5, 45),
            ZoneOffset.ofHoursMinutes(-3, -30),
            ZoneOffset.ofHoursMinutes(12, 45),
        )
        assertThat(SelectableOffsets).doesNotContain(ZoneOffset.ofHoursMinutes(1, 15))
        assertThat(SelectableOffsets.map { it.totalSeconds }).isInStrictOrder()
    }

    @Test
    fun `time shifts add up their parts, keep their sign and round-trip`() {
        val shift = TimeShift(days = 1, hours = 2, minutes = 30, seconds = 5, later = false)
        assertThat(shift.duration).isEqualTo(Duration.ofDays(1).plusHours(2).plusMinutes(30).plusSeconds(5).negated())
        assertThat(TimeShift.of(shift.duration)).isEqualTo(shift)
        assertThat(TimeShift.of(Duration.ofMinutes(90))).isEqualTo(TimeShift(hours = 1, minutes = 30))
        assertThat(TimeShift().isZero).isTrue()
        assertThat(TimeShift().with(ShiftUnit.Hours, 30).hours).isEqualTo(23)
        assertThat(TimeShift().with(ShiftUnit.Minutes, -1).minutes).isEqualTo(0)
        assertThat(TimeShift().with(ShiftUnit.Seconds, 42).value(ShiftUnit.Seconds)).isEqualTo(42)
    }
}
