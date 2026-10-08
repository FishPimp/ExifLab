package io.github.fishpimp.exiflab.core.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.unit.dp
import io.github.fishpimp.exiflab.core.designsystem.icon.ExifIcon
import io.github.fishpimp.exiflab.core.designsystem.icon.ExifIcons
import io.github.fishpimp.exiflab.core.designsystem.theme.ExifLabTheme
import io.github.fishpimp.exiflab.core.designsystem.theme.Spacing
import io.github.fishpimp.exiflab.model.ChangeKind
import io.github.fishpimp.exiflab.model.PlannedChange

/** One row of a pending-changes diff: tag, block, old value (struck through) and new value. */
@Composable
public fun ChangeRow(change: PlannedChange, modifier: Modifier = Modifier) {
    val kindLabel = stringResource(change.kind.labelRes())
    val (container, content) = when (change.kind) {
        ChangeKind.ADD -> ExifLabTheme.extendedColors.success.let { it.container to it.content }
        ChangeKind.MODIFY -> MaterialTheme.colorScheme.secondaryContainer to MaterialTheme.colorScheme.onSecondaryContainer
        ChangeKind.REMOVE -> MaterialTheme.colorScheme.errorContainer to MaterialTheme.colorScheme.onErrorContainer
    }
    val icon = when (change.kind) {
        ChangeKind.ADD -> ExifIcons.Add
        ChangeKind.MODIFY -> ExifIcons.Edit
        ChangeKind.REMOVE -> ExifIcons.Remove
    }
    Row(
        modifier = modifier
            .fillMaxWidth()
            .padding(vertical = Spacing.s)
            .semantics(mergeDescendants = true) {
                contentDescription = buildString {
                    append(kindLabel).append(": ").append(change.tagName).append(", ").append(change.directoryName)
                    change.before?.let { append(". ").append(it) }
                    change.after?.let { append(" -> ").append(it) }
                }
            },
        verticalAlignment = Alignment.Top,
        horizontalArrangement = Arrangement.spacedBy(Spacing.m),
    ) {
        Surface(shape = CircleShape, color = container, contentColor = content, modifier = Modifier.size(32.dp)) {
            Row(horizontalArrangement = Arrangement.Center, verticalAlignment = Alignment.CenterVertically) {
                ExifIcon(icon, contentDescription = null, modifier = Modifier.size(18.dp))
            }
        }
        Column(Modifier.weight(1f)) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(Spacing.s)) {
                Text(change.tagName, style = MaterialTheme.typography.titleSmall)
                Text(change.directoryName, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            if (change.before != null && change.kind != ChangeKind.ADD) {
                Text(
                    change.before!!,
                    style = ExifLabTheme.mono.small.copy(textDecoration = if (change.kind == ChangeKind.REMOVE || change.kind == ChangeKind.MODIFY) TextDecoration.LineThrough else null),
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            if (change.after != null) {
                Text(change.after!!, style = ExifLabTheme.mono.medium, color = MaterialTheme.colorScheme.onSurface)
            }
        }
    }
}

/** A list of [ChangeRow]s (not lazy; use inside a scrolling container for long lists). */
@Composable
public fun ChangeList(changes: List<PlannedChange>, modifier: Modifier = Modifier) {
    Column(modifier) {
        changes.forEach { ChangeRow(it) }
    }
}
