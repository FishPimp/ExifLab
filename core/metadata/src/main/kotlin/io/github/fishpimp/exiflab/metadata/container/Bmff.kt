package io.github.fishpimp.exiflab.metadata.container

import io.github.fishpimp.exiflab.metadata.io.SeekableSource
import io.github.fishpimp.exiflab.metadata.io.latin1
import io.github.fishpimp.exiflab.metadata.io.u32
import io.github.fishpimp.exiflab.metadata.io.u64be

/** One ISO base media file format box (HEIF, AVIF and CR3 are built from these). */
internal class BmffBox(
    val type: String,
    val start: Long,
    private val headerSize: Int,
    val end: Long,
    /** For `uuid` boxes, the extended type as a lowercase UUID string. */
    val userType: String?,
) {
    val payloadStart: Long get() = start + headerSize
    val payloadSize: Long get() = end - payloadStart
}

/** Minimal ISO-BMFF box walker over a [SeekableSource]. It reads headers only, never payloads. */
internal object Bmff {
    /**
     * Lists the boxes between [start] and [end]. A box whose declared size overruns [end] is
     * clamped to it (truncated files keep their readable prefix); a malformed header stops the walk.
     */
    fun boxes(source: SeekableSource, start: Long, end: Long): List<BmffBox> {
        val boxes = mutableListOf<BmffBox>()
        var position = start
        while (position + 8 <= end && boxes.size < MAX_BOXES) {
            val header = source.readUpTo(position, 8)
            if (header.size < 8) break
            val type = header.latin1(4, 4)
            var headerSize = 8
            val size = when (val declared = header.u32(0, bigEndian = true)) {
                0L -> end - position
                1L -> {
                    val large = source.readUpTo(position + 8, 8)
                    if (large.size < 8) break
                    headerSize = 16
                    large.u64be(0)
                }
                else -> declared
            }
            var userType: String? = null
            if (type == "uuid") {
                val uuid = source.readUpTo(position + headerSize, 16)
                if (uuid.size < 16) break
                userType = formatUuid(uuid)
                headerSize += 16
            }
            if (size < headerSize) break
            val boxEnd = if (size > end - position) end else position + size
            boxes += BmffBox(type, position, headerSize, boxEnd, userType)
            position = boxEnd
        }
        return boxes
    }

    /** Children of [box], whose payload starts [skip] bytes in (4 for FullBox version and flags). */
    fun children(source: SeekableSource, box: BmffBox, skip: Int = 0): List<BmffBox> =
        boxes(source, box.payloadStart + skip, box.end)

    private fun formatUuid(bytes: ByteArray): String {
        val hex = bytes.joinToString("") { "%02x".format(it.toInt() and 0xFF) }
        return "${hex.substring(0, 8)}-${hex.substring(8, 12)}-${hex.substring(12, 16)}-${hex.substring(16, 20)}-${hex.substring(20)}"
    }

    private const val MAX_BOXES = 4096
}
