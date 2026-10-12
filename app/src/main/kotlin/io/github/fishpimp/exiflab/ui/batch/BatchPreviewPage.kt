package io.github.fishpimp.exiflab.ui.batch

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
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
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Block
import androidx.compose.material.icons.rounded.CheckCircle
import androidx.compose.material.icons.rounded.Description
import androidx.compose.material.icons.rounded.ErrorOutline
import androidx.compose.material.icons.rounded.FileCopy
import androidx.compose.material.icons.rounded.Folder
import androidx.compose.material.icons.rounded.FolderOpen
import androidx.compose.material.icons.rounded.Info
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.LoadingIndicator
import androidx.compose.material3.MaterialShapes
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.adaptive.currentWindowAdaptiveInfo
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.window.core.layout.WindowSizeClass
import io.github.fishpimp.exiflab.R
import io.github.fishpimp.exiflab.data.batch.BatchItemReason
import io.github.fishpimp.exiflab.data.batch.BatchOperation
import io.github.fishpimp.exiflab.metadata.edit.StripCategory
import io.github.fishpimp.exiflab.ui.components.ScreenScaffold
import io.github.fishpimp.exiflab.ui.edit.DiffGroup
import io.github.fishpimp.exiflab.ui.edit.EditorBanner
import io.github.fishpimp.exiflab.ui.edit.EditorCard
import io.github.fishpimp.exiflab.ui.edit.durationText
import io.github.fishpimp.exiflab.ui.edit.groupedDiff
import io.github.fishpimp.exiflab.ui.edit.zoneName
import io.github.fishpimp.exiflab.ui.map.LatLng
import io.github.fishpimp.exiflab.ui.map.formatted
import io.github.fishpimp.exiflab.ui.photo.breakableFileName
import java.time.Duration
import java.time.ZoneId

/**
 * The batch preview: what the operation is, how each kind of photo will be saved, the folder for
 * copies of read-only photos, warnings aggregated over all photos and the first photo's changes.
 */
@Composable
internal fun BatchPreviewPage(
    state: BatchEditUiState,
    artist: String,
    copyright: String,
    actions: BatchEditActions,
    snackbarHostState: SnackbarHostState,
    modifier: Modifier = Modifier,
) {
    val kind = state.kind
    val operation = state.draft.operation(kind, artist, copyright)
    val preview = state.preview
    val hasFolder = state.copyFolder != null
    ScreenScaffold(
        title = stringResource(R.string.batch_preview_title),
        subtitle = stringResource(kind.labelRes),
        onBack = actions.onBack,
        snackbarHost = { SnackbarHost(snackbarHostState) },
        modifier = modifier,
    ) { padding ->
        Column(Modifier.fillMaxSize().padding(padding)) {
            val overview: LazyListScope.() -> Unit = {
                item(key = "operation") { OperationCard(state, operation) }
                if (preview != null) {
                    if (!preview.isComplete) item(key = "checking") { CheckingRow(preview) }
                    item(key = "routes") { RoutesCard(preview, hasFolder) }
                    if (preview.needsFolder > 0) {
                        item(key = "folder") { FolderCard(preview.needsFolder, state.copyFolder, actions) }
                    }
                    if (preview.warnings.isNotEmpty() || preview.unreadable > 0) item(key = "warnings") { WarningsCard(preview) }
                }
            }
            val example: LazyListScope.() -> Unit = {
                if (preview != null) {
                    item(key = "sample-title") { SampleTitle(preview) }
                    preview.sample?.let { sample ->
                        groupedDiff(sample.diff).forEach { (group, changes) ->
                            item(key = "sample-${group.name}") { DiffGroup(group, changes) }
                        }
                    }
                }
            }
            val listPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 8.dp, bottom = 24.dp)
            val spacing = Arrangement.spacedBy(16.dp)
            val twoPane = currentWindowAdaptiveInfo().windowSizeClass.isWidthAtLeastBreakpoint(WindowSizeClass.WIDTH_DP_EXPANDED_LOWER_BOUND)
            if (twoPane) {
                // Large screens: the plan on the left, the example photo's changes beside it.
                Row(Modifier.weight(1f).fillMaxWidth().padding(horizontal = 8.dp), horizontalArrangement = Arrangement.Center) {
                    LazyColumn(Modifier.weight(1f).widthIn(max = BatchColumnMaxWidth).fillMaxSize(), contentPadding = listPadding, verticalArrangement = spacing) {
                        overview()
                    }
                    LazyColumn(Modifier.weight(1f).widthIn(max = BatchColumnMaxWidth).fillMaxSize(), contentPadding = listPadding, verticalArrangement = spacing) {
                        example()
                    }
                }
            } else {
                Box(Modifier.weight(1f).fillMaxWidth(), contentAlignment = Alignment.TopCenter) {
                    LazyColumn(
                        modifier = Modifier.widthIn(max = BatchColumnMaxWidth).fillMaxSize(),
                        contentPadding = listPadding,
                        verticalArrangement = spacing,
                    ) {
                        overview()
                        example()
                    }
                }
            }
            StartBar(preview, hasFolder, state.starting, actions.onStart)
        }
    }
}

@Composable
private fun OperationCard(state: BatchEditUiState, operation: BatchOperation?) {
    EditorCard(
        icon = state.kind.icon,
        shape = MaterialShapes.Cookie9Sided,
        title = stringResource(state.kind.labelRes),
        subtitle = pluralStringResource(R.plurals.photo_count, state.count, state.count),
    ) {
        if (operation != null) {
            Text(
                operationSummary(operation),
                style = MaterialTheme.typography.bodyLarge,
                modifier = Modifier.padding(horizontal = 20.dp),
            )
        }
    }
}

/** The operation in one line: "Location: 59.32930, 18.06860", "Shift time +1 h", "Location, Dates". */
@Composable
fun operationSummary(operation: BatchOperation): String = when (operation) {
    is BatchOperation.SetLocation -> stringResource(R.string.edit_summary_location, LatLng(operation.latitude, operation.longitude).formatted())
    is BatchOperation.ShiftTime -> stringResource(R.string.edit_summary_shift_time, durationText(Duration.ofSeconds(operation.seconds)))
    is BatchOperation.FixTimeZone -> stringResource(
        R.string.edit_summary_time_zone,
        zoneName(ZoneId.of(operation.cameraZone)),
        zoneName(ZoneId.of(operation.actualZone)),
    )
    is BatchOperation.SetAuthorship -> listOfNotNull(
        operation.artist?.let { stringResource(R.string.edit_summary_author, it) },
        operation.copyright?.let { stringResource(R.string.edit_summary_copyright, it) },
    ).joinToString("\n")
    is BatchOperation.Strip -> operation.stripCategories.let { categories ->
        if (StripCategory.All in categories) {
            stringResource(StripCategory.All.titleRes)
        } else {
            categories.map { stringResource(it.titleRes) }.joinToString(", ")
        }
    }
}

@Composable
private fun CheckingRow(preview: BatchPreview) {
    Row(
        Modifier
            .fillMaxWidth()
            .semantics(mergeDescendants = true) { liveRegion = LiveRegionMode.Polite }
            .padding(horizontal = 4.dp),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        LoadingIndicator(Modifier.size(40.dp))
        Text(
            pluralStringResource(R.plurals.batch_checking, preview.total, preview.checked, preview.total),
            style = MaterialTheme.typography.bodyMedium,
            modifier = Modifier.weight(1f),
        )
    }
}

/** How many photos are written in place, to sidecars, as copies, and how many are skipped (and why). */
@Composable
private fun RoutesCard(preview: BatchPreview, hasFolder: Boolean) {
    Surface(shape = MaterialTheme.shapes.extraLarge, color = MaterialTheme.colorScheme.surfaceContainerLow) {
        Column(Modifier.padding(vertical = 16.dp)) {
            Text(
                stringResource(R.string.batch_routes_title),
                style = MaterialTheme.typography.titleLarge,
                modifier = Modifier.padding(horizontal = 20.dp, vertical = 4.dp).semantics { heading() },
            )
            val rows = buildList {
                if (preview.inPlace > 0) {
                    add(RouteLine(Icons.Rounded.CheckCircle, R.plurals.batch_route_in_place, preview.inPlace, R.string.batch_route_in_place_body))
                }
                val sidecars = preview.sidecars(hasFolder)
                if (sidecars > 0) add(RouteLine(Icons.Rounded.Description, R.plurals.batch_route_sidecar, sidecars, R.string.batch_route_sidecar_body))
                val copies = preview.copies(hasFolder)
                if (copies > 0) add(RouteLine(Icons.Rounded.FileCopy, R.plurals.batch_route_copy, copies, R.string.batch_route_copy_body))
                val skipped = preview.skipped(hasFolder)
                if (skipped > 0) add(RouteLine(Icons.Rounded.Block, R.plurals.batch_route_skipped, skipped, null))
            }
            rows.forEachIndexed { index, row ->
                if (index > 0) HorizontalDivider(Modifier.padding(horizontal = 20.dp), color = MaterialTheme.colorScheme.outlineVariant)
                RouteRow(row) {
                    if (row.icon == Icons.Rounded.Block) SkipReasons(preview, hasFolder)
                }
            }
        }
    }
}

private class RouteLine(val icon: ImageVector, val plural: Int, val count: Int, val body: Int?)

@Composable
private fun RouteRow(line: RouteLine, extra: @Composable () -> Unit) {
    Row(
        Modifier
            .fillMaxWidth()
            .semantics(mergeDescendants = true) {}
            .padding(horizontal = 20.dp, vertical = 12.dp),
        horizontalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        Icon(line.icon, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(pluralStringResource(line.plural, line.count, line.count), style = MaterialTheme.typography.titleMedium)
            line.body?.let {
                Text(stringResource(it), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            extra()
        }
    }
}

@Composable
private fun SkipReasons(preview: BatchPreview, hasFolder: Boolean) {
    val reasons = buildList {
        if (preview.unchanged > 0) add(preview.unchanged to BatchItemReason.NothingToChange)
        preview.skipReasons.forEach { (reason, count) -> add(count to reason) }
        if (!hasFolder) {
            if (preview.copiesNeedingFolder > 0) add(preview.copiesNeedingFolder to BatchItemReason.ReadOnly)
            if (preview.sidecarsNeedingFolder > 0) add(preview.sidecarsNeedingFolder to BatchItemReason.NoSidecarPlace)
        }
    }
    reasons.forEach { (count, reason) ->
        Text(
            pluralStringResource(R.plurals.batch_count_reason, count, count, stringResource(reason.messageRes)),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

/** Read-only photos: choose a folder for edited copies, or skip them. */
@Composable
private fun FolderCard(count: Int, folder: CopyFolder?, actions: BatchEditActions) {
    EditorCard(
        icon = if (folder != null) Icons.Rounded.Folder else Icons.Rounded.FolderOpen,
        shape = MaterialShapes.Square,
        title = stringResource(R.string.batch_folder_title),
        subtitle = pluralStringResource(R.plurals.batch_folder_needed, count, count),
        iconContainer = MaterialTheme.colorScheme.secondaryContainer,
        iconContent = MaterialTheme.colorScheme.onSecondaryContainer,
    ) {
        Column(Modifier.padding(horizontal = 20.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text(
                if (folder != null) {
                    stringResource(R.string.batch_folder_chosen, folder.name ?: stringResource(R.string.batch_folder_unnamed))
                } else {
                    stringResource(R.string.batch_folder_skipping)
                },
                style = MaterialTheme.typography.bodyLarge,
            )
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(onClick = actions.onChooseFolder, modifier = Modifier.heightIn(min = 48.dp)) {
                    Icon(Icons.Rounded.FolderOpen, contentDescription = null, modifier = Modifier.size(ButtonDefaults.IconSize))
                    Text(
                        stringResource(if (folder != null) R.string.batch_folder_change else R.string.batch_folder_choose),
                        modifier = Modifier.padding(start = ButtonDefaults.IconSpacing),
                    )
                }
                if (folder != null) {
                    TextButton(onClick = actions.onClearFolder, modifier = Modifier.heightIn(min = 48.dp)) {
                        Text(stringResource(R.string.batch_folder_skip))
                    }
                }
            }
        }
    }
}

/** Planner warnings, one line per kind with how many photos it concerns. */
@Composable
private fun WarningsCard(preview: BatchPreview) {
    val lines = buildList {
        preview.warnings.forEach { warning ->
            add(
                when (warning.kind) {
                    WarningCount.Kind.NoCaptureDate -> pluralStringResource(R.plurals.batch_warning_no_date, warning.count, warning.count)
                    WarningCount.Kind.Other -> pluralStringResource(R.plurals.batch_count_reason, warning.count, warning.count, warning.text.orEmpty())
                },
            )
        }
        if (preview.unreadable > 0) add(pluralStringResource(R.plurals.batch_warning_unreadable, preview.unreadable, preview.unreadable))
    }
    EditorBanner(
        icon = if (preview.unreadable > 0) Icons.Rounded.ErrorOutline else Icons.Rounded.Info,
        title = stringResource(R.string.edit_review_warnings_title),
        text = lines.first(),
        containerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
        contentColor = MaterialTheme.colorScheme.onSurface,
    ) {
        lines.drop(1).forEach { Text(it, style = MaterialTheme.typography.bodyMedium) }
    }
}

@Composable
private fun SampleTitle(preview: BatchPreview) {
    val sample = preview.sample
    Column(Modifier.padding(top = 8.dp, start = 4.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text(
            stringResource(R.string.batch_sample_title),
            style = MaterialTheme.typography.titleLarge,
            modifier = Modifier.semantics { heading() },
        )
        Text(
            when {
                sample != null -> stringResource(R.string.batch_sample_body, breakableFileName(sample.ref.displayName ?: stringResource(R.string.photo_untitled)))
                preview.isComplete -> stringResource(R.string.batch_sample_none)
                else -> stringResource(R.string.batch_sample_waiting)
            },
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

/** "Edit 40 photos", or why nothing would be written. */
@Composable
private fun StartBar(preview: BatchPreview?, hasFolder: Boolean, starting: Boolean, onStart: () -> Unit) {
    val written = preview?.written(hasFolder) ?: 0
    Surface(color = MaterialTheme.colorScheme.surfaceContainer, modifier = Modifier.fillMaxWidth()) {
        Box(Modifier.fillMaxWidth().navigationBarsPadding().padding(16.dp), contentAlignment = Alignment.Center) {
            Button(
                onClick = onStart,
                enabled = written > 0 && !starting,
                modifier = Modifier.widthIn(max = BatchColumnMaxWidth).fillMaxWidth().heightIn(min = 56.dp),
            ) {
                if (starting) {
                    LoadingIndicator(Modifier.size(24.dp))
                } else {
                    Icon(Icons.Rounded.PlayArrow, contentDescription = null, modifier = Modifier.size(ButtonDefaults.IconSize))
                }
                Text(
                    if (written > 0) pluralStringResource(R.plurals.batch_start, written, written) else stringResource(R.string.batch_start_nothing),
                    modifier = Modifier.padding(start = ButtonDefaults.IconSpacing),
                )
            }
        }
    }
}
