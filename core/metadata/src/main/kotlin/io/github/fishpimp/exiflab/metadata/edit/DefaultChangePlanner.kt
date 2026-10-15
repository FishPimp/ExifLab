package io.github.fishpimp.exiflab.metadata.edit

import io.github.fishpimp.exiflab.metadata.ImageFormat
import io.github.fishpimp.exiflab.metadata.model.MetadataReport
import io.github.fishpimp.exiflab.metadata.model.Rational
import io.github.fishpimp.exiflab.metadata.model.SensitivityCategory
import io.github.fishpimp.exiflab.metadata.summary.MetadataDates
import io.github.fishpimp.exiflab.metadata.write.ExifIfd
import io.github.fishpimp.exiflab.metadata.write.ExifValue
import io.github.fishpimp.exiflab.metadata.write.MetadataBlock
import io.github.fishpimp.exiflab.metadata.write.sidecar.ExifToXmp
import java.time.Duration
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.ZoneId
import java.time.ZoneOffset
import java.time.ZonedDateTime
import kotlin.math.abs

/**
 * Plans [EditOperation]s on a working copy of the report's values, so each operation sees the
 * results of the ones before it, then derives the low-level [io.github.fishpimp.exiflab.metadata.write.MetadataChanges]
 * and the [FieldDiff]s by comparing the result with the original. Values that end up as they were
 * produce neither a change nor a diff line.
 *
 * Conventions: EXIF dates are "yyyy:MM:dd HH:mm:ss" with SubSecTime* and OffsetTime* companions;
 * XMP dates are ISO 8601 and keep their precision; IPTC dates are "CCYYMMDD" and times
 * "HHMMSS±HHMM". XMP and IPTC copies are only updated where they already exist, except for
 * authorship, which is written to XMP always and to IPTC in JPEG files.
 */
class DefaultChangePlanner : ChangePlanner {
    override fun plan(report: MetadataReport, operations: List<EditOperation>): EditPlan {
        val state = WorkingMetadata(report)
        val steps = PlanSteps(state)
        for (operation in operations) steps.apply(operation)
        return EditPlan(state.changes(), state.diff(), state.warnings.distinct())
    }
}

/** The rules of each [EditOperation], applied to a [WorkingMetadata]. */
internal class PlanSteps(private val state: WorkingMetadata) {
    fun apply(operation: EditOperation) {
        when (operation) {
            is EditOperation.SetExifTag -> {
                state.setExif(operation.ifd, operation.tag, operation.value)
                if (operation.ifd == ExifIfd.Exif && operation.tag in OFFSET_TAGS) ensureExifVersionForOffsets()
            }
            is EditOperation.RemoveExifTag -> state.removeExif(operation.ifd, operation.tag)
            is EditOperation.Raw -> {
                state.apply(operation.changes, onlyExisting = false)
                state.apply(operation.syncExisting, onlyExisting = true)
            }
            is EditOperation.SetCaptureTime -> setCaptureTime(operation)
            is EditOperation.ShiftCaptureTime -> shiftCaptureTime(operation.by)
            is EditOperation.ChangeTimeZone -> changeTimeZone(operation.cameraZone, operation.actualZone)
            is EditOperation.SetUtcOffset -> setUtcOffset(operation.offset)
            is EditOperation.SetLocation -> setLocation(operation)
            is EditOperation.RemoveLocation -> removeLocation(operation.includePlaceNames)
            is EditOperation.SetAuthorship -> setAuthorship(operation)
            is EditOperation.Strip -> strip(operation.categories)
        }
    }

    // ---- Dates ----

    /** An EXIF date tag with the Exif IFD tags holding its sub-seconds and UTC offset. */
    private enum class ExifDateSlot(val ifd: ExifIfd, val dateTag: Int, val subSecTag: Int, val offsetTag: Int) {
        Original(ExifIfd.Exif, ExifTags.DATE_TIME_ORIGINAL, ExifTags.SUB_SEC_TIME_ORIGINAL, ExifTags.OFFSET_TIME_ORIGINAL),
        Digitized(ExifIfd.Exif, ExifTags.DATE_TIME_DIGITIZED, ExifTags.SUB_SEC_TIME_DIGITIZED, ExifTags.OFFSET_TIME_DIGITIZED),
        Modify(ExifIfd.Primary, ExifTags.DATE_TIME, ExifTags.SUB_SEC_TIME, ExifTags.OFFSET_TIME),
    }

    private enum class DateRole { Original, Digitized, Modify }

    private class XmpDateSlot(val name: XmpName, val role: DateRole)

    private class IptcDateSlot(val dateDataset: Int, val timeDataset: Int)

    private fun readExifDate(slot: ExifDateSlot): LocalDateTime? =
        MetadataDates.parseExif(state.exifRaw(slot.ifd, slot.dateTag), state.exifRaw(ExifIfd.Exif, slot.subSecTag))?.local

    /**
     * Writes [local] to the slot's date tag and its sub-seconds. With [dropStaleSubSeconds] an
     * existing SubSecTime tag is removed when [local] has none; otherwise it keeps its width.
     */
    private fun writeExifDate(slot: ExifDateSlot, local: LocalDateTime, dropStaleSubSeconds: Boolean) {
        state.setExif(slot.ifd, slot.dateTag, ExifValue.Ascii(EditDates.formatExif(local)))
        val existing = state.exifRaw(ExifIfd.Exif, slot.subSecTag)?.trim()?.takeIf { it.isNotEmpty() }
        when {
            local.nano == 0 && existing == null -> Unit
            local.nano == 0 && dropStaleSubSeconds -> state.removeExif(ExifIfd.Exif, slot.subSecTag)
            else -> state.setExif(ExifIfd.Exif, slot.subSecTag, ExifValue.Ascii(EditDates.subSecond(local.nano, existing?.length ?: 0)))
        }
    }

    private fun writeExifOffset(slot: ExifDateSlot, offset: ZoneOffset) {
        state.setExif(ExifIfd.Exif, slot.offsetTag, ExifValue.Ascii(EditDates.formatOffset(offset)))
    }

    /** OffsetTime* tags arrived with EXIF 2.31; a file declaring an older version is moved to 2.32. */
    private fun ensureExifVersionForOffsets() {
        val raw = state.exifRaw(ExifIfd.Exif, ExifTags.EXIF_VERSION)
        val version = raw?.let(::exifVersionText)
        if (version == null || version < "0231") {
            state.setExif(ExifIfd.Exif, ExifTags.EXIF_VERSION, ExifValue.Undefined("0232".toByteArray(Charsets.US_ASCII)))
        }
    }

    /** "30 32 33 32" (hex, as the report shows UNDEFINED values) or "0232" as four digits. */
    private fun exifVersionText(raw: String): String? {
        val parts = raw.trim().split(' ')
        val text = if (parts.size == 4 && parts.all { it.length == 2 }) {
            parts.map { it.toIntOrNull(16)?.toChar() ?: return null }.joinToString("")
        } else {
            raw.trim()
        }
        return text.takeIf { it.length == 4 && it.all(Char::isDigit) }
    }

    /** The capture time before an operation, used to place date-only values. */
    private fun captureAnchor(): LocalDateTime? =
        readExifDate(ExifDateSlot.Original)
            ?: XMP_DATES.filter { it.role == DateRole.Original }.firstNotNullOfOrNull { slot ->
                EditDates.parseXmp(state.xmpValue(slot.name))?.takeIf { it.hasTime }?.local
            }
            ?: readExifDate(ExifDateSlot.Digitized)
            ?: IPTC_DATES.firstNotNullOfOrNull { slot ->
                val date = EditDates.parseIptcDate(state.iptcValues(slot.dateDataset)?.firstOrNull()) ?: return@firstNotNullOfOrNull null
                EditDates.parseIptcTime(state.iptcValues(slot.timeDataset)?.firstOrNull())?.let { date.atTime(it.first) }
            }

    private fun setCaptureTime(operation: EditOperation.SetCaptureTime) {
        val local = operation.local
        val offset = operation.offset
        val slots = listOf(ExifDateSlot.Original, ExifDateSlot.Digitized) + if (operation.alsoModifyDate) listOf(ExifDateSlot.Modify) else emptyList()
        for (slot in slots) {
            writeExifDate(slot, local, dropStaleSubSeconds = true)
            if (offset != null) writeExifOffset(slot, offset)
        }
        if (offset != null) ensureExifVersionForOffsets()

        for (slot in XMP_DATES) {
            if (slot.role == DateRole.Modify && !operation.alsoModifyDate) continue
            val current = state.xmpValue(slot.name) ?: continue
            val parsed = EditDates.parseXmp(current)
            val written = EditDates.xmpDateTime(local, offset ?: parsed?.offset, zulu = offset == null && parsed?.zulu == true)
            state.setXmp(slot.name, XmpShape.Simple, listOf(EditDates.formatXmp(written)))
        }
        for (slot in IPTC_DATES) {
            if (state.iptcValues(slot.dateDataset) != null) state.setIptc(slot.dateDataset, listOf(EditDates.formatIptcDate(local.toLocalDate())))
            val time = state.iptcValues(slot.timeDataset)?.firstOrNull() ?: continue
            val oldOffset = EditDates.parseIptcTime(time)?.second
            state.setIptc(slot.timeDataset, listOf(EditDates.formatIptcTime(local.toLocalTime(), offset ?: oldOffset)))
        }
    }

    private fun shiftCaptureTime(by: Duration) {
        if (by.isZero) return
        val anchor = captureAnchor()
        var found = false
        for (slot in ExifDateSlot.entries) {
            val local = readExifDate(slot) ?: continue
            found = true
            writeExifDate(slot, local.plus(by), dropStaleSubSeconds = false)
        }
        for (slot in XMP_DATES) {
            val parsed = EditDates.parseXmp(state.xmpValue(slot.name)) ?: continue
            found = true
            val shifted = if (parsed.hasTime) {
                val local = parsed.local.plus(by)
                // A shift by seconds needs the seconds field even if the value had minute precision.
                val fields = if (parsed.fields == 4 && (by.seconds % 60 != 0L || by.nano != 0)) 5 else parsed.fields
                parsed.copy(local = local, fields = fields)
            } else {
                parsed.copy(local = shiftDate(parsed.local.toLocalDate(), anchor) { it.plus(by) }.atStartOfDay())
            }
            state.setXmp(slot.name, XmpShape.Simple, listOf(EditDates.formatXmp(shifted)))
        }
        for (slot in IPTC_DATES) {
            val date = EditDates.parseIptcDate(state.iptcValues(slot.dateDataset)?.firstOrNull())
            val time = EditDates.parseIptcTime(state.iptcValues(slot.timeDataset)?.firstOrNull())
            when {
                date != null && time != null -> {
                    val shifted = date.atTime(time.first).plus(by)
                    state.setIptc(slot.dateDataset, listOf(EditDates.formatIptcDate(shifted.toLocalDate())))
                    state.setIptc(slot.timeDataset, listOf(EditDates.formatIptcTime(shifted.toLocalTime(), time.second)))
                }
                date != null -> state.setIptc(slot.dateDataset, listOf(EditDates.formatIptcDate(shiftDate(date, anchor) { it.plus(by) })))
                time != null -> state.setIptc(slot.timeDataset, listOf(EditDates.formatIptcTime(time.first.plus(by), time.second)))
                else -> continue
            }
            found = true
        }
        if (!found) {
            state.warnings += "There is no capture date to shift"
            return
        }
        shiftGps(by)
    }

    /** GPS date and time stamps (UTC) move with the camera clock so the two stay consistent. */
    private fun shiftGps(by: Duration) {
        val time = EditDates.parseGpsTime(state.exifRaw(ExifIfd.Gps, ExifTags.GPS_TIME_STAMP))
        val date = EditDates.parseGpsDate(state.exifRaw(ExifIfd.Gps, ExifTags.GPS_DATE_STAMP))
        if (time != null) {
            val shifted = (date ?: LocalDate.of(2000, 1, 1)).atTime(time).plus(by)
            state.setExif(ExifIfd.Gps, ExifTags.GPS_TIME_STAMP, ExifValue.Rationals(EditDates.gpsTime(shifted.toLocalTime())))
            if (date != null) state.setExif(ExifIfd.Gps, ExifTags.GPS_DATE_STAMP, ExifValue.Ascii(EditDates.formatGpsDate(shifted.toLocalDate())))
        } else if (date != null && by.toDays() != 0L && by.minusDays(by.toDays()).isZero) {
            state.setExif(ExifIfd.Gps, ExifTags.GPS_DATE_STAMP, ExifValue.Ascii(EditDates.formatGpsDate(date.plusDays(by.toDays()))))
        }
        val xmpGps = EditDates.parseXmp(state.xmpValue(XMP_GPS_TIME))
        if (xmpGps != null && xmpGps.hasTime) {
            state.setXmp(XMP_GPS_TIME, XmpShape.Simple, listOf(EditDates.formatXmp(xmpGps.copy(local = xmpGps.local.plus(by)))))
        }
    }

    /**
     * A date without a time of day moves with the capture time when it is the capture date;
     * otherwise its time is taken as noon.
     */
    private fun shiftDate(date: LocalDate, anchor: LocalDateTime?, move: (LocalDateTime) -> LocalDateTime): LocalDate {
        val base = if (anchor != null && anchor.toLocalDate() == date) anchor else date.atTime(12, 0)
        return move(base).toLocalDate()
    }

    private fun changeTimeZone(cameraZone: ZoneId, actualZone: ZoneId) {
        // Wall times are read in the camera's zone (a time in a DST gap moves forward, an ambiguous one takes the earlier offset).
        fun convert(local: LocalDateTime): ZonedDateTime = ZonedDateTime.ofLocal(local, cameraZone, null).withZoneSameInstant(actualZone)

        val anchor = captureAnchor()
        var found = false
        var wroteOffset = false
        for (slot in ExifDateSlot.entries) {
            val local = readExifDate(slot) ?: continue
            found = true
            val converted = convert(local)
            writeExifDate(slot, converted.toLocalDateTime(), dropStaleSubSeconds = false)
            writeExifOffset(slot, converted.offset)
            wroteOffset = true
        }
        if (wroteOffset) ensureExifVersionForOffsets()
        for (slot in XMP_DATES) {
            val parsed = EditDates.parseXmp(state.xmpValue(slot.name)) ?: continue
            found = true
            val written = if (parsed.hasTime) {
                val converted = convert(parsed.local)
                parsed.copy(local = converted.toLocalDateTime(), offset = converted.offset, zulu = false)
            } else {
                parsed.copy(local = shiftDate(parsed.local.toLocalDate(), anchor) { convert(it).toLocalDateTime() }.atStartOfDay())
            }
            state.setXmp(slot.name, XmpShape.Simple, listOf(EditDates.formatXmp(written)))
        }
        for (slot in IPTC_DATES) {
            val date = EditDates.parseIptcDate(state.iptcValues(slot.dateDataset)?.firstOrNull())
            val time = EditDates.parseIptcTime(state.iptcValues(slot.timeDataset)?.firstOrNull())
            val timeDate = date ?: anchor?.toLocalDate()
            when {
                time != null && timeDate != null -> {
                    val converted = convert(timeDate.atTime(time.first))
                    if (date != null) state.setIptc(slot.dateDataset, listOf(EditDates.formatIptcDate(converted.toLocalDate())))
                    state.setIptc(slot.timeDataset, listOf(EditDates.formatIptcTime(converted.toLocalTime(), converted.offset)))
                }
                date != null -> state.setIptc(slot.dateDataset, listOf(EditDates.formatIptcDate(shiftDate(date, anchor) { convert(it).toLocalDateTime() })))
                else -> continue
            }
            found = true
        }
        if (!found) state.warnings += "There is no capture date to convert"
    }

    private fun setUtcOffset(offset: ZoneOffset) {
        var found = false
        var wroteOffset = false
        for (slot in ExifDateSlot.entries) {
            if (readExifDate(slot) == null) continue
            writeExifOffset(slot, offset)
            found = true
            wroteOffset = true
        }
        if (wroteOffset) ensureExifVersionForOffsets()
        for (slot in XMP_DATES) {
            val parsed = EditDates.parseXmp(state.xmpValue(slot.name)) ?: continue
            if (!parsed.hasTime) continue
            found = true
            state.setXmp(slot.name, XmpShape.Simple, listOf(EditDates.formatXmp(parsed.copy(offset = offset, zulu = false))))
        }
        for (slot in IPTC_DATES) {
            val time = EditDates.parseIptcTime(state.iptcValues(slot.timeDataset)?.firstOrNull()) ?: continue
            found = true
            state.setIptc(slot.timeDataset, listOf(EditDates.formatIptcTime(time.first, offset)))
        }
        if (!found) state.warnings += "There is no capture date to set the time zone for"
    }

    // ---- Location ----

    private fun setLocation(operation: EditOperation.SetLocation) {
        val latitude = operation.latitude
        val longitude = operation.longitude
        val altitude = operation.altitudeMeters
        if (!latitude.isFinite() || latitude !in -90.0..90.0) {
            state.warnings += "The latitude $latitude is outside -90 to 90 degrees; the location was not changed"
            return
        }
        if (!longitude.isFinite() || longitude !in -180.0..180.0) {
            state.warnings += "The longitude $longitude is outside -180 to 180 degrees; the location was not changed"
            return
        }
        if (altitude != null && (!altitude.isFinite() || abs(altitude) > MAX_ALTITUDE)) {
            state.warnings += "The altitude $altitude m is not plausible; the location was not changed"
            return
        }
        val previous = currentPosition()
        val moved = previous == null || abs(previous.first - latitude) > SAME_POSITION || abs(previous.second - longitude) > SAME_POSITION
        val latitudeDms = GpsCoordinates.toDms(latitude)
        val longitudeDms = GpsCoordinates.toDms(longitude)
        val latitudeRef = if (latitude < 0) "S" else "N"
        val longitudeRef = if (longitude < 0) "W" else "E"

        state.setExif(ExifIfd.Gps, ExifTags.GPS_VERSION_ID, ExifValue.Bytes(listOf(2, 3, 0, 0)))
        state.setExif(ExifIfd.Gps, ExifTags.GPS_LATITUDE_REF, ExifValue.Ascii(latitudeRef))
        state.setExif(ExifIfd.Gps, ExifTags.GPS_LATITUDE, ExifValue.Rationals(latitudeDms))
        state.setExif(ExifIfd.Gps, ExifTags.GPS_LONGITUDE_REF, ExifValue.Ascii(longitudeRef))
        state.setExif(ExifIfd.Gps, ExifTags.GPS_LONGITUDE, ExifValue.Rationals(longitudeDms))
        val altitudeRational = altitude?.let { GpsCoordinates.altitude(it) }
        if (altitude != null) {
            state.setExif(ExifIfd.Gps, ExifTags.GPS_ALTITUDE_REF, ExifValue.Bytes(listOf(if (altitude < 0) 1 else 0)))
            state.setExif(ExifIfd.Gps, ExifTags.GPS_ALTITUDE, ExifValue.Rationals(listOf(altitudeRational!!)))
        } else if (moved) {
            state.removeExif(ExifIfd.Gps, ExifTags.GPS_ALTITUDE_REF)
            state.removeExif(ExifIfd.Gps, ExifTags.GPS_ALTITUDE)
        }
        state.setExif(ExifIfd.Gps, ExifTags.GPS_MAP_DATUM, ExifValue.Ascii("WGS-84"))

        if (state.hasXmp) {
            setXmpCoordinate(xmpExif("GPSLatitude"), latitudeDms, latitudeRef[0])
            setXmpCoordinate(xmpExif("GPSLongitude"), longitudeDms, longitudeRef[0])
            if (altitude != null) {
                state.setXmp(xmpExif("GPSAltitude"), XmpShape.Simple, listOf(altitudeRational.toString()))
                state.setXmp(xmpExif("GPSAltitudeRef"), XmpShape.Simple, listOf(if (altitude < 0) "1" else "0"))
            } else if (moved) {
                state.removeXmp(xmpExif("GPSAltitude"))
                state.removeXmp(xmpExif("GPSAltitudeRef"))
            }
        }
    }

    /** Writes an XMP coordinate ("59,19.7520N") unless the existing one is the same position written differently. */
    private fun setXmpCoordinate(name: XmpName, dms: List<Rational>, ref: Char) {
        val text = ExifToXmp.formatCoordinate(dms, ref) ?: return
        val existing = GpsCoordinates.parseXmp(state.xmpValue(name))
        val target = GpsCoordinates.parseXmp(text)
        if (existing != null && target != null && abs(existing - target) <= SAME_POSITION) return
        state.setXmp(name, XmpShape.Simple, listOf(text))
    }

    /** The EXIF GPS position in the working copy, or null when absent or unreadable. */
    private fun currentPosition(): Pair<Double, Double>? {
        fun coordinate(valueTag: Int, refTag: Int, negative: String): Double? {
            val parts = state.exifRaw(ExifIfd.Gps, valueTag)?.let(EditDates::parseRationals)?.takeIf { it.size == 3 } ?: return null
            val degrees = parts[0] + parts[1] / 60 + parts[2] / 3600
            return if (state.exifRaw(ExifIfd.Gps, refTag)?.trim()?.uppercase() == negative) -degrees else degrees
        }
        val latitude = coordinate(ExifTags.GPS_LATITUDE, ExifTags.GPS_LATITUDE_REF, "S") ?: return null
        val longitude = coordinate(ExifTags.GPS_LONGITUDE, ExifTags.GPS_LONGITUDE_REF, "W") ?: return null
        return latitude to longitude
    }

    private fun removeLocation(includePlaceNames: Boolean) {
        // A report with parse problems may not show a GPS IFD that is there, so it is dropped regardless.
        if (state.hasIfd(ExifIfd.Gps) || state.originalExif.keys.any { it.ifd == ExifIfd.Gps } || state.uncertain) state.dropIfd(ExifIfd.Gps)
        state.xmp.keys.filter { it.namespace == XmpNs.EXIF && it.name.startsWith("GPS") }.forEach(state::removeXmp)
        // Vendor positions (DJI drone-dji:GpsLatitude, altitudes) and anything else XMP classifies as a location.
        state.xmp.keys.filter { (includePlaceNames || it !in PLACE_XMP) && state.xmpSensitivity(it) == SensitivityCategory.Location }
            .forEach(state::removeXmp)
        if (includePlaceNames) {
            PLACE_XMP.forEach(state::removeXmp)
            PLACE_IPTC.forEach { state.removeIptc(it) }
        }
    }

    // ---- Authorship ----

    private fun setAuthorship(operation: EditOperation.SetAuthorship) {
        if (operation.changeArtist) {
            setAuthorField(
                value = operation.artist, what = "artist", exifTag = ExifTags.ARTIST,
                xmp = { state.setXmp(XmpName(XmpNs.DC, "creator"), XmpShape.Seq, listOf(it)) },
                xmpName = XmpName(XmpNs.DC, "creator"), iptcDataset = IptcTags.BY_LINE,
            )
        }
        if (operation.changeCopyright) {
            setAuthorField(
                value = operation.copyright, what = "copyright", exifTag = ExifTags.COPYRIGHT,
                xmp = { state.setXmp(XmpName(XmpNs.DC, "rights"), XmpShape.LangAlt, listOf(it)) },
                xmpName = XmpName(XmpNs.DC, "rights"), iptcDataset = IptcTags.COPYRIGHT_NOTICE,
            )
        }
    }

    private fun setAuthorField(value: String?, what: String, exifTag: Int, xmp: (String) -> Unit, xmpName: XmpName, iptcDataset: Int) {
        val text = value?.trim()?.takeIf { it.isNotEmpty() }
        val writeIptc = state.hasIptc || state.report.format == ImageFormat.Jpeg
        if (text == null) {
            state.removeExif(ExifIfd.Primary, exifTag)
            state.removeXmp(xmpName)
            state.removeIptc(iptcDataset)
            return
        }
        val ascii = AsciiText.lossless(text)
        if (ascii != null) {
            state.setExif(ExifIfd.Primary, exifTag, ExifValue.Ascii(ascii))
        } else {
            state.removeExif(ExifIfd.Primary, exifTag)
            state.warnings += "The $what has characters EXIF cannot store; it is saved in XMP" + if (writeIptc) " and IPTC only" else " only"
        }
        xmp(text)
        if (writeIptc) {
            val max = IptcTags.MAX_BYTES.getValue(iptcDataset)
            if (text.toByteArray(Charsets.UTF_8).size <= max) {
                state.setIptc(iptcDataset, listOf(text))
            } else {
                state.removeIptc(iptcDataset)
                state.warnings += "The $what is longer than IPTC allows ($max bytes); it is saved in EXIF and XMP only"
            }
        }
    }

    // ---- Strip ----

    private fun strip(categories: Set<StripCategory>) {
        if (StripCategory.All in categories) {
            stripAll()
            return
        }
        for (category in categories) when (category) {
            StripCategory.Location -> removeLocation(includePlaceNames = true)
            StripCategory.DeviceIdentifiers -> removeFields(StripRules.deviceIdentifiers)
            StripCategory.DeviceInfo -> removeFields(StripRules.deviceInfo)
            StripCategory.PersonNames -> removeFields(StripRules.personNames)
            StripCategory.People -> removeFields(StripRules.people)
            StripCategory.Dates -> removeFields(StripRules.dates)
            StripCategory.MakerNotes -> state.removeBlock(MetadataBlock.MakerNote)
            StripCategory.Thumbnail -> state.removeBlock(MetadataBlock.ExifThumbnail)
            StripCategory.EditHistory -> removeFields(StripRules.editHistory)
            StripCategory.All -> Unit
        }
    }

    private fun removeFields(rules: StripRules.Fields) {
        rules.exif.forEach { state.removeExif(it.ifd, it.tag) }
        rules.xmp.forEach(state::removeXmp)
        rules.xmpNamespaces.forEach { namespace -> if (state.xmp.keys.any { it.namespace == namespace }) state.removeNamespace(namespace) }
        rules.iptc.forEach { state.removeIptc(it) }
    }

    /** Everything goes except the orientation (re-added) and the ICC profile, which change how the image looks. */
    private fun stripAll() {
        val orientation = state.exifRaw(ExifIfd.Primary, ExifTags.ORIENTATION)?.trim()?.toIntOrNull()?.takeIf { it in 1..8 }
            ?: state.xmpValue(XmpName(XmpNs.TIFF, "Orientation"))?.trim()?.toIntOrNull()?.takeIf { it in 1..8 }
        // An EXIF block holding only the orientation and the layout tags a JPEG needs is what a
        // previous strip left; removing and rebuilding it would change nothing.
        val alreadyMinimal = !state.uncertain && state.exif.keys.all { it.ifd == ExifIfd.Primary && it.tag in MINIMAL_IFD0 }
        val blocks = listOf(MetadataBlock.Xmp, MetadataBlock.Iptc, MetadataBlock.PhotoshopResources, MetadataBlock.Comments)
        for (block in if (alreadyMinimal) blocks else listOf(MetadataBlock.Exif) + blocks) state.removeBlock(block)
        if (orientation != null && !alreadyMinimal) state.setExif(ExifIfd.Primary, ExifTags.ORIENTATION, ExifValue.Shorts(listOf(orientation)))
    }

    private companion object {
        val OFFSET_TAGS = setOf(ExifTags.OFFSET_TIME, ExifTags.OFFSET_TIME_ORIGINAL, ExifTags.OFFSET_TIME_DIGITIZED)

        /** Orientation, X/YResolution, ResolutionUnit and YCbCrPositioning. */
        val MINIMAL_IFD0 = setOf(ExifTags.ORIENTATION, 0x011A, 0x011B, 0x0128, 0x0213)

        val XMP_DATES = listOf(
            XmpDateSlot(XmpName(XmpNs.EXIF, "DateTimeOriginal"), DateRole.Original),
            XmpDateSlot(XmpName(XmpNs.PHOTOSHOP, "DateCreated"), DateRole.Original),
            XmpDateSlot(XmpName(XmpNs.EXIF, "DateTimeDigitized"), DateRole.Digitized),
            XmpDateSlot(XmpName(XmpNs.XMP, "CreateDate"), DateRole.Digitized),
            XmpDateSlot(XmpName(XmpNs.XMP, "ModifyDate"), DateRole.Modify),
        )
        val XMP_GPS_TIME = XmpName(XmpNs.EXIF, "GPSTimeStamp")

        val IPTC_DATES = listOf(
            IptcDateSlot(IptcTags.DATE_CREATED, IptcTags.TIME_CREATED),
            IptcDateSlot(IptcTags.DIGITAL_CREATION_DATE, IptcTags.DIGITAL_CREATION_TIME),
        )

        val PLACE_XMP = listOf(
            XmpName(XmpNs.PHOTOSHOP, "City"), XmpName(XmpNs.PHOTOSHOP, "State"), XmpName(XmpNs.PHOTOSHOP, "Country"),
            XmpName(XmpNs.IPTC_CORE, "Location"), XmpName(XmpNs.IPTC_CORE, "CountryCode"),
            XmpName(XmpNs.IPTC_EXT, "LocationCreated"), XmpName(XmpNs.IPTC_EXT, "LocationShown"),
        )
        val PLACE_IPTC = listOf(
            IptcTags.CITY, IptcTags.SUB_LOCATION, IptcTags.PROVINCE_STATE, IptcTags.COUNTRY_CODE, IptcTags.COUNTRY_NAME,
            IptcTags.CONTENT_LOCATION_CODE, IptcTags.CONTENT_LOCATION_NAME,
        )

        /** Positions closer than this (degrees, about 1 cm) count as unchanged. */
        const val SAME_POSITION = 1e-7
        const val MAX_ALTITUDE = 100_000.0

        fun xmpExif(name: String) = XmpName(XmpNs.EXIF, name)
    }
}

/** Degrees as EXIF GPS rationals. */
internal object GpsCoordinates {
    /** Seconds are stored in ten-thousandths (about 3 mm), well below what GPS can resolve. */
    private const val SECOND_DENOMINATOR = 10_000L

    /** |[degrees]| as degrees, minutes and seconds; the sign goes in the reference tag. */
    fun toDms(degrees: Double): List<Rational> {
        val units = Math.round(abs(degrees) * 3600.0 * SECOND_DENOMINATOR)
        val perDegree = 3600 * SECOND_DENOMINATOR
        val perMinute = 60 * SECOND_DENOMINATOR
        return listOf(
            Rational(units / perDegree, 1),
            Rational(units % perDegree / perMinute, 1),
            Rational(units % perMinute, SECOND_DENOMINATOR),
        )
    }

    private val xmpPattern = Regex("""^(\d{1,3}),(\d{1,2}(?:\.\d+)?)(?:,(\d{1,2}(?:\.\d+)?))?([NSEWnsew])$""")

    /** Signed degrees of an XMP GPS coordinate, "DDD,MM.mmk" or "DDD,MM,SSk"; null when malformed. */
    fun parseXmp(text: String?): Double? {
        val match = text?.trim()?.let(xmpPattern::matchEntire) ?: return null
        val (degrees, minutes, seconds, ref) = match.destructured
        val value = degrees.toDouble() + minutes.toDouble() / 60 + (seconds.toDoubleOrNull() ?: 0.0) / 3600
        return if (ref.uppercase() == "S" || ref.uppercase() == "W") -value else value
    }

    /** |[meters]| in centimeters, or whole meters when exact. */
    fun altitude(meters: Double): Rational {
        val centimeters = Math.round(abs(meters) * 100)
        return if (centimeters % 100 == 0L) Rational(centimeters / 100, 1) else Rational(centimeters, 100)
    }
}

/** ASCII forms of text for EXIF ASCII tags, only where nothing is lost. */
internal object AsciiText {
    private val equivalents = mapOf(
        '©' to "(c)", '®' to "(R)", '™' to "(TM)",
        '‘' to "'", '’' to "'", '‚' to "'", '′' to "'",
        '“' to "\"", '”' to "\"", '„' to "\"", '″' to "\"",
        '‐' to "-", '‑' to "-", '‒' to "-", '–' to "-", '—' to "-", '−' to "-",
        ' ' to " ", ' ' to " ", ' ' to " ", ' ' to " ", ' ' to " ",
        '…' to "...",
    )

    /**
     * [text] in printable ASCII, with typographic characters (copyright sign, curly quotes, dashes,
     * special spaces) replaced by their usual ASCII spellings; null when a letter or other
     * character has no such equivalent.
     */
    fun lossless(text: String): String? {
        val out = StringBuilder(text.length)
        for (c in text) {
            when {
                c.code in 0x20..0x7E -> out.append(c)
                c == '\n' || c == '\t' -> out.append(c)
                else -> out.append(equivalents[c] ?: return null)
            }
        }
        return out.toString()
    }
}
