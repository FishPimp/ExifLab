package io.github.fishpimp.exiflab.metadata.write

import io.github.fishpimp.exiflab.metadata.model.Rational

/** The image file directories (IFDs) an EXIF tag can live in. */
enum class ExifIfd {
    /** IFD0: camera make/model, orientation, software, artist, copyright, modify date. */
    Primary,
    /** Exif sub-IFD: exposure, dates taken, lens, serial numbers, user comment. */
    Exif,
    /** GPS sub-IFD. */
    Gps,
    /** Interoperability sub-IFD. */
    Interoperability,
    /** IFD1: the embedded thumbnail's description. */
    Thumbnail,
}

/** A typed EXIF/TIFF value. The writer stores it with the matching TIFF field type. */
sealed interface ExifValue {
    /** ASCII (type 2); the writer appends the NUL terminator. */
    data class Ascii(val text: String) : ExifValue

    /** BYTE (type 1), e.g. GPSVersionID or GPSAltitudeRef. */
    data class Bytes(val values: List<Int>) : ExifValue

    /** SHORT (type 3). */
    data class Shorts(val values: List<Int>) : ExifValue

    /** LONG (type 4). */
    data class Longs(val values: List<Long>) : ExifValue

    /** SLONG (type 9). */
    data class SignedLongs(val values: List<Int>) : ExifValue

    /** RATIONAL (type 5). */
    data class Rationals(val values: List<Rational>) : ExifValue

    /** SRATIONAL (type 10), e.g. ExposureBiasValue. */
    data class SignedRationals(val values: List<Rational>) : ExifValue

    /** UNDEFINED (type 7), e.g. UserComment with its 8-byte character code prefix. */
    class Undefined(val bytes: ByteArray) : ExifValue {
        override fun equals(other: Any?) = other is Undefined && bytes.contentEquals(other.bytes)
        override fun hashCode() = bytes.contentHashCode()
    }
}

sealed interface ExifChange {
    val ifd: ExifIfd
    val tag: Int

    /** Adds the tag or replaces its value. Creates the IFD (and its pointer) when missing. */
    data class Set(override val ifd: ExifIfd, override val tag: Int, val value: ExifValue) : ExifChange

    /** Removes the tag when present. Empty sub-IFDs are dropped together with their pointer. */
    data class Remove(override val ifd: ExifIfd, override val tag: Int) : ExifChange
}

/** XMP array forms. */
enum class XmpArrayKind { Seq, Bag, Alt }

/**
 * Changes to the XMP packet. [namespace] is the schema URI; [prefix] is the preferred prefix used
 * when the namespace is not registered yet. [name] is a top-level property name within the schema
 * (structures are replaced or removed as a whole).
 */
sealed interface XmpChange {
    val namespace: String

    data class SetProperty(override val namespace: String, val prefix: String, val name: String, val value: String) : XmpChange

    data class SetArray(
        override val namespace: String,
        val prefix: String,
        val name: String,
        val values: List<String>,
        val kind: XmpArrayKind,
    ) : XmpChange

    /** A language alternative (`rdf:Alt` with `xml:lang`), written as `x-default`. */
    data class SetLocalizedText(override val namespace: String, val prefix: String, val name: String, val value: String) : XmpChange

    data class Remove(override val namespace: String, val name: String) : XmpChange

    /** Removes every property of a schema, e.g. all of `http://ns.adobe.com/xap/1.0/mm/` (edit history). */
    data class RemoveNamespace(override val namespace: String) : XmpChange
}

/** Changes to IPTC-IIM datasets (record 2 is the application record that holds captions, names, places). */
sealed interface IptcChange {
    val record: Int
    val dataset: Int

    data class Set(override val record: Int, override val dataset: Int, val values: List<String>) : IptcChange

    data class Remove(override val record: Int, override val dataset: Int) : IptcChange
}

/** Whole metadata blocks that can be removed in one go (used by strip/clean operations). */
enum class MetadataBlock {
    /** The entire EXIF/TIFF block, MakerNotes and thumbnail included. */
    Exif,
    /** Only the MakerNote tag inside EXIF. */
    MakerNote,
    /** The EXIF thumbnail (IFD1 and its image data). */
    ExifThumbnail,
    /** The whole XMP packet, including extended XMP. */
    Xmp,
    /** IPTC-IIM (inside Photoshop image resources for JPEG). */
    Iptc,
    /** All Photoshop image resources (APP13), IPTC included. */
    PhotoshopResources,
    /** The embedded ICC color profile. Removing it can change how colors look. */
    IccProfile,
    /** JPEG COM comments and PNG text chunks (tEXt, zTXt, non-XMP iTXt). */
    Comments,
}

/**
 * A container-independent set of metadata edits. Writers apply what the container can hold and
 * report the rest in [WriteResult.skipped] instead of failing.
 */
data class MetadataChanges(
    val exif: List<ExifChange> = emptyList(),
    val xmp: List<XmpChange> = emptyList(),
    val iptc: List<IptcChange> = emptyList(),
    val removeBlocks: Set<MetadataBlock> = emptySet(),
) {
    val isEmpty: Boolean get() = exif.isEmpty() && xmp.isEmpty() && iptc.isEmpty() && removeBlocks.isEmpty()

    operator fun plus(other: MetadataChanges) = MetadataChanges(
        exif = exif + other.exif,
        xmp = xmp + other.xmp,
        iptc = iptc + other.iptc,
        removeBlocks = removeBlocks + other.removeBlocks,
    )
}
