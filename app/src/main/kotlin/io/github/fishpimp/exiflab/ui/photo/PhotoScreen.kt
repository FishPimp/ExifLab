package io.github.fishpimp.exiflab.ui.photo

import android.text.format.Formatter
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import io.github.fishpimp.exiflab.R
import io.github.fishpimp.exiflab.data.photos.PhotoRef
import io.github.fishpimp.exiflab.data.photos.formatLabel
import io.github.fishpimp.exiflab.ui.components.PhotoThumbnail
import io.github.fishpimp.exiflab.ui.components.ReadOnlyChip
import io.github.fishpimp.exiflab.ui.components.ScreenScaffold

/**
 * Shows one photo: a large preview, its name and size, and whether it can be edited in place.
 * A placeholder until the metadata viewer replaces it.
 */
@Composable
fun PhotoScreen(ref: PhotoRef, onBack: () -> Unit, modifier: Modifier = Modifier) {
    val name = ref.displayName ?: stringResource(R.string.photo_untitled)
    val context = LocalContext.current
    val details = remember(ref, context) {
        listOfNotNull(ref.formatLabel, ref.size?.let { Formatter.formatShortFileSize(context, it) }).joinToString(DETAIL_SEPARATOR)
    }
    ScreenScaffold(title = name, onBack = onBack, modifier = modifier) { padding ->
        Box(Modifier.fillMaxSize().padding(padding), contentAlignment = Alignment.TopCenter) {
            Column(
                modifier = Modifier
                    .widthIn(max = 840.dp)
                    .verticalScroll(rememberScrollState())
                    .padding(start = 16.dp, end = 16.dp, bottom = 32.dp),
                verticalArrangement = Arrangement.spacedBy(16.dp),
            ) {
                PhotoThumbnail(
                    ref = ref,
                    contentDescription = stringResource(R.string.photo_preview_description, name),
                    contentScale = ContentScale.Fit,
                    modifier = Modifier
                        .fillMaxWidth()
                        .aspectRatio(PREVIEW_ASPECT_RATIO)
                        .clip(MaterialTheme.shapes.large),
                )
                FlowRow(
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                    itemVerticalAlignment = Alignment.CenterVertically,
                ) {
                    if (details.isNotEmpty()) {
                        Text(
                            details,
                            style = MaterialTheme.typography.bodyLarge,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    if (!ref.writable) ReadOnlyChip()
                }
            }
        }
    }
}

private const val PREVIEW_ASPECT_RATIO = 4f / 3f
private const val DETAIL_SEPARATOR = " · "
