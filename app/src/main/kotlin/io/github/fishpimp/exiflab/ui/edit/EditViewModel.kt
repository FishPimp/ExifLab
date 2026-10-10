package io.github.fishpimp.exiflab.ui.edit

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.ViewModelProvider.AndroidViewModelFactory.Companion.APPLICATION_KEY
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import androidx.core.net.toUri
import io.github.fishpimp.exiflab.appGraph
import io.github.fishpimp.exiflab.data.history.EditMode
import io.github.fishpimp.exiflab.data.history.SaveError
import io.github.fishpimp.exiflab.data.photos.PhotoOrigin
import io.github.fishpimp.exiflab.data.photos.PhotoRef
import io.github.fishpimp.exiflab.data.places.PlaceSearchState
import io.github.fishpimp.exiflab.data.save.SaveOutcome
import io.github.fishpimp.exiflab.data.save.SaveRejection
import io.github.fishpimp.exiflab.data.save.SaveTarget
import io.github.fishpimp.exiflab.data.save.SavedEditNotice
import io.github.fishpimp.exiflab.data.save.SavedEditNotices
import io.github.fishpimp.exiflab.metadata.edit.ChangePlanner
import io.github.fishpimp.exiflab.metadata.edit.EditOperation
import io.github.fishpimp.exiflab.metadata.edit.Editing
import io.github.fishpimp.exiflab.metadata.edit.EditableField
import io.github.fishpimp.exiflab.metadata.edit.FieldKind
import io.github.fishpimp.exiflab.metadata.edit.TagCatalog
import io.github.fishpimp.exiflab.metadata.edit.Validation
import io.github.fishpimp.exiflab.metadata.edit.ValidationError
import io.github.fishpimp.exiflab.metadata.model.DirectoryGroup
import io.github.fishpimp.exiflab.metadata.model.LocationStatus
import io.github.fishpimp.exiflab.metadata.model.MetadataReport
import io.github.fishpimp.exiflab.ui.photo.PhotoLoadError
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
import kotlinx.coroutines.flow.onStart
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.transformLatest
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.time.Duration
import java.time.LocalDateTime
import java.time.ZoneId
import java.time.ZoneOffset

/**
 * The single-photo editor: reads the photo, queues [EditOperation]s, keeps a live [EditPlan][io.github.fishpimp.exiflab.metadata.edit.EditPlan]
 * (planned on [computeDispatcher] whenever the queue changes) and saves it where the file allows.
 * Nothing is written before [save].
 *
 * The planner and the tag catalog are resolved lazily and may be missing from a build; the editor
 * then shows them as unavailable instead of failing.
 *
 * @param readReport reads a photo's report; blocking work belongs on an IO dispatcher inside it.
 * @param planner the [ChangePlanner]; may throw when it is not available.
 * @param catalog the [TagCatalog]; may throw when it is not available.
 * @param resolveCopy turns a saved copy's document URI into a [PhotoRef] to open.
 * @param readOtherPhoto reads another photo (by content URI) for "Copy location from another photo".
 * @param searchPlaces search-as-you-type for the location picker.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class EditViewModel(
    val ref: PhotoRef,
    private val readReport: suspend (PhotoRef) -> MetadataReport,
    private val planner: () -> ChangePlanner,
    private val catalog: () -> TagCatalog,
    private val saving: EditSaving,
    private val notices: SavedEditNotices,
    private val resolveCopy: suspend (String) -> PhotoRef,
    private val readOtherPhoto: suspend (String) -> MetadataReport,
    searchPlaces: (Flow<String>) -> Flow<PlaceSearchState>,
    private val computeDispatcher: CoroutineDispatcher = Dispatchers.Default,
) : ViewModel() {
    private val load = MutableStateFlow<EditLoad>(EditLoad.Loading)
    private val edits = MutableStateFlow<List<PendingEdit>>(emptyList())
    private val local = MutableStateFlow(LocalState())
    private val fieldQueryFlow = MutableStateFlow("")
    private val placeQueryFlow = MutableStateFlow("")
    private var nextEditId = 1L
    private var loadJob: Job? = null

    private val resolvedPlanner: ChangePlanner? by lazy { resolve(planner) }
    private val resolvedCatalog: TagCatalog? by lazy { resolve(catalog) }

    /** Search text of "All fields", bound to its search field. */
    var fieldQuery: String by mutableStateOf("")
        private set

    /** Search text of the location picker. */
    var placeQuery: String by mutableStateOf("")
        private set

    private val _events = Channel<EditEvent>(Channel.BUFFERED)
    val events: Flow<EditEvent> = _events.receiveAsFlow()

    private val plan: Flow<PlanState> = combine(load, edits) { load, edits -> (load as? EditLoad.Loaded)?.report to edits }
        .transformLatest { (report, queued) ->
            when {
                report == null || queued.isEmpty() -> emit(PlanState.Empty)
                else -> {
                    emit(PlanState.Planning)
                    emit(planFor(report, queued))
                }
            }
        }

    private val fields: Flow<FieldsState> = combine(load, edits, fieldQueryFlow, local.map { it.addedFields }) { load, edits, query, added ->
        val report = (load as? EditLoad.Loaded)?.report ?: return@combine FieldsState.Loading
        val catalog = resolvedCatalog ?: return@combine FieldsState.Unavailable
        buildFields(catalog, report, edits, query, added)
    }.flowOn(computeDispatcher)

    private val placeSearch: Flow<PlaceSearchState> = searchPlaces(placeQueryFlow).onStart { emit(PlaceSearchState.Idle) }

    val state: StateFlow<EditUiState> = combine(
        combine(load, edits, ::Pair),
        plan,
        fields,
        local,
        placeSearch,
    ) { (load, edits), plan, fields, local, search ->
        val report = (load as? EditLoad.Loaded)?.report
        EditUiState(
            load = load,
            edits = edits,
            plan = plan,
            fields = fields,
            fieldEditor = local.fieldEditor,
            saveOptions = local.saveOptionsOverride ?: report?.let { SaveOptions.of(ref, it, saving::canWrite) },
            save = local.save,
            page = local.page,
            placeSearch = search,
            readingOtherPhoto = local.readingOtherPhoto,
        )
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(STOP_TIMEOUT_MILLIS), EditUiState())

    init {
        reload()
    }

    fun reload() {
        loadJob?.cancel()
        loadJob = viewModelScope.launch {
            load.value = EditLoad.Loading
            load.value = try {
                EditLoad.Loaded(readReport(ref))
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                EditLoad.Failed(PhotoLoadError.of(e))
            }
        }
    }

    // ---- Queue ----

    /** Queues [operation] in [slot]; see [plusEdit] for how it combines with earlier edits. */
    fun queue(operation: EditOperation, slot: EditSlot, fieldLabel: String? = null, fieldValue: String? = null) {
        val edit = PendingEdit(nextEditId++, slot, operation, fieldLabel, fieldValue)
        edits.update { it.plusEdit(edit) }
        clearSaveFailure()
    }

    fun removeEdit(id: Long) {
        edits.update { list -> list.filterNot { it.id == id } }
        clearSaveFailure()
    }

    /** Drops every queued edit ("Discard"). */
    fun discardEdits() {
        edits.value = emptyList()
        local.update { it.copy(fieldEditor = null, save = SaveState.Idle) }
    }

    fun setCaptureTime(local: LocalDateTime, offset: ZoneOffset?, alsoModifyDate: Boolean) =
        queue(EditOperation.SetCaptureTime(local, offset, alsoModifyDate), EditSlot.CaptureTime)

    fun shiftTime(by: Duration) {
        if (!by.isZero) queue(EditOperation.ShiftCaptureTime(by), EditSlot.TimeShift)
    }

    fun changeTimeZone(cameraZone: ZoneId, actualZone: ZoneId) {
        queue(EditOperation.ChangeTimeZone(cameraZone, actualZone), EditSlot.TimeZone)
        closePage()
    }

    fun setUtcOffset(offset: ZoneOffset) = queue(EditOperation.SetUtcOffset(offset), EditSlot.UtcOffset)

    fun setLocation(latitude: Double, longitude: Double, altitudeMeters: Double?) {
        queue(EditOperation.SetLocation(latitude, longitude, altitudeMeters), EditSlot.Location)
        if (local.value.page == EditorPage.Location) closePage()
    }

    fun removeLocation(includePlaceNames: Boolean) =
        queue(EditOperation.RemoveLocation(includePlaceNames), EditSlot.Location)

    /** Sets artist and copyright; blank text clears that field. */
    fun setAuthorship(artist: String, copyright: String) =
        queue(EditOperation.SetAuthorship(artist.trim().ifEmpty { null }, copyright.trim().ifEmpty { null }), EditSlot.Authorship)

    /** Reads another photo's GPS position and queues it, or explains why there is none. */
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
                        EditMessage.LocationCopied
                    }
                    other.locationStatus == LocationStatus.Redacted -> EditMessage.OtherPhotoLocationRemoved
                    else -> EditMessage.OtherPhotoNoLocation
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                EditMessage.OtherPhotoUnreadable
            } finally {
                local.update { it.copy(readingOtherPhoto = false) }
            }
            _events.send(EditEvent.Message(message))
        }
    }

    // ---- Pages ----

    fun openPage(page: EditorPage) {
        local.update { it.copy(page = page) }
        if (page == EditorPage.Location) onPlaceQueryChange("")
    }

    fun closePage() {
        local.update { it.copy(page = EditorPage.Overview, save = if (it.save is SaveState.Failed) SaveState.Idle else it.save) }
    }

    fun onPlaceQueryChange(text: String) {
        placeQuery = text
        placeQueryFlow.value = text
    }

    // ---- All fields ----

    fun onFieldQueryChange(text: String) {
        fieldQuery = text
        fieldQueryFlow.value = text
    }

    /** Opens [fieldId]'s inline editor with its queued or current value. */
    fun editField(fieldId: String) {
        val catalog = resolvedCatalog ?: return
        val field = catalog.field(fieldId) ?: return
        val report = (load.value as? EditLoad.Loaded)?.report ?: return
        val pending = edits.value.lastOrNull { it.slot == EditSlot.Field(fieldId) }
        val start = if (pending != null) pending.fieldValue.orEmpty() else currentValue(catalog, field, report).orEmpty()
        local.update { it.copy(fieldEditor = FieldEditor(fieldId, start)) }
    }

    fun closeFieldEditor() = local.update { it.copy(fieldEditor = null) }

    /** Updates the draft. Errors other than "empty" show while typing; "empty" only once applied. */
    fun onFieldDraftChange(text: String) {
        val editor = local.value.fieldEditor ?: return
        val field = resolvedCatalog?.field(editor.fieldId) ?: return
        val error = validate(field, text)?.takeUnless { it == ValidationError.Empty }
        local.update { it.copy(fieldEditor = editor.copy(draft = text, error = error)) }
    }

    /** Validates the draft and queues it, or shows why it cannot be used. */
    fun applyField(): Boolean {
        val editor = local.value.fieldEditor ?: return false
        val catalog = resolvedCatalog ?: return false
        val field = catalog.field(editor.fieldId) ?: return false
        return when (val result = safeValidate(catalog, field, editor.draft)) {
            is Validation.Valid -> {
                val operation = runCatching { catalog.operationFor(field, result.normalized) }.getOrNull() ?: return false
                queue(operation, EditSlot.Field(field.id), field.label, result.normalized)
                local.update { it.copy(fieldEditor = null) }
                true
            }
            is Validation.Invalid -> {
                local.update { it.copy(fieldEditor = editor.copy(error = result.error)) }
                false
            }
        }
    }

    /** Queues removing [fieldId] from the file. */
    fun removeField(fieldId: String) {
        val catalog = resolvedCatalog ?: return
        val field = catalog.field(fieldId) ?: return
        val operation = runCatching { catalog.operationFor(field, null) }.getOrNull() ?: return
        queue(operation, EditSlot.Field(fieldId), field.label, null)
        local.update { state -> state.copy(fieldEditor = state.fieldEditor?.takeUnless { it.fieldId == fieldId }) }
    }

    /** Drops the queued change of [fieldId]. */
    fun revertField(fieldId: String) {
        edits.update { list -> list.filterNot { it.slot == EditSlot.Field(fieldId) } }
    }

    /** Shows an absent field in the list and opens it for editing. */
    fun addField(fieldId: String) {
        local.update { it.copy(addedFields = it.addedFields + fieldId) }
        editField(fieldId)
    }

    // ---- Saving ----

    /** Saves with [mode]; copies (and sidecars without a known place) first ask for a destination. */
    fun save(mode: SaveMode) {
        if (state.value.isSaving) return
        when (mode) {
            SaveMode.InPlace -> run(mode, SaveTarget.InPlace)
            SaveMode.Sidecar -> run(mode, SaveTarget.Sidecar())
            SaveMode.Copy -> {
                local.update { it.copy(save = SaveState.ChoosingDestination(mode)) }
                val report = (load.value as? EditLoad.Loaded)?.report
                val mime = ref.mimeType ?: report?.format?.mimeType ?: GENERIC_MIME_TYPE
                viewModelScope.launch { _events.send(EditEvent.ChooseDestination(mode, copyName(), mime)) }
            }
        }
    }

    /** The destination picked for a copy or a sidecar, or null when the user cancelled. */
    fun onDestinationChosen(mode: SaveMode, uri: String?) {
        if (uri == null) {
            val failure = if (mode == SaveMode.Sidecar) SaveState.Failed(SaveFailure.NoDestination) else SaveState.Idle
            local.update { it.copy(save = failure) }
            return
        }
        when (mode) {
            SaveMode.Copy -> run(mode, SaveTarget.Copy(uri))
            SaveMode.Sidecar -> run(mode, SaveTarget.Sidecar(uri))
            SaveMode.InPlace -> Unit
        }
    }

    /** Returns to the viewer after a save whose skipped changes were shown. */
    fun finishAfterSave() {
        val saved = local.value.save as? SaveState.SavedWithSkipped ?: return
        local.update { it.copy(save = SaveState.Idle) }
        viewModelScope.launch { _events.send(EditEvent.Finished(saved.openCopy)) }
    }

    fun dismissSaveFailure() = clearSaveFailure()

    private fun run(mode: SaveMode, target: SaveTarget) {
        val plan = (state.value.plan as? PlanState.Ready)?.plan
        if (plan == null || plan.changes.isEmpty) {
            local.update { it.copy(save = SaveState.Failed(SaveFailure.NoPlan)) }
            return
        }
        local.update { it.copy(save = SaveState.Saving(mode)) }
        viewModelScope.launch {
            val outcome = try {
                saving.save(ref, plan, target)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                SaveOutcome.Failed(null, SaveError.Unknown)
            }
            handle(outcome)
        }
    }

    private suspend fun handle(outcome: SaveOutcome) {
        when (outcome) {
            is SaveOutcome.Saved -> {
                val copy = if (outcome.mode == EditMode.Copy) {
                    try {
                        resolveCopy(outcome.targetUri)
                    } catch (e: CancellationException) {
                        throw e
                    } catch (e: Exception) {
                        null
                    }
                } else {
                    null
                }
                notices.post(
                    SavedEditNotice(
                        recordId = outcome.recordId,
                        photoUri = copy?.uri ?: ref.uri,
                        mode = outcome.mode,
                        skipped = outcome.skipped.size,
                    ),
                )
                edits.value = emptyList()
                if (outcome.skipped.isEmpty()) {
                    local.update { it.copy(save = SaveState.Idle, fieldEditor = null) }
                    _events.send(EditEvent.Finished(copy))
                } else {
                    local.update { it.copy(save = SaveState.SavedWithSkipped(outcome.mode, outcome.skipped, copy), fieldEditor = null) }
                }
            }
            is SaveOutcome.NeedsSidecarDestination -> {
                local.update { it.copy(save = SaveState.ChoosingDestination(SaveMode.Sidecar)) }
                _events.send(EditEvent.ChooseDestination(SaveMode.Sidecar, outcome.suggestedName, outcome.mimeType))
            }
            is SaveOutcome.Rejected -> {
                // The file turned out not to allow this mode: offer what it does allow.
                val override = when (outcome.reason) {
                    SaveRejection.ReadOnly -> SaveOptions(listOf(SaveMode.Copy), SaveReason.ReadOnly)
                    SaveRejection.UseSidecar -> SaveOptions(listOf(SaveMode.Sidecar), SaveReason.Raw)
                    SaveRejection.UnsupportedFormat -> SaveOptions(listOf(SaveMode.Sidecar), SaveReason.UnsupportedFormat)
                    SaveRejection.NotRestorable, SaveRejection.NotFound -> null
                }
                local.update { it.copy(save = SaveState.Failed(SaveFailure.Rejected(outcome.reason)), saveOptionsOverride = override ?: it.saveOptionsOverride) }
            }
            is SaveOutcome.RolledBack -> local.update { it.copy(save = SaveState.Failed(SaveFailure.Error(outcome.error, rolledBack = true))) }
            is SaveOutcome.Failed -> local.update { it.copy(save = SaveState.Failed(SaveFailure.Error(outcome.error, rolledBack = false))) }
        }
    }

    private fun clearSaveFailure() {
        local.update { if (it.save is SaveState.Failed) it.copy(save = SaveState.Idle) else it }
    }

    /** "IMG_1234_edited.jpg" for "IMG_1234.jpg". */
    private fun copyName(): String {
        val report = (load.value as? EditLoad.Loaded)?.report
        val name = ref.displayName?.takeIf { it.isNotBlank() } ?: report?.fileName ?: "photo.${report?.format?.extensions?.firstOrNull() ?: "jpg"}"
        val dot = name.lastIndexOf('.')
        return if (dot > 0) "${name.substring(0, dot)}$COPY_SUFFIX${name.substring(dot)}" else "$name$COPY_SUFFIX"
    }

    // ---- Planning and fields ----

    private suspend fun planFor(report: MetadataReport, queued: List<PendingEdit>): PlanState {
        val planner = resolvedPlanner ?: return PlanState.Unavailable
        return withContext(computeDispatcher) {
            try {
                PlanState.Ready(planner.plan(report, queued.map { it.operation }))
            } catch (e: CancellationException) {
                throw e
            } catch (e: NotImplementedError) {
                PlanState.Unavailable
            } catch (e: Exception) {
                PlanState.Failed(e.message)
            }
        }
    }

    private fun buildFields(catalog: TagCatalog, report: MetadataReport, edits: List<PendingEdit>, query: String, added: Set<String>): FieldsState {
        val all = try {
            catalog.fields
        } catch (e: NotImplementedError) {
            return FieldsState.Unavailable
        } catch (e: Exception) {
            return FieldsState.Unavailable
        }
        val pendingById = edits.mapNotNull { edit -> (edit.slot as? EditSlot.Field)?.let { it.fieldId to PendingFieldValue(edit.fieldValue) } }.toMap()
        val rows = all.map { field -> FieldRow(field, currentValue(catalog, field, report), pendingById[field.id]) }
        val shown = rows.filter { it.field.common || it.current != null || it.pending != null || it.field.id in added }
        val absent = rows.filter { it.current == null && it.pending == null && !it.field.common && it.field.id !in added }.map { it.field }
        val needle = query.trim()
        val matching = if (needle.isEmpty()) shown else shown.filter { it.matches(needle) }
        val sections = buildList {
            val common = matching.filter { it.field.common }
            if (common.isNotEmpty()) add(FieldSection(FieldSectionKind.Common, common))
            matching.filterNot { it.field.common }
                .groupBy { it.field.group }
                .toSortedMap(compareBy { groupOrder.indexOf(it).takeIf { index -> index >= 0 } ?: Int.MAX_VALUE })
                .forEach { (group, groupRows) -> add(FieldSection(FieldSectionKind.Group(group), groupRows.sortedBy { it.field.label })) }
        }
        return FieldsState.Ready(sections, absent.sortedBy { it.label }, searching = needle.isNotEmpty())
    }

    private fun FieldRow.matches(needle: String): Boolean =
        field.label.contains(needle, ignoreCase = true) ||
            field.id.contains(needle, ignoreCase = true) ||
            shown?.contains(needle, ignoreCase = true) == true

    private fun currentValue(catalog: TagCatalog, field: EditableField, report: MetadataReport): String? =
        try {
            catalog.currentValue(field, report)
        } catch (e: NotImplementedError) {
            null
        } catch (e: Exception) {
            null
        }

    private fun validate(field: EditableField, text: String): ValidationError? {
        val catalog = resolvedCatalog ?: return null
        return (safeValidate(catalog, field, text) as? Validation.Invalid)?.error
    }

    private fun safeValidate(catalog: TagCatalog, field: EditableField, text: String): Validation {
        val input = if (field.kind is FieldKind.TextList) normalizeList(text) else text
        return try {
            catalog.validate(field, input)
        } catch (e: NotImplementedError) {
            Validation.Invalid(ValidationError.InvalidChoice)
        } catch (e: Exception) {
            Validation.Invalid(ValidationError.InvalidChoice)
        }
    }

    private fun <T> resolve(provider: () -> T): T? = try {
        provider()
    } catch (e: NotImplementedError) {
        null
    } catch (e: Exception) {
        null
    }

    /** UI bits that are not derived from the report or the queue. */
    private data class LocalState(
        val page: EditorPage = EditorPage.Overview,
        val save: SaveState = SaveState.Idle,
        val fieldEditor: FieldEditor? = null,
        val addedFields: Set<String> = emptySet(),
        val saveOptionsOverride: SaveOptions? = null,
        val readingOtherPhoto: Boolean = false,
    )

    companion object {
        private const val STOP_TIMEOUT_MILLIS = 5_000L
        private const val GENERIC_MIME_TYPE = "application/octet-stream"
        private const val COPY_SUFFIX = "_edited"

        private val groupOrder = listOf(
            DirectoryGroup.Exif, DirectoryGroup.Gps, DirectoryGroup.Xmp, DirectoryGroup.Iptc,
            DirectoryGroup.MakerNote, DirectoryGroup.Icc, DirectoryGroup.Container, DirectoryGroup.File, DirectoryGroup.Other,
        )

        /** The factory for editing [ref], wired from the [io.github.fishpimp.exiflab.AppGraph]. */
        fun factory(ref: PhotoRef): ViewModelProvider.Factory = viewModelFactory {
            initializer {
                val graph = this[APPLICATION_KEY]!!.appGraph
                val photos = graph.photoRepository
                val reader = graph.metadataReader
                EditViewModel(
                    ref = ref,
                    readReport = { photo -> withContext(Dispatchers.IO) { reader.read(photos.imageSource(photo)) } },
                    planner = { Editing.planner },
                    catalog = { Editing.catalog },
                    saving = graph.editSaving,
                    notices = graph.savedEditNotices,
                    resolveCopy = { uri -> photos.resolve(uri.toUri(), PhotoOrigin.Document) },
                    readOtherPhoto = { uri ->
                        val other = photos.resolve(uri.toUri(), PhotoOrigin.Picker)
                        withContext(Dispatchers.IO) { reader.read(photos.imageSource(other)) }
                    },
                    searchPlaces = { queries -> graph.placeSearchRepository.searchAsYouType(queries) },
                )
            }
        }
    }
}

/** Keyword lists travel as one line, items separated by [LIST_SEPARATOR]. */
const val LIST_SEPARATOR = ", "

/** Splits a keyword line into items, trimming blanks. */
fun splitList(text: String): List<String> = text.split(',', ';', '\n').map { it.trim() }.filter { it.isNotEmpty() }

private fun normalizeList(text: String): String = splitList(text).joinToString(LIST_SEPARATOR)
