package io.github.fishpimp.exiflab.metadata.container

import com.drew.imaging.heif.HeifMetadataReader
import com.drew.lang.ByteArrayReader
import com.drew.metadata.Metadata
import com.drew.metadata.exif.ExifReader
import com.drew.metadata.heif.HeifDirectory
import com.drew.metadata.xmp.XmpReader
import io.github.fishpimp.exiflab.metadata.io.SeekableSource
import io.github.fishpimp.exiflab.metadata.io.latin1
import io.github.fishpimp.exiflab.metadata.io.u32
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream

/**
 * HEIF and AVIF. metadata-extractor decodes the container properties (brands, `ispe`, `irot`,
 * colour profile) from just the `ftyp` and `meta` boxes; ExifLab then reads the Exif and XMP
 * items itself through `iloc`, so the image data is never streamed, and corrects the image size
 * to the primary item's (the library keeps the first `ispe`, often a 512 x 512 tile).
 */
internal object HeifReader {
    private const val MAX_HEAD_BOX = 16L * 1024 * 1024
    private const val XMP_CONTENT_TYPE = "application/rdf+xml"

    fun read(source: SeekableSource, warnings: MutableList<String>): Metadata {
        val topLevel = Bmff.boxes(source, 0, source.length)
        val meta = topLevel.firstOrNull { it.type == "meta" }
        val head = ByteArrayOutputStream()
        for (box in listOfNotNull(topLevel.firstOrNull { it.type == "ftyp" }, meta)) {
            val size = box.end - box.start
            if (size <= MAX_HEAD_BOX) head.write(source.readUpTo(box.start, size.toInt()))
        }
        val metadata = HeifMetadataReader.readMetadata(ByteArrayInputStream(head.toByteArray()))
        if (meta == null) {
            warnings += "HEIF file has no meta box"
            return metadata
        }

        val items = HeifItems.parse(source, meta)
        items.primarySize?.let { (width, height) ->
            val directory = metadata.getFirstDirectoryOfType(HeifDirectory::class.java)
                ?: HeifDirectory().also { metadata.addDirectory(it) }
            directory.setLong(HeifDirectory.TAG_IMAGE_WIDTH, width)
            directory.setLong(HeifDirectory.TAG_IMAGE_HEIGHT, height)
        }
        for (item in items.itemsOfType("Exif")) {
            val bytes = items.data(source, item)
            val tiffStart = bytes?.let(::tiffStart)
            if (tiffStart == null) {
                warnings += "HEIF Exif item ${item.id} could not be read"
                continue
            }
            ExifReader().extract(ByteArrayReader(bytes.copyOfRange(tiffStart, bytes.size)), metadata)
        }
        for (item in items.itemsOfType("mime")) {
            if (!item.contentType.substringBefore(';').trim().equals(XMP_CONTENT_TYPE, ignoreCase = true)) continue
            items.data(source, item)?.let { XmpReader().extract(it, metadata) }
        }
        return metadata
    }

    /**
     * An Exif item starts with a 32-bit offset to the TIFF header (ISO/IEC 23008-12 Annex A),
     * normally skipping an "Exif\0\0" marker. Some writers get the offset wrong; fall back to
     * looking for the byte-order mark directly.
     */
    private fun tiffStart(bytes: ByteArray): Int? {
        fun isTiff(at: Int) = at >= 0 && at + 8 <= bytes.size && bytes.latin1(at, 2).let { it == "II" || it == "MM" }
        if (bytes.size < 12) return null
        val declared = 4 + bytes.u32(0, bigEndian = true)
        return when {
            declared < bytes.size && isTiff(declared.toInt()) -> declared.toInt()
            isTiff(4) -> 4
            isTiff(10) -> 10
            else -> null
        }
    }
}
