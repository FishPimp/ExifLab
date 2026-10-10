package io.github.fishpimp.exiflab.data.network

import okhttp3.HttpUrl
import java.io.IOException

/**
 * The only hosts ExifLab ever talks to. Matching is exact: subdomains and look-alike hosts are
 * refused like any other host.
 */
object AllowedHosts {
    /** OpenFreeMap: vector tiles, TileJSON, sprites and glyphs for the map. */
    const val MAP_TILES = "tiles.openfreemap.org"

    /** Photon by Komoot: place search. */
    const val PLACE_SEARCH = "photon.komoot.io"

    val all: Set<String> = setOf(MAP_TILES, PLACE_SEARCH)
}

/**
 * A request the network policy refused. It never left the device. Subclasses [IOException] so
 * OkHttp callers (MapLibre included) treat it like any other failed request.
 */
sealed class BlockedRequestException(message: String) : IOException(message)

/** Offline mode is on, so no request may be made. */
class OfflineModeException : BlockedRequestException("Offline mode is on")

/** The request targeted a host outside [AllowedHosts]. */
class HostNotAllowedException(val host: String) : BlockedRequestException("Host $host is not on the allowlist")

/** The request did not use HTTPS. */
class InsecureRequestException : BlockedRequestException("Only HTTPS requests are allowed")

/**
 * Decides whether a request may leave the device: never while offline mode is on, only over
 * HTTPS and only to an allowlisted host. Request URLs are never logged or put into exception
 * messages, since they can carry search text or a map area.
 *
 * @param isOffline reads the offline-mode preference. Called on OkHttp's threads for every
 *   request and redirect, so it may block briefly but must never be called on the main thread.
 */
class NetworkPolicy(
    private val isOffline: () -> Boolean,
    private val allowedHosts: Set<String> = AllowedHosts.all,
    private val requireHttps: Boolean = true,
) {
    fun isHostAllowed(host: String): Boolean = host.lowercase() in allowedHosts

    /** Throws a [BlockedRequestException] unless a request to [url] is allowed right now. */
    @Throws(BlockedRequestException::class)
    fun check(url: HttpUrl) {
        if (isOffline()) throw OfflineModeException()
        if (requireHttps && !url.isHttps) throw InsecureRequestException()
        if (!isHostAllowed(url.host)) throw HostNotAllowedException(url.host)
    }
}
