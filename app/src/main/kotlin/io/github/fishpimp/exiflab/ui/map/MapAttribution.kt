package io.github.fishpimp.exiflab.ui.map

import androidx.annotation.StringRes
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.OpenInNew
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import io.github.fishpimp.exiflab.R
import io.github.fishpimp.exiflab.designsystem.component.ListRow

/**
 * The credit line every map with OpenFreeMap tiles must show: OpenFreeMap, OpenMapTiles and
 * OpenStreetMap contributors. On interactive maps it opens a dialog with links to each.
 */
@Composable
internal fun MapAttribution(clickable: Boolean, modifier: Modifier = Modifier) {
    var showCredits by rememberSaveable { mutableStateOf(false) }
    val pill = @Composable {
        Surface(
            shape = CircleShape,
            color = MaterialTheme.colorScheme.surface.copy(alpha = PILL_ALPHA),
            contentColor = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(6.dp).widthIn(max = MAX_WIDTH.dp),
        ) {
            Text(
                stringResource(R.string.map_attribution),
                style = MaterialTheme.typography.labelSmall,
                modifier = Modifier.padding(horizontal = 8.dp, vertical = 2.dp),
            )
        }
    }
    if (clickable) {
        // The pill stays small; the touch target around it is a full 48dp tall.
        Box(
            modifier = modifier
                .heightIn(min = 48.dp)
                .clickable(
                    onClickLabel = stringResource(R.string.map_credits_action),
                    role = Role.Button,
                    onClick = { showCredits = true },
                ),
            contentAlignment = Alignment.BottomEnd,
        ) { pill() }
    } else {
        Box(modifier) { pill() }
    }
    if (showCredits) MapCreditsDialog(onDismiss = { showCredits = false })
}

private data class MapCredit(@param:StringRes val name: Int, @param:StringRes val role: Int, val url: String)

private val credits = listOf(
    MapCredit(R.string.map_credit_openfreemap, R.string.map_credit_openfreemap_role, "https://openfreemap.org/"),
    MapCredit(R.string.map_credit_openmaptiles, R.string.map_credit_openmaptiles_role, "https://www.openmaptiles.org/"),
    MapCredit(R.string.map_credit_openstreetmap, R.string.map_credit_openstreetmap_role, "https://www.openstreetmap.org/copyright"),
)

@Composable
private fun MapCreditsDialog(onDismiss: () -> Unit) {
    val uriHandler = LocalUriHandler.current
    AlertDialog(
        onDismissRequest = onDismiss,
        confirmButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.action_close)) } },
        title = { Text(stringResource(R.string.map_credits_title)) },
        text = {
            Column {
                credits.forEach { credit ->
                    ListRow(
                        title = stringResource(credit.name),
                        supporting = stringResource(credit.role),
                        // Opens the browser, a separate app; ExifLab itself fetches nothing here.
                        onClick = { runCatching { uriHandler.openUri(credit.url) } },
                        trailing = { Icon(Icons.AutoMirrored.Rounded.OpenInNew, contentDescription = null) },
                    )
                }
            }
        },
    )
}

private const val PILL_ALPHA = 0.85f
private const val MAX_WIDTH = 320
