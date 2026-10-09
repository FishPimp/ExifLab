package io.github.fishpimp.exiflab.metadata.container

import com.drew.metadata.Metadata
import com.drew.metadata.exif.ExifIFD0Directory
import com.drew.metadata.exif.ExifSubIFDDirectory
import com.drew.metadata.exif.GpsDirectory
import com.drew.metadata.exif.makernotes.CanonMakernoteDirectory
import com.drew.metadata.xmp.XmpReader
import io.github.fishpimp.exiflab.metadata.io.SeekableSource
import io.github.fishpimp.exiflab.metadata.io.latin1

/**
 * Canon CR3 (ISO-BMFF). metadata-extractor does not understand it, so this finds the Canon
 * metadata boxes `moov > uuid(85c0b687-...) > CMT1..CMT4`, each a complete TIFF structure, and
 * the XMP packet in the top-level `uuid(be7acfcb-...)` box.
 */
internal object Cr3Reader {
    const val CANON_UUID = "85c0b687-820f-11e0-8111-f4ce462b6a48"
    const val XMP_UUID = "be7acfcb-97a9-42e8-9c71-999491e3afac"
    const val PREVIEW_UUID = "eaf42b5e-1c98-4b88-b9fb-b7dc406e4d16"

    const val TAG_MAJOR_BRAND = 1
    const val TAG_COMPATIBLE_BRANDS = 2
    const val TAG_COMPRESSOR_VERSION = 3

    private const val MAX_BLOCK_SIZE = 16 * 1024 * 1024
    private val tagNames = mapOf(
        TAG_MAJOR_BRAND to "Major Brand",
        TAG_COMPATIBLE_BRANDS to "Compatible Brands",
        TAG_COMPRESSOR_VERSION to "Compressor Version",
    )

    /** The Canon metadata box inside `moov`, or null. */
    fun canonBox(source: SeekableSource, topLevel: List<BmffBox>): BmffBox? =
        topLevel.firstOrNull { it.type == "moov" }
            ?.let { moov -> Bmff.children(source, moov).firstOrNull { it.type == "uuid" && it.userType == CANON_UUID } }

    fun read(source: SeekableSource, warnings: MutableList<String>): Metadata {
        val metadata = Metadata()
        val topLevel = Bmff.boxes(source, 0, source.length)
        val container = ContainerDirectory("cr3", "Canon CR3", tagNames)
        metadata.addDirectory(container)
        topLevel.firstOrNull { it.type == "ftyp" }?.let { readFileType(source, it, container) }

        val canon = canonBox(source, topLevel)
        if (canon == null) {
            warnings += "CR3 file has no Canon metadata box"
        } else {
            val children = Bmff.children(source, canon).associateBy { it.type }
            children["CNCV"]?.let { box ->
                payload(source, box, warnings)?.let { container.setString(TAG_COMPRESSOR_VERSION, it.latin1(0, it.size).trimEnd('\u0000')) }
            }
            // IFD0 first: MakerNote decoding looks up the camera make there.
            val blocks = listOf(
                "CMT1" to { ExifIFD0Directory() },
                "CMT2" to { ExifSubIFDDirectory() },
                "CMT4" to { GpsDirectory() },
                "CMT3" to { CanonMakernoteDirectory() },
            )
            for ((type, root) in blocks) {
                val box = children[type] ?: continue
                payload(source, box, warnings)?.let { TiffBlock.parse(it, root(), metadata, warnings) }
            }
            if (blocks.none { it.first in children }) warnings += "CR3 Canon metadata box has no CMT blocks"
        }

        topLevel.firstOrNull { it.type == "uuid" && it.userType == XMP_UUID }
            ?.let { payload(source, it, warnings) }
            ?.let { XmpReader().extract(it, metadata) }
        return metadata
    }

    private fun readFileType(source: SeekableSource, box: BmffBox, container: ContainerDirectory) {
        val bytes = source.readUpTo(box.payloadStart, box.payloadSize.coerceAtMost(256).toInt())
        if (bytes.size < 8) return
        container.setString(TAG_MAJOR_BRAND, bytes.latin1(0, 4).trim())
        val brands = (8 until bytes.size - 3 step 4).map { bytes.latin1(it, 4).trim() }.filter { it.isNotEmpty() }
        if (brands.isNotEmpty()) container.setString(TAG_COMPATIBLE_BRANDS, brands.joinToString(", "))
    }

    private fun payload(source: SeekableSource, box: BmffBox, warnings: MutableList<String>): ByteArray? {
        if (box.payloadSize !in 1..MAX_BLOCK_SIZE.toLong()) return null
        val bytes = source.readUpTo(box.payloadStart, box.payloadSize.toInt())
        if (bytes.size < box.payloadSize) warnings += "CR3 box ${box.type} is truncated"
        return bytes
    }
}
