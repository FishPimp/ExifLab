package io.github.fishpimp.exiflab.ui.shareclean

import android.net.Uri
import androidx.compose.runtime.Composable
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider.AndroidViewModelFactory.Companion.APPLICATION_KEY
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import io.github.fishpimp.exiflab.appGraph
import io.github.fishpimp.exiflab.data.photos.MAX_PHOTOS_PER_OPEN
import io.github.fishpimp.exiflab.data.photos.OpenResult
import io.github.fishpimp.exiflab.data.photos.PhotoAccessError
import io.github.fishpimp.exiflab.data.photos.PhotoOrigin
import io.github.fishpimp.exiflab.data.photos.PhotoRef
import io.github.fishpimp.exiflab.data.photos.isRaw
import io.github.fishpimp.exiflab.data.shareclean.CleanedFile
import io.github.fishpimp.exiflab.data.shareclean.Refusal
import io.github.fishpimp.exiflab.data.shareclean.RefusalReason
import io.github.fishpimp.exiflab.data.shareclean.ShareCleanChoice
import io.github.fishpimp.exiflab.data.shareclean.ShareCleanChoices
import io.github.fishpimp.exiflab.data.shareclean.ShareCleanPreset
import io.github.fishpimp.exiflab.data.shareclean.ShareCleanResult
import io.github.fishpimp.exiflab.data.shareclean.ShareCleaning
import io.github.fishpimp.exiflab.metadata.edit.StripCategory
import io.github.fishpimp.exiflab.metadata.model.MetadataReport
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** The single photo's metadata, for the "Removes: ..." summary. */
sealed interface SummarySource {
    /** Several photos: the summary lists the chosen categories instead. */
    data object Several : SummarySource

    data object Loading : SummarySource

    data class Ready(val report: MetadataReport) : SummarySource

    /** The photo could not be read now; cleaning will say why. */
    data object Unavailable : SummarySource
}

/** Where the sheet is. */
sealed interface ShareCleanStep {
    data object Choosing : ShareCleanStep

    data class Cleaning(val done: Int, val total: Int) : ShareCleanStep

    /** Cleaning ended with photos left out; [result] says which and why. */
    data class Finished(val result: ShareCleanResult) : ShareCleanStep
}

/**
 * State of the Share clean sheet. The sheet shows while [photos] is not empty.
 *
 * @property rawCount how many of [photos] are RAW files, which are shared as their JPEG preview.
 */
data class ShareCleanUiState(
    val photos: List<PhotoRef> = emptyList(),
    val choice: ShareCleanChoice = ShareCleanChoice(),
    val summary: SummarySource = SummarySource.Several,
    val rawCount: Int = 0,
    val step: ShareCleanStep = ShareCleanStep.Choosing,
) {
    val isOpen: Boolean get() = photos.isNotEmpty()

    /** More photos than one share can take. */
    val tooMany: Boolean get() = photos.size > MAX_SHARE_PHOTOS

    /** True when Share can be pressed: something to remove, and something to share (not only RAW files left out). */
    val canShare: Boolean
        get() = isOpen && !tooMany && choice.canShare && step == ShareCleanStep.Choosing &&
            !(rawCount == photos.size && !choice.includeRawPreviews && photos.size > 1)
}

/** One-off requests to the screen hosting the sheet. */
sealed interface ShareCleanEvent {
    /** Open the system share sheet with these cleaned copies (never empty). */
    data class Share(val files: List<CleanedFile>) : ShareCleanEvent

    /** Nothing from a pick or a share could be opened. */
    data class OpenFailed(val error: PhotoAccessError) : ShareCleanEvent
}

/** The Share clean view model of the screen (navigation entry or activity) this is called in. */
@Composable
fun shareCleanViewModel(): ShareCleanViewModel = viewModel(factory = ShareCleanViewModel.Factory)

/** Most photos in one Share clean, the photo picker's limit. */
const val MAX_SHARE_PHOTOS = MAX_PHOTOS_PER_OPEN

/**
 * Drives the Share clean sheet: the preset (the last one used comes preselected), the summary
 * of what goes for a single photo, cleaning with progress, and the hand-off to the system share
 * sheet. Hosted by every screen that offers Share clean, and by [ShareCleanActivity].
 */
class ShareCleanViewModel(
    private val cleaner: ShareCleaning,
    private val choices: ShareCleanChoices,
    private val readReport: suspend (PhotoRef) -> MetadataReport,
    private val resolve: suspend (List<Uri>) -> OpenResult,
    /** Resolves the streams of a share intent, without the URIs only ExifLab's own access could read. */
    private val resolveShared: suspend (List<Uri>) -> OpenResult = resolve,
) : ViewModel() {
    private val _state = MutableStateFlow(ShareCleanUiState())
    val state: StateFlow<ShareCleanUiState> = _state.asStateFlow()

    private val _events = Channel<ShareCleanEvent>(Channel.BUFFERED)
    val events: Flow<ShareCleanEvent> = _events.receiveAsFlow()

    private var work: Job? = null

    /** Set by [ShareCleanActivity] once it opened the photos its intent carries, so recreation does not reopen them. */
    var startedFromIntent: Boolean = false

    /**
     * Opens the sheet for [photos]. [report] is the photo's metadata when the caller already has
     * it (the viewer), so a single photo's summary shows at once.
     */
    fun open(photos: List<PhotoRef>, report: MetadataReport? = null) {
        if (photos.isEmpty()) return
        work?.cancel()
        work = viewModelScope.launch {
            val saved = runCatching { choices.choice.first() }.getOrDefault(ShareCleanChoice())
            val single = photos.singleOrNull()
            val summary = when {
                single == null -> SummarySource.Several
                report != null -> SummarySource.Ready(report)
                else -> SummarySource.Loading
            }
            _state.value = ShareCleanUiState(
                photos = photos,
                choice = saved,
                summary = summary,
                rawCount = if (single != null && report != null) (if (report.format.isRaw) 1 else 0) else photos.count { it.isRaw },
            )
            if (single != null && report == null) {
                val loaded = try {
                    readReport(single)
                } catch (e: CancellationException) {
                    throw e
                } catch (_: Exception) {
                    null
                }
                _state.update { current ->
                    if (current.photos != photos) return@update current
                    current.copy(
                        summary = loaded?.let(SummarySource::Ready) ?: SummarySource.Unavailable,
                        rawCount = if (loaded?.format?.isRaw == true) 1 else current.rawCount,
                    )
                }
            }
        }
    }

    /** Resolves picked [uris] and opens the sheet for them. They are read once, now; access is not kept. */
    fun openUris(uris: List<Uri>) = openWith(resolve, uris)

    /** Like [openUris], for the streams another app shared with ExifLab. */
    fun openShared(uris: List<Uri>) = openWith(resolveShared, uris)

    private fun openWith(resolver: suspend (List<Uri>) -> OpenResult, uris: List<Uri>) {
        if (uris.isEmpty()) return
        viewModelScope.launch {
            when (val result = resolver(uris)) {
                is OpenResult.Opened -> open(result.refs)
                is OpenResult.Failed -> _events.send(ShareCleanEvent.OpenFailed(result.error))
            }
        }
    }

    fun selectPreset(preset: ShareCleanPreset) = updateChoice { it.copy(preset = preset) }

    /** Ticks or unticks [category] under Custom. */
    fun toggleCustom(category: StripCategory) = updateChoice { choice ->
        val custom = if (category in choice.custom) choice.custom - category else choice.custom + category
        choice.copy(preset = ShareCleanPreset.Custom, custom = custom)
    }

    fun setIncludeRawPreviews(include: Boolean) = updateChoice { it.copy(includeRawPreviews = include) }

    private fun updateChoice(transform: (ShareCleanChoice) -> ShareCleanChoice) {
        _state.update { if (it.step == ShareCleanStep.Choosing) it.copy(choice = transform(it.choice)) else it }
    }

    /** Remembers the choice, cleans every photo off the main thread, then shares or reports what was left out. */
    fun share() {
        val current = _state.value
        if (!current.canShare) return
        // A single RAW file is always shared as its preview: the sheet offers nothing else for it.
        val choice = if (current.photos.size == 1) current.choice.copy(includeRawPreviews = true) else current.choice
        _state.update { it.copy(step = ShareCleanStep.Cleaning(0, it.photos.size)) }
        work?.cancel()
        work = viewModelScope.launch {
            runCatching { choices.save(current.choice) }
            val result = try {
                cleaner.clean(current.photos, choice) { done, total ->
                    _state.update { state -> if (state.step is ShareCleanStep.Cleaning) state.copy(step = ShareCleanStep.Cleaning(done, total)) else state }
                }
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
                // No room for the copies, for instance: every photo is left out.
                ShareCleanResult(emptyList(), current.photos.map { Refusal(it.displayName ?: "", RefusalReason.WriteFailed) })
            }
            if (result.refusals.isEmpty() && result.files.isNotEmpty()) {
                _state.value = ShareCleanUiState()
                _events.send(ShareCleanEvent.Share(result.files))
            } else {
                _state.update { it.copy(step = ShareCleanStep.Finished(result)) }
            }
        }
    }

    /** From the result step: shares the photos that were cleaned. */
    fun shareCleaned() {
        val finished = _state.value.step as? ShareCleanStep.Finished ?: return
        if (finished.result.files.isEmpty()) return
        _state.value = ShareCleanUiState()
        viewModelScope.launch { _events.send(ShareCleanEvent.Share(finished.result.files)) }
    }

    /** Stops cleaning and goes back to the choices. */
    fun cancelCleaning() {
        work?.cancel()
        _state.update { it.copy(step = ShareCleanStep.Choosing) }
    }

    /** Closes the sheet, stopping any cleaning. */
    fun dismiss() {
        work?.cancel()
        _state.value = ShareCleanUiState()
    }

    companion object {
        val Factory = viewModelFactory {
            initializer {
                val graph = this[APPLICATION_KEY]!!.appGraph
                ShareCleanViewModel(
                    cleaner = graph.shareCleaner,
                    choices = graph.shareCleanChoices,
                    readReport = { ref -> withContext(Dispatchers.IO) { graph.metadataReader.read(graph.photoRepository.imageSource(ref)) } },
                    // Resolved as shared photos: ExifLab only needs to read them once, now.
                    resolve = { uris -> graph.photoRepository.open(uris, PhotoOrigin.Share) },
                    resolveShared = { uris -> graph.photoRepository.openShared(uris) },
                )
            }
        }
    }
}
