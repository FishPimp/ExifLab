package io.github.fishpimp.exiflab

import android.content.Intent
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider.AndroidViewModelFactory.Companion.APPLICATION_KEY
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import io.github.fishpimp.exiflab.data.photos.OpenResult
import io.github.fishpimp.exiflab.data.photos.PhotoAccessError
import io.github.fishpimp.exiflab.data.photos.PhotoOrigin
import io.github.fishpimp.exiflab.data.photos.PhotoRepository
import io.github.fishpimp.exiflab.data.photos.sharedStreamUris
import io.github.fishpimp.exiflab.ui.navigation.ExternalOpen
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/** A share that could not be opened; [closeApp] when ExifLab was started only for it. */
data class ShareFailure(val error: PhotoAccessError, val closeApp: Boolean)

/**
 * Activity-scoped state for photos shared to ExifLab. Resolving survives configuration
 * changes here, and the resolved request waits in [pendingOpen] until the shell shows it.
 */
class MainViewModel(private val photoRepository: PhotoRepository) : ViewModel() {
    private val _pendingOpen = MutableStateFlow<ExternalOpen?>(null)

    /** Resolved shared photos the shell has not shown yet. */
    val pendingOpen: StateFlow<ExternalOpen?> = _pendingOpen.asStateFlow()

    private val _awaitingLaunchShare = MutableStateFlow(false)

    /**
     * True while the share that started the activity is being resolved (capped at a short
     * limit). The UI waits behind the splash screen meanwhile, so it opens directly on the photo.
     */
    val awaitingLaunchShare: StateFlow<Boolean> = _awaitingLaunchShare.asStateFlow()

    private val _failures = Channel<ShareFailure>(Channel.BUFFERED)
    val failures: Flow<ShareFailure> = _failures.receiveAsFlow()

    private val inFlight = MutableStateFlow(0)
    private var launchIntentHandled = false

    /** True while a share is resolving or waiting to be shown; it must not be dropped then. */
    val hasUnhandledShare: Boolean get() = inFlight.value > 0 || _pendingOpen.value != null

    /** Handles the intent that started the activity. Repeated calls (recreation) are ignored. */
    fun onLaunchIntent(intent: Intent) {
        if (launchIntentHandled) return
        launchIntentHandled = true
        handle(intent, isLaunch = true)
    }

    /** Handles an intent delivered to the running activity. */
    fun onNewIntent(intent: Intent) = handle(intent, isLaunch = false)

    /** Called by the shell once [open] is on screen. */
    fun onExternalOpenHandled(open: ExternalOpen) {
        _pendingOpen.compareAndSet(open, null)
    }

    private fun handle(intent: Intent, isLaunch: Boolean) {
        val uris = intent.sharedStreamUris()
        if (uris.isEmpty()) return
        inFlight.update { it + 1 }
        if (isLaunch) _awaitingLaunchShare.value = true
        viewModelScope.launch {
            val waitLimit = if (isLaunch) {
                launch {
                    delay(LAUNCH_WAIT_LIMIT_MS)
                    _awaitingLaunchShare.value = false
                }
            } else {
                null
            }
            try {
                when (val result = photoRepository.open(uris, PhotoOrigin.Share)) {
                    is OpenResult.Opened -> _pendingOpen.value = ExternalOpen(System.nanoTime(), result.refs)
                    is OpenResult.Failed -> _failures.send(ShareFailure(result.error, closeApp = isLaunch))
                }
            } finally {
                waitLimit?.cancel()
                if (isLaunch) _awaitingLaunchShare.value = false
                inFlight.update { it - 1 }
            }
        }
    }

    companion object {
        /** Longest the splash screen waits for a shared photo before showing the app anyway. */
        private const val LAUNCH_WAIT_LIMIT_MS = 2_000L

        val Factory = viewModelFactory {
            initializer { MainViewModel(this[APPLICATION_KEY]!!.appGraph.photoRepository) }
        }
    }
}
