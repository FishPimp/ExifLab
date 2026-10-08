package io.github.fishpimp.exiflab.model

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/** A typed value for [EditOperation.SetTag]. */
@Serializable
public sealed interface TagValue {
    @Serializable
    @SerialName("text")
    public data class Text(val value: String) : TagValue

    @Serializable
    @SerialName("integers")
    public data class Integers(val values: List<Long>) : TagValue

    @Serializable
    @SerialName("rationals")
    public data class Rationals(val values: List<Rational>) : TagValue

    @Serializable
    @SerialName("texts")
    public data class TextList(val values: List<String>) : TagValue
}

/** Capture-related date fields. Each maps to EXIF plus its XMP/IPTC equivalents (MWG). */
@Serializable
public enum class DateField {
    /** EXIF DateTimeOriginal / SubSecTimeOriginal / OffsetTimeOriginal; XMP exif:DateTimeOriginal, photoshop:DateCreated. */
    DATE_TIME_ORIGINAL,

    /** EXIF DateTimeDigitized ("CreateDate") / SubSecTimeDigitized / OffsetTimeDigitized; XMP xmp:CreateDate. */
    CREATE_DATE,

    /** IFD0 DateTime ("ModifyDate") / SubSecTime / OffsetTime; XMP xmp:ModifyDate. */
    MODIFY_DATE,
    ;

    public companion object {
        public val ALL: Set<DateField> = entries.toSet()
    }
}

/** Fields that exist in several metadata blocks and are kept in sync when edited. */
@Serializable
public enum class CommonField {
    /** EXIF Artist, XMP dc:creator, IPTC By-line. */
    ARTIST,

    /** EXIF Copyright, XMP dc:rights, IPTC CopyrightNotice. */
    COPYRIGHT,

    /** EXIF ImageDescription, XMP dc:description, IPTC Caption-Abstract. */
    DESCRIPTION,

    /** XMP dc:title, IPTC ObjectName. */
    TITLE,

    /** XMP dc:subject, IPTC Keywords. Value is a comma-separated list. */
    KEYWORDS,

    /** EXIF CameraOwnerName. */
    CAMERA_OWNER,

    /** XMP xmp:Rating (0-5), EXIF Rating. */
    RATING,
}

/** Categories that can be removed in one step (batch "delete categories", share-clean presets). */
@Serializable
public enum class RemovalCategory {
    /** GPS IFD, XMP exif:GPS*, place names in XMP/IPTC. */
    LOCATION,

    /** Body/lens serial numbers, image unique ids, MakerNotes (which carry serials). */
    SERIAL_NUMBERS,

    /** Artist, owner, creator, copyright, IPTC by-line. */
    PEOPLE,

    /** Make, model, lens tags in EXIF and XMP. */
    DEVICE,

    /** Software, processing software, host computer, xmp:CreatorTool. */
    SOFTWARE,

    /** Capture/modify dates and times. */
    DATES,

    /** UserComment, ImageDescription, XPComment, JPEG comments. */
    COMMENTS,

    /** The MakerNote block. */
    MAKER_NOTES,

    /** EXIF thumbnail (IFD1) and its image data. */
    THUMBNAIL,

    /** The entire XMP packet. */
    XMP,

    /** The entire IPTC block. */
    IPTC,

    /** All metadata. Only what is needed to display the image correctly (orientation, colour profile) is kept. */
    EVERYTHING,
}

/** User-level edit. Serializable so batch jobs can be persisted and resumed. */
@Serializable
public sealed interface EditOperation {
    @Serializable
    @SerialName("set_tag")
    public data class SetTag(val tag: TagId, val value: TagValue) : EditOperation

    @Serializable
    @SerialName("remove_tag")
    public data class RemoveTag(val tag: TagId) : EditOperation

    /** Adds [seconds] (may be negative) to the selected date fields, keeping sub-seconds and offsets. */
    @Serializable
    @SerialName("shift_time")
    public data class ShiftDateTime(val seconds: Long, val fields: Set<DateField> = DateField.ALL) : EditOperation

    /**
     * The camera clock was set to [cameraOffsetMinutes] but the photo was taken where the offset was
     * [actualOffsetMinutes]. Local times are shifted by the difference and OffsetTime* is set to the actual
     * offset (when [writeOffsetTags]).
     */
    @Serializable
    @SerialName("fix_timezone")
    public data class FixTimeZone(
        val cameraOffsetMinutes: Int,
        val actualOffsetMinutes: Int,
        val writeOffsetTags: Boolean = true,
        val fields: Set<DateField> = DateField.ALL,
    ) : EditOperation

    /** Sets one date field. [dateTime] is `YYYY:MM:DD HH:MM:SS`; [offset] is `+HH:MM`. Null keeps the current value. */
    @Serializable
    @SerialName("set_time")
    public data class SetDateTime(
        val field: DateField,
        val dateTime: String,
        val subSeconds: String? = null,
        val offset: String? = null,
    ) : EditOperation

    /** Sets (or removes, if null) OffsetTime* for the given fields. */
    @Serializable
    @SerialName("set_offset")
    public data class SetTimeOffset(val offset: String?, val fields: Set<DateField> = DateField.ALL) : EditOperation

    /**
     * Writes a GPS position. Latitude/longitude and their refs are always written.
     * Altitude is written when [GeoPoint.altitude] is set; otherwise existing altitude is removed.
     * GPS date/time stamps are written from [gpsTimestampUtcSeconds] if given, else derived from
     * DateTimeOriginal + its offset when [deriveGpsTimeFromCapture] and an offset is known, else removed.
     */
    @Serializable
    @SerialName("set_location")
    public data class SetLocation(
        val point: GeoPoint,
        val gpsTimestampUtcSeconds: Long? = null,
        val deriveGpsTimeFromCapture: Boolean = true,
    ) : EditOperation

    @Serializable
    @SerialName("remove_location")
    public data object RemoveLocation : EditOperation

    /** Sets or clears ([value] = null) a field in every block that carries it. */
    @Serializable
    @SerialName("set_field")
    public data class SetField(val field: CommonField, val value: String?) : EditOperation

    @Serializable
    @SerialName("remove_categories")
    public data class RemoveCategories(val categories: Set<RemovalCategory>) : EditOperation
}

/** One line of the "pending changes" diff. */
@Serializable
public data class PlannedChange(
    val tag: TagId,
    val tagName: String,
    /** Display name of the block, e.g. "IFD0", "GPS", "XMP Dublin Core", "IPTC". */
    val directoryName: String,
    /** Current display value, or null if the tag does not exist. */
    val before: String?,
    /** New display value, or null if the tag is removed. */
    val after: String?,
) {
    public val kind: ChangeKind
        get() = when {
            before == null -> ChangeKind.ADD
            after == null -> ChangeKind.REMOVE
            else -> ChangeKind.MODIFY
        }
}

@Serializable
public enum class ChangeKind { ADD, MODIFY, REMOVE }

/** Where a planned write goes. */
@Serializable
public enum class WriteDestination { IN_FILE, SIDECAR }

/** Share-clean presets (M8). */
@Serializable
public enum class StripPreset(public val categories: Set<RemovalCategory>) {
    /** GPS coordinates and place names. */
    LOCATION(setOf(RemovalCategory.LOCATION)),

    /** Location, camera/phone identity and serial numbers (incl. MakerNotes, unique ids). */
    LOCATION_DEVICE_SERIALS(
        setOf(
            RemovalCategory.LOCATION,
            RemovalCategory.DEVICE,
            RemovalCategory.SERIAL_NUMBERS,
            RemovalCategory.MAKER_NOTES,
            RemovalCategory.SOFTWARE,
        ),
    ),

    /** Everything except what is needed to show the image correctly. */
    EVERYTHING(setOf(RemovalCategory.EVERYTHING)),
}
