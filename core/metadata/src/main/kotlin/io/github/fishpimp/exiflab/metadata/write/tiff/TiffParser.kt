package io.github.fishpimp.exiflab.metadata.write.tiff

import io.github.fishpimp.exiflab.metadata.write.UnsupportedEditException

/**
 * Parses an EXIF/TIFF block (either byte order) into editable IFDs. Every entry the writer does
 * not need to understand is kept byte-exact (type, count, value bytes). Damaged parts that cannot
 * be carried over safely (values pointing outside the block, broken IFD links) are dropped and
 * described in [notes]; a block without a readable IFD0 throws [UnsupportedEditException].
 */
internal class TiffParser private constructor(private val block: ByteArray, private val notes: MutableList<String>) {
    private val order: ByteOrder
    private val visited = HashSet<Long>()

    init {
        if (block.size < HEADER_SIZE) throw UnsupportedEditException("The EXIF data is too short to edit")
        val mark = String(block, 0, 2, Charsets.ISO_8859_1)
        order = when (mark) {
            "MM" -> ByteOrder(true)
            "II" -> ByteOrder(false)
            else -> throw UnsupportedEditException("The EXIF data has no TIFF header and cannot be edited")
        }
        if (order.u16(block, 2) != TIFF_MAGIC) throw UnsupportedEditException("The EXIF data has an unknown TIFF version and cannot be edited")
    }

    private fun parse(): TiffDocument {
        val ifd0Offset = order.u32(block, 4)
        val ifd0 = readIfd(ifd0Offset, "IFD0", depth = 0) ?: throw UnsupportedEditException("The EXIF data is damaged (IFD0 is unreadable) and cannot be edited")
        var current = ifd0
        var linkAt = ifd0Offset
        var index = 1
        while (index < MAX_CHAIN) {
            val nextOffset = nextLink(linkAt) ?: break
            if (nextOffset == 0L) break
            val next = readIfd(nextOffset, "IFD$index", depth = 0)
            if (next == null) {
                notes += "Dropped a damaged link to EXIF IFD$index"
                break
            }
            current.next = next
            current = next
            linkAt = nextOffset
            index++
        }
        return TiffDocument(order.bigEndian, ifd0, orphans())
    }

    /** Byte ranges of the block that hold a parsed structure or value. */
    private val covered = mutableListOf<LongArray>()

    private fun cover(start: Long, size: Long) {
        if (size > 0) covered += longArrayOf(start, minOf(block.size.toLong(), start + size))
    }

    /**
     * Bytes no parsed structure refers to. MakerNotes may point at such data (Nikon keeps its
     * preview image past the end of the MakerNote), so the serializer keeps them in place
     * while the MakerNote stays in place. Zero bytes at either end of a gap are padding (Apple
     * and Canon reserve kilobytes of it) and are not kept.
     */
    private fun orphans(): List<PinnedRange> {
        val result = mutableListOf<PinnedRange>()
        fun gap(from: Long, to: Long) {
            var start = from.toInt()
            var end = to.toInt()
            while (start < end && block[start].toInt() == 0) start++
            while (end > start && block[end - 1].toInt() == 0) end--
            if (end > start) result += PinnedRange(start.toLong(), block.copyOfRange(start, end))
        }
        var position = HEADER_SIZE.toLong()
        for (range in covered.sortedBy { it[0] }) {
            if (range[0] > position) gap(position, range[0])
            position = maxOf(position, range[1])
        }
        if (position < block.size) gap(position, block.size.toLong())
        return result
    }

    private fun nextLink(ifdOffset: Long): Long? {
        val count = order.u16(block, ifdOffset.toInt())
        val at = ifdOffset + 2 + count * ENTRY_SIZE
        return if (at + 4 <= block.size) order.u32(block, at.toInt()) else null
    }

    private fun readIfd(offset: Long, name: String, depth: Int): TiffIfd? {
        if (offset < HEADER_SIZE || offset + 2 > block.size || depth > MAX_DEPTH || !visited.add(offset)) return null
        val count = order.u16(block, offset.toInt())
        if (count > MAX_ENTRIES || offset + 2 + count.toLong() * ENTRY_SIZE > block.size) return null
        cover(offset, 2L + count * ENTRY_SIZE + 4)
        val raw =(0 until count).map { i -> RawEntry((offset + 2 + i * ENTRY_SIZE).toInt()) }
        // Byte counts of data blocks are written together with their offsets.
        val lengthTags = raw.filter { it.isDataBlockOffsets }.mapNotNull { Tiff.DATA_BLOCK_TAGS[it.tag] }.toSet()
        val ifd = TiffIfd()
        for (entry in raw) {
            if (entry.tag in lengthTags) continue
            parseEntry(entry, raw, name, depth)?.let { ifd.entries += it }
        }
        return ifd
    }

    private inner class RawEntry(val at: Int) {
        val tag = order.u16(block, at)
        val type = order.u16(block, at + 2)
        val count = order.u32(block, at + 4)
        val field: ByteArray get() = block.copyOfRange(at + 8, at + 12)
        val typeSize = Tiff.typeSize(type)
        val byteSize: Long get() = typeSize * count
        val valueOffset: Long get() = if (byteSize <= 4) (at + 8).toLong() else order.u32(block, at + 8)
        val inBounds: Boolean get() = valueOffset + byteSize <= block.size && (byteSize <= 4 || valueOffset >= HEADER_SIZE)
        val isDataBlockOffsets: Boolean get() = tag in Tiff.DATA_BLOCK_TAGS && (type == Tiff.LONG || type == Tiff.SHORT)

        fun values(): List<Long> = (0 until count.toInt()).map { i ->
            val position = (valueOffset + i * typeSize).toInt()
            if (typeSize == 2) order.u16(block, position).toLong() else order.u32(block, position)
        }
    }

    private fun parseEntry(entry: RawEntry, siblings: List<RawEntry>, ifdName: String, depth: Int): TiffEntry? {
        val tagName = "0x%04X".format(entry.tag)
        if (entry.typeSize == 0) return TiffEntry.Opaque(entry.tag, entry.type, entry.count, entry.field)
        if (!entry.inBounds || entry.byteSize > Int.MAX_VALUE) {
            notes += "Dropped EXIF tag $tagName in $ifdName: its value lies outside the EXIF block"
            return null
        }
        if (entry.byteSize > 4) cover(entry.valueOffset, entry.byteSize)
        val isPointer = entry.tag in Tiff.IFD_POINTER_TAGS || entry.type == Tiff.IFD
        if (isPointer && (entry.type == Tiff.LONG || entry.type == Tiff.IFD) && entry.count in 1..MAX_SUB_IFDS) {
            val children = entry.values().mapNotNull { readIfd(it, "$ifdName/$tagName", depth + 1) }
            if (children.size != entry.count.toInt()) notes += "Dropped a damaged sub-IFD of EXIF tag $tagName in $ifdName"
            return if (children.isEmpty()) null else TiffEntry.SubIfds(entry.tag, entry.type, children.toMutableList())
        }
        if (entry.isDataBlockOffsets) {
            val lengthTag = Tiff.DATA_BLOCK_TAGS.getValue(entry.tag)
            return dataBlocks(entry, siblings.firstOrNull { it.tag == lengthTag }, ifdName)
        }
        if (entry.tag == Tiff.TAG_MAKER_NOTE && entry.byteSize > 4 && ifdName.endsWith("0x%04X".format(Tiff.TAG_EXIF_IFD))) {
            val offset = entry.valueOffset
            val bytes = block.copyOfRange(offset.toInt(), (offset + entry.byteSize).toInt())
            val layout = MakerNotes.layout(bytes, make)
            return TiffEntry.MakerNote(
                entry.tag, entry.type, entry.count, bytes, offset, layout,
                MakerNotes.externalRanges(block, offset, bytes, layout, order.bigEndian),
            )
        }
        val start = entry.valueOffset.toInt()
        return TiffEntry.Value(entry.tag, entry.type, entry.count, block.copyOfRange(start, start + entry.byteSize.toInt()))
    }

    private fun dataBlocks(offsets: RawEntry, lengths: RawEntry?, ifdName: String): TiffEntry? {
        val tagName = "0x%04X".format(offsets.tag)
        if (lengths == null || lengths.count != offsets.count || !lengths.inBounds || lengths.typeSize !in setOf(2, 4) || offsets.count > MAX_BLOCKS) {
            notes += "Dropped EXIF tag $tagName in $ifdName: its data has no valid length"
            return null
        }
        val starts = offsets.values()
        val sizes = lengths.values()
        if (lengths.byteSize > 4) cover(lengths.valueOffset, lengths.byteSize)
        starts.indices.forEach { i -> cover(starts[i], sizes[i]) }
        val blocks = starts.indices.map { i ->
            val start = starts[i]
            if (start < HEADER_SIZE || start >= block.size) {
                notes += "Dropped EXIF tag $tagName in $ifdName: its data lies outside the EXIF block"
                return null
            }
            val end = minOf(block.size.toLong(), start + sizes[i])
            if (end < start + sizes[i]) notes += "Shortened the data of EXIF tag $tagName in $ifdName to the end of the EXIF block"
            block.copyOfRange(start.toInt(), end.toInt())
        }
        return TiffEntry.DataBlocks(offsets.tag, lengths.tag, lengths.type, blocks)
    }

    /** IFD0 Make, needed to recognize MakerNote formats. */
    private val make: String? by lazy {
        val ifd0 = order.u32(block, 4)
        if (ifd0 + 2 > block.size) return@lazy null
        val count = order.u16(block, ifd0.toInt())
        (0 until count).asSequence()
            .map { RawEntry((ifd0 + 2 + it * ENTRY_SIZE).toInt()) }
            .takeWhile { it.at + ENTRY_SIZE <= block.size }
            .firstOrNull { it.tag == Tiff.TAG_MAKE && it.type == Tiff.ASCII && it.inBounds }
            ?.let { String(block, it.valueOffset.toInt(), it.byteSize.toInt(), Charsets.ISO_8859_1).trimEnd('\u0000', ' ') }
    }

    companion object {
        fun parse(block: ByteArray, notes: MutableList<String>): TiffDocument = TiffParser(block, notes).parse()

        private const val HEADER_SIZE = 8
        private const val TIFF_MAGIC = 42
        private const val ENTRY_SIZE = 12
        private const val MAX_ENTRIES = 1000
        private const val MAX_DEPTH = 6
        private const val MAX_CHAIN = 8
        private const val MAX_SUB_IFDS = 64
        private const val MAX_BLOCKS = 4096
    }
}
