package io.github.fishpimp.exiflab.ui.map

import android.content.ActivityNotFoundException
import android.content.ClipData
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.automirrored.rounded.OpenInNew
import androidx.compose.material.icons.rounded.ContentCopy
import androidx.compose.material.icons.rounded.Place
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialShapes
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.RectangleShape
import androidx.compose.ui.platform.ClipEntry
import androidx.compose.ui.platform.LocalClipboard
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalResources
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.core.net.toUri
import io.github.fishpimp.exiflab.R
import io.github.fishpimp.exiflab.designsystem.component.ShapeIcon
import io.github.fishpimp.exiflab.designsystem.theme.ExifLabTheme
import io.github.fishpimp.exiflab.ui.navigation.Route
import kotlinx.coroutines.launch

/** Full-screen map of one location, with actions to copy the coordinates or open another map app. */
@Composable
fun MapViewScreen(route: Route.MapView, onBack: () -> Unit, modifier: Modifier = Modifier) {
    val context = LocalContext.current
    val resources = LocalResources.current
    val clipboard = LocalClipboard.current
    val scope = rememberCoroutineScope()
    val snackbarHostState = remember { SnackbarHostState() }
    val location = remember(route) { LatLng(route.latitude, route.longitude) }
    val title = route.title ?: stringResource(R.string.map_title)

    MapViewContent(
        title = title,
        location = location,
        onBack = onBack,
        onCopyCoordinates = {
            scope.launch {
                val text = location.formatted(COPY_DECIMALS)
                clipboard.setClipEntry(ClipEntry(ClipData.newPlainText(resources.getString(R.string.map_coordinates), text)))
                // Android 13 and later confirm clipboard copies themselves.
                if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) {
                    snackbarHostState.showSnackbar(resources.getString(R.string.map_coordinates_copied))
                }
            }
        },
        onOpenInOtherApp = {
            if (!context.openInMapApp(location, title)) {
                scope.launch { snackbarHostState.showSnackbar(resources.getString(R.string.map_no_app)) }
            }
        },
        snackbarHostState = snackbarHostState,
        modifier = modifier,
    )
}

/** Stateless full-screen map, rendered directly by screenshot tests. */
@Composable
fun MapViewContent(
    title: String,
    location: LatLng,
    onBack: () -> Unit,
    onCopyCoordinates: () -> Unit,
    onOpenInOtherApp: () -> Unit,
    modifier: Modifier = Modifier,
    snackbarHostState: SnackbarHostState = remember { SnackbarHostState() },
) {
    Scaffold(
        modifier = modifier,
        topBar = {
            TopAppBar(
                title = { Text(title, maxLines = 2, overflow = TextOverflow.Ellipsis) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Rounded.ArrowBack, contentDescription = stringResource(R.string.action_back))
                    }
                },
                actions = {
                    IconButton(onClick = onCopyCoordinates) {
                        Icon(Icons.Rounded.ContentCopy, contentDescription = stringResource(R.string.map_copy_coordinates))
                    }
                    IconButton(onClick = onOpenInOtherApp) {
                        Icon(Icons.AutoMirrored.Rounded.OpenInNew, contentDescription = stringResource(R.string.map_open_in_other_app))
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = MaterialTheme.colorScheme.surfaceContainer),
            )
        },
        snackbarHost = { SnackbarHost(snackbarHostState) },
        contentWindowInsets = WindowInsets.safeDrawing.only(WindowInsetsSides.Horizontal + WindowInsetsSides.Top),
        containerColor = MaterialTheme.colorScheme.surface,
    ) { padding ->
        Column(Modifier.fillMaxSize().padding(padding)) {
            Box(Modifier.weight(1f).fillMaxWidth()) {
                LocationMap(
                    marker = location,
                    interactive = true,
                    shape = RectangleShape,
                    modifier = Modifier.fillMaxSize(),
                )
            }
            CoordinatesBar(location)
        }
    }
}

/** The coordinates under the map, always readable whatever the map shows. */
@Composable
private fun CoordinatesBar(location: LatLng, modifier: Modifier = Modifier) {
    Surface(color = MaterialTheme.colorScheme.surfaceContainer, modifier = modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier
                .windowInsetsPadding(WindowInsets.navigationBars.only(WindowInsetsSides.Bottom))
                .padding(horizontal = 16.dp, vertical = 12.dp)
                .semantics(mergeDescendants = true) { },
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            ShapeIcon(icon = Icons.Rounded.Place, shape = MaterialShapes.Cookie9Sided, size = 40.dp)
            Column(Modifier.weight(1f)) {
                Text(
                    stringResource(R.string.map_coordinates),
                    style = MaterialTheme.typography.labelLarge,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Text(location.formatted(), style = ExifLabTheme.extendedTypography.monoLarge)
            }
        }
    }
}

/**
 * Opens [location] in another map app with a geo: link, labelled with [label]. False when no
 * installed app can show it. The receiving app gets only the coordinates and label.
 */
private fun Context.openInMapApp(location: LatLng, label: String): Boolean {
    val point = location.formatted(COPY_DECIMALS).replace(" ", "")
    val uri = "geo:$point?q=${Uri.encode("$point($label)")}".toUri()
    return try {
        startActivity(Intent(Intent.ACTION_VIEW, uri))
        true
    } catch (_: ActivityNotFoundException) {
        false
    }
}
