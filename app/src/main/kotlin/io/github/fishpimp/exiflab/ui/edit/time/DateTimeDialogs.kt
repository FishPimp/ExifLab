package io.github.fishpimp.exiflab.ui.edit.time

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.CalendarMonth
import androidx.compose.material.icons.rounded.Schedule
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Checkbox
import androidx.compose.material3.DatePicker
import androidx.compose.material3.DatePickerDialog
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExposedDropdownMenu
import androidx.compose.material3.ExposedDropdownMenuAnchorType
import androidx.compose.material3.ExposedDropdownMenuBox
import androidx.compose.material3.ExposedDropdownMenuDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TimePicker
import androidx.compose.material3.rememberDatePickerState
import androidx.compose.material3.rememberTimePickerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLocale
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import io.github.fishpimp.exiflab.R
import io.github.fishpimp.exiflab.ui.edit.utcText
import io.github.fishpimp.exiflab.ui.format.CaptureTimeFormat
import java.time.Instant
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.ZoneOffset

/**
 * "Set date and time": the date (Material date picker), the time (Material time picker; seconds
 * stay as they were), the UTC offset to record, and whether the modified date follows.
 */
@Composable
fun SetDateTimeDialog(
    initial: LocalDateTime,
    initialOffset: ZoneOffset?,
    onConfirm: (LocalDateTime, ZoneOffset?, Boolean) -> Unit,
    onDismiss: () -> Unit,
) {
    val locale = LocalLocale.current.platformLocale
    var date by rememberSaveable { mutableStateOf(initial.toLocalDate()) }
    var hour by rememberSaveable { mutableStateOf(initial.hour) }
    var minute by rememberSaveable { mutableStateOf(initial.minute) }
    var offset by rememberSaveable { mutableStateOf(initialOffset) }
    var alsoModify by rememberSaveable { mutableStateOf(false) }
    var picking by rememberSaveable { mutableStateOf(Picking.None) }
    val chosen = LocalDateTime.of(date, initial.toLocalTime().withHour(hour).withMinute(minute))

    AlertDialog(
        onDismissRequest = onDismiss,
        icon = { Icon(Icons.Rounded.CalendarMonth, contentDescription = null) },
        title = { Text(stringResource(R.string.edit_time_set_title)) },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(16.dp)) {
                PickerField(
                    label = stringResource(R.string.edit_time_date),
                    value = CaptureTimeFormat.date(chosen, locale),
                    icon = { Icon(Icons.Rounded.CalendarMonth, contentDescription = null) },
                    onClick = { picking = Picking.Date },
                )
                PickerField(
                    label = stringResource(R.string.edit_time_time),
                    value = CaptureTimeFormat.time(chosen, locale),
                    icon = { Icon(Icons.Rounded.Schedule, contentDescription = null) },
                    onClick = { picking = Picking.Time },
                )
                OffsetDropdown(
                    selected = offset,
                    onSelect = { offset = it },
                    keepLabel = stringResource(R.string.edit_time_offset_keep),
                    allowKeep = true,
                )
                Row(
                    Modifier
                        .fillMaxWidth()
                        .heightIn(min = 48.dp)
                        .toggleable(value = alsoModify, role = Role.Checkbox, onValueChange = { alsoModify = it }),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    Checkbox(checked = alsoModify, onCheckedChange = null)
                    Text(stringResource(R.string.edit_time_also_modify), style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f))
                }
            }
        },
        confirmButton = { TextButton(onClick = { onConfirm(chosen, offset, alsoModify) }) { Text(stringResource(R.string.edit_apply)) } },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.action_cancel)) } },
    )

    when (picking) {
        Picking.Date -> DateDialog(date, onPick = { date = it; picking = Picking.None }, onDismiss = { picking = Picking.None })
        Picking.Time -> TimeDialog(hour, minute, onPick = { h, m -> hour = h; minute = m; picking = Picking.None }, onDismiss = { picking = Picking.None })
        Picking.None -> Unit
    }
}

private enum class Picking { None, Date, Time }

/** A read-only field that opens a picker, so it reads as "Date, Sunday 14 June 2026, button". */
@Composable
private fun PickerField(label: String, value: String, icon: @Composable () -> Unit, onClick: () -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text(label, style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
        OutlinedButton(onClick = onClick, modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)) {
            icon()
            Text(value, modifier = Modifier.padding(start = 8.dp).weight(1f))
        }
    }
}

@Composable
private fun DateDialog(initial: LocalDate, onPick: (LocalDate) -> Unit, onDismiss: () -> Unit) {
    val state = rememberDatePickerState(initialSelectedDateMillis = initial.atStartOfDay().toInstant(ZoneOffset.UTC).toEpochMilli())
    DatePickerDialog(
        onDismissRequest = onDismiss,
        confirmButton = {
            TextButton(
                onClick = {
                    val millis = state.selectedDateMillis
                    if (millis != null) onPick(Instant.ofEpochMilli(millis).atZone(ZoneOffset.UTC).toLocalDate()) else onDismiss()
                },
            ) { Text(stringResource(R.string.edit_ok)) }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.action_cancel)) } },
    ) {
        DatePicker(state = state)
    }
}

@Composable
private fun TimeDialog(hour: Int, minute: Int, onPick: (Int, Int) -> Unit, onDismiss: () -> Unit) {
    val is24Hour = android.text.format.DateFormat.is24HourFormat(LocalContext.current)
    val state = rememberTimePickerState(initialHour = hour, initialMinute = minute, is24Hour = is24Hour)
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.edit_time_time)) },
        text = { TimePicker(state = state) },
        confirmButton = { TextButton(onClick = { onPick(state.hour, state.minute) }) { Text(stringResource(R.string.edit_ok)) } },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.action_cancel)) } },
    )
}

/** "Set time zone only": records a UTC offset, leaving the local times as they are. */
@Composable
fun SetOffsetDialog(initial: ZoneOffset?, onConfirm: (ZoneOffset) -> Unit, onDismiss: () -> Unit) {
    var offset by rememberSaveable { mutableStateOf(initial ?: ZoneOffset.UTC) }
    AlertDialog(
        onDismissRequest = onDismiss,
        icon = { Icon(Icons.Rounded.Schedule, contentDescription = null) },
        title = { Text(stringResource(R.string.edit_time_offset_title)) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(16.dp)) {
                Text(stringResource(R.string.edit_time_offset_body), style = MaterialTheme.typography.bodyMedium)
                OffsetDropdown(selected = offset, onSelect = { if (it != null) offset = it }, keepLabel = "", allowKeep = false)
            }
        },
        confirmButton = { TextButton(onClick = { onConfirm(offset) }) { Text(stringResource(R.string.edit_apply)) } },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.action_cancel)) } },
    )
}

/** An exposed dropdown of UTC offsets; with [allowKeep] the first entry (null) keeps what the file has. */
@Composable
fun OffsetDropdown(selected: ZoneOffset?, onSelect: (ZoneOffset?) -> Unit, keepLabel: String, allowKeep: Boolean) {
    var expanded by rememberSaveable { mutableStateOf(false) }
    val label = selected?.let { utcText(it) } ?: keepLabel
    ExposedDropdownMenuBox(expanded = expanded, onExpandedChange = { expanded = it }) {
        OutlinedTextField(
            value = label,
            onValueChange = {},
            readOnly = true,
            singleLine = true,
            label = { Text(stringResource(R.string.edit_time_offset_label)) },
            trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded = expanded) },
            modifier = Modifier.fillMaxWidth().menuAnchor(ExposedDropdownMenuAnchorType.PrimaryNotEditable),
        )
        ExposedDropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
            if (allowKeep) {
                DropdownMenuItem(
                    text = { Text(keepLabel) },
                    onClick = {
                        onSelect(null)
                        expanded = false
                    },
                    contentPadding = ExposedDropdownMenuDefaults.ItemContentPadding,
                )
            }
            SelectableOffsets.forEach { offset ->
                DropdownMenuItem(
                    text = { Text(utcText(offset)) },
                    onClick = {
                        onSelect(offset)
                        expanded = false
                    },
                    contentPadding = ExposedDropdownMenuDefaults.ItemContentPadding,
                )
            }
        }
    }
}

/** Date, then time: the Material pickers one after the other, for date-time fields. Seconds are kept. */
@Composable
fun PickDateTimeDialogs(initial: LocalDateTime, onPick: (LocalDateTime) -> Unit, onDismiss: () -> Unit) {
    var date by rememberSaveable { mutableStateOf<LocalDate?>(null) }
    val chosenDate = date
    if (chosenDate == null) {
        DateDialog(initial.toLocalDate(), onPick = { date = it }, onDismiss = onDismiss)
    } else {
        TimeDialog(
            hour = initial.hour,
            minute = initial.minute,
            onPick = { h, m -> onPick(LocalDateTime.of(chosenDate, initial.toLocalTime().withHour(h).withMinute(m))) },
            onDismiss = onDismiss,
        )
    }
}
