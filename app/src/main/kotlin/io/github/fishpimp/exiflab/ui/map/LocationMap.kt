package io.github.fishpimp.exiflab.ui.map

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalInspectionMode
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.onClick
import androidx.compose.ui.semantics.role
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.github.fishpimp.exiflab.ExifLabApplication
import io.github.fishpimp.exiflab.R

object LocationMapDefaults {
    /** Street level: the neighbourhood around a photo, with street names. */
    const val ZOOM = 14.0
}

/**
 * OpenStreetMap-based map showing [marker], drawn with MapLibre and OpenFreeMap tiles in the
 * app's colors. When maps may not or cannot go online (offline mode, no connection, previews,
 * unsupported devices) a drawn stand-in with the pin and coordinates takes its place.
 *
 * A fixed map ([interactive] false and no [onPick]) has no gestures, stays centered on the marker
 * at [zoom], and lets touches through to a scrolling parent; [onClick] makes it a button, for
 * example to open the full map. An interactive map pans, zooms and rotates; with [onPick], a tap
 * or long press moves the pin and reports the point.
 *
 * @param shape the map's outline; pass `RectangleShape` for a full-screen map.
 */
@Composable
fun LocationMap(
    marker: LatLng?,
    modifier: Modifier = Modifier,
    interactive: Boolean = false,
    onPick: ((LatLng) -> Unit)? = null,
    onClick: (() -> Unit)? = null,
    zoom: Double = LocationMapDefaults.ZOOM,
    shape: Shape = MaterialTheme.shapes.large,
) {
    val position = marker?.takeIf { it.isValid }
    val isInteractive = interactive || onPick != null
    val availability = rememberMapAvailability()
    var failed by remember { mutableStateOf(false) }

    val note = when {
        availability == MapAvailability.OfflineMode -> MapNote.OfflineMode
        availability == MapAvailability.Unsupported || failed -> MapNote.Unavailable
        availability == MapAvailability.NoConnection -> MapNote.NoConnection
        else -> null
    }
    val description = listOfNotNull(
        position?.let { stringResource(R.string.map_description, it.formatted()) }
            ?: stringResource(R.string.map_description_no_location),
        note?.let { stringResource(it.text) },
    ).joinToString(separator = ". ")
    val openLabel = stringResource(R.string.map_action_open)
    val palette = rememberMapPalette()

    Box(
        modifier
            .clip(shape)
            .background(palette.sketchSurface)
            .then(
                if (isInteractive) {
                    Modifier
                } else {
                    // A fixed map is one element for TalkBack: what it shows, and a button if it opens.
                    Modifier.clearAndSetSemantics {
                        contentDescription = description
                        if (onClick != null) {
                            role = Role.Button
                            onClick(label = openLabel) {
                                onClick()
                                true
                            }
                        }
                    }
                },
            ),
    ) {
        if (availability == MapAvailability.Live && !failed) {
            LiveLocationMap(
                marker = position,
                interactive = isInteractive,
                zoom = zoom,
                onPick = onPick,
                onFailed = { failed = true },
                description = description,
                modifier = Modifier.matchParentSize(),
            )
        } else {
            LocationMapFallback(
                marker = position,
                note = note,
                showCoordinates = !isInteractive,
                description = description,
                modifier = Modifier.matchParentSize(),
            )
        }
        if (!isInteractive) {
            // Covers a fixed map so it never takes touches: taps open it, drags scroll the parent.
            Box(
                Modifier
                    .matchParentSize()
                    .then(
                        if (onClick != null) {
                            Modifier.clickable(onClickLabel = openLabel, role = Role.Button, onClick = onClick)
                        } else {
                            Modifier.pointerInput(Unit) { }
                        },
                    ),
            )
        }
    }
}

/** Whether the real map can be shown, and if not, why. */
internal enum class MapAvailability {
    Live,

    /** The offline-mode preference has not loaded yet. */
    Loading,
    OfflineMode,
    NoConnection,

    /** The device cannot render MapLibre maps, or it failed to start. */
    Unsupported,

    /** Compose previews and tests: never start MapLibre. */
    Preview,
}

@Composable
private fun rememberMapAvailability(): MapAvailability {
    if (LocalInspectionMode.current) return MapAvailability.Preview
    val context = LocalContext.current
    val graph = remember(context) { (context.applicationContext as? ExifLabApplication)?.graph }
        ?: return MapAvailability.Preview

    val offline by graph.offlineMode.changes.collectAsStateWithLifecycle(initialValue = graph.offlineMode.currentOrNull)
    val connected by remember(graph) { graph.networkMonitor.isConnected }
        .collectAsStateWithLifecycle(initialValue = remember(graph) { graph.networkMonitor.isConnectedNow() })
    return when (offline) {
        null -> MapAvailability.Loading
        true -> MapAvailability.OfflineMode
        // MapLibre starts only once a map may actually be shown online.
        false -> when {
            !graph.mapRuntime.ensureInitialized() -> MapAvailability.Unsupported
            !connected -> MapAvailability.NoConnection
            else -> MapAvailability.Live
        }
    }
}
