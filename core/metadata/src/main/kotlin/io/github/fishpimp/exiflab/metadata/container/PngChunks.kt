package io.github.fishpimp.exiflab.metadata.container

import com.drew.imaging.png.PngMetadataReader
import com.drew.metadata.Metadata
import io.github.fishpimp.exiflab.metadata.io.SeekableSource
import io.github.fishpimp.exiflab.metadata.io.SourceCursor
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.EOFException

/**
 * Collects the metadata chunks of a PNG (skipping image data without reading it) into a compact
 * PNG stream for metadata-extractor. Chunks before a truncation point are kept.
 */
internal object PngChunks {
    fun read(source: SeekableSource, warnings: MutableList<String>): Metadata {
        val compact = ByteArrayOutputStream()
        compact.write(SIGNATURE)
        val cursor = SourceCursor(source, SIGNATURE.size.toLong())
        var sawEnd = false
        try {
            while (!sawEnd) {
                val length = cursor.u32()
                val type = cursor.bytes(4)
                val name = String(type, Charsets.ISO_8859_1)
                when {
                    length > Int.MAX_VALUE -> {
                        warnings += "PNG chunk '$name' has an invalid length; stopped reading chunks"
                        break
                    }
                    name in IMAGE_DATA_CHUNKS -> cursor.skip(length + CRC_SIZE)
                    length > MAX_METADATA_CHUNK -> {
                        warnings += "Skipped PNG chunk '$name' of $length bytes"
                        cursor.skip(length + CRC_SIZE)
                    }
                    else -> {
                        val data = cursor.bytes(length.toInt())
                        cursor.skip(CRC_SIZE)
                        compact.writeChunk(type, data)
                        sawEnd = name == "IEND"
                    }
                }
            }
        } catch (_: EOFException) {
            warnings += "PNG data ends early; the file may be truncated"
        }
        if (!sawEnd) compact.writeChunk("IEND".toByteArray(Charsets.ISO_8859_1), ByteArray(0))
        return PngMetadataReader.readMetadata(ByteArrayInputStream(compact.toByteArray()))
    }

    private fun ByteArrayOutputStream.writeChunk(type: ByteArray, data: ByteArray) {
        writeInt(data.size)
        write(type)
        write(data)
        // metadata-extractor does not verify CRCs.
        writeInt(0)
    }

    private fun ByteArrayOutputStream.writeInt(value: Int) {
        write(value ushr 24)
        write(value ushr 16)
        write(value ushr 8)
        write(value)
    }

    private val SIGNATURE = byteArrayOf(0x89.toByte(), 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A)
    private val IMAGE_DATA_CHUNKS = setOf("IDAT", "fdAT")
    private const val CRC_SIZE = 4L
    private const val MAX_METADATA_CHUNK = 32L * 1024 * 1024
}
