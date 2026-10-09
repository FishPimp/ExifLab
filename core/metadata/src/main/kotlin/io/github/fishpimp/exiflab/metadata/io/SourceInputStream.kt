package io.github.fishpimp.exiflab.metadata.io

import java.io.InputStream

/** A sequential [InputStream] view of a [SeekableSource]; skipping costs no I/O until the next read. */
internal class SourceInputStream(private val source: SeekableSource, private var position: Long = 0) : InputStream() {
    private val single = ByteArray(1)

    override fun read(): Int = if (read(single, 0, 1) == 1) single[0].toInt() and 0xFF else -1

    override fun read(buffer: ByteArray, offset: Int, length: Int): Int {
        if (length == 0) return 0
        val read = source.read(position, buffer, offset, length)
        if (read <= 0) return -1
        position += read
        return read
    }

    override fun skip(n: Long): Long {
        if (n <= 0) return 0
        val skipped = minOf(n, (source.length - position).coerceAtLeast(0))
        position += skipped
        return skipped
    }
}
