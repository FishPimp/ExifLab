package io.github.fishpimp.exiflab.metadata.io

/** Unsigned byte at [index]. */
internal fun ByteArray.u8(index: Int): Int = this[index].toInt() and 0xFF

/** Unsigned 16-bit integer at [index]. */
internal fun ByteArray.u16(index: Int, bigEndian: Boolean): Int =
    if (bigEndian) (u8(index) shl 8) or u8(index + 1) else u8(index) or (u8(index + 1) shl 8)

/** Unsigned 32-bit integer at [index]. */
internal fun ByteArray.u32(index: Int, bigEndian: Boolean): Long =
    if (bigEndian) {
        (u8(index).toLong() shl 24) or (u8(index + 1).toLong() shl 16) or (u8(index + 2).toLong() shl 8) or u8(index + 3).toLong()
    } else {
        u8(index).toLong() or (u8(index + 1).toLong() shl 8) or (u8(index + 2).toLong() shl 16) or (u8(index + 3).toLong() shl 24)
    }

/** Unsigned 64-bit integer at [index], big-endian; values above [Long.MAX_VALUE] wrap negative. */
internal fun ByteArray.u64be(index: Int): Long = (u32(index, true) shl 32) or u32(index + 4, true)

/** [length] bytes at [index] decoded as ISO-8859-1 (four-character codes, signatures). */
internal fun ByteArray.latin1(index: Int, length: Int): String = String(this, index, length, Charsets.ISO_8859_1)

/** Index of the first [value] byte from [from], or -1. */
internal fun ByteArray.indexOfByte(value: Int, from: Int = 0): Int {
    for (i in from until size) if (u8(i) == value) return i
    return -1
}
