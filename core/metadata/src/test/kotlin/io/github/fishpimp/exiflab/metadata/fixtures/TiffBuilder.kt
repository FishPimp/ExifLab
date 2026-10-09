package io.github.fishpimp.exiflab.metadata.fixtures

import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * Builds TIFF structures (Exif blocks, RAW-like files) for tests. An [Ifd] holds entries of any
 * TIFF type; sub-IFDs, out-of-line values, embedded MakerNote IFDs and data blobs are laid out
 * automatically with offsets relative to the TIFF header.
 */
class TiffBuilder(
    private val bigEndian: Boolean = true,
    private val magic: Int = 42,
    /** Zero bytes between the header and IFD0, like the image data RAW files put first. */
    private val ifd0Padding: Int = 0,
) {
    /** One image file directory. Entries are written sorted by tag, as TIFF requires. */
    class Ifd {
        internal val entries = mutableListOf<Entry>()
        /** The IFD chained after this one (IFD1 thumbnail, CR2 extra IFDs). */
        var next: Ifd? = null

        fun byte(tag: Int, vararg values: Int) = add(Entry.Data(tag, 1, values.size, values.map { byteArrayOf(it.toByte()) }))
        fun ascii(tag: Int, text: String) = (text.toByteArray(Charsets.UTF_8) + 0.toByte()).let { add(Entry.Data(tag, 2, it.size, listOf(it))) }
        fun short(tag: Int, vararg values: Int) = add(Entry.Data(tag, 3, values.size, values.map { short(it) }))
        fun long(tag: Int, vararg values: Long) = add(Entry.Data(tag, 4, values.size, values.map { int(it.toInt()) }))
        fun rational(tag: Int, vararg values: Pair<Long, Long>) =
            add(Entry.Data(tag, 5, values.size, values.map { int(it.first.toInt()) + int(it.second.toInt()) }))
        fun sbyte(tag: Int, vararg values: Int) = add(Entry.Data(tag, 6, values.size, values.map { byteArrayOf(it.toByte()) }))
        fun undefined(tag: Int, bytes: ByteArray) = add(Entry.Data(tag, 7, bytes.size, listOf(bytes)))
        fun sshort(tag: Int, vararg values: Int) = add(Entry.Data(tag, 8, values.size, values.map { short(it) }))
        fun slong(tag: Int, vararg values: Int) = add(Entry.Data(tag, 9, values.size, values.map { int(it) }))
        fun srational(tag: Int, vararg values: Pair<Int, Int>) =
            add(Entry.Data(tag, 10, values.size, values.map { int(it.first) + int(it.second) }))
        fun float(tag: Int, vararg values: Float) = add(Entry.Data(tag, 11, values.size, values.map { int(it.toRawBits()) }))
        fun double(tag: Int, vararg values: Double) = add(Entry.Data(tag, 12, values.size, values.map { long64(it.toRawBits()) }))

        /** A LONG pointer to [children], e.g. 0x8769 (Exif), 0x8825 (GPS), 0x014A (SubIFDs). */
        fun subIfd(tag: Int, vararg children: Ifd) = add(Entry.SubIfds(tag, children.toList()))

        /** An UNDEFINED entry whose bytes are a whole IFD, as Canon-style MakerNotes are. */
        fun makerNoteIfd(tag: Int, ifd: Ifd) = add(Entry.EmbeddedIfd(tag, ifd))

        /** A LONG entry holding the offset of [blob], which is written out-of-line (JPEG previews, strips). */
        fun offsetOf(tag: Int, blob: ByteArray) = add(Entry.BlobOffset(tag, blob))

        private fun add(entry: Entry) = apply { entries.removeAll { it.tag == entry.tag }; entries += entry }

        // Values are encoded big-endian here and swapped by the builder for little-endian files.
        private fun short(value: Int) = byteArrayOf((value shr 8).toByte(), value.toByte())
        private fun int(value: Int) = ByteBuffer.allocate(4).putInt(value).array()
        private fun long64(value: Long) = ByteBuffer.allocate(8).putLong(value).array()
    }

    internal sealed class Entry(val tag: Int) {
        /** [items] are big-endian encodings of each value; [count] is the TIFF count. */
        class Data(tag: Int, val type: Int, val count: Int, val items: List<ByteArray>) : Entry(tag)
        class SubIfds(tag: Int, val children: List<Ifd>) : Entry(tag)
        class EmbeddedIfd(tag: Int, val ifd: Ifd) : Entry(tag)
        class BlobOffset(tag: Int, val blob: ByteArray) : Entry(tag)
    }

    private val buffer = Buffer()

    /** Serializes a complete TIFF stream ("MM\0*" or "II*\0" header) starting with [ifd0]. */
    fun build(ifd0: Ifd): ByteArray {
        buffer.bytes(if (bigEndian) "MM".toByteArray() else "II".toByteArray())
        buffer.u16(magic)
        buffer.u32(8 + ifd0Padding)
        buffer.bytes(ByteArray(ifd0Padding))
        writeIfd(ifd0)
        return buffer.toByteArray()
    }

    private fun writeIfd(ifd: Ifd): Int {
        buffer.align()
        val start = buffer.size
        val entries = ifd.entries.sortedBy { it.tag }
        buffer.u16(entries.size)
        repeat(entries.size * 12 + 4) { buffer.byte(0) }
        entries.forEachIndexed { index, entry ->
            val at = start + 2 + index * 12
            when (entry) {
                is Entry.Data -> {
                    val bytes = entry.items.fold(ByteArray(0)) { acc, item -> acc + if (bigEndian) item else swap(item, entry.type) }
                    header(at, entry.tag, entry.type, entry.count)
                    if (bytes.size <= 4) buffer.put(at + 8, bytes) else buffer.putU32(at + 8, outOfLine(bytes))
                }
                is Entry.BlobOffset -> {
                    header(at, entry.tag, 4, 1)
                    buffer.putU32(at + 8, outOfLine(entry.blob))
                }
                is Entry.SubIfds -> {
                    header(at, entry.tag, 4, entry.children.size)
                    val table = if (entry.children.size > 1) {
                        outOfLine(ByteArray(entry.children.size * 4)).also { buffer.putU32(at + 8, it) }
                    } else {
                        at + 8
                    }
                    entry.children.forEachIndexed { i, child -> buffer.putU32(table + i * 4, writeIfd(child)) }
                }
                is Entry.EmbeddedIfd -> {
                    val offset = writeIfd(entry.ifd)
                    header(at, entry.tag, 7, buffer.size - offset)
                    buffer.putU32(at + 8, offset)
                }
            }
        }
        ifd.next?.let { buffer.putU32(start + 2 + entries.size * 12, writeIfd(it)) }
        return start
    }

    private fun header(at: Int, tag: Int, type: Int, count: Int) {
        buffer.putU16(at, tag)
        buffer.putU16(at + 2, type)
        buffer.putU32(at + 4, count)
    }

    private fun outOfLine(bytes: ByteArray): Int {
        buffer.align()
        return buffer.size.also { buffer.bytes(bytes) }
    }

    private fun swap(item: ByteArray, type: Int): ByteArray = when (type) {
        1, 2, 6, 7 -> item
        5, 10 -> item.copyOfRange(0, 4).reversedArray() + item.copyOfRange(4, 8).reversedArray()
        else -> item.reversedArray()
    }

    /** Growable byte buffer with in-place patching in the file's byte order. */
    private inner class Buffer {
        private var data = ByteArray(1024)
        var size = 0
            private set

        fun byte(value: Int) {
            ensure(1)
            data[size++] = value.toByte()
        }

        fun bytes(bytes: ByteArray) {
            ensure(bytes.size)
            bytes.copyInto(data, size)
            size += bytes.size
        }

        fun u16(value: Int) = bytes(order(2).putShort(value.toShort()).array())
        fun u32(value: Int) = bytes(order(4).putInt(value).array())
        fun put(at: Int, bytes: ByteArray) = bytes.copyInto(data, at)
        fun putU16(at: Int, value: Int) = put(at, order(2).putShort(value.toShort()).array())
        fun putU32(at: Int, value: Int) = put(at, order(4).putInt(value).array())
        fun align() = if (size % 2 == 1) byte(0) else Unit
        fun toByteArray(): ByteArray = data.copyOf(size)

        private fun order(capacity: Int) =
            ByteBuffer.allocate(capacity).order(if (bigEndian) ByteOrder.BIG_ENDIAN else ByteOrder.LITTLE_ENDIAN)

        private fun ensure(extra: Int) {
            if (size + extra > data.size) data = data.copyOf(maxOf(data.size * 2, size + extra))
        }
    }

    companion object {
        /** Builds a TIFF with [configure] applied to a fresh IFD0. */
        fun tiff(bigEndian: Boolean = true, magic: Int = 42, configure: Ifd.() -> Unit): ByteArray =
            TiffBuilder(bigEndian, magic).build(Ifd().apply(configure))

        fun ifd(configure: Ifd.() -> Unit): Ifd = Ifd().apply(configure)
    }
}
