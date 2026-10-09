package io.github.fishpimp.exiflab.data.photos

import io.github.fishpimp.exiflab.data.store.JsonListStore
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.mapLatest
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.withContext
import java.io.IOException

/**
 * The last photos the user opened, newest first. Only photos ExifLab can still open after a
 * restart are listed: those with a persisted grant of their own or inside a granted folder.
 * Session-only grants (most shared photos) are therefore never kept.
 *
 * @param readGrants reads the persisted URI grants; blocking, called on [ioDispatcher].
 */
class RecentPhotosRepository(
    private val store: JsonListStore<PhotoRef>,
    private val readGrants: () -> GrantSnapshot,
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO,
) {
    private val refreshes = MutableStateFlow(0)

    /** Recent photos that are still accessible. Grants are re-checked on every [refresh]. */
    @OptIn(ExperimentalCoroutinesApi::class)
    val recents: Flow<List<PhotoRef>> = combine(store.items, refreshes) { items, _ -> items }
        .mapLatest { items ->
            val grants = readGrants()
            items.filter { grants.canRead(it.uri) }
        }
        .flowOn(ioDispatcher)

    /** Re-checks access, e.g. when the app returns to the foreground after settings changed. */
    fun refresh() {
        refreshes.update { it + 1 }
    }

    /**
     * Moves [ref] to the front of the list, dropping duplicates and entries that lost access.
     * Best effort: a storage failure leaves the list as it was.
     */
    suspend fun record(ref: PhotoRef) = withContext(ioDispatcher) {
        val grants = readGrants()
        try {
            store.update { current -> RecentPhotos.record(current, ref) { grants.canRead(it.uri) } }
        } catch (_: IOException) {
            // Recents are a convenience; never fail opening a photo over them.
        }
    }
}

/** Pure list logic behind [RecentPhotosRepository]. */
internal object RecentPhotos {
    const val LIMIT = 20

    /** [ref] first, then the rest of [current] without duplicates of its URI, accessible only, capped. */
    fun record(
        current: List<PhotoRef>,
        ref: PhotoRef,
        limit: Int = LIMIT,
        isAccessible: (PhotoRef) -> Boolean,
    ): List<PhotoRef> = (sequenceOf(ref) + current.asSequence().filter { it.uri != ref.uri })
        .filter(isAccessible)
        .take(limit)
        .toList()
}
