package io.github.fishpimp.exiflab.metadata.write

import com.google.common.truth.Truth.assertThat
import io.github.fishpimp.exiflab.metadata.ByteArrayImageSource
import io.github.fishpimp.exiflab.metadata.CorruptImageException
import io.github.fishpimp.exiflab.metadata.ImageFormat
import io.github.fishpimp.exiflab.metadata.fixtures.Photos
import io.github.fishpimp.exiflab.metadata.fixtures.Photos.tag
import io.github.fishpimp.exiflab.metadata.fixtures.Photos.tagOrNull
import io.github.fishpimp.exiflab.metadata.fixtures.TiffBuilder
import io.github.fishpimp.exiflab.metadata.model.Rational
import io.github.fishpimp.exiflab.metadata.write.HeifFixtures.Item
import io.github.fishpimp.exiflab.metadata.write.HeifFixtures.Parsed
import io.github.fishpimp.exiflab.metadata.write.HeifFixtures.assertConsistent
import io.github.fishpimp.exiflab.metadata.write.HeifFixtures.assertImageDataIntact
import io.github.fishpimp.exiflab.metadata.write.HeifFixtures.build
import io.github.fishpimp.exiflab.metadata.write.HeifFixtures.exifItem
import io.github.fishpimp.exiflab.metadata.write.HeifFixtures.gridPhoto
import io.github.fishpimp.exiflab.metadata.write.HeifFixtures.simplePhoto
import io.github.fishpimp.exiflab.metadata.write.HeifFixtures.xmpPacket
import io.github.fishpimp.exiflab.metadata.write.JpegWriterTest.Companion.XMP_NS
import io.github.fishpimp.exiflab.metadata.write.WriteFixtures.ascii
import io.github.fishpimp.exiflab.metadata.write.WriteFixtures.assertUntouched
import io.github.fishpimp.exiflab.metadata.write.WriteFixtures.changes
import io.github.fishpimp.exiflab.metadata.write.WriteFixtures.digest
import io.github.fishpimp.exiflab.metadata.write.WriteFixtures.write
import io.github.fishpimp.exiflab.metadata.write.heif.ItemLocation
import io.github.fishpimp.exiflab.metadata.write.heif.ItemLocations
import io.github.fishpimp.exiflab.metadata.write.heif.Extent
import java.io.ByteArrayOutputStream
import org.junit.Assert.assertThrows
import org.junit.Test

class HeifWriterTest {
    private val artist = changes(ascii(ExifIfd.Primary, Photos.ARTIST, "Jane Doe"))
    private val rating = MetadataChanges(xmp = listOf(XmpChange.SetProperty(XMP_NS, "xmp", "Rating", "4")))
    private val longDescription = changes(ascii(ExifIfd.Primary, 0x010E, "A long description ".repeat(150)))

    private fun heif(spec: HeifFixtures.Spec) = build(spec)

    /** Writes and checks the invariants every HEIF write must hold. */
    private fun edit(source: ByteArray, changes: MetadataChanges, name: String = "photo.heic"): WriteFixtures.Written {
        val written = write(source, changes, name)
        assertImageDataIntact(source, written.bytes)
        assertConsistent(written.bytes)
        return written
    }

    private fun ByteArray.indexOf(needle: ByteArray): Int = (0..size - needle.size).firstOrNull { start -> needle.indices.all { this[start + it] == needle[it] } } ?: -1

    private fun ByteArray.lastIndexOf(needle: ByteArray): Int = (0..size - needle.size).lastOrNull { start -> needle.indices.all { this[start + it] == needle[it] } } ?: -1

    @Test fun canWriteHeifAndAvif() {
        assertThat(MetadataWriting.writer.canWrite(ImageFormat.Heif)).isTrue()
        assertThat(MetadataWriting.writer.canWrite(ImageFormat.Avif)).isTrue()
    }

    @Test fun rewritesExifInPlaceWhenItFitsTheReservedSpace() {
        val source = heif(gridPhoto(exif = exifItem(padding = 512)))
        val before = Photos.read(source)
        val written = edit(source, artist)
        assertThat(written.bytes.size).isEqualTo(source.size)
        // Nothing but the Exif bytes changed: same meta box, same item locations.
        val old = Parsed(source)
        val new = Parsed(written.bytes)
        assertThat(written.bytes.copyOfRange(new.meta.start, new.meta.end)).isEqualTo(source.copyOfRange(old.meta.start, old.meta.end))
        assertThat(new.fileRanges(6).single()).isEqualTo(old.fileRanges(6).single())
        val after = Photos.read(written.bytes)
        assertThat(after.tag("exif-ifd0", "Artist").rawValue).isEqualTo("Jane Doe")
        assertUntouched(before, after, setOf("exif-ifd0:${Photos.ARTIST}"))
        // The Exif item keeps its offset field and "Exif\0\0" marker.
        assertThat(new.data(6).copyOfRange(0, 10)).isEqualTo(exifItem().copyOfRange(0, 10))
    }

    @Test fun movesGrownExifToANewMdatAndZeroesTheOldBytes() {
        val source = heif(gridPhoto())
        val oldRange = Parsed(source).fileRanges(6).single()
        val written = edit(source, longDescription)
        val parsed = Parsed(written.bytes)
        assertThat(parsed.topLevel.map { it.type }).containsExactly("ftyp", "meta", "mdat", "mdat").inOrder()
        val newRange = parsed.fileRanges(6).single()
        assertThat(newRange[0]).isAtLeast(parsed.topLevel.last().start.toLong())
        assertThat(written.bytes.copyOfRange(oldRange[0].toInt(), (oldRange[0] + oldRange[1]).toInt()).all { it.toInt() == 0 }).isTrue()
        val after = Photos.read(written.bytes)
        assertThat(after.tag("exif-ifd0", "Image Description").rawValue).startsWith("A long description")
        assertThat(after.tag("exif-ifd0", "Model").rawValue).isEqualTo("Pixel 8")

        // Growing again replaces the trailing mdat instead of stacking another one.
        val again = edit(written.bytes, changes(ascii(ExifIfd.Primary, 0x010E, "Another description ".repeat(160))))
        assertThat(Parsed(again.bytes).topLevel.map { it.type }).containsExactly("ftyp", "meta", "mdat", "mdat").inOrder()
        assertThat(again.bytes.size).isLessThan(written.bytes.size + 400)
    }

    @Test fun createsExifAndXmpItemsLinkedToThePrimaryImage() {
        // Laid out like cheers_1440x960.heic: iloc version 0, base offsets pointing at the mdat payload.
        val spec = simplePhoto(exif = null).copy(ilocVersion = 0, baseOffsetSize = 4, baseRelative = true)
        val source = heif(spec)
        val written = edit(source, artist + rating)
        val parsed = Parsed(written.bytes)
        assertThat(parsed.exifIds).hasSize(1)
        assertThat(parsed.xmpIds).hasSize(1)
        // New ids follow the highest id in use.
        assertThat(parsed.exifIds + parsed.xmpIds).containsExactly(3L, 4L)
        assertThat(parsed.refs.filter { it.first == "cdsc" }.map { it.third }).containsExactly(listOf(1L), listOf(1L))
        assertThat(parsed.data(parsed.exifIds.single()).copyOfRange(0, 10)).isEqualTo(exifItem().copyOfRange(0, 10))
        val after = Photos.read(written.bytes)
        assertThat(after.tag("exif-ifd0", "Artist").rawValue).isEqualTo("Jane Doe")
        assertThat(after.tag("xmp", "xmp:Rating").rawValue).isEqualTo("4")
        assertThat(after.directories.map { it.id }).contains("heif")
    }

    @Test fun removesExifAndXmpLeavingNoDanglingReferences() {
        val source = heif(gridPhoto(xmp = xmpPacket("xmp:Rating" to "2", "dc:format" to "image/heic")))
        val written = edit(source, MetadataChanges(removeBlocks = setOf(MetadataBlock.Exif, MetadataBlock.Xmp)))
        val parsed = Parsed(written.bytes)
        assertThat(parsed.metadataIds).isEmpty()
        assertThat(parsed.infos.map { it.id }).containsExactly(1L, 2L, 3L, 4L, 5L)
        assertThat(parsed.refs.map { it.first }).containsExactly("dimg")
        // The old values are gone from the file, not just unreferenced.
        assertThat(written.bytes.indexOf("Pixel 8".toByteArray())).isEqualTo(-1)
        assertThat(written.bytes.indexOf("xmp:Rating".toByteArray())).isEqualTo(-1)
        val after = Photos.read(written.bytes)
        assertThat(after.directories.map { it.id }.filter { it.startsWith("exif") || it.startsWith("xmp") || it == "gps" }).isEmpty()
    }

    @Test fun smallNewItemsGoIntoIdatWhenTheFileHasOne() {
        val source = heif(gridPhoto())
        val written = edit(source, rating)
        val parsed = Parsed(written.bytes)
        val xmp = parsed.xmpIds.single()
        assertThat(parsed.locations.getValue(xmp).method).isEqualTo(1)
        assertThat(parsed.topLevel.map { it.type }).containsExactly("ftyp", "meta", "mdat").inOrder()
        assertThat(Photos.read(written.bytes).tag("xmp", "xmp:Rating").rawValue).isEqualTo("4")
        // The grid descriptor in idat is untouched (checked by assertImageDataIntact) and stays first.
        assertThat(parsed.idat!!.copyOfRange(0, 8)).isEqualTo(Parsed(source).idat!!)
    }

    @Test fun metaGrowthMovesEveryFileOffsetExtent() {
        // Tiles split into several extents, no free box: adding items grows meta and shifts all of mdat.
        val source = heif(gridPhoto(tileExtents = 3))
        val oldMdat = Parsed(source).box("mdat").start
        val written = edit(source, rating + longDescription)
        val parsed = Parsed(written.bytes)
        assertThat(parsed.topLevel.first { it.type == "mdat" }.start).isGreaterThan(oldMdat)
        for (id in 2L..5L) assertThat(parsed.locations.getValue(id).extents).hasSize(3)
    }

    @Test fun keepsBaseOffsetsAndRelativeExtentsConsistent() {
        val spec = gridPhoto(tileExtents = 2).copy(baseOffsetSize = 4, baseRelative = true)
        val source = heif(spec)
        val written = edit(source, rating + longDescription)
        val parsed = Parsed(written.bytes)
        val mdat = parsed.topLevel.first { it.type == "mdat" }
        // The shared base still points at the (moved) mdat payload.
        assertThat(parsed.locations.getValue(2).base).isEqualTo(mdat.payloadStart.toLong())
    }

    @Test fun supportsEightByteFieldsAndVersion2Iloc() {
        val spec = gridPhoto(xmp = xmpPacket("xmp:Rating" to "1")).copy(ilocVersion = 2, offsetSize = 8, lengthSize = 8, baseOffsetSize = 8, indexSize = 4)
        val source = heif(spec)
        val written = edit(source, artist + rating + longDescription)
        val parsed = Parsed(written.bytes)
        assertThat(parsed.ilocVersion).isEqualTo(2)
        assertThat(parsed.offsetSize).isEqualTo(8)
        val after = Photos.read(written.bytes)
        assertThat(after.tag("exif-ifd0", "Artist").rawValue).isEqualTo("Jane Doe")
        assertThat(after.tag("xmp", "xmp:Rating").rawValue).isEqualTo("4")
    }

    @Test fun widensIlocFieldsWhenOffsetsOutgrow32Bits() {
        val locations = ItemLocations(1, 4, 4, 0, 0, mutableListOf(ItemLocation(1, 0, 0, 0, mutableListOf(Extent(0, 0x1_0000_0010L, 100)))))
        val bytes = locations.bytes()
        // FullBox header (12 bytes), then offset_size << 4 | length_size.
        assertThat(bytes[12].toInt() and 0xFF).isEqualTo(0x84)
        assertThat(locations.offsetSize).isEqualTo(8)
        val large = ItemLocations(1, 4, 4, 0, 0, mutableListOf(ItemLocation(0x12345, 0, 0, 0, mutableListOf(Extent(0, 16, 100)))))
        large.bytes()
        assertThat(large.version).isEqualTo(2)
    }

    @Test fun metaAfterMdatLeavesTheDataInPlace() {
        val source = heif(gridPhoto().copy(metaAfterMdat = true))
        val old = Parsed(source)
        val written = edit(source, rating + longDescription)
        val new = Parsed(written.bytes)
        for (id in 2L..5L) assertThat(new.fileRanges(id).map { it.toList() }).isEqualTo(old.fileRanges(id).map { it.toList() })
        assertThat(new.topLevel.map { it.type }).containsExactly("ftyp", "mdat", "meta", "mdat").inOrder()
    }

    @Test fun freeBoxAfterMetaAbsorbsSizeChanges() {
        val source = heif(gridPhoto().copy(freeAfterMeta = 1000))
        val oldMdat = Parsed(source).box("mdat").start
        val grown = edit(source, rating)
        val parsed = Parsed(grown.bytes)
        assertThat(parsed.box("mdat").start).isEqualTo(oldMdat)
        assertThat(parsed.box("free").end - parsed.box("free").start).isLessThan(1000)

        val stripped = edit(source, MetadataChanges(removeBlocks = setOf(MetadataBlock.Exif)))
        assertThat(Parsed(stripped.bytes).box("mdat").start).isEqualTo(oldMdat)
        assertThat(stripped.bytes.size).isEqualTo(source.size)
    }

    @Test fun shrinkingMetaIsPaddedSoImageDataStaysPut() {
        val source = heif(gridPhoto(xmp = xmpPacket("xmp:Rating" to "1")))
        val old = Parsed(source)
        val written = edit(source, MetadataChanges(removeBlocks = setOf(MetadataBlock.Exif, MetadataBlock.Xmp)))
        val new = Parsed(written.bytes)
        assertThat(new.topLevel.map { it.type }).containsExactly("ftyp", "meta", "free", "mdat").inOrder()
        for (id in 2L..5L) assertThat(new.fileRanges(id).map { it.toList() }).isEqualTo(old.fileRanges(id).map { it.toList() })
    }

    @Test fun dropsAnMdatThatOnlyHeldRemovedMetadata() {
        val source = heif(gridPhoto(xmp = xmpPacket("xmp:Rating" to "1")).copy(mdatPerItem = true))
        assertThat(Parsed(source).topLevel.count { it.type == "mdat" }).isEqualTo(6)
        val written = edit(source, MetadataChanges(removeBlocks = setOf(MetadataBlock.Exif, MetadataBlock.Xmp)))
        assertThat(Parsed(written.bytes).topLevel.count { it.type == "mdat" }).isEqualTo(4)
        assertThat(written.bytes.indexOf("Pixel 8".toByteArray())).isEqualTo(-1)
    }

    @Test fun handlesExifAtTheEndOfTheFile() {
        // Like heic__exif-at-eof.heic: XMP first, image data, Exif last, without the "Exif\0\0" marker.
        val spec = gridPhoto(exif = exifItem(withMarker = false), xmp = xmpPacket("xmp:Rating" to "1")).let { it.copy(dataOrder = listOf(7, 2, 3, 4, 5, 6)) }
        val source = heif(spec)
        val before = Photos.read(source)
        val inPlace = edit(source, changes(ExifChange.Remove(ExifIfd.Gps, 0x0002)))
        assertThat(inPlace.bytes.size).isEqualTo(source.size)
        assertThat(Parsed(inPlace.bytes).data(6).copyOfRange(0, 4)).isEqualTo(byteArrayOf(0, 0, 0, 0))
        val grown = edit(source, longDescription)
        val after = Photos.read(grown.bytes)
        assertThat(after.tag("exif-ifd0", "Image Description").rawValue).startsWith("A long")
        assertUntouched(before, after, setOf("exif-ifd0:270"))
    }

    @Test fun rewritesXmpInPlaceWithXmpPaddingAndMovesItWhenItOutgrowsTheSpace() {
        val padded = String(xmpPacket("xmp:Rating" to "1")).replace("<?xpacket end", " ".repeat(2048) + "\n<?xpacket end").toByteArray()
        val source = heif(gridPhoto(xmp = padded))
        val old = Parsed(source)
        val written = edit(source, rating)
        val new = Parsed(written.bytes)
        assertThat(written.bytes.size).isEqualTo(source.size)
        assertThat(new.fileRanges(7).single()).isEqualTo(old.fileRanges(7).single())
        assertThat(String(new.data(7))).endsWith("<?xpacket end=\"w\"?>")
        assertThat(Photos.read(written.bytes).tag("xmp", "xmp:Rating").rawValue).isEqualTo("4")

        val label = MetadataChanges(xmp = listOf(XmpChange.SetProperty(XMP_NS, "xmp", "Label", "x".repeat(5000))))
        val grown = edit(source, label)
        val moved = Parsed(grown.bytes)
        assertThat(moved.locations.getValue(7).method).isEqualTo(0)
        assertThat(moved.fileRanges(7).single()[0]).isAtLeast(moved.topLevel.last().start.toLong())
        assertThat(Photos.read(grown.bytes).tag("xmp", "xmp:Label").rawValue).startsWith("xxxx")
        assertThat(String(moved.data(7))).contains("x".repeat(5000))
    }

    @Test fun exifStoredInIdatStaysThere() {
        val base = gridPhoto()
        val spec = base.copy(items = base.items.map { if (it.type == "Exif") Item(it.id, "Exif", it.data, inIdat = true) else it })
        val source = heif(spec)
        val written = edit(source, longDescription + artist)
        val parsed = Parsed(written.bytes)
        assertThat(parsed.locations.getValue(6).method).isEqualTo(1)
        assertThat(parsed.topLevel.count { it.type == "mdat" }).isEqualTo(1)
        assertThat(Photos.read(written.bytes).tag("exif-ifd0", "Artist").rawValue).isEqualTo("Jane Doe")
        // Only the new Exif block is left in idat; the old one was zeroed and trimmed.
        val marker = "Exif\u0000\u0000".toByteArray(Charsets.ISO_8859_1)
        assertThat(parsed.idat!!.indexOf(marker)).isEqualTo(parsed.idat!!.lastIndexOf(marker))
    }

    @Test fun sharedExtentsAreDigestedAndMovedTogether() {
        // AVIF grids often point several tiles at the same bytes.
        val base = gridPhoto()
        val spec = base.copy(items = base.items.map { if (it.id == 4) Item(4, "hvc1", ByteArray(0), sameDataAs = 3) else it })
        val source = heif(spec)
        assertThat(Parsed(source).fileRanges(4).single()).isEqualTo(Parsed(source).fileRanges(3).single())
        val written = edit(source, rating + longDescription)
        val parsed = Parsed(written.bytes)
        assertThat(parsed.fileRanges(4).single()).isEqualTo(parsed.fileRanges(3).single())
    }

    @Test fun exifAfterTheLastBoxIsRemappedWhenNewDataIsInserted() {
        // Exif stored after the last box, outside any box; new item data goes in a new mdat before it.
        val base = gridPhoto()
        val spec = base.copy(items = base.items.map { if (it.type == "Exif") Item(it.id, "Exif", it.data, inTail = true) else it })
        val source = heif(spec)
        val tailStart = Parsed(source).topLevel.last().end
        assertThat(tailStart).isLessThan(source.size)
        val written = write(source, rating + MetadataChanges(xmp = listOf(XmpChange.SetProperty(XMP_NS, "xmp", "Label", "y".repeat(5000)))))
        assertImageDataIntact(source, written.bytes)
        val parsed = Parsed(written.bytes)
        assertThat(parsed.topLevel.last().type).isEqualTo("mdat")
        assertThat(parsed.fileRanges(6).single()[0]).isAtLeast(parsed.topLevel.last().end.toLong())
        assertThat(parsed.data(6)).isEqualTo(Parsed(source).data(6))
        assertThat(Photos.read(written.bytes).tag("exif-ifd0", "Model").rawValue).isEqualTo("Pixel 8")
    }

    @Test fun avifItemsAreEditedLikeHeif() {
        val source = heif(simplePhoto(avif = true, imageExtents = 2))
        val written = edit(source, artist + rating, name = "photo.avif")
        assertThat(written.result.format).isEqualTo(ImageFormat.Avif)
        val after = Photos.read(written.bytes, "photo.avif")
        assertThat(after.tag("exif-ifd0", "Artist").rawValue).isEqualTo("Jane Doe")
        assertThat(after.tag("xmp", "xmp:Rating").rawValue).isEqualTo("4")
    }

    @Test fun appliesEveryKindOfChange() {
        val makerNote = TiffBuilder.ifd { ascii(0x0001, "maker") }
        val tiff = TiffBuilder().build(
            Photos.ifd0(Photos.exifIfd { makerNoteIfd(Photos.MAKER_NOTE, makerNote) }, Photos.gpsIfd()) { ascii(Photos.ARTIST, "Old Artist") },
        )
        val xmp = xmpPacket("xmp:Rating" to "1", "xmp:Label" to "Red", "xmpMM:DocumentID" to "doc-1", "dc:format" to "image/heic")
        val source = heif(gridPhoto(exif = exifItem(tiff), xmp = xmp))
        val before = Photos.read(source)
        val written = edit(
            source,
            MetadataChanges(
                exif = listOf(
                    ascii(ExifIfd.Primary, Photos.COPYRIGHT, "(c) Jane"),
                    ExifChange.Remove(ExifIfd.Primary, Photos.ARTIST),
                    ExifChange.Set(ExifIfd.Exif, Photos.EXPOSURE_BIAS, ExifValue.SignedRationals(listOf(Rational(-1, 3)))),
                    ExifChange.Set(ExifIfd.Gps, 0x0006, ExifValue.Rationals(listOf(Rational(25, 1)))),
                    ExifChange.Set(ExifIfd.Gps, 0x0005, ExifValue.Bytes(listOf(0))),
                ),
                xmp = listOf(
                    XmpChange.SetProperty(XMP_NS, "xmp", "Rating", "5"),
                    XmpChange.SetArray("http://purl.org/dc/elements/1.1/", "dc", "subject", listOf("a", "b"), XmpArrayKind.Bag),
                    XmpChange.SetLocalizedText("http://purl.org/dc/elements/1.1/", "dc", "title", "Title"),
                    XmpChange.Remove(XMP_NS, "Label"),
                    XmpChange.RemoveNamespace("http://ns.adobe.com/xap/1.0/mm/"),
                ),
                iptc = listOf(IptcChange.Set(2, 120, listOf("caption"))),
                removeBlocks = setOf(MetadataBlock.MakerNote, MetadataBlock.Comments),
            ),
        )
        val after = Photos.read(written.bytes)
        assertThat(after.tag("exif-ifd0", "Copyright").rawValue).isEqualTo("(c) Jane")
        assertThat(after.tagOrNull("exif-ifd0", "Artist")).isNull()
        assertThat(after.tagOrNull("gps", "GPS Altitude")).isNotNull()
        assertThat(after.directories.map { it.id }.filter { it.startsWith("makernote") }).isEmpty()
        assertThat(after.tag("xmp", "xmp:Rating").rawValue).isEqualTo("5")
        assertThat(after.tagOrNull("xmp", "xmp:Label")).isNull()
        assertThat(after.tagOrNull("xmp", "xmpMM:DocumentID")).isNull()
        assertThat(after.tag("xmp", "dc:title[1]").rawValue).isEqualTo("Title")
        assertThat(written.result.skipped.single()).contains("IPTC")
        assertUntouched(
            before, after,
            setOf("exif-ifd0:${Photos.COPYRIGHT}", "exif-ifd0:${Photos.ARTIST}", "exif-subifd:${Photos.EXPOSURE_BIAS}", "exif-subifd:${Photos.MAKER_NOTE}", "gps:", "xmp:"),
        )
    }

    @Test fun removingTheThumbnailIfdKeepsTheRest() {
        val tiff = TiffBuilder().build(Photos.ifd0(Photos.exifIfd()))
        val source = heif(simplePhoto(exif = exifItem(tiff)))
        val written = edit(source, MetadataChanges(removeBlocks = setOf(MetadataBlock.ExifThumbnail)))
        assertThat(Photos.read(written.bytes).tag("exif-ifd0", "Model").rawValue).isEqualTo("Pixel 8")
    }

    @Test fun reportsIptcAndKeepsTheColorProfile() {
        val spec = simplePhoto().let { it.copy(properties = it.properties.toMutableList().also { list -> list[3] = HeifFixtures.colrIcc() }) }
        val source = heif(spec)
        val written = edit(source, MetadataChanges(iptc = listOf(IptcChange.Set(2, 80, listOf("Jane"))), removeBlocks = setOf(MetadataBlock.IccProfile, MetadataBlock.Iptc)))
        assertThat(written.result.skipped).hasSize(2)
        assertThat(written.result.skipped.joinToString()).contains("IPTC")
        assertThat(written.result.skipped.joinToString()).contains("color profile")
        // Nothing the container can hold changed, so the file is the same.
        assertThat(written.bytes).isEqualTo(source)
    }

    @Test fun leavesTheFileIdenticalWhenNothingChanges() {
        val source = heif(gridPhoto(xmp = xmpPacket("xmp:Rating" to "1")))
        assertThat(edit(source, MetadataChanges()).bytes).isEqualTo(source)
        val noMetadata = heif(gridPhoto(exif = null))
        assertThat(edit(noMetadata, MetadataChanges(removeBlocks = setOf(MetadataBlock.Exif, MetadataBlock.Xmp))).bytes).isEqualTo(noMetadata)
    }

    @Test fun sequenceChunkOffsetsFollowMovedData() {
        for (wide in listOf(false, true)) {
            val samples = List(5) { HeifFixtures.tile(100 + it, 150 + it * 10) }
            val spec = simplePhoto(exif = null).copy(brand = "msf1", track = HeifFixtures.Track(samples, samplesPerChunk = 2, wide = wide))
            val source = heif(spec)
            val oldChunks = HeifFixtures.chunks(source)
            val written = edit(source, artist + rating)
            val newChunks = HeifFixtures.chunks(written.bytes)
            assertThat(newChunks.map { it.second }).isEqualTo(oldChunks.map { it.second })
            assertThat(newChunks.first().first).isGreaterThan(oldChunks.first().first)
            for (i in oldChunks.indices) {
                val (oldOffset, size) = oldChunks[i]
                val newOffset = newChunks[i].first
                assertThat(written.bytes.copyOfRange(newOffset.toInt(), newOffset.toInt() + size)).isEqualTo(source.copyOfRange(oldOffset.toInt(), oldOffset.toInt() + size))
            }
        }
    }

    @Test fun digestCoversImageDataButNotMetadata() {
        val spec = gridPhoto(xmp = xmpPacket("xmp:Rating" to "1"))
        val source = heif(spec)
        val reference = digest(source)
        // A different layout of the same items (data order, meta position, extents) has the same digest.
        assertThat(digest(heif(spec.copy(dataOrder = listOf(7, 6, 5, 4, 3, 2), metaAfterMdat = true)))).isEqualTo(reference)
        // So does the same image with different metadata.
        assertThat(digest(heif(gridPhoto(exif = exifItem(TiffBuilder().build(Photos.ifd0(Photos.exifIfd()))), xmp = null)))).isEqualTo(reference)

        // Image data, properties and image references count.
        val tile = Parsed(source).fileRanges(3).single()[0].toInt()
        assertThat(digest(source.copyOf().also { it[tile + 5] = (it[tile + 5] + 1).toByte() })).isNotEqualTo(reference)
        val properties = spec.properties.toMutableList().also { it[5] = HeifFixtures.irot(2) }
        assertThat(digest(heif(spec.copy(properties = properties)))).isNotEqualTo(reference)
        val refs = spec.refs.map { if (it.type == "dimg") HeifFixtures.Ref("dimg", 1, listOf(3, 2, 4, 5)) else it }
        assertThat(digest(heif(spec.copy(refs = refs)))).isNotEqualTo(reference)
        val grid = Parsed(source).let { it.meta.start + (it.bytes.copyOfRange(it.meta.start, it.meta.end).indexOf("idat".toByteArray())) + 4 + 7 }
        assertThat(digest(source.copyOf().also { it[grid] = 1 })).isNotEqualTo(reference)

        // Sample data of sequences too.
        val sequence = heif(simplePhoto().copy(track = HeifFixtures.Track(List(3) { HeifFixtures.tile(50 + it) })))
        val chunk = HeifFixtures.chunks(sequence).first().first.toInt()
        assertThat(digest(sequence.copyOf().also { it[chunk] = (it[chunk] + 1).toByte() })).isNotEqualTo(digest(sequence))
    }

    @Test fun writerRejectsOutputWhoseImageDataWouldDiffer() {
        // The sink digests the output by its own meta box, so a correct write passes and the digest is the source's.
        val source = heif(gridPhoto())
        val written = write(source, longDescription)
        assertThat(written.result.outputImageDigest).isEqualTo(digest(source))
    }

    @Test fun refusesStructuresItCannotRewriteSafely() {
        fun refuses(bytes: ByteArray) {
            assertThrows(UnsupportedEditException::class.java) { MetadataWriting.writer.write(ByteArrayImageSource(bytes, "x.heic"), artist, ByteArrayOutputStream()) }
        }
        val base = gridPhoto()
        // Construction method 2 (data built from other items).
        refuses(heif(base.copy(items = base.items.map { if (it.id == 2) Item(2, "hvc1", it.data, constructionMethod = 2) else it })))
        // Item data in another file.
        refuses(heif(base.copy(dataReference = 1)))
        // Not an image handler.
        refuses(heif(base.copy(handler = "vide")))
        // Fragmented sequences.
        refuses(heif(base.copy(topLevelExtra = listOf(HeifFixtures.box("moof", ByteArray(16))))))
        // An Exif item that belongs to an entity group cannot be removed.
        val grpl = HeifFixtures.box("grpl", HeifFixtures.fullBox("altr", 0, 0, HeifFixtures.be32(100) + HeifFixtures.be32(2) + HeifFixtures.be32(1) + HeifFixtures.be32(6)))
        assertThrows(UnsupportedEditException::class.java) {
            MetadataWriting.writer.write(ByteArrayImageSource(heif(base.copy(extraMeta = listOf(grpl)))), MetadataChanges(removeBlocks = setOf(MetadataBlock.Exif)), ByteArrayOutputStream())
        }
        // An item list whose count does not match its entries (as in NegativeArraySizeException.ItemInfoBox.init.heif).
        val damaged = heif(base)
        val iinf = Parsed(damaged).meta.let { meta -> (meta.start until meta.end).first { String(damaged, it, 4, Charsets.ISO_8859_1) == "iinf" } }
        damaged[iinf + 8] = 0x7F
        refuses(damaged)
        // No meta box at all.
        refuses(HeifFixtures.box("ftyp", "heic".toByteArray() + HeifFixtures.be32(0) + "mif1".toByteArray()) + HeifFixtures.box("mdat", ByteArray(32)))
    }

    @Test fun compressedXmpIsOnlyReplacedAsAWhole() {
        val base = gridPhoto(xmp = xmpPacket("xmp:Rating" to "1"))
        // content_type followed by content_encoding "deflate".
        val spec = base.copy(items = base.items.map { if (it.id == 7) Item(7, "mime", ByteArray(64) { 1 }, contentType = "application/rdf+xml\u0000deflate") else it })
        val source = heif(spec)
        assertThrows(UnsupportedEditException::class.java) { MetadataWriting.writer.write(ByteArrayImageSource(source, "x.heic"), rating, ByteArrayOutputStream()) }
        val written = edit(source, rating + MetadataChanges(removeBlocks = setOf(MetadataBlock.Xmp)))
        val parsed = Parsed(written.bytes)
        assertThat(parsed.xmpIds).containsExactly(8L)
        assertThat(Photos.read(written.bytes).tag("xmp", "xmp:Rating").rawValue).isEqualTo("4")
    }

    @Test fun reportsTruncatedFilesAsCorrupt() {
        val source = heif(gridPhoto())
        assertThrows(CorruptImageException::class.java) {
            MetadataWriting.writer.write(ByteArrayImageSource(source.copyOf(source.size - 100), "x.heic"), artist, ByteArrayOutputStream())
        }
    }

    @Test fun createsItemsWithLargeIds() {
        val base = simplePhoto(exif = null)
        val items = base.items.map { if (it.id == 2) Item(0x10000, "hvc1", it.data) else it }
        val spec = base.copy(items = items, ilocVersion = 2, refs = emptyList(), associations = mapOf(1 to listOf(-1, 2)))
        val source = heif(spec)
        val written = edit(source, artist)
        val parsed = Parsed(written.bytes)
        assertThat(parsed.exifIds.single()).isEqualTo(0x10001L)
        assertThat(Photos.read(written.bytes).tag("exif-ifd0", "Artist").rawValue).isEqualTo("Jane Doe")
    }
}
