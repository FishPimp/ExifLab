package io.github.fishpimp.exiflab.metadata.write.webp

import io.github.fishpimp.exiflab.metadata.CorruptImageException
import io.github.fishpimp.exiflab.metadata.io.SeekableSource
import io.github.fishpimp.exiflab.metadata.write.MetadataBlock
import io.github.fishpimp.exiflab.metadata.write.MetadataChanges
import io.github.fishpimp.exiflab.metadata.write.UnsupportedEditException
import io.github.fishpimp.exiflab.metadata.write.bytesAt
import io.github.fishpimp.exiflab.metadata.write.copyRange
import io.github.fishpimp.exiflab.metadata.write.copyToEnd
import io.github.fishpimp.exiflab.metadata.write.digest.WebpDigestSink
import io.github.fishpimp.exiflab.metadata.write.latin1
import io.github.fishpimp.exiflab.metadata.write.startsWith
import io.github.fishpimp.exiflab.metadata.write.tiff.ExifEditor
import io.github.fishpimp.exiflab.metadata.write.tiff.TiffParser
import io.github.fishpimp.exiflab.metadata.write.tiff.TiffSerializer
import io.github.fishpimp.exiflab.metadata.write.xmp.XmpPackets
import java.io.OutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * Rewrites the metadata chunks of a WebP: EXIF and XMP (in spec order after the image data) and
 * ICCP (removal only), keeping the VP8X feature flags in step and fixing the RIFF size. A simple
 * (VP8 or VP8L only) file becomes an extended one when metadata is added, with the canvas size
 * read from the bitstream header. Image chunks and any bytes after the RIFF data are copied
 * byte for byte. WebP has no place for IPTC-IIM; such changes are reported as skipped.
 */
internal class WebpWriter {
    /** A chunk of the source; [padded] is false when an odd-sized last chunk lacks its pad byte. */
    private class Chunk(val fourCc: String, val offset: Long, val size: Long, val data: ByteArray?, val padded: Boolean = true) {
        /** Size in the output, where odd chunks always get their pad byte. */
        val totalSize: Long get() = 8 + size + (size and 1L)
    }

    private sealed class Item(val fourCc: String) {
        abstract val totalSize: Long

        class Copy(val chunk: Chunk) : Item(chunk.fourCc) {
            override val totalSize: Long get() = chunk.totalSize
        }

        class New(fourCc: String, val data: ByteArray) : Item(fourCc) {
            override val totalSize: Long get() = 8L + data.size + (data.size and 1)
        }
    }

    fun write(source: SeekableSource, changes: MetadataChanges, out: OutputStream, skipped: MutableList<String>) {
        val (chunks, riffEnd) = scan(source)
        if (chunks.none { it.fourCc in WebpDigestSink.IMAGE_CHUNKS && it.fourCc != "ANIM" }) throw CorruptImageException("The WebP file has no image data")
        if (changes.iptc.isNotEmpty()) skipped += "WebP files cannot hold IPTC-IIM; ${changes.iptc.size} IPTC change(s) were not written"

        val editExif = ExifEditor.affects(changes)
        val editXmp = XmpPackets.affects(changes)
        val exifChunk = chunks.firstOrNull { it.fourCc == EXIF }
        val xmpChunk = chunks.firstOrNull { it.fourCc == XMP }

        val newExif = if (editExif) {
            val original = exifChunk?.data?.takeIf { MetadataBlock.Exif !in changes.removeBlocks }?.let { data ->
                TiffParser.parse(if (data.startsWith(EXIF_PREFIX)) data.copyOfRange(EXIF_PREFIX.size, data.size) else data, skipped)
            }
            ExifEditor.apply(original, changes, jpeg = null, skipped)?.let { TiffSerializer.serialize(it, MAX_CHUNK, skipped) }
        } else {
            null
        }
        val newXmp = if (editXmp) {
            val original = xmpChunk?.data?.takeIf { MetadataBlock.Xmp !in changes.removeBlocks }?.let(XmpPackets::parse)
            XmpPackets.edit(original, changes)?.let { XmpPackets.serialize(it) }
        } else {
            null
        }

        val items = mutableListOf<Item>()
        for (chunk in chunks) {
            when {
                chunk.fourCc == EXIF && editExif -> if (chunk === exifChunk && newExif != null) items += Item.New(EXIF, newExif)
                chunk.fourCc == XMP && editXmp -> if (chunk === xmpChunk && newXmp != null) items += Item.New(XMP, newXmp)
                chunk.fourCc == ICCP && MetadataBlock.IccProfile in changes.removeBlocks -> Unit
                else -> items += Item.Copy(chunk)
            }
        }
        val lastImage = items.indexOfLast { it.fourCc in IMAGE_DATA }
        if (newExif != null && items.none { it.fourCc == EXIF }) items.add(lastImage + 1, Item.New(EXIF, newExif))
        if (newXmp != null && items.none { it.fourCc == XMP }) {
            val exifIndex = items.indexOfFirst { it.fourCc == EXIF }
            items.add(if (exifIndex > lastImage) exifIndex + 1 else items.indexOfLast { it.fourCc in IMAGE_DATA } + 1, Item.New(XMP, newXmp))
        }

        val hasMetadata = items.any { it.fourCc == EXIF || it.fourCc == XMP || it.fourCc == ICCP }
        val first = items.first()
        if (first.fourCc != VP8X && hasMetadata) {
            items.add(0, Item.New(VP8X, simpleToExtendedHeader(chunks.first())))
        }
        val touched = editExif || editXmp || MetadataBlock.IccProfile in changes.removeBlocks
        if (touched && items.first().fourCc == VP8X) {
            items[0] = Item.New(VP8X, updatedFlags(items))
        }

        val riffSize = 4 + items.sumOf { it.totalSize }
        if (riffSize > 0xFFFFFFFFL - 8) throw UnsupportedEditException("The WebP file would be too large")
        out.write(RIFF)
        out.write(le32(riffSize))
        out.write(WEBP)
        for (item in items) {
            when (item) {
                is Item.Copy -> if (item.chunk.padded) {
                    copyRange(source, item.chunk.offset, item.chunk.totalSize, out)
                } else {
                    copyRange(source, item.chunk.offset, 8 + item.chunk.size, out)
                    out.write(0)
                }
                is Item.New -> {
                    out.write(item.fourCc.latin1())
                    out.write(le32(item.data.size.toLong()))
                    out.write(item.data)
                    if (item.data.size % 2 == 1) out.write(0)
                }
            }
        }
        copyToEnd(source, riffEnd, out)
    }

    private fun scan(source: SeekableSource): Pair<List<Chunk>, Long> {
        val header = source.bytesAt(0, 12)
        if (String(header, 0, 4, Charsets.ISO_8859_1) != "RIFF" || String(header, 8, 4, Charsets.ISO_8859_1) != "WEBP") {
            throw CorruptImageException("Not a WebP file")
        }
        val riffEnd = 8 + le32(header, 4)
        // The RIFF payload must be present in full.
        source.bytesAt(riffEnd - 1, 1)
        val chunks = mutableListOf<Chunk>()
        var position = 12L
        while (position + 8 <= riffEnd) {
            val chunkHeader = source.bytesAt(position, 8)
            val fourCc = String(chunkHeader, 0, 4, Charsets.ISO_8859_1)
            val size = le32(chunkHeader, 4)
            if (position + 8 + size > riffEnd) throw CorruptImageException("The WebP chunk '$fourCc' runs past the end of the file")
            val padded = position + 8 + size + (size and 1L) <= riffEnd
            val data = when (fourCc) {
                VP8X, EXIF, XMP -> loadChunk(source, position + 8, size)
                VP8, VP8L -> source.bytesAt(position + 8, minOf(size, BITSTREAM_HEADER.toLong()).toInt())
                else -> null
            }
            chunks += Chunk(fourCc, position, size, data, padded)
            position += 8 + size + (size and 1L)
        }
        if (chunks.isEmpty()) throw CorruptImageException("The WebP file has no chunks")
        return chunks to riffEnd
    }

    private fun loadChunk(source: SeekableSource, position: Long, size: Long): ByteArray {
        if (size > MAX_CHUNK) throw UnsupportedEditException("A WebP metadata chunk is too large to edit")
        return source.bytesAt(position, size.toInt())
    }

    /** Builds a VP8X header for a simple file, taking the canvas size (and alpha) from its bitstream. */
    private fun simpleToExtendedHeader(image: Chunk): ByteArray {
        val data = image.data ?: throw UnsupportedEditException("This WebP layout cannot be converted to the extended format")
        val width: Int
        val height: Int
        var alpha = false
        when (image.fourCc) {
            VP8 -> {
                if (data.size < 10 || (data[3].toInt() and 0xFF) != 0x9D || (data[4].toInt() and 0xFF) != 0x01 || (data[5].toInt() and 0xFF) != 0x2A) {
                    throw UnsupportedEditException("The WebP VP8 header is damaged")
                }
                width = le16(data, 6) and 0x3FFF
                height = le16(data, 8) and 0x3FFF
            }
            VP8L -> {
                if (data.size < 5 || (data[0].toInt() and 0xFF) != 0x2F) throw UnsupportedEditException("The WebP VP8L header is damaged")
                val bits = le32(data, 1)
                width = (bits and 0x3FFF).toInt() + 1
                height = ((bits shr 14) and 0x3FFF).toInt() + 1
                alpha = (bits shr 28) and 1L == 1L
            }
            else -> throw UnsupportedEditException("This WebP layout cannot be converted to the extended format")
        }
        if (width <= 0 || height <= 0) throw UnsupportedEditException("The WebP image size is invalid")
        val header = ByteArray(10)
        header[0] = (if (alpha) FLAG_ALPHA else 0).toByte()
        put24(header, 4, width - 1)
        put24(header, 7, height - 1)
        return header
    }

    /** Copies the VP8X header with the ICC, EXIF and XMP flags matching the chunks present. */
    private fun updatedFlags(items: List<Item>): ByteArray {
        val header = when (val vp8x = items.first()) {
            is Item.Copy -> vp8x.chunk.data!!.copyOf()
            is Item.New -> vp8x.data.copyOf()
        }
        if (header.size < 10) throw UnsupportedEditException("The WebP VP8X header is damaged")
        var flags = header[0].toInt() and 0xFF and (FLAG_ICC or FLAG_EXIF or FLAG_XMP).inv()
        if (items.any { it.fourCc == ICCP }) flags = flags or FLAG_ICC
        if (items.any { it.fourCc == EXIF }) flags = flags or FLAG_EXIF
        if (items.any { it.fourCc == XMP }) flags = flags or FLAG_XMP
        header[0] = flags.toByte()
        return header
    }

    private fun le16(bytes: ByteArray, at: Int): Int = (bytes[at].toInt() and 0xFF) or ((bytes[at + 1].toInt() and 0xFF) shl 8)

    private fun le32(bytes: ByteArray, at: Int): Long = ByteBuffer.wrap(bytes, at, 4).order(ByteOrder.LITTLE_ENDIAN).int.toLong() and 0xFFFFFFFFL

    private fun le32(value: Long): ByteArray = ByteBuffer.allocate(4).order(ByteOrder.LITTLE_ENDIAN).putInt(value.toInt()).array()

    private fun put24(bytes: ByteArray, at: Int, value: Int) {
        bytes[at] = value.toByte()
        bytes[at + 1] = (value shr 8).toByte()
        bytes[at + 2] = (value shr 16).toByte()
    }

    companion object {
        private const val VP8X = "VP8X"
        private const val VP8 = "VP8 "
        private const val VP8L = "VP8L"
        private const val EXIF = "EXIF"
        private const val XMP = "XMP "
        private const val ICCP = "ICCP"
        private val IMAGE_DATA = setOf(VP8, VP8L, "ALPH", "ANMF")
        private val RIFF = "RIFF".latin1()
        private val WEBP = "WEBP".latin1()
        private val EXIF_PREFIX = "Exif\u0000\u0000".latin1()
        private const val FLAG_ICC = 0x20
        private const val FLAG_ALPHA = 0x10
        private const val FLAG_EXIF = 0x08
        private const val FLAG_XMP = 0x04
        private const val BITSTREAM_HEADER = 30
        private const val MAX_CHUNK = 256 * 1024 * 1024
    }
}
