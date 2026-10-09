package io.github.fishpimp.exiflab.ui.privacy

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Backup
import androidx.compose.material.icons.rounded.Block
import androidx.compose.material.icons.rounded.CloudOff
import androidx.compose.material.icons.rounded.Folder
import androidx.compose.material.icons.rounded.Map
import androidx.compose.material.icons.rounded.Public
import androidx.compose.material.icons.rounded.TravelExplore
import androidx.compose.material.icons.rounded.VerifiedUser
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialShapes
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.graphics.shapes.RoundedPolygon
import io.github.fishpimp.exiflab.R
import io.github.fishpimp.exiflab.data.network.AllowedHosts
import io.github.fishpimp.exiflab.designsystem.component.GroupSurface
import io.github.fishpimp.exiflab.designsystem.component.ShapeIcon
import io.github.fishpimp.exiflab.designsystem.theme.ExifLabTheme
import io.github.fishpimp.exiflab.ui.components.ScreenScaffold

private data class PrivacyPoint(
    val icon: ImageVector,
    val shape: RoundedPolygon,
    val title: Int,
    val body: Int,
    val details: (@Composable () -> Unit)? = null,
)

private val points = listOf(
    PrivacyPoint(Icons.Rounded.CloudOff, MaterialShapes.Cookie9Sided, R.string.privacy_offline_title, R.string.privacy_offline_body),
    PrivacyPoint(Icons.Rounded.Block, MaterialShapes.Clover4Leaf, R.string.privacy_tracking_title, R.string.privacy_tracking_body),
    PrivacyPoint(
        Icons.Rounded.Public,
        MaterialShapes.Sunny,
        R.string.privacy_network_title,
        R.string.privacy_network_body,
        details = { NetworkHosts() },
    ),
    PrivacyPoint(Icons.Rounded.Folder, MaterialShapes.Cookie6Sided, R.string.privacy_files_title, R.string.privacy_files_body),
    PrivacyPoint(Icons.Rounded.Backup, MaterialShapes.Pill, R.string.privacy_backups_title, R.string.privacy_backups_body),
    PrivacyPoint(Icons.Rounded.VerifiedUser, MaterialShapes.Gem, R.string.privacy_permissions_title, R.string.privacy_permissions_body),
)

@Composable
fun PrivacyScreen(onBack: () -> Unit, modifier: Modifier = Modifier) {
    ScreenScaffold(
        title = stringResource(R.string.privacy_title),
        subtitle = stringResource(R.string.privacy_subtitle),
        onBack = onBack,
        modifier = modifier,
    ) { padding ->
        Box(Modifier.fillMaxSize().padding(padding), contentAlignment = Alignment.TopCenter) {
            LazyColumn(
                modifier = Modifier.widthIn(max = 720.dp).fillMaxSize(),
                contentPadding = PaddingValues(start = 16.dp, end = 16.dp, bottom = 32.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                items(points) { point ->
                    GroupSurface {
                        Row(
                            modifier = Modifier.fillMaxWidth().padding(20.dp),
                            horizontalArrangement = Arrangement.spacedBy(16.dp),
                        ) {
                            ShapeIcon(icon = point.icon, shape = point.shape, size = 48.dp)
                            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                                Text(
                                    stringResource(point.title),
                                    style = MaterialTheme.typography.titleMedium,
                                    modifier = Modifier.semantics { heading() },
                                )
                                Text(
                                    stringResource(point.body),
                                    style = MaterialTheme.typography.bodyMedium,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                                point.details?.invoke()
                            }
                        }
                    }
                }
            }
        }
    }
}

/** The two servers ExifLab may contact, named exactly as the network allowlist has them. */
@Composable
private fun NetworkHosts() {
    Column(
        modifier = Modifier.padding(top = 8.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        HostRow(Icons.Rounded.Map, AllowedHosts.MAP_TILES, stringResource(R.string.privacy_network_tiles))
        HostRow(Icons.Rounded.TravelExplore, AllowedHosts.PLACE_SEARCH, stringResource(R.string.privacy_network_search))
        Row(
            modifier = Modifier.padding(top = 4.dp).semantics(mergeDescendants = true) { },
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Icon(
                Icons.Rounded.CloudOff,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.primary,
                modifier = Modifier.size(20.dp),
            )
            Text(
                stringResource(R.string.privacy_network_offline),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurface,
            )
        }
    }
}

@Composable
private fun HostRow(icon: ImageVector, host: String, purpose: String) {
    Surface(
        shape = MaterialTheme.shapes.medium,
        color = MaterialTheme.colorScheme.surfaceContainerHighest,
        modifier = Modifier.fillMaxWidth(),
    ) {
        Row(
            modifier = Modifier.padding(12.dp).semantics(mergeDescendants = true) { },
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Icon(icon, contentDescription = null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(20.dp))
            Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Text(host, style = ExifLabTheme.extendedTypography.monoMedium, color = MaterialTheme.colorScheme.onSurface)
                Text(purpose, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    }
}
