package io.github.fishpimp.exiflab.ui.batch

import android.content.Intent
import android.provider.DocumentsContract
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.core.net.toUri
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.ViewModelProvider.AndroidViewModelFactory.Companion.APPLICATION_KEY
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import io.github.fishpimp.exiflab.appGraph
import io.github.fishpimp.exiflab.data.batch.BatchJobRepository
import io.github.fishpimp.exiflab.data.batch.BatchJobState
import io.github.fishpimp.exiflab.data.batch.BatchKind
import io.github.fishpimp.exiflab.data.batch.BatchOperation
import io.github.fishpimp.exiflab.data.batch.storedTitle
import io.github.fishpimp.exiflab.data.photos.PhotoOrigin
import io.github.fishpimp.exiflab.data.photos.PhotoRef
import io.github.fishpimp.exiflab.data.places.PlaceSearchState
import io.github.fishpimp.exiflab.metadata.edit.Editing
import io.github.fishpimp.exiflab.metadata.edit.StripCategory
import io.github.fishpimp.exiflab.metadata.model.GeoLocation
import io.github.fishpimp.exiflab.metadata.model.LocationStatus
import io.github.fishpimp.exiflab.metadata.model.MetadataReport
import io.github.fishpimp.exiflab.ui.edit.time.TimeShift
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.onStart
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.time.ZoneId

/** The batch editor's pages. */
enum class BatchEditorPage {
    /** Choosing the operation's values (for a time zone fix: the wizard). */
    Setup,

    /** The full-screen location picker. */
    Location,

    /** Counts, warnings, folder for copies and an example diff; Start. */
    Preview,
}

/** The folder chosen for copies of read-only photos. */
@Immutable
data class CopyFolder(val uri: String, val name: String?)

/** The operation's values as the user enters them. */
@Immutable
data class BatchDraft(
    val location: GeoLocation? = null,
    val shift: TimeShift = TimeShift(),
    val cameraZone: String? = null,
    val actualZone: String? = null,
    val strip: Set<StripCategory> = emptySet(),
) {
    /**
     * The operation, or null while it is incomplete. Author fields come from the editor's text
     * state: a blank field keeps each photo's own value.
     */
    fun operation(kind: BatchKind, artist: String, copyright: String): BatchOperation? = when (kind) {
        BatchKind.Location -> location?.let { BatchOperation.SetLocation(it.latitude, it.longitude, it.altitudeMeters) }
        BatchKind.ShiftTime -> shift.takeUnless { it.isZero }?.let { BatchOperation.ShiftTime(it.duration.seconds) }
        BatchKind.TimeZone -> if (cameraZone != null && actualZone != null && cameraZone != actualZone) {
            BatchOperation.FixTimeZone(cameraZone, actualZone)
        } else {
            null
        }
        BatchKind.Author -> {
            val a = artist.trim()
            val c = copyright.trim()
            if (a.isEmpty() && c.isEmpty()) {
                null
            } else {
                BatchOperation.SetAuthorship(a.ifEmpty { null }, c.ifEmpty { null }, changeArtist = a.isNotEmpty(), changeCopyright = c.isNotEmpty())
            }
        }
        BatchKind.Strip -> strip.takeIf { it.isNotEmpty() }?.let { BatchOperation.Strip.of(it) }
    }
}

/** Loading the draft's photos. */
sealed interface DraftPhotos {
    data object Loading : DraftPhotos

    /** The draft is gone (already started, or cleaned up). */
    data object Missing : DraftPhotos

    data class Loaded(val photos: List<PhotoRef>) : DraftPhotos
}

@Immutable
data class BatchEditUiState(
    val kind: BatchKind,
    val photos: DraftPhotos = DraftPhotos.Loading,
    /** The first photo's metadata: starting point for the time previews and the map. */
    val sample: MetadataReport? = null,
    val page: BatchEditorPage = BatchEditorPage.Setup,
    val draft: BatchDraft = BatchDraft(),
    val preview: BatchPreview? = null,
    val copyFolder: CopyFolder? = null,
    val starting: Boolean = false,
    val placeSearch: PlaceSearchState = PlaceSearchState.Idle,
    val readingOtherPhoto: Boolean = false,
) {
    val count: Int get() = (photos as? DraftPhotos.Loaded)?.photos?.size ?: 0
}

/** One-off results for the screen. */
sealed interface BatchEditEvent {
    /** The job runs; show its progress. */
    data class Started(val jobId: String) : BatchEditEvent

    data class Message(val message: BatchEditMessage) : BatchEditEvent
}

enum class BatchEditMessage { LocationCopied, OtherPhotoNoLocation, OtherPhotoLocationRemoved, OtherPhotoUnreadable, FolderUnavailable, StartFailed }

/**
 * The batch editor for the photos of draft job [jobId]: collects the operation's values, previews
 * what will happen to every photo (see [BatchAnalyzer]), and starts the background job.
 *
 * @param resolveFolder takes lasting access to a folder from `ACTION_OPEN_DOCUMENT_TREE` and
 *   returns it as a folder for copies; null when that is not possible.
 */
class BatchEditViewModel(
    private val jobId: String,
    val kind: BatchKind,
    private val jobs: BatchJobRepository,
    private val analyzer: BatchAnalyzer,
    private val readReport: suspend (PhotoRef) -> MetadataReport,
    private val readOtherPhoto: suspend (String) -> MetadataReport,
    private val resolveFolder: suspend (String) -> CopyFolder?,
    searchPlaces: (Flow<String>) -> Flow<PlaceSearchState>,
) : ViewModel() {
    private val local = MutableStateFlow(BatchEditUiState(kind))
    private val placeQueryFlow = MutableStateFlow("")
    private val _events = Channel<BatchEditEvent>(Channel.BUFFERED)
    private var previewJob: Job? = null
    private var previewedOperation: BatchOperation? = null

    val events: Flow<BatchEditEvent> = _events.receiveAsFlow()

    /** Author fields, bound to their inputs. Blank keeps each photo's own value. */
    var artist: String by mutableStateOf("")
        private set
    var copyright: String by mutableStateOf("")
        private set

    /** Search text of the location picker. */
    var placeQuery: String by mutableStateOf("")
        private set

    val state: StateFlow<BatchEditUiState> = combine(
        local,
        searchPlaces(placeQueryFlow).onStart { emit(PlaceSearchState.Idle) },
    ) { state, search -> state.copy(placeSearch = search) }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(STOP_TIMEOUT_MILLIS), BatchEditUiState(kind))

    init {
        viewModelScope.launch {
            val job = jobs.job(jobId)
            if (job == null || job.state != BatchJobState.Draft) {
                local.update { it.copy(photos = DraftPhotos.Missing) }
                return@launch
            }
            val photos = jobs.items(jobId).map { it.photoRef() }
            local.update { it.copy(photos = DraftPhotos.Loaded(photos)) }
            val sample = photos.firstOrNull()?.let { first -> runCatching { readReport(first) }.getOrNull() }
            local.update { it.copy(sample = sample) }
        }
    }

    /** The operation as entered so far, or null while incomplete. */
    fun operation(): BatchOperation? = local.value.draft.operation(kind, artist, copyright)

    fun onArtistChange(text: String) {
        artist = text
    }

    fun onCopyrightChange(text: String) {
        copyright = text
    }

    fun setShift(shift: TimeShift) = local.update { it.copy(draft = it.draft.copy(shift = shift)) }

    fun setLocation(latitude: Double, longitude: Double, altitudeMeters: Double?) {
        local.update { it.copy(draft = it.draft.copy(location = GeoLocation(latitude, longitude, altitudeMeters)), page = BatchEditorPage.Setup) }
    }

    fun toggleStrip(category: StripCategory) = local.update { state ->
        val current = state.draft.strip
        state.copy(draft = state.draft.copy(strip = if (category in current) current - category else current + category))
    }

    /** The time zone wizard's result; goes straight to the preview. */
    fun setTimeZones(cameraZone: ZoneId, actualZone: ZoneId) {
        local.update { it.copy(draft = it.draft.copy(cameraZone = cameraZone.id, actualZone = actualZone.id)) }
        openPreview()
    }

    fun openLocationPicker() {
        onPlaceQueryChange("")
        local.update { it.copy(page = BatchEditorPage.Location) }
    }

    fun onPlaceQueryChange(text: String) {
        placeQuery = text
        placeQueryFlow.value = text
    }

    /** Back inside the editor; false when the editor itself should close. */
    fun back(): Boolean {
        val page = local.value.page
        if (page == BatchEditorPage.Setup) return false
        local.update { it.copy(page = BatchEditorPage.Setup) }
        return true
    }

    /** Shows the preview, (re)checking the photos when the operation changed since the last check. */
    fun openPreview() {
        val operation = operation() ?: return
        val photos = (local.value.photos as? DraftPhotos.Loaded)?.photos ?: return
        local.update { it.copy(page = BatchEditorPage.Preview) }
        if (operation == previewedOperation && previewJob != null) return
        previewedOperation = operation
        previewJob?.cancel()
        local.update { it.copy(preview = analyzer.initial(photos, kind)) }
        previewJob = viewModelScope.launch {
            analyzer.analyze(photos, operation).flowOn(Dispatchers.Default).collect { preview ->
                local.update { it.copy(preview = preview) }
            }
        }
    }

    /** Reads another photo's position and uses it for every photo. */
    fun copyLocationFrom(photoUri: String) {
        if (local.value.readingOtherPhoto) return
        local.update { it.copy(readingOtherPhoto = true) }
        viewModelScope.launch {
            val message = try {
                val other = readOtherPhoto(photoUri)
                val location = other.location
                when {
                    location != null -> {
                        setLocation(location.latitude, location.longitude, location.altitudeMeters)
                        BatchEditMessage.LocationCopied
                    }
                    other.locationStatus == LocationStatus.Redacted -> BatchEditMessage.OtherPhotoLocationRemoved
                    else -> BatchEditMessage.OtherPhotoNoLocation
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                BatchEditMessage.OtherPhotoUnreadable
            } finally {
                local.update { it.copy(readingOtherPhoto = false) }
            }
            _events.send(BatchEditEvent.Message(message))
        }
    }

    /** A folder picked with `ACTION_OPEN_DOCUMENT_TREE`, or null when the picker was closed. */
    fun onCopyFolderPicked(treeUri: String?) {
        treeUri ?: return
        viewModelScope.launch {
            val folder = try {
                resolveFolder(treeUri)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                null
            }
            if (folder == null) {
                _events.send(BatchEditEvent.Message(BatchEditMessage.FolderUnavailable))
            } else {
                local.update { it.copy(copyFolder = folder) }
            }
        }
    }

    /** Skips read-only photos instead of copying them. */
    fun clearCopyFolder() = local.update { it.copy(copyFolder = null) }

    /**
     * Starts the job in the background. Its title (shown in History and the notification) is the
     * kind's [storedTitle], so it follows the app language.
     */
    fun start() {
        val operation = operation() ?: return
        if (local.value.starting) return
        local.update { it.copy(starting = true) }
        viewModelScope.launch {
            val started = try {
                jobs.start(jobId, operation, kind.storedTitle, local.value.copyFolder?.uri)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                false
            }
            if (started) {
                previewJob?.cancel()
                _events.send(BatchEditEvent.Started(jobId))
            } else {
                local.update { it.copy(starting = false) }
                _events.send(BatchEditEvent.Message(BatchEditMessage.StartFailed))
            }
        }
    }

    companion object {
        private const val STOP_TIMEOUT_MILLIS = 5_000L

        fun factory(jobId: String, kind: BatchKind): ViewModelProvider.Factory = viewModelFactory {
            initializer {
                val application = this[APPLICATION_KEY]!!
                val graph = application.appGraph
                val photos = graph.photoRepository
                val reader = graph.metadataReader
                val read: suspend (PhotoRef) -> MetadataReport = { ref -> withContext(Dispatchers.IO) { reader.read(photos.imageSource(ref)) } }
                val resolver = application.contentResolver
                BatchEditViewModel(
                    jobId = jobId,
                    kind = kind,
                    jobs = graph.batchJobs,
                    analyzer = BatchAnalyzer(readReport = read, planner = { Editing.planner }, canWrite = graph.writeEngine::canWrite),
                    readReport = read,
                    readOtherPhoto = { uri -> read(photos.resolve(uri.toUri(), PhotoOrigin.Picker)) },
                    resolveFolder = { treeUri ->
                        withContext(Dispatchers.IO) {
                            val tree = treeUri.toUri()
                            resolver.takePersistableUriPermission(tree, Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION)
                            val document = DocumentsContract.buildDocumentUriUsingTree(tree, DocumentsContract.getTreeDocumentId(tree))
                            val name = resolver.query(document, arrayOf(DocumentsContract.Document.COLUMN_DISPLAY_NAME), null, null, null)
                                ?.use { cursor -> if (cursor.moveToFirst()) cursor.getString(0) else null }
                            CopyFolder(document.toString(), name)
                        }
                    },
                    searchPlaces = { queries -> graph.placeSearchRepository.searchAsYouType(queries) },
                )
            }
        }
    }
}
