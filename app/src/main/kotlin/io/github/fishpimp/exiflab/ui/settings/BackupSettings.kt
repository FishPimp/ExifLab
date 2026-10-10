package io.github.fishpimp.exiflab.ui.settings

import android.text.format.Formatter
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.DeleteSweep
import androidx.compose.material.icons.rounded.Storage
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.unit.dp
import io.github.fishpimp.exiflab.R
import io.github.fishpimp.exiflab.data.settings.AppSettings
import io.github.fishpimp.exiflab.data.settings.BackupRetention
import io.github.fishpimp.exiflab.data.settings.DEFAULT_BACKUP_SIZE_CAP_BYTES
import io.github.fishpimp.exiflab.data.settings.SidecarNaming
import io.github.fishpimp.exiflab.designsystem.component.Choice
import io.github.fishpimp.exiflab.designsystem.component.ChoiceRow
import io.github.fishpimp.exiflab.designsystem.component.GroupSurface
import io.github.fishpimp.exiflab.designsystem.component.ListRow
import io.github.fishpimp.exiflab.designsystem.component.SectionHeader

/** How much space backups take, for the Backups section. [usedBytes] is null until measured. */
data class BackupUsage(val usedBytes: Long?, val sizeCapBytes: Long = DEFAULT_BACKUP_SIZE_CAP_BYTES)

/** The "Editing" (sidecar naming) and "Backups" sections of Settings. */
internal fun LazyListScope.editingAndBackupSettings(
    settings: AppSettings,
    backups: BackupUsage,
    onBackupRetention: (BackupRetention) -> Unit,
    onSidecarNaming: (SidecarNaming) -> Unit,
    onDeleteAllBackups: () -> Unit,
) {
    item { SectionHeader(stringResource(R.string.settings_section_editing)) }
    item {
        GroupSurface {
            LabeledControl(
                title = stringResource(R.string.settings_sidecar_naming),
                supporting = stringResource(R.string.settings_sidecar_naming_body),
            ) {
                ChoiceRow(
                    choices = listOf(
                        Choice(SidecarNaming.BaseName, stringResource(R.string.settings_sidecar_example_base)),
                        Choice(SidecarNaming.FullName, stringResource(R.string.settings_sidecar_example_full)),
                    ),
                    selected = settings.sidecarNaming,
                    onSelect = onSidecarNaming,
                )
            }
        }
    }
    item { SectionHeader(stringResource(R.string.settings_section_backups)) }
    item {
        BackupSettingsGroup(
            retention = settings.backupRetention,
            backups = backups,
            onBackupRetention = onBackupRetention,
            onDeleteAllBackups = onDeleteAllBackups,
        )
    }
}

/** Storage used, the retention choice and "Delete all backups" with its confirmation. */
@Composable
fun BackupSettingsGroup(
    retention: BackupRetention,
    backups: BackupUsage,
    onBackupRetention: (BackupRetention) -> Unit,
    onDeleteAllBackups: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    val used = backups.usedBytes
    val usedText = used?.let { Formatter.formatShortFileSize(context, it) }
    val capText = Formatter.formatShortFileSize(context, backups.sizeCapBytes)
    var confirmDelete by rememberSaveable { mutableStateOf(false) }

    GroupSurface(modifier) {
        ListRow(
            title = stringResource(R.string.settings_backup_storage),
            supporting = if (usedText != null) {
                stringResource(R.string.settings_backup_storage_value, usedText, capText)
            } else {
                stringResource(R.string.settings_backup_storage_measuring)
            },
            icon = Icons.Rounded.Storage,
        )
        if (used != null) {
            LinearProgressIndicator(
                progress = { (used.toFloat() / backups.sizeCapBytes).coerceIn(0f, 1f) },
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(start = 56.dp, end = 16.dp, bottom = 12.dp)
                    // The row above already says how much is used.
                    .clearAndSetSemantics { },
            )
        }
        LabeledControl(
            title = stringResource(R.string.settings_backup_retention),
            supporting = stringResource(R.string.settings_backup_retention_body, capText),
        ) {
            ChoiceRow(
                choices = BackupRetention.entries.map { option ->
                    val days = option.days
                    Choice(
                        option,
                        if (days != null) {
                            pluralStringResource(R.plurals.settings_backup_days, days, days)
                        } else {
                            stringResource(R.string.settings_backup_forever)
                        },
                    )
                },
                selected = retention,
                onSelect = onBackupRetention,
            )
        }
        val hasBackups = used != null && used > 0
        ListRow(
            title = stringResource(R.string.settings_backup_delete_all),
            supporting = if (hasBackups && usedText != null) {
                stringResource(R.string.settings_backup_delete_all_body, usedText)
            } else {
                stringResource(R.string.settings_backup_delete_all_none)
            },
            icon = Icons.Rounded.DeleteSweep,
            onClick = if (hasBackups) ({ confirmDelete = true }) else null,
        )
    }

    if (confirmDelete) {
        AlertDialog(
            onDismissRequest = { confirmDelete = false },
            icon = { Icon(Icons.Rounded.DeleteSweep, contentDescription = null) },
            title = { Text(stringResource(R.string.settings_backup_delete_all_title)) },
            text = {
                Column {
                    Text(stringResource(R.string.settings_backup_delete_all_confirm, usedText.orEmpty()))
                }
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        confirmDelete = false
                        onDeleteAllBackups()
                    },
                    colors = ButtonDefaults.textButtonColors(contentColor = MaterialTheme.colorScheme.error),
                ) { Text(stringResource(R.string.settings_backup_delete_all_action)) }
            },
            dismissButton = { TextButton(onClick = { confirmDelete = false }) { Text(stringResource(R.string.action_cancel)) } },
        )
    }
}
