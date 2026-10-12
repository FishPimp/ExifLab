package io.github.fishpimp.exiflab.ui.export

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.DataObject
import androidx.compose.material.icons.rounded.FileDownload
import androidx.compose.material.icons.rounded.Map
import androidx.compose.material.icons.rounded.PictureAsPdf
import androidx.compose.material.icons.rounded.Code
import androidx.compose.material.icons.rounded.TableChart
import androidx.compose.material.icons.rounded.VisibilityOff
import androidx.compose.material3.Button
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialShapes
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import io.github.fishpimp.exiflab.R
import io.github.fishpimp.exiflab.data.export.ExportFormat
import io.github.fishpimp.exiflab.data.export.ExportSettings
import io.github.fishpimp.exiflab.designsystem.component.Choice
import io.github.fishpimp.exiflab.designsystem.component.ChoiceRow
import io.github.fishpimp.exiflab.designsystem.component.GroupSurface
import io.github.fishpimp.exiflab.designsystem.component.ShapeIcon
import io.github.fishpimp.exiflab.designsystem.theme.ExifLabTheme

/** The export options as a modal bottom sheet. */
@Composable
fun ExportSheet(
    request: ExportRequest,
    settings: ExportSettings,
    fileName: String,
    mapOffline: Boolean,
    onSettingsChange: (ExportSettings) -> Unit,
    onExport: () -> Unit,
    onDismiss: () -> Unit,
) {
    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)) {
        ExportSheetContent(
            request = request,
            settings = settings,
            fileName = fileName,
            mapOffline = mapOffline,
            onSettingsChange = onSettingsChange,
            onExport = onExport,
            onCancel = onDismiss,
            modifier = Modifier.verticalScroll(rememberScrollState()),
        )
    }
}

/**
 * The body of the export sheet: format, options that apply to it, the suggested file name and the
 * buttons. Stateless, so screenshot tests can render it without a sheet.
 */
@Composable
fun ExportSheetContent(
    request: ExportRequest,
    settings: ExportSettings,
    fileName: String,
    mapOffline: Boolean,
    onSettingsChange: (ExportSettings) -> Unit,
    onExport: () -> Unit,
    onCancel: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier
            .fillMaxWidth()
            .padding(start = 24.dp, end = 24.dp, bottom = 24.dp),
        verticalArrangement = Arrangement.spacedBy(20.dp),
    ) {
        Row(horizontalArrangement = Arrangement.spacedBy(16.dp), verticalAlignment = Alignment.CenterVertically) {
            ShapeIcon(icon = Icons.Rounded.FileDownload, shape = MaterialShapes.Cookie7Sided, size = 48.dp)
            Column(Modifier.weight(1f)) {
                Text(
                    stringResource(R.string.export_title),
                    style = MaterialTheme.typography.headlineSmall,
                    modifier = Modifier.semantics { heading() },
                )
                Text(
                    request.photoName ?: pluralStringResource(R.plurals.photo_count, request.photos.size, request.photos.size),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }

        Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text(
                stringResource(R.string.export_format_label),
                style = MaterialTheme.typography.titleSmall,
                color = MaterialTheme.colorScheme.primary,
                modifier = Modifier.semantics { heading() },
            )
            ChoiceRow(
                choices = listOf(
                    Choice(ExportFormat.Pdf, stringResource(R.string.export_format_pdf), Icons.Rounded.PictureAsPdf),
                    Choice(ExportFormat.Csv, stringResource(R.string.export_format_csv), Icons.Rounded.TableChart),
                    Choice(ExportFormat.Json, stringResource(R.string.export_format_json), Icons.Rounded.DataObject),
                ),
                selected = settings.format,
                onSelect = { onSettingsChange(settings.copy(format = it)) },
            )
            Text(
                stringResource(settings.format.descriptionRes),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }

        Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text(
                stringResource(R.string.export_options_title),
                style = MaterialTheme.typography.titleSmall,
                color = MaterialTheme.colorScheme.primary,
                modifier = Modifier.semantics { heading() },
            )
            GroupSurface {
                OptionRow(
                    icon = Icons.Rounded.Code,
                    title = stringResource(R.string.export_option_raw),
                    body = stringResource(R.string.export_option_raw_body),
                    checked = settings.includeRawValues,
                    onCheckedChange = { onSettingsChange(settings.copy(includeRawValues = it)) },
                )
                HorizontalDivider(color = MaterialTheme.colorScheme.surface)
                OptionRow(
                    icon = Icons.Rounded.VisibilityOff,
                    title = stringResource(R.string.export_option_redact),
                    body = stringResource(R.string.export_option_redact_body),
                    checked = settings.redactSensitive,
                    onCheckedChange = { onSettingsChange(settings.copy(redactSensitive = it)) },
                )
                if (settings.format == ExportFormat.Pdf) {
                    HorizontalDivider(color = MaterialTheme.colorScheme.surface)
                    OptionRow(
                        icon = Icons.Rounded.Map,
                        title = stringResource(R.string.export_option_map),
                        body = stringResource(
                            when {
                                !settings.mapApplies -> R.string.export_option_map_redacted
                                mapOffline -> R.string.export_option_map_offline
                                else -> R.string.export_option_map_body
                            },
                        ),
                        checked = settings.includeMap && settings.mapApplies,
                        enabled = settings.mapApplies,
                        onCheckedChange = { onSettingsChange(settings.copy(includeMap = it)) },
                    )
                }
                if (settings.format == ExportFormat.Csv) {
                    HorizontalDivider(color = MaterialTheme.colorScheme.surface)
                    OptionRow(
                        icon = Icons.Rounded.TableChart,
                        title = stringResource(R.string.export_option_spreadsheet),
                        body = stringResource(R.string.export_option_spreadsheet_body),
                        checked = settings.spreadsheetFriendly,
                        onCheckedChange = { onSettingsChange(settings.copy(spreadsheetFriendly = it)) },
                    )
                }
            }
        }

        Column(Modifier.semantics(mergeDescendants = true) {}) {
            Text(
                stringResource(R.string.export_file_name),
                style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Text(fileName, style = ExifLabTheme.extendedTypography.monoMedium)
            Text(
                stringResource(R.string.export_file_name_hint),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }

        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.End)) {
            TextButton(onClick = onCancel, modifier = Modifier.heightIn(min = 48.dp)) { Text(stringResource(R.string.action_cancel)) }
            Button(onClick = onExport, modifier = Modifier.heightIn(min = 48.dp)) {
                Icon(Icons.Rounded.FileDownload, contentDescription = null, modifier = Modifier.size(18.dp))
                Spacer(Modifier.size(8.dp))
                Text(stringResource(R.string.export_confirm))
            }
        }
    }
}

/** A switch row; the whole row toggles and reads as one switch. */
@Composable
private fun OptionRow(
    icon: ImageVector,
    title: String,
    body: String,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
    enabled: Boolean = true,
) {
    Row(
        Modifier
            .fillMaxWidth()
            .toggleable(value = checked, enabled = enabled, role = Role.Switch, onValueChange = onCheckedChange)
            .heightIn(min = 56.dp)
            .padding(horizontal = 16.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        val contentColor = if (enabled) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.onSurface.copy(alpha = DISABLED_ALPHA)
        Icon(icon, contentDescription = null, tint = if (enabled) MaterialTheme.colorScheme.onSurfaceVariant else contentColor)
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.bodyLarge, color = contentColor)
            Text(
                body,
                style = MaterialTheme.typography.bodyMedium,
                color = if (enabled) MaterialTheme.colorScheme.onSurfaceVariant else contentColor,
            )
        }
        Switch(checked = checked, onCheckedChange = null, enabled = enabled)
    }
}

private val ExportFormat.descriptionRes: Int
    get() = when (this) {
        ExportFormat.Csv -> R.string.export_format_csv_body
        ExportFormat.Json -> R.string.export_format_json_body
        ExportFormat.Pdf -> R.string.export_format_pdf_body
    }

private const val DISABLED_ALPHA = 0.38f
