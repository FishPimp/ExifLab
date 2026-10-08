package io.github.fishpimp.exiflab.model

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * Everything ExifLab knows about one image's metadata. Produced by the engine's reader; immutable.
 *
 * Tag names and raw/display values are the technical, language-neutral strings (English, as in exiftool and
 * metadata-extractor). The app localises headings and the summary, not individual MakerNote descriptions.
 */
@Serializable
public data class MetadataDocument(
    val format: ImageFormat,
    val fileSize: Long,
    val summary: PhotoSummary,
    val directories: List<MetadataDirectory>,
    /** Problems found while reading (corrupt segments, unsupported structures). Reading never throws for these. */
    val warnings: List<String> = emptyList(),
    /** True if an XMP sidecar was supplied and merged into [directories] (category [DirectoryCategory.XMP_SIDECAR]). */
    val hasSidecar: Boolean = false,
) {
    public val tagCount: Int get() = directories.sumOf { it.tags.size }

    public fun find(id: TagId): MetadataTag? = directories.firstNotNullOfOrNull { d -> d.tags.firstOrNull { it.id == id } }

    public fun directory(id: String): MetadataDirectory? = directories.firstOrNull { it.id == id }
}

/** Coarse grouping used for ordering, colour and icons in the viewer. */
@Serializable
public enum class DirectoryCategory {
    FILE, EXIF, GPS, MAKER_NOTES, XMP, XMP_SIDECAR, IPTC, ICC, CONTAINER, OTHER
}

@Serializable
public data class MetadataDirectory(
    /** Stable id, e.g. `exif.ifd0`, `exif.exif`, `exif.gps`, `makernote.canon`, `xmp.dc`, `iptc`, `icc`, `file`. */
    val id: String,
    /** Display name, e.g. "IFD0", "Exif", "GPS", "Canon MakerNote", "XMP Dublin Core". */
    val name: String,
    val category: DirectoryCategory,
    val tags: List<MetadataTag>,
)

@Serializable
public data class MetadataTag(
    val id: TagId,
    /** Technical name, e.g. `ExposureTime`. */
    val name: String,
    /** Value as stored, e.g. `1/250`, `0x0001`, `5 bytes: 30 32 32 30 00`. */
    val rawValue: String,
    /** Human-readable value, e.g. `1/250 s`, `f/1.8`, `Top, left side (Horizontal / normal)`. */
    val displayValue: String,
    /** Set when the tag can reveal something about a person, place or device. */
    val privacy: PrivacyCategory? = null,
    /** How the tag can be edited; null means read-only. */
    val editKind: ValueKind? = null,
    /** True if the tag may be removed on its own. */
    val deletable: Boolean = false,
    /** Hex tag number for EXIF-like tags (`0x829A`), used in raw mode. */
    val tagHex: String? = null,
)

/** Identity of a tag for editing and diffing. */
@Serializable
public sealed interface TagId {
    /** A TIFF/EXIF tag in one of the standard IFDs. [tag] is the 16-bit tag number. */
    @Serializable
    @SerialName("exif")
    public data class Exif(val ifd: ExifIfd, val tag: Int) : TagId

    /** An XMP property. [path] is an XMP Core path such as `dc:creator` or `exif:GPSLatitude`. */
    @Serializable
    @SerialName("xmp")
    public data class Xmp(val namespace: String, val path: String) : TagId

    /** An IPTC-IIM dataset, e.g. record 2 dataset 80 = By-line. */
    @Serializable
    @SerialName("iptc")
    public data class Iptc(val record: Int, val dataset: Int) : TagId

    /** Anything else (MakerNotes, ICC, container info). Read-only. */
    @Serializable
    @SerialName("other")
    public data class Other(val directoryId: String, val tag: Int) : TagId
}

@Serializable
public enum class ExifIfd { IFD0, EXIF, GPS, INTEROP, IFD1 }

/** Why a tag is flagged as privacy-sensitive. */
@Serializable
public enum class PrivacyCategory {
    /** GPS coordinates, place names. */
    LOCATION,

    /** Camera body/lens serial numbers and other device-unique numbers. */
    SERIAL_NUMBER,

    /** Names of people: artist, owner, creator, copyright holder. */
    PERSON,

    /** Camera/phone make, model and lens. */
    DEVICE,

    /** Software and firmware that processed the image. */
    SOFTWARE,

    /** Unique image/document identifiers that can link copies of a file. */
    UNIQUE_ID,
}

/** Describes the input an editor needs for a tag. */
@Serializable
public sealed interface ValueKind {
    @Serializable
    @SerialName("text")
    public data class Text(val asciiOnly: Boolean, val maxLength: Int? = null, val multiline: Boolean = false) : ValueKind

    @Serializable
    @SerialName("integer")
    public data class Integer(val min: Long, val max: Long, val unit: String? = null) : ValueKind

    /** A rational number. Input accepts `1/250`, `0.004`, `1.8`. */
    @Serializable
    @SerialName("rational")
    public data class Decimal(val signed: Boolean, val unit: String? = null) : ValueKind

    @Serializable
    @SerialName("choice")
    public data class Choice(val options: List<ChoiceOption>) : ValueKind

    /** EXIF date/time `YYYY:MM:DD HH:MM:SS`. */
    @Serializable
    @SerialName("datetime")
    public data object DateTime : ValueKind

    /** EXIF SubSec* digits. */
    @Serializable
    @SerialName("subsec")
    public data object SubSeconds : ValueKind

    /** EXIF OffsetTime* `+HH:MM`. */
    @Serializable
    @SerialName("offset")
    public data object TimeOffset : ValueKind

    /** List of strings (keywords, XMP bags). */
    @Serializable
    @SerialName("textlist")
    public data object TextList : ValueKind
}

@Serializable
public data class ChoiceOption(val value: Long, val label: String)

@Serializable
public data class GeoPoint(
    val latitude: Double,
    val longitude: Double,
    /** Metres above sea level; negative below. */
    val altitude: Double? = null,
) {
    init {
        require(latitude in -90.0..90.0) { "latitude out of range: $latitude" }
        require(longitude in -180.0..180.0) { "longitude out of range: $longitude" }
    }
}

/** The capture moment as recorded, without guessing a timezone. */
@Serializable
public data class CapturedTime(
    /** `YYYY:MM:DD HH:MM:SS` exactly as stored. */
    val dateTime: String,
    /** `+HH:MM` from OffsetTimeOriginal (or XMP), if present. */
    val offset: String? = null,
    /** SubSecTimeOriginal digits, if present. */
    val subSeconds: String? = null,
    /** Technical name of the tag the value came from. */
    val source: String,
)

/** Rational value; denominators are never zero in values ExifLab writes. */
@Serializable
public data class Rational(val numerator: Long, val denominator: Long) {
    public fun toDouble(): Double = if (denominator == 0L) Double.NaN else numerator.toDouble() / denominator.toDouble()
    override fun toString(): String = "$numerator/$denominator"
}

/** The facts shown in the viewer header. All fields are optional. */
@Serializable
public data class PhotoSummary(
    val make: String? = null,
    val model: String? = null,
    val lens: String? = null,
    val exposureTime: Rational? = null,
    val fNumber: Double? = null,
    val iso: Int? = null,
    val focalLength: Double? = null,
    val focalLength35mm: Int? = null,
    val exposureBias: Double? = null,
    val flashFired: Boolean? = null,
    val width: Int? = null,
    val height: Int? = null,
    /** EXIF orientation 1..8. */
    val orientation: Int? = null,
    val captured: CapturedTime? = null,
    val software: String? = null,
    val location: GeoPoint? = null,
    val colorProfile: String? = null,
    val hasExif: Boolean = false,
    val hasXmp: Boolean = false,
    val hasIptc: Boolean = false,
    val hasMakerNotes: Boolean = false,
    val hasIcc: Boolean = false,
    /** Number of tags with a [PrivacyCategory]. */
    val privacyTagCount: Int = 0,
    /** Embedded extras detected in the container (e.g. "Ultra HDR gain map", "Motion Photo video"). */
    val extras: List<String> = emptyList(),
)
