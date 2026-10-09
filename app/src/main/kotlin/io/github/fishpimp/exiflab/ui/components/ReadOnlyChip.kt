package io.github.fishpimp.exiflab.ui.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Lock
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import io.github.fishpimp.exiflab.R

/**
 * A non-interactive chip saying a photo cannot be edited in place. Icon plus text, so the state
 * never depends on color alone.
 */
@Composable
fun ReadOnlyChip(modifier: Modifier = Modifier) {
    Surface(
        shape = MaterialTheme.shapes.small,
        color = MaterialTheme.colorScheme.secondaryContainer,
        contentColor = MaterialTheme.colorScheme.onSecondaryContainer,
        modifier = modifier,
    ) {
        Row(
            modifier = Modifier.heightIn(min = 32.dp).padding(horizontal = 12.dp, vertical = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Icon(Icons.Rounded.Lock, contentDescription = null, modifier = Modifier.size(18.dp))
            Text(stringResource(R.string.photo_read_only), style = MaterialTheme.typography.labelLarge)
        }
    }
}
