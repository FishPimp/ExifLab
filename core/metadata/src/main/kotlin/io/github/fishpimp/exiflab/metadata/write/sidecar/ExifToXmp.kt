package io.github.fishpimp.exiflab.metadata.write.sidecar

import com.adobe.internal.xmp.XMPException
import com.adobe.internal.xmp.XMPMeta
import com.adobe.internal.xmp.options.PropertyOptions
import io.github.fishpimp.exiflab.metadata.model.Rational
import io.github.fishpimp.exiflab.metadata.write.ExifChange
import io.github.fishpimp.exiflab.metadata.write.ExifIfd
import io.github.fishpimp.exiflab.metadata.write.ExifValue
import io.github.fishpimp.exiflab.metadata.write.IptcChange
import java.math.BigDecimal
import java.math.RoundingMode
import java.util.Locale

/** XMP schemas the sidecar writer maps EXIF and IPTC values to. */
internal object Ns {
    const val EXIF = "http://ns.adobe.com/exif/1.0/"
    const val EXIF_EX = "http://cipa.jp/exif/1.0/"
    const val TIFF = "http://ns.adobe.com/tiff/1.0/"
    const val AUX = "http://ns.adobe.com/exif/1.0/aux/"
    const val XMP = "http://ns.adobe.com/xap/1.0/"
    const val DC = "http://purl.org/dc/elements/1.1/"
    const val PHOTOSHOP = "http://ns.adobe.com/photoshop/1.0/"
    const val IPTC_CORE = "http://iptc.org/std/Iptc4xmpCore/1.0/xmlns/"
}

/**
 * Maps EXIF changes to their XMP equivalents (EXIF 2.3 metadata for XMP, CIPA DC-010): dates
 * become ISO 8601 with sub-seconds and offsets, GPS coordinates "DDD,MM.mmmmk", Artist dc:creator,
 * Copyright dc:rights, ImageDescription dc:description, and other tags their exif:, exifEX:,
 * tiff: and aux: properties. Parts of a combined value that the changes do not touch (the
 * offset of a date, the hemisphere of a coordinate) are taken from the existing sidecar.
 * Tags without an XMP equivalent (Interoperability, thumbnail, unknown tags) are ignored.
 */
internal class ExifToXmp(private val meta: XMPMeta) {
    private enum class Kind { Text, Int, Rational, IntSeq, RationalSeq, LangAlt, Creators, Version, Comment }

    private class Target(val namespace: String, val name: String, val kind: Kind)

    private val sets = mutableMapOf<Pair<ExifIfd, Int>, ExifValue>()
    private val removes = mutableSetOf<Pair<ExifIfd, Int>>()

    fun apply(changes: List<ExifChange>) {
        for (change in changes) {
            val key = change.ifd to change.tag
            when (change) {
                is ExifChange.Set -> {
                    sets[key] = change.value
                    removes -= key
                }
                is ExifChange.Remove -> {
                    removes += key
                    sets -= key
                }
            }
        }
        for ((key, value) in sets) MAPPING[key]?.forEach { target -> write(target, value) }
        for (key in removes) MAPPING[key]?.forEach { target -> meta.deleteProperty(target.namespace, target.name) }
        if (touched(ExifIfd.Exif, FLASH)) flash()
        dates()
        gps()
    }

    private fun touched(ifd: ExifIfd, vararg tags: Int) = tags.any { (ifd to it) in sets || (ifd to it) in removes }

    private fun write(target: Target, value: ExifValue) {
        val ns = target.namespace
        val name = target.name
        meta.deleteProperty(ns, name)
        when (target.kind) {
            Kind.Text -> text(value)?.let { meta.setProperty(ns, name, it) }
            Kind.Int -> integers(value).firstOrNull()?.let { meta.setProperty(ns, name, it.toString()) }
            Kind.Rational -> rationals(value).firstOrNull()?.let { meta.setProperty(ns, name, it.toString()) }
            Kind.IntSeq -> integers(value).forEach { meta.appendArrayItem(ns, name, SEQ, it.toString(), null) }
            Kind.RationalSeq -> rationals(value).forEach { meta.appendArrayItem(ns, name, SEQ, it.toString(), null) }
            Kind.LangAlt -> text(value)?.let { meta.setLocalizedText(ns, name, null, "x-default", it) }
            Kind.Creators -> text(value)?.split(';')?.map { it.trim() }?.filter { it.isNotEmpty() }
                ?.forEach { meta.appendArrayItem(ns, name, SEQ, it, null) }
            Kind.Version -> (value as? ExifValue.Undefined)?.bytes?.let { meta.setProperty(ns, name, String(it, Charsets.ISO_8859_1).trimEnd('\u0000')) }
                ?: text(value)?.let { meta.setProperty(ns, name, it) }
            Kind.Comment -> comment(value)?.let { meta.setLocalizedText(ns, name, null, "x-default", it) }
        }
    }

    private fun flash() {
        meta.deleteProperty(Ns.EXIF, "Flash")
        val bits = sets[ExifIfd.Exif to FLASH]?.let { integers(it).firstOrNull() } ?: return
        fun field(name: String, value: String) = meta.setStructField(Ns.EXIF, "Flash", Ns.EXIF, name, value)
        field("Fired", bool(bits and 1L != 0L))
        field("Return", ((bits shr 1) and 3L).toString())
        field("Mode", ((bits shr 3) and 3L).toString())
        field("Function", bool(bits and 0x20L != 0L))
        field("RedEyeMode", bool(bits and 0x40L != 0L))
    }

    /** Date tags, each combined with its sub-second and offset tags into one ISO 8601 value. */
    private fun dates() {
        for (date in DATES) {
            if (!touched(date.ifd, date.dateTag) && !touched(ExifIfd.Exif, date.subSecTag, date.offsetTag)) continue
            val dateKey = date.ifd to date.dateTag
            if (dateKey in removes) {
                date.targets.forEach { (ns, name) -> meta.deleteProperty(ns, name) }
                continue
            }
            val existing = date.targets.firstNotNullOfOrNull { (ns, name) -> property(ns, name) }?.let(::parseIsoDate)
            val local = sets[dateKey]?.let(::text)?.let(::exifDateToIso) ?: existing?.local ?: continue
            // Sub-second and offset tags always live in the Exif IFD, even for IFD0's ModifyDate.
            val subSec = when (ExifIfd.Exif to date.subSecTag) {
                in sets -> sets.getValue(ExifIfd.Exif to date.subSecTag).let(::text)?.trim()?.filter(Char::isDigit)?.takeIf { it.isNotEmpty() }
                in removes -> null
                else -> existing?.fraction
            }
            val offset = when (ExifIfd.Exif to date.offsetTag) {
                in sets -> sets.getValue(ExifIfd.Exif to date.offsetTag).let(::text)?.trim()?.takeIf { OFFSET.matches(it) }
                in removes -> null
                else -> existing?.offset
            }
            val iso = local + (subSec?.let { ".$it" } ?: "") + (offset ?: "")
            date.targets.forEach { (ns, name) -> meta.setProperty(ns, name, iso) }
        }
    }

    private fun gps() {
        coordinate(LATITUDE_REF, LATITUDE, "GPSLatitude", 'N', 'S')
        coordinate(LONGITUDE_REF, LONGITUDE, "GPSLongitude", 'E', 'W')
        coordinate(DEST_LATITUDE_REF, DEST_LATITUDE, "GPSDestLatitude", 'N', 'S')
        coordinate(DEST_LONGITUDE_REF, DEST_LONGITUDE, "GPSDestLongitude", 'E', 'W')
        if (touched(ExifIfd.Gps, GPS_TIME, GPS_DATE)) gpsTimeStamp()
        if (touched(ExifIfd.Gps, GPS_VERSION)) {
            meta.deleteProperty(Ns.EXIF, "GPSVersionID")
            sets[ExifIfd.Gps to GPS_VERSION]?.let { integers(it) }?.takeIf { it.isNotEmpty() }
                ?.let { meta.setProperty(Ns.EXIF, "GPSVersionID", it.joinToString(".")) }
        }
    }

    private fun coordinate(refTag: Int, valueTag: Int, name: String, positive: Char, negative: Char) {
        if (!touched(ExifIfd.Gps, refTag, valueTag)) return
        if ((ExifIfd.Gps to valueTag) in removes) {
            meta.deleteProperty(Ns.EXIF, name)
            return
        }
        val existing = property(Ns.EXIF, name)
        val ref = sets[ExifIfd.Gps to refTag]?.let(::text)?.trim()?.firstOrNull()?.uppercaseChar()
            ?: existing?.lastOrNull()?.uppercaseChar()?.takeIf { it == positive || it == negative }
            ?: positive
        val text = sets[ExifIfd.Gps to valueTag]?.let { rationals(it) }?.let { formatCoordinate(it, ref) }
            ?: existing?.takeIf { it.isNotEmpty() }?.dropLastWhile { it.isLetter() }?.plus(ref)
            ?: return
        meta.setProperty(Ns.EXIF, name, text)
    }

    private fun gpsTimeStamp() {
        if ((ExifIfd.Gps to GPS_TIME) in removes || (ExifIfd.Gps to GPS_DATE) in removes) {
            meta.deleteProperty(Ns.EXIF, "GPSTimeStamp")
            return
        }
        val existing = property(Ns.EXIF, "GPSTimeStamp")?.let(::parseIsoDate)
        val date = sets[ExifIfd.Gps to GPS_DATE]?.let(::text)?.trim()?.replace(':', '-')?.takeIf { DATE.matches(it) }
            ?: existing?.local?.substringBefore('T') ?: return
        val time = sets[ExifIfd.Gps to GPS_TIME]?.let { rationals(it) }?.takeIf { it.size == 3 && it.all { r -> r.denominator != 0L } }?.let { (h, m, s) ->
            val seconds = BigDecimal(s.numerator).divide(BigDecimal(s.denominator), 3, RoundingMode.HALF_UP).stripTrailingZeros()
            val whole = seconds.toBigInteger().toInt()
            val fraction = seconds.subtract(BigDecimal(whole)).toPlainString().removePrefix("0").takeIf { it.startsWith(".") } ?: ""
            "%02d:%02d:%02d%s".format(Locale.ROOT, (h.numerator / h.denominator), (m.numerator / m.denominator), whole, fraction)
        } ?: existing?.local?.substringAfter('T', "")?.takeIf { it.isNotEmpty() }?.plus(existing.fraction?.let { ".$it" } ?: "") ?: return
        meta.setProperty(Ns.EXIF, "GPSTimeStamp", "${date}T${time}Z")
    }

    private fun property(namespace: String, name: String): String? = try {
        meta.getPropertyString(namespace, name)
    } catch (_: XMPException) {
        null
    }

    private class IsoDate(val local: String, val fraction: String?, val offset: String?)

    private fun parseIsoDate(text: String): IsoDate? {
        val match = ISO_DATE.matchEntire(text.trim()) ?: return null
        return IsoDate(match.groupValues[1], match.groupValues[2].takeIf { it.isNotEmpty() }, match.groupValues[3].takeIf { it.isNotEmpty() })
    }

    companion object {
        private val SEQ = PropertyOptions().setArrayOrdered(true)
        private val ISO_DATE = Regex("""(\d{4}-\d{2}-\d{2}T\d{2}:\d{2}(?::\d{2})?)(?:\.(\d+))?(Z|[+-]\d{2}:\d{2})?""")
        private val EXIF_DATE = Regex("""(\d{4}):(\d{2}):(\d{2})[ T](\d{2}):(\d{2}):(\d{2})""")
        private val OFFSET = Regex("""[+-]\d{2}:\d{2}""")
        private val DATE = Regex("""\d{4}-\d{2}-\d{2}""")

        private const val FLASH = 0x9209
        private const val GPS_VERSION = 0x0000
        private const val LATITUDE_REF = 0x0001
        private const val LATITUDE = 0x0002
        private const val LONGITUDE_REF = 0x0003
        private const val LONGITUDE = 0x0004
        private const val GPS_TIME = 0x0007
        private const val DEST_LATITUDE_REF = 0x0013
        private const val DEST_LATITUDE = 0x0014
        private const val DEST_LONGITUDE_REF = 0x0015
        private const val DEST_LONGITUDE = 0x0016
        private const val GPS_DATE = 0x001D

        private class DateTags(val ifd: ExifIfd, val dateTag: Int, val subSecTag: Int, val offsetTag: Int, val targets: List<Pair<String, String>>)

        private val DATES = listOf(
            DateTags(ExifIfd.Exif, 0x9003, 0x9291, 0x9011, listOf(Ns.EXIF to "DateTimeOriginal", Ns.PHOTOSHOP to "DateCreated")),
            DateTags(ExifIfd.Exif, 0x9004, 0x9292, 0x9012, listOf(Ns.XMP to "CreateDate")),
            // ModifyDate lives in IFD0, its sub-second and offset tags in the Exif IFD.
            DateTags(ExifIfd.Primary, 0x0132, 0x9290, 0x9010, listOf(Ns.XMP to "ModifyDate")),
        )

        private fun t(namespace: String, name: String, kind: Kind) = Target(namespace, name, kind)

        private val MAPPING: Map<Pair<ExifIfd, Int>, List<Target>> = buildMap {
            fun primary(tag: Int, vararg targets: Target) = put(ExifIfd.Primary to tag, targets.toList())
            fun exif(tag: Int, vararg targets: Target) = put(ExifIfd.Exif to tag, targets.toList())
            fun gps(tag: Int, vararg targets: Target) = put(ExifIfd.Gps to tag, targets.toList())

            primary(0x0100, t(Ns.TIFF, "ImageWidth", Kind.Int))
            primary(0x0101, t(Ns.TIFF, "ImageLength", Kind.Int))
            primary(0x0102, t(Ns.TIFF, "BitsPerSample", Kind.IntSeq))
            primary(0x0103, t(Ns.TIFF, "Compression", Kind.Int))
            primary(0x0106, t(Ns.TIFF, "PhotometricInterpretation", Kind.Int))
            primary(0x010E, t(Ns.DC, "description", Kind.LangAlt))
            primary(0x010F, t(Ns.TIFF, "Make", Kind.Text))
            primary(0x0110, t(Ns.TIFF, "Model", Kind.Text))
            primary(0x0112, t(Ns.TIFF, "Orientation", Kind.Int))
            primary(0x0115, t(Ns.TIFF, "SamplesPerPixel", Kind.Int))
            primary(0x011A, t(Ns.TIFF, "XResolution", Kind.Rational))
            primary(0x011B, t(Ns.TIFF, "YResolution", Kind.Rational))
            primary(0x0128, t(Ns.TIFF, "ResolutionUnit", Kind.Int))
            primary(0x0131, t(Ns.XMP, "CreatorTool", Kind.Text))
            primary(0x013B, t(Ns.DC, "creator", Kind.Creators))
            primary(0x0213, t(Ns.TIFF, "YCbCrPositioning", Kind.Int))
            primary(0x8298, t(Ns.DC, "rights", Kind.LangAlt))

            exif(0x829A, t(Ns.EXIF, "ExposureTime", Kind.Rational))
            exif(0x829D, t(Ns.EXIF, "FNumber", Kind.Rational))
            exif(0x8822, t(Ns.EXIF, "ExposureProgram", Kind.Int))
            exif(0x8824, t(Ns.EXIF, "SpectralSensitivity", Kind.Text))
            exif(0x8827, t(Ns.EXIF, "ISOSpeedRatings", Kind.IntSeq), t(Ns.EXIF_EX, "PhotographicSensitivity", Kind.Int))
            exif(0x8830, t(Ns.EXIF_EX, "SensitivityType", Kind.Int))
            exif(0x9000, t(Ns.EXIF, "ExifVersion", Kind.Version))
            exif(0x9101, t(Ns.EXIF, "ComponentsConfiguration", Kind.IntSeq))
            exif(0x9102, t(Ns.EXIF, "CompressedBitsPerPixel", Kind.Rational))
            exif(0x9201, t(Ns.EXIF, "ShutterSpeedValue", Kind.Rational))
            exif(0x9202, t(Ns.EXIF, "ApertureValue", Kind.Rational))
            exif(0x9203, t(Ns.EXIF, "BrightnessValue", Kind.Rational))
            exif(0x9204, t(Ns.EXIF, "ExposureBiasValue", Kind.Rational))
            exif(0x9205, t(Ns.EXIF, "MaxApertureValue", Kind.Rational))
            exif(0x9206, t(Ns.EXIF, "SubjectDistance", Kind.Rational))
            exif(0x9207, t(Ns.EXIF, "MeteringMode", Kind.Int))
            exif(0x9208, t(Ns.EXIF, "LightSource", Kind.Int))
            exif(0x920A, t(Ns.EXIF, "FocalLength", Kind.Rational))
            exif(0x9214, t(Ns.EXIF, "SubjectArea", Kind.IntSeq))
            exif(0x9286, t(Ns.EXIF, "UserComment", Kind.Comment))
            exif(0xA000, t(Ns.EXIF, "FlashpixVersion", Kind.Version))
            exif(0xA001, t(Ns.EXIF, "ColorSpace", Kind.Int))
            exif(0xA002, t(Ns.EXIF, "PixelXDimension", Kind.Int))
            exif(0xA003, t(Ns.EXIF, "PixelYDimension", Kind.Int))
            exif(0xA20E, t(Ns.EXIF, "FocalPlaneXResolution", Kind.Rational))
            exif(0xA20F, t(Ns.EXIF, "FocalPlaneYResolution", Kind.Rational))
            exif(0xA210, t(Ns.EXIF, "FocalPlaneResolutionUnit", Kind.Int))
            exif(0xA215, t(Ns.EXIF, "ExposureIndex", Kind.Rational))
            exif(0xA217, t(Ns.EXIF, "SensingMethod", Kind.Int))
            exif(0xA401, t(Ns.EXIF, "CustomRendered", Kind.Int))
            exif(0xA402, t(Ns.EXIF, "ExposureMode", Kind.Int))
            exif(0xA403, t(Ns.EXIF, "WhiteBalance", Kind.Int))
            exif(0xA404, t(Ns.EXIF, "DigitalZoomRatio", Kind.Rational))
            exif(0xA405, t(Ns.EXIF, "FocalLengthIn35mmFilm", Kind.Int))
            exif(0xA406, t(Ns.EXIF, "SceneCaptureType", Kind.Int))
            exif(0xA407, t(Ns.EXIF, "GainControl", Kind.Int))
            exif(0xA408, t(Ns.EXIF, "Contrast", Kind.Int))
            exif(0xA409, t(Ns.EXIF, "Saturation", Kind.Int))
            exif(0xA40A, t(Ns.EXIF, "Sharpness", Kind.Int))
            exif(0xA40C, t(Ns.EXIF, "SubjectDistanceRange", Kind.Int))
            exif(0xA420, t(Ns.EXIF, "ImageUniqueID", Kind.Text))
            exif(0xA430, t(Ns.EXIF_EX, "CameraOwnerName", Kind.Text), t(Ns.AUX, "OwnerName", Kind.Text))
            exif(0xA431, t(Ns.EXIF_EX, "BodySerialNumber", Kind.Text), t(Ns.AUX, "SerialNumber", Kind.Text))
            exif(0xA432, t(Ns.EXIF_EX, "LensSpecification", Kind.RationalSeq))
            exif(0xA433, t(Ns.EXIF_EX, "LensMake", Kind.Text))
            exif(0xA434, t(Ns.EXIF_EX, "LensModel", Kind.Text), t(Ns.AUX, "Lens", Kind.Text))
            exif(0xA435, t(Ns.EXIF_EX, "LensSerialNumber", Kind.Text), t(Ns.AUX, "LensSerialNumber", Kind.Text))
            exif(0xA460, t(Ns.EXIF_EX, "CompositeImage", Kind.Int))

            gps(0x0005, t(Ns.EXIF, "GPSAltitudeRef", Kind.Int))
            gps(0x0006, t(Ns.EXIF, "GPSAltitude", Kind.Rational))
            gps(0x0008, t(Ns.EXIF, "GPSSatellites", Kind.Text))
            gps(0x0009, t(Ns.EXIF, "GPSStatus", Kind.Text))
            gps(0x000A, t(Ns.EXIF, "GPSMeasureMode", Kind.Text))
            gps(0x000B, t(Ns.EXIF, "GPSDOP", Kind.Rational))
            gps(0x000C, t(Ns.EXIF, "GPSSpeedRef", Kind.Text))
            gps(0x000D, t(Ns.EXIF, "GPSSpeed", Kind.Rational))
            gps(0x000E, t(Ns.EXIF, "GPSTrackRef", Kind.Text))
            gps(0x000F, t(Ns.EXIF, "GPSTrack", Kind.Rational))
            gps(0x0010, t(Ns.EXIF, "GPSImgDirectionRef", Kind.Text))
            gps(0x0011, t(Ns.EXIF, "GPSImgDirection", Kind.Rational))
            gps(0x0012, t(Ns.EXIF, "GPSMapDatum", Kind.Text))
            gps(0x0017, t(Ns.EXIF, "GPSDestBearingRef", Kind.Text))
            gps(0x0018, t(Ns.EXIF, "GPSDestBearing", Kind.Rational))
            gps(0x0019, t(Ns.EXIF, "GPSDestDistanceRef", Kind.Text))
            gps(0x001A, t(Ns.EXIF, "GPSDestDistance", Kind.Rational))
            gps(0x001B, t(Ns.EXIF, "GPSProcessingMethod", Kind.Comment))
            gps(0x001C, t(Ns.EXIF, "GPSAreaInformation", Kind.Comment))
            gps(0x001E, t(Ns.EXIF, "GPSDifferential", Kind.Int))
            gps(0x001F, t(Ns.EXIF, "GPSHPositioningError", Kind.Rational))
        }

        private fun bool(value: Boolean) = if (value) "True" else "False"

        private fun text(value: ExifValue): String? = when (value) {
            is ExifValue.Ascii -> value.text.trimEnd('\u0000')
            is ExifValue.Undefined -> String(value.bytes, Charsets.UTF_8).trimEnd('\u0000')
            else -> null
        }

        private fun integers(value: ExifValue): List<Long> = when (value) {
            is ExifValue.Bytes -> value.values.map { it.toLong() }
            is ExifValue.Shorts -> value.values.map { it.toLong() }
            is ExifValue.Longs -> value.values
            is ExifValue.SignedLongs -> value.values.map { it.toLong() }
            is ExifValue.Undefined -> value.bytes.map { it.toLong() and 0xFF }
            else -> emptyList()
        }

        private fun rationals(value: ExifValue): List<Rational> = when (value) {
            is ExifValue.Rationals -> value.values
            is ExifValue.SignedRationals -> value.values
            else -> integers(value).map { Rational(it, 1) }
        }

        /** UserComment-style text: an 8-byte character code, then the text. */
        private fun comment(value: ExifValue): String? {
            if (value !is ExifValue.Undefined) return text(value)
            val bytes = value.bytes
            if (bytes.size < 8) return String(bytes, Charsets.UTF_8).trimEnd('\u0000', ' ')
            val code = String(bytes, 0, 8, Charsets.ISO_8859_1)
            val body = bytes.copyOfRange(8, bytes.size)
            val charset = when {
                code.startsWith("UNICODE") -> if (body.size >= 2 && body[0].toInt() == 0 && body[1].toInt() != 0) Charsets.UTF_16BE else Charsets.UTF_16LE
                code.startsWith("ASCII") -> Charsets.ISO_8859_1
                else -> Charsets.UTF_8
            }
            return String(body, charset).trimEnd('\u0000', ' ').takeIf { it.isNotEmpty() }
        }

        private fun exifDateToIso(text: String): String? {
            val m = EXIF_DATE.matchEntire(text.trim()) ?: return null
            val (y, mo, d, h, mi, s) = m.destructured
            if (y == "0000") return null
            return "$y-$mo-${d}T$h:$mi:$s"
        }

        /** "DDD,MM.mmmmk": whole degrees, decimal minutes with 4 to 8 decimals, hemisphere letter. */
        fun formatCoordinate(parts: List<Rational>, ref: Char): String? {
            if (parts.isEmpty() || parts.any { it.denominator == 0L }) return null
            var degrees = BigDecimal.ZERO
            parts.take(3).forEachIndexed { i, r ->
                val value = BigDecimal(r.numerator).divide(BigDecimal(r.denominator), 12, RoundingMode.HALF_UP)
                degrees += value.divide(BigDecimal(listOf(1, 60, 3600)[i]), 12, RoundingMode.HALF_UP)
            }
            val whole = degrees.toBigInteger()
            var minutes = (degrees - BigDecimal(whole)).multiply(BigDecimal(60)).setScale(8, RoundingMode.HALF_UP).stripTrailingZeros()
            if (minutes.scale() < 4) minutes = minutes.setScale(4)
            return "$whole,${minutes.toPlainString()}$ref"
        }
    }
}

/** Maps IPTC-IIM application record (2:xx) changes to the XMP properties that replace them. */
internal object IptcToXmp {
    fun apply(meta: XMPMeta, changes: List<IptcChange>) {
        for (change in changes) {
            if (change.record != 2) continue
            val target = TARGETS[change.dataset] ?: continue
            val (ns, name, kind) = target
            meta.deleteProperty(ns, name)
            val values = (change as? IptcChange.Set)?.values?.filter { it.isNotEmpty() }.orEmpty()
            if (values.isEmpty()) continue
            when (kind) {
                "text" -> meta.setProperty(ns, name, values.first())
                "alt" -> meta.setLocalizedText(ns, name, null, "x-default", values.first())
                "seq" -> values.forEach { meta.appendArrayItem(ns, name, PropertyOptions().setArrayOrdered(true), it, null) }
                "bag" -> values.forEach { meta.appendArrayItem(ns, name, PropertyOptions().setArray(true), it, null) }
            }
        }
    }

    private val TARGETS = mapOf(
        5 to Triple(Ns.DC, "title", "alt"),
        25 to Triple(Ns.DC, "subject", "bag"),
        40 to Triple(Ns.PHOTOSHOP, "Instructions", "text"),
        80 to Triple(Ns.DC, "creator", "seq"),
        85 to Triple(Ns.PHOTOSHOP, "AuthorsPosition", "text"),
        90 to Triple(Ns.PHOTOSHOP, "City", "text"),
        92 to Triple(Ns.IPTC_CORE, "Location", "text"),
        95 to Triple(Ns.PHOTOSHOP, "State", "text"),
        100 to Triple(Ns.IPTC_CORE, "CountryCode", "text"),
        101 to Triple(Ns.PHOTOSHOP, "Country", "text"),
        103 to Triple(Ns.PHOTOSHOP, "TransmissionReference", "text"),
        105 to Triple(Ns.PHOTOSHOP, "Headline", "text"),
        110 to Triple(Ns.PHOTOSHOP, "Credit", "text"),
        115 to Triple(Ns.PHOTOSHOP, "Source", "text"),
        116 to Triple(Ns.DC, "rights", "alt"),
        120 to Triple(Ns.DC, "description", "alt"),
        122 to Triple(Ns.PHOTOSHOP, "CaptionWriter", "text"),
    )
}
