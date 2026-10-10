package io.github.fishpimp.exiflab.metadata.write

import com.google.common.truth.Truth.assertThat
import io.github.fishpimp.exiflab.metadata.ByteArrayImageSource
import io.github.fishpimp.exiflab.metadata.CorruptImageException
import io.github.fishpimp.exiflab.metadata.fixtures.Containers
import io.github.fishpimp.exiflab.metadata.fixtures.Photos
import io.github.fishpimp.exiflab.metadata.fixtures.Photos.tag
import io.github.fishpimp.exiflab.metadata.fixtures.Photos.tagOrNull
import io.github.fishpimp.exiflab.metadata.fixtures.TiffBuilder
import io.github.fishpimp.exiflab.metadata.fixtures.Xmp
import io.github.fishpimp.exiflab.metadata.write.WriteFixtures.ascii
import io.github.fishpimp.exiflab.metadata.write.WriteFixtures.assertUntouched
import io.github.fishpimp.exiflab.metadata.write.WriteFixtures.changes
import io.github.fishpimp.exiflab.metadata.write.WriteFixtures.write
import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer
import java.util.zip.CRC32
import java.util.zip.Deflater
import kotlin.random.Random
import org.junit.Assert.assertThrows
import org.junit.Test

class PngWriterTest {
    private class Chunk(val type: String, val data: ByteArray, val crcOk: Boolean)

    private fun chunks(png: ByteArray): List<Chunk> {
        val result = mutableListOf<Chunk>()
        var position = 8
        while (position + 12 <= png.size) {
            val length = ByteBuffer.wrap(png, position, 4).int
            val type = String(png, position + 4, 4, Charsets.ISO_8859_1)
            val data = png.copyOfRange(position + 8, position + 8 + length)
            val crc = ByteBuffer.wrap(png, position + 8 + length, 4).int
            val expected = CRC32().apply { update(png, position + 4, 4 + length) }.value.toInt()
            result += Chunk(type, data, crc == expected)
            position += 12 + length
            if (type == "IEND") break
        }
        return result
    }

    private fun types(png: ByteArray) = chunks(png).map { it.type }

    private val exifTiff = TiffBuilder(false).build(Photos.ifd0(Photos.exifIfd(), Photos.gpsIfd()))

    @Test fun addsExifAndXmpBeforeTheImageData() {
        val source = Containers.png()
        val written = write(
            source,
            MetadataChanges(
                exif = listOf(ascii(ExifIfd.Primary, Photos.ARTIST, "Jane Doe"), ascii(ExifIfd.Exif, Photos.DATE_TIME_ORIGINAL, "2024:01:02 03:04:05")),
                xmp = listOf(XmpChange.SetProperty(JpegWriterTest.XMP_NS, "xmp", "Rating", "4")),
            ),
        )
        assertThat(types(written.bytes)).containsExactly("IHDR", "eXIf", "iTXt", "IDAT", "IEND").inOrder()
        assertThat(chunks(written.bytes).all { it.crcOk }).isTrue()
        val after = Photos.read(written.bytes)
        assertThat(after.tag("exif-ifd0", "Artist").rawValue).isEqualTo("Jane Doe")
        assertThat(after.tag("exif-subifd", "Date/Time Original").rawValue).isEqualTo("2024:01:02 03:04:05")
        assertThat(after.tag("xmp", "xmp:Rating").rawValue).isEqualTo("4")
        // A PNG eXIf block gets no JPEG-only defaults.
        assertThat(after.tagOrNull("exif-ifd0", "YCbCr Positioning")).isNull()
    }

    @Test fun editsExistingExifAndMovesItBeforeIdat() {
        val png = Containers.png()
        val iend = png.size - 12
        val source = png.copyOfRange(0, iend) + Containers.pngChunk("eXIf", exifTiff) + png.copyOfRange(iend, png.size)
        assertThat(types(source).indexOf("eXIf")).isGreaterThan(types(source).indexOf("IDAT"))
        val written = write(source, changes(ascii(ExifIfd.Primary, Photos.ARTIST, "Jane Doe"), ExifChange.Remove(ExifIfd.Gps, 0x0000)))
        val order = types(written.bytes)
        assertThat(order.count { it == "eXIf" }).isEqualTo(1)
        assertThat(order.indexOf("eXIf")).isLessThan(order.indexOf("IDAT"))
        val before = Photos.read(source)
        val after = Photos.read(written.bytes)
        assertThat(after.tag("exif-ifd0", "Artist").rawValue).isEqualTo("Jane Doe")
        assertUntouched(before, after, setOf("exif-ifd0:${Photos.ARTIST}", "gps:0"))
    }

    @Test fun rewritesXmpIncludingCompressedPackets() {
        val packet = Xmp.packet(Xmp.description(mapOf("xmp:Rating" to "2", "xmp:Label" to "Blue")))
        val deflater = Deflater().apply { setInput(packet.toByteArray()); finish() }
        val buffer = ByteArray(65536)
        val compressed = buffer.copyOf(deflater.deflate(buffer))
        val itxt = Containers.pngChunk("iTXt", "XML:com.adobe.xmp".toByteArray() + byteArrayOf(0, 1, 0, 0, 0) + compressed)
        val source = Containers.pngWithChunks(Containers.png(), itxt)
        val written = write(source, MetadataChanges(xmp = listOf(XmpChange.SetProperty(JpegWriterTest.XMP_NS, "xmp", "Rating", "5"))))
        val after = Photos.read(written.bytes)
        assertThat(after.tag("xmp", "xmp:Rating").rawValue).isEqualTo("5")
        assertThat(after.tag("xmp", "xmp:Label").rawValue).isEqualTo("Blue")
        assertThat(types(written.bytes).count { it == "iTXt" }).isEqualTo(1)
    }

    @Test fun removesTextChunksProfilesAndMetadataBlocks() {
        val source = Containers.pngWithChunks(
            Containers.png(),
            Containers.pngChunk("iCCP", "ICC\u0000\u0000".toByteArray() + byteArrayOf(0x78, 0x9C.toByte(), 3, 0, 0, 0, 0, 1)),
            Containers.pngTextChunk("Comment", "hello"),
            Containers.pngChunk("zTXt", "Title\u0000\u0000".toByteArray() + byteArrayOf(0x78, 0x9C.toByte(), 3, 0, 0, 0, 0, 1)),
            Containers.pngXmpChunk(Xmp.packet(Xmp.description(mapOf("xmp:Rating" to "3")))),
            Containers.pngChunk("eXIf", exifTiff),
            Containers.pngTextChunk("Raw profile type exif", "exif\n 4\n00000000"),
        )
        assertThat(types(write(source, MetadataChanges(removeBlocks = setOf(MetadataBlock.Comments))).bytes))
            .containsExactly("IHDR", "iCCP", "iTXt", "eXIf", "IDAT", "IEND").inOrder()
        assertThat(types(write(source, MetadataChanges(removeBlocks = setOf(MetadataBlock.IccProfile))).bytes)).doesNotContain("iCCP")
        val noExif = types(write(source, MetadataChanges(removeBlocks = setOf(MetadataBlock.Exif))).bytes)
        assertThat(noExif).doesNotContain("eXIf")
        assertThat(noExif.count { it == "tEXt" }).isEqualTo(1)
        val noXmp = write(source, MetadataChanges(removeBlocks = setOf(MetadataBlock.Xmp)))
        assertThat(Photos.read(noXmp.bytes).directories.map { it.id }).doesNotContain("xmp")
        assertThat(types(noXmp.bytes).count { it == "iTXt" }).isEqualTo(0)
    }

    @Test fun keepsApngFramesAndPutsMetadataBeforeTheFirstFrameControl() {
        val png = Containers.png()
        val ihdrEnd = 8 + 12 + 13
        val iendStart = png.size - 12
        fun frameControl(sequence: Int) = Containers.pngChunk("fcTL", Containers.be32(sequence) + ByteArray(22))
        val apng = png.copyOfRange(0, ihdrEnd) + Containers.pngChunk("acTL", Containers.be32(2) + Containers.be32(0)) + frameControl(0) +
            png.copyOfRange(ihdrEnd, iendStart) + frameControl(1) + Containers.pngChunk("fdAT", Containers.be32(2) + ByteArray(10) { 5 }) +
            png.copyOfRange(iendStart, png.size)
        val written = write(apng, changes(ascii(ExifIfd.Primary, Photos.MAKE, "Maker")))
        val order = types(written.bytes)
        assertThat(order.indexOf("eXIf")).isEqualTo(order.indexOf("fcTL") - 1)
        assertThat(order.filter { it != "eXIf" }).isEqualTo(types(apng))
        assertThat(write(apng, MetadataChanges()).bytes).isEqualTo(apng)
        // Frame data is image data.
        val changedFrame = apng.copyOf().also { it[it.size - 12 - 4 - 3] = 6 }
        assertThat(WriteFixtures.digest(changedFrame)).isNotEqualTo(WriteFixtures.digest(apng))
    }

    @Test fun reportsIptcAsSkipped() {
        val written = write(Containers.png(), MetadataChanges(iptc = listOf(IptcChange.Set(2, 120, listOf("Caption")))))
        assertThat(written.result.skipped.single()).contains("IPTC")
        assertThat(written.bytes).isEqualTo(Containers.png())
    }

    @Test fun preservesBytesAfterIendAndImageChunks() {
        val trailer = Random(3).nextBytes(777)
        val source = Containers.png() + trailer
        val written = write(source, changes(ascii(ExifIfd.Primary, Photos.MAKE, "Maker")))
        assertThat(written.bytes.copyOfRange(written.bytes.size - trailer.size, written.bytes.size)).isEqualTo(trailer)
        val idat = { png: ByteArray -> chunks(png).filter { it.type == "IDAT" }.map { it.data.toList() } }
        assertThat(idat(written.bytes)).isEqualTo(idat(source))
    }

    @Test fun digestSeesImageChunksOnly() {
        val source = Containers.png()
        val withText = Containers.pngWithChunks(source, Containers.pngTextChunk("Comment", "x"))
        assertThat(WriteFixtures.digest(withText)).isEqualTo(WriteFixtures.digest(source))
        val idat = chunks(source).indexOfFirst { it.type == "IDAT" }
        assertThat(idat).isAtLeast(0)
        val changed = source.copyOf().also { it[it.size - 20] = (it[it.size - 20] + 1).toByte() }
        assertThat(WriteFixtures.digest(changed)).isNotEqualTo(WriteFixtures.digest(source))
    }

    @Test fun truncatedFilesFailCleanly() {
        val source = Containers.pngWithChunks(Containers.png(), Containers.pngTextChunk("Comment", "hello"))
        for (length in listOf(12, 40, source.size - 30, source.size - 1)) {
            assertThrows("length $length", CorruptImageException::class.java) {
                MetadataWriting.writer.write(ByteArrayImageSource(source.copyOf(length)), changes(ascii(ExifIfd.Primary, Photos.MAKE, "X")), ByteArrayOutputStream())
            }
        }
    }
}
