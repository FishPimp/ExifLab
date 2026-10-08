package io.github.fishpimp.exiflab.engine.container

import io.github.fishpimp.exiflab.engine.io.SeekableSource
import io.github.fishpimp.exiflab.engine.tiff.ByteRange
import java.io.File

/** Where the metadata blocks and the image data live inside a file. */
internal data class ContainerLayout(
    /** The TIFF/EXIF block: position of the TIFF header and its length. */
    val exif: ByteRange? = null,
    /** XMP packet location(s). For JPEG: standard packet first, then Extended XMP chunks. */
    val xmp: List<ByteRange> = emptyList(),
    /** Raw IPTC-IIM data (inside Photoshop IRB for JPEG). */
    val iptc: ByteRange? = null,
    val icc: List<ByteRange> = emptyList(),
    /** Byte ranges that make up the image data (hashed by the verifier). Must not include any metadata. */
    val imageData: List<ByteRange> = emptyList(),
    /** Human-readable extras found, e.g. "MPF", "Ultra HDR gain map", "Motion Photo video", "Trailer: 1234 bytes". */
    val extras: List<String> = emptyList(),
    val warnings: List<String> = emptyList(),
)

internal sealed interface BlockChange<out T> {
    data object Keep : BlockChange<Nothing>
    data object Remove : BlockChange<Nothing>
    data class Replace<T>(val value: T) : BlockChange<T>
}

internal data class ContainerChanges(
    /** Complete TIFF block (header included). */
    val exif: BlockChange<ByteArray> = BlockChange.Keep,
    /** Complete serialized XMP packet. The codec handles splitting (JPEG Extended XMP) and wrappers. */
    val xmp: BlockChange<String> = BlockChange.Keep,
    /** Raw IPTC-IIM bytes (the codec wraps them, e.g. in a Photoshop IRB for JPEG). */
    val iptc: BlockChange<ByteArray> = BlockChange.Keep,
    val dropComments: Boolean = false,
    /**
     * Drop every metadata block that is not needed to render the image (EXIF/XMP/IPTC handled via the fields
     * above; this covers comments, vendor APPn segments, trailers, PNG text chunks, etc.). ICC is always kept.
     */
    val stripEverything: Boolean = false,
)

/**
 * Container-level reading and writing for one format. Writers copy everything they do not change verbatim and
 * never touch image data.
 */
internal interface ContainerCodec {
    fun scan(source: SeekableSource): ContainerLayout

    /** The TIFF block bytes, or null if the file has none. */
    fun readExif(source: SeekableSource, layout: ContainerLayout): ByteArray? =
        layout.exif?.let { source.readBytes(it.position, it.length.toInt()) }

    /** The full XMP packet as a string (Extended XMP merged for JPEG), or null. */
    fun readXmp(source: SeekableSource, layout: ContainerLayout): String?

    /** Raw IPTC-IIM bytes, or null. */
    fun readIptc(source: SeekableSource, layout: ContainerLayout): ByteArray? =
        layout.iptc?.let { source.readBytes(it.position, it.length.toInt()) }

    /** Writes a new file to [output]. @throws io.github.fishpimp.exiflab.engine.EngineException on unsupported layouts. */
    fun write(source: SeekableSource, layout: ContainerLayout, changes: ContainerChanges, output: File)
}
