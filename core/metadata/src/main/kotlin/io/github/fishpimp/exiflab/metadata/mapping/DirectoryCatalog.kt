package io.github.fishpimp.exiflab.metadata.mapping

import com.drew.metadata.Directory
import com.drew.metadata.adobe.AdobeJpegDirectory
import com.drew.metadata.exif.ExifIFD0Directory
import com.drew.metadata.exif.ExifImageDirectory
import com.drew.metadata.exif.ExifInteropDirectory
import com.drew.metadata.exif.ExifSubIFDDirectory
import com.drew.metadata.exif.ExifThumbnailDirectory
import com.drew.metadata.exif.GpsDirectory
import com.drew.metadata.exif.PanasonicRawDistortionDirectory
import com.drew.metadata.exif.PanasonicRawIFD0Directory
import com.drew.metadata.exif.PanasonicRawWbInfo2Directory
import com.drew.metadata.exif.PanasonicRawWbInfoDirectory
import com.drew.metadata.exif.PrintIMDirectory
import com.drew.metadata.file.FileSystemDirectory
import com.drew.metadata.file.FileTypeDirectory
import com.drew.metadata.heif.HeifDirectory
import com.drew.metadata.icc.IccDirectory
import com.drew.metadata.iptc.IptcDirectory
import com.drew.metadata.jfif.JfifDirectory
import com.drew.metadata.jfxx.JfxxDirectory
import com.drew.metadata.jpeg.HuffmanTablesDirectory
import com.drew.metadata.jpeg.JpegCommentDirectory
import com.drew.metadata.jpeg.JpegDirectory
import com.drew.metadata.photoshop.DuckyDirectory
import com.drew.metadata.photoshop.PhotoshopDirectory
import com.drew.metadata.png.PngChromaticitiesDirectory
import com.drew.metadata.png.PngDirectory
import com.drew.metadata.webp.WebpDirectory
import com.drew.metadata.xmp.XmpDirectory
import io.github.fishpimp.exiflab.metadata.container.ContainerDirectory
import io.github.fishpimp.exiflab.metadata.model.DirectoryGroup

/** Where a metadata-extractor directory goes in a report. */
internal data class DirectoryPlacement(
    /** Stable id before de-duplication; repeats get "-2", "-3" suffixes. */
    val baseId: String,
    val name: String,
    val group: DirectoryGroup,
    /** Order inside the group, lower first; ties keep parse order. */
    val rank: Int = 0,
)

/** Assigns stable ids, display names, groups and ordering to metadata-extractor directories. */
internal object DirectoryCatalog {
    private const val MAKERNOTE_PACKAGE = "com.drew.metadata.exif.makernotes."

    fun place(directory: Directory): DirectoryPlacement {
        val name = directory.name
        return when (directory) {
            is ExifIFD0Directory -> DirectoryPlacement("exif-ifd0", name, DirectoryGroup.Exif, 0)
            is PanasonicRawIFD0Directory -> DirectoryPlacement("panasonic-raw-ifd0", name, DirectoryGroup.Exif, 0)
            is ExifSubIFDDirectory ->
                if (isImageSubIfd(directory)) DirectoryPlacement("image-subifd", "Image SubIFD", DirectoryGroup.Exif, 5)
                else DirectoryPlacement("exif-subifd", name, DirectoryGroup.Exif, 1)
            is ExifInteropDirectory -> DirectoryPlacement("exif-interop", name, DirectoryGroup.Exif, 2)
            is ExifThumbnailDirectory -> DirectoryPlacement("exif-thumbnail", name, DirectoryGroup.Exif, 3)
            is ExifImageDirectory -> DirectoryPlacement("exif-image", name, DirectoryGroup.Exif, 4)
            is GpsDirectory -> DirectoryPlacement("gps", name, DirectoryGroup.Gps)
            is PanasonicRawWbInfoDirectory, is PanasonicRawWbInfo2Directory, is PanasonicRawDistortionDirectory ->
                DirectoryPlacement("makernote-${slug(directory.javaClass.simpleName.removeSuffix("Directory"))}", name, DirectoryGroup.MakerNote)
            is XmpDirectory -> DirectoryPlacement("xmp", name, DirectoryGroup.Xmp)
            is IptcDirectory -> DirectoryPlacement("iptc", name, DirectoryGroup.Iptc)
            is IccDirectory -> DirectoryPlacement("icc", name, DirectoryGroup.Icc)
            is JpegDirectory -> DirectoryPlacement("jpeg", name, DirectoryGroup.Container)
            is JfifDirectory -> DirectoryPlacement("jfif", name, DirectoryGroup.Container)
            is JfxxDirectory -> DirectoryPlacement("jfxx", name, DirectoryGroup.Container)
            is AdobeJpegDirectory -> DirectoryPlacement("adobe-jpeg", name, DirectoryGroup.Container)
            is HuffmanTablesDirectory -> DirectoryPlacement("jpeg-huffman", name, DirectoryGroup.Container)
            is JpegCommentDirectory -> DirectoryPlacement("jpeg-comment", name, DirectoryGroup.Container)
            is PngDirectory -> DirectoryPlacement("png-${directory.pngChunkType.identifier.lowercase()}", name, DirectoryGroup.Container)
            is PngChromaticitiesDirectory -> DirectoryPlacement("png-chrm", name, DirectoryGroup.Container)
            is WebpDirectory -> DirectoryPlacement("webp", name, DirectoryGroup.Container)
            is HeifDirectory -> DirectoryPlacement("heif", name, DirectoryGroup.Container)
            is ContainerDirectory -> DirectoryPlacement(directory.id, name, DirectoryGroup.Container)
            is FileTypeDirectory, is FileSystemDirectory -> DirectoryPlacement("file", name, DirectoryGroup.File)
            is PhotoshopDirectory -> DirectoryPlacement("photoshop", name, DirectoryGroup.Other)
            is DuckyDirectory -> DirectoryPlacement("ducky", name, DirectoryGroup.Other)
            is PrintIMDirectory -> DirectoryPlacement("print-im", name, DirectoryGroup.Other)
            else ->
                if (directory.javaClass.name.startsWith(MAKERNOTE_PACKAGE)) {
                    DirectoryPlacement("makernote-${makernoteVendor(directory.javaClass.simpleName)}", name, DirectoryGroup.MakerNote)
                } else {
                    DirectoryPlacement(slug(name), name, DirectoryGroup.Other)
                }
        }
    }

    /**
     * metadata-extractor files every SubIFD (tag 0x014A) as "Exif SubIFD", but in RAW files most of
     * them describe image data (raw strips, previews) rather than Exif shooting data.
     */
    fun isImageSubIfd(directory: ExifSubIFDDirectory): Boolean =
        IMAGE_STRUCTURE_TAGS.any(directory::containsTag) && EXIF_MARKER_TAGS.none(directory::containsTag)

    /** "CanonMakernoteDirectory" -> "canon", "OlympusCameraSettingsMakernoteDirectory" -> "olympus-camera-settings". */
    fun makernoteVendor(simpleName: String): String =
        slug(simpleName.removeSuffix("Directory").replace("Makernote", "").replace(Regex("Type\\d+"), ""))

    /** Lowercase kebab-case of a CamelCase or free-text name. */
    fun slug(text: String): String =
        text.replace(Regex("([a-z0-9])([A-Z])"), "$1-$2")
            .lowercase()
            .replace(Regex("[^a-z0-9]+"), "-")
            .trim('-')
            .ifEmpty { "directory" }

    // NewSubfileType, StripOffsets, TileOffsets, JPEGInterchangeFormat.
    private val IMAGE_STRUCTURE_TAGS = intArrayOf(0x00FE, 0x0111, 0x0144, 0x0201)

    // ExifVersion, ExposureTime, DateTimeOriginal.
    private val EXIF_MARKER_TAGS = intArrayOf(0x9000, 0x829A, 0x9003)
}
