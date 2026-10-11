package io.github.fishpimp.exiflab.metadata.write.heif

import io.github.fishpimp.exiflab.metadata.CorruptImageException
import io.github.fishpimp.exiflab.metadata.io.SeekableSource
import io.github.fishpimp.exiflab.metadata.write.UnsupportedEditException
import io.github.fishpimp.exiflab.metadata.write.bytesAt

/** A top-level box of the file. [toEnd] marks a box whose header declares size 0 (it runs to the end of the file). */
internal class TopBox(val type: String, val start: Long, val headerSize: Int, val end: Long, val toEnd: Boolean) {
    val payloadStart: Long get() = start + headerSize
    val size: Long get() = end - start

    fun contains(position: Long, length: Long): Boolean = position >= payloadStart && position + length <= end
}

/**
 * The layout of a HEIF/AVIF file as the writer and the digest see it: the top-level boxes (bytes
 * after the last well-formed box are an opaque tail), the parsed `meta` box and, for image
 * sequences and bursts, the `moov` box with its tracks.
 */
internal class HeifStructure(
    val length: Long,
    val boxes: List<TopBox>,
    val tailStart: Long,
    val metaBox: TopBox,
    val metaBytes: ByteArray,
    val movieBox: TopBox?,
    val movieBytes: ByteArray?,
) {
    /** A fresh parse of the `meta` box, so callers may edit it. */
    fun parseMeta(): HeifMeta = HeifMeta.parse(metaBytes)

    fun parseMovie(): Movie? = movieBytes?.let(Movie::parse)

    fun digestPlan(): HeifDigestPlan = HeifDigest.plan(parseMeta(), parseMovie(), length)

    companion object {
        private const val MAX_META = 16 * 1024 * 1024
        private const val MAX_MOVIE = 64 * 1024 * 1024
        private val FRAGMENT_BOXES = setOf("moof", "mfra", "sidx", "ssix")

        fun read(source: SeekableSource): HeifStructure {
            val length = source.length
            val boxes = mutableListOf<TopBox>()
            var position = 0L
            while (length - position >= 8) {
                val header = source.bytesAt(position, 8)
                val type = BoxBytes.fourCc(header, 4)
                if (type.any { it.code !in 0x20..0x7E }) break
                var headerSize = 8
                val size = when (val declared = BoxBytes.u32(header, 0)) {
                    0L -> length - position
                    1L -> {
                        if (length - position < 16) break
                        headerSize = 16
                        BoxBytes.u64(source.bytesAt(position + 8, 8), 0)
                    }
                    else -> declared
                }
                if (type == "uuid") headerSize += 16
                if (size < headerSize) break
                if (size > length - position) throw CorruptImageException("The HEIF box '$type' runs past the end of the file; it may be truncated")
                boxes += TopBox(type, position, headerSize, position + size, toEnd = BoxBytes.u32(header, 0) == 0L)
                position += size
            }
            if (boxes.firstOrNull()?.type != "ftyp") throw CorruptImageException("Not a HEIF file")
            boxes.firstOrNull { it.type in FRAGMENT_BOXES }?.let {
                throw UnsupportedEditException("Fragmented HEIF image sequences cannot be edited")
            }
            val metaBox = boxes.filter { it.type == "meta" }.let { metas ->
                when (metas.size) {
                    0 -> throw UnsupportedEditException("The HEIF file has no meta box")
                    1 -> metas.single()
                    else -> throw UnsupportedEditException("The HEIF file has several meta boxes and cannot be rewritten safely")
                }
            }
            if (metaBox.size > MAX_META) throw UnsupportedEditException("The HEIF meta box is too large to edit")
            val movieBox = boxes.filter { it.type == "moov" }.let { movies ->
                if (movies.size > 1) throw UnsupportedEditException("The HEIF file has several movie boxes and cannot be rewritten safely")
                movies.firstOrNull()
            }
            if (movieBox != null && movieBox.size > MAX_MOVIE) throw UnsupportedEditException("The HEIF sequence is too large to edit")
            return HeifStructure(
                length = length,
                boxes = boxes,
                tailStart = position,
                metaBox = metaBox,
                metaBytes = source.bytesAt(metaBox.start, metaBox.size.toInt()),
                movieBox = movieBox,
                movieBytes = movieBox?.let { source.bytesAt(it.start, it.size.toInt()) },
            )
        }
    }
}
