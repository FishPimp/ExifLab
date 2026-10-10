package io.github.fishpimp.exiflab.metadata.write.png

import com.adobe.internal.xmp.XMPException
import com.adobe.internal.xmp.XMPMeta
import com.adobe.internal.xmp.XMPUtils
import io.github.fishpimp.exiflab.metadata.CorruptImageException
import io.github.fishpimp.exiflab.metadata.io.SeekableSource
import io.github.fishpimp.exiflab.metadata.write.MetadataBlock
import io.github.fishpimp.exiflab.metadata.write.MetadataChanges
import io.github.fishpimp.exiflab.metadata.write.UnsupportedEditException
import io.github.fishpimp.exiflab.metadata.write.bytesAt
import io.github.fishpimp.exiflab.metadata.write.copyRange
import io.github.fishpimp.exiflab.metadata.write.copyToEnd
import io.github.fishpimp.exiflab.metadata.write.latin1
import io.github.fishpimp.exiflab.metadata.write.startsWith
import io.github.fishpimp.exiflab.metadata.write.tiff.ExifEditor
import io.github.fishpimp.exiflab.metadata.write.tiff.TiffParser
import io.github.fishpimp.exiflab.metadata.write.tiff.TiffSerializer
import io.github.fishpimp.exiflab.metadata.write.xmp.XmpPackets
import java.io.ByteArrayOutputStream
import java.io.OutputStream
import java.nio.ByteBuffer
import java.util.zip.CRC32
import java.util.zip.DataFormatException
import java.util.zip.Inflater

/**
 * Rewrites the metadata chunks of a PNG: eXIf (written before the first IDAT), the XMP iTXt
 * chunk (XML:com.adobe.xmp), text chunks (tEXt, zTXt, other iTXt; removal only) and iCCP
 * (removal only). Every other chunk, image data included, is copied byte for byte with its CRC,
 * as are any bytes after IEND. PNG has no place for IPTC-IIM; such changes are reported as skipped.
 */
internal class PngWriter {
    private enum class Kind { Exif, Xmp, Text, Icc, Image, End, Other }

    private class Chunk(val type: String, val offset: Long, val length: Int, val data: ByteArray?, val kind: Kind, val keyword: String?) {
        val totalSize: Long get() = 12L + length
    }

    private sealed class Item {
        class Copy(val chunk: Chunk) : Item()
        class New(val type: String, val data: ByteArray) : Item()
    }

    fun write(source: SeekableSource, changes: MetadataChanges, out: OutputStream, skipped: MutableList<String>) {
        val (chunks, end) = scan(source)
        if (chunks.none { it.kind == Kind.Image }) throw CorruptImageException("The PNG file has no image data")
        // New chunks go before the image data, and before the first APNG frame control chunk.
        val firstImage = chunks.indexOfFirst { it.kind == Kind.Image || it.type == "fcTL" }
        val remove = changes.removeBlocks

        if (changes.iptc.isNotEmpty()) skipped += "PNG files cannot hold IPTC-IIM; ${changes.iptc.size} IPTC change(s) were not written"

        val editExif = ExifEditor.affects(changes)
        val exifChunk = chunks.firstOrNull { it.kind == Kind.Exif }
        val newExif = if (editExif) exifChunkData(exifChunk, changes, skipped) else null

        val editXmp = XmpPackets.affects(changes)
        val xmpChunk = chunks.firstOrNull { it.kind == Kind.Xmp }
        val newXmp = if (editXmp) xmpChunkData(chunks, changes) else null

        val dropKeywords = buildSet {
            if (MetadataBlock.Exif in remove) addAll(listOf("Raw profile type exif", "Raw profile type APP1"))
            if (MetadataBlock.Xmp in remove) add("Raw profile type xmp")
            if (MetadataBlock.Iptc in remove || MetadataBlock.PhotoshopResources in remove) addAll(listOf("Raw profile type iptc", "Raw profile type 8bim"))
            if (MetadataBlock.IccProfile in remove) addAll(listOf("Raw profile type icc", "Raw profile type icm"))
        }

        val items = mutableListOf<Item>()
        var exifPlaced = !editExif || newExif == null
        var xmpPlaced = !editXmp || newXmp == null
        chunks.forEachIndexed { index, chunk ->
            if (index == firstImage) {
                if (!exifPlaced) items += Item.New(EXIF, newExif!!).also { exifPlaced = true }
                if (!xmpPlaced) items += Item.New(ITXT, newXmp!!).also { xmpPlaced = true }
            }
            when {
                chunk.kind == Kind.Exif && editExif -> if (chunk === exifChunk && index < firstImage && !exifPlaced) {
                    items += Item.New(EXIF, newExif!!)
                    exifPlaced = true
                }
                chunk.kind == Kind.Xmp && editXmp -> if (chunk === xmpChunk && !xmpPlaced) {
                    items += Item.New(ITXT, newXmp!!)
                    xmpPlaced = true
                }
                chunk.kind == Kind.Icc && MetadataBlock.IccProfile in remove -> Unit
                chunk.kind == Kind.Text && (MetadataBlock.Comments in remove || chunk.keyword in dropKeywords) -> Unit
                else -> items += Item.Copy(chunk)
            }
        }

        out.write(SIGNATURE)
        val buffer = ByteArray(64 * 1024)
        for (item in items) {
            when (item) {
                is Item.Copy -> copyRange(source, item.chunk.offset, item.chunk.totalSize, out, buffer)
                is Item.New -> out.write(chunk(item.type, item.data))
            }
        }
        copyToEnd(source, end, out)
    }

    /** Returns the chunks up to and including IEND, and the offset just after IEND. */
    private fun scan(source: SeekableSource): Pair<List<Chunk>, Long> {
        if (!source.bytesAt(0, SIGNATURE.size).contentEquals(SIGNATURE)) throw CorruptImageException("Not a PNG file")
        val chunks = mutableListOf<Chunk>()
        var position = SIGNATURE.size.toLong()
        while (true) {
            val header = source.bytesAt(position, 8)
            val length = ByteBuffer.wrap(header, 0, 4).int
            if (length < 0) throw CorruptImageException("A PNG chunk at offset $position has an invalid length")
            val type = String(header, 4, 4, Charsets.ISO_8859_1)
            if (!type.all { it in 'A'..'Z' || it in 'a'..'z' }) throw CorruptImageException("The PNG chunk structure is damaged at offset $position")
            val dataOffset = position + 8
            var keyword: String? = null
            val kind = when (type) {
                "eXIf" -> Kind.Exif
                "iCCP" -> Kind.Icc
                "IDAT", "fdAT" -> Kind.Image
                "IEND" -> Kind.End
                "tEXt", "zTXt", "iTXt" -> {
                    val prefix = source.bytesAt(dataOffset, minOf(length, KEYWORD_PREFIX))
                    keyword = prefix.indexOf(0).let { if (it >= 0) String(prefix, 0, it, Charsets.ISO_8859_1) else null }
                    if (type == ITXT && keyword == XMP_KEYWORD) Kind.Xmp else Kind.Text
                }
                else -> Kind.Other
            }
            val data = if (kind == Kind.Exif || kind == Kind.Xmp) source.bytesAt(dataOffset, length) else null
            // The chunk must be complete, CRC included.
            source.bytesAt(dataOffset + length + 3, 1)
            chunks += Chunk(type, position, length, data, kind, keyword)
            position = dataOffset + length + 4
            if (kind == Kind.End) return chunks to position
        }
    }

    private fun exifChunkData(chunk: Chunk?, changes: MetadataChanges, skipped: MutableList<String>): ByteArray? {
        val original = chunk?.data?.takeIf { MetadataBlock.Exif !in changes.removeBlocks }?.let { data ->
            val tiff = if (data.startsWith(EXIF_PREFIX)) data.copyOfRange(EXIF_PREFIX.size, data.size) else data
            TiffParser.parse(tiff, skipped)
        }
        val edited = ExifEditor.apply(original, changes, jpeg = null, skipped) ?: return null
        return TiffSerializer.serialize(edited, Int.MAX_VALUE - 64, skipped)
    }

    private fun xmpChunkData(chunks: List<Chunk>, changes: MetadataChanges): ByteArray? {
        var meta: XMPMeta? = null
        if (MetadataBlock.Xmp !in changes.removeBlocks) {
            for (chunk in chunks.filter { it.kind == Kind.Xmp }) {
                val packet = XmpPackets.parse(itxtText(chunk.data!!))
                val main = meta
                if (main == null) {
                    meta = packet
                } else {
                    try {
                        XMPUtils.appendProperties(packet, main, true, false, false)
                    } catch (e: XMPException) {
                        throw UnsupportedEditException("The XMP metadata is malformed and cannot be edited safely (${e.message})")
                    }
                }
            }
        }
        val edited = XmpPackets.edit(meta, changes) ?: return null
        val header = ByteArrayOutputStream()
        header.write(XMP_KEYWORD.latin1())
        // Keyword terminator, no compression (flag and method), empty language tag and translated keyword.
        header.write(byteArrayOf(0, 0, 0, 0, 0))
        return header.toByteArray() + XmpPackets.serialize(edited)
    }

    /** The text of an iTXt chunk, inflated when compressed. */
    private fun itxtText(data: ByteArray): ByteArray {
        fun damaged(): Nothing = throw UnsupportedEditException("The PNG XMP chunk is damaged")
        val keywordEnd = data.indexOf(0)
        if (keywordEnd < 0 || keywordEnd + 3 > data.size) damaged()
        val compressed = data[keywordEnd + 1].toInt() == 1
        val languageEnd = data.indexOf(0, keywordEnd + 3).takeIf { it >= 0 } ?: damaged()
        val translatedEnd = data.indexOf(0, languageEnd + 1).takeIf { it >= 0 } ?: damaged()
        val text = data.copyOfRange(translatedEnd + 1, data.size)
        if (!compressed) return text
        val inflater = Inflater()
        try {
            inflater.setInput(text)
            val out = ByteArrayOutputStream()
            val buffer = ByteArray(16 * 1024)
            while (!inflater.finished()) {
                val n = inflater.inflate(buffer)
                if (n == 0 && (inflater.needsInput() || inflater.needsDictionary())) damaged()
                out.write(buffer, 0, n)
                if (out.size() > MAX_XMP) damaged()
            }
            return out.toByteArray()
        } catch (_: DataFormatException) {
            damaged()
        } finally {
            inflater.end()
        }
    }

    private fun ByteArray.indexOf(value: Int, from: Int = 0): Int {
        for (i in from until size) if (this[i].toInt() == value) return i
        return -1
    }

    companion object {
        private val SIGNATURE = byteArrayOf(0x89.toByte(), 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A)
        private val EXIF_PREFIX = "Exif\u0000\u0000".latin1()
        private const val EXIF = "eXIf"
        private const val ITXT = "iTXt"
        private const val XMP_KEYWORD = "XML:com.adobe.xmp"
        private const val KEYWORD_PREFIX = 80
        private const val MAX_XMP = 64 * 1024 * 1024

        /** A complete chunk: length, type, data and CRC-32 over type and data. */
        fun chunk(type: String, data: ByteArray): ByteArray {
            val typeBytes = type.latin1()
            val crc = CRC32().apply {
                update(typeBytes)
                update(data)
            }.value
            return ByteBuffer.allocate(12 + data.size).putInt(data.size).put(typeBytes).put(data).putInt(crc.toInt()).array()
        }
    }
}
