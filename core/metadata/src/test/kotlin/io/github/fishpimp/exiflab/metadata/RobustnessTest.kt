package io.github.fishpimp.exiflab.metadata

import com.google.common.truth.Truth.assertThat
import io.github.fishpimp.exiflab.metadata.fixtures.Containers
import io.github.fishpimp.exiflab.metadata.fixtures.Photos
import io.github.fishpimp.exiflab.metadata.fixtures.RawFixtures
import io.github.fishpimp.exiflab.metadata.fixtures.TiffBuilder
import io.github.fishpimp.exiflab.metadata.fixtures.Xmp
import java.io.ByteArrayInputStream
import java.io.FilterInputStream
import java.io.IOException
import java.io.InputStream
import org.junit.Assert.assertThrows
import org.junit.Test

class RobustnessTest {
    private val exifTiff = TiffBuilder().build(Photos.ifd0(Photos.exifIfd()))

    @Test fun truncatedJpegKeepsCompleteSegments() {
        val xmp = Containers.xmpSegment(Xmp.packet(Xmp.description(mapOf("xmp:Rating" to "4"))))
        val jpeg = Photos.jpeg(exifTiff, xmp)
        // Cut inside the XMP segment, which follows the Exif segment.
        val exifEnd = 2 + 4 + 6 + exifTiff.size
        val report = Photos.read(jpeg.copyOf(exifEnd + 40))
        assertThat(report.summary.make).isEqualTo("Google")
        assertThat(report.directories.map { it.id }).contains("exif-subifd")
        assertThat(report.warnings).isNotEmpty()
    }

    @Test fun truncatedExifBlockIsReportedNotThrown() {
        val jpeg = Photos.jpeg(exifTiff.copyOf(exifTiff.size / 2))
        val report = Photos.read(jpeg)
        assertThat(report.summary.make).isEqualTo("Google")
        assertThat(report.warnings).isNotEmpty()
    }

    @Test fun truncatedTiffRawKeepsWhatWasRead() {
        val dng = RawFixtures.dng()
        val report = Photos.read(dng.copyOf(dng.size - 2000), "cut.dng")
        assertThat(report.summary.model).isEqualTo("Pixel 8 Pro")
        assertThat(report.warnings).isNotEmpty()
    }

    @Test fun truncatedPngKeepsEarlierChunks() {
        val png = Containers.pngWithChunks(Containers.png(32, 24), Containers.pngChunk("eXIf", exifTiff))
        val report = Photos.read(png.copyOf(png.size - 20))
        assertThat(report.summary.make).isEqualTo("Google")
        assertThat(report.summary.width).isEqualTo(32)
        assertThat(report.warnings).isNotEmpty()
    }

    @Test fun truncatedCr3KeepsEarlierBoxes() {
        val cr3 = RawFixtures.cr3()
        val report = Photos.read(cr3.copyOf(cr3.size / 2), "cut.cr3")
        assertThat(report.summary.make).isEqualTo("Canon")
    }

    @Test fun unknownFormatIsUnsupported() {
        assertThrows(UnsupportedImageException::class.java) { Photos.read("GIF89a not supported here".toByteArray(), "a.gif") }
    }

    @Test fun emptyFileIsCorrupt() {
        assertThrows(CorruptImageException::class.java) { Photos.read(ByteArray(0)) }
    }

    @Test fun jpegWithoutAnyStructureIsCorrupt() {
        assertThrows(CorruptImageException::class.java) { Photos.read(byteArrayOf(0xFF.toByte(), 0xD8.toByte(), 0xFF.toByte(), 0x00)) }
    }

    @Test fun sourceFailuresPropagateUnchanged() {
        val failing = object : ImageSource {
            override val fileName = "gone.jpg"
            override val length: Long? = null
            override fun open(): InputStream = throw IOException("Permission revoked")
        }
        val error = assertThrows(IOException::class.java) { DefaultMetadataReader().read(failing) }
        assertThat(error).isNotInstanceOf(ImageReadException::class.java)
        assertThat(error).hasMessageThat().isEqualTo("Permission revoked")
        assertThrows(IOException::class.java) { DefaultPreviewExtractor().extract(failing) }
    }

    @Test fun largeTiffIsNotReadWhole() {
        // IFD0 sits behind 32 MiB of image data; only the header and the IFD should be read.
        val tiff = TiffBuilder(bigEndian = false, ifd0Padding = 32 * 1024 * 1024)
            .build(TiffBuilder.ifd { ascii(Photos.MAKE, "Big"); short(Photos.ORIENTATION, 1) })
        val source = CountingSource(tiff, "big.tif")
        val report = DefaultMetadataReader().read(source)
        assertThat(report.summary.make).isEqualTo("Big")
        assertThat(source.bytesRead).isLessThan(1024L * 1024)
    }

    private class CountingSource(private val bytes: ByteArray, override val fileName: String) : ImageSource {
        var bytesRead = 0L
        override val length: Long get() = bytes.size.toLong()
        override fun open(): InputStream = object : FilterInputStream(ByteArrayInputStream(bytes)) {
            override fun read(): Int = super.read().also { if (it >= 0) bytesRead++ }
            override fun read(b: ByteArray, off: Int, len: Int): Int = super.read(b, off, len).also { if (it > 0) bytesRead += it }
        }
    }
}
