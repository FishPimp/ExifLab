package io.github.fishpimp.exiflab.ui.library

import android.net.Uri
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider.AndroidViewModelFactory.Companion.APPLICATION_KEY
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import io.github.fishpimp.exiflab.appGraph
import io.github.fishpimp.exiflab.data.folders.FolderRepository
import io.github.fishpimp.exiflab.data.folders.GrantedFolder
import io.github.fishpimp.exiflab.data.folders.LibraryFolder
import io.github.fishpimp.exiflab.data.photos.PhotoAccessError
import io.github.fishpimp.exiflab.data.photos.PhotoAccessException
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

class LibraryViewModel(private val repository: FolderRepository) : ViewModel() {
    /** Granted folders with their access state; null until the first read completes. */
    val folders: StateFlow<List<LibraryFolder>?> = repository.folders
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

    private val _errors = Channel<PhotoAccessError>(Channel.BUFFERED)

    /** Failures to add or re-grant a folder, for a snackbar. */
    val errors: Flow<PhotoAccessError> = _errors.receiveAsFlow()

    /** Adds the folder returned by the system folder picker; null (cancelled) is ignored. */
    fun addFolder(treeUri: Uri?) = guarded(treeUri) { repository.addFolder(it) }

    /** Restores access to the folder stored under [previousTreeUri] with the folder the user picked again. */
    fun regrant(previousTreeUri: String, treeUri: Uri?) = guarded(treeUri) { repository.regrant(previousTreeUri, it) }

    fun removeFolder(folder: GrantedFolder) {
        viewModelScope.launch { repository.removeFolder(folder) }
    }

    /** Re-checks access, e.g. after the user returns from system settings. */
    fun refresh() = repository.refresh()

    private fun guarded(treeUri: Uri?, action: suspend (Uri) -> Unit) {
        if (treeUri == null) return
        viewModelScope.launch {
            try {
                action(treeUri)
            } catch (e: PhotoAccessException) {
                _errors.send(e.error)
            }
        }
    }

    companion object {
        val Factory = viewModelFactory {
            initializer { LibraryViewModel(this[APPLICATION_KEY]!!.appGraph.folderRepository) }
        }
    }
}
