package io.github.fishpimp.exiflab.data.settings

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import io.github.fishpimp.exiflab.designsystem.theme.BrandPalette
import io.github.fishpimp.exiflab.designsystem.theme.ContrastPreference
import io.github.fishpimp.exiflab.designsystem.theme.ThemeMode
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map

data class AppSettings(
    val themeMode: ThemeMode = ThemeMode.System,
    val dynamicColor: Boolean = true,
    val palette: BrandPalette = BrandPalette.Lagoon,
    val contrast: ContrastPreference = ContrastPreference.System,
    /** When on, ExifLab makes no network requests at all: no map tiles, no place search. */
    val offlineMode: Boolean = false,
)

private val Context.settingsDataStore: DataStore<Preferences> by preferencesDataStore(name = "settings")

/** Persists user preferences. Language is stored by the platform (per-app locales), not here. */
class SettingsRepository(context: Context) {
    private val dataStore = context.applicationContext.settingsDataStore

    val settings: Flow<AppSettings> = dataStore.data.map { prefs ->
        AppSettings(
            themeMode = prefs[Keys.ThemeMode].toEnum(ThemeMode.System),
            dynamicColor = prefs[Keys.DynamicColor] ?: true,
            palette = prefs[Keys.Palette].toEnum(BrandPalette.Lagoon),
            contrast = prefs[Keys.Contrast].toEnum(ContrastPreference.System),
            offlineMode = prefs[Keys.OfflineMode] ?: false,
        )
    }

    /** Just the offline-mode flag, re-emitted only when it changes. */
    val offlineMode: Flow<Boolean> = settings.map { it.offlineMode }.distinctUntilChanged()

    suspend fun setThemeMode(mode: ThemeMode) = dataStore.edit { it[Keys.ThemeMode] = mode.name }

    suspend fun setDynamicColor(enabled: Boolean) = dataStore.edit { it[Keys.DynamicColor] = enabled }

    suspend fun setPalette(palette: BrandPalette) = dataStore.edit { it[Keys.Palette] = palette.name }

    suspend fun setContrast(contrast: ContrastPreference) = dataStore.edit { it[Keys.Contrast] = contrast.name }

    suspend fun setOfflineMode(enabled: Boolean) = dataStore.edit { it[Keys.OfflineMode] = enabled }

    private object Keys {
        val ThemeMode = stringPreferencesKey("theme_mode")
        val DynamicColor = booleanPreferencesKey("dynamic_color")
        val Palette = stringPreferencesKey("palette")
        val Contrast = stringPreferencesKey("contrast")
        val OfflineMode = booleanPreferencesKey("offline_mode")
    }
}

private inline fun <reified E : Enum<E>> String?.toEnum(default: E): E =
    this?.let { name -> enumValues<E>().firstOrNull { it.name == name } } ?: default
