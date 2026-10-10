package io.github.fishpimp.exiflab.metadata.write.tiff

import io.github.fishpimp.exiflab.metadata.write.UnsupportedEditException
import java.util.IdentityHashMap

/**
 * Serializes a [TiffDocument]: IFD0 right after the header, then every IFD with its out-of-line
 * values, sub-IFDs, the IFD0 chain (IFD1) and finally data blocks such as the thumbnail. All
 * structures start on word (even) boundaries; entries are written in ascending tag order.
 *
 * The MakerNote stays at the offset it had in the source block whenever the result fits in
 * [maxSize]: the other structures are laid out around it (first fit, padding where needed),
 * because many vendors store offsets relative to the TIFF header inside it. Only when that layout
 * is too large is the MakerNote moved, with its offsets fixed for TIFF-relative IFD layouts.
 */
internal object TiffSerializer {
    fun serialize(document: TiffDocument, maxSize: Int, notes: MutableList<String>): ByteArray {
        val makerNote = document.makerNote
        val external = makerNote?.external.orEmpty()
        if (makerNote != null) {
            val kept = external + PinnedRange(makerNote.sourceOffset, makerNote.bytes)
            if (document.orphans.isNotEmpty()) {
                val withOrphans = Layout(document, kept + document.orphans, keepMakerNote = true).build()
                if (withOrphans.size <= maxSize) return withOrphans
            }
            val pinned = Layout(document, kept, keepMakerNote = true).build()
            if (pinned.size <= maxSize) {
                if (document.orphans.isNotEmpty()) {
                    notes += "Dropped ${document.orphans.sumOf { it.bytes.size }} bytes of EXIF data that no tag refers to, to make room"
                }
                return pinned
            }
        }
        val packed = Layout(document, external, keepMakerNote = false).build()
        if (packed.size > maxSize) {
            throw UnsupportedEditException("The EXIF data would take ${packed.size} bytes, more than the $maxSize bytes this file format allows")
        }
        if (makerNote != null && makerNote.layout == MakerNoteLayout.Unknown) {
            notes += "Moved a MakerNote of unknown format to make room; vendor tools may no longer read all of it"
        }
        return packed
    }

    private class Layout(private val document: TiffDocument, pins: List<PinnedRange>, private val keepMakerNote: Boolean) {
        private val order = ByteOrder(document.bigEndian)
        private val pins = pins.sortedBy { it.offset }
        private val reserved: List<LongArray> = mergeRanges(this.pins.map { longArrayOf(it.offset, it.end) })
        private val offsets = IdentityHashMap<Any, Long>()
        private val gaps = mutableListOf<LongArray>()
        private var end = HEADER_SIZE.toLong()

        fun build(): ByteArray {
            allocateStructure(document.ifd0)
            allocateBlocks(document.ifd0)
            val size = maxOf(end, reserved.maxOfOrNull { it[1] } ?: 0L)
            check(size <= Int.MAX_VALUE) { "EXIF block too large" }
            val out = ByteArray(size.toInt())
            out[0] = if (document.bigEndian) 'M'.code.toByte() else 'I'.code.toByte()
            out[1] = out[0]
            order.put16(out, 2, 42)
            order.put32(out, 4, offsets.getValue(document.ifd0))
            for (pin in pins) pin.bytes.copyInto(out, pin.offset.toInt())
            writeIfd(out, document.ifd0)
            return out
        }

        private fun ifdsOf(root: TiffIfd): Sequence<TiffIfd> = sequence {
            var current: TiffIfd? = root
            while (current != null) {
                yield(current)
                for (entry in current.entries) if (entry is TiffEntry.SubIfds) for (child in entry.children) yieldAll(ifdsOf(child))
                current = current.next
            }
        }

        private fun allocateStructure(root: TiffIfd) {
            for (ifd in ifdsOf(root)) {
                offsets[ifd] = allocate(2 + ENTRY_SIZE * fieldCount(ifd) + 4)
                for (entry in sorted(ifd)) {
                    when (entry) {
                        is TiffEntry.Value -> if (entry.bytes.size > 4) offsets[entry] = allocate(entry.bytes.size)
                        is TiffEntry.SubIfds -> if (entry.children.size > 1) offsets[entry] = allocate(4 * entry.children.size)
                        is TiffEntry.DataBlocks -> if (entry.blocks.size > 1) {
                            offsets[entry] = allocate(4 * entry.blocks.size)
                            offsets[entry.blocks] = allocate(lengthSize(entry) * entry.blocks.size)
                        }
                        is TiffEntry.MakerNote -> offsets[entry] = if (keepMakerNote) entry.sourceOffset else allocate(entry.bytes.size)
                        is TiffEntry.Opaque -> Unit
                    }
                }
            }
        }

        private fun allocateBlocks(root: TiffIfd) {
            for (ifd in ifdsOf(root)) {
                for (entry in sorted(ifd)) {
                    if (entry is TiffEntry.DataBlocks) for (block in entry.blocks) offsets[block] = if (block.isEmpty()) 0L else allocate(block.size)
                }
            }
        }

        private fun writeIfd(out: ByteArray, root: TiffIfd) {
            for (ifd in ifdsOf(root)) {
                val at = offsets.getValue(ifd).toInt()
                val fields = sorted(ifd).flatMap { entry ->
                    if (entry is TiffEntry.DataBlocks) listOf(entry.tag to entry, entry.lengthTag to entry) else listOf(entry.tag to entry)
                }.sortedBy { it.first }
                order.put16(out, at, fields.size)
                fields.forEachIndexed { index, (tag, entry) -> writeField(out, at + 2 + index * ENTRY_SIZE, tag, entry) }
                order.put32(out, at + 2 + fields.size * ENTRY_SIZE, ifd.next?.let { offsets.getValue(it) } ?: 0L)
            }
        }

        private fun writeField(out: ByteArray, at: Int, tag: Int, entry: TiffEntry) {
            order.put16(out, at, tag)
            when (entry) {
                is TiffEntry.Value -> {
                    header(out, at, entry.type, entry.count)
                    if (entry.bytes.size <= 4) entry.bytes.copyInto(out, at + 8) else place(out, at, offsets.getValue(entry), entry.bytes)
                }
                is TiffEntry.Opaque -> {
                    header(out, at, entry.type, entry.count)
                    entry.field.copyInto(out, at + 8)
                }
                is TiffEntry.SubIfds -> {
                    header(out, at, entry.type, entry.children.size.toLong())
                    longs(out, at, offsets[entry], entry.children.map { offsets.getValue(it) }, 4)
                }
                is TiffEntry.DataBlocks -> if (tag == entry.tag) {
                    header(out, at, Tiff.LONG, entry.blocks.size.toLong())
                    longs(out, at, offsets[entry], entry.blocks.map { offsets.getValue(it) }, 4)
                    for (block in entry.blocks) if (block.isNotEmpty()) block.copyInto(out, offsets.getValue(block).toInt())
                } else {
                    val size = lengthSize(entry)
                    header(out, at, if (size == 2) Tiff.SHORT else Tiff.LONG, entry.blocks.size.toLong())
                    longs(out, at, offsets[entry.blocks], entry.blocks.map { it.size.toLong() }, size)
                }
                is TiffEntry.MakerNote -> {
                    header(out, at, entry.type, entry.count)
                    val offset = offsets.getValue(entry)
                    val bytes = MakerNotes.relocate(entry.bytes, entry.layout, entry.sourceOffset, offset, document.bigEndian)
                    place(out, at, offset, bytes)
                }
            }
        }

        private fun header(out: ByteArray, at: Int, type: Int, count: Long) {
            order.put16(out, at + 2, type)
            order.put32(out, at + 4, count)
        }

        private fun place(out: ByteArray, at: Int, offset: Long, bytes: ByteArray) {
            order.put32(out, at + 8, offset)
            bytes.copyInto(out, offset.toInt())
        }

        /** Writes [values] of [size] bytes each inline when they fit in the value field, else at [arrayOffset]. */
        private fun longs(out: ByteArray, at: Int, arrayOffset: Long?, values: List<Long>, size: Int) {
            val inline = values.size * size <= 4
            val base = if (inline) at + 8 else arrayOffset!!.toInt().also { order.put32(out, at + 8, it.toLong()) }
            values.forEachIndexed { i, value ->
                if (size == 2) order.put16(out, base + i * 2, value.toInt()) else order.put32(out, base + i * 4, value)
            }
        }

        private fun lengthSize(entry: TiffEntry.DataBlocks): Int =
            if (entry.lengthType == Tiff.SHORT && entry.blocks.all { it.size <= 0xFFFF }) 2 else 4

        private fun fieldCount(ifd: TiffIfd) = ifd.entries.sumOf { if (it is TiffEntry.DataBlocks) 2L else 1L }.toInt()

        private fun sorted(ifd: TiffIfd) = ifd.entries.sortedBy { it.tag }

        /** First-fit allocation on even offsets around the reserved (pinned) ranges. */
        private fun allocate(size: Int): Long {
            require(size > 0)
            for (gap in gaps) {
                val start = align(gap[0])
                if (start + size <= gap[1]) {
                    gap[0] = start + size
                    return start
                }
            }
            var position = align(end)
            while (true) {
                val clash = reserved.firstOrNull { position < it[1] && position + size > it[0] } ?: break
                if (clash[0] > position) gaps += longArrayOf(position, clash[0])
                position = align(clash[1])
            }
            end = position + size
            return position
        }

        private fun align(position: Long) = position + (position and 1L)
    }

    private fun mergeRanges(ranges: List<LongArray>): List<LongArray> {
        val merged = mutableListOf<LongArray>()
        for (range in ranges.sortedBy { it[0] }) {
            val last = merged.lastOrNull()
            if (last != null && range[0] <= last[1]) last[1] = maxOf(last[1], range[1]) else merged += range.copyOf()
        }
        return merged
    }

    private const val HEADER_SIZE = 8
    private const val ENTRY_SIZE = 12
}
