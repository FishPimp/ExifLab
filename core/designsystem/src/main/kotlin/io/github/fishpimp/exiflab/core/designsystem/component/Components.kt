package io.github.fishpimp.exiflab.core.designsystem.component

import androidx.annotation.DrawableRes
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.LoadingIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import io.github.fishpimp.exiflab.core.designsystem.icon.ExifIcon
import io.github.fishpimp.exiflab.core.designsystem.icon.ExifIcons
import io.github.fishpimp.exiflab.core.designsystem.theme.ColorPair
import io.github.fishpimp.exiflab.core.designsystem.theme.ExifLabTheme
import io.github.fishpimp.exiflab.core.designsystem.theme.Spacing

/** Emphasis of a [PillButton]. */
public enum class PillStyle { FILLED, TONAL, OUTLINED }

/** Large pill-shaped action button (56 dp), the primary call to action style of ExifLab. */
@Composable
public fun PillButton(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    @DrawableRes icon: Int? = null,
    style: PillStyle = PillStyle.FILLED,
    enabled: Boolean = true,
) {
    val content: @Composable () -> Unit = {
        if (icon != null) {
            ExifIcon(icon, contentDescription = null, modifier = Modifier.size(20.dp))
            Spacer(Modifier.width(Spacing.s))
        }
        Text(text, style = MaterialTheme.typography.labelLarge, maxLines = 1, overflow = TextOverflow.Ellipsis)
    }
    val padding = PaddingValues(horizontal = 24.dp, vertical = 12.dp)
    val sized = modifier.heightIn(min = 56.dp)
    when (style) {
        PillStyle.FILLED -> Button(onClick, sized, enabled, shape = CircleShape, contentPadding = padding) { content() }
        PillStyle.TONAL -> FilledTonalButton(onClick, sized, enabled, shape = CircleShape, contentPadding = padding) { content() }
        PillStyle.OUTLINED -> OutlinedButton(onClick, sized, enabled, shape = CircleShape, contentPadding = padding) { content() }
    }
}

/** A colour-blocked rounded card. */
@Composable
public fun TonalCard(
    colors: ColorPair,
    modifier: Modifier = Modifier,
    shape: Shape = MaterialTheme.shapes.large,
    onClick: (() -> Unit)? = null,
    content: @Composable () -> Unit,
) {
    if (onClick != null) {
        Surface(onClick = onClick, modifier = modifier, shape = shape, color = colors.container, contentColor = colors.content) { content() }
    } else {
        Surface(modifier = modifier, shape = shape, color = colors.container, contentColor = colors.content) { content() }
    }
}

/** Section title used above groups of content; marked as a heading for TalkBack. */
@Composable
public fun SectionHeader(
    title: String,
    modifier: Modifier = Modifier,
    supporting: String? = null,
    action: (@Composable () -> Unit)? = null,
) {
    Row(
        modifier = modifier.fillMaxWidth().padding(horizontal = Spacing.screen, vertical = Spacing.s),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.titleLarge, modifier = Modifier.semantics { heading() })
            if (supporting != null) {
                Text(supporting, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
        action?.invoke()
    }
}

/** Privacy marker: icon + text on the amber container, never colour alone. */
@Composable
public fun PrivacyBadge(label: String, modifier: Modifier = Modifier, compact: Boolean = false) {
    val c = ExifLabTheme.extendedColors.privacy
    Surface(modifier = modifier, shape = CircleShape, color = c.container, contentColor = c.content) {
        Row(
            Modifier.padding(horizontal = if (compact) 6.dp else 10.dp, vertical = if (compact) 2.dp else 4.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            ExifIcon(ExifIcons.Shield, contentDescription = null, modifier = Modifier.size(if (compact) 12.dp else 14.dp))
            Text(label, style = if (compact) MaterialTheme.typography.labelSmall else MaterialTheme.typography.labelMedium)
        }
    }
}

/** Icon inside an expressive shape - used as a leading visual for list rows and cards. */
@Composable
public fun ShapedIcon(
    @DrawableRes icon: Int,
    colors: ColorPair,
    modifier: Modifier = Modifier,
    shape: Shape = ExpressiveShapes.Cookie,
    size: Dp = 44.dp,
    contentDescription: String? = null,
) {
    Surface(modifier = modifier.size(size), shape = shape, color = colors.container, contentColor = colors.content) {
        Box(contentAlignment = Alignment.Center) {
            ExifIcon(icon, contentDescription = contentDescription, modifier = Modifier.size(size * 0.5f))
        }
    }
}

/** Label above a big numeric readout (exposure triangle, counts). */
@Composable
public fun StatTile(label: String, value: String, modifier: Modifier = Modifier, colors: ColorPair = ExifLabTheme.extendedColors.highlight) {
    TonalCard(colors = colors, modifier = modifier, shape = MaterialTheme.shapes.medium) {
        Column(Modifier.padding(horizontal = Spacing.m, vertical = Spacing.m)) {
            Text(label.uppercase(), style = MaterialTheme.typography.labelSmall)
            Text(value, style = ExifLabTheme.mono.numeral, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
    }
}

/** Empty or error state: big expressive shape, title, body and an optional action. */
@Composable
public fun EmptyState(
    @DrawableRes icon: Int,
    title: String,
    body: String,
    modifier: Modifier = Modifier,
    shape: Shape = ExpressiveShapes.Clover,
    colors: ColorPair = ColorPair(MaterialTheme.colorScheme.tertiaryContainer, MaterialTheme.colorScheme.onTertiaryContainer),
    action: (@Composable () -> Unit)? = null,
) {
    Column(
        modifier = modifier.fillMaxWidth().padding(Spacing.xl),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(Spacing.m),
    ) {
        ShapedIcon(icon = icon, colors = colors, shape = shape, size = 120.dp)
        Text(title, style = MaterialTheme.typography.headlineSmall, textAlign = TextAlign.Center, modifier = Modifier.semantics { heading() })
        Text(body, style = MaterialTheme.typography.bodyLarge, textAlign = TextAlign.Center, color = MaterialTheme.colorScheme.onSurfaceVariant)
        if (action != null) {
            Spacer(Modifier.defaultMinSize(minHeight = Spacing.s))
            action()
        }
    }
}

/** Shape-morphing loading indicator (Material 3 Expressive). */
@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
public fun ExifLoading(modifier: Modifier = Modifier, label: String? = null) {
    Column(modifier.padding(Spacing.xl), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(Spacing.m)) {
        LoadingIndicator()
        if (label != null) Text(label, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

/** Label/value row with the value in monospace. */
@Composable
public fun KeyValueRow(label: String, value: String, modifier: Modifier = Modifier, valueColor: Color = Color.Unspecified) {
    Row(modifier.fillMaxWidth().padding(vertical = Spacing.xs), verticalAlignment = Alignment.Top) {
        Text(label, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.weight(0.42f))
        Spacer(Modifier.width(Spacing.m))
        Text(value, style = ExifLabTheme.mono.medium, color = valueColor, modifier = Modifier.weight(0.58f))
    }
}
