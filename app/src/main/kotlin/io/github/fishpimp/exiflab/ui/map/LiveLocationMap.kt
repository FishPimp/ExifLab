package io.github.fishpimp.exiflab.ui.map

import android.content.Context
import android.view.View
import androidx.compose.foundation.layout.Box
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.saveable.Saver
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.CustomAccessibilityAction
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.customActions
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.viewinterop.AndroidView
import io.github.fishpimp.exiflab.R
import org.maplibre.android.camera.CameraPosition
import org.maplibre.android.camera.CameraUpdate
import org.maplibre.android.camera.CameraUpdateFactory
import org.maplibre.android.maps.MapLibreMap
import org.maplibre.android.maps.MapLibreMapOptions
import org.maplibre.android.maps.MapView
import org.maplibre.android.maps.Style
import org.maplibre.android.geometry.LatLng as MapLibreLatLng

/**
 * The MapLibre map behind [LocationMap]. Shown only when the map may go online and the device
 * can render it; everything else gets the drawn fallback.
 *
 * @param onFailed called when the map cannot load, so the caller can switch to the fallback.
 */
@Composable
internal fun LiveLocationMap(
    marker: LatLng?,
    interactive: Boolean,
    zoom: Double,
    onPick: ((LatLng) -> Unit)?,
    onFailed: () -> Unit,
    description: String,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    val density = LocalDensity.current
    val palette = rememberMapPalette()
    val pinImage = remember(palette, density) { pinBitmap(density, palette) }
    val currentOnPick by rememberUpdatedState(onPick)
    val currentOnFailed by rememberUpdatedState(onFailed)

    // Where the user left the interactive map, across rotation and process death.
    var savedCamera by rememberSaveable(stateSaver = SavedCamera.Saver) { mutableStateOf(null) }
    // The pin follows [marker], and jumps to a picked point right away.
    var pin by remember { mutableStateOf(marker) }
    var shownMarker by remember { mutableStateOf(marker) }

    val mapView = remember(context) {
        val start = savedCamera?.takeIf { interactive }?.toCameraPosition() ?: cameraFor(marker, zoom)
        MapView(context, mapOptions(context, palette, interactive, start)).apply {
            // TalkBack gets the map from Compose semantics below, with its custom actions.
            importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO_HIDE_DESCENDANTS
            onCreate(null)
        }
    }
    MapViewLifecycle(mapView)

    var map by remember(mapView) { mutableStateOf<MapLibreMap?>(null) }
    var style by remember(mapView) { mutableStateOf<Style?>(null) }

    DisposableEffect(mapView) {
        val failure = MapView.OnDidFailLoadingMapListener { currentOnFailed() }
        mapView.addOnDidFailLoadingMapListener(failure)
        mapView.getMapAsync { map = it }
        onDispose { mapView.removeOnDidFailLoadingMapListener(failure) }
    }

    val styleUri = MapStyles.uri(palette.dark)
    LaunchedEffect(map, styleUri) {
        val loadedMap = map ?: return@LaunchedEffect
        style = null
        loadedMap.setStyle(Style.Builder().fromUri(styleUri)) { loaded ->
            if (loaded.uri == styleUri) style = loaded
        }
    }
    LaunchedEffect(style, palette, pinImage) {
        val loaded = style ?: return@LaunchedEffect
        loaded.applyPalette(palette)
        MarkerLayers.install(loaded, palette, pinImage, pin)
    }
    LaunchedEffect(style, pin) {
        style?.let { MarkerLayers.setPosition(it, pin) }
    }

    LaunchedEffect(map, interactive) {
        map?.uiSettings?.apply {
            setAllGesturesEnabled(interactive)
            setTiltGesturesEnabled(false)
            setCompassEnabled(interactive)
            setLogoEnabled(false)
            setAttributionEnabled(false)
        }
    }

    // A fixed map always centers on the marker.
    LaunchedEffect(map, marker, zoom, interactive) {
        if (!interactive && marker != null) map?.moveCamera(CameraUpdateFactory.newLatLngZoom(marker.toMapLibre(), zoom))
    }
    // An interactive map follows a new marker from outside (a search result, say), but not one
    // the user just picked on the map itself.
    LaunchedEffect(marker) {
        if (marker == shownMarker) return@LaunchedEffect
        shownMarker = marker
        val pickedOnMap = marker == pin
        pin = marker
        if (interactive && marker != null && !pickedOnMap) {
            map?.animateCamera(CameraUpdateFactory.newLatLng(marker.toMapLibre()))
        }
    }

    val pick: (MapLibreLatLng) -> Boolean = remember {
        { point ->
            val callback = currentOnPick
            if (callback == null) {
                false
            } else {
                val picked = LatLng(point.latitude, point.longitude)
                pin = picked
                callback(picked)
                true
            }
        }
    }
    DisposableEffect(map) {
        val loadedMap = map ?: return@DisposableEffect onDispose { }
        val click = MapLibreMap.OnMapClickListener { pick(it) }
        val longClick = MapLibreMap.OnMapLongClickListener { pick(it) }
        val idle = MapLibreMap.OnCameraIdleListener { savedCamera = SavedCamera.from(loadedMap.cameraPosition) }
        loadedMap.addOnMapClickListener(click)
        loadedMap.addOnMapLongClickListener(longClick)
        loadedMap.addOnCameraIdleListener(idle)
        onDispose {
            loadedMap.removeOnMapClickListener(click)
            loadedMap.removeOnMapLongClickListener(longClick)
            loadedMap.removeOnCameraIdleListener(idle)
        }
    }

    val zoomIn = stringResource(R.string.map_action_zoom_in)
    val zoomOut = stringResource(R.string.map_action_zoom_out)
    val pinAtCenter = stringResource(R.string.map_action_pin_center)
    val canPick = onPick != null
    Box(
        modifier.semantics {
            contentDescription = description
            if (interactive) {
                // Gestures are hard with TalkBack; these give the same control from the actions menu.
                customActions = buildList {
                    add(CustomAccessibilityAction(zoomIn) { map.animate(CameraUpdateFactory.zoomIn()) })
                    add(CustomAccessibilityAction(zoomOut) { map.animate(CameraUpdateFactory.zoomOut()) })
                    if (canPick) {
                        add(CustomAccessibilityAction(pinAtCenter) { map?.cameraPosition?.target?.let(pick) ?: false })
                    }
                }
            }
        },
    ) {
        AndroidView(factory = { mapView }, modifier = Modifier.matchParentSize())
        MapAttribution(clickable = interactive, modifier = Modifier.align(Alignment.BottomEnd))
    }
}

private fun mapOptions(context: Context, palette: MapPalette, interactive: Boolean, camera: CameraPosition) =
    MapLibreMapOptions.createFromAttributes(context)
        // A TextureView clips to rounded corners and scrolls smoothly inside Compose layouts.
        .textureMode(true)
        .camera(camera)
        .foregroundLoadColor(palette.land.toArgb())
        .attributionEnabled(false)
        .logoEnabled(false)
        .compassEnabled(interactive)
        .tiltGesturesEnabled(false)
        .minZoomPreference(MIN_ZOOM)
        .maxZoomPreference(MAX_ZOOM)

private fun cameraFor(marker: LatLng?, zoom: Double): CameraPosition = CameraPosition.Builder()
    .target(marker?.toMapLibre() ?: MapLibreLatLng(WORLD_CENTER_LATITUDE, 0.0))
    .zoom(if (marker != null) zoom else WORLD_ZOOM)
    .build()

private fun LatLng.toMapLibre() = MapLibreLatLng(latitude, longitude)

/** Animates the camera; false when the map is not ready yet. */
private fun MapLibreMap?.animate(update: CameraUpdate): Boolean {
    if (this == null) return false
    animateCamera(update)
    return true
}

/** The camera of an interactive map, kept in saved state. */
internal data class SavedCamera(val latitude: Double, val longitude: Double, val zoom: Double, val bearing: Double) {
    fun toCameraPosition(): CameraPosition = CameraPosition.Builder()
        .target(MapLibreLatLng(latitude, longitude))
        .zoom(zoom)
        .bearing(bearing)
        .build()

    companion object {
        fun from(position: CameraPosition): SavedCamera? {
            val target = position.target ?: return null
            return SavedCamera(target.latitude, target.longitude, position.zoom, position.bearing)
        }

        val Saver = Saver<SavedCamera?, DoubleArray>(
            save = { camera -> camera?.let { doubleArrayOf(it.latitude, it.longitude, it.zoom, it.bearing) } },
            restore = { values -> SavedCamera(values[0], values[1], values[2], values[3]) },
        )
    }
}

private const val MIN_ZOOM = 1.0
private const val MAX_ZOOM = 19.0
private const val WORLD_ZOOM = 1.5
private const val WORLD_CENTER_LATITUDE = 30.0
