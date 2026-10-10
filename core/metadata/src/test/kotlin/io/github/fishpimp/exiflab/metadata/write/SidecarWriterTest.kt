package io.github.fishpimp.exiflab.metadata.write

import com.adobe.internal.xmp.XMPMeta
import com.adobe.internal.xmp.XMPMetaFactory
import com.google.common.truth.Truth.assertThat
import io.github.fishpimp.exiflab.metadata.fixtures.Photos
import io.github.fishpimp.exiflab.metadata.write.WriteFixtures.ascii
import io.github.fishpimp.exiflab.metadata.write.WriteFixtures.rationals
import io.github.fishpimp.exiflab.metadata.write.WriteFixtures.set
import java.io.ByteArrayOutputStream
import org.junit.Assert.assertThrows
import org.junit.Test

class SidecarWriterTest {
    private fun sidecar(existing: String?, changes: MetadataChanges): Pair<String, XMPMeta> {
        val out = ByteArrayOutputStream()
        MetadataWriting.sidecar.write(existing?.toByteArray(), changes, out)
        val text = out.toString(Charsets.UTF_8)
        return text to XMPMetaFactory.parseFromBuffer(out.toByteArray())
    }

    private fun XMPMeta.text(namespace: String, name: String): String? = getPropertyString(namespace, name)

    @Test fun mapsExifChangesToXmp() {
        val (text, meta) = sidecar(
            null,
            MetadataChanges(
                exif = listOf(
                    ascii(ExifIfd.Exif, Photos.DATE_TIME_ORIGINAL, "2024:05:01 14:03:22"),
                    ascii(ExifIfd.Exif, Photos.SUB_SEC_TIME_ORIGINAL, "123"),
                    ascii(ExifIfd.Exif, Photos.OFFSET_TIME_ORIGINAL, "+02:00"),
                    ascii(ExifIfd.Exif, Photos.DATE_TIME_DIGITIZED, "2024:05:01 14:03:22"),
                    ascii(ExifIfd.Primary, Photos.DATE_TIME, "2024:06:01 08:00:00"),
                    ascii(ExifIfd.Gps, 0x0001, "S"),
                    set(ExifIfd.Gps, 0x0002, rationals(33L to 1L, 51L to 1L, 2160L to 100L)),
                    ascii(ExifIfd.Gps, 0x0003, "E"),
                    set(ExifIfd.Gps, 0x0004, rationals(151L to 1L, 12L to 1L, 3540L to 100L)),
                    set(ExifIfd.Gps, 0x0005, ExifValue.Bytes(listOf(0))),
                    set(ExifIfd.Gps, 0x0006, rationals(125L to 10L)),
                    set(ExifIfd.Gps, 0x0007, rationals(12L to 1L, 30L to 1L, 15L to 1L)),
                    ascii(ExifIfd.Gps, 0x001D, "2024:05:01"),
                    set(ExifIfd.Gps, 0x0000, ExifValue.Bytes(listOf(2, 3, 0, 0))),
                    ascii(ExifIfd.Primary, Photos.ARTIST, "Jane Doe; John Roe"),
                    ascii(ExifIfd.Primary, Photos.COPYRIGHT, "(c) 2024 Jane Doe"),
                    ascii(ExifIfd.Primary, 0x010E, "A harbour at dusk"),
                    ascii(ExifIfd.Primary, Photos.MAKE, "Google"),
                    set(ExifIfd.Primary, Photos.ORIENTATION, ExifValue.Shorts(listOf(6))),
                    set(ExifIfd.Exif, Photos.ISO, ExifValue.Shorts(listOf(400))),
                    set(ExifIfd.Exif, Photos.F_NUMBER, rationals(18L to 10L)),
                    set(ExifIfd.Exif, Photos.FLASH, ExifValue.Shorts(listOf(0x19))),
                    ascii(ExifIfd.Exif, Photos.LENS_MODEL, "Pixel 8 back camera"),
                    ascii(ExifIfd.Exif, Photos.BODY_SERIAL, "SN1"),
                    set(ExifIfd.Exif, Photos.EXIF_VERSION, ExifValue.Undefined("0232".toByteArray())),
                    set(ExifIfd.Exif, 0x9286, ExifValue.Undefined("ASCII\u0000\u0000\u0000Hello".toByteArray())),
                    ascii(ExifIfd.Interoperability, 0x0001, "R98"),
                ),
            ),
        )
        assertThat(meta.text(EXIF, "DateTimeOriginal")).isEqualTo("2024-05-01T14:03:22.123+02:00")
        assertThat(meta.text(PHOTOSHOP, "DateCreated")).isEqualTo("2024-05-01T14:03:22.123+02:00")
        assertThat(meta.text(XMP, "CreateDate")).isEqualTo("2024-05-01T14:03:22")
        assertThat(meta.text(XMP, "ModifyDate")).isEqualTo("2024-06-01T08:00:00")
        assertThat(meta.text(EXIF, "GPSLatitude")).isEqualTo("33,51.3600S")
        assertThat(meta.text(EXIF, "GPSLongitude")).isEqualTo("151,12.5900E")
        assertThat(meta.text(EXIF, "GPSAltitudeRef")).isEqualTo("0")
        assertThat(meta.text(EXIF, "GPSAltitude")).isEqualTo("125/10")
        assertThat(meta.text(EXIF, "GPSTimeStamp")).isEqualTo("2024-05-01T12:30:15Z")
        assertThat(meta.text(EXIF, "GPSVersionID")).isEqualTo("2.3.0.0")
        assertThat(meta.getArrayItem(DC, "creator", 1).value).isEqualTo("Jane Doe")
        assertThat(meta.getArrayItem(DC, "creator", 2).value).isEqualTo("John Roe")
        assertThat(meta.getLocalizedText(DC, "rights", null, "x-default").value).isEqualTo("(c) 2024 Jane Doe")
        assertThat(meta.getLocalizedText(DC, "description", null, "x-default").value).isEqualTo("A harbour at dusk")
        assertThat(meta.text(TIFF, "Make")).isEqualTo("Google")
        assertThat(meta.text(TIFF, "Orientation")).isEqualTo("6")
        assertThat(meta.getArrayItem(EXIF, "ISOSpeedRatings", 1).value).isEqualTo("400")
        assertThat(meta.text(EXIF_EX, "PhotographicSensitivity")).isEqualTo("400")
        assertThat(meta.text(EXIF, "FNumber")).isEqualTo("18/10")
        assertThat(meta.getStructField(EXIF, "Flash", EXIF, "Fired").value).isEqualTo("True")
        assertThat(meta.getStructField(EXIF, "Flash", EXIF, "Mode").value).isEqualTo("3")
        assertThat(meta.text(EXIF_EX, "LensModel")).isEqualTo("Pixel 8 back camera")
        assertThat(meta.text(AUX, "Lens")).isEqualTo("Pixel 8 back camera")
        assertThat(meta.text(EXIF_EX, "BodySerialNumber")).isEqualTo("SN1")
        assertThat(meta.text(EXIF, "ExifVersion")).isEqualTo("0232")
        assertThat(meta.getLocalizedText(EXIF, "UserComment", null, "x-default").value).isEqualTo("Hello")
        // Sidecars are bare x:xmpmeta documents, like Lightroom and darktable write them.
        assertThat(text).doesNotContain("<?xpacket")
        assertThat(text.trim()).startsWith("<x:xmpmeta")
    }

    @Test fun mergesIntoAnExistingSidecarKeepingEverythingElse() {
        val existing = """
            <x:xmpmeta xmlns:x="adobe:ns:meta/">
             <rdf:RDF xmlns:rdf="http://www.w3.org/1999/02/22-rdf-syntax-ns#">
              <rdf:Description rdf:about=""
                xmlns:xmp="http://ns.adobe.com/xap/1.0/" xmlns:exif="http://ns.adobe.com/exif/1.0/"
                xmlns:photoshop="http://ns.adobe.com/photoshop/1.0/" xmlns:dc="http://purl.org/dc/elements/1.1/"
                xmlns:crs="http://ns.adobe.com/camera-raw-settings/1.0/" xmlns:darktable="http://darktable.sf.net/"
                xmp:Rating="4" crs:Exposure2012="+0.35" crs:Version="15.0"
                exif:DateTimeOriginal="2024-05-01T14:03:22.5+02:00" photoshop:DateCreated="2024-05-01T14:03:22.5+02:00"
                exif:GPSLatitude="59,19.7500N" darktable:history_end="3">
               <dc:subject><rdf:Bag><rdf:li>harbour</rdf:li><rdf:li>dusk</rdf:li></rdf:Bag></dc:subject>
               <darktable:history><rdf:Seq>
                 <rdf:li darktable:operation="exposure" darktable:enabled="1"/>
                 <rdf:li darktable:operation="colorin" darktable:enabled="1"/>
               </rdf:Seq></darktable:history>
              </rdf:Description>
             </rdf:RDF>
            </x:xmpmeta>
        """.trimIndent()
        val (_, meta) = sidecar(
            existing,
            MetadataChanges(
                exif = listOf(
                    // Only the offset changes: the recorded local time and fraction stay.
                    ascii(ExifIfd.Exif, Photos.OFFSET_TIME_ORIGINAL, "+01:00"),
                    // Only the hemisphere changes.
                    ascii(ExifIfd.Gps, 0x0001, "S"),
                    ascii(ExifIfd.Primary, Photos.ARTIST, "Jane Doe"),
                ),
                xmp = listOf(XmpChange.SetProperty(XMP, "xmp", "Rating", "5")),
            ),
        )
        assertThat(meta.text(EXIF, "DateTimeOriginal")).isEqualTo("2024-05-01T14:03:22.5+01:00")
        assertThat(meta.text(PHOTOSHOP, "DateCreated")).isEqualTo("2024-05-01T14:03:22.5+01:00")
        assertThat(meta.text(EXIF, "GPSLatitude")).isEqualTo("59,19.7500S")
        assertThat(meta.getArrayItem(DC, "creator", 1).value).isEqualTo("Jane Doe")
        assertThat(meta.text(XMP, "Rating")).isEqualTo("5")
        assertThat(meta.text(CRS, "Exposure2012")).isEqualTo("+0.35")
        assertThat(meta.text(CRS, "Version")).isEqualTo("15.0")
        assertThat(meta.countArrayItems(DC, "subject")).isEqualTo(2)
        assertThat(meta.countArrayItems(DARKTABLE, "history")).isEqualTo(2)
        assertThat(meta.getStructField(DARKTABLE, "history[2]", DARKTABLE, "operation").value).isEqualTo("colorin")
        assertThat(meta.text(DARKTABLE, "history_end")).isEqualTo("3")
    }

    @Test fun removalsAndExplicitXmpWin() {
        val existing = """<x:xmpmeta xmlns:x="adobe:ns:meta/"><rdf:RDF xmlns:rdf="http://www.w3.org/1999/02/22-rdf-syntax-ns#">
            <rdf:Description rdf:about="" xmlns:exif="http://ns.adobe.com/exif/1.0/" xmlns:tiff="http://ns.adobe.com/tiff/1.0/"
              exif:GPSLatitude="59,19.75N" exif:GPSLongitude="18,4.1167E" tiff:Make="Old"/></rdf:RDF></x:xmpmeta>"""
        val (_, meta) = sidecar(
            existing,
            MetadataChanges(
                exif = listOf(ExifChange.Remove(ExifIfd.Gps, 0x0002), ExifChange.Remove(ExifIfd.Gps, 0x0004), ascii(ExifIfd.Primary, Photos.MAKE, "Mapped")),
                xmp = listOf(XmpChange.SetProperty(TIFF, "tiff", "Make", "Explicit")),
            ),
        )
        assertThat(meta.doesPropertyExist(EXIF, "GPSLatitude")).isFalse()
        assertThat(meta.doesPropertyExist(EXIF, "GPSLongitude")).isFalse()
        assertThat(meta.text(TIFF, "Make")).isEqualTo("Explicit")
    }

    @Test fun mapsIptcChangesAndHandlesXmpRemoval() {
        val existing = """<x:xmpmeta xmlns:x="adobe:ns:meta/"><rdf:RDF xmlns:rdf="http://www.w3.org/1999/02/22-rdf-syntax-ns#">
            <rdf:Description rdf:about="" xmlns:xmp="http://ns.adobe.com/xap/1.0/" xmp:Rating="2"/></rdf:RDF></x:xmpmeta>"""
        val (_, meta) = sidecar(
            existing,
            MetadataChanges(
                iptc = listOf(IptcChange.Set(2, 25, listOf("a", "b")), IptcChange.Set(2, 90, listOf("Stockholm"))),
                removeBlocks = setOf(MetadataBlock.Xmp),
            ),
        )
        assertThat(meta.doesPropertyExist(XMP, "Rating")).isFalse()
        assertThat(meta.countArrayItems(DC, "subject")).isEqualTo(2)
        assertThat(meta.text(PHOTOSHOP, "City")).isEqualTo("Stockholm")
    }

    @Test fun malformedSidecarsAreRefused() {
        assertThrows(UnsupportedEditException::class.java) {
            MetadataWriting.sidecar.write("<x:xmpmeta><rdf:RDF".toByteArray(), MetadataChanges(), ByteArrayOutputStream())
        }
    }

    @Test fun coordinatesUseDecimalMinutes() {
        val format = io.github.fishpimp.exiflab.metadata.write.sidecar.ExifToXmp.formatCoordinate(
            listOf(io.github.fishpimp.exiflab.metadata.model.Rational(59, 1), io.github.fishpimp.exiflab.metadata.model.Rational(19, 1), io.github.fishpimp.exiflab.metadata.model.Rational(4507, 100)),
            'N',
        )
        assertThat(format).isEqualTo("59,19.75116667N")
    }

    private companion object {
        const val EXIF = "http://ns.adobe.com/exif/1.0/"
        const val EXIF_EX = "http://cipa.jp/exif/1.0/"
        const val TIFF = "http://ns.adobe.com/tiff/1.0/"
        const val AUX = "http://ns.adobe.com/exif/1.0/aux/"
        const val XMP = "http://ns.adobe.com/xap/1.0/"
        const val DC = "http://purl.org/dc/elements/1.1/"
        const val PHOTOSHOP = "http://ns.adobe.com/photoshop/1.0/"
        const val CRS = "http://ns.adobe.com/camera-raw-settings/1.0/"
        const val DARKTABLE = "http://darktable.sf.net/"
    }
}
