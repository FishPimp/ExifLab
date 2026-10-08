package io.github.fishpimp.exiflab.engine.catalog

import io.github.fishpimp.exiflab.model.ExifIfd
import io.github.fishpimp.exiflab.model.PrivacyCategory
import io.github.fishpimp.exiflab.model.ValueKind

/** Static knowledge about a standard EXIF/TIFF tag. */
internal data class ExifTagInfo(
    val ifd: ExifIfd,
    val tag: Int,
    /** exiftool-style name, e.g. `DateTimeOriginal`. */
    val name: String,
    /** Preferred TIFF type when writing. */
    val type: Int,
    /** Fixed value count, or null if variable. */
    val count: Int? = null,
    /** Editor; null = not user-editable (still shown). */
    val kind: ValueKind? = null,
    val privacy: PrivacyCategory? = null,
    /** Pointers, offsets, byte counts: never edited or removed individually. */
    val structural: Boolean = false,
)

/** Registry of standard EXIF/TIFF/GPS tags plus XMP/IPTC privacy and naming rules. Owned by the reader agent. */
internal object TagCatalog {
    fun exif(ifd: ExifIfd, tag: Int): ExifTagInfo? = TODO("TagCatalog.exif")

    fun exifByName(name: String): ExifTagInfo? = TODO("TagCatalog.exifByName")

    /** Privacy classification for XMP properties (`exif:GPSLatitude`, `aux:SerialNumber`, `dc:creator`, ...). */
    fun xmpPrivacy(namespace: String, path: String): PrivacyCategory? = TODO("TagCatalog.xmpPrivacy")

    /** Privacy classification for IPTC record 2 datasets. */
    fun iptcPrivacy(dataset: Int): PrivacyCategory? = TODO("TagCatalog.iptcPrivacy")

    /** Privacy classification for MakerNote / other tags by their names (serials, owner names, GPS-like). */
    fun otherPrivacy(directoryId: String, name: String): PrivacyCategory? = TODO("TagCatalog.otherPrivacy")
}
