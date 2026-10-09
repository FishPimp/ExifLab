package io.github.fishpimp.exiflab.metadata.io

import com.drew.lang.BufferBoundsException
import com.drew.lang.RandomAccessReader

/**
 * metadata-extractor [RandomAccessReader] backed by a [SeekableSource] with a small LRU cache of
 * fixed-size chunks. Unlike the library's stream reader it never buffers everything up to the
 * furthest offset, so TIFF-based RAW files with IFDs near the end stay cheap to parse.
 */
internal class SourceRandomAccessReader(
    private val source: SeekableSource,
    private val chunkSize: Int = DEFAULT_CHUNK_SIZE,
    private val maxChunks: Int = DEFAULT_MAX_CHUNKS,
) : RandomAccessReader() {
    // RandomAccessReader addresses bytes with Int; anything past 2 GiB is out of reach for TIFF offsets anyway.
    private val length: Int = source.length.coerceAtMost(Int.MAX_VALUE.toLong()).toInt()

    private val chunks = object : LinkedHashMap<Int, ByteArray>(maxChunks, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<Int, ByteArray>?) = size > maxChunks
    }

    override fun toUnshiftedOffset(localOffset: Int): Int = localOffset

    override fun getLength(): Long = length.toLong()

    override fun validateIndex(index: Int, bytesRequested: Int) {
        if (!isValidIndex(index, bytesRequested)) throw BufferBoundsException(index, bytesRequested, length.toLong())
    }

    override fun isValidIndex(index: Int, bytesRequested: Int): Boolean =
        index >= 0 && bytesRequested >= 0 && index.toLong() + bytesRequested <= length

    override fun getByte(index: Int): Byte {
        validateIndex(index, 1)
        return chunk(index / chunkSize)[index % chunkSize]
    }

    override fun getBytes(index: Int, count: Int): ByteArray {
        validateIndex(index, count)
        val result = ByteArray(count)
        var copied = 0
        while (copied < count) {
            val position = index + copied
            val chunk = chunk(position / chunkSize)
            val inChunk = position % chunkSize
            val n = minOf(count - copied, chunk.size - inChunk)
            System.arraycopy(chunk, inChunk, result, copied, n)
            copied += n
        }
        return result
    }

    private fun chunk(chunkIndex: Int): ByteArray = chunks[chunkIndex] ?: run {
        val start = chunkIndex.toLong() * chunkSize
        val size = minOf(chunkSize.toLong(), length - start).toInt()
        val bytes = ByteArray(size)
        val read = source.read(start, bytes)
        if (read != size) throw BufferBoundsException("Data ends at offset ${start + read}, expected $length bytes")
        bytes.also { chunks[chunkIndex] = it }
    }

    private companion object {
        const val DEFAULT_CHUNK_SIZE = 64 * 1024
        const val DEFAULT_MAX_CHUNKS = 64
    }
}
