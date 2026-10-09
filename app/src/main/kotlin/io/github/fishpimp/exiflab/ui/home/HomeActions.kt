package io.github.fishpimp.exiflab.ui.home

import androidx.annotation.StringRes
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowForward
import androidx.compose.material.icons.rounded.FileOpen
import androidx.compose.material.icons.rounded.FolderOpen
import androidx.compose.material.icons.rounded.PhotoLibrary
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialShapes
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.graphics.shapes.RoundedPolygon
import io.github.fishpimp.exiflab.R
import io.github.fishpimp.exiflab.designsystem.component.ShapeIcon
import io.github.fishpimp.exiflab.ui.components.isLargeFontScale

/** Window width from which the three actions sit side by side instead of stacking. */
private val SideBySideMinWidth = 600.dp

private class HomeAction(
    val icon: ImageVector,
    val shape: RoundedPolygon,
    @StringRes val title: Int,
    @StringRes val body: Int,
    val onClick: () -> Unit,
)

private class ActionColors(val container: Color, val content: Color, val iconContainer: Color, val iconContent: Color)

/**
 * The ways to get photos into ExifLab: the photo picker, the file picker and granted folders.
 * Stacked on phones, side by side on wider windows.
 */
@Composable
fun HomeActions(
    onOpenPhotos: () -> Unit,
    onOpenFiles: () -> Unit,
    onBrowseFolders: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
) {
    val actions = listOf(
        HomeAction(Icons.Rounded.PhotoLibrary, MaterialShapes.Cookie9Sided, R.string.home_action_photos_title, R.string.home_action_photos_body, onOpenPhotos),
        HomeAction(Icons.Rounded.FileOpen, MaterialShapes.Clover4Leaf, R.string.home_action_files_title, R.string.home_action_files_body, onOpenFiles),
        HomeAction(Icons.Rounded.FolderOpen, MaterialShapes.Sunny, R.string.home_action_folders_title, R.string.home_action_folders_body, onBrowseFolders),
    )
    val scheme = MaterialTheme.colorScheme
    val colors = listOf(
        ActionColors(scheme.primaryContainer, scheme.onPrimaryContainer, scheme.primary, scheme.onPrimary),
        ActionColors(scheme.surfaceContainerHigh, scheme.onSurface, scheme.tertiary, scheme.onTertiary),
        ActionColors(scheme.surfaceContainerHigh, scheme.onSurface, scheme.secondary, scheme.onSecondary),
    )
    // At large font sizes the icon goes above the text so the text keeps the full card width.
    val largeFont = isLargeFontScale()
    BoxWithConstraints(modifier.fillMaxWidth()) {
        if (maxWidth >= SideBySideMinWidth) {
            Row(Modifier.height(IntrinsicSize.Min), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                actions.forEachIndexed { index, action ->
                    ActionCard(action, colors[index], iconAboveText = true, enabled, Modifier.weight(1f).fillMaxHeight())
                }
            }
        } else {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                actions.forEachIndexed { index, action ->
                    ActionCard(action, colors[index], iconAboveText = largeFont, enabled, Modifier.fillMaxWidth())
                }
            }
        }
    }
}

@Composable
private fun ActionCard(action: HomeAction, colors: ActionColors, iconAboveText: Boolean, enabled: Boolean, modifier: Modifier) {
    Card(
        onClick = action.onClick,
        enabled = enabled,
        shape = MaterialTheme.shapes.large,
        colors = CardDefaults.cardColors(
            containerColor = colors.container,
            contentColor = colors.content,
            disabledContainerColor = colors.container,
            disabledContentColor = colors.content,
        ),
        modifier = modifier,
    ) {
        if (!iconAboveText) {
            Row(
                Modifier.padding(horizontal = 20.dp, vertical = 18.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(16.dp),
            ) {
                ActionIcon(action, colors)
                ActionText(action, Modifier.weight(1f))
                Icon(Icons.AutoMirrored.Rounded.ArrowForward, contentDescription = null)
            }
        } else {
            Column(Modifier.padding(20.dp)) {
                ActionIcon(action, colors)
                Spacer(Modifier.height(16.dp))
                ActionText(action)
            }
        }
    }
}

@Composable
private fun ActionIcon(action: HomeAction, colors: ActionColors) {
    ShapeIcon(
        icon = action.icon,
        shape = action.shape,
        size = 56.dp,
        containerColor = colors.iconContainer,
        contentColor = colors.iconContent,
    )
}

@Composable
private fun ActionText(action: HomeAction, modifier: Modifier = Modifier) {
    Column(modifier, verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text(stringResource(action.title), style = MaterialTheme.typography.titleLarge)
        Text(stringResource(action.body), style = MaterialTheme.typography.bodyMedium)
    }
}
