package io.github.fishpimp.exiflab.metadata.container

import com.drew.metadata.Metadata
import io.github.fishpimp.exiflab.metadata.io.SeekableSource
import io.github.fishpimp.exiflab.metadata.io.latin1
import io.github.fishpimp.exiflab.metadata.io.u16
import io.github.fishpimp.exiflab.metadata.io.u32

/**
 * Fujifilm RAF: a big-endian header that points at an embedded JPEG (which carries the Exif,
 * MakerNote and XMP blocks) and at a small tag directory with the raw image dimensions.
 */
internal object RafReader {
    /** Header fields of a RAF file. */
    class Header(val jpegOffset: Long, val jpegLength: Long, val directoryOffset: Long, val directoryLength: Long, val bytes: ByteArray)

    const val TAG_FORMAT_VERSION = 1
    const val TAG_CAMERA_MODEL = 2
    const val TAG_RAF_VERSION = 3
    const val TAG_JPEG_OFFSET = 4
    const val TAG_JPEG_LENGTH = 5
    const val TAG_RAW_FULL_SIZE = 0x100
    const val TAG_RAW_CROP_TOP_LEFT = 0x110
    const val TAG_RAW_CROPPED_SIZE = 0x111
    const val TAG_RAW_IMAGE_SIZE = 0x121

    private const val HEADER_SIZE = 108
    private const val MAX_DIRECTORY_SIZE = 1024 * 1024
    private val SIZE_TAGS = setOf(TAG_RAW_FULL_SIZE, TAG_RAW_CROPPED_SIZE, TAG_RAW_IMAGE_SIZE)

    private val tagNames = mapOf(
        TAG_FORMAT_VERSION to "Format Version",
        TAG_CAMERA_MODEL to "Camera Model",
        TAG_RAF_VERSION to "RAF Version",
        TAG_JPEG_OFFSET to "Preview JPEG Offset",
        TAG_JPEG_LENGTH to "Preview JPEG Length",
        TAG_RAW_FULL_SIZE to "Raw Image Full Size",
        TAG_RAW_CROP_TOP_LEFT to "Raw Image Crop Top Left",
        TAG_RAW_CROPPED_SIZE to "Raw Image Cropped Size",
        TAG_RAW_IMAGE_SIZE to "Raw Image Size",
    )

    /** Reads the fixed header, or null when the file is too short to hold one. */
    fun header(source: SeekableSource): Header? {
        val bytes = source.readUpTo(0, HEADER_SIZE)
        if (bytes.size < HEADER_SIZE) return null
        return Header(
            jpegOffset = bytes.u32(84, bigEndian = true),
            jpegLength = bytes.u32(88, bigEndian = true),
            directoryOffset = bytes.u32(92, bigEndian = true),
            directoryLength = bytes.u32(96, bigEndian = true),
            bytes = bytes,
        )
    }

    fun read(source: SeekableSource, warnings: MutableList<String>): Metadata {
        val metadata = Metadata()
        val header = header(source) ?: run {
            warnings += "RAF header is truncated"
            return metadata
        }
        metadata.addDirectory(headerDirectory(source, header, warnings))
        if (header.jpegOffset in 1 until source.length && header.jpegLength > 0) {
            if (!JpegSegments.read(source, header.jpegOffset, metadata, warnings)) {
                warnings += "RAF embedded JPEG is missing or damaged"
            }
        } else {
            warnings += "RAF header has no valid embedded JPEG"
        }
        return metadata
    }

    private fun headerDirectory(source: SeekableSource, header: Header, warnings: MutableList<String>): ContainerDirectory {
        val directory = ContainerDirectory("raf", "RAF Header", tagNames) { dir, tag ->
            dir.getIntArray(tag)?.takeIf { tag in SIZE_TAGS && it.size == 2 }?.let { "${it[1]} x ${it[0]}" }
        }
        directory.setString(TAG_FORMAT_VERSION, header.bytes.latin1(16, 4).trimEnd('\u0000'))
        directory.setString(TAG_CAMERA_MODEL, header.bytes.latin1(28, 32).substringBefore('\u0000').trim())
        directory.setString(TAG_RAF_VERSION, header.bytes.latin1(60, 4).trimEnd('\u0000'))
        directory.setLong(TAG_JPEG_OFFSET, header.jpegOffset)
        directory.setLong(TAG_JPEG_LENGTH, header.jpegLength)
        readTagDirectory(source, header, directory, warnings)
        return directory
    }

    /** The RAF tag directory: a record count, then records of tag (2), size (2) and data. */
    private fun readTagDirectory(source: SeekableSource, header: Header, directory: ContainerDirectory, warnings: MutableList<String>) {
        if (header.directoryOffset <= 0 || header.directoryLength !in 4..MAX_DIRECTORY_SIZE.toLong()) return
        val bytes = source.readUpTo(header.directoryOffset, header.directoryLength.toInt())
        if (bytes.size < 4) {
            warnings += "RAF tag directory is truncated"
            return
        }
        val count = bytes.u32(0, bigEndian = true)
        var position = 4
        var read = 0L
        while (read < count && position + 4 <= bytes.size) {
            val tag = bytes.u16(position, bigEndian = true)
            val size = bytes.u16(position + 2, bigEndian = true)
            position += 4
            if (position + size > bytes.size) break
            if (tag in tagNames && size == 4) {
                directory.setIntArray(tag, intArrayOf(bytes.u16(position, true), bytes.u16(position + 2, true)))
            }
            position += size
            read++
        }
    }
}
