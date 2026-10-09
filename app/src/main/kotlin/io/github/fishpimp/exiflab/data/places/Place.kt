package io.github.fishpimp.exiflab.data.places

import io.github.fishpimp.exiflab.ui.map.LatLng

/**
 * A place search result.
 *
 * @property name what the place is called: a city, a street, "Drottninggatan 12", a museum.
 * @property locality the town or city it lies in, when that is not the place itself.
 * @property region the state, province or county, when known and not the place itself.
 * @property country the country, unless the place is the country.
 */
data class Place(
    val name: String,
    val locality: String?,
    val region: String?,
    val country: String?,
    val latLng: LatLng,
    val kind: PlaceKind,
)

/** What kind of place a result is, for choosing an icon and a sensible zoom. */
enum class PlaceKind {
    /** A building or street address. */
    Address,

    /** A named point of interest: a museum, a park, a station. */
    PointOfInterest,
    Street,

    /** A neighbourhood, district or village part of a larger place. */
    District,

    /** A city, town or village. */
    City,

    /** A county, state or province. */
    Region,
    Country,
    Other,
}

/** Why a search failed. */
enum class PlaceSearchError {
    /** Offline mode is on, so nothing was sent. */
    Offline,

    /** The request did not get through: no connection, a timeout, a blocked host. */
    Network,

    /** The search service answered with an error or something unreadable. */
    Server,
}

sealed interface PlaceSearchResult {
    data class Success(val places: List<Place>) : PlaceSearchResult

    data class Failure(val error: PlaceSearchError) : PlaceSearchResult
}
