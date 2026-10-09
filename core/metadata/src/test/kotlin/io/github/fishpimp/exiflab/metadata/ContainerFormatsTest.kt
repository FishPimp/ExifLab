package io.github.fishpimp.exiflab.metadata

import com.google.common.truth.Truth.assertThat
import io.github.fishpimp.exiflab.metadata.fixtures.Containers
import io.github.fishpimp.exiflab.metadata.fixtures.Photos
import io.github.fishpimp.exiflab.metadata.fixtures.Photos.directory
import io.github.fishpimp.exiflab.metadata.fixtures.Photos.tag
import io.github.fishpimp.exiflab.metadata.fixtures.RawFixtures
import io.github.fishpimp.exiflab.metadata.fixtures.TiffBuilder
import io.github.fishpimp.exiflab.metadata.fixtures.Xmp
import io.github.fishpimp.exiflab.metadata.model.DirectoryGroup
import io.github.fishpimp.exiflab.metadata.model.LocationStatus
import io.github.fishpimp.exiflab.metadata.model.OffsetSource
import io.github.fishpimp.exiflab.metadata.model.Rational
import io.github.fishpimp.exiflab.metadata.model.SensitivityCategory
import java.time.LocalDateTime
import java.time.ZoneOffset
import org.junit.Test

class ContainerFormatsTest {
    private val exifTiff = TiffBuilder().build(Photos.ifd0(Photos.exifIfd()))
    private val xmp = Xmp.packet(Xmp.description(mapOf("xmp:Rating" to "4", "photoshop:City" to "Stockholm")))

    @Test fun pngWithExifXmpAndText() {
        val png = Containers.pngWithChunks(
            Containers.png(32, 24),
            Containers.pngChunk("eXIf", exifTiff),
            Containers.pngXmpChunk(xmp),
            Containers.pngTextChunk("Author", "Jane Doe"),
        )
        val report = Photos.read(png, "screenshot.png")
        assertThat(report.format).isEqualTo(ImageFormat.Png)
        assertThat(report.summary.width).isEqualTo(32)
        assertThat(report.summary.height).isEqualTo(24)
        assertThat(report.summary.make).isEqualTo("Google")
        assertThat(report.directories.map { it.id }).containsAtLeast("exif-ifd0", "exif-subifd", "xmp", "png-ihdr", "png-text", "file").inOrder()
        assertThat(report.tag("xmp", "photoshop:City").sensitivity).isEqualTo(SensitivityCategory.Location)
        val author = report.tag("png-text", "Author")
        assertThat(author.displayValue).isEqualTo("Jane Doe")
        assertThat(author.id).isNull()
        assertThat(author.sensitivity).isEqualTo(SensitivityCategory.PersonName)
        assertThat(report.directory("png-ihdr").group).isEqualTo(DirectoryGroup.Container)
    }

    @Test fun pngCreationTimeIsAContainerDate() {
        val png = Containers.pngWithChunks(Containers.png(), Containers.pngTextChunk("Creation Time", "2021:03:04 05:06:07"))
        val summary = Photos.read(png).summary
        assertThat(summary.capturedAt).isEqualTo(LocalDateTime.of(2021, 3, 4, 5, 6, 7))
        assertThat(summary.utcOffset).isNull()
    }

    @Test fun webpWithExifAndXmp() {
        val report = Photos.read(Containers.webp(800, 600, exif = exifTiff, xmp = xmp), "image.webp")
        assertThat(report.format).isEqualTo(ImageFormat.WebP)
        assertThat(report.summary.width).isEqualTo(800)
        assertThat(report.summary.height).isEqualTo(600)
        assertThat(report.summary.model).isEqualTo("Pixel 8")
        assertThat(report.directories.map { it.id }).containsAtLeast("exif-ifd0", "exif-subifd", "xmp", "webp").inOrder()
    }

    @Test fun heifPrimaryItemExifAndXmp() {
        val report = Photos.read(RawFixtures.heif(exifTiff, xmp), "IMG_0001.HEIC")
        assertThat(report.format).isEqualTo(ImageFormat.Heif)
        // The first ispe is a 512 x 512 tile; the primary item is the 4032 x 3024 grid.
        assertThat(report.summary.width).isEqualTo(4032)
        assertThat(report.summary.height).isEqualTo(3024)
        assertThat(report.summary.make).isEqualTo("Google")
        assertThat(report.summary.capturedAt).isEqualTo(LocalDateTime.of(2024, 5, 1, 14, 3, 22, 123_000_000))
        assertThat(report.directories.map { it.id }).containsAtLeast("exif-ifd0", "exif-subifd", "xmp", "heif").inOrder()
        assertThat(report.tag("xmp", "xmp:Rating").displayValue).isEqualTo("4")
    }

    @Test fun rafReadsHeaderAndEmbeddedJpeg() {
        val report = Photos.read(RawFixtures.raf(), "DSCF0001.RAF")
        assertThat(report.format).isEqualTo(ImageFormat.Raf)
        assertThat(report.summary.make).isEqualTo("FUJIFILM")
        assertThat(report.summary.model).isEqualTo("X-T3")
        // Raw cropped size, not the 320 x 213 embedded preview.
        assertThat(report.summary.width).isEqualTo(6240)
        assertThat(report.summary.height).isEqualTo(4160)
        val raf = report.directory("raf")
        assertThat(raf.group).isEqualTo(DirectoryGroup.Container)
        assertThat(report.tag("raf", "Raw Image Cropped Size").displayValue).isEqualTo("6240 x 4160")
        assertThat(report.tag("raf", "Raw Image Cropped Size").rawValue).isEqualTo("4160 6240")
        assertThat(report.tag("raf", "Camera Model").displayValue).isEqualTo("X-T3")
        assertThat(report.warnings).isEmpty()
    }

    @Test fun cr3MetadataBoxes() {
        val report = Photos.read(RawFixtures.cr3(), "IMG_0001.CR3")
        assertThat(report.format).isEqualTo(ImageFormat.Cr3)
        assertThat(report.directories.map { it.id }).containsAtLeast("exif-ifd0", "exif-subifd", "gps", "makernote-canon", "xmp", "cr3").inOrder()
        val summary = report.summary
        assertThat(summary.make).isEqualTo("Canon")
        assertThat(summary.model).isEqualTo("Canon EOS R6")
        assertThat(summary.lens).isEqualTo("RF50mm F1.8 STM")
        assertThat(summary.exposureTime).isEqualTo(Rational(1, 500))
        assertThat(summary.fNumber).isEqualTo(4.0)
        assertThat(summary.iso).isEqualTo(800)
        assertThat(summary.orientation).isEqualTo(8)
        assertThat(summary.width).isEqualTo(5472)
        assertThat(summary.height).isEqualTo(3648)
        assertThat(summary.utcOffset).isEqualTo(ZoneOffset.ofHours(1))
        assertThat(summary.utcOffsetSource).isEqualTo(OffsetSource.ExifOffsetTime)
        assertThat(report.locationStatus).isEqualTo(LocationStatus.Present)
        assertThat(report.location!!.latitude).isWithin(1e-9).of(48 + 51 / 60.0 + 30 / 3600.0)
        assertThat(report.tag("makernote-canon", "Owner Name").sensitivity).isEqualTo(SensitivityCategory.PersonName)
        assertThat(report.tag("cr3", "Compressor Version").displayValue).isEqualTo("CanonCR3_001/01.09.00/00.00.00")
        assertThat(report.tag("cr3", "Major Brand").displayValue).isEqualTo("crx")
    }

    @Test fun dngLikeTiffSeparatesImageSubIfds() {
        val report = Photos.read(RawFixtures.dng(), "PXL_RAW.dng")
        assertThat(report.format).isEqualTo(ImageFormat.Dng)
        assertThat(report.directories.map { it.id }).containsAtLeast("exif-ifd0", "exif-subifd", "image-subifd", "image-subifd-2").inOrder()
        assertThat(report.directory("image-subifd").name).isEqualTo("Image SubIFD")
        // Developed size from the raw IFD's DefaultCropSize, not the thumbnail IFD0.
        assertThat(report.summary.width).isEqualTo(5984)
        assertThat(report.summary.height).isEqualTo(3992)
        assertThat(report.summary.model).isEqualTo("Pixel 8 Pro")
        assertThat(report.summary.exposureTime).isEqualTo(Rational(1, 250))
    }

    @Test fun bigEndianTiffWorksToo() {
        val report = Photos.read(RawFixtures.dng(bigEndian = true), "scan.dng")
        assertThat(report.summary.width).isEqualTo(5984)
        assertThat(report.summary.iso).isEqualTo(400)
    }

    @Test fun rw2SensorAreaAndEmbeddedExif() {
        val report = Photos.read(RawFixtures.rw2(), "P1000001.RW2")
        assertThat(report.format).isEqualTo(ImageFormat.Rw2)
        assertThat(report.summary.width).isEqualTo(5184)
        assertThat(report.summary.height).isEqualTo(3888)
        assertThat(report.summary.make).isEqualTo("Panasonic")
        assertThat(report.directories.map { it.id }).containsAtLeast("exif-ifd0", "exif-subifd", "panasonic-raw-ifd0")
    }
}
