package io.github.fishpimp.exiflab.metadata.io

import io.github.fishpimp.exiflab.metadata.ImageSource
import java.io.BufferedInputStream
import java.io.Closeable
import java.io.EOFException
import java.io.InputStream

/**
 * Random access over an [ImageSource], which only offers sequential streams. Moving forward
 * skips (a seek for file-backed streams), moving backward reopens the source. Nothing beyond one
 * stream buffer is held, so large RAW files are never loaded whole. Not thread-safe.
 */
internal class SeekableSource(private val source: ImageSource) : Closeable {
    private var stream: InputStream? = null
    private var streamPosition = 0L

    /** Total size in bytes. Measured by reading through the source once when [ImageSource.length] is unknown. */
    val length: Long by lazy { source.length?.takeIf { it >= 0 } ?: measureLength() }

    /**
     * Reads up to [count] bytes at [position] into [buffer]. Returns the number of bytes read,
     * which is less than [count] only at the end of the data.
     */
    fun read(position: Long, buffer: ByteArray, offset: Int = 0, count: Int = buffer.size - offset): Int {
        require(position >= 0 && count >= 0) { "Invalid read at $position of $count bytes" }
        if (count == 0) return 0
        return accessing {
            val input = streamAt(position) ?: return@accessing 0
            var total = 0
            while (total < count) {
                val read = input.read(buffer, offset + total, count - total)
                if (read < 0) break
                total += read
                streamPosition += read
            }
            total
        }
    }

    /** Reads exactly [count] bytes at [position], or throws [EOFException]. */
    fun readFully(position: Long, count: Int): ByteArray {
        val bytes = ByteArray(count)
        val read = read(position, bytes)
        if (read != count) throw EOFException("Wanted $count bytes at offset $position, data ends after $read")
        return bytes
    }

    /** Reads up to [count] bytes at [position]; the result is shorter only at the end of the data. */
    fun readUpTo(position: Long, count: Int): ByteArray {
        val bytes = ByteArray(count)
        val read = read(position, bytes)
        return if (read == count) bytes else bytes.copyOf(read)
    }

    override fun close() {
        stream?.close()
        stream = null
    }

    /** Returns the stream positioned at [position], or null when the data ends before it. */
    private fun streamAt(position: Long): InputStream? {
        var input = stream
        if (input == null || position < streamPosition) {
            input?.close()
            input = BufferedInputStream(source.open(), STREAM_BUFFER)
            stream = input
            streamPosition = 0
        }
        var remaining = position - streamPosition
        while (remaining > 0) {
            val skipped = input.skip(remaining)
            if (skipped > 0) {
                remaining -= skipped
                streamPosition += skipped
            } else {
                // skip() may legally make no progress; a single read either advances or reveals the end.
                if (input.read() < 0) return null
                remaining--
                streamPosition++
            }
        }
        return input
    }

    private fun measureLength(): Long = accessing {
        source.open().use { input ->
            val buffer = ByteArray(STREAM_BUFFER)
            var total = 0L
            while (true) {
                val read = input.read(buffer)
                if (read < 0) break
                total += read
            }
            total
        }
    }

    /** Runs stream work, reporting failures of the source itself as [SourceAccessException]. */
    private inline fun <T> accessing(block: () -> T): T = try {
        block()
    } catch (e: SourceAccessException) {
        throw e
    } catch (e: Exception) {
        // Includes SecurityException from content providers whose grant was revoked.
        runCatching { stream?.close() }
        stream = null
        throw SourceAccessException(e)
    }

    private companion object {
        const val STREAM_BUFFER = 16 * 1024
    }
}
