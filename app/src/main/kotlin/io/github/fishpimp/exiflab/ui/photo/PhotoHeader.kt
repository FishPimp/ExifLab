package io.github.fishpimp.exiflab.ui.photo

import android.text.format.Formatter
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Adjust
import androidx.compose.material.icons.rounded.Camera
import androidx.compose.material.icons.rounded.CenterFocusStrong
import androidx.compose.material.icons.rounded.Event
import androidx.compose.material.icons.rounded.Exposure
import androidx.compose.material.icons.rounded.HideImage
import androidx.compose.material.icons.rounded.Iso
import androidx.compose.material.icons.rounded.Lock
import androidx.compose.material.icons.rounded.OpenInFull
import androidx.compose.material.icons.rounded.PhotoCamera
import androidx.compose.material.icons.rounded.PhotoSizeSelectLarge
import androidx.compose.material.icons.rounded.ShutterSpeed
import androidx.compose.material.icons.automirrored.rounded.InsertDriveFile
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialShapes
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalLocale
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.github.fishpimp.exiflab.R
import io.github.fishpimp.exiflab.data.photos.PhotoOrigin
import io.github.fishpimp.exiflab.data.photos.PhotoRef
import io.github.fishpimp.exiflab.designsystem.component.ShapeIcon
import io.github.fishpimp.exiflab.designsystem.theme.ExifLabTheme
import io.github.fishpimp.exiflab.metadata.model.MetadataReport
import io.github.fishpimp.exiflab.metadata.model.OffsetSource
import io.github.fishpimp.exiflab.metadata.model.PhotoSummary
import io.github.fishpimp.exiflab.ui.components.PhotoThumbnail
import io.github.fishpimp.exiflab.ui.format.CameraName
import io.github.fishpimp.exiflab.ui.format.CaptureTimeFormat
import io.github.fishpimp.exiflab.ui.format.ExposureFormat
import java.util.Locale

/** The large photo preview. Its frame follows the photo's aspect ratio, so nothing is cropped or letterboxed. */
@Composable
fun PhotoPreview(
    ref: PhotoRef,
    report: MetadataReport,
    name: String,
    onOpen: () -> Unit,
    modifier: Modifier = Modifier,
    maxHeight: Dp = 460.dp,
) {
    val summary = report.summary
    val ratio = remember(summary) {
        val width = summary.width
        val height = summary.height
        if (width != null && height != null && width > 0 && height > 0) {
            val (shownWidth, shownHeight) = ExposureFormat.orientedSize(width, height, summary.orientation)
            (shownWidth.toFloat() / shownHeight).coerceIn(MIN_PREVIEW_RATIO, MAX_PREVIEW_RATIO)
        } else {
            null
        }
    }
    val openLabel = stringResource(R.string.photo_view_full_screen)
    val shape = MaterialTheme.shapes.extraLarge
    Box(modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
        Box(
            Modifier
                .heightIn(max = maxHeight)
                .aspectRatio(ratio ?: DEFAULT_PREVIEW_RATIO)
                .clip(shape)
                .clickable(onClickLabel = openLabel, role = Role.Button, onClick = onOpen),
        ) {
            PhotoThumbnail(
                ref = ref,
                contentDescription = stringResource(R.string.photo_preview_description, name),
                contentScale = if (ratio != null) ContentScale.Crop else ContentScale.Fit,
                modifier = Modifier.fillMaxSize(),
            )
            Surface(
                shape = CircleShape,
                color = MaterialTheme.colorScheme.surfaceContainerHighest.copy(alpha = 0.88f),
                contentColor = MaterialTheme.colorScheme.onSurface,
                modifier = Modifier.align(Alignment.BottomEnd).padding(12.dp),
            ) {
                Icon(Icons.Rounded.OpenInFull, contentDescription = null, modifier = Modifier.padding(8.dp).size(18.dp))
            }
        }
    }
}

/** Make, model and lens; or a calm note when the file names no camera at all. */
@Composable
fun CameraSummary(summary: PhotoSummary, modifier: Modifier = Modifier) {
    val camera = remember(summary) { CameraName.of(summary.make, summary.model) }
    Column(modifier, verticalArrangement = Arrangement.spacedBy(12.dp)) {
        if (camera == null) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                ShapeIcon(
                    icon = Icons.Rounded.HideImage,
                    shape = MaterialShapes.Clover4Leaf,
                    size = 56.dp,
                    containerColor = MaterialTheme.colorScheme.surfaceContainerHighest,
                    contentColor = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text(
                        stringResource(R.string.photo_no_camera_title),
                        style = MaterialTheme.typography.titleLarge,
                        modifier = Modifier.semantics { heading() },
                    )
                    Text(
                        stringResource(R.string.photo_no_camera_body),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        } else {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                ShapeIcon(icon = Icons.Rounded.PhotoCamera, shape = MaterialShapes.Cookie9Sided, size = 56.dp)
                Column(Modifier.weight(1f).semantics(mergeDescendants = true) { heading() }) {
                    camera.make?.let {
                        Text(it, style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.primary)
                    }
                    Text(camera.title, style = MaterialTheme.typography.headlineMedium)
                }
            }
        }
        summary.lens?.let { lens ->
            val description = stringResource(R.string.photo_lens_description, lens)
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                modifier = Modifier.clearAndSetSemantics { contentDescription = description },
            ) {
                Icon(Icons.Rounded.Adjust, contentDescription = null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(20.dp))
                Text(lens, style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    }
}

/** One number in the spec grid: "f/1.8" labelled "Aperture". */
@Immutable
class SpecTile(val icon: ImageVector, val label: String, val value: String, val supporting: String? = null)

/**
 * The exposure triangle (aperture, shutter, ISO) as one connected group, and focal length,
 * exposure bias and resolution as a second. Missing values are left out; an empty group is not shown.
 */
@Composable
fun SpecGrid(summary: PhotoSummary, modifier: Modifier = Modifier) {
    val (triangle, secondary) = specTiles(summary)
    if (triangle.isEmpty() && secondary.isEmpty()) return
    val colors = MaterialTheme.colorScheme
    SpecGroups(
        groups = listOfNotNull(
            triangle.takeIf { it.isNotEmpty() }?.let { SpecGroup(it, colors.primaryContainer, colors.onPrimaryContainer, colors.primary) },
            secondary.takeIf { it.isNotEmpty() }?.let {
                SpecGroup(it, colors.surfaceContainerHigh, colors.onSurface, colors.secondary)
            },
        ),
        modifier = modifier,
    )
}

@Composable
private fun specTiles(summary: PhotoSummary): Pair<List<SpecTile>, List<SpecTile>> {
    val locale = LocalLocale.current.platformLocale
    val aperture = summary.fNumber?.let { ExposureFormat.fNumber(it, locale) }?.let {
        SpecTile(Icons.Rounded.Camera, stringResource(R.string.photo_spec_aperture), stringResource(R.string.photo_value_aperture, it))
    }
    val shutter = summary.exposureTime?.let { ExposureFormat.shutterSpeedText(it, locale) }?.let {
        SpecTile(Icons.Rounded.ShutterSpeed, stringResource(R.string.photo_spec_shutter), stringResource(R.string.photo_value_shutter, it))
    }
    val iso = summary.iso?.takeIf { it > 0 }?.let {
        SpecTile(Icons.Rounded.Iso, stringResource(R.string.photo_spec_iso), stringResource(R.string.photo_value_iso, it.toString()))
    }
    val actualFocal = summary.focalLengthMm?.let { ExposureFormat.focalLength(it, locale) }
    val equivalent = summary.focalLength35mm?.takeIf { it > 0 }
    val focal = when {
        actualFocal != null -> SpecTile(
            Icons.Rounded.CenterFocusStrong,
            stringResource(R.string.photo_spec_focal_length),
            stringResource(R.string.photo_value_focal_length, actualFocal),
            // A full-frame lens needs no equivalent: 50 mm is 50 mm.
            equivalent?.takeIf { it.toString() != actualFocal }?.let { stringResource(R.string.photo_value_focal_length_equivalent, it.toString()) },
        )
        equivalent != null -> SpecTile(
            Icons.Rounded.CenterFocusStrong,
            stringResource(R.string.photo_spec_focal_length),
            stringResource(R.string.photo_value_focal_length_equivalent, equivalent.toString()),
        )
        else -> null
    }
    val bias = summary.exposureBiasEv?.let { ExposureFormat.exposureBias(it, locale) }?.let {
        SpecTile(Icons.Rounded.Exposure, stringResource(R.string.photo_spec_exposure_bias), stringResource(R.string.photo_value_exposure_bias, it))
    }
    val resolution = resolutionTile(summary, locale)
    return listOfNotNull(aperture, shutter, iso) to listOfNotNull(focal, bias, resolution)
}

@Composable
private fun resolutionTile(summary: PhotoSummary, locale: Locale): SpecTile? {
    val width = summary.width ?: return null
    val height = summary.height ?: return null
    if (width <= 0 || height <= 0) return null
    val (shownWidth, shownHeight) = ExposureFormat.orientedSize(width, height, summary.orientation)
    val dimensions = stringResource(R.string.photo_value_dimensions, shownWidth, shownHeight)
    val megapixels = ExposureFormat.megapixels(width, height, locale)
    val label = stringResource(R.string.photo_spec_resolution)
    return if (megapixels != null) {
        SpecTile(Icons.Rounded.PhotoSizeSelectLarge, label, stringResource(R.string.photo_value_megapixels, megapixels), dimensions)
    } else {
        SpecTile(Icons.Rounded.PhotoSizeSelectLarge, label, dimensions)
    }
}

@Immutable
private class SpecGroup(val tiles: List<SpecTile>, val container: Color, val content: Color, val accent: Color)

private val SpecGap = 4.dp
private val SpecPaddingHorizontal = 16.dp
private val SpecCornerOuter = 24.dp
private val SpecCornerInner = 6.dp
private val NumeralSizes = listOf(28.sp, 26.sp, 24.sp, 22.sp, 20.sp, 18.sp)

/** Columns and numeral size that let every value fit on one line, preferring more columns. */
private class SpecLayout(val columns: Int, val numeralSize: TextUnit, val wrap: Boolean)

@Composable
private fun SpecGroups(groups: List<SpecGroup>, modifier: Modifier = Modifier) {
    val numeral = ExifLabTheme.extendedTypography.numeral
    val measurer = rememberTextMeasurer()
    val density = LocalDensity.current
    BoxWithConstraints(modifier.fillMaxWidth()) {
        val values = groups.flatMap { group -> group.tiles.map { it.value } }
        val maxColumns = groups.maxOf { it.tiles.size }
        val available = maxWidth
        val layout = remember(values, maxColumns, available, numeral, density) {
            with(density) {
                fun fits(columns: Int, size: TextUnit): Boolean {
                    val tileWidth = (available - SpecGap * (columns - 1)) / columns - SpecPaddingHorizontal * 2
                    val style = numeral.copy(fontSize = size)
                    return values.all { measurer.measure(it, style, maxLines = 1, softWrap = false).size.width.toDp() <= tileWidth }
                }
                (maxColumns downTo 1).firstNotNullOfOrNull { columns ->
                    // Two columns only when the big size fits; otherwise one roomy column reads better.
                    val sizes = if (columns == maxColumns) NumeralSizes else NumeralSizes.take(3)
                    sizes.firstOrNull { fits(columns, it) }?.let { SpecLayout(columns, it, wrap = false) }
                } ?: SpecLayout(1, NumeralSizes.last(), wrap = true)
            }
        }
        val style = numeral.copy(fontSize = layout.numeralSize, lineHeight = layout.numeralSize * 1.2f)
        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            groups.forEach { group ->
                Column(verticalArrangement = Arrangement.spacedBy(SpecGap)) {
                    group.tiles.chunked(layout.columns).forEach { row ->
                        Row(
                            modifier = Modifier.fillMaxWidth().height(IntrinsicSize.Min),
                            horizontalArrangement = Arrangement.spacedBy(SpecGap),
                        ) {
                            row.forEachIndexed { index, tile ->
                                SpecTileView(
                                    tile = tile,
                                    group = group,
                                    numeralStyle = style,
                                    wrap = layout.wrap,
                                    shape = connectedShape(index, row.size),
                                    modifier = Modifier.weight(1f).fillMaxHeight(),
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}

/** Large outer corners and small inner ones, so a row of tiles reads as one connected group. */
private fun connectedShape(index: Int, count: Int): RoundedCornerShape = when {
    count == 1 -> RoundedCornerShape(SpecCornerOuter)
    index == 0 -> RoundedCornerShape(
        topStart = SpecCornerOuter,
        bottomStart = SpecCornerOuter,
        topEnd = SpecCornerInner,
        bottomEnd = SpecCornerInner,
    )
    index == count - 1 -> RoundedCornerShape(
        topStart = SpecCornerInner,
        bottomStart = SpecCornerInner,
        topEnd = SpecCornerOuter,
        bottomEnd = SpecCornerOuter,
    )
    else -> RoundedCornerShape(SpecCornerInner)
}

@Composable
private fun SpecTileView(
    tile: SpecTile,
    group: SpecGroup,
    numeralStyle: TextStyle,
    wrap: Boolean,
    shape: RoundedCornerShape,
    modifier: Modifier = Modifier,
) {
    val description = listOfNotNull(tile.label, tile.value, tile.supporting).joinToString(", ")
    Surface(
        shape = shape,
        color = group.container,
        contentColor = group.content,
        modifier = modifier.clearAndSetSemantics { contentDescription = description },
    ) {
        // Values share a top line and labels a bottom line across a row, whatever wraps in between.
        Column(Modifier.fillMaxHeight().padding(horizontal = SpecPaddingHorizontal, vertical = 14.dp)) {
            Icon(tile.icon, contentDescription = null, tint = group.accent, modifier = Modifier.size(20.dp))
            Spacer(Modifier.height(10.dp))
            Text(tile.value, style = numeralStyle, maxLines = if (wrap) Int.MAX_VALUE else 1, softWrap = wrap)
            tile.supporting?.let {
                Text(it, style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(top = 2.dp))
            }
            Spacer(Modifier.weight(1f).heightIn(min = 6.dp))
            Text(tile.label, style = MaterialTheme.typography.labelMedium)
        }
    }
}

/** When the photo was taken and what the file is. */
@Composable
fun PhotoFacts(ref: PhotoRef, report: MetadataReport, modifier: Modifier = Modifier) {
    val locale = LocalLocale.current.platformLocale
    val summary = report.summary
    Column(modifier, verticalArrangement = Arrangement.spacedBy(16.dp)) {
        summary.capturedAt?.let { local ->
            val date = remember(local, locale) { CaptureTimeFormat.date(local, locale) }
            val time = remember(local, locale) { CaptureTimeFormat.time(local, locale) }
            val offset = summary.utcOffset?.let { stringResource(R.string.photo_value_utc_offset, CaptureTimeFormat.utcOffset(it)) }
            FactRow(
                icon = Icons.Rounded.Event,
                shape = MaterialShapes.Cookie7Sided,
                label = stringResource(R.string.photo_captured),
                value = date,
                supporting = listOfNotNull(
                    listOfNotNull(time, offset).joinToString(SEPARATOR),
                    offsetSourceText(summary.utcOffset != null, summary.utcOffsetSource),
                ),
            )
        }
        val context = LocalContext.current
        val size = report.fileSize ?: ref.size
        val sizeText = remember(size, context) { size?.let { Formatter.formatShortFileSize(context, it) } }
        FactRow(
            icon = Icons.AutoMirrored.Rounded.InsertDriveFile,
            shape = MaterialShapes.Arch,
            label = stringResource(R.string.photo_file),
            value = ref.displayName ?: report.fileName ?: stringResource(R.string.photo_untitled),
            supporting = listOf(
                listOfNotNull(report.format.displayName, sizeText, summary.colorProfile).joinToString(SEPARATOR),
            ),
        )
    }
}

@Composable
private fun offsetSourceText(hasOffset: Boolean, source: OffsetSource?): String = when {
    !hasOffset || source == null -> stringResource(R.string.photo_time_zone_unknown)
    source == OffsetSource.ExifOffsetTime -> stringResource(R.string.photo_offset_from_exif)
    source == OffsetSource.Xmp -> stringResource(R.string.photo_offset_from_xmp)
    source == OffsetSource.GpsTimestamp -> stringResource(R.string.photo_offset_from_gps)
    else -> stringResource(R.string.photo_offset_from_makernote)
}

@Composable
private fun FactRow(
    icon: ImageVector,
    shape: androidx.graphics.shapes.RoundedPolygon,
    label: String,
    value: String,
    supporting: List<String>,
    modifier: Modifier = Modifier,
) {
    Row(
        modifier = modifier.fillMaxWidth().semantics(mergeDescendants = true) {},
        horizontalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        ShapeIcon(
            icon = icon,
            shape = shape,
            size = 44.dp,
            containerColor = MaterialTheme.colorScheme.secondaryContainer,
            contentColor = MaterialTheme.colorScheme.onSecondaryContainer,
        )
        Column(Modifier.weight(1f)) {
            Text(label, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Text(value, style = MaterialTheme.typography.bodyLarge)
            supporting.filter { it.isNotEmpty() }.forEach {
                Text(it, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    }
}

/** A calm note that edits to a read-only photo go to a copy. */
@Composable
fun ReadOnlyBanner(origin: PhotoOrigin, modifier: Modifier = Modifier) {
    Surface(
        shape = MaterialTheme.shapes.large,
        color = MaterialTheme.colorScheme.surfaceContainerHigh,
        modifier = modifier.fillMaxWidth(),
    ) {
        Row(
            Modifier.padding(16.dp),
            horizontalArrangement = Arrangement.spacedBy(16.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(Icons.Rounded.Lock, contentDescription = null, tint = MaterialTheme.colorScheme.secondary)
            Text(
                stringResource(
                    when (origin) {
                        PhotoOrigin.Picker -> R.string.photo_read_only_picker
                        PhotoOrigin.Share -> R.string.photo_read_only_share
                        PhotoOrigin.Document, PhotoOrigin.Folder -> R.string.photo_read_only_file
                    },
                ),
                style = MaterialTheme.typography.bodyMedium,
                modifier = Modifier.weight(1f),
            )
        }
    }
}

private const val SEPARATOR = " · "
private const val MIN_PREVIEW_RATIO = 0.6f
private const val MAX_PREVIEW_RATIO = 2.2f
private const val DEFAULT_PREVIEW_RATIO = 4f / 3f
