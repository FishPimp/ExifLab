package io.github.fishpimp.exiflab.metadata.container

import com.drew.imaging.jpeg.JpegMetadataReader
import com.drew.imaging.jpeg.JpegSegmentData
import com.drew.imaging.jpeg.JpegSegmentMetadataReader
import com.drew.metadata.Metadata
import io.github.fishpimp.exiflab.metadata.io.SeekableSource
import io.github.fishpimp.exiflab.metadata.io.SourceCursor
import java.io.EOFException

/**
 * Reads the marker segments of a JPEG stream (up to the first scan) and hands them to
 * metadata-extractor's segment readers. Unlike the library's reader it keeps every complete
 * segment when the data is truncated, so damaged files still show what they contain.
 */
internal object JpegSegments {
    private val wantedTypes: Set<Byte> =
        JpegMetadataReader.ALL_READERS.flatMap { reader -> reader.segmentTypes.map { it.byteValue } }.toSet()

    /**
     * Parses the JPEG stream starting at [start] (an SOI marker) into [metadata].
     * [readers] defaults to every metadata-extractor segment reader. Returns false when there is
     * no SOI marker at [start].
     */
    fun read(
        source: SeekableSource,
        start: Long,
        metadata: Metadata,
        warnings: MutableList<String>,
        readers: Iterable<JpegSegmentMetadataReader> = JpegMetadataReader.ALL_READERS,
    ): Boolean {
        val cursor = SourceCursor(source, start)
        try {
            if (cursor.u16() != SOI) return false
        } catch (_: EOFException) {
            return false
        }
        val segments = JpegSegmentData()
        try {
            collect(cursor, segments, warnings)
        } catch (_: EOFException) {
            warnings += "JPEG data ends before the image data; the file may be truncated"
        }
        JpegMetadataReader.processJpegSegmentData(metadata, readers, segments)
        return true
    }

    private fun collect(cursor: SourceCursor, segments: JpegSegmentData, warnings: MutableList<String>) {
        var scanned = 0L
        while (true) {
            var marker = cursor.u8()
            // Markers may be preceded by fill bytes; tolerate a little garbage between segments too.
            while (marker != 0xFF) {
                if (++scanned > MAX_GARBAGE_BYTES) {
                    warnings += "JPEG marker structure is damaged; stopped reading segments"
                    return
                }
                marker = cursor.u8()
            }
            var type = cursor.u8()
            while (type == 0xFF) type = cursor.u8()
            when {
                type == SOS || type == EOI -> return
                type == 0x00 || type == TEM || type in RST0..RST7 -> continue
            }
            val length = cursor.u16() - 2
            if (length < 0) {
                warnings += "JPEG segment 0x%02X has an invalid length; stopped reading segments".format(type)
                return
            }
            if (type.toByte() in wantedTypes) {
                segments.addSegment(type.toByte(), cursor.bytes(length))
            } else {
                cursor.skip(length.toLong())
            }
        }
    }

    private const val SOI = 0xFFD8
    private const val SOS = 0xDA
    private const val EOI = 0xD9
    private const val TEM = 0x01
    private const val RST0 = 0xD0
    private const val RST7 = 0xD7
    private const val MAX_GARBAGE_BYTES = 64 * 1024
}
