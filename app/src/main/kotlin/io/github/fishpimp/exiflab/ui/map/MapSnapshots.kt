package io.github.fishpimp.exiflab.ui.map

import android.content.Context
import android.graphics.Bitmap
import androidx.compose.material3.ColorScheme
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Canvas
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.CanvasDrawScope
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.LayoutDirection
import io.github.fishpimp.exiflab.data.network.NetworkMonitor
import io.github.fishpimp.exiflab.data.network.OfflineMode
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import org.maplibre.android.camera.CameraPosition
import org.maplibre.android.maps.Style
import org.maplibre.android.snapshotter.MapSnapshotter
import kotlin.coroutines.resume
import org.maplibre.android.geometry.LatLng as MapLibreLatLng

/** Whether a map image can be made right now. */
enum class MapSnapshotAvailability { Available, Offline, Unsupported }

/**
 * Still map images for exports, drawn by MapLibre's snapshotter with the app's map style, colors
 * and pin. Tiles come through the app's allowlisted HTTP client like every other map, so nothing
 * is fetched while offline mode is on.
 */
class MapSnapshots(
    private val context: Context,
    private val runtime: MapRuntime,
    private val offlineMode: OfflineMode,
    private val networkMonitor: NetworkMonitor,
) {
    suspend fun availability(): MapSnapshotAvailability {
        if (offlineMode.current() || !networkMonitor.isConnectedNow()) return MapSnapshotAvailability.Offline
        val supported = withContext(Dispatchers.Main) { runtime.ensureInitialized() }
        return if (supported) MapSnapshotAvailability.Available else MapSnapshotAvailability.Unsupported
    }

    /**
     * A [width] by [height] (in points or dp) light map centered on the position, with the pin, at
     * [pixelRatio] pixels per unit. Null when it fails or takes longer than [timeoutMillis].
     */
    suspend fun snapshot(
        latitude: Double,
        longitude: Double,
        width: Int,
        height: Int,
        scheme: ColorScheme,
        pixelRatio: Float = DEFAULT_PIXEL_RATIO,
        timeoutMillis: Long = TIMEOUT_MILLIS,
    ): Bitmap? {
        if (availability() != MapSnapshotAvailability.Available) return null
        val palette = mapPalette(scheme, dark = false)
        val bitmap = withTimeoutOrNull(timeoutMillis) {
            withContext(Dispatchers.Main) {
                suspendCancellableCoroutine<Bitmap?> { continuation ->
                    val options = MapSnapshotter.Options(width, height)
                        .withStyleBuilder(Style.Builder().fromUri(MapStyles.uri(dark = false)))
                        .withCameraPosition(CameraPosition.Builder().target(MapLibreLatLng(latitude, longitude)).zoom(ZOOM).build())
                        .withPixelRatio(pixelRatio)
                        .withLogo(false)
                        .withAttribution(false)
                    val snapshotter = MapSnapshotter(context, options)
                    snapshotter.setObserver(
                        object : MapSnapshotter.Observer {
                            override fun onDidFinishLoadingStyle() {
                                runCatching { applyPalette(palette, snapshotter::getLayer) }
                            }

                            override fun onStyleImageMissing(imageName: String) = Unit
                        },
                    )
                    continuation.invokeOnCancellation { runCatching { snapshotter.cancel() } }
                    snapshotter.start(
                        { snapshot ->
                            val image = runCatching { snapshot.bitmap.copy(Bitmap.Config.ARGB_8888, true) }.getOrNull()
                            if (continuation.isActive) continuation.resume(image)
                        },
                        { if (continuation.isActive) continuation.resume(null) },
                    )
                }
            }
        } ?: return null
        // The camera is centered on the position, so the pin goes in the middle.
        val density = Density(pixelRatio)
        CanvasDrawScope().draw(density, LayoutDirection.Ltr, Canvas(bitmap.asImageBitmap()), Size(bitmap.width.toFloat(), bitmap.height.toFloat())) {
            drawLocationMark(Offset(size.width / 2f, size.height / 2f), palette)
        }
        return bitmap
    }

    private companion object {
        /** Neighborhood level: streets and place names around the spot. */
        const val ZOOM = 14.0
        const val DEFAULT_PIXEL_RATIO = 2f
        const val TIMEOUT_MILLIS = 20_000L
    }
}
