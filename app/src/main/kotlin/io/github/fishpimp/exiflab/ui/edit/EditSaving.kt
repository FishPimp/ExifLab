package io.github.fishpimp.exiflab.ui.edit

import androidx.compose.runtime.Immutable
import io.github.fishpimp.exiflab.data.photos.PhotoOrigin
import io.github.fishpimp.exiflab.data.photos.PhotoRef
import io.github.fishpimp.exiflab.data.photos.format
import io.github.fishpimp.exiflab.data.save.PhotoSaver
import io.github.fishpimp.exiflab.data.save.SaveOutcome
import io.github.fishpimp.exiflab.data.save.SaveTarget
import io.github.fishpimp.exiflab.data.save.WriteEngine
import io.github.fishpimp.exiflab.metadata.ImageFormat
import io.github.fishpimp.exiflab.metadata.edit.EditPlan
import io.github.fishpimp.exiflab.metadata.model.LocationStatus
import io.github.fishpimp.exiflab.metadata.model.MetadataReport

/** What the editor needs from the save pipeline; [PhotoSaver] in the app, a fake in tests. */
interface EditSaving {
    /** True when files of [format] can be rewritten (in place or as a copy). */
    fun canWrite(format: ImageFormat): Boolean

    suspend fun save(ref: PhotoRef, plan: EditPlan, target: SaveTarget): SaveOutcome

    /** Puts back what record [recordId] replaced (Undo right after a save). */
    suspend fun undo(recordId: String): SaveOutcome
}

/** [EditSaving] backed by the app's journaled [PhotoSaver]. */
class PhotoSaverEditSaving(private val saver: () -> PhotoSaver, private val engine: () -> WriteEngine) : EditSaving {
    override fun canWrite(format: ImageFormat): Boolean = engine().canWrite(format)

    override suspend fun save(ref: PhotoRef, plan: EditPlan, target: SaveTarget): SaveOutcome =
        saver().save(ref = ref, changes = plan.changes, diff = plan.diff, target = target)

    override suspend fun undo(recordId: String): SaveOutcome = saver().restoreOriginal(recordId)
}

/** Where an edit can go. */
enum class SaveMode { InPlace, Copy, Sidecar }

/** Why the editor offers the [SaveOptions] it does; each has an explanation in the review. */
enum class SaveReason {
    /** A writable photo in a format ExifLab writes: save in place, or as a copy. */
    Writable,

    /** Picker, shared or read-only photos: only a copy. */
    ReadOnly,

    /** RAW (DNG included) is never modified: XMP sidecar. */
    Raw,

    /** A format the writers do not handle yet (HEIF, AVIF, TIFF): XMP sidecar. */
    UnsupportedFormat,
}

/**
 * The save buttons for one file, primary first.
 *
 * @property suggestOpenOriginal a picker or shared photo whose location was removed on the way:
 *   the original file may still have it, so hint at "Open original file".
 */
@Immutable
data class SaveOptions(val modes: List<SaveMode>, val reason: SaveReason, val suggestOpenOriginal: Boolean = false) {
    val primary: SaveMode get() = modes.first()

    companion object {
        /** Decided by the file: its format, whether it can be written, and where it came from. */
        fun of(ref: PhotoRef, report: MetadataReport, canWrite: (ImageFormat) -> Boolean): SaveOptions {
            val format = ref.format.takeIf { it != ImageFormat.Unknown } ?: report.format
            return when {
                format.isRaw || report.format.isRaw -> SaveOptions(listOf(SaveMode.Sidecar), SaveReason.Raw)
                !canWrite(format) -> SaveOptions(listOf(SaveMode.Sidecar), SaveReason.UnsupportedFormat)
                ref.writable -> SaveOptions(listOf(SaveMode.InPlace, SaveMode.Copy), SaveReason.Writable)
                else -> SaveOptions(
                    modes = listOf(SaveMode.Copy),
                    reason = SaveReason.ReadOnly,
                    suggestOpenOriginal = (ref.origin == PhotoOrigin.Picker || ref.origin == PhotoOrigin.Share) &&
                        report.locationStatus != LocationStatus.Present,
                )
            }
        }
    }
}
