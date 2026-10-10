package io.github.fishpimp.exiflab.metadata.edit

import com.google.common.truth.Truth.assertThat
import io.github.fishpimp.exiflab.metadata.fixtures.Containers
import io.github.fishpimp.exiflab.metadata.fixtures.Photos
import io.github.fishpimp.exiflab.metadata.fixtures.TiffBuilder
import io.github.fishpimp.exiflab.metadata.fixtures.Xmp
import io.github.fishpimp.exiflab.metadata.model.MetadataReport
import io.github.fishpimp.exiflab.metadata.model.MetadataTag
import io.github.fishpimp.exiflab.metadata.write.WriteFixtures
import java.awt.color.ColorSpace
import java.awt.color.ICC_Profile

/** Synthetic photos and plan-write-read helpers for the planner and catalog tests. */
object EditFixtures {
    val planner: ChangePlanner = Editing.planner
    val catalog: TagCatalog = Editing.catalog

    private val thumbnail = Containers.jpeg(16, 12)

    /** The IPTC record version (2:0) the writer adds when it rewrites IPTC that lacks one. */
    const val IPTC_RECORD_VERSION = "iptc:512"

    /** One planned and written edit: the report before, the plan, the written bytes and the report after. */
    class Edited(val before: MetadataReport, val plan: EditPlan, val bytes: ByteArray, val after: MetadataReport) {
        fun exif(directoryId: String, tag: Int): String? = after.exifRaw(directoryId, tag)
        fun xmp(path: String): String? = after.xmpRaw(path)
        fun iptc(dataset: Int): String? = after.iptcRaw(dataset)
        fun diff(label: String): FieldDiff = plan.diff.single { it.label == label }
    }

    fun MetadataReport.exifRaw(directoryId: String, tag: Int): String? =
        directories.firstOrNull { it.id == directoryId }?.tags?.firstOrNull { it.id == tag }?.rawValue

    fun MetadataReport.xmpRaw(path: String): String? =
        directories.filter { it.id.startsWith("xmp") }.flatMap { it.tags }.firstOrNull { it.name == path }?.rawValue

    fun MetadataReport.xmpTags(): List<MetadataTag> = directories.filter { it.id.startsWith("xmp") }.flatMap { it.tags }

    fun MetadataReport.iptcRaw(dataset: Int): String? =
        directories.firstOrNull { it.id == "iptc" }?.tags?.firstOrNull { it.id == (2 shl 8 or dataset) }?.rawValue

    fun plan(bytes: ByteArray, vararg operations: EditOperation): EditPlan = planner.plan(Photos.read(bytes), operations.toList())

    /** Plans [operations] against [bytes], writes the plan with the real writer and reads the result back. */
    fun edit(bytes: ByteArray, vararg operations: EditOperation): Edited {
        val before = Photos.read(bytes)
        val plan = planner.plan(before, operations.toList())
        val written = WriteFixtures.write(bytes, plan.changes).bytes
        return Edited(before, plan, written, Photos.read(written))
    }

    /** Asserts that planning [operations] again on the edited file changes nothing (the edit is stable). */
    fun Edited.assertIdempotent(vararg operations: EditOperation) {
        val again = planner.plan(after, operations.toList())
        assertThat(again.diff).isEmpty()
        assertThat(again.changes.isEmpty).isTrue()
    }

    /** A JPEG with EXIF ([ifd0], [exif], optional [gps]), optional XMP description content and IPTC datasets. */
    fun jpeg(
        exif: TiffBuilder.Ifd? = Photos.exifIfd(),
        gps: TiffBuilder.Ifd? = null,
        xmp: Map<String, String>? = null,
        xmpElements: String = "",
        iptc: List<Pair<Int, String>> = emptyList(),
        bigEndian: Boolean = true,
        ifd0: TiffBuilder.Ifd.() -> Unit = {},
    ): ByteArray {
        val tiff = exif?.let { TiffBuilder(bigEndian).build(Photos.ifd0(it, gps, ifd0)) }
        val segments = listOfNotNull(
            xmp?.let { Containers.xmpSegment(Xmp.packet(Xmp.description(it, xmpElements))) },
            iptc.takeIf { it.isNotEmpty() }?.let { Containers.iptcSegment(*it.toTypedArray()) },
        )
        return Photos.jpeg(tiff, *segments.toTypedArray())
    }

    const val XMP_PEOPLE = "<mwg-rs:Regions rdf:parseType=\"Resource\"><mwg-rs:RegionList><rdf:Bag><rdf:li rdf:parseType=\"Resource\">" +
        "<mwg-rs:Name>Jane Doe</mwg-rs:Name><mwg-rs:Type>Face</mwg-rs:Type></rdf:li></rdf:Bag></mwg-rs:RegionList></mwg-rs:Regions>" +
        "<Iptc4xmpExt:PersonInImage xmlns:Iptc4xmpExt=\"http://iptc.org/std/Iptc4xmpExt/2008-02-29/\"><rdf:Bag><rdf:li>Jane Doe</rdf:li></rdf:Bag></Iptc4xmpExt:PersonInImage>"

    const val XMP_HISTORY = "<xmpMM:History xmlns:stEvt=\"http://ns.adobe.com/xap/1.0/sType/ResourceEvent#\"><rdf:Seq>" +
        "<rdf:li stEvt:action=\"saved\" stEvt:when=\"2024-05-02T10:00:00+02:00\" stEvt:softwareAgent=\"Editor 1.0\"/></rdf:Seq></xmpMM:History>" +
        "<photoshop:History>Opened, saved</photoshop:History>"

    val FULL_XMP = mapOf(
        "xmp:CreateDate" to "2024-05-01T14:03:22.123+02:00",
        "xmp:ModifyDate" to "2024-05-01T14:03:22+02:00",
        "exif:DateTimeOriginal" to "2024-05-01T14:03:22.123+02:00",
        "photoshop:DateCreated" to "2024-05-01",
        "photoshop:City" to "Stockholm",
        "photoshop:Country" to "Sweden",
        "exif:GPSLatitude" to "59,19.75N",
        "exif:GPSLongitude" to "18,4.116667E",
        "exif:GPSTimeStamp" to "2024-05-01T12:03:22Z",
        "xmpMM:DocumentID" to "xmp.did:1234",
        "xmpMM:InstanceID" to "xmp.iid:5678",
        "aux:SerialNumber" to "0123456",
        "aux:Lens" to "Pixel lens",
        "tiff:Make" to "Google",
        "xmp:CreatorTool" to "Camera app",
        "xmpRights:Marked" to "True",
        "xmp:Rating" to "3",
    )

    const val FULL_XMP_ELEMENTS = "<dc:creator><rdf:Seq><rdf:li>Jane Doe</rdf:li></rdf:Seq></dc:creator>" +
        "<dc:rights><rdf:Alt><rdf:li xml:lang=\"x-default\">(c) Jane Doe</rdf:li></rdf:Alt></dc:rights>" +
        "<dc:title><rdf:Alt><rdf:li xml:lang=\"x-default\">Old title</rdf:li></rdf:Alt></dc:title>" +
        "<dc:description><rdf:Alt><rdf:li xml:lang=\"x-default\">Old description</rdf:li></rdf:Alt></dc:description>" +
        "<dc:subject><rdf:Bag><rdf:li>alpha</rdf:li><rdf:li>beta</rdf:li></rdf:Bag></dc:subject>" +
        XMP_PEOPLE + XMP_HISTORY

    val FULL_IPTC = listOf(
        5 to "Old title",
        25 to "alpha",
        25 to "beta",
        55 to "20240501",
        60 to "140322+0200",
        80 to "Jane Doe",
        90 to "Stockholm",
        101 to "Sweden",
        110 to "Agency",
        116 to "(c) Jane Doe",
        120 to "Old description",
    )

    /**
     * A phone-like JPEG with every kind of field: EXIF dates with sub-seconds and offsets, serials,
     * owner and lens info, a Canon-style MakerNote, an interoperability IFD, GPS with date and time,
     * an IFD1 thumbnail, XMP copies of dates, names, places, people, history and ids, IPTC copies,
     * an ICC profile and a JPEG comment.
     */
    fun fullJpeg(bigEndian: Boolean = true): ByteArray {
        val makerNote = TiffBuilder.ifd { ascii(0x0006, "Canon EOS R5"); ascii(0x000C, "123456789") }
        val exif = Photos.exifIfd {
            ascii(Photos.DATE_TIME_DIGITIZED, "2024:05:01 14:03:22")
            ascii(0x9292, "123")
            ascii(Photos.OFFSET_TIME, "+02:00")
            ascii(0x9012, "+02:00")
            ascii(Photos.BODY_SERIAL, "SN-998877")
            ascii(0xA435, "LENS-42")
            ascii(Photos.IMAGE_UNIQUE_ID, "A1B2C3D4E5F6")
            ascii(0xA430, "Jane Doe")
            ascii(0xA433, "Google")
            subIfd(Photos.INTEROP_IFD, TiffBuilder.ifd { ascii(0x0001, "R98") })
            makerNoteIfd(Photos.MAKER_NOTE, makerNote)
        }
        val gps = Photos.gpsIfd {
            rational(0x0007, 12L to 1L, 3L to 1L, 22L to 1L)
            ascii(0x001D, "2024:05:01")
            byte(0x0005, 0)
            rational(0x0006, 12345L to 100L)
            ascii(0x0012, "WGS-84")
        }
        val ifd0 = Photos.ifd0(exif, gps) {
            // Canon, so the reader decodes the MakerNote.
            ascii(Photos.MAKE, "Canon")
            ascii(Photos.ARTIST, "Jane Doe")
            ascii(Photos.COPYRIGHT, "(c) Jane Doe")
            ascii(0x013C, "Workstation")
        }
        ifd0.next = TiffBuilder.ifd {
            short(0x0103, 6)
            offsetOf(0x0201, thumbnail)
            long(0x0202, thumbnail.size.toLong())
        }
        return Photos.jpeg(
            TiffBuilder(bigEndian).build(ifd0),
            Containers.xmpSegment(Xmp.packet(Xmp.description(FULL_XMP, FULL_XMP_ELEMENTS))),
            Containers.iptcSegment(*FULL_IPTC.toTypedArray()),
            Containers.iccSegment(ICC_Profile.getInstance(ColorSpace.CS_sRGB).data),
            0xFE to "Shot by Jane".toByteArray(),
        )
    }
}
