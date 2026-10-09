package io.github.fishpimp.exiflab.metadata.preview

import io.github.fishpimp.exiflab.metadata.io.SeekableSource
import io.github.fishpimp.exiflab.metadata.io.latin1
import io.github.fishpimp.exiflab.metadata.io.u16
import io.github.fishpimp.exiflab.metadata.io.u32

/**
 * Walks the IFDs of a TIFF-based RAW file (IFD chain, SubIFDs, Exif IFD and Olympus/Nikon
 * MakerNotes) collecting embedded JPEG locations, reading only IFD entries.
 */
internal class TiffPreviewScanner(private val source: SeekableSource, private val tiffStart: Long = 0) {
    private enum class Kind { Main, Exif, OlympusMakerNote, OlympusCameraSettings, NikonMakerNote, NikonPreview }

    /** An IFD to visit; offsets inside it (values and pointers) are relative to [base]. */
    private class IfdRef(val offset: Long, val base: Long, val bigEndian: Boolean, val kind: Kind, val isIfd0: Boolean = false)

    private class Entry(val tag: Int, val type: Int, val count: Long, val valueBytes: ByteArray, val bigEndian: Boolean) {
        val fieldValue: Long get() = valueBytes.u32(0, bigEndian)
    }

    private val candidates = mutableListOf<PreviewCandidate>()
    private var orientation: Int? = null

    fun scan(): PreviewScan {
        val header = source.readUpTo(tiffStart, 8)
        if (header.size < 8) return PreviewScan.EMPTY
        val bigEndian = when (header.latin1(0, 2)) {
            "MM" -> true
            "II" -> false
            else -> return PreviewScan.EMPTY
        }
        if (header.u16(2, bigEndian) !in TIFF_MAGICS) return PreviewScan.EMPTY
        val queue = ArrayDeque<IfdRef>()
        queue += IfdRef(tiffStart + header.u32(4, bigEndian), tiffStart, bigEndian, Kind.Main, isIfd0 = true)
        val visited = HashSet<Long>()
        while (queue.isNotEmpty() && visited.size < MAX_IFDS) {
            val ifd = queue.removeFirst()
            if (ifd.offset <= 0 || !visited.add(ifd.offset)) continue
            visit(ifd, queue)
        }
        return PreviewScan(candidates.distinct(), orientation)
    }

    private fun visit(ifd: IfdRef, queue: ArrayDeque<IfdRef>) {
        val countBytes = source.readUpTo(ifd.offset, 2)
        if (countBytes.size < 2) return
        val count = countBytes.u16(0, ifd.bigEndian)
        if (count == 0 || count > MAX_ENTRIES) return
        val table = source.readUpTo(ifd.offset + 2, count * 12 + 4)
        val entries = (0 until minOf(count, table.size / 12)).associate { i ->
            val at = i * 12
            val entry = Entry(
                tag = table.u16(at, ifd.bigEndian),
                type = table.u16(at + 2, ifd.bigEndian),
                count = table.u32(at + 4, ifd.bigEndian),
                valueBytes = table.copyOfRange(at + 8, at + 12),
                bigEndian = ifd.bigEndian,
            )
            entry.tag to entry
        }
        when (ifd.kind) {
            Kind.Main -> visitMain(ifd, entries, queue)
            Kind.Exif -> entries[MAKER_NOTE]?.let { makerNote(ifd, it) }?.let(queue::add)
            Kind.OlympusMakerNote -> {
                entries[OLYMPUS_CAMERA_SETTINGS]?.let { queue += IfdRef(ifd.base + it.fieldValue, ifd.base, ifd.bigEndian, Kind.OlympusCameraSettings) }
                addPair(ifd, entries, OLYMPUS_OLD_PREVIEW_START, OLYMPUS_OLD_PREVIEW_LENGTH)
            }
            Kind.OlympusCameraSettings -> addPair(ifd, entries, OLYMPUS_PREVIEW_START, OLYMPUS_PREVIEW_LENGTH)
            Kind.NikonMakerNote ->
                entries[NIKON_PREVIEW_IFD]?.let { queue += IfdRef(ifd.base + it.fieldValue, ifd.base, ifd.bigEndian, Kind.NikonPreview) }
            Kind.NikonPreview -> addPair(ifd, entries, JPEG_OFFSET, JPEG_LENGTH)
        }
        if (ifd.kind == Kind.Main && table.size >= count * 12 + 4) {
            val next = table.u32(count * 12, ifd.bigEndian)
            if (next != 0L) queue += IfdRef(ifd.base + next, ifd.base, ifd.bigEndian, Kind.Main)
        }
    }

    private fun visitMain(ifd: IfdRef, entries: Map<Int, Entry>, queue: ArrayDeque<IfdRef>) {
        if (ifd.isIfd0) orientation = entries[ORIENTATION]?.let { values(ifd, it)?.firstOrNull()?.toInt() }?.takeIf { it in 1..8 }
        entries[SUB_IFDS]?.let { values(ifd, it) }?.forEach { queue += IfdRef(ifd.base + it, ifd.base, ifd.bigEndian, Kind.Main) }
        entries[EXIF_IFD]?.let { queue += IfdRef(ifd.base + it.fieldValue, ifd.base, ifd.bigEndian, Kind.Exif) }
        addPair(ifd, entries, JPEG_OFFSET, JPEG_LENGTH)
        // Strip-stored JPEG (old- or new-style JPEG compression), as in CR2 IFD0 and DNG previews.
        val compression = entries[COMPRESSION]?.let { values(ifd, it)?.firstOrNull() }
        if (compression == 6L || compression == 7L) {
            val offsets = entries[STRIP_OFFSETS]?.let { values(ifd, it) }
            val lengths = entries[STRIP_BYTE_COUNTS]?.let { values(ifd, it) }
            if (offsets?.size == 1 && lengths?.size == 1) add(ifd.base + offsets[0], lengths[0])
        }
        // Panasonic RW2 JpgFromRaw: an undefined-type tag whose value is the whole JPEG.
        entries[RW2_JPG_FROM_RAW]?.takeIf { it.count > 4 }?.let { add(ifd.base + it.fieldValue, it.count) }
    }

    /** Recognizes MakerNotes that hold previews; returns their main IFD. */
    private fun makerNote(exif: IfdRef, entry: Entry): IfdRef? {
        val start = exif.base + entry.fieldValue
        val head = source.readUpTo(start, 18)
        if (head.size < 18) return null
        fun order(at: Int): Boolean? = when (head.latin1(at, 2)) {
            "MM" -> true
            "II" -> false
            else -> null
        }
        return when {
            head.latin1(0, 8) == "OLYMPUS\u0000" -> order(8)?.let { IfdRef(start + 12, start, it, Kind.OlympusMakerNote) }
            head.latin1(0, 12) == "OM SYSTEM\u0000\u0000\u0000" -> order(12)?.let { IfdRef(start + 16, start, it, Kind.OlympusMakerNote) }
            head.latin1(0, 6) == "OLYMP\u0000" -> IfdRef(start + 8, exif.base, exif.bigEndian, Kind.OlympusMakerNote)
            head.latin1(0, 6) == "Nikon\u0000" && head[6].toInt() == 2 -> order(10)?.let { bigEndian ->
                val tiff = start + 10
                IfdRef(tiff + head.u32(14, bigEndian), tiff, bigEndian, Kind.NikonMakerNote)
            }
            else -> null
        }
    }

    private fun addPair(ifd: IfdRef, entries: Map<Int, Entry>, offsetTag: Int, lengthTag: Int) {
        val offset = entries[offsetTag]?.let { values(ifd, it)?.firstOrNull() } ?: return
        val length = entries[lengthTag]?.let { values(ifd, it)?.firstOrNull() } ?: return
        add(ifd.base + offset, length)
    }

    private fun add(offset: Long, length: Long) {
        if (offset > 0 && length > 2) candidates += PreviewCandidate(offset, length)
    }

    /** Integer values of a SHORT, LONG or IFD entry, read inline or from its offset. */
    private fun values(ifd: IfdRef, entry: Entry): LongArray? {
        val size = when (entry.type) {
            TYPE_SHORT -> 2
            TYPE_LONG, TYPE_IFD -> 4
            else -> return null
        }
        if (entry.count !in 1..MAX_VALUES) return null
        val count = entry.count.toInt()
        val bytes = if (count * size <= 4) entry.valueBytes else source.readUpTo(ifd.base + entry.fieldValue, count * size)
        if (bytes.size < count * size) return null
        return LongArray(count) { i -> if (size == 2) bytes.u16(i * 2, ifd.bigEndian).toLong() else bytes.u32(i * 4, ifd.bigEndian) }
    }

    private companion object {
        // Standard TIFF, Panasonic RW2 ("IIU"), Olympus ORF ("IIRO", "IIRS").
        val TIFF_MAGICS = setOf(0x002A, 0x0055, 0x4F52, 0x5352)
        const val MAX_IFDS = 64
        const val MAX_ENTRIES = 1000
        const val MAX_VALUES = 4096L

        const val TYPE_SHORT = 3
        const val TYPE_LONG = 4
        const val TYPE_IFD = 13

        const val RW2_JPG_FROM_RAW = 0x002E
        const val COMPRESSION = 0x0103
        const val STRIP_OFFSETS = 0x0111
        const val ORIENTATION = 0x0112
        const val STRIP_BYTE_COUNTS = 0x0117
        const val SUB_IFDS = 0x014A
        const val JPEG_OFFSET = 0x0201
        const val JPEG_LENGTH = 0x0202
        const val EXIF_IFD = 0x8769
        const val MAKER_NOTE = 0x927C

        const val NIKON_PREVIEW_IFD = 0x0011
        const val OLYMPUS_OLD_PREVIEW_START = 0x0088
        const val OLYMPUS_OLD_PREVIEW_LENGTH = 0x0089
        const val OLYMPUS_CAMERA_SETTINGS = 0x2020
        const val OLYMPUS_PREVIEW_START = 0x0101
        const val OLYMPUS_PREVIEW_LENGTH = 0x0102
    }
}
