package io.github.fishpimp.exiflab.ui.edit

import com.google.common.truth.Truth.assertThat
import io.github.fishpimp.exiflab.ui.edit.time.ZoneCatalog
import java.time.LocalDateTime
import java.util.Locale
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/** The time zone search finds countries and localized city names, as its hint promises (ICU data). */
@RunWith(RobolectricTestRunner::class)
class ZoneSearchLocalizedTest {
    private val at = LocalDateTime.of(2026, 6, 14, 12, 0)

    @Test
    fun `Swedish search finds zones by country in Swedish and English and by Swedish city`() {
        val zones = ZoneCatalog.all(at, Locale.forLanguageTag("sv-SE"))
        assertThat(ZoneCatalog.search(zones, "Sverige").map { it.id.id }).contains("Europe/Stockholm")
        assertThat(ZoneCatalog.search(zones, "Sweden").map { it.id.id }).contains("Europe/Stockholm")
        assertThat(ZoneCatalog.search(zones, "Danmark").map { it.id.id }).contains("Europe/Copenhagen")
        assertThat(ZoneCatalog.search(zones, "Köpenhamn").first().id.id).isEqualTo("Europe/Copenhagen")
        assertThat(ZoneCatalog.search(zones, "Copenhagen").first().id.id).isEqualTo("Europe/Copenhagen")
        val copenhagen = zones.single { it.id.id == "Europe/Copenhagen" }
        assertThat(copenhagen.city).isEqualTo("Köpenhamn")
        assertThat(copenhagen.country).isEqualTo("Danmark")
    }

    @Test
    fun `English search finds a zone by its country`() {
        val zones = ZoneCatalog.all(at, Locale.ENGLISH)
        assertThat(ZoneCatalog.search(zones, "Japan").map { it.id.id }).contains("Asia/Tokyo")
        assertThat(zones.single { it.id.id == "Europe/Stockholm" }.country).isEqualTo("Sweden")
    }
}
