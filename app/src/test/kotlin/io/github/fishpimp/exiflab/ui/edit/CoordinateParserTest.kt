package io.github.fishpimp.exiflab.ui.edit

import com.google.common.truth.Truth.assertThat
import io.github.fishpimp.exiflab.ui.edit.location.CoordinateError
import io.github.fishpimp.exiflab.ui.edit.location.CoordinateParse
import io.github.fishpimp.exiflab.ui.edit.location.CoordinateParser
import io.github.fishpimp.exiflab.ui.edit.location.ParsedPosition
import io.github.fishpimp.exiflab.ui.edit.location.parseAltitude
import org.junit.Test

class CoordinateParserTest {
    private fun position(text: String): ParsedPosition {
        val result = CoordinateParser.parse(text)
        assertThat(result).isInstanceOf(CoordinateParse.Success::class.java)
        return (result as CoordinateParse.Success).position
    }

    private fun assertAt(text: String, latitude: Double, longitude: Double, tolerance: Double = 1e-4) {
        val parsed = position(text).latLng
        assertThat(parsed.latitude).isWithin(tolerance).of(latitude)
        assertThat(parsed.longitude).isWithin(tolerance).of(longitude)
    }

    private fun error(text: String): CoordinateError = (CoordinateParser.parse(text) as CoordinateParse.Failure).error

    @Test
    fun `decimal degrees in the common spellings`() {
        assertAt("59.3293, 18.0686", 59.3293, 18.0686)
        assertAt("59.3293,18.0686", 59.3293, 18.0686)
        assertAt("  59.3293   18.0686  ", 59.3293, 18.0686)
        assertAt("59.3293; 18.0686", 59.3293, 18.0686)
        assertAt("-33.8568, 151.2153", -33.8568, 151.2153)
        assertAt("-33.8568 151.2153", -33.8568, 151.2153)
        assertAt("40.6892 -74.0445", 40.6892, -74.0445)
        assertAt("+59.3293, +18.0686", 59.3293, 18.0686)
        assertAt("59, 18", 59.0, 18.0)
        assertAt("59,18", 59.0, 18.0)
    }

    @Test
    fun `decimal commas as Swedish users type them`() {
        assertAt("59,3293 18,0686", 59.3293, 18.0686)
        assertAt("59,3293; 18,0686", 59.3293, 18.0686)
    }

    @Test
    fun `hemisphere letters before or after, in either order`() {
        assertAt("59.3293N 18.0686E", 59.3293, 18.0686)
        assertAt("59.3293 N, 18.0686 E", 59.3293, 18.0686)
        assertAt("N 59.3293 E 18.0686", 59.3293, 18.0686)
        assertAt("33.8568S 151.2153E", -33.8568, 151.2153)
        assertAt("40.6892 N 74.0445 W", 40.6892, -74.0445)
        assertAt("18.0686E 59.3293N", 59.3293, 18.0686)
        assertAt("E 18.0686 N 59.3293", 59.3293, 18.0686)
        assertAt("59.3293n 18.0686e", 59.3293, 18.0686)
    }

    @Test
    fun `degrees minutes and seconds`() {
        val lat = 59 + 19 / 60.0 + 45.5 / 3600
        val lon = 18 + 4 / 60.0 + 7.0 / 3600
        assertAt("59°19'45.5\"N 18°4'7.0\"E", lat, lon, 1e-6)
        assertAt("59°19′45.5″N 18°04′07.0″E", lat, lon, 1e-6)
        assertAt("59° 19' 45.5\" N, 18° 4' 7\" E", lat, lon, 1e-6)
        assertAt("N 59°19'45.5\" E 18°4'7\"", lat, lon, 1e-6)
        assertAt("59 19 45.5 N 18 4 7 E", lat, lon, 1e-6)
        assertAt("59 19 45.5 18 4 7", lat, lon, 1e-6)
        assertAt("59°19'45.5''N 18°4'7''E", lat, lon, 1e-6)
        assertAt("59d 19m 45.5\" N, 18d 4m 7\" E", lat, lon, 1e-6)
        assertAt("33°51'24.5\"S 151°12'55.1\"E", -(33 + 51 / 60.0 + 24.5 / 3600), 151 + 12 / 60.0 + 55.1 / 3600, 1e-6)
    }

    @Test
    fun `degrees and decimal minutes, as GPS units show them`() {
        assertAt("N 59° 19.758' E 18° 4.117'", 59 + 19.758 / 60, 18 + 4.117 / 60, 1e-6)
        assertAt("59 19.758 18 4.117", 59 + 19.758 / 60, 18 + 4.117 / 60, 1e-6)
    }

    @Test
    fun `Swedish hemispheres Ö and V`() {
        assertAt("59,3293 N 18,0686 Ö", 59.3293, 18.0686)
        assertAt("40.6892 N 74.0445 V", 40.6892, -74.0445)
    }

    @Test
    fun `Google Maps links`() {
        assertAt("https://www.google.com/maps/@59.3293,18.0686,15z", 59.3293, 18.0686)
        assertAt("https://www.google.com/maps/place/Stockholm/@59.3260668,17.8419725,11z/data=!3m1!4b1!4m6!3m5!1s0x465f763119640bcb:0xa80d27d3679d7766!8m2!3d59.3293235!4d18.0685808", 59.3293235, 18.0685808)
        assertAt("https://maps.google.com/?q=59.3293,18.0686", 59.3293, 18.0686)
        assertAt("https://www.google.com/maps?q=59.3293%2C18.0686&z=17", 59.3293, 18.0686)
        assertAt("https://www.google.com/maps/search/?api=1&query=-33.8568,151.2153", -33.8568, 151.2153)
        assertAt("https://www.google.com/maps/search/59.3293,+18.0686", 59.3293, 18.0686)
        assertAt("https://www.google.se/maps/@59.3293,18.0686,3a,75y,90t/data=!3m6", 59.3293, 18.0686)
        assertAt("https://www.google.com/maps/dir/?api=1&destination=59.3293,18.0686", 59.3293, 18.0686)
    }

    @Test
    fun `geo URIs, with altitude and query`() {
        assertAt("geo:59.3293,18.0686", 59.3293, 18.0686)
        assertAt("geo:59.3293,18.0686;u=35", 59.3293, 18.0686)
        assertAt("geo:0,0?q=59.3293,18.0686(Stockholm)", 59.3293, 18.0686)
        assertAt("GEO:-33.8568,151.2153?z=12", -33.8568, 151.2153)
        assertThat(position("geo:59.3293,18.0686,25.5").altitudeMeters).isEqualTo(25.5)
        assertThat(position("geo:59.3293,18.0686").altitudeMeters).isNull()
    }

    @Test
    fun `OpenStreetMap and Apple Maps links`() {
        assertAt("https://www.openstreetmap.org/#map=15/59.3293/18.0686", 59.3293, 18.0686)
        assertAt("https://www.openstreetmap.org/?mlat=59.3293&mlon=18.0686#map=17/59.33/18.07", 59.3293, 18.0686)
        assertAt("https://www.openstreetmap.org/search?query=x#map=12/-33.8568/151.2153", -33.8568, 151.2153)
        assertAt("https://maps.apple.com/?ll=59.3293,18.0686&q=Stockholm", 59.3293, 18.0686)
        assertAt("https://maps.apple.com/?q=59.3293,18.0686", 59.3293, 18.0686)
    }

    @Test
    fun `short links cannot be expanded offline`() {
        assertThat(error("https://maps.app.goo.gl/AbCdEf123")).isEqualTo(CoordinateError.ShortLink)
        assertThat(error("https://goo.gl/maps/xyz")).isEqualTo(CoordinateError.ShortLink)
    }

    @Test
    fun `out of range values are reported as such`() {
        assertThat(error("91.0, 18.0")).isEqualTo(CoordinateError.OutOfRange)
        assertThat(error("59.0, 181.0")).isEqualTo(CoordinateError.OutOfRange)
        assertThat(error("59°61'0\"N 18°0'0\"E")).isEqualTo(CoordinateError.OutOfRange)
        assertThat(error("59°19'60\"N 18°0'0\"E")).isEqualTo(CoordinateError.OutOfRange)
        assertThat(error("geo:95.0,18.0")).isEqualTo(CoordinateError.OutOfRange)
        assertThat(error("https://www.google.com/maps/@95.1,18.0,15z")).isEqualTo(CoordinateError.OutOfRange)
    }

    @Test
    fun `text that is not a position is rejected`() {
        assertThat(error("")).isEqualTo(CoordinateError.Empty)
        assertThat(error("   ")).isEqualTo(CoordinateError.Empty)
        assertThat(error("Stockholm")).isEqualTo(CoordinateError.Unrecognized)
        assertThat(error("59.3293")).isEqualTo(CoordinateError.Unrecognized)
        assertThat(error("59.3 18.0 12.1")).isEqualTo(CoordinateError.Unrecognized)
        assertThat(error("59.3N 18.0N")).isEqualTo(CoordinateError.Unrecognized)
        assertThat(error("N N 59 18")).isEqualTo(CoordinateError.Unrecognized)
        assertThat(error("59.3293, 18.0686, 12, 4, 5, 6, 7")).isEqualTo(CoordinateError.Unrecognized)
        assertThat(error("https://www.example.com/page")).isEqualTo(CoordinateError.Unrecognized)
        assertThat(error("https://www.google.com/maps/place/Stockholm")).isEqualTo(CoordinateError.Unrecognized)
        assertThat(error("geo:")).isEqualTo(CoordinateError.Unrecognized)
        assertThat(error("59.5°30'N 18°E")).isEqualTo(CoordinateError.OutOfRange)
    }

    @Test
    fun `altitude accepts dot and comma decimals and rejects nonsense`() {
        assertThat(parseAltitude("18")).isEqualTo(18.0)
        assertThat(parseAltitude("18,5")).isEqualTo(18.5)
        assertThat(parseAltitude(" -4.5 ")).isEqualTo(-4.5)
        assertThat(parseAltitude("−4.5")).isEqualTo(-4.5)
        assertThat(parseAltitude("")).isNull()
        assertThat(parseAltitude("high")).isNull()
        assertThat(parseAltitude("1e9")).isNull()
    }
}
