package io.github.fishpimp.exiflab.ui.folder

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Folder
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import io.github.fishpimp.exiflab.R

/**
 * A subfolder in the folder grid; TalkBack reads "<name>, folder".
 *
 * @param wide lays the icon beside the name, for a tile spanning the whole grid row (used at
 *   large font sizes, where a single cell is too narrow for the name).
 */
@Composable
fun FolderTile(name: String, onClick: () -> Unit, modifier: Modifier = Modifier, wide: Boolean = false) {
    val description = stringResource(R.string.folder_tile_description, name)
    Surface(
        onClick = onClick,
        shape = MaterialTheme.shapes.medium,
        color = MaterialTheme.colorScheme.surfaceContainerHigh,
        modifier = modifier.fillMaxWidth().heightIn(min = if (wide) 56.dp else 96.dp),
    ) {
        val content = Modifier.padding(12.dp).clearAndSetSemantics { contentDescription = description }
        if (wide) {
            Row(content, verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                FolderIcon()
                Text(name, style = MaterialTheme.typography.titleSmall)
            }
        } else {
            Column(content, verticalArrangement = Arrangement.spacedBy(8.dp)) {
                FolderIcon()
                Text(name, style = MaterialTheme.typography.titleSmall, maxLines = 3, overflow = TextOverflow.Ellipsis)
            }
        }
    }
}

@Composable
private fun FolderIcon() {
    Icon(Icons.Rounded.Folder, contentDescription = null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(32.dp))
}
