package io.github.fishpimp.exiflab.data.history

import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey
import io.github.fishpimp.exiflab.data.photos.PhotoOrigin
import io.github.fishpimp.exiflab.data.photos.PhotoRef

/** Where an edit was written. */
enum class EditMode {
    /** The photo itself was rewritten (metadata only, image data verified identical). */
    InPlace,

    /** The edited photo was written to a new document; the original is untouched. */
    Copy,

    /** The edits went to an XMP sidecar next to the photo, which is untouched. */
    Sidecar,
}

/** Life cycle of an [EditRecord]. */
enum class EditState {
    /** Journaled and in progress. Only a crash leaves a record here; recovery resolves it at the next start. */
    Pending,

    /** Written and verified. */
    Committed,

    /** Writing failed and the target was put back exactly as it was. */
    RolledBack,

    /** A later restore put the target back to how it was before this edit. */
    Restored,

    /**
     * Nothing was written, or (with [SaveError.RollbackFailed] or [SaveError.RecoveryFailed]) the
     * target could not be put back; see [EditRecord.error].
     */
    Failed,
}

/**
 * Why a save failed, stored by name in [EditRecord.error] so History can explain it in the
 * user's language.
 */
enum class SaveError {
    /** The file could not be read. */
    ReadFailed,

    /** The file changed while it was being backed up. */
    SourceChanged,

    /** The backup could not be written (for example, the device is full). */
    BackupFailed,

    /** The backup no longer matches what was recorded, so it cannot be used to restore. */
    BackupDamaged,

    /** The metadata writer refused the edit because it would have changed image data. */
    ImageDataChanged,

    /** The file's structure is too unusual to rewrite safely. */
    UnsupportedEdit,

    /** The edited file did not pass the check that re-reads it before it replaces anything. */
    CheckFailed,

    /** The target could not be opened or written. */
    WriteFailed,

    /** What was read back from the target did not match what was written. */
    VerificationFailed,

    /** Writing failed and the target could not be put back. Its backup is kept. */
    RollbackFailed,

    /** The app stopped while saving; recovery put the target back. */
    Interrupted,

    /** The app stopped while saving and recovery could not put the target back. Its backup is kept. */
    RecoveryFailed,

    /** Something unexpected went wrong. */
    Unknown,
}

/**
 * One group of edits saved together, e.g. a batch location change over 40 photos. The counts
 * are kept up to date by [io.github.fishpimp.exiflab.data.save.PhotoSaver] as each photo
 * finishes; [cancelled] is set by whoever runs the batch.
 *
 * @property title shown in History as the batch name.
 * @property restoresBatchId set when this batch restores the originals of another batch.
 */
@Entity(tableName = "edit_batches", indices = [Index("createdAt")])
data class EditBatch(
    @PrimaryKey val id: String,
    val title: String,
    val createdAt: Long,
    val total: Int,
    val succeeded: Int = 0,
    val failed: Int = 0,
    val cancelled: Int = 0,
    val restoresBatchId: String? = null,
)

/**
 * The write journal: one row per save or restore of one photo. A row is inserted as
 * [EditState.Pending] before anything is touched and moves to a final state once the target is
 * verified or put back.
 *
 * The size and hashes describe the *target*, the file that gets written: the photo for
 * [EditMode.InPlace], the new document for [EditMode.Copy] (normally empty before), the sidecar
 * for [EditMode.Sidecar] (empty when there was none). A missing target counts as empty.
 *
 * @property photoUri the photo the edit belongs to (never modified for copies and sidecars).
 * @property parentDocumentUri the folder holding the photo, when it came from a granted folder tree.
 * @property targetUri the copy or sidecar that was written; null for in-place edits.
 * @property targetName display name of the copy or sidecar.
 * @property backupPath name of the backup file in the app's backup folder, holding the target as
 *   it was before; null when the target was empty, when the backup was not needed after all, or
 *   after it was pruned or deleted.
 * @property originalSize size of the target before the edit, in bytes.
 * @property originalSha256 SHA-256 (hex) of the target before the edit.
 * @property newSha256 SHA-256 of the content being written. Set before the target is opened, so
 *   crash recovery can tell whether the write completed.
 * @property diffJson the visible changes, see [FieldDiffCodec].
 * @property summary one line describing the edit, e.g. the changed field names.
 * @property error a [SaveError] name for failed and rolled-back saves.
 * @property restoresRecordId set when this record restores another record's original.
 */
@Entity(
    tableName = "edit_records",
    foreignKeys = [
        ForeignKey(
            entity = EditBatch::class,
            parentColumns = ["id"],
            childColumns = ["batchId"],
            onDelete = ForeignKey.SET_NULL,
        ),
    ],
    indices = [Index("batchId"), Index("photoUri"), Index("createdAt"), Index("state")],
)
data class EditRecord(
    @PrimaryKey val id: String,
    val batchId: String?,
    val photoUri: String,
    val displayName: String?,
    val mimeType: String?,
    val photoOrigin: PhotoOrigin,
    val parentDocumentUri: String?,
    val mode: EditMode,
    val targetUri: String?,
    val targetName: String?,
    val state: EditState,
    val backupPath: String?,
    val originalSize: Long,
    val originalSha256: String,
    val newSha256: String?,
    val diffJson: String,
    val summary: String,
    val createdAt: Long,
    val completedAt: Long?,
    val error: String?,
    val restoresRecordId: String?,
) {
    /** The file this record writes: the photo itself, or the copy or sidecar. */
    val destinationUri: String get() = targetUri ?: photoUri

    /** The failure reason, when there is one. */
    val saveError: SaveError?
        get() = error?.let { name -> SaveError.entries.firstOrNull { it.name == name } ?: SaveError.Unknown }

    /** True when the save failed half-way and the target may be damaged; only its backup can fix it. */
    val needsRestore: Boolean
        get() = state == EditState.Failed && (saveError == SaveError.RollbackFailed || saveError == SaveError.RecoveryFailed)

    /**
     * Whether "Restore original" can put the target back: the edit is in effect (or putting it
     * back failed) and there is a backup to write back, or the target was a sidecar that did not
     * exist before (restoring removes it). Copies leave the original untouched, so there is
     * nothing to restore.
     */
    val canRestore: Boolean
        get() = when (state) {
            EditState.Committed -> mode != EditMode.Copy && (backupPath != null || (mode == EditMode.Sidecar && originalSize == 0L))
            EditState.Failed -> needsRestore && backupPath != null
            else -> false
        }

    /** A read-only [PhotoRef] for thumbnails and for re-opening the photo. */
    fun photoRef(): PhotoRef = PhotoRef(
        uri = photoUri,
        displayName = displayName,
        mimeType = mimeType,
        size = null,
        lastModified = null,
        origin = photoOrigin,
        writable = false,
        parentDocumentUri = parentDocumentUri,
    )
}
