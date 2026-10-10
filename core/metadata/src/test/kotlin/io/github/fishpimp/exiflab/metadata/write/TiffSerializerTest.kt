package io.github.fishpimp.exiflab.metadata.write

import com.google.common.truth.Truth.assertThat
import io.github.fishpimp.exiflab.metadata.fixtures.Photos
import io.github.fishpimp.exiflab.metadata.fixtures.TiffBuilder
import io.github.fishpimp.exiflab.metadata.model.Rational
import io.github.fishpimp.exiflab.metadata.write.tiff.ExifEditor
import io.github.fishpimp.exiflab.metadata.write.tiff.ExifValues
import io.github.fishpimp.exiflab.metadata.write.tiff.MakerNoteLayout
import io.github.fishpimp.exiflab.metadata.write.tiff.MakerNotes
import io.github.fishpimp.exiflab.metadata.write.tiff.TiffParser
import io.github.fishpimp.exiflab.metadata.write.tiff.TiffSerializer
import java.nio.ByteBuffer
import java.nio.ByteOrder
import org.junit.Assert.assertThrows
import org.junit.Test

class TiffSerializerTest {
    /** A raw view of a TIFF block: every entry by IFD path and tag, plus every offset that was followed. */
    private class Walk(val bytes: ByteArray) {
        val bigEndian = bytes[0] == 'M'.code.toByte()
        val entries = linkedMapOf<String, Triple<Int, Long, List<Byte>>>()
        val offsets = mutableListOf<Long>()
        val tagOrder = mutableMapOf<String, List<Int>>()

        private fun u16(at: Int) = ByteBuffer.wrap(bytes, at, 2).order(order()).short.toInt() and 0xFFFF
        private fun u32(at: Int) = ByteBuffer.wrap(bytes, at, 4).order(order()).int.toLong() and 0xFFFFFFFFL
        private fun order() = if (bigEndian) ByteOrder.BIG_ENDIAN else ByteOrder.LITTLE_ENDIAN

        init {
            var ifd = u32(4)
            var index = 0
            while (ifd != 0L && index < 4) {
                ifd = walk(ifd, "IFD$index")
                index++
            }
        }

        private fun walk(offset: Long, path: String): Long {
            offsets += offset
            val count = u16(offset.toInt())
            val tags = mutableListOf<Int>()
            for (i in 0 until count) {
                val at = offset.toInt() + 2 + i * 12
                val tag = u16(at)
                val type = u16(at + 2)
                val n = u32(at + 4)
                tags += tag
                val size = n * when (type) { 1, 2, 6, 7 -> 1; 3, 8 -> 2; 4, 9, 11, 13 -> 4; 5, 10, 12 -> 8; else -> 0 }
                val valueAt = if (size <= 4 || size == 0L) at + 8 else u32(at + 8).toInt().also { offsets += it.toLong() }
                val value = if (size == 0L) bytes.copyOfRange(at + 8, at + 12) else bytes.copyOfRange(valueAt, valueAt + size.toInt())
                if (tag in setOf(0x8769, 0x8825, 0xA005)) {
                    walk(u32(at + 8), "$path/%04X".format(tag))
                } else if (tag !in setOf(0x0201)) {
                    entries["$path:%04X".format(tag)] = Triple(type, n, value.toList())
                }
            }
            tagOrder[path] = tags
            return u32(offset.toInt() + 2 + count * 12)
        }
    }

    private fun roundTrip(tiff: ByteArray, edit: MetadataChanges = MetadataChanges(), maxSize: Int = Int.MAX_VALUE): ByteArray {
        val notes = mutableListOf<String>()
        val document = ExifEditor.apply(TiffParser.parse(tiff, notes), edit, jpeg = null, skipped = notes)!!
        return TiffSerializer.serialize(document, maxSize, notes)
    }

    private val unusual = TiffBuilder.ifd {
        ascii(Photos.MAKE, "Odd")
        undefined(0xC4A5, ByteArray(5) { it.toByte() })
        sshort(0x7001, -5, 7)
        double(0x7002, 1.5)
        float(0x7003, 2.5f)
        sbyte(0x7004, -1, 2, -3)
        srational(0x7005, -1 to 3)
        subIfd(Photos.EXIF_IFD, Photos.exifIfd { ascii(0x9999, "abc") })
        subIfd(Photos.GPS_IFD, Photos.gpsIfd())
    }

    @Test fun keepsEveryEntryByteExactWithAlignedOffsetsInBothByteOrders() {
        for (bigEndian in listOf(true, false)) {
            val source = TiffBuilder(bigEndian).build(unusual)
            val output = roundTrip(source)
            val before = Walk(source)
            val after = Walk(output)
            assertThat(after.entries).containsExactlyEntriesIn(before.entries)
            assertThat(after.offsets.all { it % 2 == 0L }).isTrue()
            for ((path, tags) in after.tagOrder) assertThat(tags).isInStrictOrder()
        }
    }

    @Test fun unknownTypesKeepTheirValueField() {
        val source = TiffBuilder().build(unusual)
        // Turn the SSHORT entry into an undefined type 99, as damaged or exotic files have.
        val patched = source.copyOf()
        val ifd = ByteBuffer.wrap(patched, 4, 4).int
        val count = ByteBuffer.wrap(patched, ifd, 2).short.toInt()
        for (i in 0 until count) {
            val at = ifd + 2 + i * 12
            if (ByteBuffer.wrap(patched, at, 2).short.toInt() == 0x7001) {
                patched[at + 2] = 0
                patched[at + 3] = 99
            }
        }
        val output = roundTrip(patched)
        assertThat(Walk(output).entries.getValue("IFD0:7001")).isEqualTo(Walk(patched).entries.getValue("IFD0:7001"))
    }

    @Test fun keepsSubIfdsStripsAndChains() {
        val strips = listOf(ByteArray(11) { 1 }, ByteArray(6) { 2 }, ByteArray(9) { 3 })
        val child = TiffBuilder.ifd {
            long(0x0100, 4)
            long(0x0111, 0, 0, 0)
            long(0x0117, *strips.map { it.size.toLong() }.toLongArray())
        }
        val source = TiffBuilder().build(TiffBuilder.ifd { ascii(Photos.MAKE, "X"); subIfd(0x014A, child, TiffBuilder.ifd { short(0x0100, 9) }) })
        // Point the strip offsets at real data: append it and patch the offsets array.
        val withStrips = source + strips.fold(ByteArray(0)) { acc, s -> acc + s }
        val document = TiffParser.parse(withStrips.also { patchStripOffsets(it, source.size, strips) }, mutableListOf())
        val output = TiffSerializer.serialize(document, Int.MAX_VALUE, mutableListOf())
        val reparsed = TiffParser.parse(output, mutableListOf())
        val sub = (reparsed.ifd0[0x014A] as io.github.fishpimp.exiflab.metadata.write.tiff.TiffEntry.SubIfds).children
        assertThat(sub).hasSize(2)
        val blocks = (sub[0][0x0111] as io.github.fishpimp.exiflab.metadata.write.tiff.TiffEntry.DataBlocks).blocks
        assertThat(blocks.map { it.toList() }).isEqualTo(strips.map { it.toList() })
    }

    private fun patchStripOffsets(tiff: ByteArray, start: Int, strips: List<ByteArray>) {
        // Find the 0x0111 entry (big-endian) and its out-of-line offsets array.
        for (at in 8 until tiff.size - 12) {
            if (tiff[at] == 0x01.toByte() && tiff[at + 1] == 0x11.toByte() && tiff[at + 3] == 4.toByte() && tiff[at + 7] == 3.toByte()) {
                val array = ByteBuffer.wrap(tiff, at + 8, 4).int
                var position = start
                strips.forEachIndexed { i, strip ->
                    ByteBuffer.wrap(tiff, array + i * 4, 4).putInt(position)
                    position += strip.size
                }
                return
            }
        }
        error("No strip offsets")
    }

    @Test fun keepsDataANikonStyleMakerNoteRefersToPastItsEnd() {
        val preview = ByteArray(301) { (it * 7).toByte() }
        fun build(previewOffset: Int): ByteArray {
            // "Nikon\0\2\x10\0\0", its own big-endian TIFF header, one IFD entry pointing past the MakerNote.
            val makerNote = "Nikon\u0000".toByteArray() + byteArrayOf(2, 0x10, 0, 0) + byteArrayOf(0x4D, 0x4D, 0, 0x2A, 0, 0, 0, 8) +
                byteArrayOf(0, 1, 0, 0x11, 0, 4, 0, 0, 0, 1) + ByteBuffer.allocate(4).putInt(previewOffset).array() + ByteArray(4)
            val ifd0 = Photos.ifd0(Photos.exifIfd { undefined(Photos.MAKER_NOTE, makerNote) }) { ascii(Photos.MAKE, "NIKON CORPORATION") }
            return TiffBuilder().build(ifd0) + preview
        }
        val probe = build(0)
        val makerNoteAt = TiffParser.parse(probe, mutableListOf()).makerNote!!.sourceOffset.toInt()
        val previewAt = probe.size - preview.size
        val source = build(previewAt - (makerNoteAt + 10))
        assertThat(TiffParser.parse(source, mutableListOf()).makerNote!!.layout).isEqualTo(MakerNoteLayout.SelfContained)

        val output = roundTrip(source, WriteFixtures.changes(WriteFixtures.ascii(ExifIfd.Primary, Photos.ARTIST, "A new artist name")))
        assertThat(TiffParser.parse(output, mutableListOf()).makerNote!!.sourceOffset.toInt()).isEqualTo(makerNoteAt)
        assertThat(output.copyOfRange(previewAt, previewAt + preview.size)).isEqualTo(preview)

        // Without the MakerNote nothing refers to the preview any more, so it goes too.
        val stripped = roundTrip(source, MetadataChanges(removeBlocks = setOf(MetadataBlock.MakerNote)))
        assertThat(JpegWriterTest.indexOf(stripped, preview)).isEqualTo(-1)
    }

    @Test fun movingATiffRelativeMakerNoteFixesItsOffsetsAndFooter() {
        val sourceOffset = 1000L
        val makerNote = ByteBuffer.allocate(2 + 12 * 2 + 4 + 8 + 8).order(ByteOrder.LITTLE_ENDIAN)
        makerNote.putShort(2)
        // An ASCII value inside the MakerNote and one SHORT inline.
        makerNote.putShort(0x0006).putShort(2).putInt(8).putInt((sourceOffset + 30).toInt())
        makerNote.putShort(0x0001).putShort(3).putInt(1).putInt(5)
        makerNote.putInt(0)
        makerNote.put("Canon 1\u0000".toByteArray())
        makerNote.put("II*\u0000".toByteArray()).putInt(sourceOffset.toInt())
        val bytes = makerNote.array()
        val moved = MakerNotes.relocate(bytes, MakerNoteLayout.TiffRelativeIfd(0), sourceOffset, 4000, blockBigEndian = false)
        val view = ByteBuffer.wrap(moved).order(ByteOrder.LITTLE_ENDIAN)
        assertThat(view.getInt(2 + 8)).isEqualTo(4030)
        assertThat(view.getInt(2 + 12 + 8)).isEqualTo(5)
        assertThat(view.getInt(moved.size - 4)).isEqualTo(4000)
        assertThat(MakerNotes.relocate(bytes, MakerNoteLayout.SelfContained, sourceOffset, 4000, false)).isEqualTo(bytes)
    }

    @Test fun damagedLinksAreDroppedWithANoteAndCyclesEnd() {
        val source = TiffBuilder().build(TiffBuilder.ifd { ascii(Photos.MAKE, "X") })
        val notes = mutableListOf<String>()
        // IFD0 is at 8 with one entry; point its next link back at itself.
        val cyclic = source.copyOf().also { ByteBuffer.wrap(it, 8 + 2 + 12, 4).putInt(8) }
        val document = TiffParser.parse(cyclic, notes)
        assertThat(document.ifd0.next).isNull()
        assertThat(notes.single()).contains("Dropped")

        // A count of 10 moves the value out of line, to an offset far outside the block.
        val outside = source.copyOf().also { ByteBuffer.wrap(it, 8 + 2 + 4, 4).putInt(10) }
        val dropped = mutableListOf<String>()
        assertThat(TiffParser.parse(outside, dropped).ifd0.isEmpty).isTrue()
        assertThat(dropped.single()).contains("0x010F")
    }

    @Test fun valuesAreValidated() {
        assertThrows(UnsupportedEditException::class.java) { ExifValues.encode(1, ExifValue.Shorts(listOf(70000)), true, "x") }
        assertThrows(UnsupportedEditException::class.java) { ExifValues.encode(1, ExifValue.Bytes(listOf(-1)), true, "x") }
        assertThrows(UnsupportedEditException::class.java) { ExifValues.encode(1, ExifValue.Longs(emptyList()), true, "x") }
        assertThrows(UnsupportedEditException::class.java) {
            ExifValues.encode(1, ExifValue.SignedRationals(listOf(Rational(1L shl 40, 1))), true, "x")
        }
        val ascii = ExifValues.encode(1, ExifValue.Ascii("Å"), false, "x")
        assertThat(ascii.bytes.toList()).containsExactly(0xC3.toByte(), 0x85.toByte(), 0.toByte()).inOrder()
        assertThat(ascii.count).isEqualTo(3)
    }
}
