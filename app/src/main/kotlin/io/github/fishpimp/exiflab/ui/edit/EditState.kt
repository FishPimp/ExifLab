package io.github.fishpimp.exiflab.ui.edit

import androidx.compose.runtime.Immutable
import io.github.fishpimp.exiflab.data.history.EditMode
import io.github.fishpimp.exiflab.data.history.SaveError
import io.github.fishpimp.exiflab.data.places.PlaceSearchState
import io.github.fishpimp.exiflab.data.photos.PhotoRef
import io.github.fishpimp.exiflab.data.save.SaveRejection
import io.github.fishpimp.exiflab.metadata.edit.EditPlan
import io.github.fishpimp.exiflab.metadata.edit.EditableField
import io.github.fishpimp.exiflab.metadata.edit.ValidationError
import io.github.fishpimp.exiflab.metadata.model.DirectoryGroup
import io.github.fishpimp.exiflab.metadata.model.MetadataReport
import io.github.fishpimp.exiflab.metadata.write.WriteSkip
import io.github.fishpimp.exiflab.ui.photo.PhotoLoadError

/** Reading the photo to edit. */
@Immutable
sealed interface EditLoad {
    data object Loading : EditLoad

    data class Failed(val error: PhotoLoadError) : EditLoad

    data class Loaded(val report: MetadataReport) : EditLoad
}

/** The plan for the queued edits, recomputed off the main thread whenever they change. */
@Immutable
sealed interface PlanState {
    /** Nothing queued. */
    data object Empty : PlanState

    data object Planning : PlanState

    data class Ready(val plan: EditPlan) : PlanState

    /** The planner is not part of this build (or failed to start). */
    data object Unavailable : PlanState

    /** The planner rejected the edits; [message] is its English explanation, for debugging only and never shown. */
    data class Failed(val message: String?) : PlanState
}

/** The pages of the editor. Sub-pages return to [Overview] on back. */
enum class EditorPage { Overview, Review, Location, TimeZone }

/** The "All fields" list. */
@Immutable
sealed interface FieldsState {
    data object Loading : FieldsState

    /** The tag catalog is not part of this build. */
    data object Unavailable : FieldsState

    /**
     * @property sections shown fields, common ones first, then by group; filtered by the search.
     * @property absent catalog fields the file does not have, for "Add field".
     */
    data class Ready(val sections: List<FieldSection>, val absent: List<EditableField>, val searching: Boolean) : FieldsState
}

@Immutable
data class FieldSection(val kind: FieldSectionKind, val rows: List<FieldRow>)

@Immutable
sealed interface FieldSectionKind {
    data object Common : FieldSectionKind

    data class Group(val group: DirectoryGroup) : FieldSectionKind
}

/**
 * One field in the list.
 *
 * @property current the file's value as editable text, null when absent.
 * @property pending the queued change, if any.
 */
@Immutable
data class FieldRow(val field: EditableField, val current: String?, val pending: PendingFieldValue?) {
    /** What the field will hold after saving. */
    val shown: String? get() = if (pending != null) pending.value else current
}

/** A queued field change; [value] null when the field will be removed. */
@Immutable
data class PendingFieldValue(val value: String?)

/**
 * The field being edited inline and the validation result to show. Its text lives in
 * [EditViewModel.fieldDraft], so the text field updates synchronously.
 */
@Immutable
data class FieldEditor(val fieldId: String, val error: ValidationError? = null)

/** Saving, and what happened. */
@Immutable
sealed interface SaveState {
    data object Idle : SaveState

    data class Saving(val mode: SaveMode) : SaveState

    /** Waiting for the user to choose where a copy or sidecar goes. */
    data class ChoosingDestination(val mode: SaveMode) : SaveState

    data class Failed(val failure: SaveFailure) : SaveState

    /**
     * Saved, but the file format could not hold everything; the review lists [skipped] before
     * returning to the viewer.
     */
    data class SavedWithSkipped(val mode: EditMode, val skipped: List<WriteSkip>, val openCopy: PhotoRef?) : SaveState
}

@Immutable
sealed interface SaveFailure {
    /** Writing failed; [rolledBack] when the file was put back exactly as it was. */
    data class Error(val error: SaveError, val rolledBack: Boolean) : SaveFailure

    data class Rejected(val reason: SaveRejection) : SaveFailure

    /** No sidecar location was chosen. */
    data object NoDestination : SaveFailure

    /** No plan to save (the planner is unavailable or failed). */
    data object NoPlan : SaveFailure
}

/** Everything the editor shows. */
@Immutable
data class EditUiState(
    val load: EditLoad = EditLoad.Loading,
    val edits: List<PendingEdit> = emptyList(),
    val plan: PlanState = PlanState.Empty,
    val fields: FieldsState = FieldsState.Loading,
    val fieldEditor: FieldEditor? = null,
    val saveOptions: SaveOptions? = null,
    val save: SaveState = SaveState.Idle,
    val page: EditorPage = EditorPage.Overview,
    val placeSearch: PlaceSearchState = PlaceSearchState.Idle,
    val readingOtherPhoto: Boolean = false,
) {
    val report: MetadataReport? get() = (load as? EditLoad.Loaded)?.report
    val hasEdits: Boolean get() = edits.isNotEmpty()
    val isSaving: Boolean get() = save is SaveState.Saving || save is SaveState.ChoosingDestination
    val canSave: Boolean get() = plan is PlanState.Ready && !plan.plan.changes.isEmpty && !isSaving

    /**
     * The file is never changed (RAW and formats ExifLab can't write): edits only go to an XMP
     * sidecar, which can add and change values but can't take anything out of the file itself.
     */
    val sidecarOnly: Boolean get() = saveOptions?.modes == listOf(SaveMode.Sidecar)
}

/** One-off things the screen does for the view model. */
sealed interface EditEvent {
    /** Leave the editor; open [openCopy] when the edit was saved as a new copy. */
    data class Finished(val openCopy: PhotoRef?) : EditEvent

    /** Ask for a new document to hold the copy (or the sidecar) with `ACTION_CREATE_DOCUMENT`. */
    data class ChooseDestination(val mode: SaveMode, val suggestedName: String, val mimeType: String) : EditEvent

    data class Message(val message: EditMessage) : EditEvent
}

/** Short notices shown in a snackbar. */
enum class EditMessage {
    LocationCopied,

    /** The other photo has no location at all. */
    OtherPhotoNoLocation,

    /** The other photo's location fields are blank, typically removed by the photo picker. */
    OtherPhotoLocationRemoved,
    OtherPhotoUnreadable,
    PlannerUnavailable,
}
