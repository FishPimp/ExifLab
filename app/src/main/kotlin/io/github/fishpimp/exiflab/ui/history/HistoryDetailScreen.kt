package io.github.fishpimp.exiflab.ui.history

import android.text.format.Formatter
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowForward
import androidx.compose.material.icons.automirrored.rounded.Undo
import androidx.compose.material.icons.rounded.ContentCopy
import androidx.compose.material.icons.rounded.DeleteOutline
import androidx.compose.material.icons.rounded.ErrorOutline
import androidx.compose.material.icons.rounded.History
import androidx.compose.material.icons.rounded.HourglassTop
import androidx.compose.material.icons.rounded.Image
import androidx.compose.material.icons.rounded.Inventory2
import androidx.compose.material.icons.rounded.Restore
import androidx.compose.material.icons.rounded.Shield
import androidx.compose.material.icons.rounded.Info
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialShapes
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLocale
import androidx.compose.ui.platform.LocalResources
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import io.github.fishpimp.exiflab.R
import io.github.fishpimp.exiflab.data.history.EditMode
import io.github.fishpimp.exiflab.data.history.EditRecord
import io.github.fishpimp.exiflab.data.history.EditState
import io.github.fishpimp.exiflab.data.history.SaveError
import io.github.fishpimp.exiflab.data.photos.PhotoRef
import io.github.fishpimp.exiflab.designsystem.component.EmptyState
import io.github.fishpimp.exiflab.designsystem.component.GroupSurface
import io.github.fishpimp.exiflab.designsystem.component.SectionHeader
import io.github.fishpimp.exiflab.designsystem.theme.ExifLabTheme
import io.github.fishpimp.exiflab.metadata.edit.FieldDiff
import io.github.fishpimp.exiflab.ui.components.PhotoThumbnail
import io.github.fishpimp.exiflab.ui.components.ScreenScaffold
import io.github.fishpimp.exiflab.ui.components.isLargeFontScale
import java.time.Instant
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle

/** What the detail of an edit can do. */
class HistoryDetailActions(
    val onRestore: () -> Unit = {},
    val onDeleteBackup: () -> Unit = {},
    val onOpenPhoto: () -> Unit = {},
    val onOpenRecord: (String) -> Unit = {},
)

/** One edit on its own screen (phones), opened from the History list. */
@Composable
fun HistoryDetailScreen(
    recordId: String,
    onBack: () -> Unit,
    onOpenPhoto: (PhotoRef) -> Unit,
    onOpenRecord: (String) -> Unit,
    modifier: Modifier = Modifier,
    viewModel: HistoryDetailViewModel = viewModel(key = "history-detail-$recordId", factory = HistoryDetailViewModel.factory(recordId)),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val snackbarHostState = remember { SnackbarHostState() }
    HistoryDetailEffects(viewModel, snackbarHostState, onOpenPhoto, onOpenRecord)
    HistoryDetailContent(
        state = state,
        actions = viewModel.actions(),
        onBack = onBack,
        snackbarHostState = snackbarHostState,
        modifier = modifier,
    )
}

/** One edit in the detail pane of the History list-detail layout (expanded widths). */
@Composable
fun HistoryDetailPane(
    recordId: String,
    snackbarHostState: SnackbarHostState,
    onOpenPhoto: (PhotoRef) -> Unit,
    onOpenRecord: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    val viewModel: HistoryDetailViewModel = viewModel(key = "history-detail-$recordId", factory = HistoryDetailViewModel.factory(recordId))
    val state by viewModel.state.collectAsStateWithLifecycle()
    HistoryDetailEffects(viewModel, snackbarHostState, onOpenPhoto, onOpenRecord)
    HistoryDetailBody(state, viewModel.actions(), modifier, showTitle = true)
}

private fun HistoryDetailViewModel.actions() = HistoryDetailActions(
    onRestore = ::restoreOriginal,
    onDeleteBackup = ::deleteBackup,
    onOpenPhoto = ::openPhoto,
    onOpenRecord = ::openRecord,
)

@Composable
private fun HistoryDetailEffects(
    viewModel: HistoryDetailViewModel,
    snackbarHostState: SnackbarHostState,
    onOpenPhoto: (PhotoRef) -> Unit,
    onOpenRecord: (String) -> Unit,
) {
    val resources = LocalResources.current
    val openPhoto by rememberUpdatedState(onOpenPhoto)
    val openRecord by rememberUpdatedState(onOpenRecord)
    LaunchedEffect(viewModel) {
        viewModel.messages.collect { message ->
            val text = when (message) {
                HistoryDetailMessage.Restored -> resources.getString(R.string.history_restored)
                is HistoryDetailMessage.RestoreFailed -> resources.getString(
                    R.string.history_restore_failed,
                    resources.getString((message.error ?: SaveError.Unknown).messageRes),
                )
                HistoryDetailMessage.BackupDeleted -> resources.getString(R.string.history_backup_deleted)
                HistoryDetailMessage.BackupDeleteFailed -> resources.getString(R.string.history_backup_delete_failed)
                HistoryDetailMessage.PhotoUnavailable -> resources.getString(R.string.history_photo_unavailable)
                is HistoryDetailMessage.OpenPhoto -> {
                    openPhoto(message.ref)
                    null
                }
                is HistoryDetailMessage.OpenRecord -> {
                    openRecord(message.recordId)
                    null
                }
            }
            if (text != null) snackbarHostState.showSnackbar(text)
        }
    }
}

/** The detail screen, stateless: a top app bar named after the photo above [HistoryDetailBody]. */
@Composable
fun HistoryDetailContent(
    state: HistoryDetailUiState,
    actions: HistoryDetailActions,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
    snackbarHostState: SnackbarHostState = remember { SnackbarHostState() },
) {
    ScreenScaffold(
        title = state.record?.title() ?: stringResource(R.string.history_detail_title),
        modifier = modifier,
        onBack = onBack,
        snackbarHost = { SnackbarHost(snackbarHostState) },
    ) { padding ->
        HistoryDetailBody(state, actions, Modifier.padding(padding).fillMaxSize(), showTitle = false)
    }
}

/**
 * The detail of one edit: header, outcome, actions, the field changes and the backup.
 *
 * @param showTitle false when a top app bar already shows the file name.
 */
@Composable
fun HistoryDetailBody(
    state: HistoryDetailUiState,
    actions: HistoryDetailActions,
    modifier: Modifier = Modifier,
    showTitle: Boolean = true,
) {
    val record = state.record
    var confirmRestore by rememberSaveable { mutableStateOf(false) }
    var confirmDelete by rememberSaveable { mutableStateOf(false) }
    when {
        state.isLoading -> Box(modifier)
        record == null -> Box(modifier.verticalScroll(rememberScrollState()), contentAlignment = Alignment.Center) {
            EmptyState(
                icon = Icons.Rounded.History,
                shape = MaterialShapes.Clover4Leaf,
                title = stringResource(R.string.history_detail_missing_title),
                body = stringResource(R.string.history_detail_missing_body),
            )
        }
        else -> Box(modifier, contentAlignment = Alignment.TopCenter) {
            DetailList(state, record, actions, showTitle, onRestore = { confirmRestore = true }, onDeleteBackup = { confirmDelete = true })
        }
    }

    if (record != null && confirmRestore) {
        val undo = record.restoresRecordId != null
        val name = record.targetName ?: record.title()
        AlertDialog(
            onDismissRequest = { confirmRestore = false },
            icon = { Icon(if (undo) Icons.AutoMirrored.Rounded.Undo else Icons.Rounded.Restore, contentDescription = null) },
            title = { Text(stringResource(if (undo) R.string.history_undo_restore_title else R.string.history_restore_title)) },
            text = { Text(stringResource(if (undo) R.string.history_undo_restore_body else R.string.history_restore_body, name)) },
            confirmButton = {
                TextButton(onClick = {
                    confirmRestore = false
                    actions.onRestore()
                }) { Text(stringResource(if (undo) R.string.history_action_undo_restore else R.string.history_action_restore)) }
            },
            dismissButton = { TextButton(onClick = { confirmRestore = false }) { Text(stringResource(R.string.action_cancel)) } },
        )
    }
    if (record != null && confirmDelete) {
        val size = state.backupBytes?.let { Formatter.formatShortFileSize(LocalContext.current, it) }
        AlertDialog(
            onDismissRequest = { confirmDelete = false },
            icon = { Icon(Icons.Rounded.DeleteOutline, contentDescription = null) },
            title = { Text(stringResource(R.string.history_delete_backup_title)) },
            text = {
                Text(
                    if (size != null) {
                        stringResource(R.string.history_delete_backup_body_size, size)
                    } else {
                        stringResource(R.string.history_delete_backup_body)
                    },
                )
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        confirmDelete = false
                        actions.onDeleteBackup()
                    },
                    colors = ButtonDefaults.textButtonColors(contentColor = MaterialTheme.colorScheme.error),
                ) { Text(stringResource(R.string.action_delete)) }
            },
            dismissButton = { TextButton(onClick = { confirmDelete = false }) { Text(stringResource(R.string.action_cancel)) } },
        )
    }
}

@Composable
private fun DetailList(
    state: HistoryDetailUiState,
    record: EditRecord,
    actions: HistoryDetailActions,
    showTitle: Boolean,
    onRestore: () -> Unit,
    onDeleteBackup: () -> Unit,
) {
    LazyColumn(
        modifier = Modifier.widthIn(max = 720.dp).fillMaxSize(),
        contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 8.dp, bottom = 32.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        item(key = "header") { DetailHeader(record, state, showTitle) }
        item(key = "status") { OutcomeCard(record, onOpenRecord = actions.onOpenRecord) }
        item(key = "actions") {
            DetailActions(
                record = record,
                busy = state.busy,
                onRestore = onRestore,
                onOpenPhoto = actions.onOpenPhoto,
                onDeleteBackup = onDeleteBackup,
            )
        }
        item(key = "changes-title") {
            SectionHeader(
                when {
                    state.diff.isEmpty() -> stringResource(R.string.history_detail_changes)
                    // These were never applied, or were undone right away.
                    record.state == EditState.Failed || record.state == EditState.RolledBack ->
                        pluralStringResource(R.plurals.history_change_count_planned, state.diff.size, state.diff.size)
                    else -> pluralStringResource(R.plurals.history_change_count, state.diff.size, state.diff.size)
                },
            )
        }
        item(key = "changes") { DiffList(state.diff) }
        item(key = "backup-title") { SectionHeader(stringResource(R.string.history_detail_backup)) }
        item(key = "backup") { BackupCard(record, state) }
    }
}

@Composable
private fun DetailHeader(record: EditRecord, state: HistoryDetailUiState, showTitle: Boolean) {
    val ref = remember(record.id) { record.photoRef() }
    Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
        PhotoThumbnail(
            ref = ref,
            contentDescription = null,
            modifier = Modifier
                .size(96.dp)
                .clip(MaterialTheme.shapes.large),
        )
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            if (showTitle) {
                Text(record.title(), style = MaterialTheme.typography.headlineSmall, modifier = Modifier.semantics { heading() })
            }
            Text(
                dateTimeLabel(record.createdAt, state.zone),
                style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            if (record.mode != EditMode.InPlace && record.targetName != null) {
                Text(
                    stringResource(R.string.history_detail_saved_to, record.targetName),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            FlowRow(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                ModeChip(record.mode)
                StateChip(record.state)
            }
        }
    }
}

/** Explains anything but a plain successful edit; nothing for those. */
@Composable
private fun OutcomeCard(record: EditRecord, onOpenRecord: (String) -> Unit) {
    val colors = MaterialTheme.colorScheme
    val error = record.saveError
    val outcome: Outcome = when (record.state) {
        EditState.Pending -> Outcome(
            Icons.Rounded.HourglassTop,
            stringResource(R.string.history_outcome_pending_title),
            stringResource(R.string.history_outcome_pending_body),
            colors.surfaceContainerHigh,
            colors.onSurface,
        )
        EditState.Committed -> if (record.restoresRecordId != null) {
            Outcome(
                Icons.Rounded.Info,
                stringResource(R.string.history_outcome_restore_title),
                stringResource(R.string.history_outcome_restore_body),
                colors.surfaceContainerHigh,
                colors.onSurface,
            )
        } else {
            return
        }
        EditState.Restored -> Outcome(
            Icons.Rounded.Restore,
            stringResource(R.string.history_outcome_restored_title),
            stringResource(R.string.history_outcome_restored_body),
            colors.tertiaryContainer,
            colors.onTertiaryContainer,
        )
        EditState.RolledBack -> Outcome(
            Icons.AutoMirrored.Rounded.Undo,
            stringResource(R.string.history_outcome_not_saved_title),
            stringResource(R.string.history_outcome_rolled_back_body, stringResource((error ?: SaveError.Unknown).messageRes)),
            colors.surfaceContainerHigh,
            colors.onSurface,
        )
        EditState.Failed -> when (error) {
            SaveError.RollbackFailed, SaveError.RecoveryFailed -> Outcome(
                Icons.Rounded.ErrorOutline,
                stringResource(R.string.history_outcome_damaged_title),
                stringResource(error.messageRes),
                colors.errorContainer,
                colors.onErrorContainer,
            )
            else -> Outcome(
                Icons.Rounded.ErrorOutline,
                stringResource(R.string.history_outcome_not_saved_title),
                stringResource(R.string.history_outcome_unchanged_body, stringResource((error ?: SaveError.Unknown).messageRes)),
                colors.errorContainer,
                colors.onErrorContainer,
            )
        }
    }
    Surface(shape = MaterialTheme.shapes.large, color = outcome.container, contentColor = outcome.content) {
        Row(Modifier.fillMaxWidth().padding(16.dp), horizontalArrangement = Arrangement.spacedBy(16.dp)) {
            Icon(outcome.icon, contentDescription = null)
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(outcome.title, style = MaterialTheme.typography.titleSmall)
                Text(outcome.body, style = MaterialTheme.typography.bodyMedium)
                val linked = record.restoresRecordId
                if (linked != null) {
                    TextButton(
                        onClick = { onOpenRecord(linked) },
                        // Line the label up with the text above it.
                        contentPadding = PaddingValues(end = 12.dp, top = 8.dp, bottom = 8.dp),
                        colors = ButtonDefaults.textButtonColors(contentColor = outcome.content),
                    ) {
                        Text(stringResource(R.string.history_action_view_edit))
                        Spacer(Modifier.size(ButtonDefaults.IconSpacing))
                        Icon(Icons.AutoMirrored.Rounded.ArrowForward, contentDescription = null, modifier = Modifier.size(ButtonDefaults.IconSize))
                    }
                }
            }
        }
    }
}

private class Outcome(val icon: ImageVector, val title: String, val body: String, val container: Color, val content: Color)

@Composable
private fun DetailActions(
    record: EditRecord,
    busy: Boolean,
    onRestore: () -> Unit,
    onOpenPhoto: () -> Unit,
    onDeleteBackup: () -> Unit,
) {
    FlowRow(
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        if (record.canRestore) {
            val undo = record.restoresRecordId != null
            Button(onClick = onRestore, enabled = !busy) {
                if (busy) {
                    CircularProgressIndicator(
                        modifier = Modifier.size(ButtonDefaults.IconSize),
                        strokeWidth = 2.dp,
                        color = MaterialTheme.colorScheme.onPrimary,
                    )
                } else {
                    Icon(
                        if (undo) Icons.AutoMirrored.Rounded.Undo else Icons.Rounded.Restore,
                        contentDescription = null,
                        modifier = Modifier.size(ButtonDefaults.IconSize),
                    )
                }
                Spacer(Modifier.size(ButtonDefaults.IconSpacing))
                Text(stringResource(if (undo) R.string.history_action_undo_restore else R.string.history_action_restore))
            }
        }
        OutlinedButton(onClick = onOpenPhoto, enabled = !busy) {
            Icon(Icons.Rounded.Image, contentDescription = null, modifier = Modifier.size(ButtonDefaults.IconSize))
            Spacer(Modifier.size(ButtonDefaults.IconSpacing))
            Text(stringResource(R.string.history_action_open_photo))
        }
        if (record.backupPath != null && record.state != EditState.Pending) {
            TextButton(onClick = onDeleteBackup, enabled = !busy) {
                Icon(Icons.Rounded.DeleteOutline, contentDescription = null, modifier = Modifier.size(ButtonDefaults.IconSize))
                Spacer(Modifier.size(ButtonDefaults.IconSpacing))
                Text(stringResource(R.string.history_action_delete_backup))
            }
        }
    }
}

@Composable
private fun DiffList(diff: List<FieldDiff>) {
    GroupSurface {
        if (diff.isEmpty()) {
            Text(
                stringResource(R.string.history_detail_no_changes),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(16.dp),
            )
        }
        diff.forEachIndexed { index, change ->
            if (index > 0) HorizontalDivider(Modifier.padding(horizontal = 16.dp), color = MaterialTheme.colorScheme.outlineVariant)
            DiffRow(change)
        }
    }
}

@Composable
private fun DiffRow(change: FieldDiff) {
    Column(
        Modifier
            .fillMaxWidth()
            .semantics(mergeDescendants = true) {}
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text(change.label, style = MaterialTheme.typography.titleSmall)
                Text(
                    stringResource(change.group.labelRes),
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            DiffKindChip(change.kind)
        }
        change.before?.let { ValueLine(stringResource(R.string.history_diff_before), it, emphasized = false) }
        change.after?.let { ValueLine(stringResource(R.string.history_diff_after), it, emphasized = true) }
    }
}

@Composable
private fun ValueLine(label: String, value: String, emphasized: Boolean) {
    val labelText: @Composable (Modifier) -> Unit = { modifier ->
        Text(label, style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = modifier)
    }
    val valueText: @Composable (Modifier) -> Unit = { modifier ->
        Text(
            value.ifEmpty { stringResource(R.string.history_diff_empty_value) },
            style = ExifLabTheme.extendedTypography.monoMedium,
            color = if (emphasized) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = modifier,
        )
    }
    if (isLargeFontScale()) {
        // Large text leaves no room for a label column; the label goes above the value.
        Column {
            labelText(Modifier)
            valueText(Modifier)
        }
    } else {
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            labelText(Modifier.widthIn(min = 56.dp))
            valueText(Modifier.weight(1f))
        }
    }
}

@Composable
private fun BackupCard(record: EditRecord, state: HistoryDetailUiState) {
    val context = LocalContext.current
    val size = state.backupBytes?.let { Formatter.formatShortFileSize(context, it) }
    val (icon, title, body) = when {
        record.backupPath != null && size != null -> {
            val days = state.retention.days
            val body = when {
                record.state == EditState.Pending -> stringResource(R.string.history_backup_pending, size)
                record.needsRestore -> stringResource(R.string.history_backup_only_copy, size)
                days == null -> stringResource(R.string.history_backup_kept_forever, size)
                else -> {
                    val until = Instant.ofEpochMilli(record.createdAt).atZone(state.zone).toLocalDate().plusDays(days.toLong())
                    val date = DateTimeFormatter.ofLocalizedDate(FormatStyle.MEDIUM).withLocale(LocalLocale.current.platformLocale).format(until)
                    stringResource(R.string.history_backup_kept_until, size, date)
                }
            }
            Triple(if (record.needsRestore) Icons.Rounded.Shield else Icons.Rounded.Inventory2, stringResource(R.string.history_backup_kept), body)
        }
        record.mode == EditMode.Copy -> Triple(
            Icons.Rounded.ContentCopy,
            stringResource(R.string.history_backup_not_needed),
            stringResource(R.string.history_backup_copy_body),
        )
        record.mode == EditMode.Sidecar && record.originalSize == 0L && record.state == EditState.Committed -> Triple(
            Icons.Rounded.Inventory2,
            stringResource(R.string.history_backup_not_needed),
            stringResource(R.string.history_backup_new_sidecar_body),
        )
        record.state == EditState.RolledBack || (record.state == EditState.Failed && !record.needsRestore) -> Triple(
            Icons.Rounded.Inventory2,
            stringResource(R.string.history_backup_none),
            stringResource(R.string.history_backup_none_body),
        )
        else -> Triple(
            Icons.Rounded.DeleteOutline,
            stringResource(R.string.history_backup_removed),
            stringResource(R.string.history_backup_removed_body),
        )
    }
    GroupSurface {
        Row(
            Modifier
                .fillMaxWidth()
                .semantics(mergeDescendants = true) {}
                .padding(16.dp),
            horizontalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            Icon(icon, contentDescription = null, tint = MaterialTheme.colorScheme.onSurfaceVariant)
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Text(title, style = MaterialTheme.typography.bodyLarge)
                Text(body, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    }
}
