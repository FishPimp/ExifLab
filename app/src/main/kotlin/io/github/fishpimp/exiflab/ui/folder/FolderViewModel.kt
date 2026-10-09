package io.github.fishpimp.exiflab.ui.folder

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.ViewModelProvider.AndroidViewModelFactory.Companion.APPLICATION_KEY
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import io.github.fishpimp.exiflab.appGraph
import io.github.fishpimp.exiflab.data.folders.FolderListing
import io.github.fishpimp.exiflab.data.folders.FolderRepository
import io.github.fishpimp.exiflab.data.photos.PhotoAccessError
import io.github.fishpimp.exiflab.data.photos.PhotoAccessException
import io.github.fishpimp.exiflab.ui.navigation.Route
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn

/** What the folder browser shows. */
sealed interface FolderUiState {
    data object Loading : FolderUiState
    data class Loaded(val listing: FolderListing) : FolderUiState
    data class Failed(val error: PhotoAccessError) : FolderUiState
}

/** Lists one folder; the listing follows changes on disk while the screen is visible. */
class FolderViewModel(repository: FolderRepository, folderTreeUri: String, documentUri: String) : ViewModel() {
    val state: StateFlow<FolderUiState> = repository.listChildren(folderTreeUri, documentUri)
        .map<FolderListing, FolderUiState> { FolderUiState.Loaded(it) }
        .catch { e ->
            if (e !is PhotoAccessException) throw e
            emit(FolderUiState.Failed(e.error))
        }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), FolderUiState.Loading)

    companion object {
        /** A factory for the folder named by [route]. */
        fun factory(route: Route.Folder): ViewModelProvider.Factory = viewModelFactory {
            initializer {
                FolderViewModel(this[APPLICATION_KEY]!!.appGraph.folderRepository, route.folderTreeUri, route.documentUri)
            }
        }
    }
}
