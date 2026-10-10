package io.github.fishpimp.exiflab.metadata.write.jpeg

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
import io.github.fishpimp.exiflab.metadata.write.iptc.IptcIim
import io.github.fishpimp.exiflab.metadata.write.iptc.PhotoshopResources
import io.github.fishpimp.exiflab.metadata.write.latin1
import io.github.fishpimp.exiflab.metadata.write.startsWith
import io.github.fishpimp.exiflab.metadata.write.tiff.ByteOrder
import io.github.fishpimp.exiflab.metadata.write.tiff.ExifEditor
import io.github.fishpimp.exiflab.metadata.write.tiff.JpegFacts
import io.github.fishpimp.exiflab.metadata.write.tiff.TiffParser
import io.github.fishpimp.exiflab.metadata.write.tiff.TiffSerializer
import io.github.fishpimp.exiflab.metadata.write.xmp.XmpPackets
import java.io.OutputStream
import java.nio.ByteBuffer

/**
 * Rewrites the metadata segments of a JPEG. Segments up to the first scan are streamed; only APP1
 * Exif, APP1 XMP (with Extended XMP), APP13 Photoshop/IPTC, APP2 ICC (removal only) and COM
 * (removal only) are touched, each where it was. New Exif goes right after SOI (and JFIF), new XMP
 * right after Exif, new APP13 after XMP. Everything from the first SOS marker to the end of the
 * file, including data after EOI, is copied byte for byte; MPF offsets are fixed when segments
 * between the MPF header and the image data change size.
 */
internal class JpegWriter {
    private enum class Kind { Exif, Xmp, ExtendedXmp, Icc, Mpf, Photoshop, Comment, Other }

    /** A segment of the source: [offset] is its first byte (fill bytes included), [size] its total length. */
    private class Segment(
        val marker: Int,
        val offset: Long,
        val size: Int,
        val payloadOffset: Long,
        val payload: ByteArray?,
        val kind: Kind,
        /** The first bytes of the payload, enough for a frame header. */
        val prefix: ByteArray = ByteArray(0),
    )

    /** One segment of the output, copied from the source or newly built. */
    private sealed class Item(val kind: Kind) {
        abstract val size: Int

        class Copy(val segment: Segment) : Item(segment.kind) {
            override val size: Int get() = segment.size
        }

        class New(val marker: Int, val payload: ByteArray, kind: Kind) : Item(kind) {
            init {
                require(payload.size + 2 <= MAX_SEGMENT_LENGTH) { "Segment too large" }
            }

            override val size: Int get() = 4 + payload.size
        }
    }

    fun write(source: SeekableSource, changes: MetadataChanges, out: OutputStream, skipped: MutableList<String>) {
        val (segments, sosOffset) = scan(source)
        val items = segments.map<Segment, Item> { Item.Copy(it) }.toMutableList()
        val removeBlocks = changes.removeBlocks

        if (ExifEditor.affects(changes)) replaceExif(items, segments, changes, skipped)
        if (XmpPackets.affects(changes)) replaceXmp(items, segments, changes)
        if (IptcIim.affects(changes)) replacePhotoshop(items, segments, changes, skipped)
        if (MetadataBlock.IccProfile in removeBlocks) items.removeAll { it.kind == Kind.Icc }
        if (MetadataBlock.Comments in removeBlocks) items.removeAll { it.kind == Kind.Comment }
        fixMpf(items, sosOffset)

        out.write(SOI)
        for (item in items) {
            when (item) {
                is Item.Copy -> copyRange(source, item.segment.offset, item.segment.size.toLong(), out)
                is Item.New -> {
                    out.write(0xFF)
                    out.write(item.marker)
                    out.write((item.payload.size + 2) ushr 8)
                    out.write(item.payload.size + 2)
                    out.write(item.payload)
                }
            }
        }
        var previous = 0
        var sawEoi = false
        copyToEnd(source, sosOffset, out) { buffer, count ->
            if (!sawEoi) {
                for (i in 0 until count) {
                    val c = buffer[i].toInt() and 0xFF
                    if (previous == 0xFF && c == EOI) {
                        sawEoi = true
                        break
                    }
                    previous = c
                }
            }
        }
        if (!sawEoi) throw CorruptImageException("The JPEG image data ends without an end marker; the file may be truncated")
    }

    private fun scan(source: SeekableSource): Pair<List<Segment>, Long> {
        val start = source.bytesAt(0, 2)
        if ((start[0].toInt() and 0xFF) != 0xFF || (start[1].toInt() and 0xFF) != 0xD8) throw CorruptImageException("Not a JPEG file")
        val segments = mutableListOf<Segment>()
        var position = 2L
        while (true) {
            if (u8(source, position) != 0xFF) throw CorruptImageException("The JPEG marker structure is damaged at offset $position")
            var markerAt = position + 1
            var marker = u8(source, markerAt)
            while (marker == 0xFF) marker = u8(source, ++markerAt)
            when {
                marker == SOS -> {
                    val length = u16(source, markerAt + 1)
                    if (length < 2) throw CorruptImageException("The JPEG scan header is damaged")
                    source.bytesAt(markerAt + length, 1)
                    return segments to position
                }
                marker == EOI -> throw CorruptImageException("The JPEG file has no image data")
                marker == TEM || marker in RST0..RST7 -> {
                    segments += Segment(marker, position, (markerAt + 1 - position).toInt(), markerAt + 1, null, Kind.Other)
                    position = markerAt + 1
                }
                marker == 0x00 -> throw CorruptImageException("The JPEG marker structure is damaged at offset $position")
                else -> {
                    val length = u16(source, markerAt + 1)
                    if (length < 2) throw CorruptImageException("The JPEG segment at offset $position has an invalid length")
                    val payloadOffset = markerAt + 3
                    val payloadLength = length - 2
                    val prefix = if (payloadLength > 0) source.bytesAt(payloadOffset, minOf(payloadLength, PREFIX_LENGTH)) else ByteArray(0)
                    val kind = classify(marker, prefix)
                    val payload = when {
                        kind in LOADED_KINDS -> source.bytesAt(payloadOffset, payloadLength)
                        payloadLength > 0 -> {
                            source.bytesAt(payloadOffset + payloadLength - 1, 1)
                            null
                        }
                        else -> null
                    }
                    val size = (payloadOffset + payloadLength - position).toInt()
                    segments += Segment(marker, position, size, payloadOffset, payload, kind, prefix)
                    position += size
                }
            }
        }
    }

    private fun classify(marker: Int, prefix: ByteArray): Kind = when {
        marker == APP1 && prefix.startsWith(EXIF_SIGNATURE) -> Kind.Exif
        marker == APP1 && prefix.startsWith(XMP_SIGNATURE) -> Kind.Xmp
        marker == APP1 && prefix.startsWith(EXTENDED_XMP_SIGNATURE) -> Kind.ExtendedXmp
        marker == APP2 && prefix.startsWith(ICC_SIGNATURE) -> Kind.Icc
        marker == APP2 && prefix.startsWith(MPF_SIGNATURE) -> Kind.Mpf
        marker == APP13 && prefix.startsWith(PHOTOSHOP_SIGNATURE) -> Kind.Photoshop
        marker == COM -> Kind.Comment
        else -> Kind.Other
    }

    private fun replaceExif(items: MutableList<Item>, segments: List<Segment>, changes: MetadataChanges, skipped: MutableList<String>) {
        val primary = segments.firstOrNull { it.kind == Kind.Exif }
        val removeAll = MetadataBlock.Exif in changes.removeBlocks
        val original = if (primary == null || removeAll) null else primary.payload!!.let { TiffParser.parse(it.copyOfRange(minOf(EXIF_HEADER.size, it.size), it.size), skipped) }
        val edited = ExifEditor.apply(original, changes, jpegFacts(segments, changes), skipped)
        val replacement = edited?.let { Item.New(APP1, EXIF_HEADER + TiffSerializer.serialize(it, MAX_PAYLOAD - EXIF_HEADER.size, skipped), Kind.Exif) }
        // The primary Exif segment is the first one, so removing later ones keeps its index valid.
        val index = items.indexOfFirst { it is Item.Copy && it.segment === primary }
        if (removeAll) items.removeAll { it.kind == Kind.Exif && !(it is Item.Copy && it.segment === primary) }
        when {
            replacement == null -> if (index >= 0) items.removeAt(index)
            index >= 0 -> items[index] = replacement
            else -> items.add(leadingApp0Count(items), replacement)
        }
    }

    private fun jpegFacts(segments: List<Segment>, changes: MetadataChanges): JpegFacts {
        val frame = segments.firstOrNull { it.marker in SOF_MARKERS && it.prefix.size >= 5 }?.prefix
        val height = frame?.let { ((it[1].toInt() and 0xFF) shl 8) or (it[2].toInt() and 0xFF) } ?: 0
        val width = frame?.let { ((it[3].toInt() and 0xFF) shl 8) or (it[4].toInt() and 0xFF) } ?: 0
        val icc = segments.any { it.kind == Kind.Icc } && MetadataBlock.IccProfile !in changes.removeBlocks
        return JpegFacts(width, height, icc)
    }

    private fun replaceXmp(items: MutableList<Item>, segments: List<Segment>, changes: MetadataChanges) {
        val standard = segments.filter { it.kind == Kind.Xmp }
        var meta: XMPMeta? = null
        if (MetadataBlock.Xmp !in changes.removeBlocks) {
            for (segment in standard) {
                val packet = XmpPackets.parse(segment.payload!!.copyOfRange(XMP_SIGNATURE.size, segment.payload.size))
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
            meta?.let { main -> XmpPackets.extendedGuid(main)?.let { guid -> XmpPackets.mergeExtended(main, extendedPacket(segments, guid)) } }
        }
        val packaged = XmpPackets.edit(meta, changes)?.let { XmpPackets.packageForJpeg(it, MAX_STANDARD_XMP) }
        val replacement = mutableListOf<Item>()
        if (packaged != null) {
            replacement += Item.New(APP1, XMP_SIGNATURE + packaged.standard, Kind.Xmp)
            val extended = packaged.extended
            if (extended != null) {
                val guid = packaged.guid!!.latin1()
                var offset = 0
                while (offset < extended.size) {
                    val count = minOf(MAX_EXTENDED_CHUNK, extended.size - offset)
                    val header = EXTENDED_XMP_SIGNATURE + guid + int32(extended.size) + int32(offset)
                    replacement += Item.New(APP1, header + extended.copyOfRange(offset, offset + count), Kind.ExtendedXmp)
                    offset += count
                }
            }
        }
        val xmpKinds = setOf(Kind.Xmp, Kind.ExtendedXmp)
        val index = items.indexOfFirst { it.kind == Kind.Xmp }
        val kept = if (index >= 0) items.subList(0, index).count { it.kind !in xmpKinds } else -1
        items.removeAll { it.kind in xmpKinds }
        val at = when {
            kept >= 0 -> kept
            else -> items.indexOfFirst { it.kind == Kind.Exif }.let { if (it >= 0) it + 1 else leadingApp0Count(items) }
        }
        items.addAll(at, replacement)
    }

    /** Reassembles the Extended XMP named by [guid] from its chunks. */
    private fun extendedPacket(segments: List<Segment>, guid: String): ByteArray {
        val chunks = segments.filter { it.kind == Kind.ExtendedXmp }.map { it.payload!! }
            .filter { it.size >= EXTENDED_HEADER_SIZE && String(it, EXTENDED_XMP_SIGNATURE.size, GUID_LENGTH, Charsets.ISO_8859_1) == guid }
        fun incomplete(): Nothing = throw UnsupportedEditException("The extended XMP metadata is incomplete and cannot be edited safely")
        if (chunks.isEmpty()) incomplete()
        val total = ByteBuffer.wrap(chunks.first(), EXTENDED_XMP_SIGNATURE.size + GUID_LENGTH, 4).int
        if (total <= 0) incomplete()
        val packet = ByteArray(total)
        var covered = 0L
        for (chunk in chunks) {
            val offset = ByteBuffer.wrap(chunk, EXTENDED_XMP_SIGNATURE.size + GUID_LENGTH + 4, 4).int
            val length = chunk.size - EXTENDED_HEADER_SIZE
            if (offset < 0 || offset + length > total) incomplete()
            chunk.copyInto(packet, offset, EXTENDED_HEADER_SIZE, chunk.size)
            covered += length
        }
        if (covered < total) incomplete()
        return packet
    }

    private fun replacePhotoshop(items: MutableList<Item>, segments: List<Segment>, changes: MetadataChanges, skipped: MutableList<String>) {
        val photoshop = segments.filter { it.kind == Kind.Photoshop }
        val resources = if (photoshop.isEmpty() || MetadataBlock.PhotoshopResources in changes.removeBlocks) {
            null
        } else {
            // Photoshop splits large resource data over several APP13 segments; the parts join up.
            val joined = photoshop.fold(ByteArray(0)) { acc, s -> acc + s.payload!!.copyOfRange(PHOTOSHOP_SIGNATURE.size, s.payload.size) }
            PhotoshopResources.parse(joined)
        }
        val edited = PhotoshopResources.edit(resources, changes, skipped)
        val replacement = mutableListOf<Item>()
        if (edited != null) {
            val data = PhotoshopResources.serialize(edited)
            val chunk = MAX_PAYLOAD - PHOTOSHOP_SIGNATURE.size
            var offset = 0
            do {
                val count = minOf(chunk, data.size - offset)
                replacement += Item.New(APP13, PHOTOSHOP_SIGNATURE + data.copyOfRange(offset, offset + count), Kind.Photoshop)
                offset += count
            } while (offset < data.size)
        }
        val index = items.indexOfFirst { it.kind == Kind.Photoshop }
        items.removeAll { it.kind == Kind.Photoshop }
        val at = when {
            index >= 0 -> minOf(index, items.size)
            else -> listOf(Kind.ExtendedXmp, Kind.Xmp, Kind.Exif).firstNotNullOfOrNull { kind ->
                items.indexOfLast { it.kind == kind }.takeIf { it >= 0 }?.plus(1)
            } ?: leadingApp0Count(items)
        }
        items.addAll(at, replacement)
    }

    /**
     * MPF offsets count from the MPF TIFF header. They stay valid while the distance from that
     * header to the image data is unchanged; otherwise every non-zero entry offset moves by the
     * difference.
     */
    private fun fixMpf(items: MutableList<Item>, sosOffset: Long) {
        val index = items.indexOfFirst { it.kind == Kind.Mpf }
        if (index < 0) return
        val copy = items[index] as? Item.Copy ?: return
        val segment = copy.segment
        val normalized = Item.New(segment.marker, segment.payload!!, Kind.Mpf)
        items[index] = normalized
        val oldTiff = segment.payloadOffset + MPF_SIGNATURE.size
        val newTiff = 2L + items.take(index).sumOf { it.size.toLong() } + 4 + MPF_SIGNATURE.size
        val newSos = 2L + items.sumOf { it.size.toLong() }
        val delta = (newSos - sosOffset) - (newTiff - oldTiff)
        items[index] = when {
            delta != 0L -> Item.New(segment.marker, Mpf.shiftOffsets(segment.payload, MPF_SIGNATURE.size, delta), Kind.Mpf)
            // Without fill bytes the source segment is identical to the normalized one.
            copy.size == normalized.size -> copy
            else -> normalized
        }
    }

    private fun leadingApp0Count(items: List<Item>): Int {
        var count = 0
        while (count < items.size && (items[count] as? Item.Copy)?.segment?.marker == APP0) count++
        return count
    }

    private fun u8(source: SeekableSource, position: Long): Int = source.bytesAt(position, 1)[0].toInt() and 0xFF

    private fun u16(source: SeekableSource, position: Long): Int {
        val bytes = source.bytesAt(position, 2)
        return ((bytes[0].toInt() and 0xFF) shl 8) or (bytes[1].toInt() and 0xFF)
    }

    private fun int32(value: Int) = ByteBuffer.allocate(4).putInt(value).array()

    companion object {
        private val SOI = byteArrayOf(0xFF.toByte(), 0xD8.toByte())
        private const val APP0 = 0xE0
        private const val APP1 = 0xE1
        private const val APP2 = 0xE2
        private const val APP13 = 0xED
        private const val COM = 0xFE
        private const val SOS = 0xDA
        private const val EOI = 0xD9
        private const val TEM = 0x01
        private const val RST0 = 0xD0
        private const val RST7 = 0xD7
        private const val PREFIX_LENGTH = 64

        /** Frame header markers SOF0-SOF15, without DHT (C4), JPG (C8) and DAC (CC). */
        private val SOF_MARKERS = (0xC0..0xCF).toSet() - setOf(0xC4, 0xC8, 0xCC)

        /** Largest segment length field (it counts its own two bytes). */
        private const val MAX_SEGMENT_LENGTH = 0xFFFF
        private const val MAX_PAYLOAD = MAX_SEGMENT_LENGTH - 2

        /** XMP specification part 3: the standard packet of a JPEG must not exceed 65502 bytes. */
        private const val MAX_STANDARD_XMP = 65502
        private const val GUID_LENGTH = 32

        val EXIF_HEADER = "Exif\u0000\u0000".latin1()
        private val EXIF_SIGNATURE = "Exif\u0000".latin1()
        val XMP_SIGNATURE = "http://ns.adobe.com/xap/1.0/\u0000".latin1()
        val EXTENDED_XMP_SIGNATURE = "http://ns.adobe.com/xmp/extension/\u0000".latin1()
        private val ICC_SIGNATURE = "ICC_PROFILE\u0000".latin1()
        private val MPF_SIGNATURE = "MPF\u0000".latin1()
        private val PHOTOSHOP_SIGNATURE = "Photoshop 3.0\u0000".latin1()
        private val EXTENDED_HEADER_SIZE = EXTENDED_XMP_SIGNATURE.size + GUID_LENGTH + 8
        private val MAX_EXTENDED_CHUNK = MAX_PAYLOAD - EXTENDED_HEADER_SIZE
        private val LOADED_KINDS = setOf(Kind.Exif, Kind.Xmp, Kind.ExtendedXmp, Kind.Photoshop, Kind.Mpf)
    }
}

/** Multi-Picture Format index (CIPA DC-007) in APP2. */
internal object Mpf {
    private const val MP_ENTRY = 0xB002
    private const val ENTRY_SIZE = 16

    /** Returns a copy of [payload] with every non-zero MP entry offset moved by [delta]. */
    fun shiftOffsets(payload: ByteArray, tiffStart: Int, delta: Long): ByteArray {
        fun damaged(): Nothing = throw UnsupportedEditException("The MPF index is damaged; secondary images cannot be kept valid")
        if (payload.size < tiffStart + 8) damaged()
        val order = when (String(payload, tiffStart, 2, Charsets.ISO_8859_1)) {
            "MM" -> ByteOrder(true)
            "II" -> ByteOrder(false)
            else -> damaged()
        }
        val patched = payload.copyOf()
        val ifd = tiffStart + order.u32(payload, tiffStart + 4).toInt()
        if (ifd < tiffStart + 8 || ifd + 2 > payload.size) damaged()
        val count = order.u16(payload, ifd)
        if (ifd + 2 + count * 12 > payload.size) damaged()
        for (i in 0 until count) {
            val at = ifd + 2 + i * 12
            if (order.u16(payload, at) != MP_ENTRY) continue
            val length = order.u32(payload, at + 4)
            val entries = tiffStart + order.u32(payload, at + 8)
            if (length % ENTRY_SIZE != 0L || entries + length > payload.size) damaged()
            for (e in 0 until (length / ENTRY_SIZE).toInt()) {
                val field = (entries + e * ENTRY_SIZE + 8).toInt()
                val offset = order.u32(payload, field)
                if (offset != 0L) {
                    val moved = offset + delta
                    if (moved !in 0..0xFFFFFFFFL) damaged()
                    order.put32(patched, field, moved)
                }
            }
        }
        return patched
    }
}
