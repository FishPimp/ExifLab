package io.github.fishpimp.exiflab.data.batch

import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey
import io.github.fishpimp.exiflab.data.history.EditMode
import io.github.fishpimp.exiflab.data.history.SaveError
import io.github.fishpimp.exiflab.data.photos.PhotoOrigin
import io.github.fishpimp.exiflab.data.photos.PhotoRef

/** Life cycle of a [BatchJob]. */
enum class BatchJobState {
    /** Photos are chosen and the operation is being set up; nothing runs yet. */
    Draft,

    /** Started; waiting for the background worker. */
    Queued,

    /** The worker is processing items. Also what a job interrupted by the system looks like until it resumes. */
    Running,

    /** Every item has a final status. */
    Finished,

    /** Stopped on request after the files in progress; the rest are [BatchItemStatus.Skipped]. */
    Cancelled,
    ;

    val isActive: Boolean get() = this == Queued || this == Running
    val isDone: Boolean get() = this == Finished || this == Cancelled
}

/**
 * One batch edit: the photos (as [BatchJobItem]s), the operation and its progress. Created as a
 * [BatchJobState.Draft] when photos are selected, so navigation only carries [id], and kept
 * after it finishes so the summary can be reopened.
 *
 * @property operationJson the [BatchOperation], see [BatchOperation.encode]; null while a draft has none.
 * @property title shown in History and the notification, in the language used when it started.
 * @property editBatchId the [io.github.fishpimp.exiflab.data.history.EditBatch] the saves join;
 *   created when the worker first runs the job.
 * @property copyFolderUri the folder (a tree document URI) that receives copies of read-only
 *   photos; null skips those photos.
 * @property cancelRequested set by Cancel; the worker stops after the files in progress.
 */
@Entity(tableName = "batch_jobs", indices = [Index("state")])
data class BatchJob(
    @PrimaryKey val id: String,
    val state: BatchJobState,
    val operationJson: String?,
    val title: String,
    val total: Int,
    val createdAt: Long,
    val startedAt: Long? = null,
    val finishedAt: Long? = null,
    val editBatchId: String? = null,
    val copyFolderUri: String? = null,
    val cancelRequested: Boolean = false,
) {
    val operation: BatchOperation? get() = operationJson?.let(BatchOperation::decodeOrNull)
}

/** Where one photo of a batch stands. */
enum class BatchItemStatus {
    Pending,
    Running,

    /** Written in place. */
    Saved,

    /** Written to an XMP sidecar. */
    Sidecar,

    /** Written as a new copy. */
    Copy,
    Skipped,
    Failed,
    ;

    val isFinal: Boolean get() = this != Pending && this != Running

    companion object {
        /** The final status of a save written in [mode]. */
        fun of(mode: EditMode): BatchItemStatus = when (mode) {
            EditMode.InPlace -> Saved
            EditMode.Copy -> Copy
            EditMode.Sidecar -> Sidecar
        }
    }
}

/** Why a photo was skipped or failed, besides a [SaveError] from the save pipeline. */
enum class BatchItemReason {
    /** The operation changes nothing in this photo (e.g. no capture date to shift). */
    NothingToChange,

    /** A read-only photo and no folder was chosen for copies. */
    ReadOnly,

    /** RAW and formats ExifLab cannot rewrite only get sidecars, which cannot remove metadata. */
    CannotStrip,

    /** The writer cannot modify this format. */
    UnsupportedFormat,

    /** A sidecar for a photo outside a granted folder, and no folder was chosen for it. */
    NoSidecarPlace,

    /** Cancel was pressed before this photo's turn. */
    Cancelled,

    /** The photo's metadata could not be read. */
    Unreadable,

    /** The change could not be planned for this photo. */
    PlanFailed,

    /** The copy (or sidecar) could not be created in the chosen folder. */
    CopyFolderUnavailable,

    /** The save pipeline failed; see [BatchJobItem.saveError]. */
    SaveFailed,

    /** Fix time zone: the photo records a UTC offset other than the camera zone's, so its time is already right. */
    AlreadyHasOtherZone,

    /** A relative date change for a photo whose sidecar records its own capture date, see [io.github.fishpimp.exiflab.data.save.SaveRejection.SidecarHasDates]. */
    SidecarHasDates,
}

/**
 * One photo of a [BatchJob], with the [PhotoRef] it was selected as and its outcome.
 *
 * @property position order in the selection; together with [jobId] the key.
 * @property recordId the History record of the save, once saved.
 * @property targetUri the copy or sidecar that was (or is being) written; for a copy it is set as
 *   soon as the empty document is created, so an interrupted run can clean it up.
 * @property notes for a written photo, the changes its file type could not hold, one per line
 *   (in English, from the writer); null when everything was stored.
 */
@Entity(
    tableName = "batch_job_items",
    primaryKeys = ["jobId", "position"],
    foreignKeys = [
        ForeignKey(entity = BatchJob::class, parentColumns = ["id"], childColumns = ["jobId"], onDelete = ForeignKey.CASCADE),
    ],
    indices = [Index("jobId", "status")],
)
data class BatchJobItem(
    val jobId: String,
    val position: Int,
    val photoUri: String,
    val displayName: String?,
    val mimeType: String?,
    val size: Long?,
    val lastModified: Long?,
    val origin: PhotoOrigin,
    val writable: Boolean,
    val parentDocumentUri: String?,
    val status: BatchItemStatus = BatchItemStatus.Pending,
    val reason: BatchItemReason? = null,
    val saveError: SaveError? = null,
    val recordId: String? = null,
    val targetUri: String? = null,
    val finishedAt: Long? = null,
    val notes: String? = null,
) {
    /** The lines of [notes]. */
    val noteLines: List<String> get() = notes?.lines()?.filter { it.isNotBlank() }.orEmpty()

    fun photoRef(): PhotoRef = PhotoRef(
        uri = photoUri,
        displayName = displayName,
        mimeType = mimeType,
        size = size,
        lastModified = lastModified,
        origin = origin,
        writable = writable,
        parentDocumentUri = parentDocumentUri,
    )

    companion object {
        fun of(jobId: String, position: Int, ref: PhotoRef) = BatchJobItem(
            jobId = jobId,
            position = position,
            photoUri = ref.uri,
            displayName = ref.displayName,
            mimeType = ref.mimeType,
            size = ref.size,
            lastModified = ref.lastModified,
            origin = ref.origin,
            writable = ref.writable,
            parentDocumentUri = ref.parentDocumentUri,
        )
    }
}

/** How many items of a job have one status; see [BatchJobDao.observeCounts]. */
data class BatchStatusCount(val status: BatchItemStatus, val count: Int)

/** A queued or running job with its progress; see [BatchJobDao.observeActiveProgress]. */
data class ActiveBatch(val id: String, val title: String, val total: Int, val done: Int)
