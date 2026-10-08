package io.github.fishpimp.exiflab.engine.io

import java.io.Closeable
import java.io.EOFException
import java.io.File
import java.io.InputStream
import java.io.RandomAccessFile
import java.nio.ByteBuffer
import java.nio.channels.FileChannel

/**
 * Random-access, read-only view of an image. Implementations must be safe for sequential use from one thread.
 * The engine never writes through a source; output always goes to a separate file.
 */
public interface SeekableSource : Closeable {
    public val size: Long

    /** Reads up to [length] bytes at [position]. Returns the number read, or -1 at end of data. */
    public fun read(position: Long, buffer: ByteArray, offset: Int = 0, length: Int = buffer.size): Int

    /** Reads exactly [length] bytes or throws [EOFException]. */
    public fun readFully(position: Long, buffer: ByteArray, offset: Int = 0, length: Int = buffer.size) {
        var done = 0
        while (done < length) {
            val n = read(position + done, buffer, offset + done, length - done)
            if (n <= 0) throw EOFException("Unexpected end of data at ${position + done} (size $size)")
            done += n
        }
    }

    public fun readBytes(position: Long, length: Int): ByteArray = ByteArray(length).also { readFully(position, it) }

    /** A fresh stream starting at [position]. Closing it does not close the source. */
    public fun openStream(position: Long = 0): InputStream = SourceInputStream(this, position)
}

public class FileSource(file: File) : SeekableSource {
    private val raf = RandomAccessFile(file, "r")
    override val size: Long = raf.length()

    override fun read(position: Long, buffer: ByteArray, offset: Int, length: Int): Int {
        if (position >= size) return -1
        raf.seek(position)
        return raf.read(buffer, offset, length)
    }

    override fun close(): Unit = raf.close()
}

/** Wraps a [FileChannel] (e.g. from an Android ParcelFileDescriptor). The channel is closed with the source. */
public class ChannelSource(private val channel: FileChannel, private val onClose: Closeable? = null) : SeekableSource {
    override val size: Long = channel.size()

    override fun read(position: Long, buffer: ByteArray, offset: Int, length: Int): Int {
        if (position >= size) return -1
        return channel.read(ByteBuffer.wrap(buffer, offset, length), position)
    }

    override fun close() {
        channel.close()
        onClose?.close()
    }
}

public class ByteArraySource(private val bytes: ByteArray) : SeekableSource {
    override val size: Long get() = bytes.size.toLong()

    override fun read(position: Long, buffer: ByteArray, offset: Int, length: Int): Int {
        if (position >= bytes.size) return -1
        val n = minOf(length.toLong(), bytes.size - position).toInt()
        System.arraycopy(bytes, position.toInt(), buffer, offset, n)
        return n
    }

    override fun close() {}
}

private class SourceInputStream(private val source: SeekableSource, private var position: Long) : InputStream() {
    private var mark = position
    private val one = ByteArray(1)

    override fun read(): Int = if (read(one, 0, 1) <= 0) -1 else one[0].toInt() and 0xFF

    override fun read(b: ByteArray, off: Int, len: Int): Int {
        if (len == 0) return 0
        val n = source.read(position, b, off, len)
        if (n > 0) position += n
        return n
    }

    override fun skip(n: Long): Long {
        val skipped = minOf(n, source.size - position).coerceAtLeast(0)
        position += skipped
        return skipped
    }

    override fun available(): Int = minOf(Int.MAX_VALUE.toLong(), (source.size - position).coerceAtLeast(0)).toInt()
    override fun markSupported(): Boolean = true
    override fun mark(readlimit: Int) { mark = position }
    override fun reset() { position = mark }
}
