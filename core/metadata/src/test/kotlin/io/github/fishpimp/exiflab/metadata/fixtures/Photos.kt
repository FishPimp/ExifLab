package io.github.fishpimp.exiflab.metadata.fixtures

import io.github.fishpimp.exiflab.metadata.ByteArrayImageSource
import io.github.fishpimp.exiflab.metadata.DefaultMetadataReader
import io.github.fishpimp.exiflab.metadata.model.MetadataReport
import io.github.fishpimp.exiflab.metadata.model.MetadataTag

/** Ready-made Exif structures and report helpers shared by the tests. */
object Photos {
    // Exif tag ids used across tests.
    const val MAKE = 0x010F
    const val MODEL = 0x0110
    const val ORIENTATION = 0x0112
    const val SOFTWARE = 0x0131
    const val DATE_TIME = 0x0132
    const val ARTIST = 0x013B
    const val COPYRIGHT = 0x8298
    const val EXPOSURE_TIME = 0x829A
    const val F_NUMBER = 0x829D
    const val EXIF_IFD = 0x8769
    const val GPS_IFD = 0x8825
    const val ISO = 0x8827
    const val EXIF_VERSION = 0x9000
    const val DATE_TIME_ORIGINAL = 0x9003
    const val DATE_TIME_DIGITIZED = 0x9004
    const val OFFSET_TIME = 0x9010
    const val OFFSET_TIME_ORIGINAL = 0x9011
    const val EXPOSURE_BIAS = 0x9204
    const val FLASH = 0x9209
    const val FOCAL_LENGTH = 0x920A
    const val MAKER_NOTE = 0x927C
    const val SUB_SEC_TIME_ORIGINAL = 0x9291
    const val PIXEL_X = 0xA002
    const val PIXEL_Y = 0xA003
    const val INTEROP_IFD = 0xA005
    const val IMAGE_UNIQUE_ID = 0xA420
    const val BODY_SERIAL = 0xA431
    const val LENS_MODEL = 0xA434
    const val FOCAL_LENGTH_35 = 0xA405

    /** IFD0 of a typical phone photo, pointing at [exif] and optionally [gps]. */
    fun ifd0(exif: TiffBuilder.Ifd, gps: TiffBuilder.Ifd? = null, configure: TiffBuilder.Ifd.() -> Unit = {}) = TiffBuilder.ifd {
        ascii(MAKE, "Google")
        ascii(MODEL, "Pixel 8")
        short(ORIENTATION, 6)
        ascii(SOFTWARE, "HDR+ 1.0.612345")
        ascii(DATE_TIME, "2024:05:01 14:03:22")
        subIfd(EXIF_IFD, exif)
        gps?.let { subIfd(GPS_IFD, it) }
        configure()
    }

    /** Exif SubIFD with typical capture settings. */
    fun exifIfd(configure: TiffBuilder.Ifd.() -> Unit = {}) = TiffBuilder.ifd {
        rational(EXPOSURE_TIME, 1L to 250L)
        rational(F_NUMBER, 18L to 10L)
        short(ISO, 400)
        undefined(EXIF_VERSION, "0232".toByteArray())
        ascii(DATE_TIME_ORIGINAL, "2024:05:01 14:03:22")
        ascii(SUB_SEC_TIME_ORIGINAL, "123")
        ascii(OFFSET_TIME_ORIGINAL, "+02:00")
        srational(EXPOSURE_BIAS, -7 to 10)
        short(FLASH, 0x19)
        rational(FOCAL_LENGTH, 690L to 100L)
        short(FOCAL_LENGTH_35, 26)
        ascii(LENS_MODEL, "Pixel 8 back camera 6.9mm f/1.68")
        long(PIXEL_X, 4000)
        long(PIXEL_Y, 3000)
        configure()
    }

    /** GPS IFD for a position given in degrees, minutes and seconds. */
    fun gpsIfd(
        latitude: Triple<Int, Int, Int> = Triple(59, 19, 45),
        latitudeRef: String = "N",
        longitude: Triple<Int, Int, Int> = Triple(18, 4, 7),
        longitudeRef: String = "E",
        configure: TiffBuilder.Ifd.() -> Unit = {},
    ) = TiffBuilder.ifd {
        byte(0x0000, 2, 3, 0, 0)
        ascii(0x0001, latitudeRef)
        rational(0x0002, latitude.first.toLong() to 1L, latitude.second.toLong() to 1L, latitude.third.toLong() to 1L)
        ascii(0x0003, longitudeRef)
        rational(0x0004, longitude.first.toLong() to 1L, longitude.second.toLong() to 1L, longitude.third.toLong() to 1L)
        configure()
    }

    /** A JPEG of [width] x [height] carrying [tiff] as its Exif block plus any [extra] segments. */
    fun jpeg(tiff: ByteArray?, vararg extra: Pair<Int, ByteArray>, width: Int = 64, height: Int = 48): ByteArray =
        Containers.jpegWithSegments(Containers.jpeg(width, height), *(listOfNotNull(tiff?.let(Containers::exifSegment)) + extra).toTypedArray())

    fun read(bytes: ByteArray, name: String? = null): MetadataReport = DefaultMetadataReader().read(ByteArrayImageSource(bytes, name))

    fun MetadataReport.directory(id: String) = directories.single { it.id == id }

    fun MetadataReport.tag(directoryId: String, name: String): MetadataTag = directory(directoryId).tags.single { it.name == name }

    fun MetadataReport.tagOrNull(directoryId: String, name: String): MetadataTag? =
        directories.firstOrNull { it.id == directoryId }?.tags?.firstOrNull { it.name == name }
}
