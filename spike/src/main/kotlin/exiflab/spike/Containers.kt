package exiflab.spike

import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.zip.CRC32

/**
 * Prototype container writers. Each one locates the EXIF TIFF block inside a container,
 * hands it to a TIFF engine and splices the result back without touching image data.
 */
interface ExifContainer {
    /** The current TIFF block, or null if the file has no EXIF. */
    fun readTiff(file: ByteArray): ByteArray?
    /** Returns a new file with the TIFF block replaced/inserted. */
    fun writeTiff(file: ByteArray, tiff: ByteArray): ByteArray
}

class UnsupportedLayout(msg: String) : Exception(msg)

private val EXIF_PREFIX = byteArrayOf(0x45, 0x78, 0x69, 0x66, 0, 0) // "Exif\0\0"

/** JPEG: EXIF lives in an APP1 segment starting with "Exif\0\0". Everything from SOS on is copied verbatim. */
object JpegContainer : ExifContainer {
    private data class Segment(val marker: Int, val start: Int, val payloadStart: Int, val end: Int)

    private fun segments(b: ByteArray): Pair<List<Segment>, Int> {
        require(b[0] == 0xFF.toByte() && b[1] == 0xD8.toByte()) { "not a JPEG" }
        val list = ArrayList<Segment>()
        var p = 2
        while (p < b.size) {
            if (b[p] != 0xFF.toByte()) throw UnsupportedLayout("bad marker at $p")
            val m = b[p + 1].toInt() and 0xFF
            if (m == 0xFF) { p++; continue }
            if (m == 0xDA) return list to p // SOS: rest of file (scan data, EOI, trailers) is opaque
            val len = ((b[p + 2].toInt() and 0xFF) shl 8) or (b[p + 3].toInt() and 0xFF)
            list += Segment(m, p, p + 4, p + 2 + len)
            p += 2 + len
        }
        throw UnsupportedLayout("no SOS")
    }

    private fun Segment.isExif(b: ByteArray) =
        marker == 0xE1 && end - payloadStart >= 6 && b.copyOfRange(payloadStart, payloadStart + 6).contentEquals(EXIF_PREFIX)

    override fun readTiff(file: ByteArray): ByteArray? {
        val (segs, _) = segments(file)
        val s = segs.firstOrNull { it.isExif(file) } ?: return null
        return file.copyOfRange(s.payloadStart + 6, s.end)
    }

    override fun writeTiff(file: ByteArray, tiff: ByteArray): ByteArray {
        val payloadLen = 6 + tiff.size
        if (payloadLen + 2 > 0xFFFF) throw UnsupportedLayout("EXIF block ${tiff.size} B exceeds the 64 KiB APP1 limit")
        val (segs, sos) = segments(file)
        val exif = segs.firstOrNull { it.isExif(file) }
        if (segs.any { it.marker == 0xE2 && file.decodeAscii(it.payloadStart, 4) == "MPF\u0000" } &&
            exif != null && segs.indexOf(exif) > segs.indexOfFirst { it.marker == 0xE2 }
        ) throw UnsupportedLayout("EXIF after MPF: MPF offsets would need rewriting")
        val out = ByteArrayOutputStream(file.size + tiff.size)
        out.write(file, 0, 2)
        val newSeg = ByteArrayOutputStream().apply {
            write(0xFF); write(0xE1); write((payloadLen + 2) shr 8); write((payloadLen + 2) and 0xFF)
            write(EXIF_PREFIX); write(tiff)
        }.toByteArray()
        var inserted = false
        for (s in segs) {
            if (s === exif) { out.write(newSeg); inserted = true; continue }
            if (!inserted && exif == null && s.marker != 0xE0) { out.write(newSeg); inserted = true }
            out.write(file, s.start, s.end - s.start)
        }
        if (!inserted) out.write(newSeg)
        out.write(file, sos, file.size - sos)
        return out.toByteArray()
    }
}

/** PNG: EXIF in an eXIf chunk (raw TIFF, no "Exif" prefix). New chunk placed before the first IDAT. */
object PngContainer : ExifContainer {
    private data class Chunk(val type: String, val start: Int, val dataStart: Int, val length: Int) {
        val end get() = dataStart + length + 4
    }

    private fun chunks(b: ByteArray): List<Chunk> {
        val list = ArrayList<Chunk>()
        var p = 8
        while (p + 8 <= b.size) {
            val len = ByteBuffer.wrap(b, p, 4).int
            val type = b.decodeAscii(p + 4, 4)
            list += Chunk(type, p, p + 8, len)
            p += 12 + len
            if (type == "IEND") break
        }
        return list
    }

    override fun readTiff(file: ByteArray): ByteArray? =
        chunks(file).firstOrNull { it.type == "eXIf" }?.let { file.copyOfRange(it.dataStart, it.dataStart + it.length) }

    override fun writeTiff(file: ByteArray, tiff: ByteArray): ByteArray {
        val cs = chunks(file)
        val out = ByteArrayOutputStream(file.size + tiff.size)
        out.write(file, 0, 8)
        var written = false
        for (c in cs) {
            if (c.type == "eXIf") continue
            if (!written && c.type == "IDAT") { out.write(chunk("eXIf", tiff)); written = true }
            out.write(file, c.start, c.end - c.start)
        }
        val last = cs.last()
        out.write(file, last.end, file.size - last.end) // anything after IEND, verbatim
        return out.toByteArray()
    }

    private fun chunk(type: String, data: ByteArray): ByteArray {
        val t = type.toByteArray(Charsets.US_ASCII)
        val crc = CRC32().apply { update(t); update(data) }.value
        return ByteBuffer.allocate(12 + data.size).putInt(data.size).put(t).put(data).putInt(crc.toInt()).array()
    }
}

/** WebP: EXIF in an "EXIF" RIFF chunk; requires the VP8X header with the EXIF flag set. */
object WebpContainer : ExifContainer {
    private data class Chunk(val fourcc: String, val start: Int, val dataStart: Int, val size: Int) {
        val end get() = dataStart + size + (size and 1)
    }

    private fun chunks(b: ByteArray): List<Chunk> {
        require(b.decodeAscii(0, 4) == "RIFF" && b.decodeAscii(8, 4) == "WEBP") { "not WebP" }
        val list = ArrayList<Chunk>()
        var p = 12
        while (p + 8 <= b.size) {
            val size = ByteBuffer.wrap(b, p + 4, 4).order(ByteOrder.LITTLE_ENDIAN).int
            list += Chunk(b.decodeAscii(p, 4), p, p + 8, size)
            p += 8 + size + (size and 1)
        }
        return list
    }

    override fun readTiff(file: ByteArray): ByteArray? {
        val c = chunks(file).firstOrNull { it.fourcc == "EXIF" } ?: return null
        var data = file.copyOfRange(c.dataStart, c.dataStart + c.size)
        if (data.size >= 6 && data.copyOfRange(0, 6).contentEquals(EXIF_PREFIX)) data = data.copyOfRange(6, data.size)
        return data
    }

    override fun writeTiff(file: ByteArray, tiff: ByteArray): ByteArray {
        val cs = chunks(file)
        val vp8x = cs.firstOrNull { it.fourcc == "VP8X" }
            ?: throw UnsupportedLayout("simple WebP without VP8X: needs VP8X synthesis (planned)")
        val out = ByteArrayOutputStream(file.size + tiff.size)
        out.write(file, 0, 12)
        var written = false
        for (c in cs) {
            if (c.fourcc == "EXIF") continue
            if (!written && c.fourcc == "XMP ") { out.write(chunk("EXIF", tiff)); written = true }
            val bytes = file.copyOfRange(c.start, c.end)
            if (c === vp8x) bytes[8] = (bytes[8].toInt() or 0x08).toByte()
            out.write(bytes)
        }
        if (!written) out.write(chunk("EXIF", tiff))
        val result = out.toByteArray()
        ByteBuffer.wrap(result).order(ByteOrder.LITTLE_ENDIAN).putInt(4, result.size - 8)
        return result
    }

    private fun chunk(fourcc: String, data: ByteArray): ByteArray {
        val b = ByteBuffer.allocate(8 + data.size + (data.size and 1)).order(ByteOrder.LITTLE_ENDIAN)
        b.put(fourcc.toByteArray(Charsets.US_ASCII)).putInt(data.size).put(data)
        return b.array()
    }
}

/**
 * HEIF/HEIC: EXIF is an item of type 'Exif' in the 'meta' box. Its bytes are located via
 * the 'iloc' box. The prototype patches only that item's single extent:
 *  - new data fits in the old extent: overwrite in place, shrink extent_length, zero the tail;
 *  - otherwise: append a new 'mdat' box at EOF, repoint the extent, zero the old bytes.
 * Neither path changes the size of 'meta', so no other offset in the file moves.
 */
object HeifContainer : ExifContainer {
    private data class Box(val type: String, val start: Long, val headerSize: Int, val size: Long) {
        val contentStart get() = start + headerSize
        val end get() = start + size
    }

    private class ExifLocation(
        val extentOffsetPos: Int, val offsetSize: Int,
        val extentLengthPos: Int, val lengthSize: Int,
        val baseOffset: Long, val dataOffset: Long, val dataLength: Long,
    )

    private fun boxes(b: ByteArray, from: Long, to: Long): List<Box> {
        val list = ArrayList<Box>()
        var p = from
        while (p + 8 <= to) {
            var size = b.u32be(p.toInt())
            val type = b.decodeAscii(p.toInt() + 4, 4)
            var header = 8
            if (size == 1L) { size = ByteBuffer.wrap(b, p.toInt() + 8, 8).long; header = 16 }
            if (size == 0L) size = to - p
            list += Box(type, p, header, size)
            p += size
        }
        return list
    }

    private fun locate(b: ByteArray): ExifLocation? {
        val meta = boxes(b, 0, b.size.toLong()).firstOrNull { it.type == "meta" } ?: throw UnsupportedLayout("no meta box")
        val children = boxes(b, meta.contentStart + 4, meta.end)
        val iinf = children.first { it.type == "iinf" }
        val iloc = children.first { it.type == "iloc" }
        // iinf: find item_ID of the 'Exif' item
        val iinfVersion = b[iinf.contentStart.toInt()].toInt()
        val entriesStart = iinf.contentStart + 4 + (if (iinfVersion == 0) 2 else 4)
        val exifId = boxes(b, entriesStart, iinf.end).filter { it.type == "infe" }.firstNotNullOfOrNull { infe ->
            val v = b[infe.contentStart.toInt()].toInt()
            var p = infe.contentStart.toInt() + 4
            if (v < 2) return@firstNotNullOfOrNull null
            val id = if (v == 2) b.u16be(p).toLong().also { p += 2 } else b.u32be(p).also { p += 4 }
            p += 2 // protection index
            if (b.decodeAscii(p, 4) == "Exif") id else null
        } ?: return null
        // iloc
        var p = iloc.contentStart.toInt()
        val version = b[p].toInt(); p += 4
        val offsetSize = (b[p].toInt() shr 4) and 0xF; val lengthSize = b[p].toInt() and 0xF
        val baseOffsetSize = (b[p + 1].toInt() shr 4) and 0xF
        val indexSize = if (version == 1 || version == 2) b[p + 1].toInt() and 0xF else 0
        p += 2
        val itemCount = if (version < 2) b.u16be(p).toLong().also { p += 2 } else b.u32be(p).also { p += 4 }
        repeat(itemCount.toInt()) {
            val id = if (version < 2) b.u16be(p).toLong().also { p += 2 } else b.u32be(p).also { p += 4 }
            var construction = 0
            if (version == 1 || version == 2) { construction = b.u16be(p) and 0xF; p += 2 }
            p += 2 // data_reference_index
            val base = b.readN(p, baseOffsetSize); p += baseOffsetSize
            val extentCount = b.u16be(p); p += 2
            if (id == exifId) {
                if (construction != 0) throw UnsupportedLayout("Exif item stored in idat (construction_method $construction)")
                if (extentCount != 1) throw UnsupportedLayout("Exif item has $extentCount extents")
                p += indexSize
                val offPos = p; val off = b.readN(p, offsetSize); p += offsetSize
                val lenPos = p; val len = b.readN(p, lengthSize)
                if (offsetSize == 0 || lengthSize == 0) throw UnsupportedLayout("iloc offset/length size 0")
                return ExifLocation(offPos, offsetSize, lenPos, lengthSize, base, base + off, len)
            }
            p += extentCount * (indexSize + offsetSize + lengthSize)
        }
        throw UnsupportedLayout("Exif item $exifId not in iloc")
    }

    override fun readTiff(file: ByteArray): ByteArray? {
        val loc = locate(file) ?: return null
        val start = loc.dataOffset.toInt()
        val headerOffset = file.u32be(start).toInt()
        return file.copyOfRange(start + 4 + headerOffset, (loc.dataOffset + loc.dataLength).toInt())
    }

    override fun writeTiff(file: ByteArray, tiff: ByteArray): ByteArray {
        val loc = locate(file) ?: throw UnsupportedLayout("no Exif item: inserting one grows 'meta' (planned)")
        val start = loc.dataOffset.toInt()
        val headerOffset = file.u32be(start).toInt()
        val prefix = file.copyOfRange(start, start + 4 + headerOffset) // keeps "Exif\0\0" if present
        val payload = prefix + tiff
        if (payload.size <= loc.dataLength) {
            val out = file.copyOf()
            payload.copyInto(out, start)
            out.fill(0, start + payload.size, (loc.dataOffset + loc.dataLength).toInt())
            out.writeN(loc.extentLengthPos, loc.lengthSize, payload.size.toLong())
            return out
        }
        val newDataOffset = file.size + 8L
        val rel = newDataOffset - loc.baseOffset
        if (loc.offsetSize == 4 && rel > 0xFFFFFFFFL) throw UnsupportedLayout("file too large for 32-bit iloc offsets")
        val out = ByteArrayOutputStream(file.size + 8 + payload.size)
        val patched = file.copyOf()
        patched.fill(0, start, (loc.dataOffset + loc.dataLength).toInt())
        patched.writeN(loc.extentOffsetPos, loc.offsetSize, rel)
        patched.writeN(loc.extentLengthPos, loc.lengthSize, payload.size.toLong())
        out.write(patched)
        out.write(ByteBuffer.allocate(8).putInt(8 + payload.size).put("mdat".toByteArray()).array())
        out.write(payload)
        return out.toByteArray()
    }
}

/** TIFF-based raw/DNG: the whole file is the TIFF block. */
object TiffFileContainer : ExifContainer {
    override fun readTiff(file: ByteArray) = file
    override fun writeTiff(file: ByteArray, tiff: ByteArray) = tiff
}

internal fun ByteArray.decodeAscii(p: Int, n: Int) = String(this, p, n, Charsets.ISO_8859_1)
internal fun ByteArray.u16be(p: Int) = ((this[p].toInt() and 0xFF) shl 8) or (this[p + 1].toInt() and 0xFF)
internal fun ByteArray.u32be(p: Int) = ByteBuffer.wrap(this, p, 4).int.toLong() and 0xFFFFFFFFL
internal fun ByteArray.readN(p: Int, n: Int): Long { var v = 0L; for (i in 0 until n) v = (v shl 8) or (this[p + i].toLong() and 0xFF); return v }
internal fun ByteArray.writeN(p: Int, n: Int, value: Long) { for (i in 0 until n) this[p + i] = (value shr (8 * (n - 1 - i))).toByte() }
