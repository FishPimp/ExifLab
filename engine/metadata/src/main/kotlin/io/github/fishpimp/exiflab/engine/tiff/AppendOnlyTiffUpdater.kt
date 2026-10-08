package io.github.fishpimp.exiflab.engine.tiff

import io.github.fishpimp.exiflab.engine.plan.TiffEdits
import java.nio.ByteOrder

internal data class TiffUpdateResult(
    /** Bytes of superseded metadata overwritten with zeros. */
    val zeroedBytes: Long,
    val notes: List<String> = emptyList(),
)

/**
 * Applies [TiffEdits] without moving any existing byte that is still referenced (see docs/spike-format-matrix.md).
 * Changed IFD tables are rewritten in place when the new table fits, otherwise appended and re-linked.
 * Superseded values/tables are zeroed. MakerNote, strips, tiles, sub-IFDs and unknown data never move.
 */
internal object AppendOnlyTiffUpdater {
    fun apply(buffer: TiffBuffer, base: Long, edits: TiffEdits): TiffUpdateResult = TODO("AppendOnlyTiffUpdater.apply")

    /** A minimal valid TIFF block (header + empty IFD0) for files that have no EXIF yet. */
    fun emptyTiff(order: ByteOrder = ByteOrder.BIG_ENDIAN): ByteArray = TODO("AppendOnlyTiffUpdater.emptyTiff")
}
