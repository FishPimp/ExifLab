package io.github.fishpimp.exiflab.metadata.write

import com.google.common.truth.Truth.assertThat
import io.github.fishpimp.exiflab.metadata.ByteArrayImageSource
import io.github.fishpimp.exiflab.metadata.CorruptImageException
import io.github.fishpimp.exiflab.metadata.ImageFormat
import io.github.fishpimp.exiflab.metadata.fixtures.Containers
import io.github.fishpimp.exiflab.metadata.fixtures.Photos
import io.github.fishpimp.exiflab.metadata.fixtures.Photos.directory
import io.github.fishpimp.exiflab.metadata.fixtures.Photos.tag
import io.github.fishpimp.exiflab.metadata.fixtures.Photos.tagOrNull
import io.github.fishpimp.exiflab.metadata.fixtures.TiffBuilder
import io.github.fishpimp.exiflab.metadata.fixtures.Xmp
import io.github.fishpimp.exiflab.metadata.model.Rational
import io.github.fishpimp.exiflab.metadata.write.WriteFixtures.ascii
import io.github.fishpimp.exiflab.metadata.write.WriteFixtures.assertUntouched
import io.github.fishpimp.exiflab.metadata.write.WriteFixtures.changes
import io.github.fishpimp.exiflab.metadata.write.WriteFixtures.rationals
import io.github.fishpimp.exiflab.metadata.write.WriteFixtures.set
import io.github.fishpimp.exiflab.metadata.write.WriteFixtures.write
import java.io.ByteArrayOutputStream
import java.security.MessageDigest
import kotlin.random.Random
import org.junit.Assert.assertThrows
import org.junit.Test

class JpegWriterTest {
    private val thumbnail = Containers.jpeg(16, 12)

    /** A phone-like JPEG: IFD0, Exif (with Interop), GPS and an IFD1 thumbnail. */
    private fun phoneJpeg(bigEndian: Boolean = true, extra: List<Pair<Int, ByteArray>> = emptyList()): ByteArray {
        val exif = Photos.exifIfd { subIfd(Photos.INTEROP_IFD, TiffBuilder.ifd { ascii(0x0001, "R98") }) }
        val ifd0 = Photos.ifd0(exif, Photos.gpsIfd())
        ifd0.next = TiffBuilder.ifd {
            short(0x0103, 6)
            offsetOf(0x0201, thumbnail)
            long(0x0202, thumbnail.size.toLong())
        }
        return Photos.jpeg(TiffBuilder(bigEndian).build(ifd0), *extra.toTypedArray())
    }

    @Test fun setsReplacesAndRemovesTagsInEveryIfdInBothByteOrders() {
        for (bigEndian in listOf(true, false)) {
            val source = phoneJpeg(bigEndian)
            val edit = changes(
                ascii(ExifIfd.Primary, Photos.ARTIST, "Jane Doe"),
                ascii(ExifIfd.Primary, Photos.SOFTWARE, "ExifLab"),
                ExifChange.Remove(ExifIfd.Primary, Photos.DATE_TIME),
                set(ExifIfd.Exif, Photos.ISO, ExifValue.Shorts(listOf(800))),
                ascii(ExifIfd.Exif, Photos.BODY_SERIAL, "SN-42"),
                ExifChange.Remove(ExifIfd.Exif, Photos.FOCAL_LENGTH),
                set(ExifIfd.Exif, Photos.EXPOSURE_BIAS, ExifValue.SignedRationals(listOf(Rational(-1, 3)))),
                set(ExifIfd.Gps, 0x0002, rationals(10L to 1L, 30L to 1L, 0L to 1L)),
                ExifChange.Remove(ExifIfd.Gps, 0x0000),
                ascii(ExifIfd.Interoperability, 0x0001, "THM"),
                set(ExifIfd.Thumbnail, 0x011A, rationals(72L to 1L)),
            )
            val written = write(source, edit)
            val before = Photos.read(source)
            val after = Photos.read(written.bytes)

            assertThat(after.tag("exif-ifd0", "Artist").rawValue).isEqualTo("Jane Doe")
            assertThat(after.tag("exif-ifd0", "Software").rawValue).isEqualTo("ExifLab")
            assertThat(after.tagOrNull("exif-ifd0", "Date/Time")).isNull()
            assertThat(after.tag("exif-subifd", "ISO Speed Ratings").rawValue).isEqualTo("800")
            assertThat(after.tag("exif-subifd", "Body Serial Number").rawValue).isEqualTo("SN-42")
            assertThat(after.tagOrNull("exif-subifd", "Focal Length")).isNull()
            assertThat(after.tag("exif-subifd", "Exposure Bias Value").rawValue).isEqualTo("-1/3")
            assertThat(after.location!!.latitude).isWithin(1e-9).of(10.5)
            assertThat(after.tagOrNull("gps", "GPS Version ID")).isNull()
            assertThat(after.tag("exif-interop", "Interoperability Index").rawValue).isEqualTo("THM")
            assertThat(after.tag("exif-thumbnail", "X Resolution").rawValue).isEqualTo("72/1")
            assertThat(written.result.format).isEqualTo(ImageFormat.Jpeg)
            assertThat(written.result.skipped).isEmpty()

            assertUntouched(
                before, after,
                setOf(
                    "exif-ifd0:${Photos.ARTIST}", "exif-ifd0:${Photos.SOFTWARE}", "exif-ifd0:${Photos.DATE_TIME}",
                    "exif-subifd:${Photos.ISO}", "exif-subifd:${Photos.BODY_SERIAL}", "exif-subifd:${Photos.FOCAL_LENGTH}",
                    "exif-subifd:${Photos.EXPOSURE_BIAS}", "gps:2", "gps:0", "exif-interop:1", "exif-thumbnail:282",
                ),
            )
            // The thumbnail moved but is the same image.
            val tiff = JpegStructure.exifTiff(written.bytes)
            assertThat(String(tiff, 0, 2, Charsets.ISO_8859_1)).isEqualTo(if (bigEndian) "MM" else "II")
            assertThat(indexOf(tiff, thumbnail)).isAtLeast(8)
        }
    }

    @Test fun keepsTheSegmentByteExactWhenExifIsNotEdited() {
        val source = phoneJpeg(extra = listOf(Containers.xmpSegment(Xmp.packet(Xmp.description(mapOf("xmp:Rating" to "3"))))))
        val written = write(source, MetadataChanges(xmp = listOf(XmpChange.SetProperty(XMP_NS, "xmp", "Rating", "5"))))
        assertThat(JpegStructure.exifTiff(written.bytes)).isEqualTo(JpegStructure.exifTiff(source))
        assertThat(Photos.read(written.bytes).tag("xmp", "xmp:Rating").rawValue).isEqualTo("5")
    }

    @Test fun editsXmpPaddedWithNulBytes() {
        val padded = Containers.xmpSegment(Xmp.packet(Xmp.description(mapOf("xmp:Rating" to "3")))).let { it.first to it.second + ByteArray(40) }
        val written = write(Photos.jpeg(null, padded), MetadataChanges(xmp = listOf(XmpChange.SetProperty(XMP_NS, "xmp", "Label", "Red"))))
        val after = Photos.read(written.bytes)
        assertThat(after.tag("xmp", "xmp:Rating").rawValue).isEqualTo("3")
        assertThat(after.tag("xmp", "xmp:Label").rawValue).isEqualTo("Red")
    }

    @Test fun emptyChangesReproduceTheFile() {
        val source = phoneJpeg(extra = listOf(Containers.iptcSegment(120 to "Caption"), 0xFE to "comment".toByteArray()))
        assertThat(write(source, MetadataChanges()).bytes).isEqualTo(source)
    }

    @Test fun createsGpsIfdFromNothing() {
        val source = Photos.jpeg(TiffBuilder().build(Photos.ifd0(Photos.exifIfd())))
        val written = write(
            source,
            changes(
                ascii(ExifIfd.Gps, 0x0001, "S"),
                set(ExifIfd.Gps, 0x0002, rationals(33L to 1L, 51L to 1L, 2160L to 100L)),
                ascii(ExifIfd.Gps, 0x0003, "E"),
                set(ExifIfd.Gps, 0x0004, rationals(151L to 1L, 12L to 1L, 3540L to 100L)),
            ),
        )
        val after = Photos.read(written.bytes)
        val location = after.location!!
        assertThat(location.latitude).isWithin(1e-6).of(-(33 + 51 / 60.0 + 21.6 / 3600))
        assertThat(location.longitude).isWithin(1e-6).of(151 + 12 / 60.0 + 35.4 / 3600)
        assertThat(after.tag("gps", "GPS Version ID").rawValue).isEqualTo("2 3 0 0")
        assertUntouched(Photos.read(source), after, setOf("gps:"))
    }

    @Test fun createsAnExifBlockRightAfterJfifAndXmpRightAfterIt() {
        val source = Containers.jpeg()
        assertThat(JpegStructure.labels(source).first()).isEqualTo("APP0")
        val written = write(
            source,
            MetadataChanges(
                exif = listOf(ascii(ExifIfd.Exif, Photos.DATE_TIME_ORIGINAL, "2024:06:01 10:00:00")),
                xmp = listOf(XmpChange.SetProperty(XMP_NS, "xmp", "Rating", "4")),
                iptc = listOf(IptcChange.Set(2, 120, listOf("Hello"))),
            ),
        )
        assertThat(JpegStructure.labels(written.bytes).take(5)).containsExactly("APP0", "Exif", "XMP", "APP13", "DQT").inOrder()
        val after = Photos.read(written.bytes)
        assertThat(after.tag("exif-subifd", "Date/Time Original").rawValue).isEqualTo("2024:06:01 10:00:00")
        assertThat(after.tag("exif-subifd", "Exif Version").displayValue).isEqualTo("2.32")
        assertThat(after.tag("exif-ifd0", "X Resolution").rawValue).isEqualTo("72/1")
        assertThat(after.tag("exif-subifd", "Color Space").rawValue).isEqualTo("1")
        assertThat(after.tag("exif-subifd", "Exif Image Width").rawValue).isEqualTo("64")
        assertThat(after.tag("exif-subifd", "Exif Image Height").rawValue).isEqualTo("48")
        assertThat(after.tag("exif-subifd", "Components Configuration").displayValue).isEqualTo("YCbCr")
        assertThat(after.tag("xmp", "xmp:Rating").rawValue).isEqualTo("4")
        assertThat(after.tag("iptc", "Caption/Abstract").rawValue).isEqualTo("Hello")
        assertThat(after.tag("iptc", "Application Record Version").rawValue).isEqualTo("4")
    }

    @Test fun removingTheLastTagsDropsEmptySubIfdsAndTheBlock() {
        val source = Photos.jpeg(TiffBuilder().build(TiffBuilder.ifd { ascii(Photos.MAKE, "X"); subIfd(Photos.GPS_IFD, TiffBuilder.ifd { ascii(1, "N") }) }))
        val noGps = write(source, changes(ExifChange.Remove(ExifIfd.Gps, 1)))
        val tiff = JpegStructure.exifTiff(noGps.bytes)
        assertThat(Photos.read(noGps.bytes).directories.map { it.id }).doesNotContain("gps")
        assertThat(indexOf(tiff, byteArrayOf(0x88.toByte(), 0x25))).isEqualTo(-1)

        val empty = write(noGps.bytes, changes(ExifChange.Remove(ExifIfd.Primary, Photos.MAKE)))
        assertThat(JpegStructure.labels(empty.bytes)).doesNotContain("Exif")
    }

    @Test fun removesWholeBlocks() {
        val makerNote = TiffBuilder.ifd { ascii(0x0009, "Jane Doe"); long(0x000C, 123456789) }
        val ifd0 = Photos.ifd0(Photos.exifIfd { makerNoteIfd(Photos.MAKER_NOTE, makerNote) }) { ascii(Photos.MAKE, "Canon") }
        ifd0.next = TiffBuilder.ifd { offsetOf(0x0201, thumbnail); long(0x0202, thumbnail.size.toLong()) }
        val resources = Containers.iptcSegment(120 to "Caption").second + photoshopResource(0x040C, ByteArray(9) { 7 })
        val source = Photos.jpeg(
            TiffBuilder(false).build(ifd0),
            Containers.xmpSegment(Xmp.packet(Xmp.description(mapOf("xmp:Rating" to "3")))),
            0xED to resources,
            Containers.iccSegment(ByteArray(128) { 1 }),
            0xFE to "A comment".toByteArray(),
        )
        val before = Photos.read(source)
        assertThat(before.directories.map { it.id }).containsAtLeast("makernote-canon", "exif-thumbnail", "xmp", "iptc", "photoshop", "jpeg-comment")

        fun idsAfter(vararg blocks: MetadataBlock) = Photos.read(write(source, MetadataChanges(removeBlocks = blocks.toSet())).bytes).directories.map { it.id }

        assertThat(idsAfter(MetadataBlock.MakerNote)).doesNotContain("makernote-canon")
        assertUntouched(before, Photos.read(write(source, MetadataChanges(removeBlocks = setOf(MetadataBlock.MakerNote))).bytes), setOf("makernote-canon:", "exif-subifd:${Photos.MAKER_NOTE}"))
        assertThat(idsAfter(MetadataBlock.ExifThumbnail)).doesNotContain("exif-thumbnail")
        assertThat(idsAfter(MetadataBlock.Exif)).containsNoneOf("exif-ifd0", "exif-subifd", "makernote-canon", "exif-thumbnail")
        assertThat(idsAfter(MetadataBlock.Xmp)).doesNotContain("xmp")
        val withoutIptc = idsAfter(MetadataBlock.Iptc)
        assertThat(withoutIptc).doesNotContain("iptc")
        assertThat(withoutIptc).contains("photoshop")
        assertThat(idsAfter(MetadataBlock.PhotoshopResources)).containsNoneOf("iptc", "photoshop")
        assertThat(idsAfter(MetadataBlock.IccProfile)).doesNotContain("icc")
        assertThat(idsAfter(MetadataBlock.Comments)).doesNotContain("jpeg-comment")

        val stripped = write(source, MetadataChanges(removeBlocks = MetadataBlock.entries.toSet()))
        assertThat(JpegStructure.labels(stripped.bytes)).containsNoneOf("Exif", "XMP", "APP13", "ICC", "COM")
        // Block removals run before tag changes: strip everything but keep the orientation.
        val oriented = write(
            source,
            MetadataChanges(exif = listOf(set(ExifIfd.Primary, Photos.ORIENTATION, ExifValue.Shorts(listOf(6)))), removeBlocks = setOf(MetadataBlock.Exif)),
        )
        val tags = Photos.read(oriented.bytes).directory("exif-ifd0").tags.map { it.name }
        assertThat(tags).contains("Orientation")
        assertThat(tags).doesNotContain("Make")
    }

    @Test fun makerNoteStaysAtItsOriginalOffset() {
        val makerNote = TiffBuilder.ifd {
            ascii(0x0006, "Canon EOS R5 image type")
            ascii(0x0009, "Jane Doe")
            long(0x000C, 123456789)
            ascii(0x0095, "RF24-105mm F4 L IS USM")
        }
        val ifd0 = Photos.ifd0(Photos.exifIfd { makerNoteIfd(Photos.MAKER_NOTE, makerNote) }) { ascii(Photos.MAKE, "Canon") }
        val source = Photos.jpeg(TiffBuilder().build(ifd0))
        val sourceOffset = makerNoteOffset(JpegStructure.exifTiff(source))

        val written = write(source, changes(ascii(ExifIfd.Primary, Photos.ARTIST, "A much longer artist name than before"), ExifChange.Remove(ExifIfd.Exif, Photos.LENS_MODEL)))
        assertThat(makerNoteOffset(JpegStructure.exifTiff(written.bytes))).isEqualTo(sourceOffset)
        val before = Photos.read(source)
        val after = Photos.read(written.bytes)
        assertThat(after.directory("makernote-canon").tags.map { it.key to it.rawValue })
            .containsExactlyElementsIn(before.directory("makernote-canon").tags.map { it.key to it.rawValue })
        assertUntouched(before, after, setOf("exif-ifd0:${Photos.ARTIST}", "exif-subifd:${Photos.LENS_MODEL}"))
    }

    @Test fun makerNoteMovesWithFixedOffsetsWhenItCannotStay() {
        val makerNote = TiffBuilder.ifd {
            ascii(0x0006, "Canon EOS R5 image type")
            ascii(0x0009, "Jane Doe")
            ascii(0x0095, "RF24-105mm F4 L IS USM")
        }
        // A long description is laid out before the Exif IFD, pushing the MakerNote far into the block.
        val ifd0 = Photos.ifd0(Photos.exifIfd { makerNoteIfd(Photos.MAKER_NOTE, makerNote) }) {
            ascii(Photos.MAKE, "Canon")
            ascii(0x010E, "d".repeat(30000))
        }
        val bigThumbnail = Containers.jpeg(16, 12) + ByteArray(30000)
        ifd0.next = TiffBuilder.ifd { offsetOf(0x0201, bigThumbnail); long(0x0202, bigThumbnail.size.toLong()) }
        val source = Photos.jpeg(TiffBuilder().build(ifd0))
        val sourceTiff = JpegStructure.exifTiff(source)
        assertThat(makerNoteOffset(sourceTiff)).isGreaterThan(30000)

        // The longer description no longer fits before the MakerNote; keeping it in place would overflow 64 KB.
        val written = write(source, changes(ascii(ExifIfd.Primary, 0x010E, "e".repeat(31000))))
        assertThat(makerNoteOffset(JpegStructure.exifTiff(written.bytes))).isNotEqualTo(makerNoteOffset(sourceTiff))
        val before = Photos.read(source)
        val after = Photos.read(written.bytes)
        assertThat(after.directory("makernote-canon").tags.map { it.key to it.rawValue })
            .containsExactlyElementsIn(before.directory("makernote-canon").tags.map { it.key to it.rawValue })
        assertThat(after.tag("makernote-canon", "Owner Name").rawValue).isEqualTo("Jane Doe")
        assertThat(written.result.skipped).isEmpty()
    }

    @Test fun exifThatCannotFitInASegmentIsRefused() {
        val source = phoneJpeg()
        val error = assertThrows(UnsupportedEditException::class.java) {
            MetadataWriting.writer.write(
                ByteArrayImageSource(source),
                changes(set(ExifIfd.Exif, 0x9286, ExifValue.Undefined(ByteArray(70000)))),
                ByteArrayOutputStream(),
            )
        }
        assertThat(error).hasMessageThat().contains("bytes")
    }

    @Test fun bigXmpIsSplitIntoExtendedXmpAndMergedOnTheNextEdit() {
        val data = java.util.Base64.getEncoder().encodeToString(Random(1).nextBytes(120_000))
        val source = phoneJpeg()
        val first = write(
            source,
            MetadataChanges(
                xmp = listOf(
                    XmpChange.SetProperty(GDEPTH_NS, "GDepth", "Data", data),
                    XmpChange.SetProperty(XMP_NS, "xmp", "Rating", "2"),
                ),
            ),
        )
        val segments = JpegStructure.segments(first.bytes)
        assertThat(segments.map { it.label }.filter { it.endsWith("XMP") }).containsExactly("XMP", "ExtXMP", "ExtXMP", "ExtXMP").inOrder()
        val standard = String(segments.first { it.label == "XMP" }.payload, Charsets.UTF_8)
        assertThat(standard).contains("HasExtendedXMP")
        assertThat(standard).contains("Rating")
        assertThat(standard).doesNotContain(data.substring(0, 100))
        val extended = assembleExtended(segments)
        val guid = MessageDigest.getInstance("MD5").digest(extended).joinToString("") { "%02X".format(it) }
        assertThat(standard).contains(guid)
        assertThat(String(extended, Charsets.UTF_8)).contains(data)

        // A second, unrelated edit merges the extended packet and splits it again.
        val second = write(first.bytes, MetadataChanges(xmp = listOf(XmpChange.SetProperty(XMP_NS, "xmp", "Rating", "5"))))
        val again = JpegStructure.segments(second.bytes)
        assertThat(String(assembleExtended(again), Charsets.UTF_8)).contains(data)
        assertThat(String(again.first { it.label == "XMP" }.payload, Charsets.UTF_8)).contains("xmp:Rating=\"5\"")
        assertThat(JpegStructure.exifTiff(second.bytes)).isEqualTo(JpegStructure.exifTiff(source))

        // Removing the property makes the packet small again: no extended segments remain.
        val third = write(second.bytes, MetadataChanges(xmp = listOf(XmpChange.Remove(GDEPTH_NS, "Data"))))
        assertThat(JpegStructure.labels(third.bytes)).doesNotContain("ExtXMP")
    }

    @Test fun rewritesOnlyTheIptcResource() {
        val thumbnailResource = photoshopResource(0x040C, ByteArray(33) { it.toByte() })
        val iptc = Containers.iptcSegment(120 to "Old caption", 25 to "one", 80 to "Someone")
        val digestResource = photoshopResource(0x0425, ByteArray(16))
        val source = Photos.jpeg(null, 0xED to (iptc.second + thumbnailResource + digestResource))
        val written = write(
            source,
            MetadataChanges(
                iptc = listOf(
                    IptcChange.Set(2, 120, listOf("New caption")),
                    IptcChange.Set(2, 25, listOf("alpha", "beta")),
                    IptcChange.Remove(2, 80),
                    IptcChange.Set(2, 90, listOf("Stockholm")),
                ),
            ),
        )
        val after = Photos.read(written.bytes)
        assertThat(after.tag("iptc", "Caption/Abstract").rawValue).isEqualTo("New caption")
        assertThat(after.tag("iptc", "Keywords").rawValue).contains("alpha")
        assertThat(after.tag("iptc", "Keywords").rawValue).contains("beta")
        assertThat(after.tagOrNull("iptc", "By-line")).isNull()
        assertThat(after.tag("iptc", "City").rawValue).isEqualTo("Stockholm")
        val payload = JpegStructure.segments(written.bytes).single { it.label == "APP13" }.payload
        assertThat(indexOf(payload, thumbnailResource)).isAtLeast(0)
        // The IPTC digest resource now matches the new IPTC data.
        val iptcData = iptcResourceData(payload)
        val digestAt = indexOf(payload, "8BIM".toByteArray() + byteArrayOf(0x04, 0x25))
        assertThat(payload.copyOfRange(digestAt + 12, digestAt + 28)).isEqualTo(MessageDigest.getInstance("MD5").digest(iptcData))
    }

    @Test fun nonAsciiIptcIsWrittenAsDeclaredUtf8() {
        val source = Photos.jpeg(null, Containers.iptcSegment(25 to "plain"))
        val written = write(source, MetadataChanges(iptc = listOf(IptcChange.Set(2, 120, listOf("Smörgåsbord i Göteborg")))))
        val after = Photos.read(written.bytes)
        assertThat(after.tag("iptc", "Caption/Abstract").rawValue).isEqualTo("Smörgåsbord i Göteborg")
        assertThat(after.tag("iptc", "Keywords").rawValue).isEqualTo("plain")
        assertThat(after.tag("iptc", "Coded Character Set").rawValue).isEqualTo("UTF-8")
    }

    @Test fun preservesTrailingDataAfterTheImage() {
        val trailer = Random(7).nextBytes(5000)
        val source = phoneJpeg() + trailer
        val written = write(source, changes(ascii(ExifIfd.Primary, Photos.ARTIST, "Someone")))
        assertThat(written.bytes.copyOfRange(written.bytes.size - trailer.size, written.bytes.size)).isEqualTo(trailer)
        val sos = JpegStructure.sosOffset(written.bytes)
        val sourceSos = JpegStructure.sosOffset(source)
        assertThat(written.bytes.copyOfRange(sos, written.bytes.size)).isEqualTo(source.copyOfRange(sourceSos, source.size))
    }

    @Test fun keepsMpfOffsetsPointingAtTheSecondaryImage() {
        val secondary = Containers.jpeg(8, 8)
        val source = mpfJpeg(secondary)
        assertThat(secondaryImageOffset(source)).isEqualTo(source.size - secondary.size)

        // Exif sits before the MPF segment: the distance to the secondary image is unchanged.
        val exifEdit = write(source, changes(ascii(ExifIfd.Primary, Photos.ARTIST, "Someone with a long name")))
        assertThat(secondaryImageOffset(exifEdit.bytes)).isEqualTo(exifEdit.bytes.size - secondary.size)
        assertThat(mpfSegment(exifEdit.bytes).payload).isEqualTo(mpfSegment(source).payload)

        // XMP sits after the MPF segment: growing it moves the secondary image, so the offset is fixed.
        val xmpEdit = write(source, MetadataChanges(xmp = listOf(XmpChange.SetProperty(XMP_NS, "xmp", "Label", "x".repeat(3000)))))
        assertThat(secondaryImageOffset(xmpEdit.bytes)).isEqualTo(xmpEdit.bytes.size - secondary.size)
        assertThat(xmpEdit.bytes.copyOfRange(xmpEdit.bytes.size - secondary.size, xmpEdit.bytes.size)).isEqualTo(secondary)
    }

    @Test fun digestCoversImageDataOnly() {
        val source = phoneJpeg()
        val edited = write(source, changes(ascii(ExifIfd.Primary, Photos.ARTIST, "Someone"))).bytes
        assertThat(WriteFixtures.digest(edited)).isEqualTo(WriteFixtures.digest(source))
        val damaged = source.copyOf().also { it[it.size - 10] = (it[it.size - 10] + 1).toByte() }
        assertThat(WriteFixtures.digest(damaged)).isNotEqualTo(WriteFixtures.digest(source))
        val requantized = source.copyOf().also {
            val dqt = JpegStructure.segments(it).first { s -> s.label == "DQT" }
            it[dqt.offset + 10] = (it[dqt.offset + 10] + 1).toByte()
        }
        assertThat(WriteFixtures.digest(requantized)).isNotEqualTo(WriteFixtures.digest(source))
    }

    @Test fun truncatedFilesFailCleanly() {
        val source = phoneJpeg(extra = listOf(Containers.xmpSegment(Xmp.packet(Xmp.description(mapOf("xmp:Rating" to "3"))))))
        val exif = JpegStructure.segments(source).first { it.label == "Exif" }
        for (length in listOf(3, exif.offset + 20, JpegStructure.sosOffset(source) + 6, source.size - 2)) {
            val truncated = source.copyOf(length)
            assertThrows("length $length", CorruptImageException::class.java) {
                MetadataWriting.writer.write(ByteArrayImageSource(truncated), changes(ascii(ExifIfd.Primary, Photos.ARTIST, "X")), ByteArrayOutputStream())
            }
        }
    }

    @Test fun damagedExifIsRefusedNotDropped() {
        val damaged = Photos.jpeg(null, 0xE1 to ("Exif\u0000\u0000".toByteArray() + "XX*\u0000junk".toByteArray()))
        assertThrows(UnsupportedEditException::class.java) {
            MetadataWriting.writer.write(ByteArrayImageSource(damaged), changes(ascii(ExifIfd.Primary, Photos.ARTIST, "X")), ByteArrayOutputStream())
        }
        // Edits that do not touch the damaged block still work.
        val written = write(damaged, MetadataChanges(xmp = listOf(XmpChange.SetProperty(XMP_NS, "xmp", "Rating", "1"))))
        assertThat(JpegStructure.segments(written.bytes).first().payload).isEqualTo(JpegStructure.segments(damaged).first().payload)
    }

    @Test fun onlyJpegPngAndWebpAreWritable() {
        val writer = MetadataWriting.writer
        assertThat(ImageFormat.entries.filter(writer::canWrite)).containsExactly(ImageFormat.Jpeg, ImageFormat.Png, ImageFormat.WebP)
        val tiff = TiffBuilder.tiff { ascii(Photos.MAKE, "X") }
        assertThrows(UnsupportedEditException::class.java) {
            writer.write(ByteArrayImageSource(tiff, "a.tif"), MetadataChanges(), ByteArrayOutputStream())
        }
        assertThrows(UnsupportedEditException::class.java) {
            writer.write(ByteArrayImageSource(Containers.raf(Containers.jpeg()), "a.raf"), MetadataChanges(), ByteArrayOutputStream())
        }
    }

    @Test fun structuralTagsCannotBeSetDirectly() {
        val written = write(phoneJpeg(), changes(set(ExifIfd.Primary, Photos.EXIF_IFD, ExifValue.Longs(listOf(8)))))
        assertThat(written.result.skipped.single()).contains("0x8769")
    }

    private fun makerNoteOffset(tiff: ByteArray): Long {
        val document = io.github.fishpimp.exiflab.metadata.write.tiff.TiffParser.parse(tiff, mutableListOf())
        return document.makerNote!!.sourceOffset
    }

    private fun photoshopResource(id: Int, data: ByteArray): ByteArray =
        "8BIM".toByteArray() + Containers.be16(id) + byteArrayOf(0, 0) + Containers.be32(data.size) + data + if (data.size % 2 == 1) byteArrayOf(0) else ByteArray(0)

    private fun iptcResourceData(payload: ByteArray): ByteArray {
        val at = indexOf(payload, "8BIM".toByteArray() + byteArrayOf(0x04, 0x04))
        val size = JpegStructure.be32(payload, at + 8).toInt()
        return payload.copyOfRange(at + 12, at + 12 + size)
    }

    private fun assembleExtended(segments: List<JpegStructure.Segment>): ByteArray {
        val chunks = segments.filter { it.label == "ExtXMP" }.map { it.payload }
        val total = JpegStructure.be32(chunks.first(), 35 + 32).toInt()
        val out = ByteArray(total)
        for (chunk in chunks) {
            val offset = JpegStructure.be32(chunk, 35 + 32 + 4).toInt()
            chunk.copyInto(out, offset, 35 + 32 + 8, chunk.size)
        }
        return out
    }

    /** A JPEG with Exif, an MPF index, XMP after it, and a secondary image after EOI. */
    private fun mpfJpeg(secondary: ByteArray): ByteArray {
        val entries = ByteArray(32)
        val mpfTiff = TiffBuilder.tiff {
            undefined(0xB000, "0100".toByteArray())
            long(0xB001, 2)
            undefined(0xB002, entries)
        }
        val primary = Photos.jpeg(
            TiffBuilder().build(Photos.ifd0(Photos.exifIfd())),
            0xE2 to ("MPF\u0000".toByteArray() + mpfTiff),
            Containers.xmpSegment(Xmp.packet(Xmp.description(mapOf("xmp:Rating" to "1")))),
        )
        val file = primary + secondary
        val mpf = mpfSegment(file)
        val tiffStart = mpf.offset + 4 + 4
        val entriesAt = tiffStart + mpfEntriesOffset(file, tiffStart)
        Containers.be32(primary.size).copyInto(file, entriesAt + 4)
        Containers.be32(secondary.size).copyInto(file, entriesAt + 16 + 4)
        Containers.be32(primary.size - tiffStart).copyInto(file, entriesAt + 16 + 8)
        return file
    }

    private fun mpfSegment(jpeg: ByteArray) = JpegStructure.segments(jpeg).single { it.label == "MPF" }

    private fun mpfEntriesOffset(jpeg: ByteArray, tiffStart: Int): Int {
        val ifd = tiffStart + JpegStructure.be32(jpeg, tiffStart + 4).toInt()
        val count = ((jpeg[ifd].toInt() and 0xFF) shl 8) or (jpeg[ifd + 1].toInt() and 0xFF)
        for (i in 0 until count) {
            val at = ifd + 2 + i * 12
            val tag = ((jpeg[at].toInt() and 0xFF) shl 8) or (jpeg[at + 1].toInt() and 0xFF)
            if (tag == 0xB002) return JpegStructure.be32(jpeg, at + 8).toInt()
        }
        error("No MP entries")
    }

    private fun secondaryImageOffset(jpeg: ByteArray): Int {
        val tiffStart = mpfSegment(jpeg).offset + 4 + 4
        val entries = tiffStart + mpfEntriesOffset(jpeg, tiffStart)
        return tiffStart + JpegStructure.be32(jpeg, entries + 16 + 8).toInt()
    }

    companion object {
        const val XMP_NS = "http://ns.adobe.com/xap/1.0/"
        const val GDEPTH_NS = "http://ns.google.com/photos/1.0/depthmap/"

        fun indexOf(haystack: ByteArray, needle: ByteArray): Int {
            outer@ for (i in 0..haystack.size - needle.size) {
                for (j in needle.indices) if (haystack[i + j] != needle[j]) continue@outer
                return i
            }
            return -1
        }
    }
}
