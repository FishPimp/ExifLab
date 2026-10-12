package io.github.fishpimp.exiflab.data.shareclean

import io.github.fishpimp.exiflab.metadata.edit.StripCategory
import io.github.fishpimp.exiflab.metadata.model.DirectoryGroup
import io.github.fishpimp.exiflab.metadata.model.LocationStatus
import io.github.fishpimp.exiflab.metadata.model.MetadataDirectory
import io.github.fishpimp.exiflab.metadata.model.MetadataReport
import io.github.fishpimp.exiflab.metadata.model.MetadataTag
import io.github.fishpimp.exiflab.metadata.model.SensitivityCategory

/** The kinds of metadata Share clean talks about, in the order they are listed. */
enum class MetadataKind {
    Location,
    SerialNumbers,
    UniqueIds,
    CameraDetails,
    Names,
    People,
    Dates,
    MakerNotes,
    Thumbnail,
    EditHistory,

    /** Anything else [StripCategory.All] removes. */
    OtherMetadata,
}

/**
 * What a photo holds of each [MetadataKind] that a set of [StripCategory]s covers: the tags
 * found, by kind. Built from a read [MetadataReport] only, independently of the planner, so the
 * same check can say what a strip will remove (on the original) and prove it is gone (on the
 * cleaned copy).
 */
class MetadataInventory private constructor(
    private val tagsByKind: Map<MetadataKind, Set<String>>,
    private val locationPresent: Boolean,
) {
    /** Kinds found, in display order. */
    val kinds: List<MetadataKind> get() = MetadataKind.entries.filter { it in tagsByKind || (it == MetadataKind.Location && locationPresent) }

    val isEmpty: Boolean get() = kinds.isEmpty()

    /** Tags of [kind] that were found; at least 1 for a location that is only known from the summary. */
    fun count(kind: MetadataKind): Int =
        maxOf(tagsByKind[kind]?.size ?: 0, if (kind == MetadataKind.Location && locationPresent) 1 else 0)

    /** The kinds in this inventory that [other] does not have. */
    fun without(other: MetadataInventory): List<MetadataKind> = kinds.filter { it !in other.kinds }

    companion object {
        /** What [categories] cover in [report]. */
        fun of(report: MetadataReport, categories: Set<StripCategory>): MetadataInventory {
            val all = StripCategory.All in categories
            fun has(category: StripCategory) = all || category in categories
            val makerNotesGo = has(StripCategory.MakerNotes)
            val found = LinkedHashMap<MetadataKind, MutableSet<String>>()
            val tags = report.directories.flatMap { directory -> directory.tags.map { directory to it } }

            fun add(kind: MetadataKind, tag: MetadataTag) {
                found.getOrPut(kind) { LinkedHashSet() } += tag.key
            }

            for ((directory, tag) in tags) {
                val inMakerNote = directory.group == DirectoryGroup.MakerNote
                // MakerNote content goes with the MakerNotes category as a whole.
                val sensitivity = tag.sensitivity.takeUnless { inMakerNote }
                if (has(StripCategory.Location) && sensitivity == SensitivityCategory.Location) add(MetadataKind.Location, tag)
                if (has(StripCategory.DeviceIdentifiers)) {
                    if (sensitivity == SensitivityCategory.SerialNumber) add(MetadataKind.SerialNumbers, tag)
                    // Ids inside the XMP edit history (DerivedFrom, History, Ingredients) go with EditHistory.
                    if (sensitivity == SensitivityCategory.DeviceIdentifier && !isNestedMediaManagement(tag)) add(MetadataKind.UniqueIds, tag)
                }
                if (has(StripCategory.DeviceInfo) && isDeviceInfo(directory, tag)) add(MetadataKind.CameraDetails, tag)
                if (has(StripCategory.PersonNames) && sensitivity == SensitivityCategory.PersonName) add(MetadataKind.Names, tag)
                if (has(StripCategory.People) && sensitivity == SensitivityCategory.People) add(MetadataKind.People, tag)
                if (has(StripCategory.Dates) && isDate(directory, tag)) add(MetadataKind.Dates, tag)
                if (makerNotesGo && inMakerNote) add(MetadataKind.MakerNotes, tag)
                if (has(StripCategory.Thumbnail) && directory.id == THUMBNAIL_DIRECTORY) add(MetadataKind.Thumbnail, tag)
                if (has(StripCategory.EditHistory) && isEditHistory(directory, tag)) add(MetadataKind.EditHistory, tag)
            }
            if (all) {
                val counted = found.values.flatten().toSet()
                for ((directory, tag) in tags) {
                    if (tag.key !in counted && isRemovedByAll(directory, tag)) add(MetadataKind.OtherMetadata, tag)
                }
            }
            return MetadataInventory(
                tagsByKind = found,
                locationPresent = has(StripCategory.Location) && report.locationStatus == LocationStatus.Present,
            )
        }

        private const val THUMBNAIL_DIRECTORY = "exif-thumbnail"

        /** Orientation, resolution and YCbCrPositioning: what a full strip leaves in IFD0. */
        private val MINIMAL_IFD0 = setOf(0x0112, 0x011A, 0x011B, 0x0128, 0x0213)

        private val TOP_LEVEL_MEDIA_IDS = setOf("xmpMM:DocumentID", "xmpMM:InstanceID", "xmpMM:OriginalDocumentID")

        private val DEVICE_INFO_EXIF = setOf(
            0x010F, // Make
            0x0110, // Model
            0x0131, // Software
            0x013C, // HostComputer
            0xC614, // UniqueCameraModel
            0xA432, // LensSpecification
            0xA433, // LensMake
            0xA434, // LensModel
        )
        private val DEVICE_INFO_XMP = listOf(
            "tiff:Make", "tiff:Model", "tiff:Software", "tiff:HostComputer", "xmp:CreatorTool",
            "exifEX:LensMake", "exifEX:LensModel", "exifEX:LensSpecification",
            "aux:Lens", "aux:LensInfo", "aux:LensID", "aux:Firmware",
        )

        private val DATE_EXIF = setOf(
            0x0132, // DateTime
            0x9003, // DateTimeOriginal
            0x9004, // DateTimeDigitized
            0x9010, 0x9011, 0x9012, // OffsetTime*
            0x9290, 0x9291, 0x9292, // SubSecTime*
        )
        private val DATE_GPS = setOf(0x0007, 0x001D) // GPSTimeStamp, GPSDateStamp
        private val DATE_XMP = listOf(
            "xmp:CreateDate", "xmp:ModifyDate", "xmp:MetadataDate", "exif:DateTimeOriginal", "exif:DateTimeDigitized",
            "exif:GPSTimeStamp", "photoshop:DateCreated", "tiff:DateTime",
        )

        /** IPTC application record (2) dates and times: release, expiration, reference, created, digitized. */
        private val DATE_IPTC = listOf(30, 35, 37, 38, 47, 55, 60, 62, 63).map { 0x0200 or it }.toSet()

        private val COMMENT_DIRECTORIES = setOf("jpeg-comment", "png-text", "png-ztxt", "png-itxt")

        private fun isNestedMediaManagement(tag: MetadataTag) = tag.name.startsWith("xmpMM:") && tag.name !in TOP_LEVEL_MEDIA_IDS

        /** True when [name] is [property] itself or one of its array items or fields. */
        private fun MetadataTag.isXmp(property: String) =
            name == property || name.startsWith("$property[") || name.startsWith("$property/")

        private fun isDeviceInfo(directory: MetadataDirectory, tag: MetadataTag) = when (directory.group) {
            DirectoryGroup.Exif -> tag.id in DEVICE_INFO_EXIF
            DirectoryGroup.Xmp -> DEVICE_INFO_XMP.any { tag.isXmp(it) }
            else -> false
        }

        private fun isDate(directory: MetadataDirectory, tag: MetadataTag) = when (directory.group) {
            DirectoryGroup.Exif -> tag.id in DATE_EXIF
            DirectoryGroup.Gps -> tag.id in DATE_GPS
            DirectoryGroup.Xmp -> DATE_XMP.any { tag.isXmp(it) }
            DirectoryGroup.Iptc -> tag.id in DATE_IPTC
            else -> false
        }

        private fun isEditHistory(directory: MetadataDirectory, tag: MetadataTag) =
            directory.group == DirectoryGroup.Xmp && (tag.name.startsWith("xmpMM:") || tag.isXmp("photoshop:History"))

        /** Everything [StripCategory.All] removes: EXIF but its minimal IFD0, XMP, IPTC, Photoshop resources, comments. */
        private fun isRemovedByAll(directory: MetadataDirectory, tag: MetadataTag): Boolean = when (directory.group) {
            DirectoryGroup.Exif -> !(directory.id == "exif-ifd0" && tag.id in MINIMAL_IFD0)
            DirectoryGroup.Gps, DirectoryGroup.MakerNote, DirectoryGroup.Xmp, DirectoryGroup.Iptc -> true
            else -> directory.id == "photoshop" || directory.id.startsWith("photoshop-") || directory.id in COMMENT_DIRECTORIES
        }
    }
}
