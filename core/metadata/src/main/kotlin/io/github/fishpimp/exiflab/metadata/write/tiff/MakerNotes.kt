package io.github.fishpimp.exiflab.metadata.write.tiff

/** How the offsets inside a MakerNote are based, which decides whether and how it can move. */
internal sealed interface MakerNoteLayout {
    /**
     * No offsets, or offsets relative to the MakerNote itself (Nikon type 3 with its own TIFF
     * header, Fujifilm, Apple, Olympus type 2, Pentax, Leica type 5, Sanyo, Ricoh, Kodak). The
     * bytes can move unchanged.
     */
    data object SelfContained : MakerNoteLayout

    /**
     * An IFD starting [ifdStart] bytes into the MakerNote whose offsets are relative to the TIFF
     * header (Canon, Sony, Panasonic, Samsung, DJI, Olympus type 1, Nikon type 1, Casio, Sigma).
     * Moving it means adding the distance moved to each of those offsets.
     */
    data class TiffRelativeIfd(val ifdStart: Int, val forceBigEndian: Boolean? = null) : MakerNoteLayout

    /** A format nobody here understands; moving it may break whatever offsets it holds. */
    data object Unknown : MakerNoteLayout
}

/**
 * Recognizes MakerNote formats the way metadata-extractor does (same signatures, same order), so
 * that what ExifLab shows before an edit is still decoded after it.
 */
internal object MakerNotes {
    fun layout(bytes: ByteArray, make: String?): MakerNoteLayout {
        fun text(length: Int) = if (bytes.size >= length) String(bytes, 0, length, Charsets.ISO_8859_1) else ""
        val upperMake = make?.trim()?.uppercase().orEmpty()
        return when {
            text(6) == "OLYMP\u0000" || text(5) == "EPSON" || text(4) == "AGFA" -> MakerNoteLayout.TiffRelativeIfd(8)
            text(10) == "OLYMPUS\u0000II" -> MakerNoteLayout.SelfContained
            text(14) == "OM SYSTEM\u0000\u0000\u0000II" -> MakerNoteLayout.SelfContained
            upperMake.startsWith("MINOLTA") -> MakerNoteLayout.TiffRelativeIfd(0)
            upperMake.startsWith("NIKON") -> when {
                text(5) != "Nikon" -> MakerNoteLayout.TiffRelativeIfd(0)
                bytes.size > 6 && bytes[6].toInt() == 1 -> MakerNoteLayout.TiffRelativeIfd(8)
                bytes.size > 6 && bytes[6].toInt() == 2 -> MakerNoteLayout.SelfContained
                else -> MakerNoteLayout.Unknown
            }
            text(8) == "SONY CAM" || text(8) == "SONY DSC" -> MakerNoteLayout.TiffRelativeIfd(12)
            make?.startsWith("SONY") == true && !(bytes.size >= 2 && bytes[0].toInt() == 1 && bytes[1].toInt() == 0) ->
                MakerNoteLayout.TiffRelativeIfd(0)
            text(12) == "SEMC MS\u0000\u0000\u0000\u0000\u0000" -> MakerNoteLayout.TiffRelativeIfd(20, forceBigEndian = true)
            text(8) == "SIGMA\u0000\u0000\u0000" || text(8) == "FOVEON\u0000\u0000" -> MakerNoteLayout.TiffRelativeIfd(10)
            text(3) == "KDK" -> MakerNoteLayout.SelfContained
            make.equals("Canon", ignoreCase = true) -> MakerNoteLayout.TiffRelativeIfd(0)
            upperMake.startsWith("CASIO") ->
                if (text(6) == "QVC\u0000\u0000\u0000") MakerNoteLayout.TiffRelativeIfd(6) else MakerNoteLayout.TiffRelativeIfd(0)
            text(8) == "FUJIFILM" || make.equals("Fujifilm", ignoreCase = true) -> MakerNoteLayout.SelfContained
            text(7) == "KYOCERA" -> MakerNoteLayout.TiffRelativeIfd(22)
            text(5) == "LEICA" -> when {
                text(8) in LEICA_TYPE5 -> MakerNoteLayout.SelfContained
                make == "Leica Camera AG" || make == "LEICA" -> MakerNoteLayout.TiffRelativeIfd(8)
                else -> MakerNoteLayout.Unknown
            }
            text(12) == "Panasonic\u0000\u0000\u0000" -> MakerNoteLayout.TiffRelativeIfd(12)
            text(4) == "AOC\u0000" -> MakerNoteLayout.SelfContained
            upperMake.startsWith("PENTAX") || upperMake.startsWith("ASAHI") -> MakerNoteLayout.SelfContained
            text(8) == "SANYO\u0000\u0001\u0000" -> MakerNoteLayout.SelfContained
            upperMake.startsWith("RICOH") -> MakerNoteLayout.SelfContained
            text(10) == "Apple iOS\u0000" -> MakerNoteLayout.SelfContained
            text(9).equals("RECONYXUF", ignoreCase = true) || text(9).equals("RECONYXH2", ignoreCase = true) -> MakerNoteLayout.SelfContained
            upperMake == "SAMSUNG" -> MakerNoteLayout.TiffRelativeIfd(0)
            upperMake == "DJI" -> MakerNoteLayout.TiffRelativeIfd(0)
            else -> MakerNoteLayout.Unknown
        }
    }

    /**
     * Ranges of [block] outside the MakerNote that its TIFF-relative offsets point at. They are
     * kept at their offsets so the MakerNote still finds them after the rest is laid out anew.
     */
    fun externalRanges(block: ByteArray, sourceOffset: Long, bytes: ByteArray, layout: MakerNoteLayout, blockBigEndian: Boolean): List<PinnedRange> {
        if (layout !is MakerNoteLayout.TiffRelativeIfd) return emptyList()
        val ranges = mutableListOf<PinnedRange>()
        forEachOffset(bytes, layout, blockBigEndian) { _, offset, size ->
            val inside = offset >= sourceOffset && offset + size <= sourceOffset + bytes.size
            if (!inside && offset >= HEADER_SIZE && offset + size <= block.size) {
                ranges += PinnedRange(offset, block.copyOfRange(offset.toInt(), (offset + size).toInt()))
            }
        }
        return merge(ranges)
    }

    /**
     * Returns the MakerNote bytes to store at [newOffset]: offsets into the MakerNote itself are
     * moved along for TIFF-relative IFDs, and a Canon-style footer (byte order mark plus the
     * MakerNote's own offset) is updated. Other layouts are returned unchanged.
     */
    fun relocate(bytes: ByteArray, layout: MakerNoteLayout, sourceOffset: Long, newOffset: Long, blockBigEndian: Boolean): ByteArray {
        if (layout !is MakerNoteLayout.TiffRelativeIfd || newOffset == sourceOffset) return bytes
        val moved = bytes.copyOf()
        val delta = newOffset - sourceOffset
        val order = ByteOrder(layout.forceBigEndian ?: blockBigEndian)
        forEachOffset(bytes, layout, blockBigEndian) { fieldAt, offset, size ->
            if (offset >= sourceOffset && offset + size <= sourceOffset + bytes.size) order.put32(moved, fieldAt, offset + delta)
        }
        if (bytes.size >= 8) {
            val footer = bytes.size - 8
            val mark = String(bytes, footer, 4, Charsets.ISO_8859_1)
            val footerOrder = when (mark) {
                "II*\u0000" -> ByteOrder(false)
                "MM\u0000*" -> ByteOrder(true)
                else -> null
            }
            if (footerOrder != null && footerOrder.u32(bytes, footer + 4) == sourceOffset) footerOrder.put32(moved, footer + 4, newOffset)
        }
        return moved
    }

    /** Calls [action] with (value field position, TIFF-relative offset, byte size) for each out-of-line value of the MakerNote IFD. */
    private inline fun forEachOffset(
        bytes: ByteArray,
        layout: MakerNoteLayout.TiffRelativeIfd,
        blockBigEndian: Boolean,
        action: (fieldAt: Int, offset: Long, size: Long) -> Unit,
    ) {
        val order = ByteOrder(layout.forceBigEndian ?: blockBigEndian)
        val start = layout.ifdStart
        if (start + 2 > bytes.size) return
        val count = order.u16(bytes, start)
        if (count == 0 || count > MAX_ENTRIES || start + 2 + count * 12 > bytes.size) return
        for (i in 0 until count) {
            val at = start + 2 + i * 12
            val typeSize = Tiff.typeSize(order.u16(bytes, at + 2))
            val size = typeSize * order.u32(bytes, at + 4)
            if (typeSize == 0 || size <= 4) continue
            action(at + 8, order.u32(bytes, at + 8), size)
        }
    }

    private fun merge(ranges: List<PinnedRange>): List<PinnedRange> {
        val sorted = ranges.sortedBy { it.offset }
        val merged = mutableListOf<PinnedRange>()
        for (range in sorted) {
            val last = merged.lastOrNull()
            if (last != null && range.offset <= last.end) {
                if (range.end > last.end) {
                    val joined = last.bytes + range.bytes.copyOfRange((last.end - range.offset).toInt(), range.bytes.size)
                    merged[merged.size - 1] = PinnedRange(last.offset, joined)
                }
            } else {
                merged += range
            }
        }
        return merged
    }

    private val LEICA_TYPE5 = setOf(1, 4, 5, 6, 7).map { "LEICA\u0000${it.toChar()}\u0000" }.toSet()
    private const val MAX_ENTRIES = 1000
    private const val HEADER_SIZE = 8
}
