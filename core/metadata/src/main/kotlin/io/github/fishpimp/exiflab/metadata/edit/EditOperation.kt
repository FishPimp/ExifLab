package io.github.fishpimp.exiflab.metadata.edit

import io.github.fishpimp.exiflab.metadata.model.DirectoryGroup
import io.github.fishpimp.exiflab.metadata.model.MetadataReport
import io.github.fishpimp.exiflab.metadata.write.ExifIfd
import io.github.fishpimp.exiflab.metadata.write.ExifValue
import io.github.fishpimp.exiflab.metadata.write.MetadataChanges
import java.time.Duration
import java.time.LocalDateTime
import java.time.ZoneId
import java.time.ZoneOffset

/**
 * A user-level edit, independent of how the container stores it. A [ChangePlanner] turns a list
 * of operations into low-level [MetadataChanges] that keep EXIF, XMP and IPTC consistent.
 */
sealed interface EditOperation {
    /** Sets one EXIF tag to a typed value. */
    data class SetExifTag(val ifd: ExifIfd, val tag: Int, val value: ExifValue) : EditOperation

    /** Removes one EXIF tag. */
    data class RemoveExifTag(val ifd: ExifIfd, val tag: Int) : EditOperation

    /**
     * Escape hatch for edits that are already expressed as low-level changes (XMP/IPTC fields).
     * [syncExisting] holds companion changes that only apply where the field already exists, so
     * an edit can keep an existing copy in another block in sync (dc:title and IPTC Object Name)
     * without creating that block. Removals in it always apply.
     */
    data class Raw(
        val changes: MetadataChanges,
        val description: String,
        val syncExisting: MetadataChanges = MetadataChanges(),
    ) : EditOperation

    /**
     * Sets the capture time. Updates DateTimeOriginal, DateTimeDigitized (CreateDate), SubSec*,
     * OffsetTime* when [offset] is known, and the XMP/IPTC date fields that already exist.
     * [alsoModifyDate] also sets IFD0 DateTime.
     */
    data class SetCaptureTime(
        val local: LocalDateTime,
        val offset: ZoneOffset?,
        val alsoModifyDate: Boolean = false,
    ) : EditOperation

    /**
     * Moves every capture/creation/modification date field by [by] (positive or negative), keeping
     * offsets and sub-seconds. GPS date and time stamps move too, so they stay consistent with the
     * corrected clock. Warns when the file has no date.
     */
    data class ShiftCaptureTime(val by: Duration) : EditOperation

    /**
     * Fixes a camera clock that was set to the wrong time zone: the recorded local times are read
     * as [cameraZone] wall-clock times and rewritten as [actualZone] wall-clock times for the same
     * instant (daylight saving resolved for each photo's own date). Offset tags are set to the
     * [actualZone] offset. A wall time inside a daylight saving gap of [cameraZone] is moved
     * forward by the gap; an ambiguous one takes the earlier offset. GPS time stamps (UTC) stay.
     */
    data class ChangeTimeZone(val cameraZone: ZoneId, val actualZone: ZoneId) : EditOperation

    /** Records the UTC offset without changing the local times. */
    data class SetUtcOffset(val offset: ZoneOffset) : EditOperation

    /**
     * Sets GPS position (EXIF GPS IFD and XMP exif:GPS*), optionally altitude. Without an altitude
     * an existing one is kept only while the position stays the same. Coordinates out of range
     * produce a warning and no change.
     */
    data class SetLocation(val latitude: Double, val longitude: Double, val altitudeMeters: Double? = null) : EditOperation

    /** Removes GPS data from EXIF and XMP and, when [includePlaceNames], city/country fields too. */
    data class RemoveLocation(val includePlaceNames: Boolean = true) : EditOperation

    /**
     * Sets (or clears, with null) the author and copyright in EXIF, XMP (dc:creator, dc:rights) and IPTC.
     * With [changeArtist] or [changeCopyright] false that half is left as it is, so one of the two
     * can be edited on its own.
     */
    data class SetAuthorship(
        val artist: String?,
        val copyright: String?,
        val changeArtist: Boolean = true,
        val changeCopyright: Boolean = true,
    ) : EditOperation

    /** Removes the metadata of the given categories. */
    data class Strip(val categories: Set<StripCategory>) : EditOperation
}

/** What a strip operation removes. */
enum class StripCategory {
    /** GPS position and place names. */
    Location,
    /** Camera and lens serial numbers, unique image/device ids. */
    DeviceIdentifiers,
    /** Camera make, model, lens and software. */
    DeviceInfo,
    /** Artist, owner, creator, copyright and contact details. */
    PersonNames,
    /** Face and people regions. */
    People,
    /** Capture and modification dates. */
    Dates,
    /** Vendor MakerNotes (often contain serials and internal ids). */
    MakerNotes,
    /** Embedded EXIF thumbnail (can show the image before it was cropped). */
    Thumbnail,
    /** XMP edit history and document ids. */
    EditHistory,
    /** Everything except orientation and the color profile, which affect how the image looks. */
    All,
}

/** One visible difference between the original and the planned metadata. */
data class FieldDiff(
    /** English field label, e.g. "Date Taken" or "GPS Latitude". */
    val label: String,
    val group: DirectoryGroup,
    val before: String?,
    val after: String?,
) {
    enum class Kind { Added, Changed, Removed }

    val kind: Kind
        get() = when {
            before == null -> Kind.Added
            after == null -> Kind.Removed
            else -> Kind.Changed
        }
}

/** Planned low-level changes plus a human-readable diff for the preview. */
data class EditPlan(
    val changes: MetadataChanges,
    val diff: List<FieldDiff>,
    /** Problems the user should know about, e.g. no capture date to shift. The app words them. */
    val warnings: List<PlanWarning> = emptyList(),
)

/**
 * Something about a plan the user should know. Each case carries what the app needs to word it
 * in the user's language; the planner never produces display text.
 */
sealed interface PlanWarning {
    /** The file has no capture date for [action] to work on, so nothing was changed. */
    data class NoCaptureDate(val action: DateAction) : PlanWarning

    /** The latitude [value] is outside -90 to 90 degrees; the location was not changed. */
    data class LatitudeOutOfRange(val value: Double) : PlanWarning

    /** The longitude [value] is outside -180 to 180 degrees; the location was not changed. */
    data class LongitudeOutOfRange(val value: Double) : PlanWarning

    /** The altitude [value] (meters) is not plausible; the location was not changed. */
    data class AltitudeImplausible(val value: Double) : PlanWarning

    /** [field] has characters EXIF cannot store, so it is saved in XMP and, when [alsoIptc], IPTC only. */
    data class NonAsciiField(val field: AuthorField, val alsoIptc: Boolean) : PlanWarning

    /** [field] is longer than IPTC allows ([maxBytes] bytes), so it is not saved in IPTC. */
    data class IptcTooLong(val field: AuthorField, val maxBytes: Int) : PlanWarning
}

/** The date operation a [PlanWarning.NoCaptureDate] concerns. */
enum class DateAction {
    /** [EditOperation.ShiftCaptureTime]. */
    Shift,

    /** [EditOperation.ChangeTimeZone]. */
    Convert,

    /** [EditOperation.SetUtcOffset]. */
    SetZone,
}

/** The authorship fields of [EditOperation.SetAuthorship]. */
enum class AuthorField { Artist, Copyright }

/** Plans [EditOperation]s against a file's current metadata. Pure and deterministic. */
interface ChangePlanner {
    fun plan(report: MetadataReport, operations: List<EditOperation>): EditPlan
}
