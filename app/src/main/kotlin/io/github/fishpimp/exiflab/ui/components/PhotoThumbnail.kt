package io.github.fishpimp.exiflab.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Image
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import coil3.compose.AsyncImage
import coil3.compose.AsyncImagePainter
import io.github.fishpimp.exiflab.data.photos.PhotoRef
import io.github.fishpimp.exiflab.data.photos.formatLabel

/**
 * A photo loaded through the app's image loader (RAW previews included). Until an image is
 * available, and when none can be made, a neutral placeholder shows the file format instead.
 * The caller sizes it through [modifier].
 *
 * @param contentDescription usually the file name; null when an enclosing element describes it.
 * @param showFormatLabel false when the caller already shows the format, e.g. as a badge.
 */
@Composable
fun PhotoThumbnail(
    ref: PhotoRef,
    contentDescription: String?,
    modifier: Modifier = Modifier,
    contentScale: ContentScale = ContentScale.Crop,
    showFormatLabel: Boolean = true,
) {
    var showsImage by remember(ref) { mutableStateOf(false) }
    Box(modifier, contentAlignment = Alignment.Center) {
        if (!showsImage) ThumbnailPlaceholder(ref.formatLabel?.takeIf { showFormatLabel }, Modifier.matchParentSize())
        AsyncImage(
            model = ref,
            contentDescription = contentDescription,
            contentScale = contentScale,
            onState = { showsImage = it is AsyncImagePainter.State.Success },
            modifier = Modifier.matchParentSize(),
        )
    }
}

@Composable
private fun ThumbnailPlaceholder(label: String?, modifier: Modifier) {
    Box(modifier.background(MaterialTheme.colorScheme.surfaceContainerHighest), contentAlignment = Alignment.Center) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(4.dp),
            modifier = Modifier.padding(8.dp),
        ) {
            Icon(
                Icons.Rounded.Image,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.size(24.dp),
            )
            if (label != null) {
                Text(
                    label,
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Clip,
                )
            }
        }
    }
}
