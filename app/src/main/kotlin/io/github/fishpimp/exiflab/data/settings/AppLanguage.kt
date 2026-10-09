package io.github.fishpimp.exiflab.data.settings

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
