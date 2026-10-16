package io.github.fishpimp.exiflab.ui.folder

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.ViewModelProvider.AndroidViewModelFactory.Companion.APPLICATION_KEY
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import io.github.fishpimp.exiflab.appGraph
import io.github.fishpimp.exiflab.data.batch.BatchJobRepository
import io.github.fishpimp.exiflab.data.batch.BatchKind
import io.github.fishpimp.exiflab.data.folders.FolderListing
import io.github.fishpimp.exiflab.data.folders.FolderRepository
import io.github.fishpimp.exiflab.data.photos.PhotoAccessError
import io.github.fishpimp.exiflab.data.photos.PhotoAccessException
import io.github.fishpimp.exiflab.data.photos.PhotoRef
import io.github.fishpimp.exiflab.ui.navigation.Route
import io.github.fishpimp.exiflab.ui.selection.PhotoSelection
import io.github.fishpimp.exiflab.ui.selection.PhotoSelectionHolder
import io.github.fishpimp.exiflab.ui.selection.SelectionActions
import io.github.fishpimp.exiflab.ui.selection.actions
import io.github.fishpimp.exiflab.ui.selection.exitSelectionWhenStarted
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.flow.onStart
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

/** What the folder browser shows. */
sealed interface FolderUiState {
    data object Loading : FolderUiState
    data class Loaded(val listing: FolderListing) : FolderUiState
    data class Failed(val error: PhotoAccessError) : FolderUiState
}

/**
 * Lists one folder; the listing follows changes on disk while the screen is visible. Holds the
 * multi-select state and turns a selection into a batch draft.
 */
class FolderViewModel(
    repository: FolderRepository,
    folderTreeUri: String,
    documentUri: String,
    private val batchJobs: BatchJobRepository? = null,
) : ViewModel() {
    private val selectionHolder = PhotoSelectionHolder()
    private val batchEvents = Channel<Route.BatchEdit>(Channel.BUFFERED)

    private val attempts = MutableStateFlow(0)
    private var retrying = false

    @OptIn(ExperimentalCoroutinesApi::class)
    val state: StateFlow<FolderUiState> = attempts
        .flatMapLatest {
            repository.listChildren(folderTreeUri, documentUri)
                .map<FolderListing, FolderUiState> { FolderUiState.Loaded(it) }
                .onStart {
                    // Show that a retry is running, not the old failure.
                    if (retrying) emit(FolderUiState.Loading)
                    retrying = false
                }
                .catch { e ->
                    if (e !is PhotoAccessException) throw e
                    emit(FolderUiState.Failed(e.error))
                }
        }
        .onEach { state ->
            // Photos deleted while selected drop out of the selection.
            val listing = (state as? FolderUiState.Loaded)?.listing ?: return@onEach
            if (selectionHolder.state.value.count > 0 && !listing.isLoading) {
                val available = listing.photos.mapTo(HashSet(listing.photos.size * 2)) { it.uri }
                selectionHolder.update { it.retain(available) }
            }
        }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), FolderUiState.Loading)

    val selection: StateFlow<PhotoSelection> = selectionHolder.state

    /** Lists the folder again after a failure, e.g. once an SD card is back. */
    fun retry() {
        retrying = true
        attempts.value++
    }

    val selectionActions: SelectionActions = selectionHolder.actions {
        (state.value as? FolderUiState.Loaded)?.listing?.photos?.map { it.uri }.orEmpty()
    }

    /** Opens the batch editor for the selected photos. */
    val batchEditRequests: Flow<Route.BatchEdit> = batchEvents.receiveAsFlow()

    /** The selected photos in folder order, for exporting their metadata. */
    fun selectedPhotos(): List<PhotoRef> {
        val photos = (state.value as? FolderUiState.Loaded)?.listing?.photos ?: return emptyList()
        return selection.value.selectedIn(photos)
    }

    private var draftWatch: Job? = null

    /**
     * Stores the selected photos as a batch draft and asks to open the editor on [kind]. The
     * selection stays until the batch starts, so backing out of the editor keeps it.
     */
    fun startBatch(kind: BatchKind) {
        val jobs = batchJobs ?: return
        val photos = (state.value as? FolderUiState.Loaded)?.listing?.photos ?: return
        val selected = selection.value.selectedIn(photos)
        if (selected.isEmpty()) return
        viewModelScope.launch {
            val id = jobs.createDraft(selected)
            draftWatch?.cancel()
            draftWatch = viewModelScope.exitSelectionWhenStarted(selectionHolder, jobs, id)
            batchEvents.send(Route.BatchEdit(id, kind))
        }
    }

    companion object {
        /** A factory for the folder named by [route]. */
        fun factory(route: Route.Folder): ViewModelProvider.Factory = viewModelFactory {
            initializer {
                val graph = this[APPLICATION_KEY]!!.appGraph
                FolderViewModel(graph.folderRepository, route.folderTreeUri, route.documentUri, graph.batchJobs)
            }
        }
    }
}
