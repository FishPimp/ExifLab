package io.github.fishpimp.exiflab.ui.shareclean

import android.app.Activity
import android.content.ActivityNotFoundException
import android.content.ComponentName
import android.content.Context
import android.content.ContextWrapper
import android.content.Intent
import android.widget.Toast
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowForward
import androidx.compose.material.icons.rounded.Share
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialShapes
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.PlainTooltip
import androidx.compose.material3.Text
import androidx.compose.material3.TooltipAnchorPosition
import androidx.compose.material3.TooltipBox
import androidx.compose.material3.TooltipDefaults
import androidx.compose.material3.rememberTooltipState
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import io.github.fishpimp.exiflab.MainActivity
import io.github.fishpimp.exiflab.R
import io.github.fishpimp.exiflab.data.shareclean.CleanedFile
import io.github.fishpimp.exiflab.data.shareclean.ShareCleanIntents
import io.github.fishpimp.exiflab.designsystem.component.ShapeIcon
import io.github.fishpimp.exiflab.ui.components.isLargeFontScale

/**
 * The "Share clean" icon action for top bars and toolbars, with a tooltip; its name is also its
 * TalkBack label. [tooltipAbove] for toolbars at the bottom of the screen.
 */
@Composable
fun ShareCleanIconButton(onClick: () -> Unit, modifier: Modifier = Modifier, enabled: Boolean = true, tooltipAbove: Boolean = false) {
    val label = stringResource(R.string.share_clean_action)
    TooltipBox(
        positionProvider = TooltipDefaults.rememberTooltipPositionProvider(
            if (tooltipAbove) TooltipAnchorPosition.Above else TooltipAnchorPosition.Below,
        ),
        tooltip = { PlainTooltip { Text(label) } },
        state = rememberTooltipState(),
        modifier = modifier,
    ) {
        IconButton(onClick = onClick, enabled = enabled) {
            Icon(Icons.Rounded.Share, contentDescription = label)
        }
    }
}

/** Home's "Share without metadata" card: picks photos, then opens the Share clean sheet. */
@Composable
fun ShareCleanHomeCard(onClick: () -> Unit, modifier: Modifier = Modifier, enabled: Boolean = true) {
    val largeFont = isLargeFontScale()
    Card(
        onClick = onClick,
        enabled = enabled,
        shape = MaterialTheme.shapes.extraLarge,
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.tertiaryContainer,
            contentColor = MaterialTheme.colorScheme.onTertiaryContainer,
            disabledContainerColor = MaterialTheme.colorScheme.tertiaryContainer,
            disabledContentColor = MaterialTheme.colorScheme.onTertiaryContainer,
        ),
        modifier = modifier.fillMaxWidth(),
    ) {
        val icon = @Composable {
            ShapeIcon(
                icon = Icons.Rounded.Share,
                shape = MaterialShapes.Cookie7Sided,
                size = 56.dp,
                containerColor = MaterialTheme.colorScheme.tertiary,
                contentColor = MaterialTheme.colorScheme.onTertiary,
            )
        }
        val text = @Composable { textModifier: Modifier ->
            Column(textModifier, verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(stringResource(R.string.home_share_clean_title), style = MaterialTheme.typography.titleLarge)
                Text(stringResource(R.string.home_share_clean_body), style = MaterialTheme.typography.bodyMedium)
            }
        }
        // At large font sizes the icon goes above the text so the text keeps the full card width.
        if (largeFont) {
            Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
                icon()
                text(Modifier)
            }
        } else {
            Row(
                Modifier.padding(horizontal = 20.dp, vertical = 18.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(16.dp),
            ) {
                icon()
                text(Modifier.weight(1f))
                Icon(Icons.AutoMirrored.Rounded.ArrowForward, contentDescription = null)
            }
        }
    }
}

/** ExifLab's own share targets, left out of the share sheet so copies are not shared back into the app. */
fun ownShareTargets(context: Context): List<ComponentName> = listOf(
    ComponentName(context, MainActivity::class.java),
    ComponentName(context, ShareCleanActivity::class.java),
)

/** Opens the system share sheet for [files], with read access to each of them only. */
fun Context.startCleanShare(files: List<CleanedFile>) {
    val uris = files.map { ShareCleanIntents.uriFor(this, it.file) }
    val send = ShareCleanIntents.send(uris, files.map { it.mimeType })
    val title = resources.getQuantityString(R.plurals.share_clean_chooser_title, files.size, files.size)
    val chooser = ShareCleanIntents.chooser(send, title, ownShareTargets(this))
    if (findActivity() == null) chooser.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
    try {
        startActivity(chooser)
    } catch (_: ActivityNotFoundException) {
        Toast.makeText(this, R.string.share_clean_no_app, Toast.LENGTH_LONG).show()
    }
}

private fun Context.findActivity(): Activity? {
    var context: Context? = this
    while (context != null) {
        if (context is Activity) return context
        context = (context as? ContextWrapper)?.baseContext
    }
    return null
}
