package io.github.fishpimp.exiflab.ui.components

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Lock
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.unit.dp
import io.github.fishpimp.exiflab.R
import io.github.fishpimp.exiflab.data.photos.PhotoRef
import io.github.fishpimp.exiflab.data.photos.formatLabel

/** Column layout shared by photo grids: as many 112dp-wide cells as fit. */
val PhotoGridCells: GridCells = GridCells.Adaptive(112.dp)

/**
 * A square grid cell for one photo: thumbnail, a format badge and a lock on read-only files.
 * TalkBack reads the file name and, when it applies, that the photo is read-only.
 */
@Composable
fun PhotoTile(ref: PhotoRef, onClick: () -> Unit, modifier: Modifier = Modifier) {
    val description = photoDescription(ref)
    Box(
        modifier
            .aspectRatio(1f)
            .clip(MaterialTheme.shapes.medium)
            .clickable(role = Role.Button, onClick = onClick)
            .clearAndSetSemantics { contentDescription = description },
    ) {
        PhotoThumbnail(ref = ref, contentDescription = null, showFormatLabel = false, modifier = Modifier.fillMaxSize())
        ref.formatLabel?.let { label ->
            FormatBadge(label, Modifier.align(Alignment.BottomStart).padding(6.dp))
        }
        if (!ref.writable) {
            Surface(
                shape = CircleShape,
                color = MaterialTheme.colorScheme.secondaryContainer,
                contentColor = MaterialTheme.colorScheme.onSecondaryContainer,
                modifier = Modifier.align(Alignment.TopEnd).padding(6.dp),
            ) {
                Icon(Icons.Rounded.Lock, contentDescription = null, modifier = Modifier.padding(4.dp).size(16.dp))
            }
        }
    }
}

/** A small label such as "RAF" on top of a thumbnail. */
@Composable
fun FormatBadge(label: String, modifier: Modifier = Modifier) {
    Surface(
        shape = MaterialTheme.shapes.extraSmall,
        color = MaterialTheme.colorScheme.surfaceBright.copy(alpha = 0.9f),
        contentColor = MaterialTheme.colorScheme.onSurface,
        modifier = modifier,
    ) {
        Text(label, style = MaterialTheme.typography.labelSmall, maxLines = 1, modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp))
    }
}

/** File name for TalkBack, with the read-only state appended when the file cannot be edited in place. */
@Composable
fun photoDescription(ref: PhotoRef): String {
    val name = ref.displayName ?: stringResource(R.string.photo_untitled)
    return if (ref.writable) name else stringResource(R.string.photo_description_read_only, name)
}
