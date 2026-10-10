package io.github.fishpimp.exiflab.metadata.write.tiff

/** TIFF field types and the tag ids the write engine treats as structure rather than values. */
internal object Tiff {
    const val BYTE = 1
    const val ASCII = 2
    const val SHORT = 3
    const val LONG = 4
    const val RATIONAL = 5
    const val SBYTE = 6
    const val UNDEFINED = 7
    const val SSHORT = 8
    const val SLONG = 9
    const val SRATIONAL = 10
    const val FLOAT = 11
    const val DOUBLE = 12
    const val IFD = 13

    /** Bytes per value of [type], or 0 for types classic TIFF does not define. */
    fun typeSize(type: Int): Int = when (type) {
        BYTE, ASCII, SBYTE, UNDEFINED -> 1
        SHORT, SSHORT -> 2
        LONG, SLONG, FLOAT, IFD -> 4
        RATIONAL, SRATIONAL, DOUBLE -> 8
        else -> 0
    }

    const val TAG_MAKE = 0x010F
    const val TAG_EXIF_IFD = 0x8769
    const val TAG_GPS_IFD = 0x8825
    const val TAG_INTEROP_IFD = 0xA005
    const val TAG_SUB_IFDS = 0x014A
    const val TAG_MAKER_NOTE = 0x927C
    const val TAG_THUMBNAIL_OFFSET = 0x0201
    const val TAG_THUMBNAIL_LENGTH = 0x0202
    const val TAG_STRIP_OFFSETS = 0x0111
    const val TAG_STRIP_BYTE_COUNTS = 0x0117
    const val TAG_TILE_OFFSETS = 0x0144
    const val TAG_TILE_BYTE_COUNTS = 0x0145

    /** Tags that point at other IFDs. */
    val IFD_POINTER_TAGS = setOf(TAG_EXIF_IFD, TAG_GPS_IFD, TAG_INTEROP_IFD, TAG_SUB_IFDS)

    /** Offset tags of data blocks, mapped to the tag holding each block's byte count. */
    val DATA_BLOCK_TAGS = mapOf(
        TAG_THUMBNAIL_OFFSET to TAG_THUMBNAIL_LENGTH,
        TAG_STRIP_OFFSETS to TAG_STRIP_BYTE_COUNTS,
        TAG_TILE_OFFSETS to TAG_TILE_BYTE_COUNTS,
    )

    /** Tags whose value is laid out by the writer and therefore cannot be set directly. */
    val STRUCTURAL_TAGS: Set<Int> = IFD_POINTER_TAGS + DATA_BLOCK_TAGS.keys + DATA_BLOCK_TAGS.values + TAG_MAKER_NOTE
}

/** One image file directory: its entries in file order plus the IFD chained after it. */
internal class TiffIfd {
    val entries = mutableListOf<TiffEntry>()

    /** The next IFD in the chain (IFD1 after IFD0). Only followed for the IFD0 chain. */
    var next: TiffIfd? = null

    val isEmpty: Boolean get() = entries.isEmpty()

    operator fun get(tag: Int): TiffEntry? = entries.firstOrNull { it.covers(tag) }

    fun remove(tag: Int): Boolean = entries.removeAll { it.covers(tag) }

    /** Adds [entry], replacing every entry with the same tag (keeping the first one's position). */
    fun put(entry: TiffEntry) {
        val index = entries.indexOfFirst { it.covers(entry.tag) }
        entries.removeAll { it.covers(entry.tag) }
        if (index >= 0) entries.add(minOf(index, entries.size), entry) else entries += entry
    }

    /** The first IFD [tag] points at, if any. */
    fun child(tag: Int): TiffIfd? = (get(tag) as? TiffEntry.SubIfds)?.children?.firstOrNull()
}

internal sealed class TiffEntry(val tag: Int) {
    open fun covers(tag: Int): Boolean = tag == this.tag

    /** A value kept byte-exact: [bytes] are in the block's byte order and hold count values of [type]. */
    class Value(tag: Int, val type: Int, val count: Long, val bytes: ByteArray) : TiffEntry(tag)

    /** An entry of a type classic TIFF does not define; its 4-byte value field is kept verbatim. */
    class Opaque(tag: Int, val type: Int, val count: Long, val field: ByteArray) : TiffEntry(tag)

    /** A pointer to one or more IFDs (Exif, GPS, Interoperability, SubIFDs, any IFD-typed entry). */
    class SubIfds(tag: Int, val type: Int, val children: MutableList<TiffIfd>) : TiffEntry(tag)

    /**
     * Data blocks referenced by offset plus byte count (the IFD1 JPEG thumbnail, strips, tiles).
     * Covers both the offset tag ([tag]) and [lengthTag]; the writer relocates the blocks.
     */
    class DataBlocks(tag: Int, val lengthTag: Int, val lengthType: Int, val blocks: List<ByteArray>) : TiffEntry(tag) {
        override fun covers(tag: Int): Boolean = tag == this.tag || tag == lengthTag
    }

    /**
     * The MakerNote, with the offset it had in the source block. Vendors such as Canon store
     * offsets relative to the TIFF header inside it, so the serializer keeps it at [sourceOffset]
     * when it can; [layout] says how to move it safely otherwise.
     */
    class MakerNote(
        tag: Int,
        val type: Int,
        val count: Long,
        val bytes: ByteArray,
        val sourceOffset: Long,
        val layout: MakerNoteLayout,
        /** Data outside the MakerNote that its TIFF-relative offsets point at, kept where it was. */
        val external: List<PinnedRange>,
    ) : TiffEntry(tag)
}

/** Bytes that must stay at [offset] in the serialized block. */
internal class PinnedRange(val offset: Long, val bytes: ByteArray) {
    val end: Long get() = offset + bytes.size
}

/**
 * A parsed EXIF/TIFF block. [orphans] are the source bytes no parsed structure refers to; they
 * are kept in place next to a MakerNote that stays in place, since MakerNotes may refer to them.
 */
internal class TiffDocument(val bigEndian: Boolean, val ifd0: TiffIfd, val orphans: List<PinnedRange> = emptyList()) {
    val exif: TiffIfd? get() = ifd0.child(Tiff.TAG_EXIF_IFD)
    val gps: TiffIfd? get() = ifd0.child(Tiff.TAG_GPS_IFD)
    val interop: TiffIfd? get() = exif?.child(Tiff.TAG_INTEROP_IFD)
    val thumbnail: TiffIfd? get() = ifd0.next

    val makerNote: TiffEntry.MakerNote? get() = exif?.get(Tiff.TAG_MAKER_NOTE) as? TiffEntry.MakerNote
}

/** Reads and writes integers in a TIFF block's byte order. */
internal class ByteOrder(val bigEndian: Boolean) {
    fun u16(bytes: ByteArray, at: Int): Int {
        val a = bytes[at].toInt() and 0xFF
        val b = bytes[at + 1].toInt() and 0xFF
        return if (bigEndian) (a shl 8) or b else (b shl 8) or a
    }

    fun u32(bytes: ByteArray, at: Int): Long {
        val hi = u16(bytes, if (bigEndian) at else at + 2).toLong()
        val lo = u16(bytes, if (bigEndian) at + 2 else at).toLong()
        return (hi shl 16) or lo
    }

    fun put16(bytes: ByteArray, at: Int, value: Int) {
        if (bigEndian) {
            bytes[at] = (value ushr 8).toByte()
            bytes[at + 1] = value.toByte()
        } else {
            bytes[at] = value.toByte()
            bytes[at + 1] = (value ushr 8).toByte()
        }
    }

    fun put32(bytes: ByteArray, at: Int, value: Long) {
        if (bigEndian) {
            put16(bytes, at, (value ushr 16).toInt() and 0xFFFF)
            put16(bytes, at + 2, value.toInt() and 0xFFFF)
        } else {
            put16(bytes, at, value.toInt() and 0xFFFF)
            put16(bytes, at + 2, (value ushr 16).toInt() and 0xFFFF)
        }
    }
}
