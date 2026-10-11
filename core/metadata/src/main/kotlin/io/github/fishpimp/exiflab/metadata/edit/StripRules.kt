package io.github.fishpimp.exiflab.metadata.edit

import io.github.fishpimp.exiflab.metadata.write.ExifIfd

/**
 * The fields each [StripCategory] removes, by block. Location, MakerNotes, Thumbnail and All are
 * handled as operations of their own (GPS IFD, whole blocks) in [PlanSteps].
 */
internal object StripRules {
    class Fields(
        val exif: List<ExifKey> = emptyList(),
        val xmp: List<XmpName> = emptyList(),
        /** Schemas removed as a whole. */
        val xmpNamespaces: List<String> = emptyList(),
        val iptc: List<Int> = emptyList(),
    )

    private fun primary(vararg tags: Int) = tags.map { ExifKey(ExifIfd.Primary, it) }
    private fun exif(vararg tags: Int) = tags.map { ExifKey(ExifIfd.Exif, it) }
    private fun xmp(namespace: String, vararg names: String) = names.map { XmpName(namespace, it) }

    /** Serial numbers and unique image or document ids. MakerNote serials go with the MakerNotes category. */
    val deviceIdentifiers = Fields(
        exif = primary(ExifTags.CAMERA_SERIAL_NUMBER) +
            exif(ExifTags.BODY_SERIAL_NUMBER, ExifTags.LENS_SERIAL_NUMBER, ExifTags.IMAGE_UNIQUE_ID),
        xmp = xmp(XmpNs.XMP_MM, "DocumentID", "InstanceID", "OriginalDocumentID") +
            xmp(XmpNs.AUX, "SerialNumber", "LensSerialNumber") +
            xmp(XmpNs.EXIF_EX, "BodySerialNumber", "LensSerialNumber", "ImageUniqueID") +
            xmp(XmpNs.EXIF, "ImageUniqueID") +
            xmp(XmpNs.PHOTOSHOP, "DocumentAncestors"),
    )

    /** Camera and lens make and model, software and computer names. */
    val deviceInfo = Fields(
        exif = primary(ExifTags.MAKE, ExifTags.MODEL, ExifTags.SOFTWARE, ExifTags.HOST_COMPUTER, ExifTags.UNIQUE_CAMERA_MODEL) +
            exif(ExifTags.LENS_SPECIFICATION, ExifTags.LENS_MAKE, ExifTags.LENS_MODEL),
        xmp = xmp(XmpNs.TIFF, "Make", "Model", "Software", "HostComputer") +
            xmp(XmpNs.XMP, "CreatorTool") +
            xmp(XmpNs.EXIF_EX, "LensMake", "LensModel", "LensSpecification") +
            xmp(XmpNs.AUX, "Lens", "LensInfo", "LensID", "Firmware"),
    )

    /** Names of the author, owner, copyright holder and contacts. */
    val personNames = Fields(
        exif = primary(ExifTags.ARTIST, ExifTags.COPYRIGHT, ExifTags.XP_AUTHOR) + exif(ExifTags.CAMERA_OWNER_NAME),
        xmp = xmp(XmpNs.DC, "creator", "rights", "contributor", "publisher") +
            xmp(XmpNs.PHOTOSHOP, "Credit", "AuthorsPosition", "CaptionWriter") +
            xmp(XmpNs.IPTC_CORE, "CreatorContactInfo") +
            xmp(XmpNs.AUX, "OwnerName") +
            xmp(XmpNs.EXIF_EX, "CameraOwnerName") +
            xmp(XmpNs.TIFF, "Artist", "Copyright"),
        xmpNamespaces = listOf(XmpNs.XMP_RIGHTS, XmpNs.PLUS),
        iptc = listOf(IptcTags.BY_LINE, IptcTags.BY_LINE_TITLE, IptcTags.CREDIT, IptcTags.COPYRIGHT_NOTICE, IptcTags.CONTACT, IptcTags.WRITER_EDITOR),
    )

    /** Face and person regions and the names of people shown. */
    val people = Fields(
        xmp = xmp(XmpNs.MWG_RS, "Regions") + xmp(XmpNs.MP, "RegionInfo") + xmp(XmpNs.IPTC_EXT, "PersonInImage", "PersonInImageWDetails"),
    )

    /** Every capture, modification, GPS and editorial date. */
    val dates = Fields(
        exif = primary(ExifTags.DATE_TIME) +
            exif(
                ExifTags.DATE_TIME_ORIGINAL, ExifTags.DATE_TIME_DIGITIZED,
                ExifTags.OFFSET_TIME, ExifTags.OFFSET_TIME_ORIGINAL, ExifTags.OFFSET_TIME_DIGITIZED,
                ExifTags.SUB_SEC_TIME, ExifTags.SUB_SEC_TIME_ORIGINAL, ExifTags.SUB_SEC_TIME_DIGITIZED,
            ) +
            listOf(ExifKey(ExifIfd.Gps, ExifTags.GPS_TIME_STAMP), ExifKey(ExifIfd.Gps, ExifTags.GPS_DATE_STAMP), ExifKey(ExifIfd.Thumbnail, ExifTags.DATE_TIME)),
        xmp = xmp(XmpNs.XMP, "CreateDate", "ModifyDate", "MetadataDate") +
            xmp(XmpNs.EXIF, "DateTimeOriginal", "DateTimeDigitized", "GPSTimeStamp") +
            xmp(XmpNs.PHOTOSHOP, "DateCreated") +
            xmp(XmpNs.TIFF, "DateTime"),
        iptc = listOf(
            IptcTags.RELEASE_DATE, IptcTags.RELEASE_TIME, IptcTags.EXPIRATION_DATE, IptcTags.EXPIRATION_TIME, IptcTags.REFERENCE_DATE,
            IptcTags.DATE_CREATED, IptcTags.TIME_CREATED, IptcTags.DIGITAL_CREATION_DATE, IptcTags.DIGITAL_CREATION_TIME,
        ),
    )

    /** XMP media management (document ids, derivation, history) and Photoshop's edit history. */
    val editHistory = Fields(
        xmp = xmp(XmpNs.PHOTOSHOP, "History"),
        xmpNamespaces = listOf(XmpNs.XMP_MM),
    )
}
