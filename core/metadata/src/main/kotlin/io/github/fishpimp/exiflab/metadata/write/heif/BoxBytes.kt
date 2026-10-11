package io.github.fishpimp.exiflab.metadata.write.heif

import io.github.fishpimp.exiflab.metadata.write.UnsupportedEditException
import java.io.ByteArrayOutputStream

/** A box inside an in-memory buffer (the `meta` or `moov` box of a HEIF file). */
internal class MemBox(val type: String, val start: Int, val headerSize: Int, val end: Int) {
    val payloadStart: Int get() = start + headerSize
    val size: Int get() = end - start
}

/** Strict ISO-BMFF parsing and building over byte arrays, for the boxes the HEIF writer rewrites. */
internal object BoxBytes {
    /**
     * The boxes that exactly tile `data[start, end)`. Anything that does not (a size running past
     * [end], a short header, bytes left over) means the structure cannot be rewritten safely.
     */
    fun children(data: ByteArray, start: Int, end: Int, where: String): List<MemBox> {
        val result = mutableListOf<MemBox>()
        var position = start
        while (position < end) {
            if (end - position < 8) damaged(where)
            val declared = u32(data, position)
            val type = fourCc(data, position + 4)
            var header = 8
            val size = when (declared) {
                0L -> (end - position).toLong()
                1L -> {
                    if (end - position < 16) damaged(where)
                    header = 16
                    u64(data, position + 8)
                }
                else -> declared
            }
            if (type == "uuid") header += 16
            if (size < header || size > end - position) damaged(where)
            result += MemBox(type, position, header, position + size.toInt())
            position += size.toInt()
            if (result.size > MAX_CHILDREN) damaged(where)
        }
        return result
    }

    fun damaged(where: String): Nothing = throw UnsupportedEditException("The HEIF $where box is damaged or unusual and cannot be rewritten safely")

    fun u8(data: ByteArray, at: Int): Int = data[at].toInt() and 0xFF

    fun u16(data: ByteArray, at: Int): Int = (u8(data, at) shl 8) or u8(data, at + 1)

    fun u32(data: ByteArray, at: Int): Long = (u16(data, at).toLong() shl 16) or u16(data, at + 2).toLong()

    fun u64(data: ByteArray, at: Int): Long = (u32(data, at) shl 32) or u32(data, at + 4)

    fun fourCc(data: ByteArray, at: Int): String = String(data, at, 4, Charsets.ISO_8859_1)

    /** A box with a 32-bit size, or a 64-bit `largesize` when it does not fit. */
    fun box(type: String, payload: ByteArray): ByteArray {
        val writer = ByteWriter()
        val size = 8L + payload.size
        if (size <= 0xFFFFFFFFL) {
            writer.u32(size)
            writer.fourCc(type)
        } else {
            writer.u32(1)
            writer.fourCc(type)
            writer.u64(size + 8)
        }
        writer.bytes(payload)
        return writer.toByteArray()
    }

    fun fullBox(type: String, version: Int, flags: Int, body: ByteArray): ByteArray {
        val writer = ByteWriter()
        writer.u8(version)
        writer.u24(flags)
        writer.bytes(body)
        return box(type, writer.toByteArray())
    }

    private const val MAX_CHILDREN = 1_000_000
}

/** Big-endian writer for box payloads. */
internal class ByteWriter {
    private val out = ByteArrayOutputStream()

    val size: Int get() = out.size()

    fun u8(value: Int) = out.write(value and 0xFF)

    fun u16(value: Int) {
        u8(value shr 8)
        u8(value)
    }

    fun u24(value: Int) {
        u8(value shr 16)
        u16(value)
    }

    fun u32(value: Long) {
        u16((value shr 16).toInt())
        u16(value.toInt())
    }

    fun u64(value: Long) {
        u32(value ushr 32)
        u32(value and 0xFFFFFFFFL)
    }

    /** An unsigned value in [size] bytes (0, 4 or 8, as `iloc` fields are). */
    fun uint(value: Long, size: Int) {
        when (size) {
            0 -> check(value == 0L) { "A value of $value needs a field" }
            2 -> u16(value.toInt())
            4 -> u32(value)
            8 -> u64(value)
            else -> error("Unsupported field size $size")
        }
    }

    fun fourCc(text: String) = bytes(text.toByteArray(Charsets.ISO_8859_1).also { check(it.size == 4) })

    fun cString(text: String) {
        bytes(text.toByteArray(Charsets.UTF_8))
        u8(0)
    }

    fun bytes(data: ByteArray) = out.write(data)

    fun toByteArray(): ByteArray = out.toByteArray()
}

/** Sequential big-endian reader over part of a buffer; running past [end] means the box is damaged. */
internal class ByteReader(private val data: ByteArray, var position: Int, private val end: Int, private val where: String) {
    val remaining: Int get() = end - position

    private fun need(count: Int): Int {
        if (count < 0 || count > end - position) BoxBytes.damaged(where)
        return position.also { position += count }
    }

    fun u8(): Int = BoxBytes.u8(data, need(1))

    fun u16(): Int = BoxBytes.u16(data, need(2))

    fun u32(): Long = BoxBytes.u32(data, need(4))

    fun u64(): Long = BoxBytes.u64(data, need(8)).also { if (it < 0) BoxBytes.damaged(where) }

    fun uint(size: Int): Long = when (size) {
        0 -> 0L
        2 -> u16().toLong()
        4 -> u32()
        8 -> u64()
        else -> BoxBytes.damaged(where)
    }

    fun fourCc(): String = BoxBytes.fourCc(data, need(4))

    /** A NUL-terminated UTF-8 string; a missing terminator at the end of the box is tolerated. */
    fun cString(): String {
        var stop = position
        while (stop < end && data[stop].toInt() != 0) stop++
        val text = String(data, position, stop - position, Charsets.UTF_8)
        position = if (stop < end) stop + 1 else end
        return text
    }
}
