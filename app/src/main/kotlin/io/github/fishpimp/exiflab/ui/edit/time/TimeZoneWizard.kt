package io.github.fishpimp.exiflab.ui.edit.time

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.automirrored.rounded.ArrowForward
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.Search
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearWavyProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalLocale
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import io.github.fishpimp.exiflab.R
import io.github.fishpimp.exiflab.designsystem.theme.ExifLabTheme
import io.github.fishpimp.exiflab.ui.components.isLargeFontScale
import io.github.fishpimp.exiflab.ui.edit.durationText
import io.github.fishpimp.exiflab.ui.edit.utcText
import java.time.LocalDateTime
import java.time.ZoneId
import java.time.ZoneOffset

/** Where the "Fix time zone" wizard is: the step, both answers so far and the search text. */
@Immutable
data class TimeZoneWizardState(
    val step: WizardStep = WizardStep.CameraZone,
    val cameraZone: ZoneId,
    val actualZone: ZoneId?,
    val query: String = "",
)

enum class WizardStep { CameraZone, ActualZone }

/**
 * The "Fix time zone" wizard: "Camera clock was set to" (default: this device's zone), then
 * "Photo was taken in" (default: a zone matching the photo's recorded offset, when known), with a
 * live preview of the corrected time, daylight saving included.
 */
@Composable
fun TimeZoneWizard(
    recorded: LocalDateTime?,
    recordedOffset: ZoneOffset?,
    onApply: (ZoneId, ZoneId) -> Unit,
    onClose: () -> Unit,
    deviceZone: ZoneId = ZoneId.systemDefault(),
) {
    val locale = LocalLocale.current.platformLocale
    val reference = recorded ?: LocalDateTime.now()
    val zones = remember(reference, locale) { ZoneCatalog.all(reference, locale) }
    val suggested = remember(zones, recordedOffset, deviceZone) { ZoneCatalog.suggestFor(recordedOffset, reference, deviceZone, zones) }
    var step by rememberSaveable { mutableStateOf(WizardStep.CameraZone) }
    var camera by rememberSaveable { mutableStateOf(deviceZone.id) }
    // A suggestion equal to the camera's zone would change nothing, so it is not preselected.
    var actual by rememberSaveable { mutableStateOf(suggested?.takeIf { it != deviceZone }?.id) }
    var query by rememberSaveable { mutableStateOf("") }
    TimeZoneWizardContent(
        state = TimeZoneWizardState(step, ZoneId.of(camera), actual?.let(ZoneId::of), query),
        recorded = recorded,
        zones = zones,
        deviceZone = deviceZone,
        suggestedZone = suggested,
        onStateChange = {
            if (it.step != step) query = "" else query = it.query
            step = it.step
            camera = it.cameraZone.id
            actual = it.actualZone?.id
        },
        onApply = { cameraZone, actualZone -> onApply(cameraZone, actualZone) },
        onClose = onClose,
    )
}

/** The wizard's page; stateless, for screenshot tests. */
@Composable
fun TimeZoneWizardContent(
    state: TimeZoneWizardState,
    recorded: LocalDateTime?,
    zones: List<ZoneChoice>,
    deviceZone: ZoneId,
    suggestedZone: ZoneId?,
    onStateChange: (TimeZoneWizardState) -> Unit,
    onApply: (ZoneId, ZoneId) -> Unit,
    onClose: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val locale = LocalLocale.current.platformLocale
    val cameraStep = state.step == WizardStep.CameraZone
    val selected = if (cameraStep) state.cameraZone else state.actualZone
    val pinnedZone = if (cameraStep) deviceZone else suggestedZone?.takeIf { it != state.cameraZone }
    val reference = recorded ?: LocalDateTime.now()
    val filtered = remember(zones, state.query) { ZoneCatalog.search(zones, state.query) }
    val pinned = remember(pinnedZone, reference, locale, state.query) {
        pinnedZone?.takeIf { state.query.isBlank() }?.let { ZoneCatalog.choice(it, reference, locale) }
    }
    val correction = remember(recorded, state.cameraZone, state.actualZone) {
        if (recorded != null && state.actualZone != null) TimeZoneCorrection.of(recorded, state.cameraZone, state.actualZone) else null
    }
    fun select(zone: ZoneId) = onStateChange(if (cameraStep) state.copy(cameraZone = zone) else state.copy(actualZone = zone))

    Scaffold(
        modifier = modifier,
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.edit_tz_title)) },
                navigationIcon = {
                    IconButton(onClick = onClose) { Icon(Icons.Rounded.Close, contentDescription = stringResource(R.string.action_close)) }
                },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = MaterialTheme.colorScheme.surface),
            )
        },
        containerColor = MaterialTheme.colorScheme.surface,
    ) { padding ->
        Box(Modifier.fillMaxSize().padding(padding), contentAlignment = Alignment.TopCenter) {
            Column(Modifier.widthIn(max = 720.dp).fillMaxSize()) {
                Column(Modifier.padding(horizontal = 16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    LinearWavyProgressIndicator(
                        progress = { if (cameraStep) 0.5f else 1f },
                        modifier = Modifier.fillMaxWidth(),
                    )
                    Text(
                        stringResource(R.string.edit_tz_step, if (cameraStep) 1 else 2, 2),
                        style = MaterialTheme.typography.labelLarge,
                        color = MaterialTheme.colorScheme.primary,
                    )
                    Text(
                        stringResource(if (cameraStep) R.string.edit_tz_camera_question else R.string.edit_tz_actual_question),
                        style = MaterialTheme.typography.headlineSmall,
                        modifier = Modifier.semantics { heading() },
                    )
                    Text(
                        stringResource(if (cameraStep) R.string.edit_tz_camera_body else R.string.edit_tz_actual_body),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    OutlinedTextField(
                        value = state.query,
                        onValueChange = { onStateChange(state.copy(query = it)) },
                        label = { Text(stringResource(R.string.edit_tz_search)) },
                        leadingIcon = { Icon(Icons.Rounded.Search, contentDescription = null) },
                        singleLine = true,
                        keyboardOptions = androidx.compose.foundation.text.KeyboardOptions(imeAction = ImeAction.Search),
                        modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp),
                    )
                }
                LazyColumn(
                    modifier = Modifier.weight(1f).fillMaxWidth().selectableGroup(),
                    contentPadding = PaddingValues(vertical = 8.dp),
                ) {
                    if (pinned != null) {
                        item(key = "pinned-header") {
                            ListLabel(stringResource(if (cameraStep) R.string.edit_tz_device_zone else R.string.edit_tz_suggested_zone))
                        }
                        item(key = "pinned") { ZoneRow(pinned, pinned.id == selected, onClick = { select(pinned.id) }) }
                        item(key = "all-header") { ListLabel(stringResource(R.string.edit_tz_all_zones)) }
                    }
                    if (filtered.isEmpty()) {
                        item(key = "empty") {
                            Text(
                                stringResource(R.string.edit_tz_no_match),
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.padding(16.dp),
                            )
                        }
                    }
                    items(filtered, key = { it.id.id }) { zone -> ZoneRow(zone, zone.id == selected, onClick = { select(zone.id) }) }
                }
                WizardFooter(
                    state = state,
                    correction = correction,
                    hasRecordedTime = recorded != null,
                    onBack = { onStateChange(state.copy(step = WizardStep.CameraZone)) },
                    onNext = { onStateChange(state.copy(step = WizardStep.ActualZone)) },
                    onApply = { state.actualZone?.let { onApply(state.cameraZone, it) } },
                )
            }
        }
    }
}

@Composable
private fun ListLabel(text: String) {
    Text(
        text,
        style = MaterialTheme.typography.labelLarge,
        color = MaterialTheme.colorScheme.primary,
        modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp).semantics { heading() },
    )
}

@Composable
private fun ZoneRow(zone: ZoneChoice, selected: Boolean, onClick: () -> Unit) {
    Row(
        Modifier
            .fillMaxWidth()
            .selectable(selected = selected, role = Role.RadioButton, onClick = onClick)
            .heightIn(min = 56.dp)
            .padding(horizontal = 16.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        RadioButton(selected = selected, onClick = null)
        Column(Modifier.weight(1f)) {
            Text(zone.city, style = MaterialTheme.typography.bodyLarge)
            Text(
                listOf(zone.displayName, zone.region).filter { it.isNotBlank() }.joinToString(" · "),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        Text(utcText(zone.offset), style = ExifLabTheme.extendedTypography.monoSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@Composable
private fun WizardFooter(
    state: TimeZoneWizardState,
    correction: TimeZoneCorrection?,
    hasRecordedTime: Boolean,
    onBack: () -> Unit,
    onNext: () -> Unit,
    onApply: () -> Unit,
) {
    Surface(color = MaterialTheme.colorScheme.surfaceContainer, shape = MaterialTheme.shapes.extraLarge.copy(bottomStart = androidx.compose.foundation.shape.CornerSize(0.dp), bottomEnd = androidx.compose.foundation.shape.CornerSize(0.dp))) {
        Column(
            Modifier
                .fillMaxWidth()
                .navigationBarsPadding()
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            CorrectionPreview(correction, hasRecordedTime)
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.End)) {
                if (state.step == WizardStep.ActualZone) {
                    OutlinedButton(onClick = onBack, modifier = Modifier.heightIn(min = 48.dp)) {
                        Icon(Icons.AutoMirrored.Rounded.ArrowBack, contentDescription = null)
                        Text(stringResource(R.string.edit_tz_back), modifier = Modifier.padding(start = ButtonDefaults.IconSpacing))
                    }
                    Button(onClick = onApply, enabled = state.actualZone != null, modifier = Modifier.heightIn(min = 48.dp)) {
                        Icon(Icons.Rounded.Check, contentDescription = null)
                        Text(stringResource(R.string.edit_apply), modifier = Modifier.padding(start = ButtonDefaults.IconSpacing))
                    }
                } else {
                    Button(onClick = onNext, modifier = Modifier.heightIn(min = 48.dp)) {
                        Text(stringResource(R.string.edit_tz_next))
                        Icon(Icons.AutoMirrored.Rounded.ArrowForward, contentDescription = null, modifier = Modifier.padding(start = ButtonDefaults.IconSpacing))
                    }
                }
            }
        }
    }
}

/** Recorded and corrected time with offsets and daylight saving, announced as it changes. */
@Composable
private fun CorrectionPreview(correction: TimeZoneCorrection?, hasRecordedTime: Boolean) {
    if (correction == null) {
        Text(
            stringResource(if (hasRecordedTime) R.string.edit_tz_preview_pick else R.string.edit_tz_preview_no_time),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        return
    }
    val shiftText = if (correction.changesTime) {
        stringResource(R.string.edit_tz_preview_shift, durationText(correction.shift))
    } else {
        stringResource(R.string.edit_tz_preview_same)
    }
    Column(
        Modifier.semantics(mergeDescendants = true) { liveRegion = LiveRegionMode.Polite },
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        val before: @Composable (Modifier) -> Unit = {
            PreviewTime(stringResource(R.string.edit_tz_recorded), correction.recorded, correction.cameraOffset, emphasized = false, modifier = it)
        }
        val after: @Composable (Modifier) -> Unit = {
            PreviewTime(stringResource(R.string.edit_tz_corrected), correction.corrected, correction.correctedOffset, emphasized = true, modifier = it)
        }
        if (isLargeFontScale()) {
            before(Modifier.fillMaxWidth())
            after(Modifier.fillMaxWidth())
        } else {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                before(Modifier.weight(1f))
                Icon(Icons.AutoMirrored.Rounded.ArrowForward, contentDescription = null, tint = MaterialTheme.colorScheme.onSurfaceVariant)
                after(Modifier.weight(1f))
            }
        }
        val dst = when {
            correction.correctedDaylightSaving -> stringResource(R.string.edit_tz_summer_time)
            else -> stringResource(R.string.edit_tz_standard_time)
        }
        Text("$shiftText · $dst", style = MaterialTheme.typography.bodyMedium)
        if (correction.recordedInGap) {
            Text(stringResource(R.string.edit_tz_gap), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        if (correction.recordedAmbiguous) {
            Text(stringResource(R.string.edit_tz_overlap), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}
