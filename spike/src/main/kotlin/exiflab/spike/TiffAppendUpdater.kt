package exiflab.spike

import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * Prototype of an "append-only" TIFF/EXIF updater.
 *
 * Idea: never move existing bytes. New versions of the IFDs that change (IFD0, ExifIFD,
 * GPS) are appended to the end of the TIFF stream and the parent pointers are patched.
 * Unchanged out-of-line values (MakerNote, strips, tiles, sub-IFDs, previews) keep their
 * original offsets, so every absolute offset in the file stays valid, including the ones
 * inside MakerNotes that we do not understand.
 *
 * Superseded bytes (old IFD tables, old values of edited/removed tags, the old GPS IFD)
 * are zeroed so removed data cannot be recovered from the file.
 *
 * Works on a complete TIFF-based file (DNG, CR2, NEF, ...) and on the TIFF block inside
 * JPEG APP1 / PNG eXIf / WebP EXIF / HEIF Exif items. Classic TIFF only (no BigTIFF).
 */
class TiffAppendUpdater(private val original: ByteArray?) {

    data class Entry(val tag: Int, val type: Int, val count: Long, val value: ByteArray)

    class Edits(
        val ifd0: Map<Int, Entry?> = emptyMap(), // null value = remove tag
        val exif: Map<Int, Entry?> = emptyMap(),
        /** null = leave GPS alone, empty list = remove GPS IFD, otherwise replace it. */
        val gps: List<Entry>? = null,
    )

    private val buf: ByteArray = original ?: freshTiff()
    private val order: ByteOrder =
        if (buf[0] == 'I'.code.toByte()) ByteOrder.LITTLE_ENDIAN else ByteOrder.BIG_ENDIAN

    private data class RawEntry(val tag: Int, val type: Int, val count: Long, val field: ByteArray, val fieldPos: Int) {
        fun size(): Long = count * typeSize(type)
        fun isInline() = size() <= 4
    }

    private data class Ifd(val offset: Int, val entries: List<RawEntry>, val next: Long) {
        fun tableLength() = 2 + entries.size * 12 + 4
    }

    /** Raw value bytes of a GPS tag as stored in the original file (for privacy checks). */
    fun gpsValueBytes(tag: Int): ByteArray? {
        val ifd0 = readIfd(u32(4).toInt())
        val gpsPtr = ifd0.entries.firstOrNull { it.tag == TAG_GPS_IFD }?.let { u32(it.fieldPos).toInt() } ?: return null
        val e = readIfd(gpsPtr).entries.firstOrNull { it.tag == tag } ?: return null
        if (e.isInline()) return e.field.copyOf(e.size().toInt())
        val off = u32(e.fieldPos).toInt()
        return buf.copyOfRange(off, off + e.size().toInt())
    }

    fun apply(edits: Edits): ByteArray {
        val firstIfd = u32(4).toInt()
        val ifd0 = readIfd(firstIfd)
        val exifPtr = ifd0.entries.firstOrNull { it.tag == TAG_EXIF_IFD }?.let { u32(it.fieldPos).toInt() }
        val gpsPtr = ifd0.entries.firstOrNull { it.tag == TAG_GPS_IFD }?.let { u32(it.fieldPos).toInt() }
        val exifIfd = exifPtr?.let { readIfd(it) }
        val gpsIfd = gpsPtr?.let { readIfd(it) }

        val out = ByteArrayOutputStream(buf.size + 4096)
        out.write(buf)
        val zeroRanges = mutableListOf<IntRange>()

        // GPS
        var newGpsOffset: Long? = gpsPtr?.toLong()
        if (edits.gps != null) {
            if (gpsIfd != null) {
                zeroRanges += gpsIfd.offset until gpsIfd.offset + gpsIfd.tableLength()
                gpsIfd.entries.filter { !it.isInline() }.forEach { e ->
                    val off = u32(e.fieldPos).toInt()
                    zeroRanges += off until off + e.size().toInt()
                }
            }
            newGpsOffset = if (edits.gps.isEmpty()) null else writeIfd(out, edits.gps.map { it.toRaw() }, 0)
        }

        // ExifIFD
        var newExifOffset: Long? = exifPtr?.toLong()
        if (edits.exif.isNotEmpty()) {
            val merged = merge(exifIfd?.entries.orEmpty(), edits.exif, zeroRanges)
            newExifOffset = writeIfd(out, merged, 0)
        }

        // IFD0 (always rewritten so the pointers can be patched)
        val ifd0Edits = LinkedHashMap<Int, Entry?>(edits.ifd0)
        ifd0Edits[TAG_EXIF_IFD] = newExifOffset?.let { Entry(TAG_EXIF_IFD, TYPE_LONG, 1, u32Bytes(it)) }
        ifd0Edits[TAG_GPS_IFD] = newGpsOffset?.let { Entry(TAG_GPS_IFD, TYPE_LONG, 1, u32Bytes(it)) }
        val ifd0Entries = merge(ifd0.entries, ifd0Edits, zeroRanges)
        val newIfd0Offset = writeIfd(out, ifd0Entries, ifd0.next)

        val result = out.toByteArray()
        // Old tables are no longer referenced: zero them as well.
        if (original != null) {
            zeroRanges += ifd0.offset until ifd0.offset + ifd0.tableLength()
            if (exifIfd != null && edits.exif.isNotEmpty()) {
                zeroRanges += exifIfd.offset until exifIfd.offset + exifIfd.tableLength()
            }
        }
        for (r in zeroRanges) for (i in r) if (i in 8 until buf.size) result[i] = 0
        ByteBuffer.wrap(result).order(order).putInt(4, newIfd0Offset.toInt())
        return result
    }

    /** Merge original entries with edits. Values of replaced/removed out-of-line entries are scheduled for zeroing. */
    private fun merge(original: List<RawEntry>, edits: Map<Int, Entry?>, zero: MutableList<IntRange>): List<RawEntry> {
        val byTag = LinkedHashMap<Int, RawEntry>()
        original.forEach { byTag[it.tag] = it }
        for ((tag, entry) in edits) {
            val old = byTag[tag]
            if (old != null && !old.isInline() && tag != TAG_EXIF_IFD && tag != TAG_GPS_IFD) {
                val off = u32(old.fieldPos).toInt()
                zero += off until off + old.size().toInt()
            }
            if (entry == null) byTag.remove(tag) else byTag[tag] = entry.toRaw()
        }
        return byTag.values.sortedBy { it.tag }
    }

    /** Append an IFD (values first, then the table). Returns the table offset. */
    private fun writeIfd(out: ByteArrayOutputStream, entries: List<RawEntry>, next: Long): Long {
        val fields = ArrayList<ByteArray>()
        for (e in entries) {
            if (e.fieldPos >= 0) {
                fields += e.field // original entry: inline value or original offset, unchanged
            } else if (e.field.size <= 4) {
                fields += e.field.copyOf(4)
            } else {
                align(out)
                val off = out.size().toLong()
                out.write(e.field)
                fields += u32Bytes(off)
            }
        }
        align(out)
        val tableOffset = out.size().toLong()
        val table = ByteBuffer.allocate(2 + entries.size * 12 + 4).order(order)
        table.putShort(entries.size.toShort())
        entries.forEachIndexed { i, e ->
            table.putShort(e.tag.toShort()).putShort(e.type.toShort()).putInt(e.count.toInt()).put(fields[i])
        }
        table.putInt(next.toInt())
        out.write(table.array())
        return tableOffset
    }

    private fun Entry.toRaw() = RawEntry(tag, type, count, value, -1)

    private fun readIfd(offset: Int): Ifd {
        val n = u16(offset)
        val entries = (0 until n).map { i ->
            val p = offset + 2 + i * 12
            RawEntry(u16(p), u16(p + 2), u32(p + 4), buf.copyOfRange(p + 8, p + 12), p + 8)
        }
        return Ifd(offset, entries, u32(offset + 2 + n * 12))
    }

    private fun align(out: ByteArrayOutputStream) { if (out.size() % 2 != 0) out.write(0) }
    private fun u16(p: Int) = ByteBuffer.wrap(buf, p, 2).order(order).short.toInt() and 0xFFFF
    private fun u32(p: Int) = ByteBuffer.wrap(buf, p, 4).order(order).int.toLong() and 0xFFFFFFFFL
    private fun u32Bytes(v: Long) = ByteBuffer.allocate(4).order(order).putInt(v.toInt()).array()

    // Value encoders bound to this file's byte order.
    fun ascii(tag: Int, s: String): Entry { val b = (s + "\u0000").toByteArray(Charsets.US_ASCII); return Entry(tag, TYPE_ASCII, b.size.toLong(), b) }
    fun bytes(tag: Int, vararg v: Int) = Entry(tag, TYPE_BYTE, v.size.toLong(), ByteArray(v.size) { v[it].toByte() })
    fun rationals(tag: Int, vararg v: Pair<Long, Long>): Entry {
        val b = ByteBuffer.allocate(v.size * 8).order(order)
        v.forEach { (n, d) -> b.putInt(n.toInt()).putInt(d.toInt()) }
        return Entry(tag, TYPE_RATIONAL, v.size.toLong(), b.array())
    }

    companion object {
        const val TAG_EXIF_IFD = 0x8769
        const val TAG_GPS_IFD = 0x8825
        const val TYPE_BYTE = 1; const val TYPE_ASCII = 2; const val TYPE_LONG = 4; const val TYPE_RATIONAL = 5

        fun typeSize(type: Int): Int = when (type) {
            1, 2, 6, 7 -> 1
            3, 8 -> 2
            4, 9, 11, 13 -> 4
            5, 10, 12 -> 8
            else -> 1
        }

        private fun freshTiff(): ByteArray {
            // "MM", 42, IFD0 at 8, IFD0 with zero entries and no next IFD.
            return byteArrayOf(0x4D, 0x4D, 0, 42, 0, 0, 0, 8, 0, 0, 0, 0, 0, 0)
        }
    }
}
