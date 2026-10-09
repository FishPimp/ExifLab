package io.github.fishpimp.exiflab.metadata

import com.google.common.truth.Truth.assertThat
import io.github.fishpimp.exiflab.metadata.fixtures.Containers
import io.github.fishpimp.exiflab.metadata.fixtures.Photos
import io.github.fishpimp.exiflab.metadata.fixtures.Photos.directory
import io.github.fishpimp.exiflab.metadata.fixtures.Photos.tag
import io.github.fishpimp.exiflab.metadata.fixtures.TiffBuilder
import io.github.fishpimp.exiflab.metadata.fixtures.Xmp
import io.github.fishpimp.exiflab.metadata.model.DirectoryGroup
import io.github.fishpimp.exiflab.metadata.model.OffsetSource
import io.github.fishpimp.exiflab.metadata.model.Rational
import io.github.fishpimp.exiflab.metadata.model.SensitivityCategory
import java.awt.color.ColorSpace
import java.awt.color.ICC_Profile
import java.time.LocalDateTime
import java.time.ZoneOffset
import org.junit.Test

class JpegReportTest {
    private val phoneJpeg = Photos.jpeg(TiffBuilder().build(Photos.ifd0(Photos.exifIfd())))

    @Test fun summaryFromExif() {
        val summary = Photos.read(phoneJpeg, "PXL_20240501.jpg").summary
        assertThat(summary.make).isEqualTo("Google")
        assertThat(summary.model).isEqualTo("Pixel 8")
        assertThat(summary.lens).isEqualTo("Pixel 8 back camera 6.9mm f/1.68")
        assertThat(summary.fNumber).isEqualTo(1.8)
        assertThat(summary.exposureTime).isEqualTo(Rational(1, 250))
        assertThat(summary.iso).isEqualTo(400)
        assertThat(summary.focalLengthMm).isEqualTo(6.9)
        assertThat(summary.focalLength35mm).isEqualTo(26)
        assertThat(summary.exposureBiasEv).isWithin(1e-9).of(-0.7)
        assertThat(summary.flashFired).isTrue()
        assertThat(summary.orientation).isEqualTo(6)
        assertThat(summary.software).isEqualTo("HDR+ 1.0.612345")
        assertThat(summary.capturedAt).isEqualTo(LocalDateTime.of(2024, 5, 1, 14, 3, 22, 123_000_000))
        assertThat(summary.utcOffset).isEqualTo(ZoneOffset.ofHours(2))
        assertThat(summary.utcOffsetSource).isEqualTo(OffsetSource.ExifOffsetTime)
    }

    @Test fun dimensionsComeFromTheFrameHeaderNotStaleExif() {
        // Exif claims 4000 x 3000; the actual JPEG is 64 x 48.
        val summary = Photos.read(phoneJpeg).summary
        assertThat(summary.width).isEqualTo(64)
        assertThat(summary.height).isEqualTo(48)
    }

    @Test fun rawAndDisplayValues() {
        val report = Photos.read(phoneJpeg)
        val exposure = report.tag("exif-subifd", "Exposure Time")
        assertThat(exposure.rawValue).isEqualTo("1/250")
        assertThat(exposure.displayValue).isEqualTo("1/250 sec")
        assertThat(exposure.id).isEqualTo(0x829A)
        assertThat(exposure.hexId).isEqualTo("0x829A")
        assertThat(exposure.key).isEqualTo("exif-subifd:${0x829A}")
        val fNumber = report.tag("exif-subifd", "F-Number")
        assertThat(fNumber.rawValue).isEqualTo("18/10")
        assertThat(fNumber.displayValue).isEqualTo("f/1.8")
        val version = report.tag("exif-subifd", "Exif Version")
        assertThat(version.rawValue).isEqualTo("30 32 33 32")
        assertThat(version.displayValue).isEqualTo("2.32")
        assertThat(report.tag("exif-ifd0", "Orientation").displayValue).isEqualTo("Right side, top (Rotate 90 CW)")
    }

    @Test fun directoriesAreOrderedWithStableIds() {
        val thumbnail = Containers.jpeg(16, 12)
        val makerNote = TiffBuilder.ifd { ascii(0x0006, "Canon EOS R5"); ascii(0x0009, "Jane Doe") }
        val ifd0 = Photos.ifd0(
            exif = Photos.exifIfd {
                subIfd(Photos.INTEROP_IFD, TiffBuilder.ifd { ascii(0x0001, "R98") })
                makerNoteIfd(Photos.MAKER_NOTE, makerNote)
            },
            gps = Photos.gpsIfd(),
        ) { ascii(Photos.MAKE, "Canon") }
        ifd0.next = TiffBuilder.ifd {
            short(0x0103, 6)
            offsetOf(0x0201, thumbnail)
            long(0x0202, thumbnail.size.toLong())
        }
        val jpeg = Photos.jpeg(
            TiffBuilder().build(ifd0),
            Containers.xmpSegment(Xmp.packet(Xmp.description(mapOf("xmp:Rating" to "4")))),
            Containers.iptcSegment(0x78 to "A caption"),
            Containers.iccSegment(ICC_Profile.getInstance(ColorSpace.CS_sRGB).data),
        )
        val report = Photos.read(jpeg)
        assertThat(report.directories.map { it.id }).containsAtLeast(
            "exif-ifd0", "exif-subifd", "exif-interop", "exif-thumbnail", "gps", "makernote-canon",
            "xmp", "iptc", "icc", "jpeg", "jfif", "file",
        ).inOrder()
        // The Photoshop resource block only carried IPTC, so it has no tags of its own.
        assertThat(report.directories.map { it.id }).doesNotContain("photoshop")
        assertThat(report.directories.map { it.group.ordinal }).isInOrder()
        assertThat(report.directory("makernote-canon").group).isEqualTo(DirectoryGroup.MakerNote)
        assertThat(report.directory("makernote-canon").name).isEqualTo("Canon Makernote")
        assertThat(report.directory("exif-ifd0").name).isEqualTo("Exif IFD0")
        assertThat(report.directories.flatMap { it.tags }.map { it.key }).containsNoDuplicates()
        assertThat(report.summary.colorProfile).isEqualTo("sRGB built-in")
        assertThat(report.tag("iptc", "Caption/Abstract").displayValue).isEqualTo("A caption")
    }

    @Test fun repeatedDirectoriesGetNumberedIds() {
        val first = Xmp.packet(Xmp.description(mapOf("xmp:Rating" to "4")))
        val second = Xmp.packet(Xmp.description(mapOf("xmp:Label" to "Red")))
        val report = Photos.read(Photos.jpeg(null, Containers.xmpSegment(first), Containers.xmpSegment(second)))
        assertThat(report.directories.map { it.id }).containsAtLeast("xmp", "xmp-2").inOrder()
    }

    @Test fun xmpPropertiesAreEnumeratedByPath() {
        val xmp = Xmp.packet(
            Xmp.description(
                attributes = mapOf("exif:GPSLatitude" to "59,19.758N", "xmp:Rating" to "5"),
                elements = "<dc:creator><rdf:Seq><rdf:li>Jane Doe</rdf:li><rdf:li>John Roe</rdf:li></rdf:Seq></dc:creator>" +
                    "<dc:title><rdf:Alt><rdf:li xml:lang=\"x-default\">Harbour</rdf:li></rdf:Alt></dc:title>",
            ),
        )
        val tags = Photos.read(Photos.jpeg(null, Containers.xmpSegment(xmp))).directory("xmp").tags
        val byName = tags.associateBy { it.name }
        assertThat(byName.keys).containsAtLeast("dc:creator[1]", "dc:creator[2]", "dc:title[1]", "exif:GPSLatitude", "xmp:Rating")
        assertThat(byName.getValue("dc:creator[2]").displayValue).isEqualTo("John Roe")
        assertThat(byName.getValue("dc:creator[1]").rawValue).isEqualTo("Jane Doe")
        assertThat(byName.getValue("dc:title[1]").displayValue).isEqualTo("Harbour")
        assertThat(byName.getValue("exif:GPSLatitude").key).isEqualTo("xmp:exif:GPSLatitude")
        assertThat(tags.map { it.id }.distinct()).containsExactly(null)
    }

    @Test fun sensitiveTagsAreClassifiedPerCategory() {
        val xmp = Xmp.packet(
            Xmp.description(
                attributes = mapOf("xmpMM:DocumentID" to "xmp.did:1234", "aux:SerialNumber" to "0123456"),
                elements = "<dc:creator><rdf:Seq><rdf:li>Jane Doe</rdf:li></rdf:Seq></dc:creator>" +
                    "<mwg-rs:Regions rdf:parseType=\"Resource\"><mwg-rs:RegionList><rdf:Bag><rdf:li rdf:parseType=\"Resource\">" +
                    "<mwg-rs:Name>Jane Doe</mwg-rs:Name><mwg-rs:Type>Face</mwg-rs:Type></rdf:li></rdf:Bag></mwg-rs:RegionList></mwg-rs:Regions>" +
                    "<xmp:CreatorTool>Camera app</xmp:CreatorTool>",
            ),
        )
        val ifd0 = Photos.ifd0(Photos.exifIfd { ascii(Photos.BODY_SERIAL, "SN-998877"); ascii(Photos.IMAGE_UNIQUE_ID, "A1B2C3D4") }, Photos.gpsIfd()) {
            ascii(Photos.ARTIST, "Jane Doe")
            ascii(Photos.COPYRIGHT, "(c) Jane Doe")
        }
        val report = Photos.read(
            Photos.jpeg(TiffBuilder().build(ifd0), Containers.xmpSegment(xmp), Containers.iptcSegment(0x5A to "Stockholm", 0x50 to "Jane Doe")),
        )
        fun category(directoryId: String, name: String) = report.tag(directoryId, name).sensitivity

        assertThat(category("gps", "GPS Latitude")).isEqualTo(SensitivityCategory.Location)
        assertThat(category("gps", "GPS Version ID")).isEqualTo(SensitivityCategory.Location)
        assertThat(category("iptc", "City")).isEqualTo(SensitivityCategory.Location)
        assertThat(category("exif-subifd", "Body Serial Number")).isEqualTo(SensitivityCategory.SerialNumber)
        assertThat(category("xmp", "aux:SerialNumber")).isEqualTo(SensitivityCategory.SerialNumber)
        assertThat(category("exif-ifd0", "Artist")).isEqualTo(SensitivityCategory.PersonName)
        assertThat(category("exif-ifd0", "Copyright")).isEqualTo(SensitivityCategory.PersonName)
        assertThat(category("iptc", "By-line")).isEqualTo(SensitivityCategory.PersonName)
        assertThat(category("xmp", "dc:creator[1]")).isEqualTo(SensitivityCategory.PersonName)
        assertThat(category("exif-subifd", "Unique Image ID")).isEqualTo(SensitivityCategory.DeviceIdentifier)
        assertThat(category("xmp", "xmpMM:DocumentID")).isEqualTo(SensitivityCategory.DeviceIdentifier)
        assertThat(category("xmp", "mwg-rs:Regions/mwg-rs:RegionList[1]/mwg-rs:Name")).isEqualTo(SensitivityCategory.People)
        assertThat(category("xmp", "xmp:CreatorTool")).isNull()
        assertThat(category("exif-subifd", "Exposure Time")).isNull()

        val findings = report.sensitiveFindings.associateBy { it.category }
        assertThat(findings.keys).containsExactlyElementsIn(SensitivityCategory.entries).inOrder()
        assertThat(findings.getValue(SensitivityCategory.SerialNumber).tagKeys)
            .containsExactly("exif-subifd:${Photos.BODY_SERIAL}", "xmp:aux:SerialNumber")
    }

    @Test fun canonMakerNoteOwnerAndSerialAreSensitive() {
        val makerNote = TiffBuilder.ifd {
            ascii(0x0009, "Jane Doe") // OwnerName
            long(0x000C, 123456789) // SerialNumber
            ascii(0x0095, "RF24-105mm F4 L IS USM") // LensModel
        }
        val ifd0 = Photos.ifd0(Photos.exifIfd { makerNoteIfd(Photos.MAKER_NOTE, makerNote); ascii(Photos.LENS_MODEL, "") }) {
            ascii(Photos.MAKE, "Canon")
        }
        val report = Photos.read(Photos.jpeg(TiffBuilder().build(ifd0)))
        assertThat(report.tag("makernote-canon", "Owner Name").sensitivity).isEqualTo(SensitivityCategory.PersonName)
        assertThat(report.tag("makernote-canon", "Camera Serial Number").sensitivity).isEqualTo(SensitivityCategory.SerialNumber)
        // Empty Exif LensModel falls back to the MakerNote lens name.
        assertThat(report.summary.lens).isEqualTo("RF24-105mm F4 L IS USM")
    }

    @Test fun binaryValuesAreSummarizedAndHexTruncated() {
        val medium = ByteArray(100) { it.toByte() }
        val blob = ByteArray(5000) { (it % 251).toByte() }
        val ifd0 = Photos.ifd0(Photos.exifIfd { undefined(0x7778, medium); undefined(0x7777, blob) })
        val report = Photos.read(Photos.jpeg(TiffBuilder().build(ifd0)))
        val tags = report.directories.flatMap { it.tags }
        val mediumTag = tags.single { it.id == 0x7778 }
        assertThat(mediumTag.rawValue).startsWith("00 01 02 03")
        assertThat(mediumTag.rawValue).endsWith(" ... (100 bytes)")
        assertThat(mediumTag.rawValue.split(" ... ")[0].split(" ")).hasSize(64)
        val blobTag = tags.single { it.id == 0x7777 }
        assertThat(blobTag.rawValue).isEqualTo("(5000 bytes)")
        assertThat(blobTag.displayValue).isEqualTo("Binary data (5000 bytes)")
    }

    @Test fun controlCharactersAreEscaped() {
        val ifd0 = Photos.ifd0(Photos.exifIfd()) { ascii(Photos.ARTIST, "Jane\u0001Doe\r\nPhotographer\u0007") }
        val artist = Photos.read(Photos.jpeg(TiffBuilder().build(ifd0))).tag("exif-ifd0", "Artist")
        assertThat(artist.rawValue).isEqualTo("Jane\\x01Doe\nPhotographer\\x07")
    }

    @Test fun fileDirectoryDescribesTheFile() {
        val report = Photos.read(phoneJpeg, "photo.jpg")
        assertThat(report.format).isEqualTo(ImageFormat.Jpeg)
        assertThat(report.fileName).isEqualTo("photo.jpg")
        assertThat(report.fileSize).isEqualTo(phoneJpeg.size.toLong())
        val file = report.directory("file")
        assertThat(file.group).isEqualTo(DirectoryGroup.File)
        assertThat(file.tags.map { it.name }).containsExactly("File Name", "File Size", "File Type", "MIME Type").inOrder()
        assertThat(file.tags.single { it.name == "MIME Type" }.displayValue).isEqualTo("image/jpeg")
        assertThat(report.warnings).isEmpty()
    }
}
