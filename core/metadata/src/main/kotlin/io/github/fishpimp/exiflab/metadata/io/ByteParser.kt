package io.github.fishpimp.exiflab.metadata.io

import java.io.EOFException

/** Big-endian sequential reader over an in-memory buffer; throws [EOFException] past its end. */
internal class ByteParser(private val bytes: ByteArray, var position: Int = 0) {
    val remaining: Int get() = bytes.size - position

    fun u8(): Int = require(1).let { bytes.u8(it) }

    fun u16(): Int = require(2).let { bytes.u16(it, bigEndian = true) }

    fun u32(): Long = require(4).let { bytes.u32(it, bigEndian = true) }

    /** Unsigned big-endian integer of [size] bytes (0 to 8); 0 bytes reads as 0. */
    fun uint(size: Int): Long {
        val start = require(size)
        var value = 0L
        for (i in 0 until size) value = (value shl 8) or bytes.u8(start + i).toLong()
        return value
    }

    fun fourCc(): String = require(4).let { bytes.latin1(it, 4) }

    /** A NUL-terminated UTF-8 string; a missing terminator ends the string at the buffer end. */
    fun cString(): String {
        val end = bytes.indexOfByte(0, position).let { if (it < 0) bytes.size else it }
        val text = String(bytes, position, end - position, Charsets.UTF_8)
        position = minOf(end + 1, bytes.size)
        return text
    }

    private fun require(count: Int): Int {
        if (count < 0 || count > remaining) throw EOFException("Wanted $count bytes at $position of ${bytes.size}")
        return position.also { position += count }
    }
}
