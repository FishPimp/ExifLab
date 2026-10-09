package io.github.fishpimp.exiflab.ui.map

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Map
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip

/** A point on the map, in WGS84 degrees. */
data class LatLng(val latitude: Double, val longitude: Double)

/**
 * OpenStreetMap-based map showing [marker]. When [onPick] is set the map is interactive and
 * tapping or long-pressing drops a pin at that position.
 *
 * Placeholder until the map milestone wires up MapLibre; keep this signature stable.
 */
@Composable
fun LocationMap(
    marker: LatLng?,
    modifier: Modifier = Modifier,
    interactive: Boolean = false,
    onPick: ((LatLng) -> Unit)? = null,
) {
    Box(
        modifier = modifier
            .clip(MaterialTheme.shapes.large)
            .background(MaterialTheme.colorScheme.surfaceContainerHigh),
        contentAlignment = Alignment.Center,
    ) {
        Icon(Icons.Rounded.Map, contentDescription = null, tint = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}
