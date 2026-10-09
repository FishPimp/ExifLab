package io.github.fishpimp.exiflab.metadata

import com.google.common.truth.Truth.assertThat
import io.github.fishpimp.exiflab.metadata.fixtures.Containers
import io.github.fishpimp.exiflab.metadata.fixtures.Photos
import io.github.fishpimp.exiflab.metadata.fixtures.RawFixtures
import io.github.fishpimp.exiflab.metadata.fixtures.TiffBuilder
import java.io.ByteArrayInputStream
import javax.imageio.ImageIO
import org.junit.Test

class PreviewExtractorTest {
    private val extractor = DefaultPreviewExtractor()

    private fun extract(bytes: ByteArray, name: String, maxBytes: Int = 24 * 1024 * 1024) =
        extractor.extract(ByteArrayImageSource(bytes, name), maxBytes)

    private fun assertDecodes(preview: EmbeddedPreview, width: Int, height: Int) {
        assertThat(preview.width).isEqualTo(width)
        assertThat(preview.height).isEqualTo(height)
        val image = ImageIO.read(ByteArrayInputStream(preview.jpegBytes))
        assertThat(image.width).isEqualTo(width)
        assertThat(image.height).isEqualTo(height)
    }

    @Test fun dngPicksTheLargestDisplayableJpeg() {
        val preview = extract(RawFixtures.dng(), "raw.dng")!!
        // 640 x 480 preview beats the 160 x 120 thumbnail; the lossless raw strip is no candidate.
        assertDecodes(preview, 640, 480)
        assertThat(preview.orientation).isEqualTo(8)
    }

    @Test fun maxBytesSkipsLargerCandidates() {
        val dng = RawFixtures.dng()
        val full = extract(dng, "raw.dng")!!
        val limited = extract(dng, "raw.dng", maxBytes = full.jpegBytes.size - 1)!!
        assertDecodes(limited, 160, 120)
        assertThat(extract(dng, "raw.dng", maxBytes = 16)).isNull()
    }

    @Test fun nefStyleSubIfdWithJpegInterchangeFormat() {
        val preview = Containers.jpeg(300, 200)
        val nef = TiffBuilder.tiff(bigEndian = true) {
            long(0x00FE, 1)
            ascii(Photos.MAKE, "NIKON CORPORATION")
            short(Photos.ORIENTATION, 6)
            subIfd(0x014A, TiffBuilder.ifd { long(0x00FE, 1); short(0x0103, 6); offsetOf(0x0201, preview); long(0x0202, preview.size.toLong()) })
        }
        val extracted = extract(nef, "DSC_0001.NEF")!!
        assertDecodes(extracted, 300, 200)
        assertThat(extracted.orientation).isEqualTo(6)
    }

    @Test fun olympusMakerNotePreview() {
        val preview = Containers.jpeg(320, 240)
        // New-style Olympus MakerNote: "OLYMPUS\0II" + version, IFD at +12, offsets relative to the MakerNote.
        val cameraSettingsOffset = 12 + 2 + 12 + 4
        val previewOffset = cameraSettingsOffset + 2 + 2 * 12 + 4
        val makerNote = "OLYMPUS\u0000II".toByteArray(Charsets.ISO_8859_1) + byteArrayOf(3, 0) +
            le16(1) + entry(0x2020, 13, 1, cameraSettingsOffset) + le32(0) +
            le16(2) + entry(0x0101, 4, 1, previewOffset) + entry(0x0102, 4, 1, preview.size) + le32(0) +
            preview
        val orf = TiffBuilder(bigEndian = false, magic = 0x4F52).build(
            TiffBuilder.ifd {
                ascii(Photos.MAKE, "OLYMPUS CORPORATION")
                short(Photos.ORIENTATION, 1)
                subIfd(Photos.EXIF_IFD, TiffBuilder.ifd { undefined(Photos.MAKER_NOTE, makerNote) })
            },
        )
        assertDecodes(extract(orf, "P1010001.ORF")!!, 320, 240)
    }

    @Test fun rw2JpgFromRaw() {
        val preview = extract(RawFixtures.rw2(), "P1000001.RW2")!!
        assertDecodes(preview, 320, 240)
        assertThat(preview.orientation).isEqualTo(3)
    }

    @Test fun rafHeaderJpegWithEmbeddedOrientation() {
        val preview = extract(RawFixtures.raf(), "DSCF0001.RAF")!!
        assertDecodes(preview, 320, 213)
        assertThat(preview.orientation).isEqualTo(6)
    }

    @Test fun cr3PrefersPrvwOverThmb() {
        val preview = extract(RawFixtures.cr3(), "IMG_0001.CR3")!!
        assertDecodes(preview, 1620, 1080)
        assertThat(preview.orientation).isEqualTo(8)
    }

    @Test fun cr3FallsBackToThumbnail() {
        val cr3 = RawFixtures.cr3(preview = RawFixtures.losslessRawStrip)
        assertDecodes(extract(cr3, "IMG_0001.CR3")!!, 160, 120)
    }

    @Test fun nonRawFormatsHaveNoPreview() {
        assertThat(extract(Photos.jpeg(TiffBuilder().build(Photos.ifd0(Photos.exifIfd()))), "a.jpg")).isNull()
        assertThat(extract(Containers.png(), "a.png")).isNull()
        assertThat(extract(Containers.webp(10, 10), "a.webp")).isNull()
        assertThat(extract(ByteArray(64), "a.bin")).isNull()
    }

    @Test fun truncatedRawYieldsNoPreviewInsteadOfThrowing() {
        val dng = RawFixtures.dng()
        assertThat(extract(dng.copyOf(200), "cut.dng")).isNull()
    }

    private fun le16(value: Int) = byteArrayOf(value.toByte(), (value shr 8).toByte())
    private fun le32(value: Int) = Containers.le32(value)
    private fun entry(tag: Int, type: Int, count: Int, value: Int) = le16(tag) + le16(type) + le32(count) + le32(value)
}
