package io.github.fishpimp.exiflab.data.places

import io.github.fishpimp.exiflab.ui.map.LatLng
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/**
 * Photon's GeoJSON answer. Only the fields ExifLab shows are declared; everything else is ignored.
 * See https://github.com/komoot/photon for the format.
 */
@Serializable
internal data class PhotonResponse(val features: List<PhotonFeature> = emptyList())

@Serializable
internal data class PhotonFeature(
    val geometry: PhotonGeometry? = null,
    val properties: PhotonProperties = PhotonProperties(),
)

@Serializable
internal data class PhotonGeometry(
    val type: String? = null,
    /** GeoJSON order: longitude first. */
    val coordinates: List<Double> = emptyList(),
)

@Serializable
internal data class PhotonProperties(
    val name: String? = null,
    val type: String? = null,
    @SerialName("osm_key") val osmKey: String? = null,
    @SerialName("osm_value") val osmValue: String? = null,
    val housenumber: String? = null,
    val street: String? = null,
    val district: String? = null,
    val locality: String? = null,
    val city: String? = null,
    val county: String? = null,
    val state: String? = null,
    val country: String? = null,
)

/** Turns Photon responses into [Place]s. */
internal object PhotonParser {
    private val json = Json {
        ignoreUnknownKeys = true
        coerceInputValues = true
    }

    /**
     * Parses a response body. Results without a usable name or position are skipped, and results
     * that would look identical in a list are shown once.
     *
     * @throws kotlinx.serialization.SerializationException when the body is not Photon GeoJSON.
     */
    fun parse(body: String): List<Place> =
        json.decodeFromString(PhotonResponse.serializer(), body).features
            .mapNotNull(::toPlace)
            .distinctBy { listOf(it.name, it.locality, it.region, it.country, it.kind) }

    private fun toPlace(feature: PhotonFeature): Place? {
        val coordinates = feature.geometry?.coordinates ?: return null
        if (coordinates.size < 2) return null
        val latLng = LatLng(latitude = coordinates[1], longitude = coordinates[0])
        if (!latLng.isValid) return null

        val p = feature.properties
        val kind = kindOf(p)
        val streetAddress = p.street.clean()?.let { street -> p.housenumber.clean()?.let { "$street $it" } ?: street }
        val name = p.name.clean() ?: streetAddress ?: return null

        // Context lines name the larger places around the result, never the result itself.
        val locality = when (kind) {
            PlaceKind.City, PlaceKind.Region, PlaceKind.Country -> null
            else -> firstDistinct(name, p.city, p.locality, p.district)
        }
        val region = when (kind) {
            PlaceKind.Region, PlaceKind.Country -> null
            else -> firstDistinct(name, p.state, p.county)
        }
        val country = if (kind == PlaceKind.Country) null else p.country.clean()?.takeIf { it != name }
        return Place(name = name, locality = locality, region = region, country = country, latLng = latLng, kind = kind)
    }

    private fun kindOf(p: PhotonProperties): PlaceKind = when (p.type) {
        "house" -> if (p.name.clean() == null || p.osmKey in addressKeys) PlaceKind.Address else PlaceKind.PointOfInterest
        "street" -> PlaceKind.Street
        "district", "locality" -> PlaceKind.District
        "city" -> PlaceKind.City
        "county", "state" -> PlaceKind.Region
        "country" -> PlaceKind.Country
        else -> PlaceKind.Other
    }

    private val addressKeys = setOf("building", "place")

    private fun firstDistinct(name: String, vararg candidates: String?): String? =
        candidates.firstNotNullOfOrNull { candidate -> candidate.clean()?.takeIf { it != name } }

    private fun String?.clean(): String? = this?.trim()?.takeIf { it.isNotEmpty() }
}
