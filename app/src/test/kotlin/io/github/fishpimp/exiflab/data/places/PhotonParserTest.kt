package io.github.fishpimp.exiflab.data.places

import com.google.common.truth.Truth.assertThat
import io.github.fishpimp.exiflab.ui.map.LatLng
import kotlinx.serialization.SerializationException
import org.junit.Assert.assertThrows
import org.junit.Test

/** Parsing recorded Photon responses (see src/test/resources/photon). */
class PhotonParserTest {
    @Test
    fun `cities, stations, streets and regions get the right kind and context`() {
        val places = PhotonParser.parse(sample("stockholm.json"))

        assertThat(places.map { it.name }).containsExactly(
            "Stockholm",
            "Stockholm Centralstation",
            "Stockholm Arlanda flygplats",
            "Stockholmsvägen",
            "Stockholms län",
        ).inOrder()

        val city = places[0]
        assertThat(city.kind).isEqualTo(PlaceKind.City)
        assertThat(city.locality).isNull()
        assertThat(city.region).isEqualTo("Stockholms län")
        assertThat(city.country).isEqualTo("Sverige")
        // GeoJSON puts longitude first; Place does not.
        assertThat(city.latLng).isEqualTo(LatLng(latitude = 59.3251172, longitude = 18.0710935))

        val station = places[1]
        assertThat(station.kind).isEqualTo(PlaceKind.PointOfInterest)
        assertThat(station.locality).isEqualTo("Stockholm")
        assertThat(station.region).isEqualTo("Stockholms län")

        val airport = places[2]
        assertThat(airport.locality).isEqualTo("Sigtuna")

        val street = places[3]
        assertThat(street.kind).isEqualTo(PlaceKind.Street)
        assertThat(street.locality).isEqualTo("Täby")

        val county = places[4]
        assertThat(county.kind).isEqualTo(PlaceKind.Region)
        assertThat(county.locality).isNull()
        assertThat(county.region).isNull()
        assertThat(county.country).isEqualTo("Sverige")
    }

    @Test
    fun `results that would look the same are listed once`() {
        val places = PhotonParser.parse(sample("stockholm.json"))
        assertThat(places.count { it.name == "Stockholm" }).isEqualTo(1)
    }

    @Test
    fun `addresses without a name are named by street and number`() {
        val places = PhotonParser.parse(sample("address.json"))

        val address = places[0]
        assertThat(address.name).isEqualTo("Drottninggatan 12")
        assertThat(address.kind).isEqualTo(PlaceKind.Address)
        assertThat(address.locality).isEqualTo("Stockholm")

        val venue = places[1]
        assertThat(venue.name).isEqualTo("Kulturhuset Stadsteatern")
        assertThat(venue.kind).isEqualTo(PlaceKind.PointOfInterest)

        assertThat(places[2].kind).isEqualTo(PlaceKind.Street)

        val district = places[3]
        assertThat(district.name).isEqualTo("Norrmalm")
        assertThat(district.kind).isEqualTo(PlaceKind.District)
        assertThat(district.locality).isEqualTo("Stockholm")
    }

    @Test
    fun `a country carries no larger context`() {
        val places = PhotonParser.parse(sample("paris_en.json"))
        assertThat(places.map { it.kind })
            .containsExactly(PlaceKind.PointOfInterest, PlaceKind.City, PlaceKind.Country).inOrder()

        val tower = places[0]
        assertThat(tower.name).isEqualTo("Eiffel Tower")
        assertThat(tower.locality).isEqualTo("Paris")
        assertThat(tower.region).isEqualTo("Ile-de-France")
        assertThat(tower.country).isEqualTo("France")

        val paris = places[1]
        assertThat(paris.locality).isNull()
        // County "Paris" repeats the name, so the state is the region.
        assertThat(paris.region).isEqualTo("Ile-de-France")

        val france = places[2]
        assertThat(france.locality).isNull()
        assertThat(france.region).isNull()
        assertThat(france.country).isNull()
    }

    @Test
    fun `results without a usable position or name are skipped`() {
        val places = PhotonParser.parse(sample("edge_cases.json"))
        assertThat(places).hasSize(1)
        val peak = places.single()
        assertThat(peak.name).isEqualTo("Ramberget")
        assertThat(peak.kind).isEqualTo(PlaceKind.Other)
        assertThat(peak.locality).isEqualTo("Göteborg")
        assertThat(peak.region).isEqualTo("Västra Götalands län")
    }

    @Test
    fun `no results`() {
        assertThat(PhotonParser.parse(sample("empty.json"))).isEmpty()
    }

    @Test
    fun `an error page is not mistaken for results`() {
        assertThrows(SerializationException::class.java) { PhotonParser.parse(sample("bad_gateway.html")) }
    }
}

internal fun sample(name: String): String =
    checkNotNull(PhotonParserTest::class.java.getResource("/photon/$name")) { "Missing sample $name" }.readText()
