package io.github.fishpimp.exiflab.data.places

import io.github.fishpimp.exiflab.data.network.AllowedHosts
import io.github.fishpimp.exiflab.data.network.OfflineModeException
import io.github.fishpimp.exiflab.data.network.await
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.transformLatest
import kotlinx.coroutines.withContext
import kotlinx.serialization.SerializationException
import okhttp3.Call
import okhttp3.HttpUrl
import okhttp3.Request
import java.io.IOException

/**
 * Searches places by name or address with Photon (photon.komoot.io), through the app's
 * allowlisted HTTP client. Only the search text, a result limit and optionally a language leave
 * the device.
 *
 * [search] is a plain suspend function: cancelling the caller cancels the request, which makes
 * it safe to drive from `debounce` plus `mapLatest`, or from [searchAsYouType].
 *
 * @param client the app's HTTP client.
 * @param isOffline reads the offline-mode preference.
 * @param appLanguage the app's current language code ("en", "sv", ...).
 */
class PlaceSearchRepository(
    private val client: Call.Factory,
    private val isOffline: suspend () -> Boolean,
    private val appLanguage: () -> String?,
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO,
) {
    /** Searches for [query]. Queries shorter than [MIN_QUERY_LENGTH] return no results without a request. */
    suspend fun search(query: String): PlaceSearchResult {
        val text = query.trim().take(MAX_QUERY_LENGTH)
        if (text.length < MIN_QUERY_LENGTH) return PlaceSearchResult.Success(emptyList())
        if (isOffline()) return PlaceSearchResult.Failure(PlaceSearchError.Offline)

        val request = Request.Builder().url(searchUrl(text, photonLanguage(appLanguage()))).get().build()
        return withContext(ioDispatcher) {
            try {
                client.newCall(request).await().use { response ->
                    if (!response.isSuccessful) {
                        PlaceSearchResult.Failure(PlaceSearchError.Server)
                    } else {
                        PlaceSearchResult.Success(PhotonParser.parse(response.body.string()))
                    }
                }
            } catch (_: OfflineModeException) {
                PlaceSearchResult.Failure(PlaceSearchError.Offline)
            } catch (_: IOException) {
                PlaceSearchResult.Failure(PlaceSearchError.Network)
            } catch (_: SerializationException) {
                PlaceSearchResult.Failure(PlaceSearchError.Server)
            }
        }
    }

    /**
     * Search-as-you-type: waits until typing pauses for [debounceMillis], drops a search as soon
     * as newer text arrives, and reports progress as [PlaceSearchState]s.
     */
    @OptIn(FlowPreview::class, ExperimentalCoroutinesApi::class)
    fun searchAsYouType(queries: Flow<String>, debounceMillis: Long = DEBOUNCE_MILLIS): Flow<PlaceSearchState> =
        queries
            .map { it.trim() }
            .distinctUntilChanged()
            .debounce { if (it.length < MIN_QUERY_LENGTH) 0L else debounceMillis }
            .transformLatest { text ->
                if (text.length < MIN_QUERY_LENGTH) {
                    emit(PlaceSearchState.Idle)
                } else {
                    emit(PlaceSearchState.Searching(text))
                    emit(search(text).toState(text))
                }
            }

    companion object {
        const val MIN_QUERY_LENGTH = 2
        const val MAX_QUERY_LENGTH = 200
        const val RESULT_LIMIT = 8

        /** Pause in typing before a search is sent; keeps the free service from being flooded. */
        const val DEBOUNCE_MILLIS = 350L

        /** Languages Photon can label results in; for any other app language it uses local names. */
        private val photonLanguages = setOf("en", "de", "fr")

        internal fun photonLanguage(appLanguage: String?): String? =
            appLanguage?.lowercase()?.takeIf { it in photonLanguages }

        internal fun searchUrl(query: String, language: String?): HttpUrl = HttpUrl.Builder()
            .scheme("https")
            .host(AllowedHosts.PLACE_SEARCH)
            .addPathSegment("api")
            .addPathSegment("")
            .addQueryParameter("q", query)
            .addQueryParameter("limit", RESULT_LIMIT.toString())
            .apply { if (language != null) addQueryParameter("lang", language) }
            .build()
    }
}

/** Progress of a search-as-you-type session. */
sealed interface PlaceSearchState {
    /** No search: the text is empty or too short. */
    data object Idle : PlaceSearchState

    data class Searching(val query: String) : PlaceSearchState

    data class Results(val query: String, val places: List<Place>) : PlaceSearchState

    data class Failed(val query: String, val error: PlaceSearchError) : PlaceSearchState
}

private fun PlaceSearchResult.toState(query: String): PlaceSearchState = when (this) {
    is PlaceSearchResult.Success -> PlaceSearchState.Results(query, places)
    is PlaceSearchResult.Failure -> PlaceSearchState.Failed(query, error)
}
