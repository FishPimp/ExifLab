package io.github.fishpimp.exiflab.ui.home

import android.net.Uri
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider.AndroidViewModelFactory.Companion.APPLICATION_KEY
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import io.github.fishpimp.exiflab.appGraph
import io.github.fishpimp.exiflab.data.photos.OpenResult
import io.github.fishpimp.exiflab.data.photos.PhotoAccessError
import io.github.fishpimp.exiflab.data.photos.PhotoOrigin
import io.github.fishpimp.exiflab.data.photos.PhotoRef
import io.github.fishpimp.exiflab.data.photos.PhotoRepository
import io.github.fishpimp.exiflab.data.photos.RecentPhotosRepository
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

/** One-off outcomes the Home screen reacts to. */
sealed interface HomeEvent {
    /** Show these photos (never empty). */
    data class Open(val refs: List<PhotoRef>) : HomeEvent

    /** Nothing from the last pick could be opened. */
    data class Failed(val error: PhotoAccessError) : HomeEvent
}

class HomeViewModel(
    private val photoRepository: PhotoRepository,
    private val recentPhotosRepository: RecentPhotosRepository,
) : ViewModel() {
    val recents: StateFlow<List<PhotoRef>> = recentPhotosRepository.recents
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    private val _isOpening = MutableStateFlow(false)

    /** True while picked files are being resolved. */
    val isOpening: StateFlow<Boolean> = _isOpening.asStateFlow()

    private val _events = Channel<HomeEvent>(Channel.BUFFERED)
    val events: Flow<HomeEvent> = _events.receiveAsFlow()

    /** Resolves URIs returned by the photo or file picker. An empty list (cancelled pick) is ignored. */
    fun open(uris: List<Uri>, origin: PhotoOrigin) {
        if (uris.isEmpty()) return
        viewModelScope.launch {
            _isOpening.value = true
            val result = try {
                photoRepository.open(uris, origin)
            } finally {
                _isOpening.value = false
            }
            _events.send(
                when (result) {
                    is OpenResult.Opened -> HomeEvent.Open(result.refs)
                    is OpenResult.Failed -> HomeEvent.Failed(result.error)
                },
            )
        }
    }

    /** Re-checks which recent photos are still accessible. */
    fun refreshRecents() = recentPhotosRepository.refresh()

    companion object {
        val Factory = viewModelFactory {
            initializer {
                val graph = this[APPLICATION_KEY]!!.appGraph
                HomeViewModel(graph.photoRepository, graph.recentPhotosRepository)
            }
        }
    }
}
