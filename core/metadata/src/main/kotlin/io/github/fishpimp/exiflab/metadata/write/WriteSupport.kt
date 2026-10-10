package io.github.fishpimp.exiflab.metadata.write

import io.github.fishpimp.exiflab.metadata.CorruptImageException
import io.github.fishpimp.exiflab.metadata.io.SeekableSource
import java.io.EOFException
import java.io.OutputStream

/** Passes bytes to [target] while feeding [sink] and counting them. */
internal class TeeOutputStream(private val target: OutputStream, private val sink: OutputStream) : OutputStream() {
    var count = 0L
        private set

    override fun write(b: Int) {
        target.write(b)
        sink.write(b)
        count++
    }

    override fun write(b: ByteArray, off: Int, len: Int) {
        target.write(b, off, len)
        sink.write(b, off, len)
        count += len
    }

    override fun flush() = target.flush()
}

/** Copies [length] bytes at [position] of [source] to [out]; a source that ends early is corrupt. */
internal fun copyRange(source: SeekableSource, position: Long, length: Long, out: OutputStream, buffer: ByteArray = ByteArray(COPY_BUFFER)) {
    var done = 0L
    while (done < length) {
        val want = minOf(buffer.size.toLong(), length - done).toInt()
        val read = source.read(position + done, buffer, 0, want)
        if (read <= 0) throw CorruptImageException("The file ends early; it may be truncated")
        out.write(buffer, 0, read)
        done += read
    }
}

/** Copies everything from [position] to the end of [source]; returns the number of bytes copied. */
internal inline fun copyToEnd(source: SeekableSource, position: Long, out: OutputStream, onChunk: (ByteArray, Int) -> Unit = { _, _ -> }): Long {
    val buffer = ByteArray(COPY_BUFFER)
    var done = 0L
    while (true) {
        val read = source.read(position + done, buffer)
        if (read <= 0) return done
        onChunk(buffer, read)
        out.write(buffer, 0, read)
        done += read
    }
}

/** Reads exactly [count] bytes at [position], reporting a short file as corrupt. */
internal fun SeekableSource.bytesAt(position: Long, count: Int): ByteArray = try {
    readFully(position, count)
} catch (_: EOFException) {
    throw CorruptImageException("The file ends early; it may be truncated")
}

internal fun String.latin1(): ByteArray = toByteArray(Charsets.ISO_8859_1)

internal fun ByteArray.startsWith(prefix: ByteArray): Boolean =
    size >= prefix.size && prefix.indices.all { this[it] == prefix[it] }

internal const val COPY_BUFFER = 64 * 1024
