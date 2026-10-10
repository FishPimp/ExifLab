package io.github.fishpimp.exiflab.ui.photo

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.ContentCopy
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.unit.dp
import io.github.fishpimp.exiflab.R
import io.github.fishpimp.exiflab.designsystem.theme.ExifLabTheme
import io.github.fishpimp.exiflab.metadata.model.MetadataTag

/**
 * Everything about one tag: where it lives, its id, both value forms as selectable text, and copy
 * buttons. Opened by tapping a row; long values are shown in full here. "Copy value" copies the
 * form the list currently shows.
 */
@Composable
fun TagDetailsSheet(
    tag: MetadataTag,
    directoryName: String,
    onCopyValue: () -> Unit,
    onCopyNameAndValue: () -> Unit,
    onDismiss: () -> Unit,
) {
    ModalBottomSheet(onDismissRequest = onDismiss) {
        Column(
            Modifier
                .fillMaxWidth()
                .verticalScroll(rememberScrollState())
                .padding(start = 24.dp, end = 24.dp, bottom = 32.dp),
            verticalArrangement = Arrangement.spacedBy(20.dp),
        ) {
            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(tag.name, style = MaterialTheme.typography.headlineSmall, modifier = Modifier.semantics { heading() })
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(directoryName, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    tag.hexId?.let {
                        Text(it, style = ExifLabTheme.extendedTypography.monoMedium, color = MaterialTheme.colorScheme.primary)
                    }
                }
            }
            tag.sensitivity?.let { category ->
                val colors = ExifLabTheme.extendedColors
                Surface(shape = CircleShape, color = colors.sensitiveContainer, contentColor = colors.onSensitiveContainer) {
                    Row(
                        Modifier.heightIn(min = 36.dp).padding(horizontal = 14.dp, vertical = 6.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        Icon(category.icon, contentDescription = null, modifier = Modifier.size(18.dp))
                        Text(stringResource(R.string.photo_tag_sensitive, stringResource(category.labelRes)), style = MaterialTheme.typography.labelLarge)
                    }
                }
            }
            DetailValue(stringResource(R.string.photo_value_readable), tag.displayValue, MaterialTheme.typography.bodyLarge)
            if (tag.rawValue != tag.displayValue) {
                DetailValue(stringResource(R.string.photo_value_raw), tag.rawValue, ExifLabTheme.extendedTypography.monoMedium)
            }
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                FilledTonalButton(onClick = onCopyValue, contentPadding = ButtonDefaults.ButtonWithIconContentPadding) {
                    Icon(Icons.Rounded.ContentCopy, contentDescription = null, modifier = Modifier.size(ButtonDefaults.IconSize))
                    Text(stringResource(R.string.photo_copy_value), modifier = Modifier.padding(start = ButtonDefaults.IconSpacing))
                }
                OutlinedButton(onClick = onCopyNameAndValue) { Text(stringResource(R.string.photo_copy_name_value)) }
            }
        }
    }
}

@Composable
private fun DetailValue(label: String, value: String, style: TextStyle) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(label, style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Surface(shape = MaterialTheme.shapes.medium, color = MaterialTheme.colorScheme.surfaceContainerHigh, modifier = Modifier.fillMaxWidth()) {
            SelectionContainer {
                Text(value, style = style, modifier = Modifier.padding(16.dp))
            }
        }
    }
}
