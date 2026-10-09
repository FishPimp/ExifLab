package io.github.fishpimp.exiflab.data.store

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.emptyPreferences
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.serialization.KSerializer
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json
import java.io.IOException

private val Context.libraryDataStore: DataStore<Preferences> by preferencesDataStore(name = "library")

/** The DataStore that holds granted folders and recent photos. One instance per process. */
val Context.libraryPreferences: DataStore<Preferences>
    get() = applicationContext.libraryDataStore

/**
 * A list of serializable items kept as JSON under one key of a preferences DataStore.
 * Missing or unreadable data reads as an empty list, so a corrupt entry never blocks the app.
 */
class JsonListStore<T>(
    private val dataStore: DataStore<Preferences>,
    name: String,
    serializer: KSerializer<T>,
) {
    private val key = stringPreferencesKey(name)
    private val listSerializer = ListSerializer(serializer)

    /** The current list, re-emitted whenever it changes. */
    val items: Flow<List<T>> = dataStore.data
        .catch { e -> if (e is IOException) emit(emptyPreferences()) else throw e }
        .map { decode(it[key]) }
        .distinctUntilChanged()

    /** Atomically replaces the list with [transform] applied to the stored one. */
    suspend fun update(transform: (List<T>) -> List<T>) {
        dataStore.edit { prefs ->
            prefs[key] = json.encodeToString(listSerializer, transform(decode(prefs[key])))
        }
    }

    private fun decode(raw: String?): List<T> =
        raw?.let { runCatching { json.decodeFromString(listSerializer, it) }.getOrNull() }.orEmpty()

    private companion object {
        val json = Json { ignoreUnknownKeys = true }
    }
}
