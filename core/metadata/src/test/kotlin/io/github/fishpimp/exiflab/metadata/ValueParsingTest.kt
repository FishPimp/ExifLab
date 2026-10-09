package io.github.fishpimp.exiflab.metadata

import com.drew.lang.Rational
import com.google.common.truth.Truth.assertThat
import io.github.fishpimp.exiflab.metadata.mapping.DirectoryCatalog
import io.github.fishpimp.exiflab.metadata.mapping.ValueText
import io.github.fishpimp.exiflab.metadata.summary.MetadataDates
import io.github.fishpimp.exiflab.metadata.summary.cleanIccDescription
import java.time.LocalDateTime
import java.time.ZoneOffset
import org.junit.Test

class ValueParsingTest {
    @Test fun rawValues() {
        assertThat(ValueText.raw(Rational(-7, 10))).isEqualTo("-7/10")
        assertThat(ValueText.raw(arrayOf(Rational(59, 1), Rational(19, 1), Rational(4512, 100)))).isEqualTo("59/1 19/1 4512/100")
        assertThat(ValueText.raw(intArrayOf(1, 2, 3))).isEqualTo("1 2 3")
        assertThat(ValueText.raw(byteArrayOf(0x4A, 0x46, 0x49, 0x46))).isEqualTo("4A 46 49 46")
        assertThat(ValueText.raw(IntArray(100) { it })).endsWith("63 ... (100 values)")
        assertThat(ValueText.raw(null)).isEmpty()
    }

    @Test fun sanitizeTrimsEscapesAndCaps() {
        assertThat(ValueText.sanitize("Canon\u0000\u0000  ")).isEqualTo("Canon")
        assertThat(ValueText.sanitize("a\u001Bb\tc\r\nd")).isEqualTo("a\\x1Bb\tc\nd")
        val long = ValueText.sanitize("x".repeat(10_000))
        assertThat(long).startsWith("x".repeat(ValueText.MAX_TEXT_LENGTH))
        assertThat(long).endsWith(" ... (10000 characters)")
    }

    @Test fun exifDates() {
        assertThat(MetadataDates.parseExif("2024:05:01 14:03:22")?.local).isEqualTo(LocalDateTime.of(2024, 5, 1, 14, 3, 22))
        assertThat(MetadataDates.parseExif("2024-05-01T14:03")?.local).isEqualTo(LocalDateTime.of(2024, 5, 1, 14, 3))
        assertThat(MetadataDates.parseExif("2024:05:01 14:03:22", "045")?.local?.nano).isEqualTo(45_000_000)
        assertThat(MetadataDates.parseExif("2024:05:01 14:03:22", "junk")?.local?.nano).isEqualTo(0)
        assertThat(MetadataDates.parseExif("    :  :     :  :  ")).isNull()
        assertThat(MetadataDates.parseExif("2024:02:30 10:00:00")).isNull()
    }

    @Test fun xmpDatesAndOffsets() {
        val parsed = MetadataDates.parseXmp("2022-07-14T08:15:30.5-03:30")!!
        assertThat(parsed.local).isEqualTo(LocalDateTime.of(2022, 7, 14, 8, 15, 30, 500_000_000))
        assertThat(parsed.offset).isEqualTo(ZoneOffset.ofHoursMinutes(-3, -30))
        assertThat(MetadataDates.parseXmp("2022-07-14")).isNull()
        assertThat(MetadataDates.parseOffset("Z")).isEqualTo(ZoneOffset.UTC)
        assertThat(MetadataDates.parseOffset("+0545")).isEqualTo(ZoneOffset.ofHoursMinutes(5, 45))
        assertThat(MetadataDates.parseOffset("+19:00")).isNull()
        assertThat(MetadataDates.parseOffset("   :  ")).isNull()
    }

    @Test fun makerNoteIdsAreStableSlugs() {
        assertThat(DirectoryCatalog.makernoteVendor("CanonMakernoteDirectory")).isEqualTo("canon")
        assertThat(DirectoryCatalog.makernoteVendor("NikonType2MakernoteDirectory")).isEqualTo("nikon")
        assertThat(DirectoryCatalog.makernoteVendor("OlympusCameraSettingsMakernoteDirectory")).isEqualTo("olympus-camera-settings")
        assertThat(DirectoryCatalog.makernoteVendor("SonyTag9050bDirectory")).isEqualTo("sony-tag9050b")
        assertThat(DirectoryCatalog.makernoteVendor("LeicaType5MakernoteDirectory")).isEqualTo("leica")
    }

    @Test fun iccDescriptionsLoseLocalePrefixAndPadding() {
        assertThat(cleanIccDescription("1 enUS(sRGB IEC61966-2.1\\x00)")).isEqualTo("sRGB IEC61966-2.1")
        assertThat(cleanIccDescription("1 enUS(Display P3\u0000)")).isEqualTo("Display P3")
        assertThat(cleanIccDescription("Display P3")).isEqualTo("Display P3")
    }
}
