package io.github.fishpimp.exiflab.ui.photo

import android.net.Uri
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.ViewModelProvider.AndroidViewModelFactory.Companion.APPLICATION_KEY
import androidx.lifecycle.createSavedStateHandle
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import io.github.fishpimp.exiflab.appGraph
import io.github.fishpimp.exiflab.data.photos.PhotoAccessError
import io.github.fishpimp.exiflab.data.photos.PhotoAccessException
import io.github.fishpimp.exiflab.data.photos.OpenResult
import io.github.fishpimp.exiflab.data.photos.PhotoOrigin
import io.github.fishpimp.exiflab.data.photos.PhotoRef
import io.github.fishpimp.exiflab.metadata.CorruptImageException
import io.github.fishpimp.exiflab.metadata.UnsupportedImageException
import io.github.fishpimp.exiflab.metadata.model.MetadataReport
import io.github.fishpimp.exiflab.metadata.model.SensitivityCategory
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.mapLatest
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.FileNotFoundException
import java.io.IOException

/** Why a photo's metadata could not be shown. Each maps to a user-facing title and message. */
enum class PhotoLoadError {
    /** Not an image format ExifLab reads. */
    Unsupported,

    /** Recognized, but too damaged for anything to be read. */
    Damaged,

    /** ExifLab's access to the file was revoked or expired. */
    AccessLost,

    /** The file is gone. */
    NotFound,

    /** Any other read failure; usually worth a retry. */
    ReadFailed,
    ;

    /** Retrying cannot help an unsupported or damaged file. */
    val canRetry: Boolean get() = this != Unsupported && this != Damaged

    companion object {
        /** Classifies a failure of [io.github.fishpimp.exiflab.metadata.MetadataReader.read] or of the file itself. */
        fun of(error: Throwable): PhotoLoadError = when (error) {
            is UnsupportedImageException -> Unsupported
            is CorruptImageException -> Damaged
            is PhotoAccessException -> when (error.error) {
                PhotoAccessError.NotFound -> NotFound
                PhotoAccessError.PermissionDenied -> AccessLost
                PhotoAccessError.Unsupported -> Unsupported
                PhotoAccessError.ReadFailed, PhotoAccessError.WriteFailed -> ReadFailed
            }
            is SecurityException -> AccessLost
            is FileNotFoundException -> NotFound
            is IOException -> ReadFailed
            else -> ReadFailed
        }
    }
}

/** Reading the report. */
@Immutable
sealed interface PhotoLoad {
    data object Loading : PhotoLoad
    data class Failed(val error: PhotoLoadError) : PhotoLoad
    data class Loaded(val report: MetadataReport) : PhotoLoad
}

/**
 * Everything the viewer shows apart from the search text, which lives in [PhotoViewModel.query]
 * so the text field updates synchronously.
 *
 * @property browser the tag list for the current search, filter and collapse state; null until first built.
 * @property isWorking true while a copy is saved or another file is opened.
 */
@Immutable
data class PhotoUiState(
    val load: PhotoLoad = PhotoLoad.Loading,
    val browser: TagBrowser? = null,
    val mode: ValueMode = ValueMode.Readable,
    val filter: SensitivityCategory? = null,
    val isWorking: Boolean = false,
)

/** One-off outcomes the screen reacts to. */
sealed interface PhotoEvent {
    /** Show this photo: a saved copy or the original file the user picked. */
    data class OpenPhoto(val ref: PhotoRef) : PhotoEvent

    /** Saving a copy failed. */
    data class CopyFailed(val error: PhotoAccessError) : PhotoEvent

    /** Opening the picked original failed. */
    data class OpenFailed(val error: PhotoAccessError) : PhotoEvent
}

/**
 * Reads one photo's metadata and holds the viewer state: search text, readable or raw values,
 * collapsed sections and the sensitivity filter. That state lives in [SavedStateHandle], so it
 * survives rotation and process death. Filtering runs on [computeDispatcher], off the main thread.
 *
 * @param readReport reads the report; blocking work belongs on an IO dispatcher inside it.
 * @param saveCopy copies [ref] byte for byte to a document the user created; returns the copy.
 * @param openDocument resolves a document the user picked.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class PhotoViewModel(
    val ref: PhotoRef,
    private val savedState: SavedStateHandle,
    private val readReport: suspend (PhotoRef) -> MetadataReport,
    private val saveCopy: suspend (PhotoRef, Uri) -> PhotoRef,
    private val openDocument: suspend (Uri) -> PhotoRef,
    computeDispatcher: CoroutineDispatcher = Dispatchers.Default,
) : ViewModel() {
    private val load = MutableStateFlow<PhotoLoad>(PhotoLoad.Loading)
    private val queryFlow = MutableStateFlow(savedState.get<String>(KEY_QUERY).orEmpty())
    private val modeFlow = savedState.getStateFlow(KEY_RAW, false).map { if (it) ValueMode.Raw else ValueMode.Readable }
    private val filterFlow = savedState.getStateFlow<String?>(KEY_FILTER, null)
        .map { name -> SensitivityCategory.entries.firstOrNull { it.name == name } }
    private val collapsedFlow = savedState.getStateFlow<ArrayList<String>?>(KEY_COLLAPSED, null)
    private val filterCollapsedFlow = savedState.getStateFlow(KEY_FILTER_COLLAPSED, ArrayList<String>())
    private val working = MutableStateFlow(false)
    private var loadJob: Job? = null

    /** Search text, bound to the search field. */
    var query: String by mutableStateOf(queryFlow.value)
        private set

    private val _events = Channel<PhotoEvent>(Channel.BUFFERED)
    val events: Flow<PhotoEvent> = _events.receiveAsFlow()

    private val browser: Flow<TagBrowser?> = combine(
        load,
        queryFlow,
        modeFlow,
        filterFlow,
        combine(collapsedFlow, filterCollapsedFlow) { normal, filtering -> normal to filtering },
    ) { load, query, mode, filter, (collapsed, filterCollapsed) ->
        val report = (load as? PhotoLoad.Loaded)?.report ?: return@combine null
        val filtering = query.isNotBlank() || filter != null
        // Searching and filtering start with every section open; the user's own collapse state comes back after.
        val sections = if (filtering) filterCollapsed else collapsed ?: TagBrowserBuilder.defaultCollapsed(report)
        BrowserInput(report, query, mode, filter, sections.toSet())
    }.mapLatest { input ->
        input?.let { TagBrowserBuilder.build(it.report, it.query, it.mode, it.filter, it.collapsed) }
    }.flowOn(computeDispatcher)

    val state: StateFlow<PhotoUiState> = combine(load, browser, modeFlow, filterFlow, working) { load, browser, mode, filter, working ->
        PhotoUiState(load = load, browser = browser, mode = mode, filter = filter, isWorking = working)
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(STOP_TIMEOUT_MILLIS), PhotoUiState())

    init {
        reload()
    }

    /** Reads (or re-reads, after an error) the photo's metadata. */
    fun reload() {
        loadJob?.cancel()
        loadJob = viewModelScope.launch {
            load.value = PhotoLoad.Loading
            load.value = try {
                val report = readReport(ref)
                if (savedState.get<ArrayList<String>>(KEY_COLLAPSED) == null) {
                    savedState[KEY_COLLAPSED] = ArrayList(TagBrowserBuilder.defaultCollapsed(report))
                }
                PhotoLoad.Loaded(report)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                PhotoLoad.Failed(PhotoLoadError.of(e))
            }
        }
    }

    fun onQueryChange(text: String) {
        val wasFiltering = isFiltering()
        query = text
        queryFlow.value = text
        savedState[KEY_QUERY] = text
        if (wasFiltering != isFiltering()) resetFilterCollapse()
    }

    fun setMode(mode: ValueMode) {
        savedState[KEY_RAW] = mode == ValueMode.Raw
    }

    /** Shows only tags of [category], or everything again when it is already the filter. */
    fun toggleFilter(category: SensitivityCategory) {
        val current = savedState.get<String>(KEY_FILTER)
        savedState[KEY_FILTER] = if (current == category.name) null else category.name
        resetFilterCollapse()
    }

    fun clearFilter() {
        savedState[KEY_FILTER] = null
        resetFilterCollapse()
    }

    /** Collapses an expanded section or expands a collapsed one. */
    fun toggleSection(sectionId: String) {
        val key = activeCollapseKey()
        val current = currentCollapsed(key)
        savedState[key] = ArrayList(if (sectionId in current) current - sectionId else current + sectionId)
    }

    /** Expands every section, or collapses them all. */
    fun setAllExpanded(expanded: Boolean) {
        val report = (load.value as? PhotoLoad.Loaded)?.report ?: return
        savedState[activeCollapseKey()] = if (expanded) ArrayList() else ArrayList(TagBrowserBuilder.allSections(report))
    }

    /** Copies the photo byte for byte to [destination] (from the system "create document" picker), then opens the copy. */
    fun saveCopy(destination: Uri) = runWork(
        onError = { PhotoEvent.CopyFailed(it) },
        fallback = PhotoAccessError.WriteFailed,
    ) { saveCopy(ref, destination) }

    /** Opens a document the user picked, typically the original of a photo that was shared without its location. */
    fun openOriginal(document: Uri) = runWork(
        onError = { PhotoEvent.OpenFailed(it) },
        fallback = PhotoAccessError.ReadFailed,
    ) { openDocument(document) }

    private fun runWork(
        onError: (PhotoAccessError) -> PhotoEvent,
        fallback: PhotoAccessError,
        work: suspend () -> PhotoRef,
    ) {
        if (working.value) return
        working.value = true
        viewModelScope.launch {
            val event = try {
                PhotoEvent.OpenPhoto(work())
            } catch (e: CancellationException) {
                throw e
            } catch (e: PhotoAccessException) {
                onError(e.error)
            } catch (e: IOException) {
                onError(fallback)
            } catch (e: SecurityException) {
                onError(PhotoAccessError.PermissionDenied)
            } finally {
                working.value = false
            }
            _events.send(event)
        }
    }

    private fun isFiltering() = queryFlow.value.isNotBlank() || savedState.get<String>(KEY_FILTER) != null

    private fun activeCollapseKey() = if (isFiltering()) KEY_FILTER_COLLAPSED else KEY_COLLAPSED

    private fun currentCollapsed(key: String): Set<String> {
        savedState.get<ArrayList<String>>(key)?.let { return it.toSet() }
        val report = (load.value as? PhotoLoad.Loaded)?.report
        return if (key == KEY_COLLAPSED && report != null) TagBrowserBuilder.defaultCollapsed(report) else emptySet()
    }

    private fun resetFilterCollapse() {
        savedState[KEY_FILTER_COLLAPSED] = ArrayList<String>()
    }

    private class BrowserInput(
        val report: MetadataReport,
        val query: String,
        val mode: ValueMode,
        val filter: SensitivityCategory?,
        val collapsed: Set<String>,
    )

    companion object {
        private const val KEY_QUERY = "query"
        private const val KEY_RAW = "raw"
        private const val KEY_FILTER = "filter"
        private const val KEY_COLLAPSED = "collapsed"
        private const val KEY_FILTER_COLLAPSED = "filterCollapsed"
        private const val STOP_TIMEOUT_MILLIS = 5_000L

        /** A factory for the viewer of [ref], wired from the [io.github.fishpimp.exiflab.AppGraph]. */
        fun factory(ref: PhotoRef): ViewModelProvider.Factory = viewModelFactory {
            initializer {
                val graph = this[APPLICATION_KEY]!!.appGraph
                val photos = graph.photoRepository
                val reader = graph.metadataReader
                PhotoViewModel(
                    ref = ref,
                    savedState = createSavedStateHandle(),
                    readReport = { photo -> withContext(Dispatchers.IO) { reader.read(photos.imageSource(photo)) } },
                    saveCopy = photos::saveCopy,
                    openDocument = { uri ->
                        when (val result = photos.open(listOf(uri), PhotoOrigin.Document)) {
                            is OpenResult.Opened -> result.refs.first()
                            is OpenResult.Failed -> throw PhotoAccessException(result.error)
                        }
                    },
                )
            }
        }
    }
}
