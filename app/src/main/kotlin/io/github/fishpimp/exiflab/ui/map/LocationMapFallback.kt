package io.github.fishpimp.exiflab.ui.map

import androidx.annotation.StringRes
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.CloudOff
import androidx.compose.material.icons.rounded.ErrorOutline
import androidx.compose.material.icons.rounded.Place
import androidx.compose.material.icons.rounded.WifiOff
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.isSpecified
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.layout.layoutId
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.dp
import io.github.fishpimp.exiflab.R
import io.github.fishpimp.exiflab.designsystem.theme.ExifLabTheme
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.ln
import kotlin.math.sin
import kotlin.random.Random

/** Why the drawn map stands in for the real one. */
internal enum class MapNote(val icon: ImageVector, @param:StringRes val text: Int) {
    OfflineMode(Icons.Rounded.CloudOff, R.string.map_note_offline),
    NoConnection(Icons.Rounded.WifiOff, R.string.map_note_no_connection),
    Unavailable(Icons.Rounded.ErrorOutline, R.string.map_note_unavailable),
}

/**
 * A map-like surface drawn entirely in Compose: a graticule over soft contour lines, the pin in
 * the middle of the space the chips leave, a note on why the real map is off, and the
 * coordinates. Shown in offline mode, without a connection, in previews and tests, and on devices
 * that cannot render MapLibre. Nothing here touches the network.
 *
 * @param note why the real map is not shown, or null to say nothing (previews, loading).
 * @param showCoordinates shows the coordinates in a chip; hosts that show them elsewhere turn it off.
 * @param description what TalkBack reads for the whole map.
 */
@Composable
internal fun LocationMapFallback(
    marker: LatLng?,
    note: MapNote?,
    showCoordinates: Boolean,
    description: String,
    modifier: Modifier = Modifier,
) {
    val palette = rememberMapPalette()
    // Each place gets its own, stable terrain.
    val seed = remember(marker) {
        marker?.let { (it.latitude * SEED_SCALE).toLong() * SEED_MIX + (it.longitude * SEED_SCALE).toLong() } ?: DEFAULT_SEED
    }
    // Where the pin stands: the middle of the space the chips leave free, found during layout.
    // Unspecified when the chips leave no room for it (tiny maps at very large text sizes); the
    // coordinates chip still says where the photo was taken.
    val pinPoint = remember { mutableStateOf(Offset.Unspecified) }
    Layout(
        content = {
            if (note != null) MapNoteChip(note, Modifier.layoutId(NOTE_ID))
            if (marker != null && showCoordinates) CoordinatesChip(marker, Modifier.layoutId(COORDINATES_ID))
        },
        modifier = modifier
            .clearAndSetSemantics { contentDescription = description }
            .drawBehind {
                val point = pinPoint.value
                drawSketch(seed, palette, if (point.isSpecified) point else center)
                if (marker != null && point.isSpecified) drawLocationMark(point, palette)
            },
    ) { measurables, constraints ->
        val inset = CHIP_INSET.dp.roundToPx()
        val gap = CHIP_GAP.dp.roundToPx()
        val maxChipWidth = if (constraints.hasBoundedWidth) constraints.maxWidth - 2 * inset else Constraints.Infinity
        val chipConstraints = Constraints(maxWidth = maxChipWidth.coerceAtLeast(0))
        val noteChip = measurables.firstOrNull { it.layoutId == NOTE_ID }?.measure(chipConstraints)
        val coordinatesChip = measurables.firstOrNull { it.layoutId == COORDINATES_ID }?.measure(chipConstraints)
        // Callers give the map a size; fall back to a sensible one rather than failing without it.
        val width = if (constraints.hasBoundedWidth) constraints.maxWidth else UNBOUNDED_SIZE.dp.roundToPx()
        val height = if (constraints.hasBoundedHeight) constraints.maxHeight else UNBOUNDED_SIZE.dp.roundToPx()
        layout(width, height) {
            noteChip?.place(inset, inset)
            coordinatesChip?.place(inset, height - inset - coordinatesChip.height)
            val top = noteChip?.let { inset + it.height + gap } ?: 0
            val bottom = coordinatesChip?.let { height - inset - it.height - gap } ?: height
            // The point sits below the middle by half the pin, so the whole mark looks centered.
            // Overlapping the chips a little is fine (they draw on top); hiding the head is not.
            val minimumRoom = (MapPin.Height / 2).toPx()
            val markShift = (MapPin.Height - MapPin.RingRadius).toPx() / 2f
            pinPoint.value = if (bottom - top >= minimumRoom || coordinatesChip == null) {
                Offset(width / 2f, (top + bottom) / 2f + markShift)
            } else {
                Offset.Unspecified
            }
        }
    }
}

@Composable
private fun MapNoteChip(note: MapNote, modifier: Modifier = Modifier) {
    Surface(
        shape = CircleShape,
        color = MaterialTheme.colorScheme.secondaryContainer,
        contentColor = MaterialTheme.colorScheme.onSecondaryContainer,
        modifier = modifier,
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Icon(note.icon, contentDescription = null, modifier = Modifier.size(18.dp))
            Text(stringResource(note.text), style = MaterialTheme.typography.labelLarge)
        }
    }
}

/** The position as text on a pill, for maps that cannot show their surroundings. */
@Composable
private fun CoordinatesChip(position: LatLng, modifier: Modifier = Modifier) {
    Surface(
        shape = CircleShape,
        color = MaterialTheme.colorScheme.surface.copy(alpha = CHIP_ALPHA),
        contentColor = MaterialTheme.colorScheme.onSurface,
        modifier = modifier,
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            Icon(
                Icons.Rounded.Place,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.primary,
                modifier = Modifier.size(16.dp),
            )
            Text(position.formatted(), style = ExifLabTheme.extendedTypography.monoMedium)
        }
    }
}

/** Contour lines around a hilltop near the middle, under a graticule that crosses at [pin]. */
private fun DrawScope.drawSketch(seed: Long, palette: MapPalette, pin: Offset) {
    drawRect(palette.sketchSurface)
    val random = Random(seed)
    val summit = Offset(
        size.width * (SUMMIT_MIN + random.nextFloat() * SUMMIT_RANGE),
        size.height * (SUMMIT_MIN + random.nextFloat() * SUMMIT_RANGE),
    )
    val phases = FloatArray(PHASE_COUNT) { random.nextFloat() * TWO_PI }
    val spacing = CONTOUR_SPACING.dp.toPx()
    val reach = hypot(size.width, size.height)
    val fine = Stroke(width = 1.dp.toPx())
    val index = Stroke(width = 1.75.dp.toPx())
    var ring = 1
    while (ring * spacing * MIN_WOBBLE < reach) {
        val path = contourPath(summit, ring * spacing, ring, phases)
        if (ring <= HILL_RINGS) drawPath(path, palette.sketchHill)
        drawPath(path, palette.sketchContour, style = if (ring % INDEX_EVERY == 0) index else fine)
        ring++
    }

    val step = GRID_STEP.dp.toPx()
    val gridWidth = 1.dp.toPx()
    var x = pin.x % step
    while (x < size.width) {
        drawLine(palette.sketchGrid, Offset(x, 0f), Offset(x, size.height), gridWidth)
        x += step
    }
    var y = pin.y % step
    while (y < size.height) {
        drawLine(palette.sketchGrid, Offset(0f, y), Offset(size.width, y), gridWidth)
        y += step
    }
}

/**
 * One closed contour: a stretched circle with a few gentle wobbles that turn slowly from ring to
 * ring. The turn follows ln(ring), so it slows down as rings grow and neighbours never cross.
 */
private fun contourPath(center: Offset, radius: Float, ring: Int, phases: FloatArray): Path = Path().apply {
    val drift = ln(ring.toFloat())
    for (i in 0..CONTOUR_SEGMENTS) {
        val t = i.toFloat() / CONTOUR_SEGMENTS * TWO_PI
        val wobble = 1f +
            0.14f * sin(2f * t + phases[0] + drift * 0.6f) +
            0.08f * sin(3f * t + phases[1] - drift * 0.5f) +
            0.05f * sin(5f * t + phases[2])
        val r = radius * wobble
        val x = center.x + r * cos(t) * STRETCH
        val y = center.y + r * sin(t)
        if (i == 0) moveTo(x, y) else lineTo(x, y)
    }
    close()
}

private const val TWO_PI = (2 * PI).toFloat()
private const val CONTOUR_SPACING = 20
private const val CONTOUR_SEGMENTS = 96
private const val GRID_STEP = 44
private const val HILL_RINGS = 2
private const val INDEX_EVERY = 4
private const val MIN_WOBBLE = 0.7f
private const val STRETCH = 1.3f
private const val PHASE_COUNT = 3
private const val SUMMIT_MIN = 0.2f
private const val SUMMIT_RANGE = 0.6f
private const val CHIP_ALPHA = 0.92f
private const val CHIP_INSET = 12
private const val CHIP_GAP = 8
private const val UNBOUNDED_SIZE = 200
private const val NOTE_ID = "note"
private const val COORDINATES_ID = "coordinates"
private const val SEED_SCALE = 1_000
private const val SEED_MIX = 31L
private const val DEFAULT_SEED = 7L
