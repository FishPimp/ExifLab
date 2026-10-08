package io.github.fishpimp.exiflab.core.data.api

import io.github.fishpimp.exiflab.engine.EditPlan
import io.github.fishpimp.exiflab.model.EditOperation
import io.github.fishpimp.exiflab.model.ImageFormat
import io.github.fishpimp.exiflab.model.MetadataDocument
import io.github.fishpimp.exiflab.model.PlannedChange
import io.github.fishpimp.exiflab.model.StripPreset
import io.github.fishpimp.exiflab.model.WriteDestination
import kotlinx.serialization.Serializable

/** Where an image reference came from; decides how it can be read and written. */
@Serializable
public enum class ImageOrigin {
    /** content://media/... from ExifLab's own gallery (READ_MEDIA_IMAGES). Writable after a write request. */
    MEDIA_STORE,

    /** content://media/picker/... from the system Photo Picker. Location is usually redacted; read-only. */
    PHOTO_PICKER,

    /** A SAF document (ACTION_OPEN_DOCUMENT or inside a granted folder tree). Writable if the provider allows. */
    DOCUMENT,

    /** Received from another app (share sheet / VIEW). Read-only; access ends with the receiving activity. */
    SHARED,
}

/** A user-chosen image. [uri] is a content URI string. */
@Serializable
public data class ImageRef(
    val uri: String,
    val displayName: String,
    val mimeType: String? = null,
    val size: Long? = null,
    val origin: ImageOrigin,
    /** For [ImageOrigin.DOCUMENT] files opened inside a granted folder: the tree URI (needed for sidecars). */
    val treeUri: String? = null,
    /** MediaStore id when known (gallery items, resolved picker items). */
    val mediaId: Long? = null,
    val dateTakenMillis: Long? = null,
    /** MediaStore RELATIVE_PATH (e.g. "DCIM/Camera/") when known. */
    val relativePath: String? = null,
)

/** A gallery item from MediaStore. */
public data class MediaItem(
    val ref: ImageRef,
    val width: Int?,
    val height: Int?,
    val bucketId: String?,
    val bucketName: String?,
)

public data class MediaBucket(val id: String, val name: String, val count: Int, val coverUri: String?)

/** What the app is allowed to do with media right now. */
public data class MediaAccess(
    /** Full library access (READ_MEDIA_IMAGES, or READ_EXTERNAL_STORAGE before Android 13). */
    val canBrowseAll: Boolean,
    /** Android 14+ "selected photos only" access. */
    val partialAccess: Boolean,
    /** ACCESS_MEDIA_LOCATION granted (needed to read unredacted GPS from MediaStore). */
    val canReadLocation: Boolean,
)

/** How (and whether) edits to an image can be saved. */
public data class WriteCapabilities(
    /** Where edits go for this format (in the file, or an XMP sidecar). */
    val destination: WriteDestination?,
    /** The file itself can be modified (possibly after the user confirms a system write request). */
    val canWriteInPlace: Boolean,
    /** A system confirmation dialog is required before writing in place (MediaStore createWriteRequest). */
    val needsWriteConsent: Boolean,
    /** A copy can be saved to Pictures/ExifLab instead. */
    val canSaveCopy: Boolean,
    /** For sidecar formats: the folder is accessible, so the sidecar can be written next to the file. */
    val sidecarFolderAccessible: Boolean,
    /** Why in-place writing is impossible, for the explanation shown in the UI. */
    val readOnlyReason: ReadOnlyReason? = null,
)

public enum class ReadOnlyReason {
    /** Shared from another app; ExifLab only got a read-only copy. */
    SHARED_READ_ONLY,

    /** From the Photo Picker; the picker does not grant write access. */
    PICKER_READ_ONLY,

    /** The document provider does not support writing. */
    PROVIDER_READ_ONLY,

    /** Format cannot be written at all. */
    UNSUPPORTED_FORMAT,
}

/** Hint shown when GPS data is probably missing because it was removed before reaching ExifLab. */
public enum class LocationHint {
    /** No hint. */
    NONE,

    /** ACCESS_MEDIA_LOCATION is not granted; Android hides GPS from apps without it. */
    PERMISSION_MISSING,

    /** Photo Picker strips location unless the user allows it. */
    PICKER_REDACTED,

    /** Shared by another app, which may have removed location before sharing. */
    SENDER_MAY_HAVE_REMOVED,
}

/** An image whose metadata has been read. */
public data class LoadedImage(
    val ref: ImageRef,
    val document: MetadataDocument,
    val capabilities: WriteCapabilities,
    val locationHint: LocationHint,
    /** Existing XMP sidecar next to a RAW/DNG file, if found. */
    val sidecarName: String? = null,
)

public enum class SaveMode {
    /** Modify the original file (or its sidecar). */
    IN_PLACE,

    /** Save an edited copy to Pictures/ExifLab; the original is untouched. */
    COPY,
}

/** Preview of an edit before it is saved. */
public data class EditPreview(
    val ref: ImageRef,
    val plan: EditPlan,
    val changes: List<PlannedChange>,
    val destination: WriteDestination,
    val warnings: List<String>,
)

/** Outcome of saving one image. */
public sealed interface WriteOutcome {
    public data class Success(
        val ref: ImageRef,
        /** URI of the file that now holds the edit (the original, a copy, or the sidecar). */
        val resultUri: String,
        val historyId: Long,
        val destination: WriteDestination,
        val savedAsCopy: Boolean,
    ) : WriteOutcome

    public data class Failure(val ref: ImageRef, val error: WriteError) : WriteOutcome
}

/** Failure reasons the UI maps to localised messages. */
public enum class WriteError {
    NO_CHANGES,
    PERMISSION_DENIED,
    WRITE_CONSENT_REQUIRED,
    SOURCE_GONE,
    SOURCE_CHANGED,
    UNSUPPORTED_FORMAT,
    UNSUPPORTED_LAYOUT,
    CORRUPT_FILE,
    BLOCK_TOO_LARGE,
    INVALID_VALUE,
    VERIFICATION_FAILED,
    SIDECAR_FOLDER_ACCESS_NEEDED,
    STORAGE_FULL,
    IO_ERROR,
}

/** Row of the History screen. */
public data class HistoryEntry(
    val id: Long,
    val batchId: String?,
    val createdAtMillis: Long,
    val ref: ImageRef,
    val format: ImageFormat,
    /** Short description, e.g. "Location set, 3 dates shifted". English-neutral summary built from operations. */
    val operations: List<EditOperation>,
    val changes: List<PlannedChange>,
    val destination: WriteDestination,
    val savedAsCopy: Boolean,
    val resultUri: String?,
    val state: HistoryState,
    val backupBytes: Long,
    val error: WriteError?,
    val restoredAtMillis: Long?,
) {
    public val canRestore: Boolean get() = state == HistoryState.DONE && backupBytes >= 0 && restoredAtMillis == null && !savedAsCopy
}

public enum class HistoryState { IN_PROGRESS, DONE, FAILED, ROLLED_BACK }

public data class BackupUsage(val bytes: Long, val files: Int)

/** Batch editing. */
public data class BatchItemSpec(val ref: ImageRef, val operations: List<EditOperation>)

public data class BatchStatus(
    val id: String,
    val createdAtMillis: Long,
    val title: String,
    val state: BatchState,
    val total: Int,
    val done: Int,
    val failed: Int,
    val skipped: Int,
    val items: List<BatchItemStatus>,
)

public enum class BatchState { QUEUED, RUNNING, NEEDS_CONSENT, DONE, CANCELLED, FAILED }

public data class BatchItemStatus(
    val ref: ImageRef,
    val state: BatchItemState,
    val error: WriteError?,
    val changes: Int,
    val historyId: Long?,
)

public enum class BatchItemState { PENDING, RUNNING, DONE, FAILED, SKIPPED }

/** A file prepared for sharing without metadata. */
public data class CleanFile(
    val source: ImageRef,
    /** FileProvider URI to hand to the share sheet. */
    val shareUri: String,
    val mimeType: String,
    val removedTags: Int,
)

public data class StripResult(val files: List<CleanFile>, val failures: List<Pair<ImageRef, WriteError>>, val preset: StripPreset)

public enum class ExportFormat(public val mimeType: String, public val extension: String) {
    CSV("text/csv", "csv"),
    JSON("application/json", "json"),
    PDF("application/pdf", "pdf"),
}
