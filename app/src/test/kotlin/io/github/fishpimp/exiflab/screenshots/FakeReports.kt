package io.github.fishpimp.exiflab.screenshots

import io.github.fishpimp.exiflab.data.photos.PhotoOrigin
import io.github.fishpimp.exiflab.data.photos.PhotoRef
import io.github.fishpimp.exiflab.metadata.ImageFormat
import io.github.fishpimp.exiflab.metadata.model.DirectoryGroup
import io.github.fishpimp.exiflab.metadata.model.GeoLocation
import io.github.fishpimp.exiflab.metadata.model.LocationStatus
import io.github.fishpimp.exiflab.metadata.model.MetadataDirectory
import io.github.fishpimp.exiflab.metadata.model.MetadataReport
import io.github.fishpimp.exiflab.metadata.model.MetadataTag
import io.github.fishpimp.exiflab.metadata.model.OffsetSource
import io.github.fishpimp.exiflab.metadata.model.PhotoSummary
import io.github.fishpimp.exiflab.metadata.model.Rational
import io.github.fishpimp.exiflab.metadata.model.SensitiveFinding
import io.github.fishpimp.exiflab.metadata.model.SensitivityCategory
import io.github.fishpimp.exiflab.metadata.model.SensitivityCategory.DeviceIdentifier
import io.github.fishpimp.exiflab.metadata.model.SensitivityCategory.Location
import io.github.fishpimp.exiflab.metadata.model.SensitivityCategory.People
import io.github.fishpimp.exiflab.metadata.model.SensitivityCategory.PersonName
import io.github.fishpimp.exiflab.metadata.model.SensitivityCategory.SerialNumber
import java.time.LocalDateTime
import java.time.ZoneOffset

/**
 * Realistic metadata reports for screenshot and unit tests, shaped like the engine's output for
 * real files: unreduced rationals, metadata-extractor tag names and descriptions, blank GPS blocks.
 */
internal object FakeReports {
    /** One tag in a fake directory; [id] null for XMP paths and container fields. */
    class Tag(val id: Int?, val name: String, val display: String, val raw: String = display, val sensitivity: SensitivityCategory? = null)

    fun directory(id: String, name: String, group: DirectoryGroup, tags: List<Tag>) = MetadataDirectory(
        id = id,
        name = name,
        group = group,
        tags = tags.map { tag ->
            MetadataTag(
                key = "$id:${tag.id ?: tag.name}",
                directoryId = id,
                id = tag.id,
                name = tag.name,
                displayValue = tag.display,
                rawValue = tag.raw,
                sensitivity = tag.sensitivity,
            )
        },
    )

    fun findings(directories: List<MetadataDirectory>): List<SensitiveFinding> {
        val tags = directories.flatMap { it.tags }
        return SensitivityCategory.entries.mapNotNull { category ->
            tags.filter { it.sensitivity == category }.map { it.key }.takeIf { it.isNotEmpty() }?.let { SensitiveFinding(category, it) }
        }
    }

    private fun fileDirectory(name: String, size: Long, sizeText: String, format: ImageFormat) = directory(
        "file", "File", DirectoryGroup.File,
        listOf(
            Tag(null, "File Name", name),
            Tag(null, "File Size", sizeText, size.toString()),
            Tag(null, "File Type", format.displayName, format.name),
            Tag(null, "MIME Type", format.mimeType),
        ),
    )

    // ---- Phone JPEG with location (Pixel 8 Pro) ----

    const val PHONE_NAME = "PXL_20260614_174207123.jpg"
    private const val PHONE_SIZE = 3_412_345L

    private val phoneIfd0 = listOf(
        Tag(0x010F, "Make", "Google"),
        Tag(0x0110, "Model", "Pixel 8 Pro"),
        Tag(0x0112, "Orientation", "Top, left side (Horizontal / normal)", "1"),
        Tag(0x011A, "X Resolution", "72 dots per inch", "72/1"),
        Tag(0x011B, "Y Resolution", "72 dots per inch", "72/1"),
        Tag(0x0128, "Resolution Unit", "Inch", "2"),
        Tag(0x0131, "Software", "HDR+ 1.0.641377693zd"),
        Tag(0x0132, "Date/Time", "2026:06:14 19:42:07"),
        Tag(0x0213, "YCbCr Positioning", "Center of pixel array", "1"),
    )

    private val phoneSubIfd = listOf(
        Tag(0x829A, "Exposure Time", "0.00214 sec", "2141/1000000"),
        Tag(0x829D, "F-Number", "f/1.7", "168/100"),
        Tag(0x8822, "Exposure Program", "Program normal", "2"),
        Tag(0x8827, "ISO Speed Ratings", "49"),
        Tag(0x9000, "Exif Version", "2.32", "30 32 33 32"),
        Tag(0x9003, "Date/Time Original", "2026:06:14 19:42:07"),
        Tag(0x9004, "Date/Time Digitized", "2026:06:14 19:42:07"),
        Tag(0x9010, "Offset Time", "+02:00"),
        Tag(0x9011, "Offset Time Original", "+02:00"),
        Tag(0x9012, "Offset Time Digitized", "+02:00"),
        Tag(0x9201, "Shutter Speed Value", "1/466 sec", "886/100"),
        Tag(0x9202, "Aperture Value", "f/1.7", "153/100"),
        Tag(0x9203, "Brightness Value", "7.84", "784/100"),
        Tag(0x9204, "Exposure Bias Value", "0 EV", "0/6"),
        Tag(0x9205, "Max Aperture Value", "f/1.7", "153/100"),
        Tag(0x9207, "Metering Mode", "Center weighted average", "2"),
        Tag(0x9209, "Flash", "Flash did not fire, compulsory flash mode", "16"),
        Tag(0x920A, "Focal Length", "6.9 mm", "690/100"),
        Tag(0x9290, "Sub-Sec Time", "123"),
        Tag(0x9291, "Sub-Sec Time Original", "123"),
        Tag(0x9292, "Sub-Sec Time Digitized", "123"),
        Tag(0xA001, "Color Space", "sRGB", "1"),
        Tag(0xA002, "Exif Image Width", "4080 pixels", "4080"),
        Tag(0xA003, "Exif Image Height", "3072 pixels", "3072"),
        Tag(0xA217, "Sensing Method", "One-chip color area sensor", "2"),
        Tag(0xA301, "Scene Type", "Directly photographed image", "1"),
        Tag(0xA401, "Custom Rendered", "Custom process", "1"),
        Tag(0xA402, "Exposure Mode", "Auto exposure", "0"),
        Tag(0xA403, "White Balance Mode", "Auto white balance", "0"),
        Tag(0xA404, "Digital Zoom Ratio", "1", "100/100"),
        Tag(0xA405, "Focal Length 35", "24 mm", "24"),
        Tag(0xA406, "Scene Capture Type", "Standard", "0"),
        Tag(0xA40C, "Subject Distance Range", "Unknown", "0"),
        Tag(0xA432, "Lens Specification", "6.9mm f/1.7", "690/100 690/100 168/100 168/100"),
        Tag(0xA433, "Lens Make", "Google"),
        Tag(0xA434, "Lens Model", "Pixel 8 Pro back camera 6.9mm f/1.68"),
    )

    private val phoneInterop = listOf(
        Tag(0x0001, "Interoperability Index", "Recommended Exif Interoperability Rules (ExifR98)", "R98"),
        Tag(0x0002, "Interoperability Version", "1.00", "30 31 30 30"),
    )

    private val phoneGps = listOf(
        Tag(0x0000, "GPS Version ID", "2.200", "2 2 0 0", Location),
        Tag(0x0001, "GPS Latitude Ref", "N", sensitivity = Location),
        Tag(0x0002, "GPS Latitude", "59° 19' 35.29\"", "59/1 19/1 3529/100", Location),
        Tag(0x0003, "GPS Longitude Ref", "E", sensitivity = Location),
        Tag(0x0004, "GPS Longitude", "18° 4' 18.67\"", "18/1 4/1 1867/100", Location),
        Tag(0x0005, "GPS Altitude Ref", "Sea level", "0", Location),
        Tag(0x0006, "GPS Altitude", "18.4 metres", "184/10", Location),
        Tag(0x0007, "GPS Time-Stamp", "17:42:05.000 UTC", "17/1 42/1 5/1", Location),
        Tag(0x0010, "GPS Img Direction Ref", "Magnetic direction", "M", Location),
        Tag(0x0011, "GPS Img Direction", "247 degrees", "247/1", Location),
        Tag(0x001D, "GPS Date Stamp", "2026:06:14", sensitivity = Location),
    )

    /** The blank GPS block Android's photo picker leaves behind. */
    private val redactedGps = listOf(
        Tag(0x0000, "GPS Version ID", "2.200", "2 2 0 0", Location),
        Tag(0x0001, "GPS Latitude Ref", "", sensitivity = Location),
        Tag(0x0002, "GPS Latitude", "0° 0' 0\"", "0/1 0/1 0/1", Location),
        Tag(0x0003, "GPS Longitude Ref", "", sensitivity = Location),
        Tag(0x0004, "GPS Longitude", "0° 0' 0\"", "0/1 0/1 0/1", Location),
        Tag(0x0005, "GPS Altitude Ref", "Sea level", "0", Location),
        Tag(0x0006, "GPS Altitude", "0 metres", "0/1", Location),
        Tag(0x0007, "GPS Time-Stamp", "00:00:00.000 UTC", "0/1 0/1 0/1", Location),
        Tag(0x001D, "GPS Date Stamp", "", sensitivity = Location),
    )

    private val phoneXmp = listOf(
        Tag(null, "GCamera:MotionPhoto", "1"),
        Tag(null, "GCamera:MotionPhotoVersion", "1"),
        Tag(null, "GCamera:MotionPhotoPresentationTimestampUs", "1032145"),
        Tag(null, "Container:Directory[1]/Container:Item/Item:Mime", "image/jpeg"),
        Tag(null, "Container:Directory[1]/Container:Item/Item:Semantic", "Primary"),
        Tag(null, "Container:Directory[2]/Container:Item/Item:Mime", "video/mp4"),
        Tag(null, "Container:Directory[2]/Container:Item/Item:Semantic", "MotionPhoto"),
        Tag(null, "Container:Directory[2]/Container:Item/Item:Length", "2945112"),
        Tag(null, "hdrgm:Version", "1.0"),
    )

    private val phoneIcc = listOf(
        Tag(0x0000, "Profile Size", "536"),
        Tag(0x0008, "Version", "4.0.0", "67108864"),
        Tag(0x000C, "Class", "Display Device", "mntr"),
        Tag(0x0010, "Color space", "RGB", "RGB "),
        Tag(0x0014, "Profile Connection Space", "XYZ", "XYZ "),
        Tag(0x0018, "Profile Date/Time", "2016:12:08 09:38:28"),
        Tag(0x0024, "Signature", "acsp"),
        Tag(0x0028, "Primary Platform", "Apple Computer, Inc.", "APPL"),
        Tag(0x64657363, "Profile Description", "Display P3"),
        Tag(0x63707274, "Profile Copyright", "Copyright Apple Inc., 2017"),
        Tag(0x77747074, "Media White Point", "(0.9505, 1, 1.0891)"),
    )

    private val phoneJpegSegments = listOf(
        Tag(null, "Compression Type", "Baseline", "0"),
        Tag(null, "Data Precision", "8 bits", "8"),
        Tag(null, "Image Height", "3072 pixels", "3072"),
        Tag(null, "Image Width", "4080 pixels", "4080"),
        Tag(null, "Number of Components", "3"),
        Tag(null, "Component 1", "Y component: Quantization table 0, Sampling factors 2 horiz/2 vert"),
        Tag(null, "Component 2", "Cb component: Quantization table 1, Sampling factors 1 horiz/1 vert"),
        Tag(null, "Component 3", "Cr component: Quantization table 1, Sampling factors 1 horiz/1 vert"),
    )

    private val phoneSummary = PhotoSummary(
        make = "Google",
        model = "Pixel 8 Pro",
        lens = "Pixel 8 Pro back camera 6.9mm f/1.68",
        fNumber = 1.68,
        exposureTime = Rational(2141, 1_000_000),
        iso = 49,
        focalLengthMm = 6.9,
        focalLength35mm = 24,
        exposureBiasEv = 0.0,
        flashFired = false,
        width = 4080,
        height = 3072,
        orientation = 1,
        capturedAt = LocalDateTime.of(2026, 6, 14, 19, 42, 7, 123_000_000),
        utcOffset = ZoneOffset.ofHours(2),
        utcOffsetSource = OffsetSource.ExifOffsetTime,
        software = "HDR+ 1.0.641377693zd",
        colorProfile = "Display P3",
    )

    private fun phoneDirectories(gps: List<Tag>) = listOf(
        directory("exif-ifd0", "Exif IFD0", DirectoryGroup.Exif, phoneIfd0),
        directory("exif-subifd", "Exif SubIFD", DirectoryGroup.Exif, phoneSubIfd),
        directory("exif-interop", "Interoperability", DirectoryGroup.Exif, phoneInterop),
        directory("gps", "GPS", DirectoryGroup.Gps, gps),
        directory("xmp", "XMP", DirectoryGroup.Xmp, phoneXmp),
        directory("icc", "ICC Profile", DirectoryGroup.Icc, phoneIcc),
        directory("jpeg", "JPEG", DirectoryGroup.Container, phoneJpegSegments),
        fileDirectory(PHONE_NAME, PHONE_SIZE, "3.4 MB", ImageFormat.Jpeg),
    )

    /** A phone photo opened from a folder, location intact. */
    val phoneJpeg: MetadataReport = phoneDirectories(phoneGps).let { directories ->
        MetadataReport(
            format = ImageFormat.Jpeg,
            fileName = PHONE_NAME,
            fileSize = PHONE_SIZE,
            summary = phoneSummary,
            location = GeoLocation(
                latitude = 59 + 19 / 60.0 + 35.29 / 3600,
                longitude = 18 + 4 / 60.0 + 18.67 / 3600,
                altitudeMeters = 18.4,
                directionDegrees = 247.0,
            ),
            locationStatus = LocationStatus.Present,
            directories = directories,
            sensitiveFindings = findings(directories),
            warnings = emptyList(),
        )
    }

    /** The same photo after the photo picker blanked its GPS block. */
    val phoneJpegRedacted: MetadataReport = phoneDirectories(redactedGps).let { directories ->
        phoneJpeg.copy(
            location = null,
            locationStatus = LocationStatus.Redacted,
            directories = directories,
            sensitiveFindings = findings(directories),
        )
    }

    val phoneRef = PhotoRef(
        uri = "content://fake.provider/document/$PHONE_NAME",
        displayName = PHONE_NAME,
        mimeType = "image/jpeg",
        size = PHONE_SIZE,
        lastModified = 1_781_458_927_000,
        origin = PhotoOrigin.Folder,
        writable = true,
    )

    val phonePickerRef = phoneRef.copy(uri = "content://media/picker/0/com.android.providers.media.photopicker/media/1000012345", origin = PhotoOrigin.Picker, writable = false)

    // ---- Camera RAW with MakerNotes, serials and names (Fujifilm X-T5) ----

    const val RAW_NAME = "DSCF4410.RAF"
    private const val RAW_SIZE = 55_812_096L
    private const val OWNER = "Maja Lindqvist"

    private val rawIfd0 = listOf(
        Tag(0x010F, "Make", "FUJIFILM"),
        Tag(0x0110, "Model", "X-T5"),
        Tag(0x0112, "Orientation", "Top, left side (Horizontal / normal)", "1"),
        Tag(0x011A, "X Resolution", "72 dots per inch", "72/1"),
        Tag(0x011B, "Y Resolution", "72 dots per inch", "72/1"),
        Tag(0x0128, "Resolution Unit", "Inch", "2"),
        Tag(0x0131, "Software", "Digital Camera X-T5 Ver2.10"),
        Tag(0x0132, "Date/Time", "2026:05:03 06:12:48"),
        Tag(0x013B, "Artist", OWNER, sensitivity = PersonName),
        Tag(0x8298, "Copyright", "© $OWNER 2026", sensitivity = PersonName),
    )

    private val rawSubIfd = listOf(
        Tag(0x829A, "Exposure Time", "1/3800 sec", "10/38000"),
        Tag(0x829D, "F-Number", "f/5.6", "56/10"),
        Tag(0x8822, "Exposure Program", "Aperture priority", "3"),
        Tag(0x8827, "ISO Speed Ratings", "125"),
        Tag(0x8830, "Sensitivity Type", "Standard Output Sensitivity", "1"),
        Tag(0x9000, "Exif Version", "2.32", "30 32 33 32"),
        Tag(0x9003, "Date/Time Original", "2026:05:03 06:12:48"),
        Tag(0x9004, "Date/Time Digitized", "2026:05:03 06:12:48"),
        Tag(0x9010, "Offset Time", "+02:00"),
        Tag(0x9011, "Offset Time Original", "+02:00"),
        Tag(0x9201, "Shutter Speed Value", "1/3800 sec", "1189/100"),
        Tag(0x9202, "Aperture Value", "f/5.6", "497/100"),
        Tag(0x9203, "Brightness Value", "11.21", "1121/100"),
        Tag(0x9204, "Exposure Bias Value", "-0.67 EV", "-67/100"),
        Tag(0x9205, "Max Aperture Value", "f/1.4", "97/100"),
        Tag(0x9207, "Metering Mode", "Multi-segment", "5"),
        Tag(0x9208, "White Balance", "Unknown", "0"),
        Tag(0x9209, "Flash", "Flash did not fire, compulsory flash mode", "16"),
        Tag(0x920A, "Focal Length", "33 mm", "330/10"),
        Tag(0xA001, "Color Space", "sRGB", "1"),
        Tag(0xA002, "Exif Image Width", "7728 pixels", "7728"),
        Tag(0xA003, "Exif Image Height", "5152 pixels", "5152"),
        Tag(0xA20E, "Focal Plane X Resolution", "1/3.76 cm", "1000/3760"),
        Tag(0xA210, "Focal Plane Resolution Unit", "cm", "3"),
        Tag(0xA217, "Sensing Method", "One-chip color area sensor", "2"),
        Tag(0xA300, "File Source", "Digital Still Camera (DSC)", "3"),
        Tag(0xA301, "Scene Type", "Directly photographed image", "1"),
        Tag(0xA401, "Custom Rendered", "Normal process", "0"),
        Tag(0xA402, "Exposure Mode", "Auto exposure", "0"),
        Tag(0xA403, "White Balance Mode", "Auto white balance", "0"),
        Tag(0xA405, "Focal Length 35", "50 mm", "50"),
        Tag(0xA406, "Scene Capture Type", "Standard", "0"),
        Tag(0xA40A, "Sharpness", "None", "0"),
        Tag(0xA40C, "Subject Distance Range", "Unknown", "0"),
        Tag(0xA431, "Body Serial Number", "4BA51023", sensitivity = SerialNumber),
        Tag(0xA432, "Lens Specification", "33mm f/1.4", "330/10 330/10 14/10 14/10"),
        Tag(0xA433, "Lens Make", "FUJIFILM"),
        Tag(0xA434, "Lens Model", "XF33mmF1.4 R LM WR"),
        Tag(0xA435, "Lens Serial Number", "13A04521", sensitivity = SerialNumber),
        Tag(0xA460, "Composite Image", "Not a Composite Image", "1"),
    )

    private val fujiMakerNote = listOf(
        Tag(0x0000, "Makernote Version", "0130", "30 31 33 30"),
        Tag(0x0010, "Serial Number", "FF02B4532113     Y59384 2023:05:12 0A1B2C3D0E4F", sensitivity = SerialNumber),
        Tag(0x1000, "Quality", "FINE"),
        Tag(0x1001, "Sharpness", "Normal", "3"),
        Tag(0x1002, "White Balance", "Auto", "0"),
        Tag(0x1003, "Color Saturation", "+1 (medium high)", "128"),
        Tag(0x100A, "White Balance Fine Tune", "Red +0, Blue +0", "0 0"),
        Tag(0x100E, "Noise Reduction", "-2 (weak)", "1280"),
        Tag(0x100F, "Clarity", "0"),
        Tag(0x1010, "Flash Mode", "Not Attached", "15"),
        Tag(0x1011, "Flash Strength", "0 EV", "0/100"),
        Tag(0x1020, "Macro", "Off", "0"),
        Tag(0x1021, "Focus Mode", "Auto", "0"),
        Tag(0x1022, "AF Mode", "Zone", "258"),
        Tag(0x1023, "Focus Pixel", "3864 2576", "3864 2576"),
        Tag(0x102B, "Prioritize Settings", "AF-S Priority: Release, AF-C Priority: Release", "34"),
        Tag(0x102D, "Focus Settings", "AF-C, Zone, tracking 2", "36962306"),
        Tag(0x102E, "AF-C Settings", "Set 1 (multi-purpose)", "258"),
        Tag(0x1030, "Slow Sync", "Off", "0"),
        Tag(0x1031, "Picture Mode", "Aperture priority AE", "3"),
        Tag(0x1032, "Exposure Count", "1"),
        Tag(0x1040, "Shadow Tone", "-1 (medium soft)", "16"),
        Tag(0x1041, "Highlight Tone", "+1 (medium hard)", "-16"),
        Tag(0x1044, "Digital Zoom", "0"),
        Tag(0x1045, "Lens Modulation Optimizer", "On", "1"),
        Tag(0x1047, "Grain Effect Roughness", "Weak", "32"),
        Tag(0x1048, "Color Chrome Effect", "Strong", "64"),
        Tag(0x104C, "Grain Effect Size", "Small", "16"),
        Tag(0x104E, "Color Chrome FX Blue", "Weak", "32"),
        Tag(0x1050, "Shutter Type", "Mechanical", "0"),
        Tag(0x1100, "Auto Bracketing", "Off", "0"),
        Tag(0x1101, "Sequence Number", "0"),
        Tag(0x1103, "Drive Speed", "Single frame", "0"),
        Tag(0x1150, "Image Count", "4410", "4410"),
        Tag(0x1300, "Blur Warning", "None", "0"),
        Tag(0x1301, "Focus Warning", "Good", "0"),
        Tag(0x1302, "Exposure Warning", "Good", "0"),
        Tag(0x1304, "Gelatin Filter Effect", "Off", "0"),
        Tag(0x1400, "Dynamic Range", "Standard", "1"),
        Tag(0x1401, "Film Mode", "Classic Chrome", "1792"),
        Tag(0x1402, "Dynamic Range Setting", "Auto (100-400%)", "0"),
        Tag(0x1403, "Development Dynamic Range", "100"),
        Tag(0x1404, "Min Focal Length", "33", "330/10"),
        Tag(0x1405, "Max Focal Length", "33", "330/10"),
        Tag(0x1406, "Max Aperture At Min Focal", "1.4", "14/10"),
        Tag(0x1407, "Max Aperture At Max Focal", "1.4", "14/10"),
        Tag(0x140B, "Auto Dynamic Range", "100"),
        Tag(0x1422, "Image Stabilization", "Sensor-shift; On (mode 1, continuous); 0", "1 1 0"),
        Tag(0x1425, "Scene Recognition", "Landscape", "259"),
        Tag(0x1431, "Rating", "0"),
        Tag(0x1436, "Image Generation", "Original Image", "0"),
        Tag(0x1438, "Image Count", "4410"),
        Tag(0x1443, "D Range Priority", "Off", "0"),
        Tag(0x1446, "Flicker Reduction", "Off", "0"),
        Tag(0x1447, "Unknown tag (0x1447)", "0"),
        Tag(0x4005, "Faces Detected", "2", sensitivity = People),
        Tag(0x4100, "Face Positions", "1832 1104 2116 1388 2804 1240 3020 1456", sensitivity = People),
        Tag(0x4103, "Face Element Types", "Face, Face", "1 1", People),
        Tag(0x4282, "Face Rec Info", "(84 bytes)", "(84 bytes)", People),
        Tag(0x8000, "File Source", "Camera", "0"),
        Tag(0x8002, "Order Number", "0"),
        Tag(0x8003, "Frame Number", "4410"),
        Tag(0xB211, "Parallax", "0", "0/1"),
    )

    private val rawXmp = listOf(
        Tag(null, "xmp:Rating", "4"),
        Tag(null, "xmp:CreatorTool", "Digital Camera X-T5 Ver2.10"),
        Tag(null, "xmp:CreateDate", "2026-05-03T06:12:48.15+02:00"),
        Tag(null, "dc:creator[1]", OWNER, sensitivity = PersonName),
        Tag(null, "dc:rights[1]", "© $OWNER 2026. All rights reserved.", sensitivity = PersonName),
        Tag(null, "xmpMM:DocumentID", "xmp.did:7c1e5e7a-0d55-4c2c-9d1b-3f0b8e2a91c4", sensitivity = DeviceIdentifier),
        Tag(null, "xmpMM:InstanceID", "xmp.iid:2f9d1b44-8a1e-4b7f-a6c1-0e5d93b7c812", sensitivity = DeviceIdentifier),
        Tag(null, "photoshop:DateCreated", "2026-05-03T06:12:48.15+02:00"),
    )

    private val rafHeader = listOf(
        Tag(null, "Format Version", "0201"),
        Tag(null, "Camera ID", "X-T5"),
        Tag(null, "JPEG Preview Offset", "148"),
        Tag(null, "JPEG Preview Length", "6912346"),
        Tag(null, "CFA Header Offset", "6912512"),
        Tag(null, "Raw Image Size", "5152 x 7728", "7728 5152"),
    )

    private val rawSummary = PhotoSummary(
        make = "FUJIFILM",
        model = "X-T5",
        lens = "XF33mmF1.4 R LM WR",
        fNumber = 5.6,
        exposureTime = Rational(10, 38_000),
        iso = 125,
        focalLengthMm = 33.0,
        focalLength35mm = 50,
        exposureBiasEv = -0.67,
        flashFired = false,
        width = 7728,
        height = 5152,
        orientation = 1,
        capturedAt = LocalDateTime.of(2026, 5, 3, 6, 12, 48, 150_000_000),
        utcOffset = ZoneOffset.ofHours(2),
        utcOffsetSource = OffsetSource.ExifOffsetTime,
        software = "Digital Camera X-T5 Ver2.10",
    )

    val cameraRaw: MetadataReport = listOf(
        directory("exif-ifd0", "Exif IFD0", DirectoryGroup.Exif, rawIfd0),
        directory("exif-subifd", "Exif SubIFD", DirectoryGroup.Exif, rawSubIfd),
        directory("makernote-fujifilm", "Fujifilm Makernote", DirectoryGroup.MakerNote, fujiMakerNote),
        directory("xmp", "XMP", DirectoryGroup.Xmp, rawXmp),
        directory("raf", "RAF Header", DirectoryGroup.Container, rafHeader),
        fileDirectory(RAW_NAME, RAW_SIZE, "55.8 MB", ImageFormat.Raf),
    ).let { directories ->
        MetadataReport(
            format = ImageFormat.Raf,
            fileName = RAW_NAME,
            fileSize = RAW_SIZE,
            summary = rawSummary,
            location = null,
            locationStatus = LocationStatus.Absent,
            directories = directories,
            sensitiveFindings = findings(directories),
            warnings = listOf(
                "Fujifilm Makernote: Unknown tag 0x1447 has an invalid count of 0",
                "XMP: Skipped a second rdf:Description that repeats xmp:Rating",
            ),
        )
    }

    val rawRef = PhotoRef(
        uri = "content://fake.provider/tree/camera/document/$RAW_NAME",
        displayName = RAW_NAME,
        mimeType = "image/x-fuji-raf",
        size = RAW_SIZE,
        lastModified = 1_777_781_568_000,
        origin = PhotoOrigin.Folder,
        writable = true,
    )

    // ---- A bare PNG screenshot, shared from another app ----

    const val PNG_NAME = "Screenshot_20260901-101530.png"
    private const val PNG_SIZE = 612_345L

    val barePng: MetadataReport = listOf(
        directory(
            "png-ihdr", "PNG-IHDR", DirectoryGroup.Container,
            listOf(
                Tag(null, "Image Width", "1080"),
                Tag(null, "Image Height", "2400"),
                Tag(null, "Bits Per Sample", "8"),
                Tag(null, "Color Type", "True Color with Alpha", "6"),
                Tag(null, "Compression Type", "Deflate", "0"),
                Tag(null, "Filter Method", "Adaptive", "0"),
                Tag(null, "Interlace Method", "No Interlace", "0"),
            ),
        ),
        directory("png-srgb", "PNG-sRGB", DirectoryGroup.Container, listOf(Tag(null, "sRGB Rendering Intent", "Perceptual", "0"))),
        fileDirectory(PNG_NAME, PNG_SIZE, "612.3 kB", ImageFormat.Png),
    ).let { directories ->
        MetadataReport(
            format = ImageFormat.Png,
            fileName = PNG_NAME,
            fileSize = PNG_SIZE,
            summary = PhotoSummary(width = 1080, height = 2400),
            location = null,
            locationStatus = LocationStatus.Absent,
            directories = directories,
            sensitiveFindings = emptyList(),
            warnings = emptyList(),
        )
    }

    val pngRef = PhotoRef(
        uri = "content://com.example.share/screenshots/$PNG_NAME",
        displayName = PNG_NAME,
        mimeType = "image/png",
        size = PNG_SIZE,
        lastModified = null,
        origin = PhotoOrigin.Share,
        writable = false,
    )

    /** A synthetic report with [directoryCount] directories of [tagsPerDirectory] tags, for scale tests. */
    fun large(directoryCount: Int = 12, tagsPerDirectory: Int = 50): MetadataReport {
        val directories = (1..directoryCount).map { d ->
            directory(
                "dir-$d", "Directory $d", if (d % 3 == 0) DirectoryGroup.MakerNote else DirectoryGroup.Exif,
                (1..tagsPerDirectory).map { t ->
                    Tag(d * 1000 + t, "Tag $d.$t", "Value $t of directory $d", "$t/1", if (t % 25 == 0) SerialNumber else null)
                },
            )
        }
        return MetadataReport(
            format = ImageFormat.Jpeg,
            fileName = "large.jpg",
            fileSize = 1L,
            summary = PhotoSummary(),
            location = null,
            locationStatus = LocationStatus.Absent,
            directories = directories,
            sensitiveFindings = findings(directories),
            warnings = emptyList(),
        )
    }
}
