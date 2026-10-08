package io.github.fishpimp.exiflab.core.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.size
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.unit.dp
import coil3.compose.AsyncImage
import io.github.fishpimp.exiflab.core.data.api.ImageRef
import io.github.fishpimp.exiflab.core.designsystem.icon.ExifIcon
import io.github.fishpimp.exiflab.core.designsystem.icon.ExifIcons
import io.github.fishpimp.exiflab.model.ImageFormat

/**
 * Coil model for the JPEG preview embedded in a file (RAW previews, EXIF thumbnails). The app registers a Coil
 * fetcher for it that calls MetadataRepository.embeddedPreview.
 */
public data class EmbeddedPreview(val ref: ImageRef)

/** True when the platform decoder cannot be expected to decode the file (RAW formats). */
public fun ImageRef.prefersEmbeddedPreview(): Boolean {
    val byName = ImageFormat.fromExtension(displayName)
    if (byName.isRaw && byName != ImageFormat.DNG) return true
    val mime = mimeType ?: return false
    return mime.startsWith("image/x-") && mime != "image/x-adobe-dng"
}

/**
 * Thumbnail of an image: decoded by Coil from the content URI, falling back to the embedded preview (RAW files,
 * HEIC on Android 8) and finally to an icon.
 */
@Composable
public fun PhotoThumbnail(
    ref: ImageRef,
    contentDescription: String?,
    modifier: Modifier = Modifier,
    shape: Shape = MaterialTheme.shapes.medium,
    contentScale: ContentScale = ContentScale.Crop,
) {
    var stage by remember(ref.uri) { mutableStateOf(if (ref.prefersEmbeddedPreview()) 1 else 0) }
    Box(modifier.clip(shape).background(MaterialTheme.colorScheme.surfaceContainerHigh), contentAlignment = Alignment.Center) {
        when (stage) {
            0 -> AsyncImage(
                model = ref.uri,
                contentDescription = contentDescription,
                contentScale = contentScale,
                onError = { stage = 1 },
                modifier = Modifier.fillMaxSize(),
            )
            1 -> AsyncImage(
                model = EmbeddedPreview(ref),
                contentDescription = contentDescription,
                contentScale = contentScale,
                onError = { stage = 2 },
                modifier = Modifier.fillMaxSize(),
            )
            else -> ExifIcon(ExifIcons.HideImage, contentDescription = contentDescription, modifier = Modifier.size(28.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

private fun Modifier.fillMaxSize(): Modifier = this.then(androidx.compose.foundation.layout.fillMaxSize().let { Modifier })
