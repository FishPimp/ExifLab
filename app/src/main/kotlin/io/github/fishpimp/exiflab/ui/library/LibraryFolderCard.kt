package io.github.fishpimp.exiflab.ui.library

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Edit
import androidx.compose.material.icons.rounded.Folder
import androidx.compose.material.icons.rounded.FolderOff
import androidx.compose.material.icons.rounded.Lock
import androidx.compose.material.icons.rounded.MoreVert
import androidx.compose.material.icons.rounded.Refresh
import androidx.compose.material.icons.rounded.RemoveCircleOutline
import androidx.compose.material.icons.rounded.WarningAmber
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialShapes
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import io.github.fishpimp.exiflab.R
import io.github.fishpimp.exiflab.data.folders.FolderAccess
import io.github.fishpimp.exiflab.data.folders.LibraryFolder
import io.github.fishpimp.exiflab.designsystem.component.ShapeIcon

/**
 * One granted folder. Tapping opens it; when access was lost the card explains why and offers
 * to grant access again instead. Removal sits in the overflow menu.
 */
@Composable
fun LibraryFolderCard(
    item: LibraryFolder,
    onOpen: () -> Unit,
    onRegrant: () -> Unit,
    onRemove: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val lost = item.access == FolderAccess.Lost
    Card(
        onClick = if (lost) onRegrant else onOpen,
        shape = MaterialTheme.shapes.large,
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainer),
        modifier = modifier.fillMaxWidth(),
    ) {
        Row(
            Modifier.padding(start = 16.dp, top = 16.dp, end = 4.dp, bottom = if (lost) 8.dp else 16.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            ShapeIcon(
                icon = if (lost) Icons.Rounded.FolderOff else Icons.Rounded.Folder,
                shape = MaterialShapes.Cookie6Sided,
                size = 48.dp,
                containerColor = if (lost) MaterialTheme.colorScheme.errorContainer else MaterialTheme.colorScheme.primaryContainer,
                contentColor = if (lost) MaterialTheme.colorScheme.onErrorContainer else MaterialTheme.colorScheme.onPrimaryContainer,
            )
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Text(item.folder.displayName, style = MaterialTheme.typography.titleMedium)
                AccessLabel(item.access)
            }
            FolderMenu(folderName = item.folder.displayName, onRemove = onRemove)
        }
        if (lost) {
            Column(Modifier.padding(start = 16.dp, end = 16.dp, bottom = 16.dp)) {
                Text(
                    stringResource(R.string.library_access_lost_body),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(Modifier.height(12.dp))
                FilledTonalButton(onClick = onRegrant) {
                    Icon(Icons.Rounded.Refresh, contentDescription = null, modifier = Modifier.size(18.dp))
                    Spacer(Modifier.size(8.dp))
                    Text(stringResource(R.string.library_regrant))
                }
            }
        }
    }
}

@Composable
private fun AccessLabel(access: FolderAccess) {
    val (icon: ImageVector, text: Int, color: Color) = when (access) {
        FolderAccess.ReadWrite -> Triple(Icons.Rounded.Edit, R.string.library_access_read_write, MaterialTheme.colorScheme.onSurfaceVariant)
        FolderAccess.ReadOnly -> Triple(Icons.Rounded.Lock, R.string.library_access_read_only, MaterialTheme.colorScheme.onSurfaceVariant)
        FolderAccess.Lost -> Triple(Icons.Rounded.WarningAmber, R.string.library_access_lost, MaterialTheme.colorScheme.error)
    }
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        Icon(icon, contentDescription = null, tint = color, modifier = Modifier.size(16.dp))
        Text(stringResource(text), style = MaterialTheme.typography.bodyMedium, color = color)
    }
}

@Composable
private fun FolderMenu(folderName: String, onRemove: () -> Unit) {
    var expanded by rememberSaveable { mutableStateOf(false) }
    Box {
        IconButton(onClick = { expanded = true }) {
            Icon(Icons.Rounded.MoreVert, contentDescription = stringResource(R.string.library_folder_options, folderName))
        }
        DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
            DropdownMenuItem(
                text = { Text(stringResource(R.string.library_remove_folder)) },
                leadingIcon = { Icon(Icons.Rounded.RemoveCircleOutline, contentDescription = null) },
                onClick = {
                    expanded = false
                    onRemove()
                },
            )
        }
    }
}
