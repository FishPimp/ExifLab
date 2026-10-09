package io.github.fishpimp.exiflab.data.network

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.runBlocking

/**
 * The offline-mode preference, readable synchronously by the network layer. Until the stored
 * value has loaded, readers wait for it instead of guessing, so a request can never slip out in
 * the moment after app start.
 *
 * @param preference the stored offline-mode flag.
 */
class OfflineMode(preference: Flow<Boolean>, scope: CoroutineScope) {
    private val state: StateFlow<Boolean?> = preference
        .distinctUntilChanged()
        .stateIn(scope, SharingStarted.Eagerly, null)

    /** The preference, once loaded, and every change after that. */
    val changes: Flow<Boolean> = state.filterNotNull()

    /** The current value, or null while it is still loading. */
    val currentOrNull: Boolean? get() = state.value

    /** The current value, waiting for it to load if needed. */
    suspend fun current(): Boolean = state.value ?: changes.first()

    /**
     * The current value for callers on background threads, such as OkHttp interceptors. Blocks
     * only until the preference has loaded once.
     */
    fun currentBlocking(): Boolean = state.value ?: runBlocking { changes.first() }
}
