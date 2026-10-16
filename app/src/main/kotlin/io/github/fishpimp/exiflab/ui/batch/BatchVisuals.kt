package io.github.fishpimp.exiflab.ui.batch

import android.content.Context
import androidx.annotation.StringRes
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Block
import androidx.compose.material.icons.rounded.CheckCircle
import androidx.compose.material.icons.rounded.CleaningServices
import androidx.compose.material.icons.rounded.Copyright
import androidx.compose.material.icons.rounded.Description
import androidx.compose.material.icons.rounded.EditLocationAlt
import androidx.compose.material.icons.rounded.ErrorOutline
import androidx.compose.material.icons.rounded.FileCopy
import androidx.compose.material.icons.rounded.HourglassEmpty
import androidx.compose.material.icons.rounded.Sync
import androidx.compose.material.icons.rounded.TravelExplore
import androidx.compose.material.icons.rounded.Update
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import io.github.fishpimp.exiflab.R
import io.github.fishpimp.exiflab.data.batch.BatchItemReason
import io.github.fishpimp.exiflab.data.batch.BatchItemStatus
import io.github.fishpimp.exiflab.data.batch.BatchJobItem
import io.github.fishpimp.exiflab.data.batch.BatchKind
import io.github.fishpimp.exiflab.data.batch.batchKindOfTitle
import io.github.fishpimp.exiflab.data.history.SaveError
import io.github.fishpimp.exiflab.metadata.edit.StripCategory
import io.github.fishpimp.exiflab.ui.history.messageRes

val BatchKind.icon: ImageVector
    get() = when (this) {
        BatchKind.Location -> Icons.Rounded.EditLocationAlt
        BatchKind.ShiftTime -> Icons.Rounded.Update
        BatchKind.TimeZone -> Icons.Rounded.TravelExplore
        BatchKind.Author -> Icons.Rounded.Copyright
        BatchKind.Strip -> Icons.Rounded.CleaningServices
    }

@get:StringRes
val BatchKind.labelRes: Int
    get() = when (this) {
        BatchKind.Location -> R.string.batch_action_location
        BatchKind.ShiftTime -> R.string.batch_action_shift
        BatchKind.TimeZone -> R.string.batch_action_time_zone
        BatchKind.Author -> R.string.batch_action_author
        BatchKind.Strip -> R.string.batch_action_strip
    }

/** A batch's stored title in the app's language: its kind's name, or the text older versions stored. */
@Composable
fun batchTitle(stored: String): String = batchKindOfTitle(stored)?.let { stringResource(it.labelRes) } ?: stored

/** [batchTitle] outside composition, e.g. for notifications. */
fun Context.batchTitle(stored: String): String = batchKindOfTitle(stored)?.let { getString(it.labelRes) } ?: stored

@get:StringRes
val BatchKind.descriptionRes: Int
    get() = when (this) {
        BatchKind.Location -> R.string.batch_action_location_body
        BatchKind.ShiftTime -> R.string.batch_action_shift_body
        BatchKind.TimeZone -> R.string.batch_action_time_zone_body
        BatchKind.Author -> R.string.batch_action_author_body
        BatchKind.Strip -> R.string.batch_action_strip_body
    }

val BatchItemStatus.icon: ImageVector
    get() = when (this) {
        BatchItemStatus.Pending -> Icons.Rounded.HourglassEmpty
        BatchItemStatus.Running -> Icons.Rounded.Sync
        BatchItemStatus.Saved -> Icons.Rounded.CheckCircle
        BatchItemStatus.Sidecar -> Icons.Rounded.Description
        BatchItemStatus.Copy -> Icons.Rounded.FileCopy
        BatchItemStatus.Skipped -> Icons.Rounded.Block
        BatchItemStatus.Failed -> Icons.Rounded.ErrorOutline
    }

@get:StringRes
val BatchItemStatus.labelRes: Int
    get() = when (this) {
        BatchItemStatus.Pending -> R.string.batch_status_pending
        BatchItemStatus.Running -> R.string.batch_status_running
        BatchItemStatus.Saved -> R.string.batch_status_saved
        BatchItemStatus.Sidecar -> R.string.batch_status_sidecar
        BatchItemStatus.Copy -> R.string.batch_status_copy
        BatchItemStatus.Skipped -> R.string.batch_status_skipped
        BatchItemStatus.Failed -> R.string.batch_status_failed
    }

@get:StringRes
val BatchItemReason.messageRes: Int
    get() = when (this) {
        BatchItemReason.NothingToChange -> R.string.batch_reason_nothing
        BatchItemReason.ReadOnly -> R.string.batch_reason_read_only
        BatchItemReason.CannotStrip -> R.string.batch_reason_cannot_strip
        BatchItemReason.UnsupportedFormat -> R.string.batch_reason_unsupported
        BatchItemReason.NoSidecarPlace -> R.string.batch_reason_no_sidecar_place
        BatchItemReason.Cancelled -> R.string.batch_reason_cancelled
        BatchItemReason.Unreadable -> R.string.batch_reason_unreadable
        BatchItemReason.PlanFailed -> R.string.batch_reason_plan_failed
        BatchItemReason.CopyFolderUnavailable -> R.string.batch_reason_folder_unavailable
        BatchItemReason.SaveFailed -> R.string.history_error_unknown
    }

@get:StringRes
val StripCategory.titleRes: Int
    get() = when (this) {
        StripCategory.All -> R.string.batch_strip_all
        StripCategory.Location -> R.string.batch_strip_location
        StripCategory.DeviceIdentifiers -> R.string.batch_strip_device_ids
        StripCategory.DeviceInfo -> R.string.batch_strip_device_info
        StripCategory.PersonNames -> R.string.batch_strip_names
        StripCategory.People -> R.string.batch_strip_people
        StripCategory.Dates -> R.string.batch_strip_dates
        StripCategory.MakerNotes -> R.string.batch_strip_maker_notes
        StripCategory.Thumbnail -> R.string.batch_strip_thumbnail
        StripCategory.EditHistory -> R.string.batch_strip_edit_history
    }

@get:StringRes
val StripCategory.bodyRes: Int
    get() = when (this) {
        StripCategory.All -> R.string.batch_strip_all_body
        StripCategory.Location -> R.string.batch_strip_location_body
        StripCategory.DeviceIdentifiers -> R.string.batch_strip_device_ids_body
        StripCategory.DeviceInfo -> R.string.batch_strip_device_info_body
        StripCategory.PersonNames -> R.string.batch_strip_names_body
        StripCategory.People -> R.string.batch_strip_people_body
        StripCategory.Dates -> R.string.batch_strip_dates_body
        StripCategory.MakerNotes -> R.string.batch_strip_maker_notes_body
        StripCategory.Thumbnail -> R.string.batch_strip_thumbnail_body
        StripCategory.EditHistory -> R.string.batch_strip_edit_history_body
    }

/** A readable reason for a skipped or failed item, or null for written ones. */
@get:StringRes
val BatchJobItem.reasonRes: Int?
    get() = when {
        saveError != null -> saveError.messageRes
        reason == BatchItemReason.SaveFailed -> SaveError.Unknown.messageRes
        else -> reason?.messageRes
    }

/** Container and content colors of a status chip; the icon and label carry the meaning too. */
@Composable
private fun BatchItemStatus.colors(): Pair<Color, Color> {
    val colors = MaterialTheme.colorScheme
    return when (this) {
        BatchItemStatus.Saved, BatchItemStatus.Sidecar, BatchItemStatus.Copy -> colors.primaryContainer to colors.onPrimaryContainer
        BatchItemStatus.Failed -> colors.errorContainer to colors.onErrorContainer
        BatchItemStatus.Skipped -> colors.surfaceContainerHighest to colors.onSurfaceVariant
        BatchItemStatus.Pending, BatchItemStatus.Running -> colors.secondaryContainer to colors.onSecondaryContainer
    }
}

/** The outcome of one photo: icon and text on a tonal pill. */
@Composable
fun BatchStatusChip(status: BatchItemStatus, modifier: Modifier = Modifier) {
    val (container, content) = status.colors()
    Surface(shape = MaterialTheme.shapes.small, color = container, contentColor = content, modifier = modifier) {
        Row(
            Modifier.heightIn(min = 28.dp).padding(horizontal = 10.dp, vertical = 4.dp),
            horizontalArrangement = Arrangement.spacedBy(6.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(status.icon, contentDescription = null, modifier = Modifier.size(16.dp))
            Text(stringResource(status.labelRes), style = MaterialTheme.typography.labelLarge)
        }
    }
}
