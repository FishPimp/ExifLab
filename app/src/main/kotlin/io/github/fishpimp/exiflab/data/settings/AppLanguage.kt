package io.github.fishpimp.exiflab.data.settings

import android.content.Context
import android.content.res.Configuration
import android.os.Build
import android.os.LocaleList
import androidx.appcompat.app.AppCompatDelegate
import androidx.core.os.LocaleListCompat

/** In-app language choice, backed by Android's per-app language support. */
enum class AppLanguage(val tag: String?) {
    System(null),
    English("en"),
    Swedish("sv");

    companion object {
        fun current(): AppLanguage {
            val locales = AppCompatDelegate.getApplicationLocales()
            if (locales.isEmpty) return System
            val language = locales[0]?.language
            return entries.firstOrNull { it.tag == language } ?: System
        }

        /** The language the app shows right now: the in-app choice, else the system language. */
        fun resolvedLanguageCode(): String? =
            AppCompatDelegate.getApplicationLocales()[0]?.language
                ?: LocaleListCompat.getAdjustedDefault()[0]?.language

        fun apply(language: AppLanguage) {
            val locales = language.tag?.let { LocaleListCompat.forLanguageTags(it) }
                ?: LocaleListCompat.getEmptyLocaleList()
            AppCompatDelegate.setApplicationLocales(locales)
        }
    }
}

/**
 * This context with the in-app language applied, for text made outside an activity (batch
 * notifications, the PDF report). On Android 13+ the system applies it to the whole app; on
 * Android 10-12 AppCompat only applies it to activities, so the application context still has the
 * system language. Call it each time: the choice can change while the app runs.
 */
fun Context.appLocalized(): Context {
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) return this
    val locales = AppCompatDelegate.getApplicationLocales()
    return if (locales.isEmpty) this else withLocales(locales.toLanguageTags())
}

/** This context with its resources (strings, formats) in [languageTags], e.g. "sv". */
internal fun Context.withLocales(languageTags: String): Context =
    createConfigurationContext(Configuration(resources.configuration).apply { setLocales(LocaleList.forLanguageTags(languageTags)) })
