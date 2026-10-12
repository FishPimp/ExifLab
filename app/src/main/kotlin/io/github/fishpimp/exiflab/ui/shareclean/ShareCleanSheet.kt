package io.github.fishpimp.exiflab.ui.shareclean

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Block
import androidx.compose.material.icons.rounded.CleaningServices
import androidx.compose.material.icons.rounded.ErrorOutline
import androidx.compose.material.icons.rounded.Info
import androidx.compose.material.icons.rounded.Lock
import androidx.compose.material.icons.rounded.Share
import androidx.compose.material.icons.rounded.Visibility
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearWavyProgressIndicator
import androidx.compose.material3.LoadingIndicator
import androidx.compose.material3.MaterialShapes
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalResources
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.ProgressBarRangeInfo
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.progressBarRangeInfo
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.github.fishpimp.exiflab.R
import io.github.fishpimp.exiflab.data.photos.PhotoAccessError
import io.github.fishpimp.exiflab.data.shareclean.Refusal
import io.github.fishpimp.exiflab.data.shareclean.ShareCleanPreset
import io.github.fishpimp.exiflab.data.shareclean.ShareCleanResult
import io.github.fishpimp.exiflab.designsystem.component.ShapeIcon
import io.github.fishpimp.exiflab.designsystem.theme.ExifLabTheme
import io.github.fishpimp.exiflab.metadata.edit.StripCategory
import io.github.fishpimp.exiflab.ui.batch.bodyRes
import io.github.fishpimp.exiflab.ui.batch.titleRes
import kotlinx.coroutines.launch

/** Everything the sheet can ask for. Defaults do nothing, for screenshot tests. */
@Immutable
data class ShareCleanActions(
    val onSelectPreset: (ShareCleanPreset) -> Unit = {},
    val onToggleCustom: (StripCategory) -> Unit = {},
    val onIncludeRawPreviews: (Boolean) -> Unit = {},
    val onShare: () -> Unit = {},
    val onShareCleaned: () -> Unit = {},
    val onCancelCleaning: () -> Unit = {},
    val onClose: () -> Unit = {},
)

/**
 * The Share clean sheet of [viewModel], shown while it has photos, and the hand-off of the
 * cleaned copies to the system share sheet.
 *
 * @param compact the smaller sheet [ShareCleanActivity] shows over the app that shared the photos.
 * @param onShared called after the system share sheet was opened.
 * @param onClosed called when the sheet closes without sharing.
 * @param onOpenFailed called when picked or shared photos could not be opened.
 */
@Composable
fun ShareCleanSheetHost(
    viewModel: ShareCleanViewModel,
    compact: Boolean = false,
    onShared: () -> Unit = {},
    onClosed: () -> Unit = {},
    onOpenFailed: (PhotoAccessError) -> Unit = {},
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val currentOnShared by rememberUpdatedState(onShared)
    val currentOnOpenFailed by rememberUpdatedState(onOpenFailed)
    LaunchedEffect(viewModel) {
        viewModel.events.collect { event ->
            when (event) {
                is ShareCleanEvent.Share -> {
                    context.startCleanShare(event.files)
                    currentOnShared()
                }
                is ShareCleanEvent.OpenFailed -> currentOnOpenFailed(event.error)
            }
        }
    }
    if (!state.isOpen) return

    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    val scope = rememberCoroutineScope()
    fun close() {
        viewModel.dismiss()
        onClosed()
    }
    val actions = remember(viewModel) {
        ShareCleanActions(
            onSelectPreset = viewModel::selectPreset,
            onToggleCustom = viewModel::toggleCustom,
            onIncludeRawPreviews = viewModel::setIncludeRawPreviews,
            onShare = viewModel::share,
            onShareCleaned = viewModel::shareCleaned,
            onCancelCleaning = viewModel::cancelCleaning,
            onClose = { scope.launch { sheetState.hide() }.invokeOnCompletion { close() } },
        )
    }
    ModalBottomSheet(onDismissRequest = ::close, sheetState = sheetState) {
        ShareCleanSheetContent(
            state = state,
            actions = actions,
            compact = compact,
            modifier = Modifier.verticalScroll(rememberScrollState()),
        )
    }
}

/**
 * The body of the Share clean sheet: presets with what they remove, then cleaning progress, then
 * (only when photos were left out) the result. Stateless, so screenshot tests render it directly.
 */
@Composable
fun ShareCleanSheetContent(
    state: ShareCleanUiState,
    actions: ShareCleanActions,
    modifier: Modifier = Modifier,
    compact: Boolean = false,
) {
    Column(
        modifier
            .fillMaxWidth()
            .padding(start = 24.dp, end = 24.dp, bottom = 24.dp),
        verticalArrangement = Arrangement.spacedBy(if (compact) 16.dp else 20.dp),
    ) {
        val step = state.step
        SheetHeader(state, compact, title = if (step is ShareCleanStep.Finished) step.result.title() else null)
        when (step) {
            ShareCleanStep.Choosing -> ChoosingBody(state, actions, compact)
            is ShareCleanStep.Cleaning -> CleaningBody(step, actions)
            is ShareCleanStep.Finished -> FinishedBody(step.result, actions)
        }
    }
}

@Composable
private fun ShareCleanResult.title(): String =
    stringResource(if (files.isEmpty()) R.string.share_clean_result_none else R.string.share_clean_result_some)

@Composable
private fun SheetHeader(state: ShareCleanUiState, compact: Boolean, title: String?) {
    val single = state.photos.singleOrNull()
    val subtitle = single?.displayName ?: pluralStringResource(R.plurals.photo_count, state.photos.size, state.photos.size)
    Row(horizontalArrangement = Arrangement.spacedBy(16.dp), verticalAlignment = Alignment.CenterVertically) {
        ShapeIcon(
            icon = Icons.Rounded.Share,
            shape = MaterialShapes.Cookie7Sided,
            size = if (compact) 40.dp else 48.dp,
            containerColor = ExifLabTheme.extendedColors.sensitiveContainer,
            contentColor = ExifLabTheme.extendedColors.onSensitiveContainer,
        )
        Column(Modifier.weight(1f)) {
            Text(
                text = title ?: stringResource(if (compact) R.string.share_clean_and_share else R.string.share_clean_title),
                style = if (compact) MaterialTheme.typography.titleLarge else MaterialTheme.typography.headlineSmall,
                modifier = Modifier.semantics { heading() },
            )
            Text(subtitle, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

@Composable
private fun ChoosingBody(state: ShareCleanUiState, actions: ShareCleanActions, compact: Boolean) {
    if (state.tooMany) {
        Notice(Icons.Rounded.ErrorOutline, stringResource(R.string.share_clean_too_many, MAX_SHARE_PHOTOS), error = true)
    }
    SummaryCard(state)
    PresetList(state, actions, compact)
    if (state.rawCount > 0) RawNote(state, actions)
    if (!compact) {
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.Top) {
            Icon(Icons.Rounded.Lock, contentDescription = null, modifier = Modifier.size(18.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
            Text(
                stringResource(R.string.share_clean_private_note),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
    SheetButtons {
        TextButton(onClick = actions.onClose) { Text(stringResource(R.string.action_cancel)) }
        Button(onClick = actions.onShare, enabled = state.canShare) {
            Icon(Icons.Rounded.Share, contentDescription = null, modifier = Modifier.size(18.dp))
            Text(stringResource(R.string.share_clean_share), Modifier.padding(start = 8.dp))
        }
    }
}

/** What goes: for one photo, what it holds of the choice (and what private data stays); for several, the choice. */
@Composable
private fun SummaryCard(state: ShareCleanUiState) {
    val resources = LocalResources.current
    val categories = state.choice.categories
    if (categories.isEmpty()) return
    Surface(shape = MaterialTheme.shapes.large, color = MaterialTheme.colorScheme.surfaceContainerHigh, modifier = Modifier.fillMaxWidth()) {
        Column(
            Modifier
                .padding(16.dp)
                .semantics(mergeDescendants = true) { liveRegion = LiveRegionMode.Polite },
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            when (val summary = state.summary) {
                SummarySource.Several -> SummaryLine(
                    Icons.Rounded.CleaningServices,
                    stringResource(R.string.share_clean_removes_each, remember(resources, categories) { categoriesPhrase(resources, categories) }),
                )
                SummarySource.Loading -> Row(horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically) {
                    LoadingIndicator(Modifier.size(24.dp))
                    Text(stringResource(R.string.share_clean_checking), style = MaterialTheme.typography.bodyMedium)
                }
                SummarySource.Unavailable -> SummaryLine(Icons.Rounded.Info, stringResource(R.string.share_clean_summary_unavailable))
                is SummarySource.Ready -> {
                    val text = remember(resources, summary.report, categories) { removalSummary(resources, summary.report, categories) }
                    SummaryLine(
                        Icons.Rounded.CleaningServices,
                        text.removes?.let { stringResource(R.string.share_clean_removes, it) } ?: stringResource(R.string.share_clean_nothing),
                        emphasized = text.removes != null,
                    )
                    text.keeps?.let { SummaryLine(Icons.Rounded.Visibility, stringResource(R.string.share_clean_keeps, it)) }
                }
            }
        }
    }
}

@Composable
private fun SummaryLine(icon: ImageVector, text: String, emphasized: Boolean = false) {
    Row(horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.Top) {
        Icon(
            icon,
            contentDescription = null,
            modifier = Modifier.size(24.dp),
            tint = if (emphasized) ExifLabTheme.extendedColors.sensitive else MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Text(
            text,
            style = if (emphasized) MaterialTheme.typography.bodyLarge else MaterialTheme.typography.bodyMedium,
            modifier = Modifier.weight(1f),
        )
    }
}

@Composable
private fun PresetList(state: ShareCleanUiState, actions: ShareCleanActions, compact: Boolean) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(
            stringResource(R.string.share_clean_presets_title),
            style = MaterialTheme.typography.titleSmall,
            color = MaterialTheme.colorScheme.primary,
            modifier = Modifier.semantics { heading() },
        )
        Surface(shape = MaterialTheme.shapes.large, color = MaterialTheme.colorScheme.surfaceContainer, modifier = Modifier.fillMaxWidth()) {
            Column(Modifier.selectableGroup().padding(vertical = 4.dp)) {
                ShareCleanPreset.entries.forEach { preset ->
                    PresetRow(preset, selected = state.choice.preset == preset, onSelect = { actions.onSelectPreset(preset) })
                }
                AnimatedVisibility(
                    visible = state.choice.preset == ShareCleanPreset.Custom,
                    enter = expandVertically() + fadeIn(),
                    exit = shrinkVertically() + fadeOut(),
                ) {
                    CustomCategories(state.choice.custom, actions.onToggleCustom, showBodies = !compact)
                }
            }
        }
        if (state.choice.preset == ShareCleanPreset.Custom && state.choice.custom.isEmpty()) {
            Notice(Icons.Rounded.ErrorOutline, stringResource(R.string.share_clean_custom_empty), error = true)
        }
    }
}

@Composable
private fun PresetRow(preset: ShareCleanPreset, selected: Boolean, onSelect: () -> Unit) {
    Row(
        Modifier
            .fillMaxWidth()
            .selectable(selected = selected, role = Role.RadioButton, onClick = onSelect)
            .heightIn(min = 56.dp)
            .padding(horizontal = 16.dp, vertical = 10.dp),
        horizontalArrangement = Arrangement.spacedBy(16.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        RadioButton(selected = selected, onClick = null)
        Column(Modifier.weight(1f)) {
            Text(stringResource(preset.titleRes), style = MaterialTheme.typography.titleMedium)
            Text(
                stringResource(preset.bodyRes),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

/** Every strip category as a checkbox; "everything" covers (and ticks) all others. */
@Composable
private fun CustomCategories(selected: Set<StripCategory>, onToggle: (StripCategory) -> Unit, showBodies: Boolean) {
    val everything = StripCategory.All in selected
    Column(Modifier.padding(start = 40.dp, bottom = 8.dp)) {
        HorizontalDivider(Modifier.padding(start = 16.dp, end = 16.dp, bottom = 4.dp), color = MaterialTheme.colorScheme.outlineVariant)
        CustomOrder.forEach { category ->
            val covered = everything && category != StripCategory.All
            val checked = category in selected || covered
            Row(
                Modifier
                    .fillMaxWidth()
                    .toggleable(value = checked, enabled = !covered, role = Role.Checkbox, onValueChange = { onToggle(category) })
                    .heightIn(min = 48.dp)
                    .padding(horizontal = 16.dp, vertical = 6.dp),
                horizontalArrangement = Arrangement.spacedBy(12.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Checkbox(checked = checked, onCheckedChange = null, enabled = !covered)
                val alpha = if (covered) DISABLED_ALPHA else 1f
                Column(Modifier.weight(1f)) {
                    Text(
                        stringResource(category.titleRes),
                        style = MaterialTheme.typography.bodyLarge,
                        color = MaterialTheme.colorScheme.onSurface.copy(alpha = alpha),
                    )
                    if (showBodies) {
                        Text(
                            stringResource(category.bodyRes),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = alpha),
                        )
                    }
                }
            }
        }
    }
}

/** The single categories by how private they are, then "everything". */
private val CustomOrder = listOf(
    StripCategory.Location,
    StripCategory.DeviceIdentifiers,
    StripCategory.PersonNames,
    StripCategory.People,
    StripCategory.DeviceInfo,
    StripCategory.Dates,
    StripCategory.MakerNotes,
    StripCategory.Thumbnail,
    StripCategory.EditHistory,
    StripCategory.All,
)

private const val DISABLED_ALPHA = 0.38f

@Composable
private fun RawNote(state: ShareCleanUiState, actions: ShareCleanActions) {
    Surface(
        shape = MaterialTheme.shapes.large,
        color = MaterialTheme.colorScheme.tertiaryContainer,
        contentColor = MaterialTheme.colorScheme.onTertiaryContainer,
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column {
            Row(
                Modifier.padding(16.dp).semantics(mergeDescendants = true) {},
                horizontalArrangement = Arrangement.spacedBy(12.dp),
                verticalAlignment = Alignment.Top,
            ) {
                Icon(Icons.Rounded.Info, contentDescription = null, modifier = Modifier.size(24.dp))
                val text = if (state.photos.size == 1) {
                    stringResource(R.string.share_clean_raw_single)
                } else {
                    pluralStringResource(R.plurals.share_clean_raw_many, state.rawCount, state.rawCount)
                }
                Text(text, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f))
            }
            if (state.photos.size > 1) {
                val include = state.choice.includeRawPreviews
                Row(
                    Modifier
                        .fillMaxWidth()
                        .toggleable(value = include, role = Role.Switch, onValueChange = actions.onIncludeRawPreviews)
                        .heightIn(min = 56.dp)
                        .padding(start = 52.dp, end = 16.dp, top = 4.dp, bottom = 12.dp),
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Column(Modifier.weight(1f)) {
                        Text(stringResource(R.string.share_clean_raw_previews), style = MaterialTheme.typography.titleSmall)
                        Text(
                            stringResource(if (include) R.string.share_clean_raw_previews_on else R.string.share_clean_raw_previews_off),
                            style = MaterialTheme.typography.bodySmall,
                        )
                    }
                    Switch(checked = include, onCheckedChange = null)
                }
            }
        }
    }
}

@Composable
private fun Notice(icon: ImageVector, text: String, error: Boolean = false) {
    val color = if (error) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant
    Row(
        Modifier.semantics(mergeDescendants = true) { liveRegion = LiveRegionMode.Polite },
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        verticalAlignment = Alignment.Top,
    ) {
        Icon(icon, contentDescription = null, tint = color, modifier = Modifier.size(20.dp))
        Text(text, style = MaterialTheme.typography.bodyMedium, color = color, modifier = Modifier.weight(1f))
    }
}

@Composable
private fun CleaningBody(step: ShareCleanStep.Cleaning, actions: ShareCleanActions) {
    val fraction = if (step.total > 0) step.done.toFloat() / step.total else 0f
    val current = (step.done + 1).coerceAtMost(step.total)
    val progressText = pluralStringResource(R.plurals.share_clean_progress, step.total, current, step.total)
    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        LinearWavyProgressIndicator(
            progress = { fraction },
            modifier = Modifier
                .fillMaxWidth()
                .semantics {
                    contentDescription = progressText
                    progressBarRangeInfo = ProgressBarRangeInfo(fraction, 0f..1f)
                },
        )
        Text(
            progressText,
            style = MaterialTheme.typography.titleMedium,
            modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite },
        )
        Text(
            stringResource(R.string.share_clean_progress_body),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
    SheetButtons {
        TextButton(onClick = actions.onCancelCleaning) { Text(stringResource(R.string.action_cancel)) }
    }
}

@Composable
private fun FinishedBody(result: ShareCleanResult, actions: ShareCleanActions) {
    val resources = LocalResources.current
    Text(
        pluralStringResource(R.plurals.share_clean_result_body, result.refusals.size, result.refusals.size),
        style = MaterialTheme.typography.bodyLarge,
    )
    Surface(shape = MaterialTheme.shapes.large, color = MaterialTheme.colorScheme.surfaceContainer, modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(vertical = 8.dp)) {
            result.refusals.forEach { refusal -> RefusalRow(refusal, refusal.reason.message(resources)) }
        }
    }
    SheetButtons {
        TextButton(onClick = actions.onClose) { Text(stringResource(R.string.action_close)) }
        if (result.files.isNotEmpty()) {
            Button(onClick = actions.onShareCleaned) {
                Icon(Icons.Rounded.Share, contentDescription = null, modifier = Modifier.size(18.dp))
                Text(
                    pluralStringResource(R.plurals.share_clean_share_count, result.files.size, result.files.size),
                    Modifier.padding(start = 8.dp),
                )
            }
        }
    }
}

@Composable
private fun RefusalRow(refusal: Refusal, reason: String) {
    Row(
        Modifier
            .fillMaxWidth()
            .semantics(mergeDescendants = true) {}
            .heightIn(min = 56.dp)
            .padding(horizontal = 16.dp, vertical = 8.dp),
        horizontalArrangement = Arrangement.spacedBy(16.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(Icons.Rounded.Block, contentDescription = null, tint = MaterialTheme.colorScheme.error)
        Column(Modifier.weight(1f)) {
            Text(
                refusal.name.ifEmpty { stringResource(R.string.photo_untitled) },
                style = MaterialTheme.typography.bodyLarge,
            )
            Text(reason, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

/** The sheet's buttons, at the end; they wrap onto more lines at large font sizes. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun SheetButtons(content: @Composable () -> Unit) {
    FlowRow(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.End),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) { content() }
}
