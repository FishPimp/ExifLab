package io.github.fishpimp.exiflab.ui.edit

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
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
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.rounded.CheckCircle
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.Description
import androidx.compose.material.icons.rounded.Difference
import androidx.compose.material.icons.rounded.ErrorOutline
import androidx.compose.material.icons.rounded.FileOpen
import androidx.compose.material.icons.rounded.Info
import androidx.compose.material.icons.rounded.Save
import androidx.compose.material.icons.rounded.SaveAs
import androidx.compose.material.icons.rounded.Tune
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.ContainedLoadingIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LoadingIndicator
import androidx.compose.material3.MaterialShapes
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.unit.dp
import io.github.fishpimp.exiflab.R
import io.github.fishpimp.exiflab.data.photos.PhotoRef
import io.github.fishpimp.exiflab.data.photos.format
import io.github.fishpimp.exiflab.data.save.SaveRejection
import io.github.fishpimp.exiflab.designsystem.component.ShapeIcon
import io.github.fishpimp.exiflab.designsystem.theme.ExifLabTheme
import io.github.fishpimp.exiflab.metadata.ImageFormat
import io.github.fishpimp.exiflab.metadata.edit.FieldDiff
import io.github.fishpimp.exiflab.metadata.model.DirectoryGroup
import io.github.fishpimp.exiflab.metadata.model.MetadataReport
import io.github.fishpimp.exiflab.ui.history.DiffKindChip
import io.github.fishpimp.exiflab.ui.history.labelRes
import io.github.fishpimp.exiflab.ui.history.messageRes

/**
 * The diff preview and save step: every field that will change, grouped by metadata block,
 * planner warnings, where the result goes and why, and the save buttons the file allows.
 */
@Composable
fun ReviewContent(
    ref: PhotoRef,
    report: MetadataReport,
    state: EditUiState,
    onClose: () -> Unit,
    onSave: (SaveMode) -> Unit,
    onDismissFailure: () -> Unit,
    onFinish: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val options = state.saveOptions
    val plan = (state.plan as? PlanState.Ready)?.plan
    val saved = state.save as? SaveState.SavedWithSkipped
    Scaffold(
        modifier = modifier,
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.edit_review_title)) },
                navigationIcon = {
                    IconButton(onClick = onClose, enabled = !state.isSaving) {
                        Icon(Icons.AutoMirrored.Rounded.ArrowBack, contentDescription = stringResource(R.string.action_back))
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = MaterialTheme.colorScheme.surface),
            )
        },
        bottomBar = {
            if (saved != null) {
                ReviewBar { Button(onClick = onFinish, modifier = Modifier.fillMaxWidth().heightIn(min = 56.dp)) { Text(stringResource(R.string.edit_review_done)) } }
            } else if (options != null && plan != null) {
                ReviewBar {
                    options.modes.forEachIndexed { index, mode ->
                        val content: @Composable () -> Unit = {
                            Icon(mode.icon, contentDescription = null, modifier = Modifier.size(ButtonDefaults.IconSize))
                            Text(stringResource(mode.labelRes), modifier = Modifier.padding(start = ButtonDefaults.IconSpacing))
                        }
                        if (index == 0) {
                            Button(
                                onClick = { onSave(mode) },
                                enabled = state.canSave,
                                modifier = Modifier.fillMaxWidth().heightIn(min = 56.dp),
                            ) { content() }
                        } else {
                            OutlinedButton(
                                onClick = { onSave(mode) },
                                enabled = state.canSave,
                                modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp),
                            ) { content() }
                        }
                    }
                }
            }
        },
        containerColor = MaterialTheme.colorScheme.surface,
    ) { padding ->
        Box(Modifier.fillMaxSize().padding(padding), contentAlignment = Alignment.TopCenter) {
            LazyColumn(
                modifier = Modifier.widthIn(max = 720.dp).fillMaxSize(),
                contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 8.dp, bottom = 24.dp),
                verticalArrangement = Arrangement.spacedBy(16.dp),
            ) {
                item(key = "header") { ReviewHeader(ref, report, state.plan) }
                if (saved != null) {
                    item(key = "saved") { SavedResult(saved) }
                }
                (state.save as? SaveState.Failed)?.let { failed ->
                    item(key = "failure") { FailureBanner(failed.failure, onDismissFailure) }
                }
                when (val planState = state.plan) {
                    PlanState.Planning -> item(key = "planning") { PlanningRow() }
                    PlanState.Unavailable -> item(key = "unavailable") {
                        EditorBanner(Icons.Rounded.Tune, stringResource(R.string.edit_review_planner_unavailable))
                    }
                    is PlanState.Failed -> item(key = "plan-failed") {
                        EditorBanner(
                            icon = Icons.Rounded.ErrorOutline,
                            title = stringResource(R.string.edit_review_plan_failed),
                            text = planState.message ?: stringResource(R.string.edit_review_plan_failed_body),
                            containerColor = MaterialTheme.colorScheme.errorContainer,
                            contentColor = MaterialTheme.colorScheme.onErrorContainer,
                            announce = true,
                        )
                    }
                    PlanState.Empty -> if (saved == null) {
                        item(key = "empty") { EditorBanner(Icons.Rounded.Info, stringResource(R.string.edit_review_nothing)) }
                    }
                    is PlanState.Ready -> {
                        if (planState.plan.warnings.isNotEmpty()) {
                            item(key = "warnings") { WarningsBanner(planState.plan.warnings) }
                        }
                        if (options != null && saved == null) {
                            item(key = "destination") { DestinationCard(options, ref, report) }
                        }
                        val groups = groupedDiff(planState.plan.diff)
                        if (groups.isEmpty()) {
                            item(key = "no-diff") { EditorBanner(Icons.Rounded.Info, stringResource(R.string.edit_review_no_visible_changes)) }
                        }
                        groups.forEach { (group, changes) ->
                            item(key = "group-${group.name}") { DiffGroup(group, changes) }
                        }
                    }
                }
            }
            if (state.save is SaveState.Saving) SavingOverlay(state.save.mode)
        }
    }
}

@Composable
private fun ReviewBar(content: @Composable () -> Unit) {
    Surface(color = MaterialTheme.colorScheme.surfaceContainer) {
        Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
            Column(
                Modifier
                    .widthIn(max = 720.dp)
                    .fillMaxWidth()
                    .navigationBarsPadding()
                    .padding(horizontal = 16.dp, vertical = 12.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) { content() }
        }
    }
}

@Composable
private fun ReviewHeader(ref: PhotoRef, report: MetadataReport, plan: PlanState) {
    val count = (plan as? PlanState.Ready)?.plan?.diff?.size
    Row(horizontalArrangement = Arrangement.spacedBy(16.dp), verticalAlignment = Alignment.CenterVertically) {
        ShapeIcon(
            icon = Icons.Rounded.Difference,
            shape = MaterialShapes.Gem,
            size = 56.dp,
            containerColor = MaterialTheme.colorScheme.tertiaryContainer,
            contentColor = MaterialTheme.colorScheme.onTertiaryContainer,
        )
        Column(Modifier.weight(1f)) {
            Text(
                if (count != null) pluralStringResource(R.plurals.edit_review_count, count, count) else stringResource(R.string.edit_review_title),
                style = MaterialTheme.typography.headlineSmall,
                modifier = Modifier.semantics { heading() },
            )
            Text(
                ref.displayName ?: report.fileName ?: stringResource(R.string.photo_untitled),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Text(
                stringResource(R.string.edit_review_nothing_written),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun PlanningRow() {
    Row(
        Modifier.semantics(mergeDescendants = true) { liveRegion = LiveRegionMode.Polite },
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        LoadingIndicator(Modifier.size(40.dp))
        Text(stringResource(R.string.edit_review_planning), style = MaterialTheme.typography.bodyMedium)
    }
}

@Composable
private fun WarningsBanner(warnings: List<String>) {
    EditorBanner(
        icon = Icons.Rounded.Info,
        title = stringResource(R.string.edit_review_warnings_title),
        text = warnings.first(),
        containerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
        contentColor = MaterialTheme.colorScheme.onSurface,
    ) {
        warnings.drop(1).forEach { Text(it, style = MaterialTheme.typography.bodyMedium) }
    }
}

/** One metadata block's changes, each with its kind (icon, color and label) and before and after. */
@Composable
internal fun DiffGroup(group: DirectoryGroup, changes: List<FieldDiff>) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(
            stringResource(group.labelRes),
            style = MaterialTheme.typography.titleSmall,
            color = MaterialTheme.colorScheme.primary,
            modifier = Modifier.padding(start = 4.dp).semantics { heading() },
        )
        Surface(shape = MaterialTheme.shapes.large, color = MaterialTheme.colorScheme.surfaceContainerLow) {
            Column {
                changes.forEachIndexed { index, change ->
                    if (index > 0) HorizontalDivider(Modifier.padding(horizontal = 16.dp), color = MaterialTheme.colorScheme.outlineVariant)
                    DiffRow(change)
                }
            }
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
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically) {
            Text(change.label, style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f))
            DiffKindChip(change.kind)
        }
        when (change.kind) {
            FieldDiff.Kind.Added -> ValueBox(stringResource(R.string.edit_after), change.after, change.kind, Modifier.fillMaxWidth())
            FieldDiff.Kind.Removed -> ValueBox(stringResource(R.string.edit_before), change.before, change.kind, Modifier.fillMaxWidth(), struck = true)
            FieldDiff.Kind.Changed -> BeforeAfter(
                before = { m -> ValueBox(stringResource(R.string.edit_before), change.before, null, m) },
                after = { m -> ValueBox(stringResource(R.string.edit_after), change.after, change.kind, m) },
            )
        }
    }
}

@Composable
private fun ValueBox(label: String, value: String?, kind: FieldDiff.Kind?, modifier: Modifier, struck: Boolean = false) {
    DiffValueBox(label, kind, modifier) {
        Text(
            value.orEmpty().ifEmpty { stringResource(R.string.history_diff_empty_value) },
            style = ExifLabTheme.extendedTypography.monoMedium.let { if (struck) it.copy(textDecoration = TextDecoration.LineThrough) else it },
        )
    }
}

/** Where the edit goes and why: in place, a copy (read-only photos) or a sidecar (RAW, unsupported formats). */
@Composable
private fun DestinationCard(options: SaveOptions, ref: PhotoRef, report: MetadataReport) {
    val format = report.format.displayName.takeIf { report.format != ImageFormat.Unknown } ?: ref.format.displayName
    val (icon, title, body) = when (options.reason) {
        SaveReason.Writable -> Triple(Icons.Rounded.Save, stringResource(R.string.edit_dest_in_place_title), stringResource(R.string.edit_dest_in_place_body))
        SaveReason.ReadOnly -> Triple(Icons.Rounded.SaveAs, stringResource(R.string.edit_dest_copy_title), stringResource(R.string.edit_dest_copy_body))
        SaveReason.Raw -> Triple(Icons.Rounded.Description, stringResource(R.string.edit_dest_sidecar_title), stringResource(R.string.edit_dest_raw_body))
        SaveReason.UnsupportedFormat -> Triple(
            Icons.Rounded.Description,
            stringResource(R.string.edit_dest_sidecar_title),
            stringResource(R.string.edit_dest_unsupported_body, format),
        )
    }
    EditorBanner(
        icon = icon,
        title = title,
        text = body,
        containerColor = MaterialTheme.colorScheme.secondaryContainer,
        contentColor = MaterialTheme.colorScheme.onSecondaryContainer,
    ) {
        if (options.suggestOpenOriginal) {
            Row(Modifier.padding(top = 8.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Icon(Icons.Rounded.FileOpen, contentDescription = null, modifier = Modifier.size(20.dp))
                Text(stringResource(R.string.edit_dest_open_original_hint), style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f))
            }
        }
    }
}

@Composable
private fun FailureBanner(failure: SaveFailure, onDismiss: () -> Unit) {
    val text = when (failure) {
        is SaveFailure.Error -> stringResource(failure.error.messageRes) +
            if (failure.rolledBack) " " + stringResource(R.string.edit_failure_rolled_back) else ""
        is SaveFailure.Rejected -> stringResource(
            when (failure.reason) {
                SaveRejection.ReadOnly -> R.string.edit_failure_read_only
                SaveRejection.UseSidecar -> R.string.edit_failure_use_sidecar
                SaveRejection.UnsupportedFormat -> R.string.edit_failure_unsupported
                SaveRejection.NotRestorable, SaveRejection.NotFound -> R.string.history_error_unknown
                SaveRejection.SidecarHasDates -> R.string.edit_failure_sidecar_dates
            },
        )
        SaveFailure.NoDestination -> stringResource(R.string.edit_failure_no_destination)
        SaveFailure.NoPlan -> stringResource(R.string.edit_failure_no_plan)
    }
    EditorBanner(
        icon = Icons.Rounded.ErrorOutline,
        title = stringResource(R.string.edit_failure_title),
        text = text,
        containerColor = MaterialTheme.colorScheme.errorContainer,
        contentColor = MaterialTheme.colorScheme.onErrorContainer,
        announce = true,
    ) {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
            TextButton(onClick = onDismiss, modifier = Modifier.heightIn(min = 48.dp)) {
                Icon(Icons.Rounded.Close, contentDescription = null, modifier = Modifier.size(ButtonDefaults.IconSize))
                Text(stringResource(R.string.edit_failure_dismiss), modifier = Modifier.padding(start = ButtonDefaults.IconSpacing))
            }
        }
    }
}

@Composable
private fun SavedResult(saved: SaveState.SavedWithSkipped) {
    val colors = ExifLabTheme.extendedColors
    EditorBanner(
        icon = Icons.Rounded.CheckCircle,
        title = stringResource(R.string.edit_saved_title),
        text = pluralStringResource(R.plurals.edit_saved_skipped, saved.skipped.size, saved.skipped.size),
        containerColor = colors.addedContainer,
        contentColor = colors.onAddedContainer,
        announce = true,
    ) {
        saved.skipped.forEach { Text("• $it", style = MaterialTheme.typography.bodyMedium) }
    }
}

/** Covers the review while saving: an expressive indicator and what is happening. */
@Composable
private fun SavingOverlay(mode: SaveMode) {
    Box(
        Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.scrim.copy(alpha = SCRIM_ALPHA))
            // Swallows touches so nothing underneath can be pressed while saving.
            .clickable(interactionSource = remember { MutableInteractionSource() }, indication = null, enabled = true, onClick = {}),
        contentAlignment = Alignment.Center,
    ) {
        Surface(shape = MaterialTheme.shapes.extraLarge, color = MaterialTheme.colorScheme.surfaceContainerHigh, modifier = Modifier.padding(32.dp)) {
            Column(
                Modifier
                    .padding(28.dp)
                    .semantics(mergeDescendants = true) { liveRegion = LiveRegionMode.Polite },
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(16.dp),
            ) {
                ContainedLoadingIndicator(Modifier.size(72.dp))
                Text(
                    stringResource(
                        when (mode) {
                            SaveMode.InPlace -> R.string.edit_saving_in_place
                            SaveMode.Copy -> R.string.edit_saving_copy
                            SaveMode.Sidecar -> R.string.edit_saving_sidecar
                        },
                    ),
                    style = MaterialTheme.typography.titleLarge,
                )
                Text(
                    stringResource(if (mode == SaveMode.Sidecar) R.string.edit_saving_sidecar_body else R.string.edit_saving_body),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    textAlign = TextAlign.Center,
                )
            }
        }
    }
}

val SaveMode.icon: ImageVector
    get() = when (this) {
        SaveMode.InPlace -> Icons.Rounded.Save
        SaveMode.Copy -> Icons.Rounded.SaveAs
        SaveMode.Sidecar -> Icons.Rounded.Description
    }

/** [diff] by metadata block, in the order the review shows them. */
internal fun groupedDiff(diff: List<FieldDiff>): List<Pair<DirectoryGroup, List<FieldDiff>>> =
    diff.groupBy { it.group }.toList().sortedBy { groupOrder.indexOf(it.first).let { i -> if (i < 0) Int.MAX_VALUE else i } }

private val groupOrder = listOf(
    DirectoryGroup.Exif, DirectoryGroup.Gps, DirectoryGroup.Xmp, DirectoryGroup.Iptc,
    DirectoryGroup.MakerNote, DirectoryGroup.Icc, DirectoryGroup.Container, DirectoryGroup.File, DirectoryGroup.Other,
)

private const val SCRIM_ALPHA = 0.32f
