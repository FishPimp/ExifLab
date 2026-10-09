package io.github.fishpimp.exiflab.ui.photo

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material.icons.rounded.ContentCopy
import androidx.compose.material.icons.rounded.FileOpen
import androidx.compose.material.icons.rounded.GppGood
import androidx.compose.material.icons.rounded.LocationOff
import androidx.compose.material.icons.rounded.Navigation
import androidx.compose.material.icons.rounded.SaveAs
import androidx.compose.material.icons.rounded.Terrain
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialShapes
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import io.github.fishpimp.exiflab.R
import io.github.fishpimp.exiflab.data.photos.PhotoOrigin
import io.github.fishpimp.exiflab.designsystem.component.ShapeIcon
import io.github.fishpimp.exiflab.designsystem.theme.ExifLabTheme
import io.github.fishpimp.exiflab.metadata.model.GeoLocation
import io.github.fishpimp.exiflab.metadata.model.LocationStatus
import io.github.fishpimp.exiflab.metadata.model.SensitiveFinding
import io.github.fishpimp.exiflab.metadata.model.SensitivityCategory
import io.github.fishpimp.exiflab.ui.format.CompassPoint
import io.github.fishpimp.exiflab.ui.format.LocationFormat
import io.github.fishpimp.exiflab.ui.map.LatLng
import io.github.fishpimp.exiflab.ui.map.LocationMap

/** A section title inside the viewer, announced as a heading. */
@Composable
fun ViewerSectionTitle(text: String, modifier: Modifier = Modifier) {
    Text(text, style = MaterialTheme.typography.titleLarge, modifier = modifier.semantics { heading() })
}

/**
 * One badge per kind of identifying data in the photo. Each badge pairs the sensitive color with
 * an icon and a label, and toggles a filter that shows only those tags.
 */
@Composable
fun PrivacySection(
    findings: List<SensitiveFinding>,
    selected: SensitivityCategory?,
    onToggle: (SensitivityCategory) -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(modifier, verticalArrangement = Arrangement.spacedBy(12.dp)) {
        ViewerSectionTitle(stringResource(R.string.photo_privacy_title))
        if (findings.isEmpty()) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(16.dp),
                modifier = Modifier.semantics(mergeDescendants = true) {},
            ) {
                ShapeIcon(
                    icon = Icons.Rounded.GppGood,
                    shape = MaterialShapes.Cookie6Sided,
                    size = 44.dp,
                    containerColor = MaterialTheme.colorScheme.secondaryContainer,
                    contentColor = MaterialTheme.colorScheme.onSecondaryContainer,
                )
                Text(
                    stringResource(R.string.photo_privacy_clean),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.weight(1f),
                )
            }
        } else {
            Text(
                stringResource(R.string.photo_privacy_found),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                findings.forEach { finding ->
                    PrivacyBadge(
                        category = finding.category,
                        count = finding.tagKeys.size,
                        selected = finding.category == selected,
                        onToggle = { onToggle(finding.category) },
                    )
                }
            }
        }
    }
}

@Composable
private fun PrivacyBadge(category: SensitivityCategory, count: Int, selected: Boolean, onToggle: () -> Unit) {
    val colors = ExifLabTheme.extendedColors
    val container by animateColorAsState(if (selected) colors.sensitive else colors.sensitiveContainer, label = "badgeContainer")
    val content by animateColorAsState(if (selected) colors.onSensitive else colors.onSensitiveContainer, label = "badgeContent")
    // Expressive shape change: a pill at rest, a rounded square when the filter is on.
    val corner by animateDpAsState(if (selected) 14.dp else 24.dp, label = "badgeCorner")
    val shape = RoundedCornerShape(corner)
    Surface(
        shape = shape,
        color = container,
        contentColor = content,
        modifier = Modifier
            .clip(shape)
            .toggleable(value = selected, role = Role.Checkbox, onValueChange = { onToggle() }),
    ) {
        Row(
            modifier = Modifier.heightIn(min = 48.dp).padding(start = 14.dp, end = 18.dp, top = 8.dp, bottom = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Icon(if (selected) Icons.Rounded.Check else category.icon, contentDescription = null, modifier = Modifier.size(18.dp))
            Text(stringResource(category.labelRes), style = MaterialTheme.typography.labelLarge)
            Text(
                pluralStringResource(R.plurals.photo_tag_count, count, count),
                style = MaterialTheme.typography.labelMedium,
            )
        }
    }
}

/**
 * Where the photo was taken, with a map; or, when the location is missing, why that may be and
 * how to reach the full file.
 */
@Composable
fun LocationSection(
    location: GeoLocation?,
    status: LocationStatus,
    origin: PhotoOrigin,
    onCopyCoordinates: (String) -> Unit,
    onOpenOriginal: () -> Unit,
    onSaveCopy: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(modifier, verticalArrangement = Arrangement.spacedBy(12.dp)) {
        ViewerSectionTitle(stringResource(R.string.photo_location_title))
        if (location != null && status == LocationStatus.Present) {
            LocationCard(location, onCopyCoordinates)
        } else {
            MissingLocationCard(status, origin, onOpenOriginal, onSaveCopy)
        }
    }
}

@Composable
private fun LocationCard(location: GeoLocation, onCopyCoordinates: (String) -> Unit) {
    val dms = remember(location) { LocationFormat.degreesMinutesSeconds(location.latitude, location.longitude) }
    val decimal = remember(location) { LocationFormat.decimalDegrees(location.latitude, location.longitude) }
    Surface(shape = MaterialTheme.shapes.large, color = MaterialTheme.colorScheme.surfaceContainer, modifier = Modifier.fillMaxWidth()) {
        Column {
            LocationMap(
                marker = LatLng(location.latitude, location.longitude),
                modifier = Modifier.fillMaxWidth().height(MAP_HEIGHT).padding(6.dp),
            )
            Column(Modifier.padding(start = 20.dp, end = 8.dp, top = 10.dp, bottom = 16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f).semantics(mergeDescendants = true) {}) {
                        Text(dms, style = ExifLabTheme.extendedTypography.monoLarge)
                        Text(decimal, style = ExifLabTheme.extendedTypography.monoMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    IconButton(onClick = { onCopyCoordinates(decimal) }) {
                        Icon(Icons.Rounded.ContentCopy, contentDescription = stringResource(R.string.photo_copy_coordinates))
                    }
                }
                val altitude = location.altitudeMeters
                val direction = location.directionDegrees
                if (altitude != null || direction != null) {
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(24.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        altitude?.let {
                            val meters = LocationFormat.altitudeMeters(it)
                            LocationStat(
                                icon = Icons.Rounded.Terrain,
                                label = stringResource(R.string.photo_location_altitude),
                                value = if (it < 0 && meters > 0) {
                                    stringResource(R.string.photo_value_altitude_below, meters)
                                } else {
                                    stringResource(R.string.photo_value_altitude, meters)
                                },
                            )
                        }
                        direction?.let {
                            LocationStat(
                                icon = Icons.Rounded.Navigation,
                                iconRotation = it.toFloat(),
                                label = stringResource(R.string.photo_location_direction),
                                value = stringResource(
                                    R.string.photo_value_direction,
                                    LocationFormat.bearingDegrees(it),
                                    stringResource(LocationFormat.compassPoint(it).labelRes),
                                ),
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun LocationStat(icon: ImageVector, label: String, value: String, iconRotation: Float = 0f) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(10.dp),
        modifier = Modifier.semantics(mergeDescendants = true) {},
    ) {
        Icon(icon, contentDescription = null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(20.dp).rotate(iconRotation))
        Column {
            Text(label, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Text(value, style = MaterialTheme.typography.bodyLarge)
        }
    }
}

@Composable
private fun MissingLocationCard(status: LocationStatus, origin: PhotoOrigin, onOpenOriginal: () -> Unit, onSaveCopy: () -> Unit) {
    // The picker and sharing apps strip location; a file opened directly has simply none.
    val viaSharing = origin == PhotoOrigin.Picker || origin == PhotoOrigin.Share
    val redacted = status == LocationStatus.Redacted
    Surface(shape = MaterialTheme.shapes.large, color = MaterialTheme.colorScheme.surfaceContainer, modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(16.dp),
                modifier = Modifier.semantics(mergeDescendants = true) {},
            ) {
                ShapeIcon(
                    icon = Icons.Rounded.LocationOff,
                    shape = MaterialShapes.Cookie6Sided,
                    size = 44.dp,
                    containerColor = MaterialTheme.colorScheme.surfaceContainerHighest,
                    contentColor = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Text(
                    stringResource(if (redacted) R.string.photo_location_removed_title else R.string.photo_location_none_title),
                    style = MaterialTheme.typography.titleMedium,
                    modifier = Modifier.weight(1f),
                )
            }
            Text(
                stringResource(
                    when {
                        viaSharing && redacted -> R.string.photo_location_shared_redacted
                        viaSharing -> R.string.photo_location_shared_absent
                        redacted -> R.string.photo_location_redacted
                        else -> R.string.photo_location_absent
                    },
                ),
                style = MaterialTheme.typography.bodyMedium,
            )
            if (viaSharing) {
                Text(
                    stringResource(R.string.photo_location_shared_hint),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                FlowRow(
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                    modifier = Modifier.padding(top = 4.dp),
                ) {
                    FilledTonalButton(onClick = onOpenOriginal, contentPadding = ButtonDefaults.ButtonWithIconContentPadding) {
                        Icon(Icons.Rounded.FileOpen, contentDescription = null, modifier = Modifier.size(ButtonDefaults.IconSize))
                        Text(stringResource(R.string.photo_open_original), modifier = Modifier.padding(start = ButtonDefaults.IconSpacing))
                    }
                    OutlinedButton(onClick = onSaveCopy, contentPadding = ButtonDefaults.ButtonWithIconContentPadding) {
                        Icon(Icons.Rounded.SaveAs, contentDescription = null, modifier = Modifier.size(ButtonDefaults.IconSize))
                        Text(stringResource(R.string.photo_save_copy), modifier = Modifier.padding(start = ButtonDefaults.IconSpacing))
                    }
                }
            }
        }
    }
}

private val CompassPoint.labelRes: Int
    get() = when (this) {
        CompassPoint.N -> R.string.compass_n
        CompassPoint.NE -> R.string.compass_ne
        CompassPoint.E -> R.string.compass_e
        CompassPoint.SE -> R.string.compass_se
        CompassPoint.S -> R.string.compass_s
        CompassPoint.SW -> R.string.compass_sw
        CompassPoint.W -> R.string.compass_w
        CompassPoint.NW -> R.string.compass_nw
    }

private val MAP_HEIGHT = 200.dp
