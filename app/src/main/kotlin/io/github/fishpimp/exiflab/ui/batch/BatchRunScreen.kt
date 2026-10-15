package io.github.fishpimp.exiflab.ui.batch

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.Done
import androidx.compose.material.icons.rounded.Restore
import androidx.compose.material.icons.rounded.SearchOff
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularWavyProgressIndicator
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
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
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalLocale
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
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import io.github.fishpimp.exiflab.R
import io.github.fishpimp.exiflab.data.batch.BatchItemStatus
import io.github.fishpimp.exiflab.data.batch.BatchJobItem
import io.github.fishpimp.exiflab.data.batch.BatchJobState
import io.github.fishpimp.exiflab.data.photos.PhotoRef
import io.github.fishpimp.exiflab.designsystem.component.EmptyState
import io.github.fishpimp.exiflab.ui.components.PhotoThumbnail
import io.github.fishpimp.exiflab.ui.components.ScreenScaffold
import io.github.fishpimp.exiflab.ui.photo.breakableFileName
import java.text.NumberFormat
import java.util.Locale

/**
 * A batch job: its progress while it runs (a wavy progress ring, counts, the file being written,
 * Cancel), then the summary with every photo's outcome, filters, "Restore all" and "Done".
 * Everything comes from Room, so leaving and reopening the app shows the same job.
 */
@Composable
fun BatchRunScreen(
    jobId: String,
    onBack: () -> Unit,
    onDone: () -> Unit,
    onOpenPhoto: (PhotoRef) -> Unit,
    modifier: Modifier = Modifier,
    viewModel: BatchRunViewModel = viewModel(key = "batch-run:$jobId", factory = BatchRunViewModel.factory(jobId)),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val snackbarHostState = remember { SnackbarHostState() }
    val resources = LocalResources.current
    LaunchedEffect(viewModel) {
        viewModel.messages.collect { message ->
            when (message) {
                is BatchRunMessage.Restored -> {
                    val result = message.result
                    val total = result.restored + result.failed
                    val text = when {
                        total == 0 -> resources.getString(R.string.batch_restore_nothing)
                        result.failed == 0 -> resources.getQuantityString(R.plurals.history_batch_restored, result.restored, result.restored)
                        else -> resources.getQuantityString(R.plurals.history_batch_restored_partial, total, result.restored, total)
                    }
                    snackbarHostState.showSnackbar(text)
                }
            }
        }
    }
    BatchRunContent(
        state = state,
        actions = BatchRunActions(
            onBack = onBack,
            onDone = onDone,
            onCancel = viewModel::cancel,
            onRestoreAll = viewModel::restoreAll,
            onFilter = viewModel::setFilter,
            onOpenPhoto = onOpenPhoto,
        ),
        snackbarHostState = snackbarHostState,
        modifier = modifier,
    )
}

@Immutable
data class BatchRunActions(
    val onBack: () -> Unit = {},
    val onDone: () -> Unit = {},
    val onCancel: () -> Unit = {},
    val onRestoreAll: () -> Unit = {},
    val onFilter: (BatchItemStatus?) -> Unit = {},
    val onOpenPhoto: (PhotoRef) -> Unit = {},
)

/** Stateless progress or summary, rendered directly by screenshot tests. */
@Composable
fun BatchRunContent(
    state: BatchRunUiState,
    actions: BatchRunActions,
    modifier: Modifier = Modifier,
    snackbarHostState: SnackbarHostState = remember { SnackbarHostState() },
) {
    val job = state.job
    val done = job?.state?.isDone == true
    ScreenScaffold(
        title = when {
            job == null -> stringResource(R.string.batch_run_title)
            job.state == BatchJobState.Cancelled -> stringResource(R.string.batch_summary_cancelled_title)
            done -> stringResource(R.string.batch_summary_title)
            else -> stringResource(R.string.batch_run_title)
        },
        subtitle = job?.title?.ifBlank { null },
        onBack = actions.onBack,
        snackbarHost = { SnackbarHost(snackbarHostState) },
        modifier = modifier,
    ) { padding ->
        Box(Modifier.fillMaxSize().padding(padding), contentAlignment = Alignment.TopCenter) {
            when {
                state.isLoading -> Unit
                job == null -> Box(Modifier.fillMaxSize().verticalScroll(rememberScrollState()), contentAlignment = Alignment.Center) {
                    EmptyState(
                        icon = Icons.Rounded.SearchOff,
                        shape = MaterialShapes.Cookie6Sided,
                        title = stringResource(R.string.batch_missing_title),
                        body = stringResource(R.string.batch_run_missing_body),
                    )
                }
                done -> BatchSummary(state, actions)
                else -> BatchProgressBody(state, actions.onCancel)
            }
        }
    }
}

@Composable
private fun BatchProgressBody(state: BatchRunUiState, onCancel: () -> Unit) {
    val job = state.job ?: return
    val total = state.total.coerceAtLeast(1)
    val fraction = state.done.toFloat() / total
    val waiting = job.state == BatchJobState.Queued
    val stopping = job.cancelRequested
    val progressText = pluralStringResource(R.plurals.batch_notification_progress, state.total, state.done, state.total)
    Column(
        Modifier
            .widthIn(max = BatchColumnMaxWidth)
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 24.dp, vertical = 16.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(20.dp),
    ) {
        Box(contentAlignment = Alignment.Center, modifier = Modifier.padding(top = 16.dp)) {
            if (waiting) {
                CircularWavyProgressIndicator(Modifier.size(PROGRESS_SIZE).semantics { contentDescription = progressText })
            } else {
                CircularWavyProgressIndicator(
                    progress = { fraction },
                    modifier = Modifier.size(PROGRESS_SIZE).semantics {
                        contentDescription = progressText
                        progressBarRangeInfo = ProgressBarRangeInfo(fraction, 0f..1f)
                    },
                )
            }
            Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.padding(32.dp)) {
                Text(percentText(fraction, LocalLocale.current.platformLocale), style = MaterialTheme.typography.displaySmall)
            }
        }
        Text(
            when {
                stopping -> stringResource(R.string.batch_notification_cancelling)
                waiting -> stringResource(R.string.batch_run_waiting)
                else -> progressText
            },
            style = MaterialTheme.typography.headlineSmall,
            textAlign = TextAlign.Center,
            modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite },
        )
        CountChips(state, includeRunning = false)
        val current = state.runningNames
        if (current.isNotEmpty() && !waiting) {
            Surface(shape = MaterialTheme.shapes.large, color = MaterialTheme.colorScheme.surfaceContainerHigh, modifier = Modifier.fillMaxWidth()) {
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text(stringResource(R.string.batch_run_current), style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    current.forEach { name -> Text(breakableFileName(name), style = MaterialTheme.typography.bodyLarge) }
                }
            }
        }
        Text(
            stringResource(R.string.batch_run_background),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
        )
        OutlinedButton(onClick = onCancel, enabled = !stopping, modifier = Modifier.heightIn(min = 48.dp)) {
            Icon(Icons.Rounded.Close, contentDescription = null, modifier = Modifier.size(ButtonDefaults.IconSize))
            Text(stringResource(R.string.batch_cancel), modifier = Modifier.padding(start = ButtonDefaults.IconSpacing))
        }
    }
}

/** Written, failed and skipped so far, each with its icon. */
@Composable
private fun CountChips(state: BatchRunUiState, includeRunning: Boolean) {
    val statuses = listOf(BatchItemStatus.Saved, BatchItemStatus.Sidecar, BatchItemStatus.Copy, BatchItemStatus.Skipped, BatchItemStatus.Failed) +
        if (includeRunning) listOf(BatchItemStatus.Pending) else emptyList()
    FlowRow(
        horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.CenterHorizontally),
        verticalArrangement = Arrangement.spacedBy(8.dp),
        modifier = Modifier.fillMaxWidth(),
    ) {
        statuses.forEach { status ->
            val count = state.count(status)
            if (count > 0) {
                val label = "${stringResource(status.labelRes)}: $count"
                Box(Modifier.semantics(mergeDescendants = true) { contentDescription = label }) { CountChip(status, count) }
            }
        }
    }
}

@Composable
private fun CountChip(status: BatchItemStatus, count: Int) {
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        BatchStatusChip(status)
        Text(count.toString(), style = MaterialTheme.typography.titleMedium)
    }
}

@Composable
private fun BatchSummary(state: BatchRunUiState, actions: BatchRunActions) {
    var confirmRestore by rememberSaveable { mutableStateOf(false) }
    val visible = state.visibleItems
    LazyColumn(
        modifier = Modifier.widthIn(max = BatchColumnMaxWidth).fillMaxSize(),
        contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 8.dp, bottom = 24.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        item(key = "totals", contentType = "totals") { Totals(state, actions, onRestore = { confirmRestore = true }) }
        item(key = "filters", contentType = "filters") { Filters(state, actions.onFilter) }
        items(visible, key = { it.position }, contentType = { "item" }) { item ->
            SummaryRow(item, onOpen = { actions.onOpenPhoto(item.photoRef()) })
        }
    }
    if (confirmRestore) {
        val count = state.count(BatchItemStatus.Saved) + state.count(BatchItemStatus.Sidecar)
        AlertDialog(
            onDismissRequest = { confirmRestore = false },
            icon = { Icon(Icons.Rounded.Restore, contentDescription = null) },
            title = { Text(stringResource(R.string.history_batch_restore_title)) },
            text = { Text(pluralStringResource(R.plurals.history_batch_restore_body, count, count)) },
            confirmButton = {
                TextButton(onClick = {
                    confirmRestore = false
                    actions.onRestoreAll()
                }) { Text(stringResource(R.string.history_action_restore_all)) }
            },
            dismissButton = { TextButton(onClick = { confirmRestore = false }) { Text(stringResource(R.string.action_cancel)) } },
        )
    }
}

@Composable
private fun Totals(state: BatchRunUiState, actions: BatchRunActions, onRestore: () -> Unit) {
    Surface(shape = MaterialTheme.shapes.extraLarge, color = MaterialTheme.colorScheme.surfaceContainerLow, modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
            Text(
                pluralStringResource(R.plurals.batch_summary_written, state.total, state.written, state.total),
                style = MaterialTheme.typography.headlineSmall,
                modifier = Modifier.semantics { heading() },
            )
            CountChips(state, includeRunning = false)
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                if (state.canRestore || state.restoring) {
                    OutlinedButton(onClick = onRestore, enabled = !state.restoring, modifier = Modifier.heightIn(min = 48.dp)) {
                        if (state.restoring) {
                            LoadingIndicator(Modifier.size(ButtonDefaults.IconSize))
                        } else {
                            Icon(Icons.Rounded.Restore, contentDescription = null, modifier = Modifier.size(ButtonDefaults.IconSize))
                        }
                        Text(stringResource(R.string.history_action_restore_all), modifier = Modifier.padding(start = ButtonDefaults.IconSpacing))
                    }
                }
                Button(onClick = actions.onDone, modifier = Modifier.heightIn(min = 48.dp)) {
                    Icon(Icons.Rounded.Done, contentDescription = null, modifier = Modifier.size(ButtonDefaults.IconSize))
                    Text(stringResource(R.string.edit_review_done), modifier = Modifier.padding(start = ButtonDefaults.IconSpacing))
                }
            }
            Text(
                stringResource(R.string.batch_summary_history),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

/** Filter chips by outcome; only outcomes that occurred are offered. */
@Composable
private fun Filters(state: BatchRunUiState, onFilter: (BatchItemStatus?) -> Unit) {
    val outcomes = listOf(BatchItemStatus.Saved, BatchItemStatus.Sidecar, BatchItemStatus.Copy, BatchItemStatus.Skipped, BatchItemStatus.Failed)
        .filter { state.count(it) > 0 }
    FlowRow(
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalArrangement = Arrangement.spacedBy(0.dp),
        modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
    ) {
        FilterChip(
            selected = state.filter == null,
            onClick = { onFilter(null) },
            label = { Text(stringResource(R.string.batch_filter_all, state.items.size)) },
            leadingIcon = if (state.filter == null) {
                { Icon(Icons.Rounded.Done, contentDescription = null, modifier = Modifier.size(FilterChipDefaults.IconSize)) }
            } else {
                null
            },
            modifier = Modifier.heightIn(min = 48.dp),
        )
        outcomes.forEach { status ->
            FilterChip(
                selected = state.filter == status,
                onClick = { onFilter(if (state.filter == status) null else status) },
                label = { Text(stringResource(R.string.batch_filter_status, stringResource(status.labelRes), state.count(status))) },
                leadingIcon = { Icon(status.icon, contentDescription = null, modifier = Modifier.size(FilterChipDefaults.IconSize)) },
                modifier = Modifier.heightIn(min = 48.dp),
            )
        }
    }
}

/** One photo: thumbnail, name, outcome chip and, for skipped or failed photos, why. */
@Composable
private fun SummaryRow(item: BatchJobItem, onOpen: () -> Unit) {
    val ref = remember(item.photoUri) { item.photoRef() }
    val name = item.displayName ?: stringResource(R.string.photo_untitled)
    // Reasons are written to follow "3 photos:", so they start in lower case on their own.
    val reason = item.reasonRes?.let { stringResource(it) }?.replaceFirstChar { it.uppercaseChar() }
    Column {
        Row(
            Modifier
                .fillMaxWidth()
                .clip(MaterialTheme.shapes.large)
                .clickable(role = Role.Button, onClickLabel = stringResource(R.string.selection_open), onClick = onOpen)
                .semantics(mergeDescendants = true) {}
                .heightIn(min = 72.dp)
                .padding(horizontal = 8.dp, vertical = 8.dp),
            horizontalArrangement = Arrangement.spacedBy(16.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            PhotoThumbnail(
                ref = ref,
                contentDescription = null,
                showFormatLabel = false,
                modifier = Modifier.size(56.dp).clip(MaterialTheme.shapes.medium),
            )
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(remember(name) { breakableFileName(name) }, style = MaterialTheme.typography.titleMedium)
                BatchStatusChip(item.status)
                if (reason != null) {
                    Text(reason, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
        }
        HorizontalDivider(Modifier.padding(start = 80.dp), color = MaterialTheme.colorScheme.outlineVariant)
    }
}

private val PROGRESS_SIZE = 200.dp

/** "42%" in English, "42 %" in Swedish; rounded down, so it never reads 100 before the last photo is done. */
internal fun percentText(fraction: Float, locale: Locale): String =
    NumberFormat.getPercentInstance(locale).format((fraction * 100).toInt() / 100.0)
