package io.github.fishpimp.exiflab.data.save

import io.github.fishpimp.exiflab.data.history.EditMode
import io.github.fishpimp.exiflab.data.history.SaveError

/** Where [PhotoSaver.save] writes an edit. */
sealed interface SaveTarget {
    /** Rewrite the photo itself. Needs a writable photo in a format the writer supports. */
    data object InPlace : SaveTarget

    /** Write the edited photo to [destinationUri], a new document (from `ACTION_CREATE_DOCUMENT`). */
    data class Copy(val destinationUri: String) : SaveTarget

    /**
     * Write an XMP sidecar. With no [destinationUri] the sidecar next to the photo is found or
     * created (photos from a granted folder only); otherwise [SaveOutcome.NeedsSidecarDestination]
     * asks for one.
     */
    data class Sidecar(val destinationUri: String? = null) : SaveTarget
}

/** Why a save or restore was not attempted. Nothing was journaled or changed. */
enum class SaveRejection {
    /** The photo cannot be written in place (picker and shared photos, read-only folders). Offer a copy. */
    ReadOnly,

    /** The writer cannot modify this format. */
    UnsupportedFormat,

    /** RAW files are never modified; save to a sidecar instead. */
    UseSidecar,

    /** The record has nothing to restore (a copy, already restored, or its backup is gone). */
    NotRestorable,

    /** No such record. */
    NotFound,
}

/** Result of [PhotoSaver.save], [PhotoSaver.restoreOriginal] and recovery. */
sealed interface SaveOutcome {
    /**
     * Written and verified.
     *
     * @property targetUri the file that was written (the photo, the copy or the sidecar).
     * @property skipped changes the container could not hold, in English, from the writer.
     */
    data class Saved(
        val recordId: String,
        val mode: EditMode,
        val targetUri: String,
        val skipped: List<String> = emptyList(),
    ) : SaveOutcome

    /** Writing failed after the target was opened, and the target was put back exactly as it was. */
    data class RolledBack(val recordId: String, val error: SaveError) : SaveOutcome

    /**
     * The save failed. With [SaveError.RollbackFailed] the target may be damaged and its backup is
     * kept so History can restore it; otherwise nothing was changed. [recordId] is null when the
     * failure came before anything was journaled.
     */
    data class Failed(val recordId: String?, val error: SaveError, val backupKept: Boolean = false) : SaveOutcome

    /**
     * The sidecar's location is unknown (the photo is not from a granted folder, or the folder
     * is read-only). Let the user create [suggestedName] with `ACTION_CREATE_DOCUMENT` using
     * [mimeType], then save again with [SaveTarget.Sidecar] and that document.
     */
    data class NeedsSidecarDestination(val suggestedName: String, val mimeType: String = SIDECAR_MIME_TYPE) : SaveOutcome

    /** Not attempted; see [SaveRejection]. */
    data class Rejected(val reason: SaveRejection) : SaveOutcome
}

/** Outcome of [PhotoSaver.restoreBatch]. [batchId] is the new batch that holds the restores, if any were attempted. */
data class BatchRestoreResult(val batchId: String?, val restored: Int, val failed: Int)

/**
 * The MIME type sidecars are created with. Providers append an extension that matches the MIME
 * type when the name lacks one ("IMG_1234.xmp.rdf" for `application/rdf+xml`), and
 * `application/octet-stream` is the one type they leave alone.
 */
const val SIDECAR_MIME_TYPE = "application/octet-stream"
