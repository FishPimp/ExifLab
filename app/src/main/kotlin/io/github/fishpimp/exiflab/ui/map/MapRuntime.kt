package io.github.fishpimp.exiflab.ui.map

import android.content.Context
import android.content.pm.PackageManager
import androidx.annotation.MainThread
import io.github.fishpimp.exiflab.BuildConfig
import io.github.fishpimp.exiflab.data.network.OfflineMode
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import okhttp3.OkHttpClient
import org.maplibre.android.MapLibre
import org.maplibre.android.log.Logger
import org.maplibre.android.module.http.HttpRequestUtil

/**
 * Process-wide MapLibre setup, done the first time a map is shown rather than at app start.
 * MapLibre fetches tiles, sprites and glyphs through the app's allowlisted [OkHttpClient] and is
 * told it has no connection while offline mode is on.
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
    }

    private companion object {
        /** Vulkan 1.0.3, the version MapLibre's renderer requires. */
        const val VULKAN_1_0_3 = 0x400003

        fun supportsMapRendering(context: Context): Boolean =
            context.packageManager.hasSystemFeature(PackageManager.FEATURE_VULKAN_HARDWARE_VERSION, VULKAN_1_0_3)
    }
}
