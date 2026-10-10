package io.github.fishpimp.exiflab.ui.edit

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.selection.toggleable
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowForward
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.Copyright
import androidx.compose.material.icons.rounded.EditCalendar
import androidx.compose.material.icons.rounded.EditLocationAlt
import androidx.compose.material.icons.rounded.EditNote
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material.icons.rounded.LocationOff
import androidx.compose.material.icons.rounded.Person
import androidx.compose.material.icons.rounded.PhotoLibrary
import androidx.compose.material.icons.rounded.Place
import androidx.compose.material.icons.rounded.Public
import androidx.compose.material.icons.rounded.Schedule
import androidx.compose.material.icons.rounded.TravelExplore
import androidx.compose.material.icons.rounded.Update
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Checkbox
import androidx.compose.material3.Icon
import androidx.compose.material3.InputChip
import androidx.compose.material3.InputChipDefaults
import androidx.compose.material3.LoadingIndicator
import androidx.compose.material3.MaterialShapes
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
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
import androidx.compose.ui.platform.LocalLocale
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.github.fishpimp.exiflab.R
import io.github.fishpimp.exiflab.designsystem.theme.ExifLabTheme
import io.github.fishpimp.exiflab.metadata.edit.FieldDiff
import io.github.fishpimp.exiflab.metadata.model.LocationStatus
import io.github.fishpimp.exiflab.metadata.model.MetadataReport
import io.github.fishpimp.exiflab.ui.components.isLargeFontScale
import io.github.fishpimp.exiflab.ui.format.CaptureTimeFormat
import io.github.fishpimp.exiflab.ui.format.LocationFormat
import io.github.fishpimp.exiflab.ui.map.LatLng
import io.github.fishpimp.exiflab.ui.map.LocationMap
import io.github.fishpimp.exiflab.ui.map.formatted

/** The queued edits as removable chips, with "Discard all". */
@Composable
fun PendingEditsCard(edits: List<PendingEdit>, onRemove: (Long) -> Unit, onDiscardAll: () -> Unit, modifier: Modifier = Modifier) {
    Surface(
        shape = MaterialTheme.shapes.extraLarge,
        color = MaterialTheme.colorScheme.secondaryContainer,
        contentColor = MaterialTheme.colorScheme.onSecondaryContainer,
        modifier = modifier.fillMaxWidth(),
    ) {
        Column(Modifier.padding(start = 20.dp, end = 12.dp, top = 12.dp, bottom = 16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Rounded.EditNote, contentDescription = null)
                Text(
                    pluralStringResource(R.plurals.edit_pending_title, edits.size, edits.size),
                    style = MaterialTheme.typography.titleMedium,
                    modifier = Modifier
                        .weight(1f)
                        .padding(start = 12.dp)
                        .semantics { liveRegion = LiveRegionMode.Polite },
                )
                TextButton(onClick = onDiscardAll, modifier = Modifier.heightIn(min = 48.dp)) {
                    Text(stringResource(R.string.edit_discard_all), color = MaterialTheme.colorScheme.onSecondaryContainer)
                }
            }
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                edits.forEach { edit ->
                    val summary = editSummary(edit)
                    val removeLabel = stringResource(R.string.edit_pending_remove, summary)
                    InputChip(
                        selected = true,
                        onClick = { onRemove(edit.id) },
                        label = { Text(summary, maxLines = 3) },
                        trailingIcon = {
                            Icon(Icons.Rounded.Close, contentDescription = removeLabel, modifier = Modifier.size(InputChipDefaults.AvatarSize))
                        },
                        colors = InputChipDefaults.inputChipColors(
                            selectedContainerColor = MaterialTheme.colorScheme.surfaceContainerLowest,
                            selectedLabelColor = MaterialTheme.colorScheme.onSurface,
                            selectedTrailingIconColor = MaterialTheme.colorScheme.onSurfaceVariant,
                        ),
                        modifier = Modifier.heightIn(min = 48.dp),
                    )
                }
            }
        }
    }
}

/** Which date and time tool is open. */
enum class TimeTool { SetDateTime, Shift, Offset }

/**
 * "Date and time": the capture time as recorded and, when edits are queued, as it will be; then
 * the four tools.
 */
@Composable
fun DateTimeCard(
    report: MetadataReport,
    edits: List<PendingEdit>,
    onOpenTool: (TimeTool) -> Unit,
    onFixTimeZone: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val before = CaptureTimeState(report.summary.capturedAt, report.summary.utcOffset)
    val after = report.captureTimeAfter(edits)
    val changed = after != before
    EditorCard(
        icon = Icons.Rounded.Schedule,
        shape = MaterialShapes.Cookie9Sided,
        title = stringResource(R.string.edit_time_title),
        subtitle = stringResource(R.string.edit_time_subtitle),
        modifier = modifier,
    ) {
        Column(Modifier.padding(horizontal = 20.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            if (before.local == null && !changed) {
                Text(stringResource(R.string.edit_time_none), style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
            } else if (!changed) {
                TimeValue(before)
            } else {
                BeforeAfter(
                    before = { m -> DiffValueBox(stringResource(R.string.edit_before), null, m) { TimeValue(before, compact = true) } },
                    after = { m -> DiffValueBox(stringResource(R.string.edit_after), FieldDiff.Kind.Changed, m) { TimeValue(after, compact = true) } },
                )
            }
        }
        Column {
            EditorActionRow(Icons.Rounded.EditCalendar, stringResource(R.string.edit_time_set), stringResource(R.string.edit_time_set_body), { onOpenTool(TimeTool.SetDateTime) })
            EditorActionRow(
                Icons.Rounded.Update,
                stringResource(R.string.edit_time_shift),
                stringResource(R.string.edit_time_shift_body),
                { onOpenTool(TimeTool.Shift) },
                enabled = before.local != null || after.local != null,
            )
            EditorActionRow(
                Icons.Rounded.TravelExplore,
                stringResource(R.string.edit_time_fix_zone),
                stringResource(R.string.edit_time_fix_zone_body),
                onFixTimeZone,
                enabled = before.local != null || after.local != null,
            )
            EditorActionRow(Icons.Rounded.Public, stringResource(R.string.edit_time_offset_only), stringResource(R.string.edit_time_offset_only_body), { onOpenTool(TimeTool.Offset) })
        }
    }
}

@Composable
private fun TimeValue(state: CaptureTimeState, compact: Boolean = false) {
    val locale = LocalLocale.current.platformLocale
    val local = state.local
    Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
        if (local == null) {
            Text(stringResource(R.string.edit_time_none), style = MaterialTheme.typography.bodyLarge)
            return@Column
        }
        val numeral = ExifLabTheme.extendedTypography.numeral
        Text(
            CaptureTimeFormat.time(local, locale),
            // Side by side, the time has half the width; a smaller numeral keeps it on one line.
            style = if (compact) numeral.copy(fontSize = 20.sp, lineHeight = 26.sp) else numeral,
        )
        Text(CaptureTimeFormat.date(local, locale), style = MaterialTheme.typography.bodyMedium)
        Text(
            state.offset?.let { utcText(it) } ?: stringResource(R.string.edit_time_offset_unknown),
            style = ExifLabTheme.extendedTypography.monoSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

/** Two blocks side by side with an arrow, stacked at large text sizes. */
@Composable
fun BeforeAfter(before: @Composable (Modifier) -> Unit, after: @Composable (Modifier) -> Unit) {
    if (isLargeFontScale()) {
        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            before(Modifier.fillMaxWidth())
            after(Modifier.fillMaxWidth())
        }
    } else {
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
            before(Modifier.weight(1f))
            Icon(Icons.AutoMirrored.Rounded.ArrowForward, contentDescription = null, tint = MaterialTheme.colorScheme.onSurfaceVariant)
            after(Modifier.weight(1f))
        }
    }
}

/** "Location": the map with the current or queued position, and the location tools. */
@Composable
fun LocationCard(
    report: MetadataReport,
    edits: List<PendingEdit>,
    readingOtherPhoto: Boolean,
    onChangeLocation: () -> Unit,
    onCopyFromPhoto: () -> Unit,
    onRemoveLocation: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val state = report.locationAfter(edits)
    val location = state.location
    EditorCard(
        icon = Icons.Rounded.Place,
        shape = MaterialShapes.Clover4Leaf,
        title = stringResource(R.string.edit_location_title),
        subtitle = stringResource(
            when {
                state.removed -> R.string.edit_location_will_remove
                state.changed -> R.string.edit_location_will_change
                location != null -> R.string.edit_location_current
                report.locationStatus == LocationStatus.Redacted -> R.string.edit_location_redacted
                else -> R.string.edit_location_none
            },
        ),
        iconContainer = ExifLabTheme.extendedColors.sensitiveContainer,
        iconContent = ExifLabTheme.extendedColors.onSensitiveContainer,
        modifier = modifier,
    ) {
        if (location != null) {
            val latLng = LatLng(location.latitude, location.longitude)
            Column(Modifier.padding(horizontal = 20.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                // Taller at large text sizes, so the coordinates on the drawn map still fit.
                LocationMap(
                    marker = latLng,
                    onClick = onChangeLocation,
                    modifier = Modifier.fillMaxWidth().height(if (isLargeFontScale()) 240.dp else 180.dp),
                )
                Text(latLng.formatted(), style = ExifLabTheme.extendedTypography.monoMedium)
                Text(
                    LocationFormat.degreesMinutesSeconds(location.latitude, location.longitude),
                    style = ExifLabTheme.extendedTypography.monoSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                location.altitudeMeters?.let { meters ->
                    Text(
                        stringResource(
                            if (meters < 0) R.string.edit_location_altitude_below else R.string.edit_location_altitude_above,
                            LocationFormat.altitudeMeters(meters),
                        ),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }
        Column {
            EditorActionRow(
                Icons.Rounded.EditLocationAlt,
                stringResource(if (location != null) R.string.edit_location_change else R.string.edit_location_add),
                stringResource(R.string.edit_location_change_body),
                onChangeLocation,
            )
            Row(verticalAlignment = Alignment.CenterVertically) {
                EditorActionRow(
                    Icons.Rounded.PhotoLibrary,
                    stringResource(R.string.edit_location_copy),
                    stringResource(R.string.edit_location_copy_body),
                    onCopyFromPhoto,
                    enabled = !readingOtherPhoto,
                    modifier = Modifier.weight(1f),
                )
                if (readingOtherPhoto) {
                    val reading = stringResource(R.string.edit_location_copy_reading)
                    LoadingIndicator(Modifier.padding(end = 16.dp).size(32.dp).semantics { contentDescription = reading })
                }
            }
            if (location != null || (!state.removed && report.locationStatus != LocationStatus.Absent)) {
                EditorActionRow(
                    Icons.Rounded.LocationOff,
                    stringResource(R.string.edit_location_remove),
                    stringResource(R.string.edit_location_remove_body),
                    onRemoveLocation,
                    tint = MaterialTheme.colorScheme.error,
                )
            }
        }
    }
}

/** "Remove location", with the choice to remove place names (city, country) as well. */
@Composable
fun RemoveLocationDialog(onConfirm: (Boolean) -> Unit, onDismiss: () -> Unit) {
    var includePlaces by rememberSaveable { mutableStateOf(true) }
    AlertDialog(
        onDismissRequest = onDismiss,
        icon = { Icon(Icons.Rounded.LocationOff, contentDescription = null) },
        title = { Text(stringResource(R.string.edit_location_remove_title)) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text(stringResource(R.string.edit_location_remove_dialog_body), style = MaterialTheme.typography.bodyMedium)
                Row(
                    Modifier
                        .fillMaxWidth()
                        .heightIn(min = 48.dp)
                        .toggleable(value = includePlaces, role = Role.Checkbox, onValueChange = { includePlaces = it }),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    Checkbox(checked = includePlaces, onCheckedChange = null)
                    Text(stringResource(R.string.edit_location_remove_places), style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f))
                }
            }
        },
        confirmButton = {
            TextButton(onClick = { onConfirm(includePlaces) }) { Text(stringResource(R.string.edit_location_remove_confirm)) }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.action_cancel)) } },
    )
}

/** "Author and copyright": two fields and Apply. Blank text clears that field. */
@Composable
fun AuthorCard(report: MetadataReport, edits: List<PendingEdit>, onApply: (String, String) -> Unit, modifier: Modifier = Modifier) {
    val effective = report.authorshipAfter(edits)
    var artist by rememberSaveable(effective) { mutableStateOf(effective.artist.orEmpty()) }
    var copyright by rememberSaveable(effective) { mutableStateOf(effective.copyright.orEmpty()) }
    val changed = artist.trim() != effective.artist.orEmpty() || copyright.trim() != effective.copyright.orEmpty()
    EditorCard(
        icon = Icons.Rounded.Person,
        shape = MaterialShapes.Sunny,
        title = stringResource(R.string.edit_author_title),
        subtitle = stringResource(R.string.edit_author_subtitle),
        iconContainer = MaterialTheme.colorScheme.tertiaryContainer,
        iconContent = MaterialTheme.colorScheme.onTertiaryContainer,
        modifier = modifier,
    ) {
        Column(Modifier.padding(horizontal = 20.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            OutlinedTextField(
                value = artist,
                onValueChange = { artist = it },
                label = { Text(stringResource(R.string.edit_author_artist)) },
                leadingIcon = { Icon(Icons.Rounded.Person, contentDescription = null) },
                singleLine = true,
                keyboardOptions = androidx.compose.foundation.text.KeyboardOptions(capitalization = KeyboardCapitalization.Words, imeAction = ImeAction.Next),
                modifier = Modifier.fillMaxWidth(),
            )
            OutlinedTextField(
                value = copyright,
                onValueChange = { copyright = it },
                label = { Text(stringResource(R.string.edit_author_copyright)) },
                leadingIcon = { Icon(Icons.Rounded.Copyright, contentDescription = null) },
                placeholder = { Text(stringResource(R.string.edit_author_copyright_hint)) },
                singleLine = true,
                keyboardOptions = androidx.compose.foundation.text.KeyboardOptions(imeAction = ImeAction.Done),
                modifier = Modifier.fillMaxWidth(),
            )
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                Button(onClick = { onApply(artist, copyright) }, enabled = changed, modifier = Modifier.heightIn(min = 48.dp)) {
                    Icon(Icons.Rounded.Check, contentDescription = null, modifier = Modifier.size(ButtonDefaults.IconSize))
                    Text(stringResource(R.string.edit_apply), modifier = Modifier.padding(start = ButtonDefaults.IconSpacing))
                }
            }
        }
    }
}
