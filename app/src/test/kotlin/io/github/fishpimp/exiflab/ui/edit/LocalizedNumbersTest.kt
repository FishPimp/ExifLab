package io.github.fishpimp.exiflab.ui.edit

import com.google.common.truth.Truth.assertThat
import io.github.fishpimp.exiflab.ui.batch.percentText
import io.github.fishpimp.exiflab.ui.edit.location.formatAltitude
import io.github.fishpimp.exiflab.ui.edit.location.parseAltitude
import java.util.Locale
import org.junit.Test

/** Numbers the editor shows follow the app language: decimal comma and "42 %" in Swedish. */
class LocalizedNumbersTest {
    private val swedish = Locale.forLanguageTag("sv-SE")

    @Test
    fun `range limits use the locale's decimal separator and never an exponent`() {
        assertThat(localizedNumber(-99.99, Locale.ENGLISH)).isEqualTo("-99.99")
        assertThat(localizedNumber(99.99, swedish)).isEqualTo("99,99")
        assertThat(localizedNumber(65535.0, swedish)).isEqualTo("65535")
        assertThat(localizedNumber(1e-7, Locale.ENGLISH)).isEqualTo("0")
        assertThat(localizedNumber(1e9, Locale.ENGLISH)).isEqualTo("1000000000")
    }

    @Test
    fun `altitude is prefilled in the locale's form and reads back`() {
        assertThat(formatAltitude(12.5, Locale.ENGLISH)).isEqualTo("12.5")
        assertThat(formatAltitude(12.5, swedish)).isEqualTo("12,5")
        assertThat(formatAltitude(18.0, swedish)).isEqualTo("18")
        assertThat(formatAltitude(1234.0, swedish)).isEqualTo("1234")
        for (meters in listOf(12.5, -4.5, 0.0, 8848.9)) {
            assertThat(parseAltitude(formatAltitude(meters, swedish))).isEqualTo(meters)
            assertThat(parseAltitude(formatAltitude(meters, Locale.ENGLISH))).isEqualTo(meters)
        }
    }

    @Test
    fun `batch progress percent follows the locale and rounds down`() {
        assertThat(percentText(0.42f, Locale.ENGLISH)).isEqualTo("42%")
        assertThat(percentText(0.42f, swedish).replace(' ', ' ')).isEqualTo("42 %")
        assertThat(percentText(0.999f, Locale.ENGLISH)).isEqualTo("99%")
    }
}
