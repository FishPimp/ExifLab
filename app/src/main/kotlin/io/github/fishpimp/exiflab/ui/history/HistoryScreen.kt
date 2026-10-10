package io.github.fishpimp.exiflab.ui.history

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Block
import androidx.compose.material.icons.rounded.CheckCircle
import androidx.compose.material.icons.rounded.ErrorOutline
import androidx.compose.material.icons.rounded.ExpandLess
import androidx.compose.material.icons.rounded.ExpandMore
import androidx.compose.material.icons.rounded.History
import androidx.compose.material.icons.rounded.PhotoLibrary
import androidx.compose.material.icons.rounded.Restore
import androidx.compose.material.icons.rounded.TouchApp
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialShapes
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.VerticalDivider
import androidx.compose.material3.adaptive.currentWindowAdaptiveInfo
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalResources
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.window.core.layout.WindowSizeClass
import io.github.fishpimp.exiflab.R
import io.github.fishpimp.exiflab.data.history.EditRecord
import io.github.fishpimp.exiflab.data.history.EditState
import io.github.fishpimp.exiflab.data.photos.PhotoRef
import io.github.fishpimp.exiflab.designsystem.component.EmptyState
import io.github.fishpimp.exiflab.designsystem.component.SectionHeader
import io.github.fishpimp.exiflab.ui.components.PhotoThumbnail
import io.github.fishpimp.exiflab.ui.components.ScreenScaffold

/**
 * The History tab. On phones an edit opens [onOpenRecord] (its own screen); on expanded widths
 * the list and the selected edit sit side by side.
 */
@Composable
fun HistoryScreen(
    onOpenRecord: (String) -> Unit,
    onOpenPhoto: (PhotoRef) -> Unit,
    modifier: Modifier = Modifier,
    viewModel: HistoryViewModel = viewModel(factory = HistoryViewModel.Factory),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val snackbarHostState = remember { SnackbarHostState() }
    val resources = LocalResources.current
    LaunchedEffect(viewModel) {
        viewModel.messages.collect { message ->
            when (message) {
                is HistoryMessage.BatchRestored -> {
                    val total = message.restored + message.failed
                    val text = if (message.failed == 0) {
                        resources.getQuantityString(R.plurals.history_batch_restored, message.restored, message.restored)
                    } else {
                        resources.getQuantityString(R.plurals.history_batch_restored_partial, total, message.restored, total)
                    }
                    snackbarHostState.showSnackbar(text)
                }
            }
        }
    }
    val twoPane = currentWindowAdaptiveInfo().windowSizeClass.isWidthAtLeastBreakpoint(WindowSizeClass.WIDTH_DP_EXPANDED_LOWER_BOUND)
    var selectedId by rememberSaveable { mutableStateOf<String?>(null) }
    if (twoPane) {
        // Start the detail pane on the newest edit rather than leaving it blank.
        LaunchedEffect(state.days, selectedId) {
            if (selectedId == null) selectedId = state.days.firstNotNullOfOrNull { day -> day.items.firstRecordId() }
        }
    }
    HistoryContent(
        state = state,
        onOpenRecord = { id -> if (twoPane) selectedId = id else onOpenRecord(id) },
        onRestoreBatch = viewModel::restoreBatch,
        selectedRecordId = selectedId.takeIf { twoPane },
        snackbarHostState = snackbarHostState,
        modifier = modifier,
        detailPane = if (twoPane) {
            { paneModifier ->
                val id = selectedId
                if (id == null) {
                    HistoryDetailPlaceholder(paneModifier)
                } else {
                    HistoryDetailPane(
                        recordId = id,
                        snackbarHostState = snackbarHostState,
                        onOpenPhoto = onOpenPhoto,
                        onOpenRecord = { selectedId = it },
                        modifier = paneModifier,
                    )
                }
            }
        } else {
            null
        },
    )
}

private fun List<HistoryItem>.firstRecordId(): String? = firstNotNullOfOrNull { item ->
    when (item) {
        is HistoryItem.Single -> item.record.id
        is HistoryItem.Batch -> item.records.firstOrNull()?.id
    }
}

/**
 * The History list, stateless. With a [detailPane] the list and the pane share the width
 * (list-detail on expanded windows); without one the list is capped and centered.
 */
@Composable
fun HistoryContent(
    state: HistoryUiState,
    onOpenRecord: (String) -> Unit,
    onRestoreBatch: (String) -> Unit,
    modifier: Modifier = Modifier,
    selectedRecordId: String? = null,
    snackbarHostState: SnackbarHostState = remember { SnackbarHostState() },
    initiallyExpandedBatches: Set<String> = emptySet(),
    detailPane: (@Composable (Modifier) -> Unit)? = null,
) {
    var expanded by rememberSaveable { mutableStateOf(initiallyExpandedBatches.toList()) }
    var confirmBatch by rememberSaveable { mutableStateOf<String?>(null) }
    ScreenScaffold(
        title = stringResource(R.string.nav_history),
        modifier = modifier,
        snackbarHost = { SnackbarHost(snackbarHostState) },
    ) { padding ->
        val list: @Composable (Modifier) -> Unit = { listModifier ->
            HistoryList(
                state = state,
                selectedRecordId = selectedRecordId,
                expandedBatches = expanded.toSet(),
                onToggleBatch = { id -> expanded = if (id in expanded) expanded - id else expanded + id },
                onOpenRecord = onOpenRecord,
                onRestoreBatch = { confirmBatch = it },
                modifier = listModifier,
            )
        }
        when {
            state.isLoading -> Box(Modifier.padding(padding).fillMaxSize())
            state.isEmpty -> EmptyHistory(Modifier.padding(padding))
            detailPane != null -> BoxWithConstraints(Modifier.padding(padding).fillMaxSize()) {
                val listWidth = (maxWidth * 0.42f).coerceIn(340.dp, 460.dp)
                Row(Modifier.fillMaxSize()) {
                    list(Modifier.width(listWidth).fillMaxHeight())
                    VerticalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                    detailPane(Modifier.weight(1f).fillMaxHeight())
                }
            }
            else -> Box(Modifier.padding(padding).fillMaxSize(), contentAlignment = Alignment.TopCenter) {
                list(Modifier.widthIn(max = 720.dp).fillMaxSize())
            }
        }
    }

    val batchToRestore = confirmBatch?.let { id ->
        state.days.asSequence().flatMap { it.items }.filterIsInstance<HistoryItem.Batch>().firstOrNull { it.batch.id == id }
    }
    if (batchToRestore != null) {
        val count = batchToRestore.records.count { it.canRestore }
        AlertDialog(
            onDismissRequest = { confirmBatch = null },
            icon = { Icon(Icons.Rounded.Restore, contentDescription = null) },
            title = { Text(stringResource(R.string.history_batch_restore_title)) },
            text = { Text(pluralStringResource(R.plurals.history_batch_restore_body, count, count)) },
            confirmButton = {
                TextButton(onClick = {
                    confirmBatch = null
                    onRestoreBatch(batchToRestore.batch.id)
                }) { Text(stringResource(R.string.history_action_restore_all)) }
            },
            dismissButton = { TextButton(onClick = { confirmBatch = null }) { Text(stringResource(R.string.action_cancel)) } },
        )
    }
}

@Composable
private fun EmptyHistory(modifier: Modifier) {
    Box(
        modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState()),
        contentAlignment = Alignment.Center,
    ) {
        EmptyState(
            icon = Icons.Rounded.History,
            shape = MaterialShapes.Clover4Leaf,
            title = stringResource(R.string.history_empty_title),
            body = stringResource(R.string.history_empty_body),
        )
    }
}

/** The detail pane before an edit is picked (expanded widths only). */
@Composable
fun HistoryDetailPlaceholder(modifier: Modifier = Modifier) {
    Box(modifier.verticalScroll(rememberScrollState()), contentAlignment = Alignment.Center) {
        EmptyState(
            icon = Icons.Rounded.TouchApp,
            shape = MaterialShapes.Cookie7Sided,
            title = stringResource(R.string.history_select_title),
            body = stringResource(R.string.history_select_body),
        )
    }
}

@Composable
private fun HistoryList(
    state: HistoryUiState,
    selectedRecordId: String?,
    expandedBatches: Set<String>,
    onToggleBatch: (String) -> Unit,
    onOpenRecord: (String) -> Unit,
    onRestoreBatch: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    LazyColumn(
        modifier = modifier,
        contentPadding = PaddingValues(start = 16.dp, end = 16.dp, bottom = 32.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        state.days.forEach { day ->
            item(key = "day:${day.date}", contentType = "day") {
                SectionHeader(dayLabel(day.date, state.today), Modifier.padding(top = 8.dp))
            }
            items(day.items, key = { it.key }, contentType = { it::class }) { item ->
                when (item) {
                    is HistoryItem.Single -> HistoryRecordCard(
                        record = item.record,
                        state = state,
                        selected = item.record.id == selectedRecordId,
                        onClick = { onOpenRecord(item.record.id) },
                    )
                    is HistoryItem.Batch -> HistoryBatchCard(
                        item = item,
                        state = state,
                        expanded = item.batch.id in expandedBatches,
                        selectedRecordId = selectedRecordId,
                        onToggle = { onToggleBatch(item.batch.id) },
                        onOpenRecord = onOpenRecord,
                        onRestoreAll = { onRestoreBatch(item.batch.id) },
                    )
                }
            }
        }
    }
}

@Composable
private fun HistoryRecordCard(record: EditRecord, state: HistoryUiState, selected: Boolean, onClick: () -> Unit) {
    val ref = remember(record.id) { record.photoRef() }
    Surface(
        onClick = onClick,
        shape = MaterialTheme.shapes.large,
        color = if (selected) MaterialTheme.colorScheme.secondaryContainer else MaterialTheme.colorScheme.surfaceContainerLow,
        modifier = Modifier
            .fillMaxWidth()
            .semantics { this.selected = selected },
    ) {
        Row(Modifier.padding(12.dp), horizontalArrangement = Arrangement.spacedBy(16.dp)) {
            PhotoThumbnail(
                ref = ref,
                contentDescription = null,
                modifier = Modifier
                    .size(64.dp)
                    .clip(MaterialTheme.shapes.medium),
            )
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(record.title(), style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f))
                    Text(
                        timeLabel(record.createdAt, state.zone),
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(top = 2.dp),
                    )
                }
                Text(
                    record.summaryLine(),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                FlowRow(
                    modifier = Modifier.padding(top = 4.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    ModeChip(record.mode)
                    StateChip(record.state)
                }
            }
        }
    }
}

@Composable
private fun HistoryBatchCard(
    item: HistoryItem.Batch,
    state: HistoryUiState,
    expanded: Boolean,
    selectedRecordId: String?,
    onToggle: () -> Unit,
    onOpenRecord: (String) -> Unit,
    onRestoreAll: () -> Unit,
) {
    val batch = item.batch
    val title = if (batch.restoresBatchId != null) stringResource(R.string.history_batch_restore_of, batch.title) else batch.title
    val stateText = stringResource(if (expanded) R.string.history_batch_expanded else R.string.history_batch_collapsed)
    val toggleLabel = stringResource(if (expanded) R.string.history_batch_hide else R.string.history_batch_show)
    val restoring = state.restoringBatchId == batch.id
    Surface(
        shape = MaterialTheme.shapes.large,
        color = MaterialTheme.colorScheme.surfaceContainerLow,
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable(onClickLabel = toggleLabel, onClick = onToggle)
                    .semantics { stateDescription = stateText }
                    .padding(12.dp),
                horizontalArrangement = Arrangement.spacedBy(16.dp),
            ) {
                BatchThumbnails(item.records)
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text(title, style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f))
                        Text(
                            timeLabel(batch.createdAt, state.zone),
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(top = 2.dp),
                        )
                    }
                    Text(
                        pluralStringResource(R.plurals.photo_count, batch.total, batch.total),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    BatchCounts(item, Modifier.padding(top = 4.dp))
                }
                Icon(
                    if (expanded) Icons.Rounded.ExpandLess else Icons.Rounded.ExpandMore,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(top = 4.dp),
                )
            }
            AnimatedVisibility(visible = expanded) {
                Column(
                    Modifier.padding(start = 12.dp, end = 12.dp, bottom = 12.dp),
                    verticalArrangement = Arrangement.spacedBy(4.dp),
                ) {
                    item.records.forEach { record ->
                        BatchMemberRow(record, selected = record.id == selectedRecordId, onClick = { onOpenRecord(record.id) })
                    }
                    if (item.canRestoreAll) {
                        Spacer(Modifier.size(4.dp))
                        FilledTonalButton(onClick = onRestoreAll, enabled = state.restoringBatchId == null) {
                            if (restoring) {
                                CircularProgressIndicator(Modifier.size(ButtonDefaults.IconSize), strokeWidth = 2.dp)
                            } else {
                                Icon(Icons.Rounded.Restore, contentDescription = null, modifier = Modifier.size(ButtonDefaults.IconSize))
                            }
                            Spacer(Modifier.size(ButtonDefaults.IconSpacing))
                            Text(stringResource(R.string.history_action_restore_all))
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun BatchCounts(item: HistoryItem.Batch, modifier: Modifier = Modifier) {
    val batch = item.batch
    val colors = MaterialTheme.colorScheme
    FlowRow(
        modifier = modifier,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        if (batch.restoresBatchId != null) StateChip(EditState.Restored)
        StatusChip(
            icon = Icons.Rounded.CheckCircle,
            text = pluralStringResource(R.plurals.history_batch_saved, batch.succeeded, batch.succeeded),
            containerColor = colors.secondaryContainer,
            contentColor = colors.onSecondaryContainer,
        )
        if (batch.failed > 0) {
            StatusChip(
                icon = Icons.Rounded.ErrorOutline,
                text = pluralStringResource(R.plurals.history_batch_failed, batch.failed, batch.failed),
                containerColor = colors.errorContainer,
                contentColor = colors.onErrorContainer,
            )
        }
        if (batch.cancelled > 0) {
            StatusChip(
                icon = Icons.Rounded.Block,
                text = pluralStringResource(R.plurals.history_batch_cancelled, batch.cancelled, batch.cancelled),
                containerColor = colors.surfaceContainerHighest,
                contentColor = colors.onSurfaceVariant,
            )
        }
    }
}

/** Up to three photos of a batch, fanned out; a stack icon when there are none to show. */
@Composable
private fun BatchThumbnails(records: List<EditRecord>) {
    val shown = remember(records) { records.distinctBy { it.photoUri }.take(3).map { it.photoRef() } }
    Box(Modifier.size(64.dp)) {
        if (shown.isEmpty()) {
            Surface(
                shape = MaterialTheme.shapes.medium,
                color = MaterialTheme.colorScheme.surfaceContainerHighest,
                modifier = Modifier.size(64.dp),
            ) {
                Box(contentAlignment = Alignment.Center) {
                    Icon(Icons.Rounded.PhotoLibrary, contentDescription = null, tint = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
        }
        val size = if (shown.size > 1) 52.dp else 64.dp
        val step = if (shown.size > 1) 12.dp / (shown.size - 1) else 0.dp
        shown.asReversed().forEachIndexed { index, ref ->
            val offset = step * (shown.size - 1 - index)
            Surface(
                shape = MaterialTheme.shapes.medium,
                color = MaterialTheme.colorScheme.surfaceContainerLow,
                modifier = Modifier.offset(x = offset, y = offset).size(size),
            ) {
                PhotoThumbnail(
                    ref = ref,
                    contentDescription = null,
                    showFormatLabel = false,
                    modifier = Modifier
                        .padding(1.dp)
                        .clip(MaterialTheme.shapes.medium),
                )
            }
        }
    }
}

@Composable
private fun BatchMemberRow(record: EditRecord, selected: Boolean, onClick: () -> Unit) {
    val ref = remember(record.id) { record.photoRef() }
    Surface(
        onClick = onClick,
        shape = MaterialTheme.shapes.medium,
        color = if (selected) MaterialTheme.colorScheme.secondaryContainer else MaterialTheme.colorScheme.surfaceContainer,
        modifier = Modifier
            .fillMaxWidth()
            .semantics { this.selected = selected },
    ) {
        Row(
            Modifier
                .heightIn(min = 56.dp)
                .padding(horizontal = 8.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            PhotoThumbnail(
                ref = ref,
                contentDescription = null,
                showFormatLabel = false,
                modifier = Modifier
                    .size(40.dp)
                    .clip(MaterialTheme.shapes.small),
            )
            Text(record.title(), style = MaterialTheme.typography.bodyLarge, modifier = Modifier.weight(1f))
            StateChip(record.state)
        }
    }
}
