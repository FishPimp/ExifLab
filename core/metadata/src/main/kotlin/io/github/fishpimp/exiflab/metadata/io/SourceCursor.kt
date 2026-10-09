package io.github.fishpimp.exiflab.metadata.io

import java.io.EOFException

/** Big-endian sequential reader over a [SeekableSource]; every read throws [EOFException] at the end of data. */
internal class SourceCursor(private val source: SeekableSource, var position: Long) {
    private val scratch = ByteArray(8)

    fun u8(): Int = fill(1).u8(0)

    fun u16(): Int = fill(2).u16(0, bigEndian = true)

    fun u32(): Long = fill(4).u32(0, bigEndian = true)

    fun bytes(count: Int): ByteArray = source.readFully(position, count).also { position += count }

    fun skip(count: Long) {
        position += count
    }

    private fun fill(count: Int): ByteArray {
        if (source.read(position, scratch, 0, count) != count) throw EOFException("Data ends at offset $position")
        position += count
        return scratch
    }
}
