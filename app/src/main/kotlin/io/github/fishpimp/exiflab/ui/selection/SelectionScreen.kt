package io.github.fishpimp.exiflab.ui.selection

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.ViewModelProvider.AndroidViewModelFactory.Companion.APPLICATION_KEY
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import io.github.fishpimp.exiflab.R
import io.github.fishpimp.exiflab.appGraph
import io.github.fishpimp.exiflab.data.batch.BatchJobRepository
import io.github.fishpimp.exiflab.data.batch.BatchKind
import io.github.fishpimp.exiflab.data.photos.PhotoRef
import io.github.fishpimp.exiflab.ui.components.PhotoGridCells
import io.github.fishpimp.exiflab.ui.components.ScreenScaffold
import io.github.fishpimp.exiflab.ui.export.ExportHost
import io.github.fishpimp.exiflab.ui.export.ExportViewModel
import io.github.fishpimp.exiflab.ui.navigation.Route
import io.github.fishpimp.exiflab.ui.shareclean.ShareCleanSheetHost
import io.github.fishpimp.exiflab.ui.shareclean.shareCleanViewModel
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.launch

/**
 * Several photos picked or shared at once. A tap opens one; long press or "Select all" starts
 * multi-select with the batch actions.
 */
@Composable
fun SelectionScreen(
    refs: List<PhotoRef>,
    onBack: () -> Unit,
    onOpenPhoto: (PhotoRef) -> Unit,
    onBatchEdit: (Route.BatchEdit) -> Unit,
    modifier: Modifier = Modifier,
    viewModel: SelectionViewModel = viewModel(factory = SelectionViewModel.factory(refs)),
    exportViewModel: ExportViewModel = viewModel(factory = ExportViewModel.Factory),
) {
    val selection by viewModel.selection.collectAsStateWithLifecycle()
    val currentOnBatchEdit by rememberUpdatedState(onBatchEdit)
    val snackbarHostState = remember { SnackbarHostState() }
    LaunchedEffect(viewModel) { viewModel.batchEditRequests.collect { currentOnBatchEdit(it) } }
    BackHandler(enabled = selection.active) { viewModel.selectionActions.onExit() }
    val shareClean = shareCleanViewModel()
    ExportHost(snackbarHostState, exportViewModel)
    SelectionContent(
        refs = refs,
        onBack = onBack,
        onOpenPhoto = onOpenPhoto,
        selection = selection,
        selectionActions = viewModel.selectionActions,
        onBatchAction = viewModel::startBatch,
        onShareClean = { shareClean.open(selection.selectedIn(refs)) },
        onExport = { exportViewModel.open(selection.selectedIn(refs)) },
        snackbarHostState = snackbarHostState,
        modifier = modifier,
    )
    ShareCleanSheetHost(shareClean)
}

/** Stateless photo grid of a selection, rendered directly by screenshot tests. */
@Composable
fun SelectionContent(
    refs: List<PhotoRef>,
    onBack: () -> Unit,
    onOpenPhoto: (PhotoRef) -> Unit,
    modifier: Modifier = Modifier,
    selection: PhotoSelection = PhotoSelection(),
    selectionActions: SelectionActions = SelectionActions(),
    onBatchAction: (BatchKind) -> Unit = {},
    onShareClean: (() -> Unit)? = null,
    onExport: () -> Unit = {},
    snackbarHostState: SnackbarHostState = remember { SnackbarHostState() },
) {
    val title = pluralStringResource(R.plurals.photo_count, refs.size, refs.size)
    ScreenScaffold(
        title = if (selection.active) selectionTitle(selection) else title,
        subtitle = if (selection.active) title else null,
        onBack = if (selection.active) selectionActions.onExit else onBack,
        navigationIcon = if (selection.active) Icons.Rounded.Close else Icons.AutoMirrored.Rounded.ArrowBack,
        navigationLabel = if (selection.active) stringResource(R.string.selection_exit) else null,
        actions = { SelectionBarActions(selection, selectionActions, total = refs.size, enterSelectsAll = true) },
        snackbarHost = { SnackbarHost(snackbarHostState) },
        modifier = modifier,
    ) { padding ->
        Box(Modifier.fillMaxSize().padding(padding), contentAlignment = Alignment.TopCenter) {
            val gridState = rememberLazyGridState()
            LazyVerticalGrid(
                columns = PhotoGridCells,
                state = gridState,
                modifier = Modifier
                    .widthIn(max = 1200.dp)
                    .fillMaxSize()
                    .dragToSelect(gridState, refs, selection, selectionActions),
                contentPadding = PaddingValues(start = 16.dp, end = 16.dp, bottom = if (selection.active) SelectionBottomPadding else 24.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                items(refs, key = { it.uri }) { ref ->
                    SelectablePhotoTile(
                        ref = ref,
                        selectionActive = selection.active,
                        selected = selection.isSelected(ref.uri),
                        onOpen = { onOpenPhoto(ref) },
                        onBegin = { selectionActions.onBegin(ref.uri) },
                        onToggle = { selectionActions.onToggle(ref.uri) },
                    )
                }
            }
            BatchActionsToolbar(
                visible = selection.active,
                enabled = selection.count > 0,
                onAction = onBatchAction,
                onExport = onExport,
                modifier = Modifier.align(Alignment.BottomCenter),
                onShareClean = onShareClean,
            )
        }
    }
}

/** Selection state of the picked photos and their hand-off to the batch editor. */
class SelectionViewModel(private val refs: List<PhotoRef>, private val batchJobs: BatchJobRepository?) : ViewModel() {
    private val holder = PhotoSelectionHolder()
    private val batchEvents = Channel<Route.BatchEdit>(Channel.BUFFERED)

    val selection: StateFlow<PhotoSelection> = holder.state
    val selectionActions: SelectionActions = holder.actions { refs.map { it.uri } }
    val batchEditRequests: Flow<Route.BatchEdit> = batchEvents.receiveAsFlow()

    private var draftWatch: Job? = null

    /**
     * Stores the selected photos as a batch draft and asks to open the editor on [kind]. The
     * selection stays until the batch starts, so backing out of the editor keeps it.
     */
    fun startBatch(kind: BatchKind) {
        val jobs = batchJobs ?: return
        val selected = selection.value.selectedIn(refs)
        if (selected.isEmpty()) return
        viewModelScope.launch {
            val id = jobs.createDraft(selected)
            draftWatch?.cancel()
            draftWatch = viewModelScope.exitSelectionWhenStarted(holder, jobs, id)
            batchEvents.send(Route.BatchEdit(id, kind))
        }
    }

    companion object {
        fun factory(refs: List<PhotoRef>): ViewModelProvider.Factory = viewModelFactory {
            initializer { SelectionViewModel(refs, this[APPLICATION_KEY]!!.appGraph.batchJobs) }
        }
    }
}
