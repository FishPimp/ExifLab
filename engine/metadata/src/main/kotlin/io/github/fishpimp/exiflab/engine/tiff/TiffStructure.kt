package io.github.fishpimp.exiflab.engine.tiff

import io.github.fishpimp.exiflab.model.ExifIfd
import java.nio.ByteOrder

/** One IFD entry as stored. [valuePosition] is the absolute buffer position of the value bytes. */
internal data class TiffEntry(
    val tag: Int,
    val type: Int,
    val count: Long,
    /** Absolute position of the 12-byte entry in the buffer. */
    val entryPosition: Long,
    /** Absolute position of the value bytes (inside the entry when [inline]). */
    val valuePosition: Long,
    val inline: Boolean,
) {
    val byteLength: Long get() = count * io.github.fishpimp.exiflab.engine.plan.TiffType.size(type)
}

internal data class TiffIfd(
    /** Absolute position of the IFD (its entry count). */
    val position: Long,
    val entries: List<TiffEntry>,
    /** Raw next-IFD offset (relative to the TIFF header), 0 if none. */
    val nextOffset: Long,
) {
    val tableLength: Long get() = 2L + entries.size * 12L + 4L
    fun entry(tag: Int): TiffEntry? = entries.firstOrNull { it.tag == tag }
}

/** Absolute byte range in the buffer. */
internal data class ByteRange(val position: Long, val length: Long) {
    val end: Long get() = position + length
}

/** Parsed TIFF structure (read-only view; the updater re-parses after changes). */
internal data class TiffStructure(
    /** Absolute position of the TIFF header ("II*\0"/"MM\0*") in the buffer. */
    val base: Long,
    val order: ByteOrder,
    /** 42 for TIFF; 0x55 (RW2), 0x4F52/0x5352 (ORF) etc. are reported as-is. */
    val magic: Int,
    val ifds: Map<ExifIfd, TiffIfd>,
    /** Location of the MakerNote value (pinned: never moved). */
    val makerNote: ByteRange?,
    /** Location of the IFD1 JPEG thumbnail data. */
    val thumbnail: ByteRange?,
    /** Other IFDs reachable from IFD0 (SubIFDs, chained IFDs) - never modified, listed for diagnostics. */
    val otherIfdPositions: List<Long>,
    val warnings: List<String>,
)
