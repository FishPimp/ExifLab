package io.github.fishpimp.exiflab.metadata.write

import com.google.common.truth.Truth.assertThat
import io.github.fishpimp.exiflab.metadata.ByteArrayImageSource
import io.github.fishpimp.exiflab.metadata.CorruptImageException
import io.github.fishpimp.exiflab.metadata.fixtures.Containers
import io.github.fishpimp.exiflab.metadata.fixtures.Photos
import io.github.fishpimp.exiflab.metadata.fixtures.Photos.tag
import io.github.fishpimp.exiflab.metadata.fixtures.TiffBuilder
import io.github.fishpimp.exiflab.metadata.fixtures.Xmp
import io.github.fishpimp.exiflab.metadata.write.WriteFixtures.ascii
import io.github.fishpimp.exiflab.metadata.write.WriteFixtures.assertUntouched
import io.github.fishpimp.exiflab.metadata.write.WriteFixtures.changes
import io.github.fishpimp.exiflab.metadata.write.WriteFixtures.write
import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.random.Random
import org.junit.Assert.assertThrows
import org.junit.Test

class WebpWriterTest {
    private class Chunk(val fourCc: String, val data: ByteArray)

    private fun chunks(webp: ByteArray): List<Chunk> {
        val riffEnd = 8 + le32(webp, 4)
        assertThat(riffEnd).isAtMost(webp.size)
        val result = mutableListOf<Chunk>()
        var position = 12
        while (position + 8 <= riffEnd) {
            val size = le32(webp, position + 4)
            result += Chunk(String(webp, position, 4, Charsets.ISO_8859_1), webp.copyOfRange(position + 8, position + 8 + size))
            position += 8 + size + (size and 1)
        }
        assertThat(position).isEqualTo(riffEnd)
        return result
    }

    private fun fourCcs(webp: ByteArray) = chunks(webp).map { it.fourCc }

    private fun le32(bytes: ByteArray, at: Int) = ByteBuffer.wrap(bytes, at, 4).order(ByteOrder.LITTLE_ENDIAN).int

    private fun riff(vararg chunks: Pair<String, ByteArray>): ByteArray {
        val body = chunks.fold(ByteArray(0)) { acc, (fourCc, data) ->
            acc + fourCc.toByteArray(Charsets.ISO_8859_1) + Containers.le32(data.size) + data + if (data.size % 2 == 1) byteArrayOf(0) else ByteArray(0)
        }
        return "RIFF".toByteArray() + Containers.le32(body.size + 4) + "WEBP".toByteArray() + body
    }

    /** A lossless bitstream header of [width] x [height] with the alpha hint bit set as asked. */
    private fun vp8l(width: Int, height: Int, alpha: Boolean): ByteArray {
        val bits = (width - 1) or ((height - 1) shl 14) or ((if (alpha) 1 else 0) shl 28)
        return byteArrayOf(0x2F) + Containers.le32(bits) + Random(width).nextBytes(23)
    }

    /** A lossy key frame header of [width] x [height] followed by filler. */
    private fun vp8(width: Int, height: Int): ByteArray =
        byteArrayOf(0x50, 0x01, 0x00, 0x9D.toByte(), 0x01, 0x2A, width.toByte(), (width shr 8).toByte(), height.toByte(), (height shr 8).toByte()) +
            Random(height).nextBytes(41)

    private fun vp8xFlags(webp: ByteArray) = chunks(webp).first().data[0].toInt() and 0xFF

    private fun canvas(webp: ByteArray): Pair<Int, Int> {
        val data = chunks(webp).first().data
        fun le24(at: Int) = (data[at].toInt() and 0xFF) or ((data[at + 1].toInt() and 0xFF) shl 8) or ((data[at + 2].toInt() and 0xFF) shl 16)
        return (le24(4) + 1) to (le24(7) + 1)
    }

    private val tiff = TiffBuilder().build(Photos.ifd0(Photos.exifIfd()))

    @Test fun convertsSimpleLosslessToExtendedWhenMetadataIsAdded() {
        val source = riff("VP8L" to vp8l(300, 200, alpha = true))
        val written = write(
            source,
            MetadataChanges(
                exif = listOf(ascii(ExifIfd.Primary, Photos.ARTIST, "Jane Doe")),
                xmp = listOf(XmpChange.SetProperty(JpegWriterTest.XMP_NS, "xmp", "Rating", "3")),
            ),
        )
        assertThat(fourCcs(written.bytes)).containsExactly("VP8X", "VP8L", "EXIF", "XMP ").inOrder()
        assertThat(vp8xFlags(written.bytes)).isEqualTo(0x10 or 0x08 or 0x04)
        assertThat(canvas(written.bytes)).isEqualTo(300 to 200)
        val after = Photos.read(written.bytes)
        assertThat(after.tag("exif-ifd0", "Artist").rawValue).isEqualTo("Jane Doe")
        assertThat(after.tag("xmp", "xmp:Rating").rawValue).isEqualTo("3")
        assertThat(chunks(written.bytes)[1].data).isEqualTo(chunks(source)[0].data)
    }

    @Test fun convertsSimpleLossyToExtended() {
        val source = riff("VP8 " to vp8(640, 480))
        val written = write(source, changes(ascii(ExifIfd.Primary, Photos.MAKE, "Maker")))
        assertThat(fourCcs(written.bytes)).containsExactly("VP8X", "VP8 ", "EXIF").inOrder()
        assertThat(vp8xFlags(written.bytes)).isEqualTo(0x08)
        assertThat(canvas(written.bytes)).isEqualTo(640 to 480)
        // Odd-sized chunks keep their pad byte.
        assertThat(written.bytes.size % 2).isEqualTo(0)
    }

    @Test fun leavesFilesAloneWhenNothingChanges() {
        val source = riff("VP8 " to vp8(64, 48))
        val written = write(source, MetadataChanges(removeBlocks = setOf(MetadataBlock.Exif, MetadataBlock.Xmp)))
        assertThat(written.bytes).isEqualTo(source)
        // Inconsistent VP8X flags are only corrected when metadata chunks are rewritten.
        val extended = Containers.webp(32, 16, exif = tiff).also { it[20] = 0x0C }
        assertThat(write(extended, MetadataChanges(iptc = listOf(IptcChange.Set(2, 120, listOf("x"))))).bytes).isEqualTo(extended)
        assertThat(vp8xFlags(write(extended, changes(ascii(ExifIfd.Primary, Photos.MAKE, "M"))).bytes)).isEqualTo(0x08)
    }

    @Test fun editsAndRemovesChunksInExtendedFilesKeepingFlagsInStep() {
        val xmp = Xmp.packet(Xmp.description(mapOf("xmp:Rating" to "1")))
        val source = Containers.webp(32, 16, exif = tiff, xmp = xmp)
        val before = Photos.read(source)
        val edited = write(source, changes(ascii(ExifIfd.Primary, Photos.ARTIST, "Jane Doe")))
        assertThat(fourCcs(edited.bytes)).containsExactly("VP8X", "VP8L", "EXIF", "XMP ").inOrder()
        assertThat(chunks(edited.bytes)[3].data).isEqualTo(chunks(source)[3].data)
        assertUntouched(before, Photos.read(edited.bytes), setOf("exif-ifd0:${Photos.ARTIST}"))

        val noXmp = write(source, MetadataChanges(removeBlocks = setOf(MetadataBlock.Xmp)))
        assertThat(fourCcs(noXmp.bytes)).containsExactly("VP8X", "VP8L", "EXIF").inOrder()
        assertThat(vp8xFlags(noXmp.bytes)).isEqualTo(0x08)
        assertThat(canvas(noXmp.bytes)).isEqualTo(32 to 16)

        val nothing = write(source, MetadataChanges(removeBlocks = setOf(MetadataBlock.Xmp, MetadataBlock.Exif)))
        assertThat(fourCcs(nothing.bytes)).containsExactly("VP8X", "VP8L").inOrder()
        assertThat(vp8xFlags(nothing.bytes)).isEqualTo(0)
    }

    @Test fun removesIccProfiles() {
        val vp8x = byteArrayOf(0x20, 0, 0, 0) + byteArrayOf(31, 0, 0, 15, 0, 0)
        val source = riff("VP8X" to vp8x, "ICCP" to ByteArray(128) { 3 }, "VP8L" to vp8l(32, 16, alpha = false))
        val written = write(source, MetadataChanges(removeBlocks = setOf(MetadataBlock.IccProfile)))
        assertThat(fourCcs(written.bytes)).containsExactly("VP8X", "VP8L").inOrder()
        assertThat(vp8xFlags(written.bytes)).isEqualTo(0)
    }

    @Test fun placesMetadataAfterAnimationFrames() {
        val vp8x = byteArrayOf(0x02, 0, 0, 0) + byteArrayOf(31, 0, 0, 15, 0, 0)
        val frame = ByteArray(16) + "VP8L".toByteArray() + Containers.le32(28) + vp8l(32, 16, alpha = false)
        val source = riff("VP8X" to vp8x, "ANIM" to ByteArray(6), "ANMF" to frame, "ANMF" to frame, "UNKN" to byteArrayOf(1, 2, 3))
        val written = write(
            source,
            MetadataChanges(
                exif = listOf(ascii(ExifIfd.Primary, Photos.MAKE, "Maker")),
                xmp = listOf(XmpChange.SetProperty(JpegWriterTest.XMP_NS, "xmp", "Rating", "2")),
            ),
        )
        assertThat(fourCcs(written.bytes)).containsExactly("VP8X", "ANIM", "ANMF", "ANMF", "EXIF", "XMP ", "UNKN").inOrder()
        assertThat(vp8xFlags(written.bytes)).isEqualTo(0x02 or 0x08 or 0x04)
    }

    @Test fun reportsIptcAsSkippedAndKeepsTrailingData() {
        val trailer = Random(5).nextBytes(101)
        val source = Containers.webp(8, 8, exif = tiff) + trailer
        val written = write(source, MetadataChanges(iptc = listOf(IptcChange.Set(2, 120, listOf("x"))), exif = listOf(ascii(ExifIfd.Primary, Photos.MAKE, "M"))))
        assertThat(written.result.skipped.single()).contains("IPTC")
        assertThat(written.bytes.copyOfRange(written.bytes.size - trailer.size, written.bytes.size)).isEqualTo(trailer)
    }

    @Test fun digestCoversImageChunksOnly() {
        val plain = Containers.webp(32, 16)
        val withMetadata = Containers.webp(64, 64, exif = tiff, xmp = Xmp.packet())
        assertThat(WriteFixtures.digest(withMetadata)).isEqualTo(WriteFixtures.digest(plain))
        val image = chunks(plain).indexOfFirst { it.fourCc == "VP8L" }
        assertThat(image).isAtLeast(0)
        val changed = plain.copyOf().also { it[it.size - 3] = (it[it.size - 3] + 1).toByte() }
        assertThat(WriteFixtures.digest(changed)).isNotEqualTo(WriteFixtures.digest(plain))
    }

    @Test fun refusesAWriteThatWouldChangeImageData() {
        // The odd-sized image chunk lacks its pad byte and data follows the RIFF payload: whether the
        // next byte is padding or trailing data is ambiguous, and padding it would shift that data.
        val image = vp8l(32, 16, alpha = false).copyOf(27)
        val body = "VP8L".toByteArray() + Containers.le32(image.size) + image
        val source = "RIFF".toByteArray() + Containers.le32(body.size + 4) + "WEBP".toByteArray() + body + byteArrayOf(9, 8, 7)
        assertThrows(ImageDataMismatchException::class.java) {
            MetadataWriting.writer.write(ByteArrayImageSource(source), changes(ascii(ExifIfd.Primary, Photos.MAKE, "X")), ByteArrayOutputStream())
        }
    }

    @Test fun truncatedFilesFailCleanly() {
        val source = Containers.webp(32, 16, exif = tiff)
        for (length in listOf(14, 30, source.size - 1)) {
            assertThrows("length $length", CorruptImageException::class.java) {
                MetadataWriting.writer.write(ByteArrayImageSource(source.copyOf(length)), changes(ascii(ExifIfd.Primary, Photos.MAKE, "X")), ByteArrayOutputStream())
            }
        }
    }
}
