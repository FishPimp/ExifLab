package io.github.fishpimp.exiflab.engine.tiff

import java.io.RandomAccessFile

/**
 * Mutable random-access storage that the TIFF updater works on: either an in-memory TIFF block (JPEG APP1,
 * PNG eXIf, WebP EXIF, HEIF Exif item) or a whole TIFF-based file on disk (DNG, TIFF).
 * Positions are absolute within the buffer; TIFF offsets are relative to the TIFF header at `base`.
 */
internal interface TiffBuffer {
    val size: Long
    fun read(position: Long, length: Int): ByteArray
    fun write(position: Long, bytes: ByteArray)

    /** Appends [bytes] after padding the end to a multiple of [align]; returns the absolute position written. */
    fun append(bytes: ByteArray, align: Int = 2): Long
    fun zero(position: Long, length: Long)
}

internal class ByteArrayTiffBuffer(initial: ByteArray) : TiffBuffer {
    private var data: ByteArray = initial.copyOf()
    private var length: Int = initial.size

    override val size: Long get() = length.toLong()

    override fun read(position: Long, length: Int): ByteArray {
        require(position >= 0 && position + length <= this.length) { "read $position+$length outside 0..${this.length}" }
        return data.copyOfRange(position.toInt(), position.toInt() + length)
    }

    override fun write(position: Long, bytes: ByteArray) {
        require(position >= 0 && position + bytes.size <= length) { "write $position+${bytes.size} outside 0..$length" }
        System.arraycopy(bytes, 0, data, position.toInt(), bytes.size)
    }

    override fun append(bytes: ByteArray, align: Int): Long {
        while (length % align != 0) grow(byteArrayOf(0))
        val at = length.toLong()
        grow(bytes)
        return at
    }

    override fun zero(position: Long, length: Long) {
        require(position >= 0 && position + length <= this.length)
        data.fill(0, position.toInt(), (position + length).toInt())
    }

    fun toByteArray(): ByteArray = data.copyOf(length)

    private fun grow(bytes: ByteArray) {
        if (length + bytes.size > data.size) data = data.copyOf(maxOf(data.size * 2, length + bytes.size, 64))
        System.arraycopy(bytes, 0, data, length, bytes.size)
        length += bytes.size
    }
}

/** Works directly on a file (used for DNG/TIFF output copies, which can be hundreds of MB). */
internal class FileTiffBuffer(private val raf: RandomAccessFile) : TiffBuffer {
    override val size: Long get() = raf.length()

    override fun read(position: Long, length: Int): ByteArray {
        require(position >= 0 && position + length <= raf.length())
        raf.seek(position)
        return ByteArray(length).also { raf.readFully(it) }
    }

    override fun write(position: Long, bytes: ByteArray) {
        require(position >= 0 && position + bytes.size <= raf.length())
        raf.seek(position)
        raf.write(bytes)
    }

    override fun append(bytes: ByteArray, align: Int): Long {
        var end = raf.length()
        val pad = ((align - (end % align)) % align).toInt()
        raf.seek(end)
        if (pad > 0) raf.write(ByteArray(pad))
        end += pad
        raf.write(bytes)
        return end
    }

    override fun zero(position: Long, length: Long) {
        require(position >= 0 && position + length <= raf.length())
        raf.seek(position)
        var left = length
        val chunk = ByteArray(minOf(length, 64 * 1024L).toInt().coerceAtLeast(1))
        while (left > 0) {
            val n = minOf(left, chunk.size.toLong()).toInt()
            raf.write(chunk, 0, n)
            left -= n
        }
    }
}
