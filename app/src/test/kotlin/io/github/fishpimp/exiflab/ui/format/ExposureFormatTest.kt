package io.github.fishpimp.exiflab.ui.format

import com.google.common.truth.Truth.assertThat
import io.github.fishpimp.exiflab.metadata.model.Rational
import java.util.Locale
import org.junit.Test

class ExposureFormatTest {
    private val english = Locale.ENGLISH
    private val swedish = Locale.forLanguageTag("sv-SE")

    private fun shutter(numerator: Long, denominator: Long, locale: Locale = english) =
        ExposureFormat.shutterSpeedText(Rational(numerator, denominator), locale)

    @Test
    fun `unreduced phone exposure times become 1 over N`() {
        assertThat(shutter(2141, 1_000_000)).isEqualTo("1/467")
        assertThat(shutter(10, 38_000)).isEqualTo("1/3800")
        assertThat(shutter(8333, 1_000_000)).isEqualTo("1/120")
        assertThat(shutter(1, 250)).isEqualTo("1/250")
        assertThat(shutter(4, 1000)).isEqualTo("1/250")
    }

    @Test
    fun `whole and long exposures read in seconds`() {
        assertThat(shutter(3000, 100)).isEqualTo("30")
        assertThat(shutter(25, 10)).isEqualTo("2.5")
        assertThat(shutter(1, 1)).isEqualTo("1")
        assertThat(shutter(13, 10)).isEqualTo("1.3")
        assertThat(ExposureFormat.shutterSpeed(Rational(3000, 100))).isEqualTo(ShutterSpeed.Seconds(30.0))
    }

    @Test
    fun `slow exposures stay fractions only when they are close to 1 over N`() {
        assertThat(shutter(1, 3)).isEqualTo("1/3")
        assertThat(shutter(1, 2)).isEqualTo("1/2")
        assertThat(shutter(1, 4)).isEqualTo("1/4")
        // 0.4 s and 0.6 s have no clean 1/N form; cameras show them as decimals too.
        assertThat(shutter(4, 10)).isEqualTo("0.4")
        assertThat(shutter(6, 10)).isEqualTo("0.6")
        // 0.95 s would round to 1/1.
        assertThat(ExposureFormat.shutterSpeed(Rational(95, 100))).isInstanceOf(ShutterSpeed.Seconds::class.java)
    }

    @Test
    fun `seconds use the locale decimal separator`() {
        assertThat(shutter(25, 10, swedish)).isEqualTo("2,5")
        assertThat(shutter(2141, 1_000_000, swedish)).isEqualTo("1/467")
    }

    @Test
    fun `invalid exposure times give nothing`() {
        assertThat(shutter(0, 100)).isNull()
        assertThat(shutter(1, 0)).isNull()
        assertThat(shutter(-1, 100)).isNull()
    }

    @Test
    fun `f-numbers keep at most one decimal`() {
        assertThat(ExposureFormat.fNumber(1.8, english)).isEqualTo("1.8")
        assertThat(ExposureFormat.fNumber(1.68, english)).isEqualTo("1.7")
        assertThat(ExposureFormat.fNumber(2.0, english)).isEqualTo("2")
        assertThat(ExposureFormat.fNumber(11.0, english)).isEqualTo("11")
        assertThat(ExposureFormat.fNumber(5.6, swedish)).isEqualTo("5,6")
        assertThat(ExposureFormat.fNumber(0.0, english)).isNull()
        assertThat(ExposureFormat.fNumber(Double.NaN, english)).isNull()
    }

    @Test
    fun `exposure bias is signed with a typographic minus and plus-minus for zero`() {
        assertThat(ExposureFormat.exposureBias(0.7, english)).isEqualTo("+0.7")
        assertThat(ExposureFormat.exposureBias(-0.67, english)).isEqualTo("−0.7")
        assertThat(ExposureFormat.exposureBias(-1.0 / 3, english)).isEqualTo("−0.3")
        assertThat(ExposureFormat.exposureBias(2.0, english)).isEqualTo("+2")
        assertThat(ExposureFormat.exposureBias(0.0, english)).isEqualTo("±0")
        assertThat(ExposureFormat.exposureBias(-0.01, english)).isEqualTo("±0")
        assertThat(ExposureFormat.exposureBias(1.3, swedish)).isEqualTo("+1,3")
        assertThat(ExposureFormat.exposureBias(Double.NaN, english)).isNull()
    }

    @Test
    fun `focal lengths drop needless decimals`() {
        assertThat(ExposureFormat.focalLength(6.9, english)).isEqualTo("6.9")
        assertThat(ExposureFormat.focalLength(33.0, english)).isEqualTo("33")
        assertThat(ExposureFormat.focalLength(4.25, english)).isEqualTo("4.3")
        assertThat(ExposureFormat.focalLength(-1.0, english)).isNull()
    }

    @Test
    fun `megapixels round to one decimal and skip tiny images`() {
        assertThat(ExposureFormat.megapixels(4032, 3024, english)).isEqualTo("12.2")
        assertThat(ExposureFormat.megapixels(7728, 5152, english)).isEqualTo("39.8")
        assertThat(ExposureFormat.megapixels(8000, 6000, english)).isEqualTo("48")
        assertThat(ExposureFormat.megapixels(1080, 2400, swedish)).isEqualTo("2,6")
        assertThat(ExposureFormat.megapixels(64, 48, english)).isNull()
        assertThat(ExposureFormat.megapixels(0, 3024, english)).isNull()
    }

    @Test
    fun `rotated orientations swap width and height`() {
        assertThat(ExposureFormat.orientedSize(4032, 3024, 6)).isEqualTo(3024 to 4032)
        assertThat(ExposureFormat.orientedSize(4032, 3024, 8)).isEqualTo(3024 to 4032)
        assertThat(ExposureFormat.orientedSize(4032, 3024, 1)).isEqualTo(4032 to 3024)
        assertThat(ExposureFormat.orientedSize(4032, 3024, null)).isEqualTo(4032 to 3024)
    }
}
