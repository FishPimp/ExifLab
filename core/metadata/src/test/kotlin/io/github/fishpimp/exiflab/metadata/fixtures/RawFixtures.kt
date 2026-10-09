package io.github.fishpimp.exiflab.metadata.fixtures

import io.github.fishpimp.exiflab.metadata.fixtures.Containers.be16
import io.github.fishpimp.exiflab.metadata.fixtures.Containers.be32
import io.github.fishpimp.exiflab.metadata.fixtures.Containers.box
import io.github.fishpimp.exiflab.metadata.fixtures.Containers.fullBoxHeader
import io.github.fishpimp.exiflab.metadata.fixtures.Containers.uuidBox

/** Synthetic RAW and HEIF files that mirror the real container layouts. */
object RawFixtures {
    const val CANON_UUID = "85c0b687-820f-11e0-8111-f4ce462b6a48"
    const val XMP_UUID = "be7acfcb-97a9-42e8-9c71-999491e3afac"
    const val PREVIEW_UUID = "eaf42b5e-1c98-4b88-b9fb-b7dc406e4d16"

    /** JPEG data that starts like the lossless-JPEG raw strips of DNG/CR2 (SOF3), which is no preview. */
    val losslessRawStrip: ByteArray =
        byteArrayOf(0xFF.toByte(), 0xD8.toByte(), 0xFF.toByte(), 0xC3.toByte(), 0x00, 0x0B, 0x0E, 0x0F, 0xA0.toByte(), 0x17, 0x70, 0x01, 0x01, 0x11, 0x00) +
            ByteArray(4096)

    /** A Fujifilm RAF whose embedded 320 x 213 JPEG carries the Exif (orientation 6). */
    fun raf(): ByteArray {
        val exif = TiffBuilder().build(
            Photos.ifd0(Photos.exifIfd()) {
                ascii(Photos.MAKE, "FUJIFILM")
                ascii(Photos.MODEL, "X-T3")
            },
        )
        return Containers.raf(Photos.jpeg(exif, width = 320, height = 213))
    }

    /**
     * A Canon CR3 box tree: ftyp, moov > uuid(Canon) > CNCV, CMT1-4, THMB (160 x 120), the XMP
     * uuid box and the preview uuid box with a 1620 x 1080 PRVW JPEG.
     */
    fun cr3(preview: ByteArray = Containers.jpeg(1620, 1080), thumbnail: ByteArray = Containers.jpeg(160, 120)): ByteArray {
        val cmt1 = TiffBuilder.tiff(bigEndian = false) {
            ascii(Photos.MAKE, "Canon")
            ascii(Photos.MODEL, "Canon EOS R6")
            short(Photos.ORIENTATION, 8)
            ascii(Photos.DATE_TIME, "2024:06:01 12:00:00")
        }
        val cmt2 = TiffBuilder.tiff(bigEndian = false) {
            rational(Photos.EXPOSURE_TIME, 1L to 500L)
            rational(Photos.F_NUMBER, 40L to 10L)
            short(Photos.ISO, 800)
            ascii(Photos.DATE_TIME_ORIGINAL, "2024:06:01 12:00:00")
            ascii(Photos.OFFSET_TIME_ORIGINAL, "+01:00")
            ascii(Photos.LENS_MODEL, "RF50mm F1.8 STM")
            ascii(Photos.BODY_SERIAL, "012345678901")
            long(Photos.PIXEL_X, 5472)
            long(Photos.PIXEL_Y, 3648)
        }
        val cmt3 = TiffBuilder.tiff(bigEndian = false) {
            ascii(0x0006, "Canon EOS R6")
            ascii(0x0009, "Jane Doe")
            long(0x000C, 123456)
        }
        val cmt4 = TiffBuilder.tiff(bigEndian = false) {
            byte(0x0000, 2, 3, 0, 0)
            ascii(0x0001, "N")
            rational(0x0002, 48L to 1L, 51L to 1L, 30L to 1L)
            ascii(0x0003, "E")
            rational(0x0004, 2L to 1L, 17L to 1L, 40L to 1L)
        }
        // THMB: version/flags, width, height, JPEG size, two unknown shorts, JPEG.
        val thmb = box("THMB", fullBoxHeader(), be16(160), be16(120), be32(thumbnail.size), be16(1), be16(0), thumbnail)
        // PRVW: unknown long, unknown short, width, height, unknown short, JPEG size, JPEG.
        val prvw = box("PRVW", be32(0), be16(1), be16(1620), be16(1080), be16(1), be32(preview.size), preview)
        val canon = uuidBox(
            CANON_UUID,
            box("CNCV", "CanonCR3_001/01.09.00/00.00.00".toByteArray()),
            box("CMT1", cmt1),
            box("CMT2", cmt2),
            box("CMT3", cmt3),
            box("CMT4", cmt4),
            thmb,
        )
        val xmp = Xmp.packet(Xmp.description(mapOf("xmp:Rating" to "3")))
        return box("ftyp", "crx ".toByteArray(), be32(1), "crx ".toByteArray(), "isom".toByteArray()) +
            box("moov", canon, box("mvhd", ByteArray(100))) +
            uuidBox(XMP_UUID, xmp.toByteArray()) +
            uuidBox(PREVIEW_UUID, ByteArray(8), prvw) +
            box("mdat", ByteArray(512))
    }

    /**
     * A DNG-like TIFF: IFD0 is a small JPEG thumbnail (orientation 8), SubIFD 1 the lossless raw
     * image (6000 x 4000, default crop 5984 x 3992), SubIFD 2 a 640 x 480 JPEG preview.
     */
    fun dng(bigEndian: Boolean = false): ByteArray {
        val thumbnail = Containers.jpeg(160, 120)
        val preview = Containers.jpeg(640, 480)
        val raw = TiffBuilder.ifd {
            long(0x00FE, 0)
            long(0x0100, 6000)
            long(0x0101, 4000)
            short(0x0103, 7)
            offsetOf(0x0111, losslessRawStrip)
            long(0x0117, losslessRawStrip.size.toLong())
            long(0xC620, 5984, 3992)
        }
        val previewIfd = TiffBuilder.ifd {
            long(0x00FE, 1)
            long(0x0100, 640)
            long(0x0101, 480)
            short(0x0103, 7)
            short(0x0106, 6)
            offsetOf(0x0111, preview)
            long(0x0117, preview.size.toLong())
        }
        val ifd0 = TiffBuilder.ifd {
            long(0x00FE, 1)
            long(0x0100, 160)
            long(0x0101, 120)
            ascii(Photos.MAKE, "Google")
            ascii(Photos.MODEL, "Pixel 8 Pro")
            short(Photos.ORIENTATION, 8)
            offsetOf(0x0201, thumbnail)
            long(0x0202, thumbnail.size.toLong())
            subIfd(0x014A, raw, previewIfd)
            subIfd(Photos.EXIF_IFD, Photos.exifIfd())
            byte(0xC612, 1, 4, 0, 0)
        }
        return TiffBuilder(bigEndian).build(ifd0)
    }

    /** A Panasonic RW2 ("IIU"): sensor borders in IFD0 and a 320 x 240 JpgFromRaw with Exif. */
    fun rw2(): ByteArray {
        val exif = TiffBuilder().build(Photos.ifd0(Photos.exifIfd()) { ascii(Photos.MAKE, "Panasonic"); ascii(Photos.MODEL, "DC-G9") })
        val jpgFromRaw = Photos.jpeg(exif, width = 320, height = 240)
        return TiffBuilder(bigEndian = false, magic = 0x55).build(
            TiffBuilder.ifd {
                short(0x0002, 5200)
                short(0x0003, 3904)
                short(0x0004, 8)
                short(0x0005, 8)
                short(0x0006, 3896)
                short(0x0007, 5192)
                ascii(Photos.MAKE, "Panasonic")
                ascii(Photos.MODEL, "DC-G9")
                short(Photos.ORIENTATION, 3)
                undefined(0x002E, jpgFromRaw)
            },
        )
    }

    /**
     * A HEIF file whose primary item (a 4032 x 3024 grid) is described by the second `ispe`
     * property (the first is a 512 x 512 tile size), with Exif and XMP items in `mdat`.
     */
    fun heif(exifTiff: ByteArray, xmp: String): ByteArray {
        val exifItem = be32(6) + "Exif\u0000\u0000".toByteArray(Charsets.ISO_8859_1) + exifTiff
        val xmpItem = xmp.toByteArray()
        fun build(mdatPayloadStart: Int): ByteArray {
            val hdlr = box("hdlr", fullBoxHeader(), be32(0), "pict".toByteArray(), ByteArray(12), byteArrayOf(0))
            val pitm = box("pitm", fullBoxHeader(), be16(1))
            fun infe(id: Int, type: String, contentType: String? = null) = box(
                "infe", fullBoxHeader(version = 2), be16(id), be16(0), type.toByteArray(), byteArrayOf(0),
                contentType?.let { it.toByteArray() + 0.toByte() } ?: ByteArray(0),
            )
            val iinf = box("iinf", fullBoxHeader(), be16(3), infe(1, "grid"), infe(2, "Exif"), infe(3, "mime", "application/rdf+xml"))
            fun location(id: Int, offset: Int, length: Int) = be16(id) + be16(0) + be16(1) + be32(offset) + be32(length)
            val iloc = box(
                "iloc", fullBoxHeader(), byteArrayOf(0x44, 0x00), be16(3),
                location(1, mdatPayloadStart + exifItem.size + xmpItem.size, 64),
                location(2, mdatPayloadStart, exifItem.size),
                location(3, mdatPayloadStart + exifItem.size, xmpItem.size),
            )
            fun ispe(width: Int, height: Int) = box("ispe", fullBoxHeader(), be32(width), be32(height))
            val ipco = box("ipco", box("hvcC", ByteArray(23)), ispe(512, 512), ispe(4032, 3024))
            val ipma = box("ipma", fullBoxHeader(), be32(1), be16(1), byteArrayOf(2, 0x81.toByte(), 0x03))
            val meta = box("meta", fullBoxHeader(), hdlr, pitm, iinf, iloc, box("iprp", ipco, ipma))
            val ftyp = box("ftyp", "heic".toByteArray(), be32(0), "mif1".toByteArray(), "heic".toByteArray())
            return ftyp + meta + box("mdat", exifItem, xmpItem, ByteArray(64))
        }
        val draft = build(0)
        val mdatStart = draft.size - (exifItem.size + xmpItem.size + 64)
        return build(mdatStart)
    }
}
