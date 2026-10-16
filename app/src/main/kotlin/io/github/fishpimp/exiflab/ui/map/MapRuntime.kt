package io.github.fishpimp.exiflab.ui.map

import android.content.Context
import android.content.pm.PackageManager
import androidx.annotation.MainThread
import androidx.core.content.edit
import io.github.fishpimp.exiflab.BuildConfig
import io.github.fishpimp.exiflab.data.network.OfflineMode
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import org.maplibre.android.MapLibre
import org.maplibre.android.log.Logger
import org.maplibre.android.module.http.HttpRequestUtil
import org.maplibre.android.offline.OfflineManager

/**
 * Process-wide MapLibre setup, done the first time a map is shown rather than at app start.
 * MapLibre fetches tiles, sprites and glyphs through the app's allowlisted [OkHttpClient] and is
 * told it has no connection while offline mode is on.
 *
 * MapLibre keeps the tiles it shows in its own cache, so maps load faster and work offline. The
 * tiles show where viewed photos were taken, so the cache is kept small and emptied every
 * [MapCacheRetention.PERIOD_MS].
 *
 * @param httpClient the app's HTTP client; only created once a map is actually needed.
 */
class MapRuntime(
    private val context: Context,
    private val httpClient: () -> OkHttpClient,
    private val offlineMode: OfflineMode,
    private val scope: CoroutineScope,
) {
    private var available: Boolean? = null

    /**
     * Initializes MapLibre once. Returns false when maps cannot be shown on this device (no
     * Vulkan, or the native library failed to load); callers then show the drawn fallback.
     */
    @MainThread
    fun ensureInitialized(): Boolean {
        available?.let { return it }
        val result = supportsMapRendering(context) && runCatching { initialize() }.isSuccess
        available = result
        return result
    }

    private fun initialize() {
        // Tile URLs reveal where a photo was taken; keep them out of logcat.
        Logger.setVerbosity(if (BuildConfig.DEBUG) Logger.WARN else Logger.NONE)
        MapLibre.getInstance(context)
        HttpRequestUtil.setOkHttpClient(httpClient())
        HttpRequestUtil.setLogEnabled(false)
        HttpRequestUtil.setPrintRequestUrlOnFailure(false)
        scope.launch(Dispatchers.Main.immediate) {
            // null hands connectivity tracking back to MapLibre itself.
            offlineMode.changes.collect { offline -> MapLibre.setConnected(if (offline) false else null) }
        }
        scope.launch(Dispatchers.IO) { runCatching { limitCache() } }
    }

    /** Caps the tile cache, and empties it once its contents are [MapCacheRetention.PERIOD_MS] old. */
    private suspend fun limitCache() {
        val preferences = context.getSharedPreferences(CACHE_PREFERENCES, Context.MODE_PRIVATE)
        val now = System.currentTimeMillis()
        val clear = MapCacheRetention.isDue(preferences.getLong(KEY_CACHE_STARTED, 0L), now)
        withContext(Dispatchers.Main) {
            val offline = OfflineManager.getInstance(context)
            offline.setMaximumAmbientCacheSize(MapCacheRetention.MAX_BYTES, IgnoreResult)
            if (clear) offline.clearAmbientCache(IgnoreResult)
        }
        if (clear) preferences.edit { putLong(KEY_CACHE_STARTED, now) }
    }

    /** Cache maintenance is best effort; a failure leaves the cache as it was. */
    private object IgnoreResult : OfflineManager.FileSourceCallback {
        override fun onSuccess() = Unit

        override fun onError(message: String) = Unit
    }

    private companion object {
        const val CACHE_PREFERENCES = "map_cache"
        const val KEY_CACHE_STARTED = "started_at"

        /** Vulkan 1.0.3, the version MapLibre's renderer requires. */
        const val VULKAN_1_0_3 = 0x400003

        fun supportsMapRendering(context: Context): Boolean =
            context.packageManager.hasSystemFeature(PackageManager.FEATURE_VULKAN_HARDWARE_VERSION, VULKAN_1_0_3)
    }
}

/** How long and how much MapLibre's tile cache keeps; see [MapRuntime]. */
object MapCacheRetention {
    /** The cache is emptied once its oldest contents may be this old. */
    const val PERIOD_MS = 30L * 24 * 60 * 60 * 1000

    /** MapLibre's default is 50 MB; a photo's surroundings need far less. */
    const val MAX_BYTES = 20L * 1024 * 1024

    /** True when a cache started at [startedAt] (0 when unknown) is due to be emptied at [now]. */
    fun isDue(startedAt: Long, now: Long): Boolean = startedAt <= 0L || now - startedAt >= PERIOD_MS || now < startedAt
}
