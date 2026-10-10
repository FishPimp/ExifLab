package io.github.fishpimp.exiflab.ui.photo

import androidx.compose.animation.core.animate
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.rememberTransformableState
import androidx.compose.foundation.gestures.transformable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material3.FilledTonalIconButton
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import coil3.compose.AsyncImage
import coil3.request.ImageRequest
import coil3.size.Precision
import io.github.fishpimp.exiflab.R
import io.github.fishpimp.exiflab.data.photos.PhotoRef
import kotlinx.coroutines.launch

private const val MAX_ZOOM = 6f
private const val DOUBLE_TAP_ZOOM = 2.5f

/** Longest edge, in pixels, the zoomable preview decodes: sharp at high zoom without risking memory on 100 MP files. */
private const val MAX_DECODE_PIXELS = 4096

/** The photo on its own, edge to edge. Pinch to zoom and pan; double-tap to zoom in on a spot or back out. */
@Composable
fun FullScreenPreview(ref: PhotoRef, description: String, onDismiss: () -> Unit) {
    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false, decorFitsSystemWindows = false),
    ) {
        val context = LocalContext.current
        val request = remember(ref, context) {
            ImageRequest.Builder(context).data(ref).size(MAX_DECODE_PIXELS, MAX_DECODE_PIXELS).precision(Precision.INEXACT).build()
        }
        val scope = rememberCoroutineScope()
        var scale by remember { mutableFloatStateOf(1f) }
        var offset by remember { mutableStateOf(Offset.Zero) }
        var bounds by remember { mutableStateOf(IntSize.Zero) }

        fun clamp(value: Offset, zoom: Float): Offset {
            val maxX = bounds.width * (zoom - 1) / 2
            val maxY = bounds.height * (zoom - 1) / 2
            return Offset(value.x.coerceIn(-maxX, maxX), value.y.coerceIn(-maxY, maxY))
        }

        val transform = rememberTransformableState { zoomChange, panChange, _ ->
            val zoom = (scale * zoomChange).coerceIn(1f, MAX_ZOOM)
            offset = clamp(offset + panChange, zoom)
            scale = zoom
        }

        Box(
            Modifier
                .fillMaxSize()
                .background(MaterialTheme.colorScheme.scrim)
                .onSizeChanged { bounds = it }
                .pointerInput(Unit) {
                    detectTapGestures(
                        onDoubleTap = { tap ->
                            val startScale = scale
                            val startOffset = offset
                            val targetScale = if (scale > 1f) 1f else DOUBLE_TAP_ZOOM
                            // Keep the tapped spot under the finger while zooming in.
                            val center = Offset(bounds.width / 2f, bounds.height / 2f)
                            val targetOffset = if (targetScale > 1f) clamp((center - tap) * (targetScale - 1), targetScale) else Offset.Zero
                            scope.launch {
                                animate(0f, 1f) { fraction, _ ->
                                    scale = startScale + (targetScale - startScale) * fraction
                                    offset = startOffset + (targetOffset - startOffset) * fraction
                                }
                            }
                        },
                    )
                }
                .transformable(transform),
        ) {
            AsyncImage(
                model = request,
                contentDescription = description,
                contentScale = ContentScale.Fit,
                modifier = Modifier
                    .fillMaxSize()
                    .graphicsLayer {
                        scaleX = scale
                        scaleY = scale
                        translationX = offset.x
                        translationY = offset.y
                    },
            )
            FilledTonalIconButton(
                onClick = onDismiss,
                modifier = Modifier
                    .align(Alignment.TopEnd)
                    .windowInsetsPadding(WindowInsets.safeDrawing)
                    .padding(12.dp),
            ) {
                Icon(Icons.Rounded.Close, contentDescription = stringResource(R.string.action_close))
            }
        }
    }
}
