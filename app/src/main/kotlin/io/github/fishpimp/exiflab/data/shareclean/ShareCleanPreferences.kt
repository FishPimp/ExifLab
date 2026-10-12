package io.github.fishpimp.exiflab.data.shareclean

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.core.stringSetPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import io.github.fishpimp.exiflab.metadata.edit.StripCategory
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

/** Remembers the last Share clean choice. */
interface ShareCleanChoices {
    val choice: Flow<ShareCleanChoice>

    suspend fun save(choice: ShareCleanChoice)
}

private val Context.shareCleanDataStore: DataStore<Preferences> by preferencesDataStore(name = "share_clean")

/** [ShareCleanChoices] in its own DataStore file, apart from the app settings. */
class ShareCleanPreferences(context: Context) : ShareCleanChoices {
    private val dataStore = context.applicationContext.shareCleanDataStore

    override val choice: Flow<ShareCleanChoice> = dataStore.data.map { prefs -> decode(prefs) }

    override suspend fun save(choice: ShareCleanChoice) {
        dataStore.edit { prefs ->
            prefs[Keys.Preset] = choice.preset.name
            prefs[Keys.Custom] = choice.custom.mapTo(LinkedHashSet()) { it.name }
            prefs[Keys.IncludeRawPreviews] = choice.includeRawPreviews
        }
    }

    private object Keys {
        val Preset = stringPreferencesKey("preset")
        val Custom = stringSetPreferencesKey("custom_categories")
        val IncludeRawPreviews = booleanPreferencesKey("include_raw_previews")
    }

    private companion object {
        fun decode(prefs: Preferences): ShareCleanChoice {
            val defaults = ShareCleanChoice()
            val preset = prefs[Keys.Preset]?.let { name -> ShareCleanPreset.entries.firstOrNull { it.name == name } }
            val custom = prefs[Keys.Custom]?.mapNotNullTo(LinkedHashSet()) { name -> StripCategory.entries.firstOrNull { it.name == name } }
            return ShareCleanChoice(
                preset = preset ?: defaults.preset,
                custom = custom ?: defaults.custom,
                includeRawPreviews = prefs[Keys.IncludeRawPreviews] ?: defaults.includeRawPreviews,
            )
        }
    }
}
