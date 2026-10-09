package io.github.fishpimp.exiflab.metadata.preview

import io.github.fishpimp.exiflab.metadata.io.SeekableSource
import io.github.fishpimp.exiflab.metadata.io.SourceCursor
import java.io.EOFException

/** Pixel size from a JPEG frame header. */
internal data class JpegFrame(val width: Int, val height: Int) {
    val area: Long get() = width.toLong() * height

    companion object {
        /**
         * The frame size of the JPEG stored at [offset], or null unless it starts with an SOI marker
         * and is a baseline, extended or progressive Huffman JPEG. Lossless JPEG (the compressed raw
         * data in CR2 and DNG files) and arithmetic coding are rejected: they are not displayable previews.
         */
        fun read(source: SeekableSource, offset: Long, length: Long): JpegFrame? {
            val end = offset + length
            val cursor = SourceCursor(source, offset)
            try {
                if (cursor.u16() != SOI) return null
                while (cursor.position + 4 <= end) {
                    if (cursor.u8() != 0xFF) return null
                    var marker = cursor.u8()
                    while (marker == 0xFF) marker = cursor.u8()
                    when {
                        marker == EOI || marker == SOS -> return null
                        marker == TEM || marker in RST_MARKERS -> continue
                    }
                    val segmentLength = cursor.u16()
                    if (segmentLength < 2) return null
                    if (marker in SOF_MARKERS) {
                        if (marker !in DISPLAYABLE_SOF) return null
                        cursor.u8() // sample precision
                        val height = cursor.u16()
                        val width = cursor.u16()
                        return if (width > 0 && height > 0) JpegFrame(width, height) else null
                    }
                    cursor.skip(segmentLength - 2L)
                }
            } catch (_: EOFException) {
                // Truncated candidate.
            }
            return null
        }

        private const val SOI = 0xFFD8
        private const val EOI = 0xD9
        private const val SOS = 0xDA
        private const val TEM = 0x01
        private val RST_MARKERS = 0xD0..0xD7
        private val SOF_MARKERS = (0xC0..0xCF) - setOf(0xC4, 0xC8, 0xCC)
        private val DISPLAYABLE_SOF = setOf(0xC0, 0xC1, 0xC2)
    }
}
