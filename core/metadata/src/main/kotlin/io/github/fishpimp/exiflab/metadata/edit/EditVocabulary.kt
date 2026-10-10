package io.github.fishpimp.exiflab.metadata.edit

/** XMP schema namespaces the editor reads and writes, with the prefixes used when a packet lacks them. */
internal object XmpNs {
    const val EXIF = "http://ns.adobe.com/exif/1.0/"
    const val EXIF_EX = "http://cipa.jp/exif/1.0/"
    const val TIFF = "http://ns.adobe.com/tiff/1.0/"
    const val AUX = "http://ns.adobe.com/exif/1.0/aux/"
    const val XMP = "http://ns.adobe.com/xap/1.0/"
    const val XMP_MM = "http://ns.adobe.com/xap/1.0/mm/"
    const val XMP_RIGHTS = "http://ns.adobe.com/xap/1.0/rights/"
    const val DC = "http://purl.org/dc/elements/1.1/"
    const val PHOTOSHOP = "http://ns.adobe.com/photoshop/1.0/"
    const val IPTC_CORE = "http://iptc.org/std/Iptc4xmpCore/1.0/xmlns/"
    const val IPTC_EXT = "http://iptc.org/std/Iptc4xmpExt/2008-02-29/"
    const val MWG_RS = "http://www.metadataworkinggroup.com/schemas/regions/"
    const val MP = "http://ns.microsoft.com/photo/1.2/"
    const val PLUS = "http://ns.useplus.org/ldf/xmp/1.0/"

    val PREFIXES = mapOf(
        EXIF to "exif", EXIF_EX to "exifEX", TIFF to "tiff", AUX to "aux", XMP to "xmp", XMP_MM to "xmpMM",
        XMP_RIGHTS to "xmpRights", DC to "dc", PHOTOSHOP to "photoshop", IPTC_CORE to "Iptc4xmpCore",
        IPTC_EXT to "Iptc4xmpExt", MWG_RS to "mwg-rs", MP to "MP", PLUS to "plus",
    )
}

/** A top-level XMP property: its schema namespace and local name (without prefix). */
internal data class XmpName(val namespace: String, val name: String) {
    val preferredPrefix: String get() = XmpNs.PREFIXES[namespace] ?: "ns"
}

/** Numeric EXIF tag ids the planner and catalog use. */
internal object ExifTags {
    // IFD0
    const val IMAGE_DESCRIPTION = 0x010E
    const val MAKE = 0x010F
    const val MODEL = 0x0110
    const val ORIENTATION = 0x0112
    const val SOFTWARE = 0x0131
    const val DATE_TIME = 0x0132
    const val ARTIST = 0x013B
    const val HOST_COMPUTER = 0x013C
    const val COPYRIGHT = 0x8298
    const val EXIF_IFD_POINTER = 0x8769
    const val GPS_IFD_POINTER = 0x8825
    const val XP_AUTHOR = 0x9C9D
    const val UNIQUE_CAMERA_MODEL = 0xC614
    const val CAMERA_SERIAL_NUMBER = 0xC62F

    // Exif IFD
    const val EXPOSURE_TIME = 0x829A
    const val F_NUMBER = 0x829D
    const val EXPOSURE_PROGRAM = 0x8822
    const val ISO = 0x8827
    const val EXIF_VERSION = 0x9000
    const val DATE_TIME_ORIGINAL = 0x9003
    const val DATE_TIME_DIGITIZED = 0x9004
    const val OFFSET_TIME = 0x9010
    const val OFFSET_TIME_ORIGINAL = 0x9011
    const val OFFSET_TIME_DIGITIZED = 0x9012
    const val EXPOSURE_BIAS = 0x9204
    const val METERING_MODE = 0x9207
    const val FLASH = 0x9209
    const val FOCAL_LENGTH = 0x920A
    const val MAKER_NOTE = 0x927C
    const val USER_COMMENT = 0x9286
    const val SUB_SEC_TIME = 0x9290
    const val SUB_SEC_TIME_ORIGINAL = 0x9291
    const val SUB_SEC_TIME_DIGITIZED = 0x9292
    const val INTEROP_IFD_POINTER = 0xA005
    const val FOCAL_LENGTH_35MM = 0xA405
    const val WHITE_BALANCE = 0xA403
    const val IMAGE_UNIQUE_ID = 0xA420
    const val CAMERA_OWNER_NAME = 0xA430
    const val BODY_SERIAL_NUMBER = 0xA431
    const val LENS_SPECIFICATION = 0xA432
    const val LENS_MAKE = 0xA433
    const val LENS_MODEL = 0xA434
    const val LENS_SERIAL_NUMBER = 0xA435

    // GPS IFD
    const val GPS_VERSION_ID = 0x0000
    const val GPS_LATITUDE_REF = 0x0001
    const val GPS_LATITUDE = 0x0002
    const val GPS_LONGITUDE_REF = 0x0003
    const val GPS_LONGITUDE = 0x0004
    const val GPS_ALTITUDE_REF = 0x0005
    const val GPS_ALTITUDE = 0x0006
    const val GPS_TIME_STAMP = 0x0007
    const val GPS_MAP_DATUM = 0x0012
    const val GPS_DATE_STAMP = 0x001D
}

/** IPTC-IIM application record (2:xx) datasets the planner and catalog use. */
internal object IptcTags {
    const val RECORD = 2
    const val OBJECT_NAME = 5
    const val KEYWORDS = 25
    const val CONTENT_LOCATION_CODE = 26
    const val CONTENT_LOCATION_NAME = 27
    const val RELEASE_DATE = 30
    const val RELEASE_TIME = 35
    const val EXPIRATION_DATE = 37
    const val EXPIRATION_TIME = 38
    const val REFERENCE_DATE = 47
    const val DATE_CREATED = 55
    const val TIME_CREATED = 60
    const val DIGITAL_CREATION_DATE = 62
    const val DIGITAL_CREATION_TIME = 63
    const val BY_LINE = 80
    const val BY_LINE_TITLE = 85
    const val CITY = 90
    const val SUB_LOCATION = 92
    const val PROVINCE_STATE = 95
    const val COUNTRY_CODE = 100
    const val COUNTRY_NAME = 101
    const val HEADLINE = 105
    const val CREDIT = 110
    const val COPYRIGHT_NOTICE = 116
    const val CONTACT = 118
    const val CAPTION = 120
    const val WRITER_EDITOR = 122

    /** Maximum lengths in bytes from the IPTC-IIM 4.2 specification. */
    val MAX_BYTES = mapOf(
        OBJECT_NAME to 64, KEYWORDS to 64, BY_LINE to 32, BY_LINE_TITLE to 32, CITY to 32, SUB_LOCATION to 32,
        PROVINCE_STATE to 32, COUNTRY_CODE to 3, COUNTRY_NAME to 64, HEADLINE to 256, CREDIT to 32,
        COPYRIGHT_NOTICE to 128, CONTACT to 128, CAPTION to 2000, WRITER_EDITOR to 32,
    )
}
