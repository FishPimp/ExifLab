package io.github.fishpimp.exiflab.ui.edit.time

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowForward
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.FastForward
import androidx.compose.material.icons.rounded.FastRewind
import androidx.compose.material.icons.rounded.Remove
import androidx.compose.material.icons.rounded.Update
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.FilledTonalIconButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.MaterialShapes
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
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
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import io.github.fishpimp.exiflab.R
import io.github.fishpimp.exiflab.designsystem.component.Choice
import io.github.fishpimp.exiflab.designsystem.component.ChoiceRow
import io.github.fishpimp.exiflab.designsystem.component.ShapeIcon
import io.github.fishpimp.exiflab.designsystem.theme.ExifLabTheme
import io.github.fishpimp.exiflab.ui.components.isLargeFontScale
import io.github.fishpimp.exiflab.metadata.edit.FieldDiff
import io.github.fishpimp.exiflab.ui.edit.DiffValueBox
import io.github.fishpimp.exiflab.ui.edit.durationText
import io.github.fishpimp.exiflab.ui.edit.utcText
import io.github.fishpimp.exiflab.ui.format.CaptureTimeFormat
import java.time.Duration
import java.time.LocalDateTime
import java.time.ZoneOffset

/** "Shift time" in a bottom sheet; [onApply] gets the signed amount. */
@Composable
fun ShiftTimeSheet(
    capturedAt: LocalDateTime?,
    offset: ZoneOffset?,
    onApply: (Duration) -> Unit,
    onDismiss: () -> Unit,
) {
    var days by rememberSaveable { mutableStateOf(0) }
    var hours by rememberSaveable { mutableStateOf(0) }
    var minutes by rememberSaveable { mutableStateOf(0) }
    var seconds by rememberSaveable { mutableStateOf(0) }
    var later by rememberSaveable { mutableStateOf(true) }
    val shift = TimeShift(days, hours, minutes, seconds, later)
    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)) {
        ShiftTimeContent(
            shift = shift,
            capturedAt = capturedAt,
            offset = offset,
            onShiftChange = {
                days = it.days
                hours = it.hours
                minutes = it.minutes
                seconds = it.seconds
                later = it.later
            },
            onApply = { onApply(shift.duration) },
            onCancel = onDismiss,
            modifier = Modifier.verticalScroll(rememberScrollState()),
        )
    }
}

/**
 * The body of the shift sheet: direction, four steppers and a live before/after preview.
 * Stateless, so screenshot tests can render it without a sheet.
 */
@Composable
fun ShiftTimeContent(
    shift: TimeShift,
    capturedAt: LocalDateTime?,
    offset: ZoneOffset?,
    onShiftChange: (TimeShift) -> Unit,
    onApply: () -> Unit,
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
            ShapeIcon(icon = Icons.Rounded.Update, shape = MaterialShapes.Cookie7Sided, size = 48.dp)
            Column(Modifier.weight(1f)) {
                Text(stringResource(R.string.edit_shift_title), style = MaterialTheme.typography.headlineSmall, modifier = Modifier.semantics { heading() })
                Text(
                    stringResource(R.string.edit_shift_body),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        ChoiceRow(
            choices = listOf(
                Choice(true, stringResource(R.string.edit_shift_later), Icons.Rounded.FastForward),
                Choice(false, stringResource(R.string.edit_shift_earlier), Icons.Rounded.FastRewind),
            ),
            selected = shift.later,
            onSelect = { onShiftChange(shift.copy(later = it)) },
        )
        Surface(shape = MaterialTheme.shapes.large, color = MaterialTheme.colorScheme.surfaceContainerHigh) {
            Column(Modifier.padding(vertical = 8.dp)) {
                ShiftUnit.entries.forEach { unit ->
                    Stepper(unit = unit, value = shift.value(unit), onValueChange = { onShiftChange(shift.with(unit, it)) })
                }
            }
        }
        ShiftPreview(shift, capturedAt, offset)
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.End)) {
            TextButton(onClick = onCancel, modifier = Modifier.heightIn(min = 48.dp)) { Text(stringResource(R.string.action_cancel)) }
            Button(onClick = onApply, enabled = !shift.isZero, modifier = Modifier.heightIn(min = 48.dp)) {
                Text(stringResource(R.string.edit_shift_apply))
            }
        }
    }
}

/** One stepper: "Days", minus, the value (announced when it changes), plus. */
@Composable
private fun Stepper(unit: ShiftUnit, value: Int, onValueChange: (Int) -> Unit) {
    val name = stringResource(unit.labelRes)
    val valueText = pluralStringResource(unit.countRes, value, value)
    Row(
        Modifier
            .fillMaxWidth()
            .heightIn(min = 64.dp)
            .padding(horizontal = 16.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Text(name, style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f))
        FilledTonalIconButton(
            onClick = { onValueChange(value - 1) },
            enabled = value > 0,
            shapes = IconButtonDefaults.shapes(),
            modifier = Modifier.size(48.dp),
        ) {
            Icon(Icons.Rounded.Remove, contentDescription = stringResource(R.string.edit_shift_decrease, name))
        }
        Text(
            value.toString(),
            style = ExifLabTheme.extendedTypography.numeral,
            textAlign = TextAlign.Center,
            modifier = Modifier
                .widthIn(min = 56.dp)
                .clearAndSetSemantics {
                    contentDescription = valueText
                    liveRegion = LiveRegionMode.Polite
                },
        )
        FilledTonalIconButton(
            onClick = { onValueChange(value + 1) },
            enabled = value < unit.max,
            shapes = IconButtonDefaults.shapes(),
            modifier = Modifier.size(48.dp),
        ) {
            Icon(Icons.Rounded.Add, contentDescription = stringResource(R.string.edit_shift_increase, name))
        }
    }
}

/** Before and after, side by side (stacked at large text sizes), so the effect is visible before applying. */
@Composable
private fun ShiftPreview(shift: TimeShift, capturedAt: LocalDateTime?, offset: ZoneOffset?) {
    if (capturedAt == null) {
        Text(
            stringResource(R.string.edit_shift_no_date),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        return
    }
    val after = capturedAt.plus(shift.duration)
    val summary = stringResource(R.string.edit_shift_preview_description, durationText(shift.duration))
    val before: @Composable (Modifier) -> Unit = { modifier ->
        PreviewTime(stringResource(R.string.edit_before), capturedAt, offset, emphasized = false, modifier)
    }
    val afterBlock: @Composable (Modifier) -> Unit = { modifier ->
        PreviewTime(stringResource(R.string.edit_after), after, offset, emphasized = true, modifier)
    }
    Column(
        Modifier.semantics(mergeDescendants = true) {
            contentDescription = summary
            liveRegion = LiveRegionMode.Polite
        },
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        if (isLargeFontScale()) {
            before(Modifier.fillMaxWidth())
            afterBlock(Modifier.fillMaxWidth())
        } else {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                before(Modifier.weight(1f))
                Icon(Icons.AutoMirrored.Rounded.ArrowForward, contentDescription = null, tint = MaterialTheme.colorScheme.onSurfaceVariant)
                afterBlock(Modifier.weight(1f))
            }
        }
        if (offset != null) {
            Text(
                stringResource(R.string.edit_shift_offset_kept, utcText(offset)),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
internal fun PreviewTime(label: String, time: LocalDateTime, offset: ZoneOffset?, emphasized: Boolean, modifier: Modifier = Modifier) {
    val locale = LocalLocale.current.platformLocale
    DiffValueBox(label, if (emphasized) FieldDiff.Kind.Changed else null, modifier) {
        Text(CaptureTimeFormat.time(time, locale), style = ExifLabTheme.extendedTypography.monoLarge)
        Text(CaptureTimeFormat.date(time, locale), style = MaterialTheme.typography.bodySmall)
        if (offset != null) Text(utcText(offset), style = ExifLabTheme.extendedTypography.monoSmall)
    }
}

private val ShiftUnit.labelRes: Int
    get() = when (this) {
        ShiftUnit.Days -> R.string.edit_unit_days
        ShiftUnit.Hours -> R.string.edit_unit_hours
        ShiftUnit.Minutes -> R.string.edit_unit_minutes
        ShiftUnit.Seconds -> R.string.edit_unit_seconds
    }

private val ShiftUnit.countRes: Int
    get() = when (this) {
        ShiftUnit.Days -> R.plurals.edit_count_days
        ShiftUnit.Hours -> R.plurals.edit_count_hours
        ShiftUnit.Minutes -> R.plurals.edit_count_minutes
        ShiftUnit.Seconds -> R.plurals.edit_count_seconds
    }
