package io.github.fishpimp.exiflab.ui.edit

import androidx.compose.runtime.Immutable
import io.github.fishpimp.exiflab.metadata.edit.EditOperation
import io.github.fishpimp.exiflab.metadata.model.GeoLocation
import io.github.fishpimp.exiflab.metadata.model.LocationStatus
import io.github.fishpimp.exiflab.metadata.model.MetadataReport
import java.time.Duration
import java.time.LocalDateTime
import java.time.ZoneOffset

/**
 * One queued edit. [slot] decides which edits replace each other: a new location replaces the
 * previous location edit, a new value for a field replaces that field's earlier value, and so on.
 *
 * @property fieldLabel the catalog field's label, for edits made in "All fields".
 * @property fieldValue the value the field will get (null when it is removed), for the field list.
 */
@Immutable
data class PendingEdit(
    val id: Long,
    val slot: EditSlot,
    val operation: EditOperation,
    val fieldLabel: String? = null,
    val fieldValue: String? = null,
)

/** Groups of edits where only the latest one counts. */
sealed interface EditSlot {
    /** "Set date and time": an absolute time replaces every earlier time edit. */
    data object CaptureTime : EditSlot

    /** "Shift time": shifts add up into one. */
    data object TimeShift : EditSlot

    /** "Fix time zone". */
    data object TimeZone : EditSlot

    /** "Set time zone only". */
    data object UtcOffset : EditSlot

    /** Set or remove the location. */
    data object Location : EditSlot

    data object Authorship : EditSlot

    /** One catalog field, by [EditableField.id][io.github.fishpimp.exiflab.metadata.edit.EditableField.id]. */
    data class Field(val fieldId: String) : EditSlot

    companion object {
        /** The time slots an absolute "Set date and time" makes pointless. */
        val TimeSlots: Set<EditSlot> = setOf(CaptureTime, TimeShift, TimeZone, UtcOffset)
    }
}

/**
 * Adds [edit] to [edits]: earlier edits in the same slot are replaced (the new one goes last, as
 * the latest intent), shifts are summed, and an absolute capture time drops every other time edit.
 * A shift that sums to zero removes the shift.
 */
fun List<PendingEdit>.plusEdit(edit: PendingEdit): List<PendingEdit> {
    val operation = edit.operation
    if (operation is EditOperation.ShiftCaptureTime) {
        val previous = firstOrNull { it.slot == EditSlot.TimeShift }?.operation as? EditOperation.ShiftCaptureTime
        val total = (previous?.by ?: Duration.ZERO).plus(operation.by)
        val without = filterNot { it.slot == EditSlot.TimeShift }
        return if (total.isZero) without else without + edit.copy(operation = EditOperation.ShiftCaptureTime(total))
    }
    val replaced = when (edit.slot) {
        EditSlot.CaptureTime -> EditSlot.TimeSlots
        else -> setOf(edit.slot)
    }
    return filterNot { it.slot in replaced } + edit
}

/** Capture time and offset as the queued edits would leave them; what the date and time card previews. */
@Immutable
data class CaptureTimeState(val local: LocalDateTime?, val offset: ZoneOffset?)

/**
 * Applies the time edits in [edits] to the report's capture time, the way the planner does, so
 * the editor can show "before → after" without a plan. Field edits are not considered.
 */
fun MetadataReport.captureTimeAfter(edits: List<PendingEdit>): CaptureTimeState {
    var local = summary.capturedAt
    var offset = summary.utcOffset
    for (edit in edits) {
        when (val op = edit.operation) {
            is EditOperation.SetCaptureTime -> {
                local = op.local
                offset = op.offset ?: offset
            }
            is EditOperation.ShiftCaptureTime -> local = local?.plus(op.by)
            is EditOperation.ChangeTimeZone -> local?.let { recorded ->
                val corrected = recorded.atZone(op.cameraZone).withZoneSameInstant(op.actualZone)
                local = corrected.toLocalDateTime()
                offset = corrected.offset
            }
            is EditOperation.SetUtcOffset -> offset = op.offset
            else -> Unit
        }
    }
    return CaptureTimeState(local, offset)
}

/** Position after the queued location edits: [location] null with [removed] when it will be removed. */
@Immutable
data class LocationState(val location: GeoLocation?, val status: LocationStatus, val removed: Boolean, val changed: Boolean)

fun MetadataReport.locationAfter(edits: List<PendingEdit>): LocationState {
    val edit = edits.lastOrNull { it.slot == EditSlot.Location }?.operation
    return when (edit) {
        is EditOperation.SetLocation -> LocationState(
            location = GeoLocation(edit.latitude, edit.longitude, edit.altitudeMeters),
            status = LocationStatus.Present,
            removed = false,
            changed = true,
        )
        is EditOperation.RemoveLocation -> LocationState(null, LocationStatus.Absent, removed = true, changed = true)
        else -> LocationState(location, locationStatus, removed = false, changed = false)
    }
}

/** The artist and copyright the author card starts from: queued values first, then the file's. */
@Immutable
data class Authorship(val artist: String?, val copyright: String?)

fun MetadataReport.authorship(): Authorship {
    fun tag(directoryId: String, id: Int) = directories.firstOrNull { it.id == directoryId }?.tags?.firstOrNull { it.id == id }?.rawValue
    fun xmp(name: String) = directories.firstOrNull { it.id == "xmp" }?.tags?.firstOrNull { it.name == name || it.name.startsWith("$name[") }?.displayValue
    val artist = tag("exif-ifd0", TAG_ARTIST) ?: xmp("dc:creator")
    val copyright = tag("exif-ifd0", TAG_COPYRIGHT) ?: xmp("dc:rights")
    return Authorship(artist?.trim()?.ifEmpty { null }, copyright?.trim()?.ifEmpty { null })
}

fun MetadataReport.authorshipAfter(edits: List<PendingEdit>): Authorship {
    val edit = edits.lastOrNull { it.slot == EditSlot.Authorship }?.operation as? EditOperation.SetAuthorship
    return if (edit != null) Authorship(edit.artist, edit.copyright) else authorship()
}

private const val TAG_ARTIST = 0x013B
private const val TAG_COPYRIGHT = 0x8298
