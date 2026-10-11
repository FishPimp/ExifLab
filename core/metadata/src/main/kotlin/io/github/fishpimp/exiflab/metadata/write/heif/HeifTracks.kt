package io.github.fishpimp.exiflab.metadata.write.heif

import io.github.fishpimp.exiflab.metadata.write.UnsupportedEditException

/** The chunks of one track of a HEIF image sequence or burst, from its sample table. */
internal class Track(
    /** Absolute file offsets of the chunks (`stco` or `co64`). */
    val chunkOffsets: LongArray,
    /** Byte size of every chunk: the sizes of the samples `stsc` assigns to it. */
    val chunkSizes: LongArray,
    /** Whether the offsets are 64-bit (`co64`). */
    val wide: Boolean,
    /** Position of the first offset entry in the `moov` buffer. */
    val tableStart: Int,
    /** Raw `stsd`, `stsc` and `stsz`/`stz2` boxes, which describe the samples and are part of the image data digest. */
    val description: List<ByteArray>,
)

/** A parsed `moov` box: its tracks and whether it holds other file offsets the writer cannot adjust. */
internal class Movie(val bytes: ByteArray, val tracks: List<Track>, private val hasOtherOffsets: Boolean) {
    /**
     * Returns the box with every chunk offset moved through [map] (source offset and size to output
     * offset, or null when the chunk cannot be placed).
     *
     * @throws UnsupportedEditException when a chunk cannot be placed, an offset no longer fits, or the
     *   box holds other absolute offsets (`saio`, a track-level `iloc`) while data moves.
     */
    fun moved(map: (offset: Long, size: Long) -> Long?): ByteArray {
        val result = bytes.copyOf()
        var changed = false
        for (track in tracks) {
            for (i in track.chunkOffsets.indices) {
                val old = track.chunkOffsets[i]
                val new = map(old, track.chunkSizes[i])
                    ?: if (track.chunkSizes[i] == 0L) old else throw UnsupportedEditException("A HEIF sequence track has data the writer cannot place")
                if (new == old) continue
                changed = true
                if (track.wide) {
                    put(result, track.tableStart + i * 8, new ushr 32)
                    put(result, track.tableStart + i * 8 + 4, new and 0xFFFFFFFFL)
                } else {
                    if (new > 0xFFFFFFFFL) throw UnsupportedEditException("A HEIF sequence track would need 64-bit chunk offsets")
                    put(result, track.tableStart + i * 4, new)
                }
            }
        }
        if (changed && hasOtherOffsets) throw UnsupportedEditException("The HEIF sequence holds file offsets the writer cannot adjust")
        return result
    }

    private fun put(bytes: ByteArray, at: Int, value: Long) {
        for (k in 0 until 4) bytes[at + k] = (value shr (24 - 8 * k)).toByte()
    }

    companion object {
        private val CONTAINERS = setOf("moov", "trak", "mdia", "minf", "stbl", "edts", "udta", "dinf", "mvex", "tref")

        fun parse(bytes: ByteArray): Movie {
            val top = BoxBytes.children(bytes, 0, bytes.size, "moov").singleOrNull()?.takeIf { it.type == "moov" } ?: BoxBytes.damaged("moov")
            var hasOtherOffsets = false
            val tracks = mutableListOf<Track>()

            // Looks for boxes that hold absolute file offsets besides the chunk tables. Containers that
            // do not parse as plain boxes (QuickTime `udta` may end with a zero terminator) hold none.
            fun scan(box: MemBox) {
                val skip = when (box.type) {
                    "saio", "iloc" -> {
                        hasOtherOffsets = true
                        return
                    }
                    "meta" -> 4
                    in CONTAINERS -> 0
                    else -> return
                }
                if (box.end - box.payloadStart < skip) return
                runCatching { BoxBytes.children(bytes, box.payloadStart + skip, box.end, box.type) }.getOrNull()?.forEach(::scan)
            }
            scan(top)

            for (trak in BoxBytes.children(bytes, top.payloadStart, top.end, "moov").filter { it.type == "trak" }) {
                val mdia = child(bytes, trak, "mdia")
                val minf = child(bytes, mdia, "minf")
                checkSelfContained(bytes, minf)
                tracks += parseSampleTable(bytes, child(bytes, minf, "stbl"))
            }
            return Movie(bytes, tracks, hasOtherOffsets)
        }

        private fun child(bytes: ByteArray, box: MemBox, type: String): MemBox =
            BoxBytes.children(bytes, box.payloadStart, box.end, box.type).singleOrNull { it.type == type } ?: BoxBytes.damaged(box.type)

        /** Sample data in another file (a `dref` entry without the self-contained flag) cannot be digested or moved. */
        private fun checkSelfContained(bytes: ByteArray, minf: MemBox) {
            val dinf = BoxBytes.children(bytes, minf.payloadStart, minf.end, "minf").firstOrNull { it.type == "dinf" } ?: return
            val dref = BoxBytes.children(bytes, dinf.payloadStart, dinf.end, "dinf").firstOrNull { it.type == "dref" } ?: return
            if (dref.end - dref.payloadStart < 8) BoxBytes.damaged("dref")
            for (entry in BoxBytes.children(bytes, dref.payloadStart + 8, dref.end, "dref")) {
                val flags = if (entry.end - entry.payloadStart >= 4) BoxBytes.u32(bytes, entry.payloadStart) and 0xFFFFFF else 0L
                if (flags and 1L == 0L) throw UnsupportedEditException("The HEIF sequence keeps sample data in another file")
            }
        }

        private fun parseSampleTable(bytes: ByteArray, stbl: MemBox): Track {
            val children = BoxBytes.children(bytes, stbl.payloadStart, stbl.end, "stbl")
            fun single(vararg types: String): MemBox = children.filter { it.type in types }.singleOrNull() ?: BoxBytes.damaged("stbl")

            val offsetsBox = single("stco", "co64")
            val wide = offsetsBox.type == "co64"
            val offsetsReader = ByteReader(bytes, offsetsBox.payloadStart + 4, offsetsBox.end, offsetsBox.type)
            val chunkCount = offsetsReader.u32()
            if (chunkCount > offsetsReader.remaining / (if (wide) 8 else 4)) BoxBytes.damaged(offsetsBox.type)
            val tableStart = offsetsReader.position
            val offsets = LongArray(chunkCount.toInt()) { if (wide) offsetsReader.u64() else offsetsReader.u32() }

            val sizesBox = single("stsz", "stz2")
            val sizes = sampleSizes(bytes, sizesBox)

            val stsc = single("stsc")
            val stscReader = ByteReader(bytes, stsc.payloadStart + 4, stsc.end, "stsc")
            val runCount = stscReader.u32()
            if (runCount > stscReader.remaining / 12) BoxBytes.damaged("stsc")
            val runs = List(runCount.toInt()) { Pair(stscReader.u32(), stscReader.u32()).also { stscReader.u32() } }

            val chunkSizes = LongArray(offsets.size)
            var sample = 0L
            for ((index, run) in runs.withIndex()) {
                val (firstChunk, samplesPerChunk) = run
                val nextFirst = runs.getOrNull(index + 1)?.first ?: (offsets.size + 1L)
                if (firstChunk < 1 || nextFirst <= firstChunk) BoxBytes.damaged("stsc")
                for (chunk in firstChunk until minOf(nextFirst, offsets.size + 1L)) {
                    if (samplesPerChunk > sizes.count - sample) BoxBytes.damaged("stsc")
                    val constant = sizes.constant
                    chunkSizes[(chunk - 1).toInt()] = if (constant != null) {
                        sample += samplesPerChunk
                        samplesPerChunk * constant
                    } else {
                        var total = 0L
                        for (k in 0L until samplesPerChunk) total += sizes.size(sample++)
                        total
                    }
                }
            }
            if (sample != sizes.count) BoxBytes.damaged("stsc")

            val stsd = single("stsd")
            val description = listOf(stsd, stsc, sizesBox).map { bytes.copyOfRange(it.start, it.end) }
            return Track(offsets, chunkSizes, wide, tableStart, description)
        }

        private class SampleSizes(val count: Long, val constant: Long? = null, val size: (Long) -> Long)

        private fun sampleSizes(bytes: ByteArray, box: MemBox): SampleSizes {
            val reader = ByteReader(bytes, box.payloadStart + 4, box.end, box.type)
            if (box.type == "stsz") {
                val constant = reader.u32()
                val count = reader.u32()
                if (constant != 0L) return SampleSizes(count, constant) { constant }
                if (count > reader.remaining / 4) BoxBytes.damaged("stsz")
                val start = reader.position
                return SampleSizes(count) { BoxBytes.u32(bytes, start + (it * 4).toInt()) }
            }
            reader.u16()
            reader.u8()
            val fieldSize = reader.u8()
            val count = reader.u32()
            val start = reader.position
            val needed = when (fieldSize) {
                4 -> (count + 1) / 2
                8 -> count
                16 -> count * 2
                else -> BoxBytes.damaged("stz2")
            }
            if (needed > reader.remaining) BoxBytes.damaged("stz2")
            return SampleSizes(count) { index ->
                when (fieldSize) {
                    4 -> BoxBytes.u8(bytes, start + (index / 2).toInt()).let { if (index % 2 == 0L) it shr 4 else it and 0x0F }.toLong()
                    8 -> BoxBytes.u8(bytes, start + index.toInt()).toLong()
                    else -> BoxBytes.u16(bytes, start + (index * 2).toInt()).toLong()
                }
            }
        }
    }
}
