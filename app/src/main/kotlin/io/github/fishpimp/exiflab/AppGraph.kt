package io.github.fishpimp.exiflab

import android.content.Context
import io.github.fishpimp.exiflab.data.settings.SettingsRepository

/**
 * Manual dependency container. One instance lives in [ExifLabApplication]; screens reach it
 * through [appGraph]. Keep construction lazy so app start stays cheap.
 */
class AppGraph(context: Context) {
    private val appContext = context.applicationContext

    val settingsRepository: SettingsRepository by lazy { SettingsRepository(appContext) }
}

val Context.appGraph: AppGraph
    get() = (applicationContext as ExifLabApplication).graph
