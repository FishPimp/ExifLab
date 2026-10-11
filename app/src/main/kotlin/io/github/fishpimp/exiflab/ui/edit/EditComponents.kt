package io.github.fishpimp.exiflab.ui.edit

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.KeyboardArrowRight
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.graphics.shapes.RoundedPolygon
import io.github.fishpimp.exiflab.designsystem.component.ShapeIcon
import io.github.fishpimp.exiflab.designsystem.theme.ExifLabTheme
import io.github.fishpimp.exiflab.metadata.edit.FieldDiff

/**
 * One section of the editor: an expressive shape icon, a title announced as a heading, an
 * optional line of context and the section's content.
 */
@Composable
fun EditorCard(
    icon: ImageVector,
    shape: RoundedPolygon,
    title: String,
    modifier: Modifier = Modifier,
    subtitle: String? = null,
    iconContainer: Color = MaterialTheme.colorScheme.primaryContainer,
    iconContent: Color = MaterialTheme.colorScheme.onPrimaryContainer,
    content: @Composable ColumnScope.() -> Unit,
) {
    Surface(
        shape = MaterialTheme.shapes.extraLarge,
        color = MaterialTheme.colorScheme.surfaceContainerLow,
        modifier = modifier.fillMaxWidth(),
    ) {
        Column(Modifier.padding(vertical = 20.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
            Row(
                Modifier.padding(horizontal = 20.dp),
                horizontalArrangement = Arrangement.spacedBy(16.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                ShapeIcon(icon = icon, shape = shape, size = 48.dp, containerColor = iconContainer, contentColor = iconContent)
                Column(Modifier.weight(1f)) {
                    Text(title, style = MaterialTheme.typography.titleLarge, modifier = Modifier.semantics { heading() })
                    if (subtitle != null) {
                        Text(subtitle, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
            }
            content()
        }
    }
}

/**
 * A full-width action inside an [EditorCard]: icon, title, what it does, and a chevron. One
 * accessibility node, at least 64dp tall, so it stays easy to hit and to read at large text sizes.
 */
@Composable
fun EditorActionRow(
    icon: ImageVector,
    title: String,
    description: String?,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    tint: Color = MaterialTheme.colorScheme.primary,
) {
    val contentAlpha = if (enabled) 1f else DISABLED_ALPHA
    Row(
        modifier
            .fillMaxWidth()
            .padding(horizontal = 8.dp)
            .clip(MaterialTheme.shapes.medium)
            .clickable(enabled = enabled, role = Role.Button, onClick = onClick)
            .heightIn(min = 64.dp)
            .padding(horizontal = 12.dp, vertical = 10.dp),
        horizontalArrangement = Arrangement.spacedBy(16.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(icon, contentDescription = null, tint = tint.copy(alpha = contentAlpha))
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.onSurface.copy(alpha = contentAlpha))
            if (description != null) {
                Text(
                    description,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = contentAlpha),
                )
            }
        }
        Icon(
            Icons.AutoMirrored.Rounded.KeyboardArrowRight,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = contentAlpha),
        )
    }
}

/**
 * A calm message block: icon, optional title and text on a tonal container. With [announce] it is
 * a polite live region, so TalkBack reads it when it appears.
 */
@Composable
fun EditorBanner(
    icon: ImageVector,
    text: String,
    modifier: Modifier = Modifier,
    title: String? = null,
    containerColor: Color = MaterialTheme.colorScheme.secondaryContainer,
    contentColor: Color = MaterialTheme.colorScheme.onSecondaryContainer,
    announce: Boolean = false,
    content: @Composable ColumnScope.() -> Unit = {},
) {
    Surface(
        shape = MaterialTheme.shapes.large,
        color = containerColor,
        contentColor = contentColor,
        modifier = modifier
            .fillMaxWidth()
            .semantics(mergeDescendants = true) { if (announce) liveRegion = LiveRegionMode.Polite },
    ) {
        Row(Modifier.padding(16.dp), horizontalArrangement = Arrangement.spacedBy(16.dp)) {
            Icon(icon, contentDescription = null, modifier = Modifier.size(24.dp))
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                if (title != null) Text(title, style = MaterialTheme.typography.titleSmall)
                Text(text, style = MaterialTheme.typography.bodyMedium)
                content()
            }
        }
    }
}

/**
 * A labelled value in a before/after comparison. A [kind] tints the box with its diff color and
 * marks it with an accent bar at the start; the text stays in the normal content color, so it reads
 * well in both themes. The meaning is also in [label] (and the chip next to it), never color alone.
 */
@Composable
fun DiffValueBox(
    label: String,
    kind: FieldDiff.Kind?,
    modifier: Modifier = Modifier,
    content: @Composable ColumnScope.() -> Unit,
) {
    val colors = MaterialTheme.colorScheme
    val accent = when (kind) {
        FieldDiff.Kind.Added -> ExifLabTheme.extendedColors.addedContainer
        FieldDiff.Kind.Changed -> ExifLabTheme.extendedColors.changedContainer
        FieldDiff.Kind.Removed -> ExifLabTheme.extendedColors.removedContainer
        null -> null
    }
    val background = if (accent != null) lerp(colors.surfaceContainerHighest, accent, TINT_FRACTION) else colors.surfaceContainerHighest
    Surface(
        shape = MaterialTheme.shapes.medium,
        color = background,
        contentColor = if (accent != null) colors.onSurface else colors.onSurfaceVariant,
        modifier = modifier.semantics(mergeDescendants = true) {},
    ) {
        Column(
            Modifier
                .drawBehind { if (accent != null) drawRect(accent, size = Size(ACCENT_WIDTH.toPx(), size.height)) }
                .padding(start = 16.dp, end = 12.dp, top = 10.dp, bottom = 10.dp),
            verticalArrangement = Arrangement.spacedBy(2.dp),
        ) {
            Text(label, style = MaterialTheme.typography.labelLarge)
            content()
        }
    }
}

private const val DISABLED_ALPHA = 0.38f
private const val TINT_FRACTION = 0.3f
private val ACCENT_WIDTH = 5.dp
