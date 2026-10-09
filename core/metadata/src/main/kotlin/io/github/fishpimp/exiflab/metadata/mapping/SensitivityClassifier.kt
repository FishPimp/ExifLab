package io.github.fishpimp.exiflab.metadata.mapping

import com.drew.metadata.Directory
import com.drew.metadata.exif.ExifDirectoryBase
import com.drew.metadata.exif.GpsDirectory
import com.drew.metadata.exif.makernotes.AppleMakernoteDirectory
import com.drew.metadata.exif.makernotes.CanonMakernoteDirectory
import com.drew.metadata.exif.makernotes.FujifilmMakernoteDirectory
import com.drew.metadata.exif.makernotes.LeicaMakernoteDirectory
import com.drew.metadata.exif.makernotes.NikonType2MakernoteDirectory
import com.drew.metadata.exif.makernotes.OlympusEquipmentMakernoteDirectory
import com.drew.metadata.exif.makernotes.OlympusMakernoteDirectory
import com.drew.metadata.exif.makernotes.PanasonicMakernoteDirectory
import com.drew.metadata.exif.makernotes.SamsungType2MakernoteDirectory
import com.drew.metadata.exif.makernotes.SigmaMakernoteDirectory
import com.drew.metadata.exif.makernotes.SonyTag9050bDirectory
import com.drew.metadata.iptc.IptcDirectory
import com.drew.metadata.photoshop.DuckyDirectory
import com.drew.metadata.png.PngDirectory
import io.github.fishpimp.exiflab.metadata.model.SensitivityCategory
import io.github.fishpimp.exiflab.metadata.model.SensitivityCategory.DeviceIdentifier
import io.github.fishpimp.exiflab.metadata.model.SensitivityCategory.Location
import io.github.fishpimp.exiflab.metadata.model.SensitivityCategory.People
import io.github.fishpimp.exiflab.metadata.model.SensitivityCategory.PersonName
import io.github.fishpimp.exiflab.metadata.model.SensitivityCategory.SerialNumber

/**
 * Decides which tags can identify a person, place or device: an explicit table of known tags per
 * directory type, then name-based fallbacks for the long tail of vendor tags.
 */
internal object SensitivityClassifier {
    private val exifTags = mapOf(
        0xA431 to SerialNumber, // BodySerialNumber
        0xA435 to SerialNumber, // LensSerialNumber
        0xC62F to SerialNumber, // CameraSerialNumber (DNG)
        0x013B to PersonName, // Artist
        0x8298 to PersonName, // Copyright
        0xA430 to PersonName, // CameraOwnerName
        0x9C9D to PersonName, // XPAuthor
        0xA420 to DeviceIdentifier, // ImageUniqueID
    )

    private val iptcTags = mapOf(
        IptcDirectory.TAG_CONTENT_LOCATION_CODE to Location,
        IptcDirectory.TAG_CONTENT_LOCATION_NAME to Location,
        IptcDirectory.TAG_CITY to Location,
        IptcDirectory.TAG_SUB_LOCATION to Location,
        IptcDirectory.TAG_PROVINCE_OR_STATE to Location,
        IptcDirectory.TAG_COUNTRY_OR_PRIMARY_LOCATION_CODE to Location,
        IptcDirectory.TAG_COUNTRY_OR_PRIMARY_LOCATION_NAME to Location,
        IptcDirectory.TAG_BY_LINE to PersonName,
        IptcDirectory.TAG_COPYRIGHT_NOTICE to PersonName,
        IptcDirectory.TAG_CAPTION_WRITER to PersonName,
        IptcDirectory.TAG_CREDIT to PersonName,
        IptcDirectory.TAG_CONTACT to PersonName,
    )

    private val makernoteTags: Map<Class<out Directory>, Map<Int, SensitivityCategory>> = mapOf(
        CanonMakernoteDirectory::class.java to mapOf(
            CanonMakernoteDirectory.TAG_CANON_OWNER_NAME to PersonName,
            CanonMakernoteDirectory.TAG_CANON_SERIAL_NUMBER to SerialNumber,
            CanonMakernoteDirectory.TAG_SERIAL_INFO_ARRAY to SerialNumber,
            CanonMakernoteDirectory.TAG_IMAGE_UNIQUE_ID to DeviceIdentifier,
            CanonMakernoteDirectory.TAG_FACE_DETECT_ARRAY_1 to People,
            CanonMakernoteDirectory.TAG_FACE_DETECT_ARRAY_2 to People,
        ),
        NikonType2MakernoteDirectory::class.java to mapOf(
            NikonType2MakernoteDirectory.TAG_CAMERA_SERIAL_NUMBER to SerialNumber,
            NikonType2MakernoteDirectory.TAG_CAMERA_SERIAL_NUMBER_2 to SerialNumber,
            NikonType2MakernoteDirectory.TAG_FACE_DETECT to People,
        ),
        FujifilmMakernoteDirectory::class.java to mapOf(
            FujifilmMakernoteDirectory.TAG_SERIAL_NUMBER to SerialNumber,
            FujifilmMakernoteDirectory.TAG_FACES_DETECTED to People,
            FujifilmMakernoteDirectory.TAG_FACE_POSITIONS to People,
            FujifilmMakernoteDirectory.TAG_FACE_REC_INFO to People,
        ),
        OlympusMakernoteDirectory::class.java to mapOf(
            OlympusMakernoteDirectory.TAG_SERIAL_NUMBER_1 to SerialNumber,
            OlympusMakernoteDirectory.TAG_SERIAL_NUMBER_2 to SerialNumber,
            OlympusMakernoteDirectory.TAG_CAMERA_ID to DeviceIdentifier,
        ),
        OlympusEquipmentMakernoteDirectory::class.java to mapOf(
            OlympusEquipmentMakernoteDirectory.TAG_SERIAL_NUMBER to SerialNumber,
            OlympusEquipmentMakernoteDirectory.TAG_INTERNAL_SERIAL_NUMBER to SerialNumber,
            OlympusEquipmentMakernoteDirectory.TAG_LENS_SERIAL_NUMBER to SerialNumber,
            OlympusEquipmentMakernoteDirectory.TAG_EXTENDER_SERIAL_NUMBER to SerialNumber,
            OlympusEquipmentMakernoteDirectory.TAG_FLASH_SERIAL_NUMBER to SerialNumber,
        ),
        PanasonicMakernoteDirectory::class.java to mapOf(
            PanasonicMakernoteDirectory.TAG_INTERNAL_SERIAL_NUMBER to SerialNumber,
            PanasonicMakernoteDirectory.TAG_LENS_SERIAL_NUMBER to SerialNumber,
            PanasonicMakernoteDirectory.TAG_ACCESSORY_SERIAL_NUMBER to SerialNumber,
            PanasonicMakernoteDirectory.TAG_FACES_DETECTED to People,
            PanasonicMakernoteDirectory.TAG_FACE_DETECTION_INFO to People,
            PanasonicMakernoteDirectory.TAG_FACE_RECOGNITION_INFO to People,
            PanasonicMakernoteDirectory.TAG_RECOGNIZED_FACE_FLAGS to People,
            PanasonicMakernoteDirectory.TAG_WORLD_TIME_LOCATION to Location,
        ),
        LeicaMakernoteDirectory::class.java to mapOf(LeicaMakernoteDirectory.TAG_SERIAL_NUMBER to SerialNumber),
        SigmaMakernoteDirectory::class.java to mapOf(SigmaMakernoteDirectory.TAG_SERIAL_NUMBER to SerialNumber),
        SonyTag9050bDirectory::class.java to mapOf(SonyTag9050bDirectory.TAG_INTERNAL_SERIAL_NUMBER to SerialNumber),
        SamsungType2MakernoteDirectory::class.java to mapOf(
            SamsungType2MakernoteDirectory.TagSerialNumber to SerialNumber,
            SamsungType2MakernoteDirectory.TagInternalLensSerialNumber to SerialNumber,
            SamsungType2MakernoteDirectory.TagFaceDetect to People,
            SamsungType2MakernoteDirectory.TagFaceRecognition to People,
            SamsungType2MakernoteDirectory.TagFaceName to People,
            SamsungType2MakernoteDirectory.TagLocalLocationName to Location,
        ),
        AppleMakernoteDirectory::class.java to mapOf(
            AppleMakernoteDirectory.TAG_BURST_UUID to DeviceIdentifier,
            AppleMakernoteDirectory.TAG_CONTENT_IDENTIFIER to DeviceIdentifier,
            AppleMakernoteDirectory.TAG_IMAGE_UNIQUE_ID to DeviceIdentifier,
            AppleMakernoteDirectory.TAG_LIVE_PHOTO_ID to DeviceIdentifier,
        ),
        DuckyDirectory::class.java to mapOf(DuckyDirectory.TAG_COPYRIGHT to PersonName),
    )

    private val pngTextKeywords = mapOf("author" to PersonName, "copyright" to PersonName)

    private val xmpPrefixes = listOf(
        "mwg-rs:Regions" to People,
        "MP:RegionInfo" to People,
        "Iptc4xmpExt:PersonInImage" to People,
        "exif:GPS" to Location,
        "photoshop:City" to Location,
        "photoshop:State" to Location,
        "photoshop:Country" to Location,
        "Iptc4xmpCore:Location" to Location,
        "Iptc4xmpCore:CountryCode" to Location,
        "Iptc4xmpExt:LocationCreated" to Location,
        "Iptc4xmpExt:LocationShown" to Location,
        "aux:SerialNumber" to SerialNumber,
        "aux:LensSerialNumber" to SerialNumber,
        "exifEX:BodySerialNumber" to SerialNumber,
        "exifEX:LensSerialNumber" to SerialNumber,
        "dc:creator" to PersonName,
        "dc:rights" to PersonName,
        "xmpRights:Owner" to PersonName,
        "photoshop:Credit" to PersonName,
        "photoshop:CaptionWriter" to PersonName,
        "Iptc4xmpCore:CreatorContactInfo" to PersonName,
        "tiff:Artist" to PersonName,
        "tiff:Copyright" to PersonName,
        "aux:OwnerName" to PersonName,
        "exifEX:CameraOwnerName" to PersonName,
        "exifEX:ImageUniqueID" to DeviceIdentifier,
        "exif:ImageUniqueID" to DeviceIdentifier,
        "photoshop:DocumentAncestors" to DeviceIdentifier,
    )

    // Word start only, so "FaceDetect" and "Faces Detected" match but "Surface" does not.
    private val faceWord = Regex("\\bface", RegexOption.IGNORE_CASE)
    private val personWords = listOf("owner", "author", "artist", "creator")
    private val identifierWords = listOf("unique id", "uniqueid", "uuid", "device id", "deviceid")

    /** Category for tag [tagId] named [tagName] in a metadata-extractor [directory], or null. */
    fun classify(directory: Directory, tagId: Int, tagName: String): SensitivityCategory? {
        explicit(directory, tagId, tagName)?.let { return it }
        return byName(tagName)
    }

    /** Category for an XMP property at [path], or null. */
    fun classifyXmp(path: String): SensitivityCategory? {
        xmpPrefixes.firstOrNull { (prefix, _) -> path.startsWith(prefix) }?.let { return it.second }
        val lower = path.lowercase()
        if (lower.startsWith("xmpmm:") && ("documentid" in lower || "instanceid" in lower)) return DeviceIdentifier
        return byName(path)
    }

    private fun explicit(directory: Directory, tagId: Int, tagName: String): SensitivityCategory? = when (directory) {
        is GpsDirectory -> Location
        is ExifDirectoryBase -> exifTags[tagId]
        is IptcDirectory -> iptcTags[tagId]
        is PngDirectory -> pngTextKeywords[tagName.lowercase()]
        else -> makernoteTags[directory.javaClass]?.get(tagId)
    }

    private fun byName(name: String): SensitivityCategory? {
        val lower = name.lowercase()
        return when {
            faceWord.containsMatchIn(name) -> People
            "serial" in lower && "format" !in lower -> SerialNumber
            identifierWords.any { it in lower } -> DeviceIdentifier
            personWords.any { it in lower } && "creatortool" !in lower.replace(" ", "") -> PersonName
            else -> null
        }
    }
}
