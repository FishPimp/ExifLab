package io.github.fishpimp.exiflab.ui.export

import android.content.ContentResolver
import android.net.Uri
import android.provider.DocumentsContract
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.ViewModelProvider.AndroidViewModelFactory.Companion.APPLICATION_KEY
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import io.github.fishpimp.exiflab.appGraph
import io.github.fishpimp.exiflab.data.export.ExportFileNames
import io.github.fishpimp.exiflab.data.export.ExportFormat
import io.github.fishpimp.exiflab.data.export.ExportProgress
import io.github.fishpimp.exiflab.data.export.ExportSettings
import io.github.fishpimp.exiflab.data.export.MetadataExporter
import io.github.fishpimp.exiflab.data.export.pdf.PdfPalette
import io.github.fishpimp.exiflab.data.photos.PhotoRef
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.IOException
import java.io.OutputStream
import java.time.LocalDate

/** The photos an export is about, and where they came from. */
data class ExportRequest(
    val photos: List<PhotoRef>,
    /** The folder they share, for the suggested file name of a batch export. */
    val folderName: String? = null,
) {
    val isSingle: Boolean get() = photos.size == 1

    /** The one photo's name, for the sheet and the file name. */
    val photoName: String? get() = photos.singleOrNull()?.let { MetadataExporter.displayName(it, null) }
}

/**
 * What the export sheet and its progress dialog show.
 *
 * @property request the photos, while the sheet is open or an export runs.
 * @property mapOffline offline mode is on or there is no connection, so a PDF gets no map.
 * @property progress non-null while an export runs.
 */
data class ExportUiState(
    val request: ExportRequest? = null,
    val sheetOpen: Boolean = false,
    val settings: ExportSettings = ExportSettings(),
    val mapOffline: Boolean = false,
    val progress: ExportProgress? = null,
)

/** Why an export did not produce a file. */
enum class ExportError {
    /** The destination could not be written. */
    Write,

    /** The only photo, or every photo, could not be read. */
    Unreadable,

    /** Writing the PDF itself failed. */
    Pdf,
}

sealed interface ExportEvent {
    /** Ask the system where to save, suggesting [fileName]. */
    data class ChooseDestination(val fileName: String, val mimeType: String) : ExportEvent

    /**
     * The file is written. [failed] photos could not be read and were left out.
     */
    data class Finished(val uri: Uri, val mimeType: String, val exported: Int, val failed: Int, val single: Boolean) : ExportEvent

    data class Failed(val error: ExportError, val single: Boolean) : ExportEvent

    data object Cancelled : ExportEvent
}

/** Opens and removes the document an export writes to. */
interface ExportDocuments {
    fun openOutput(uri: Uri): OutputStream

    /** Removes a half-written export; failures are ignored. */
    fun delete(uri: Uri)
}

class ContentResolverExportDocuments(private val resolver: ContentResolver) : ExportDocuments {
    override fun openOutput(uri: Uri): OutputStream =
        resolver.openOutputStream(uri, "wt") ?: throw IOException("No output stream for the export")

    override fun delete(uri: Uri) {
        runCatching { DocumentsContract.deleteDocument(resolver, uri) }
    }
}

/**
 * Runs metadata exports for one screen: the options sheet, the system "Save as" step, writing
 * off the main thread with progress, cancelling, and cleaning up after a failure.
 */
class ExportViewModel(
    private val exporter: MetadataExporter,
    private val documents: ExportDocuments,
    mapOffline: Flow<Boolean>,
    private val today: () -> LocalDate = { LocalDate.now() },
) : ViewModel() {
    private val local = MutableStateFlow(ExportUiState())
    private val eventChannel = Channel<ExportEvent>(Channel.BUFFERED)
    private var job: Job? = null

    val state: StateFlow<ExportUiState> = combine(local, mapOffline) { state, offline -> state.copy(mapOffline = offline) }
        .stateIn(viewModelScope, SharingStarted.Eagerly, ExportUiState())

    val events: Flow<ExportEvent> = eventChannel.receiveAsFlow()

    /** The state without the connectivity part, for tests. */
    internal val current: StateFlow<ExportUiState> = local.asStateFlow()

    /** Opens the sheet for [photos]. Ignored while an export runs. */
    fun open(photos: List<PhotoRef>, folderName: String? = null) {
        if (photos.isEmpty() || local.value.progress != null) return
        local.update { state ->
            val previous = state.request
            // Keep the choices made last time on this screen, but start a new kind of export with its own default format.
            val settings = if (previous != null && previous.isSingle == (photos.size == 1)) state.settings else ExportSettings.defaultFor(photos.size)
            state.copy(request = ExportRequest(photos, folderName), sheetOpen = true, settings = settings)
        }
    }

    fun dismiss() {
        local.update { it.copy(sheetOpen = false) }
    }

    fun updateSettings(settings: ExportSettings) {
        local.update { it.copy(settings = settings) }
    }

    /** The name suggested for the current request and format. */
    fun suggestedFileName(state: ExportUiState = local.value): String? {
        val request = state.request ?: return null
        return suggestedFileName(request, state.settings.format, today())
    }

    /** Closes the sheet and asks for a destination. */
    fun confirm() {
        val state = local.value
        val name = suggestedFileName(state) ?: return
        local.update { it.copy(sheetOpen = false) }
        eventChannel.trySend(ExportEvent.ChooseDestination(name, state.settings.format.mimeType))
    }

    /**
     * The user picked where to save ([uri]), or backed out of the system dialog (null). Starts
     * writing in the background; [palette] colors a PDF report.
     */
    fun onDestinationChosen(uri: Uri?, palette: PdfPalette) {
        val state = local.value
        val request = state.request ?: return
        if (uri == null || job?.isActive == true) return
        val settings = state.settings
        local.update { it.copy(progress = ExportProgress(0, request.photos.size)) }
        job = viewModelScope.launch {
            var keepFile = false
            try {
                val result = withContext(Dispatchers.IO) {
                    documents.openOutput(uri).use { output ->
                        exporter.export(request.photos, settings, output, palette) { progress ->
                            local.update { it.copy(progress = progress) }
                        }
                    }
                }
                if (result.exported == 0) {
                    eventChannel.send(ExportEvent.Failed(ExportError.Unreadable, request.isSingle))
                } else {
                    keepFile = true
                    eventChannel.send(ExportEvent.Finished(uri, settings.format.mimeType, result.exported, result.failed.size, request.isSingle))
                }
            } catch (e: CancellationException) {
                eventChannel.trySend(ExportEvent.Cancelled)
                throw e
            } catch (e: Exception) {
                val error = if (e !is IOException && settings.format == ExportFormat.Pdf) ExportError.Pdf else ExportError.Write
                eventChannel.send(ExportEvent.Failed(error, request.isSingle))
            } finally {
                if (!keepFile) withContext(NonCancellable + Dispatchers.IO) { documents.delete(uri) }
                local.update { it.copy(progress = null) }
            }
        }
    }

    /** Stops a running export; the half-written file is removed. */
    fun cancel() {
        job?.cancel()
    }

    companion object {
        fun suggestedFileName(request: ExportRequest, format: ExportFormat, today: LocalDate): String =
            if (request.isSingle) {
                ExportFileNames.forPhoto(request.photoName, format)
            } else {
                ExportFileNames.forSelection(request.folderName, format, today)
            }

        val Factory: ViewModelProvider.Factory = viewModelFactory {
            initializer {
                val graph = this[APPLICATION_KEY]!!.appGraph
                ExportViewModel(
                    exporter = graph.metadataExporter,
                    documents = ContentResolverExportDocuments(graph.contentResolver),
                    mapOffline = combine(graph.offlineMode.changes, graph.networkMonitor.isConnected) { offline, connected -> offline || !connected },
                )
            }
        }
    }
}
