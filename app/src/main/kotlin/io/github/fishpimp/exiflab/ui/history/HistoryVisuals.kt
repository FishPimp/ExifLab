package io.github.fishpimp.exiflab.ui.history

import androidx.annotation.StringRes
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.Undo
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.CheckCircle
import androidx.compose.material.icons.rounded.ContentCopy
import androidx.compose.material.icons.rounded.Description
import androidx.compose.material.icons.rounded.Edit
import androidx.compose.material.icons.rounded.ErrorOutline
import androidx.compose.material.icons.rounded.HourglassTop
import androidx.compose.material.icons.rounded.Remove
import androidx.compose.material.icons.rounded.Restore
import androidx.compose.material.icons.rounded.SwapHoriz
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalLocale
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import io.github.fishpimp.exiflab.R
import io.github.fishpimp.exiflab.data.history.EditMode
import io.github.fishpimp.exiflab.data.history.EditRecord
import io.github.fishpimp.exiflab.data.history.EditState
import io.github.fishpimp.exiflab.data.history.SaveError
import io.github.fishpimp.exiflab.designsystem.theme.ExifLabTheme
import io.github.fishpimp.exiflab.metadata.edit.FieldDiff
import io.github.fishpimp.exiflab.metadata.model.DirectoryGroup
import io.github.fishpimp.exiflab.ui.photo.breakableFileName
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle

/** A small non-interactive label with an icon and text, so meaning never rests on color alone. */
@Composable
fun StatusChip(
    icon: ImageVector,
    text: String,
    containerColor: Color,
    contentColor: Color,
    modifier: Modifier = Modifier,
    border: BorderStroke? = null,
) {
    Surface(
        shape = MaterialTheme.shapes.small,
        color = containerColor,
        contentColor = contentColor,
        border = border,
        modifier = modifier,
    ) {
        Row(
            modifier = Modifier.heightIn(min = 28.dp).padding(horizontal = 10.dp, vertical = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            Icon(icon, contentDescription = null, modifier = Modifier.size(16.dp))
            Text(text, style = MaterialTheme.typography.labelLarge)
        }
    }
}

/** Where the edit went: in place, a copy or a sidecar. Outlined, to read as a property rather than a status. */
@Composable
fun ModeChip(mode: EditMode, modifier: Modifier = Modifier) {
    StatusChip(
        icon = mode.icon,
        text = stringResource(mode.labelRes),
        containerColor = Color.Transparent,
        contentColor = MaterialTheme.colorScheme.onSurfaceVariant,
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
        modifier = modifier,
    )
}

/** The outcome of an edit, as icon plus text on a matching container color. */
@Composable
fun StateChip(state: EditState, modifier: Modifier = Modifier) {
    val colors = MaterialTheme.colorScheme
    val (container, content) = when (state) {
        EditState.Pending -> colors.surfaceContainerHighest to colors.onSurfaceVariant
        EditState.Committed -> colors.primaryContainer to colors.onPrimaryContainer
        EditState.Restored -> colors.tertiaryContainer to colors.onTertiaryContainer
        EditState.RolledBack -> colors.surfaceContainerHighest to colors.onSurface
        EditState.Failed -> colors.errorContainer to colors.onErrorContainer
    }
    StatusChip(
        icon = state.icon,
        text = stringResource(state.labelRes),
        containerColor = container,
        contentColor = content,
        modifier = modifier,
    )
}

/** Added, changed or removed, colored with the diff roles and always labelled. */
@Composable
fun DiffKindChip(kind: FieldDiff.Kind, modifier: Modifier = Modifier) {
    val colors = ExifLabTheme.extendedColors
    val (container, content) = when (kind) {
        FieldDiff.Kind.Added -> colors.addedContainer to colors.onAddedContainer
        FieldDiff.Kind.Changed -> colors.changedContainer to colors.onChangedContainer
        FieldDiff.Kind.Removed -> colors.removedContainer to colors.onRemovedContainer
    }
    StatusChip(
        icon = kind.icon,
        text = stringResource(kind.labelRes),
        containerColor = container,
        contentColor = content,
        modifier = modifier,
    )
}

val EditMode.icon: ImageVector
    get() = when (this) {
        EditMode.InPlace -> Icons.Rounded.Edit
        EditMode.Copy -> Icons.Rounded.ContentCopy
        EditMode.Sidecar -> Icons.Rounded.Description
    }

@get:StringRes
val EditMode.labelRes: Int
    get() = when (this) {
        EditMode.InPlace -> R.string.history_mode_in_place
        EditMode.Copy -> R.string.history_mode_copy
        EditMode.Sidecar -> R.string.history_mode_sidecar
    }

val EditState.icon: ImageVector
    get() = when (this) {
        EditState.Pending -> Icons.Rounded.HourglassTop
        EditState.Committed -> Icons.Rounded.CheckCircle
        EditState.Restored -> Icons.Rounded.Restore
        EditState.RolledBack -> Icons.AutoMirrored.Rounded.Undo
        EditState.Failed -> Icons.Rounded.ErrorOutline
    }

@get:StringRes
val EditState.labelRes: Int
    get() = when (this) {
        EditState.Pending -> R.string.history_state_saving
        EditState.Committed -> R.string.history_state_saved
        EditState.Restored -> R.string.history_state_restored
        EditState.RolledBack -> R.string.history_state_rolled_back
        EditState.Failed -> R.string.history_state_failed
    }

val FieldDiff.Kind.icon: ImageVector
    get() = when (this) {
        FieldDiff.Kind.Added -> Icons.Rounded.Add
        FieldDiff.Kind.Changed -> Icons.Rounded.SwapHoriz
        FieldDiff.Kind.Removed -> Icons.Rounded.Remove
    }

@get:StringRes
val FieldDiff.Kind.labelRes: Int
    get() = when (this) {
        FieldDiff.Kind.Added -> R.string.history_diff_added
        FieldDiff.Kind.Changed -> R.string.history_diff_changed
        FieldDiff.Kind.Removed -> R.string.history_diff_removed
    }

@get:StringRes
val DirectoryGroup.labelRes: Int
    get() = when (this) {
        DirectoryGroup.Exif -> R.string.history_group_exif
        DirectoryGroup.Gps -> R.string.history_group_gps
        DirectoryGroup.MakerNote -> R.string.history_group_makernote
        DirectoryGroup.Xmp -> R.string.history_group_xmp
        DirectoryGroup.Iptc -> R.string.history_group_iptc
        DirectoryGroup.Icc -> R.string.history_group_icc
        DirectoryGroup.Container -> R.string.history_group_container
        DirectoryGroup.File -> R.string.history_group_file
        DirectoryGroup.Other -> R.string.history_group_other
    }

/** A sentence explaining a failure, for History and snackbars. */
@get:StringRes
val SaveError.messageRes: Int
    get() = when (this) {
        SaveError.ReadFailed -> R.string.history_error_read
        SaveError.SourceChanged -> R.string.history_error_source_changed
        SaveError.BackupFailed -> R.string.history_error_backup
        SaveError.BackupDamaged -> R.string.history_error_backup_damaged
        SaveError.ImageDataChanged -> R.string.history_error_image_data
        SaveError.UnsupportedEdit -> R.string.history_error_unsupported
        SaveError.CheckFailed -> R.string.history_error_check
        SaveError.WriteFailed -> R.string.history_error_write
        SaveError.VerificationFailed -> R.string.history_error_verification
        SaveError.RollbackFailed -> R.string.history_error_rollback
        SaveError.Interrupted -> R.string.history_error_interrupted
        SaveError.RecoveryFailed -> R.string.history_error_recovery
        SaveError.Unknown -> R.string.history_error_unknown
    }

/**
 * The file name History shows for a record: the photo's, or a generic one. Long camera names wrap
 * between their parts rather than inside numbers.
 */
@Composable
fun EditRecord.title(): String =
    displayName?.takeIf { it.isNotBlank() }?.let(::breakableFileName) ?: stringResource(R.string.photo_untitled)

/** The one-line summary of a record. */
@Composable
fun EditRecord.summaryLine(): String = when {
    restoresRecordId != null && summary.isNotBlank() -> stringResource(R.string.history_summary_restore_of, summary)
    restoresRecordId != null -> stringResource(R.string.history_summary_restore)
    summary.isNotBlank() -> summary
    else -> stringResource(R.string.history_summary_metadata)
}

/** "Today", "Yesterday" or the full localized date. */
@Composable
fun dayLabel(date: LocalDate, today: LocalDate): String = when (date) {
    today -> stringResource(R.string.history_today)
    today.minusDays(1) -> stringResource(R.string.history_yesterday)
    else -> DateTimeFormatter.ofLocalizedDate(FormatStyle.FULL).withLocale(LocalLocale.current.platformLocale).format(date)
}

/** "14:03" or "2:03 PM". */
@Composable
fun timeLabel(epochMillis: Long, zone: ZoneId): String =
    DateTimeFormatter.ofLocalizedTime(FormatStyle.SHORT)
        .withLocale(LocalLocale.current.platformLocale)
        .format(Instant.ofEpochMilli(epochMillis).atZone(zone))

/** "9 Oct 2026, 14:03" in the user's conventions. */
@Composable
fun dateTimeLabel(epochMillis: Long, zone: ZoneId): String =
    DateTimeFormatter.ofLocalizedDateTime(FormatStyle.MEDIUM, FormatStyle.SHORT)
        .withLocale(LocalLocale.current.platformLocale)
        .format(Instant.ofEpochMilli(epochMillis).atZone(zone))
