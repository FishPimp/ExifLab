package io.github.fishpimp.exiflab.metadata.edit

import com.adobe.internal.xmp.XMPMetaFactory
import io.github.fishpimp.exiflab.metadata.model.DirectoryGroup
import io.github.fishpimp.exiflab.metadata.model.MetadataReport
import io.github.fishpimp.exiflab.metadata.model.MetadataTag
import io.github.fishpimp.exiflab.metadata.model.SensitivityCategory
import io.github.fishpimp.exiflab.metadata.summary.MetadataDates
import io.github.fishpimp.exiflab.metadata.write.ExifChange
import io.github.fishpimp.exiflab.metadata.write.ExifIfd
import io.github.fishpimp.exiflab.metadata.write.ExifValue
import io.github.fishpimp.exiflab.metadata.write.IptcChange
import io.github.fishpimp.exiflab.metadata.write.MetadataChanges
import io.github.fishpimp.exiflab.metadata.write.XmpArrayKind
import io.github.fishpimp.exiflab.metadata.write.XmpChange

/**
 * The fields of the tag editor: common EXIF tags of IFD0 and the Exif IFD, the main XMP
 * (Dublin Core, xmp:, photoshop:) properties and the IPTC fields people fill in.
 *
 * Ids are "exif:ifd0:0x013B" and "exif:subifd:0x9003" for EXIF, "xmp:dc:title" for XMP and
 * "iptc:2:25" for IPTC; [FieldIds] names the common ones. Values travel as text: dates as
 * "yyyy-MM-dd HH:mm:ss", rationals as "1/250" or "2.8", choices as their option value, lists
 * as [FieldKind.TextList.join] text.
 *
 * Fields that exist in several blocks stay in sync: author and copyright through
 * [EditOperation.SetAuthorship], title, description, keywords and place names by updating the
 * copies that already exist in the other blocks ([EditOperation.Raw.syncExisting]).
 */
class DefaultTagCatalog : TagCatalog {
    override val fields: List<EditableField> = ENTRIES.map { it.field }

    private val entries = ENTRIES.associateBy { it.field.id }

    override fun field(id: String): EditableField? = entries[id]?.field

    private fun entry(field: EditableField): CatalogEntry =
        entries[field.id] ?: throw IllegalArgumentException("Unknown field ${field.id}")

    override fun currentValue(field: EditableField, report: MetadataReport): String? {
        val entry = entry(field)
        return when (val storage = entry.storage) {
            is Storage.Exif -> exifCurrent(storage, report)
            is Storage.Xmp -> xmpCurrent(storage, field.kind, report)
            is Storage.Iptc -> iptcCurrent(storage, field.kind, report)
        }
    }

    override fun validate(field: EditableField, input: String): Validation {
        val entry = entry(field)
        return when (val kind = field.kind) {
            is FieldKind.Text -> validateText(kind, input, countBytes = entry.storage is Storage.Iptc)
            is FieldKind.Integer -> {
                val number = input.trim().removePrefix("+").toLongOrNull() ?: return Validation.Invalid(ValidationError.NotANumber)
                if (number !in kind.min..kind.max) Validation.Invalid(ValidationError.OutOfRange) else Validation.Valid(number.toString())
            }
            is FieldKind.Number -> validateNumber(kind, entry, input)
            FieldKind.DateTime -> FieldValues.parseDateTime(input)?.let { Validation.Valid(FieldValues.formatDateTime(it)) }
                ?: Validation.Invalid(if (input.isBlank()) ValidationError.Empty else ValidationError.InvalidDate)
            is FieldKind.Choice -> validateChoice(kind, entry, input)
            FieldKind.TextList -> {
                val items = FieldKind.TextList.split(input)
                when {
                    items.isEmpty() -> Validation.Invalid(ValidationError.Empty)
                    entry.storage is Storage.Iptc && items.any { it.toByteArray(Charsets.UTF_8).size > IptcTags.MAX_BYTES.getValue(IptcTags.KEYWORDS) } ->
                        Validation.Invalid(ValidationError.TooLong)
                    else -> Validation.Valid(FieldKind.TextList.join(items))
                }
            }
        }
    }

    /**
     * @throws IllegalArgumentException when [input] does not pass [validate].
     */
    override fun operationFor(field: EditableField, input: String?): EditOperation {
        val entry = entry(field)
        val value = input?.let {
            when (val validation = validate(field, it)) {
                is Validation.Valid -> validation.normalized
                is Validation.Invalid -> throw IllegalArgumentException("Invalid value for ${field.id}: ${validation.error}")
            }
        }
        when (entry.author) {
            AuthorRole.Artist -> return EditOperation.SetAuthorship(value, null, changeArtist = true, changeCopyright = false)
            AuthorRole.Copyright -> return EditOperation.SetAuthorship(null, value, changeArtist = false, changeCopyright = true)
            null -> Unit
        }
        val storage = entry.storage
        if (storage is Storage.Exif && entry.syncs.isEmpty()) {
            return if (value == null) {
                EditOperation.RemoveExifTag(storage.ifd, storage.tag)
            } else {
                EditOperation.SetExifTag(storage.ifd, storage.tag, exifValue(storage, value))
            }
        }
        val action = if (value == null) "Remove" else "Set"
        return EditOperation.Raw(
            changes = change(storage, value, field.kind) ?: MetadataChanges(),
            description = "$action ${field.label}",
            syncExisting = entry.syncs.mapNotNull { change(it, value, field.kind) }.fold(MetadataChanges()) { acc, c -> acc + c },
        )
    }

    // ---- Current values ----

    private fun exifCurrent(storage: Storage.Exif, report: MetadataReport): String? {
        val tag = report.directories.firstOrNull { it.id == storage.ifd.reportId() }?.tags?.firstOrNull { it.id == storage.tag } ?: return null
        val raw = tag.rawValue.trim()
        return when (storage.encoding) {
            ExifEncoding.Date -> MetadataDates.parseExif(raw)?.local?.let(FieldValues::formatDateTime) ?: raw
            ExifEncoding.Rational, ExifEncoding.SignedRational ->
                raw.substringBefore(' ').let { FieldValues.parseNumber(it) }?.let { FieldValues.formatNumber(it, storage.style) } ?: raw
            ExifEncoding.Short -> raw.substringBefore(' ')
            ExifEncoding.Comment -> tag.displayValue.trim()
            ExifEncoding.Ascii, ExifEncoding.Offset -> raw
        }.takeIf { it.isNotEmpty() }
    }

    private fun xmpCurrent(storage: Storage.Xmp, kind: FieldKind, report: MetadataReport): String? {
        val prefix = XMPMetaFactory.getSchemaRegistry().getNamespacePrefix(storage.name.namespace)?.removeSuffix(":") ?: storage.name.preferredPrefix
        val path = "$prefix:${storage.name.name}"
        val tags = report.directories.filter { it.group == DirectoryGroup.Xmp }
            .firstNotNullOfOrNull { directory -> directory.tags.filter { it.name == path || it.name.startsWith("$path[") }.takeIf { it.isNotEmpty() } }
            ?: return null
        val values = tags.map { it.rawValue }
        return (if (kind == FieldKind.TextList) FieldKind.TextList.join(values) else values.first()).takeIf { it.isNotEmpty() }
    }

    private fun iptcCurrent(storage: Storage.Iptc, kind: FieldKind, report: MetadataReport): String? {
        val tag: MetadataTag = report.directories.filter { it.group == DirectoryGroup.Iptc }
            .firstNotNullOfOrNull { directory -> directory.tags.firstOrNull { it.id == (IptcTags.RECORD shl 8 or storage.dataset) } }
            ?: return null
        return if (kind == FieldKind.TextList) {
            FieldKind.TextList.join(tag.displayValue.split(';').map { it.trim() }.filter { it.isNotEmpty() })
        } else {
            tag.rawValue
        }.takeIf { it.isNotEmpty() }
    }

    // ---- Validation ----

    private fun validateText(kind: FieldKind.Text, input: String, countBytes: Boolean): Validation {
        val text = if (kind.multiline) {
            input.replace("\r\n", "\n").replace('\r', '\n').trim()
        } else {
            input.trim().replace(Regex("""\s*[\r\n]+\s*"""), " ")
        }
        if (text.isEmpty()) return Validation.Invalid(ValidationError.Empty)
        if (kind.asciiOnly && text.any { !(it.code in 0x20..0x7E || (kind.multiline && it == '\n')) }) {
            return Validation.Invalid(ValidationError.NotAscii)
        }
        val length = if (countBytes) text.toByteArray(Charsets.UTF_8).size else text.length
        if (kind.maxLength != null && length > kind.maxLength) return Validation.Invalid(ValidationError.TooLong)
        return Validation.Valid(text)
    }

    private fun validateNumber(kind: FieldKind.Number, entry: CatalogEntry, input: String): Validation {
        if (input.isBlank()) return Validation.Invalid(ValidationError.Empty)
        val number = FieldValues.parseNumber(input) ?: return Validation.Invalid(ValidationError.NotANumber)
        val value = number.value
        if ((!kind.signed && value < 0) || (kind.min != null && value < kind.min) || (kind.max != null && value > kind.max)) {
            return Validation.Invalid(ValidationError.OutOfRange)
        }
        val style = (entry.storage as? Storage.Exif)?.style ?: NumberStyle.Decimal
        val normalized = FieldValues.formatNumber(number, style)
        // The value must fit a TIFF (signed) rational.
        val rational = runCatching { FieldValues.toRational(normalized) }.getOrNull() ?: return Validation.Invalid(ValidationError.OutOfRange)
        val range = if (kind.signed) Int.MIN_VALUE.toLong()..Int.MAX_VALUE.toLong() else 0L..0xFFFFFFFFL
        if (rational.numerator !in range || rational.denominator !in 1L..range.last) return Validation.Invalid(ValidationError.OutOfRange)
        return Validation.Valid(normalized)
    }

    private fun validateChoice(kind: FieldKind.Choice, entry: CatalogEntry, input: String): Validation {
        val text = input.trim()
        if (text.isEmpty()) return Validation.Invalid(ValidationError.Empty)
        kind.options.firstOrNull { it.value == text || it.label.equals(text, ignoreCase = true) }?.let { return Validation.Valid(it.value) }
        if ((entry.storage as? Storage.Exif)?.encoding == ExifEncoding.Offset) {
            val offset = EditDates.parseOffset(text.removePrefix("UTC").removePrefix("GMT"))
            val formatted = offset?.let { EditDates.formatOffset(it) }
            kind.options.firstOrNull { it.value == formatted }?.let { return Validation.Valid(it.value) }
        }
        text.toIntOrNull()?.let { number -> kind.options.firstOrNull { it.value.toIntOrNull() == number }?.let { return Validation.Valid(it.value) } }
        return Validation.Invalid(ValidationError.InvalidChoice)
    }

    // ---- Operations ----

    private fun exifValue(storage: Storage.Exif, value: String): ExifValue = when (storage.encoding) {
        ExifEncoding.Ascii, ExifEncoding.Offset -> ExifValue.Ascii(value)
        ExifEncoding.Short -> ExifValue.Shorts(listOf(value.toInt()))
        ExifEncoding.Rational -> ExifValue.Rationals(listOf(FieldValues.toRational(value)))
        ExifEncoding.SignedRational -> ExifValue.SignedRationals(listOf(FieldValues.toRational(value)))
        ExifEncoding.Date -> ExifValue.Ascii(EditDates.formatExif(FieldValues.parseDateTime(value)!!))
        ExifEncoding.Comment -> ExifValue.Undefined(FieldValues.userComment(value))
    }

    /**
     * The change that writes [value] (null removes) to [storage]. A value the storage cannot hold
     * as it is (non-ASCII text in an EXIF ASCII tag, text longer than IPTC allows) removes that
     * copy instead, so no stale value is left behind.
     */
    private fun change(storage: Storage, value: String?, kind: FieldKind): MetadataChanges? = when (storage) {
        is Storage.Exif -> {
            val fitting = value?.let { if (storage.encoding == ExifEncoding.Ascii) AsciiText.lossless(it) else it }
            if (fitting == null) {
                MetadataChanges(exif = listOf(ExifChange.Remove(storage.ifd, storage.tag)))
            } else {
                MetadataChanges(exif = listOf(ExifChange.Set(storage.ifd, storage.tag, exifValue(storage, fitting))))
            }
        }
        is Storage.Xmp -> {
            val ns = storage.name.namespace
            val prefix = storage.name.preferredPrefix
            val name = storage.name.name
            val change = when {
                value == null -> XmpChange.Remove(ns, name)
                storage.shape == XmpShape.LangAlt -> XmpChange.SetLocalizedText(ns, prefix, name, value)
                storage.shape == XmpShape.Bag -> XmpChange.SetArray(ns, prefix, name, items(value, kind), XmpArrayKind.Bag)
                storage.shape == XmpShape.Seq -> XmpChange.SetArray(ns, prefix, name, items(value, kind), XmpArrayKind.Seq)
                else -> XmpChange.SetProperty(ns, prefix, name, value)
            }
            MetadataChanges(xmp = listOf(change))
        }
        is Storage.Iptc -> {
            val values = value?.let { items(it, kind) }
            val max = IptcTags.MAX_BYTES[storage.dataset]
            val fits = values != null && (max == null || values.all { it.toByteArray(Charsets.UTF_8).size <= max })
            val change = if (fits) IptcChange.Set(IptcTags.RECORD, storage.dataset, values!!) else IptcChange.Remove(IptcTags.RECORD, storage.dataset)
            MetadataChanges(iptc = listOf(change))
        }
    }

    private fun items(value: String, kind: FieldKind): List<String> = if (kind == FieldKind.TextList) FieldKind.TextList.split(value) else listOf(value)

    // ---- Definitions ----

    private enum class ExifEncoding { Ascii, Short, Rational, SignedRational, Date, Comment, Offset }

    private enum class AuthorRole { Artist, Copyright }

    private sealed interface Storage {
        data class Exif(val ifd: ExifIfd, val tag: Int, val encoding: ExifEncoding, val style: NumberStyle = NumberStyle.Decimal) : Storage
        data class Xmp(val name: XmpName, val shape: XmpShape) : Storage
        data class Iptc(val dataset: Int) : Storage
    }

    private class CatalogEntry(
        val field: EditableField,
        val storage: Storage,
        val syncs: List<Storage> = emptyList(),
        val author: AuthorRole? = null,
    )

    private companion object {
        fun ExifIfd.reportId(): String = when (this) {
            ExifIfd.Primary -> "exif-ifd0"
            ExifIfd.Exif -> "exif-subifd"
            ExifIfd.Gps -> "gps"
            ExifIfd.Interoperability -> "exif-interop"
            ExifIfd.Thumbnail -> "exif-thumbnail"
        }

        fun ExifIfd.idPart(): String = when (this) {
            ExifIfd.Primary -> "ifd0"
            ExifIfd.Exif -> "subifd"
            ExifIfd.Gps -> "gps"
            ExifIfd.Interoperability -> "interop"
            ExifIfd.Thumbnail -> "ifd1"
        }

        fun exif(
            ifd: ExifIfd,
            tag: Int,
            kind: FieldKind,
            encoding: ExifEncoding,
            style: NumberStyle = NumberStyle.Decimal,
            sensitivity: SensitivityCategory? = null,
            common: Boolean = false,
            syncs: List<Storage> = emptyList(),
            author: AuthorRole? = null,
        ): CatalogEntry {
            val id = "exif:${ifd.idPart()}:0x%04X".format(tag)
            val group = if (ifd == ExifIfd.Gps) DirectoryGroup.Gps else DirectoryGroup.Exif
            val field = EditableField(id, ExifText.tagName(ifd, tag), group, kind, sensitivity, common)
            return CatalogEntry(field, Storage.Exif(ifd, tag, encoding, style), syncs, author)
        }

        fun xmp(
            namespace: String,
            name: String,
            kind: FieldKind,
            shape: XmpShape,
            sensitivity: SensitivityCategory? = null,
            common: Boolean = false,
            syncs: List<Storage> = emptyList(),
            author: AuthorRole? = null,
        ): CatalogEntry {
            val xmpName = XmpName(namespace, name)
            val label = "${xmpName.preferredPrefix}:$name"
            return CatalogEntry(EditableField("xmp:$label", label, DirectoryGroup.Xmp, kind, sensitivity, common), Storage.Xmp(xmpName, shape), syncs, author)
        }

        fun iptc(
            dataset: Int,
            kind: FieldKind,
            sensitivity: SensitivityCategory? = null,
            syncs: List<Storage> = emptyList(),
            author: AuthorRole? = null,
        ): CatalogEntry {
            val field = EditableField("iptc:2:$dataset", IptcText.tagName(IptcTags.RECORD, dataset), DirectoryGroup.Iptc, kind, sensitivity)
            return CatalogEntry(field, Storage.Iptc(dataset), syncs, author)
        }

        fun ascii(maxLength: Int? = null, multiline: Boolean = false) = FieldKind.Text(maxLength = maxLength, asciiOnly = true, multiline = multiline)
        fun text(maxLength: Int? = null, multiline: Boolean = false) = FieldKind.Text(maxLength = maxLength, multiline = multiline)
        fun iptcText(dataset: Int, multiline: Boolean = false) = FieldKind.Text(maxLength = IptcTags.MAX_BYTES.getValue(dataset), multiline = multiline)
        fun choice(vararg options: Pair<Int, String>) = FieldKind.Choice(options.map { FieldOption(it.first.toString(), it.second) })

        val ORIENTATION = choice(
            1 to "Normal",
            2 to "Mirror horizontal",
            3 to "Rotate 180",
            4 to "Mirror vertical",
            5 to "Mirror horizontal and rotate 270 CW",
            6 to "Rotate 90 CW",
            7 to "Mirror horizontal and rotate 90 CW",
            8 to "Rotate 270 CW",
        )
        val EXPOSURE_PROGRAM = choice(
            0 to "Not defined", 1 to "Manual", 2 to "Program AE", 3 to "Aperture-priority AE", 4 to "Shutter speed priority AE",
            5 to "Creative (slow speed)", 6 to "Action (high speed)", 7 to "Portrait", 8 to "Landscape",
        )
        val METERING_MODE = choice(
            0 to "Unknown", 1 to "Average", 2 to "Center-weighted average", 3 to "Spot", 4 to "Multi-spot",
            5 to "Multi-segment", 6 to "Partial", 255 to "Other",
        )
        val FLASH = choice(
            0x00 to "No flash",
            0x01 to "Fired",
            0x05 to "Fired, return not detected",
            0x07 to "Fired, return detected",
            0x08 to "On, did not fire",
            0x09 to "On, fired",
            0x0D to "On, return not detected",
            0x0F to "On, return detected",
            0x10 to "Off, did not fire",
            0x18 to "Auto, did not fire",
            0x19 to "Auto, fired",
            0x1D to "Auto, fired, return not detected",
            0x1F to "Auto, fired, return detected",
            0x20 to "No flash function",
            0x41 to "Fired, red-eye reduction",
            0x49 to "On, red-eye reduction",
            0x50 to "Off, did not fire, red-eye reduction",
            0x58 to "Auto, did not fire, red-eye reduction",
            0x59 to "Auto, fired, red-eye reduction",
        )
        val WHITE_BALANCE = choice(0 to "Auto", 1 to "Manual")

        /** UTC offsets in use by real time zones. */
        val OFFSETS = FieldKind.Choice(
            listOf(
                "-12:00", "-11:00", "-10:00", "-09:30", "-09:00", "-08:00", "-07:00", "-06:00", "-05:00", "-04:00", "-03:30",
                "-03:00", "-02:00", "-01:00", "+00:00", "+01:00", "+02:00", "+03:00", "+03:30", "+04:00", "+04:30", "+05:00",
                "+05:30", "+05:45", "+06:00", "+06:30", "+07:00", "+08:00", "+08:45", "+09:00", "+09:30", "+10:00", "+10:30",
                "+11:00", "+12:00", "+12:45", "+13:00", "+14:00",
            ).map { FieldOption(it, "UTC$it") },
        )

        private val XMP_TITLE = Storage.Xmp(XmpName(XmpNs.DC, "title"), XmpShape.LangAlt)
        private val XMP_DESCRIPTION = Storage.Xmp(XmpName(XmpNs.DC, "description"), XmpShape.LangAlt)
        private val XMP_SUBJECT = Storage.Xmp(XmpName(XmpNs.DC, "subject"), XmpShape.Bag)
        private val XMP_CITY = Storage.Xmp(XmpName(XmpNs.PHOTOSHOP, "City"), XmpShape.Simple)
        private val XMP_COUNTRY = Storage.Xmp(XmpName(XmpNs.PHOTOSHOP, "Country"), XmpShape.Simple)
        private val EXIF_DESCRIPTION = Storage.Exif(ExifIfd.Primary, ExifTags.IMAGE_DESCRIPTION, ExifEncoding.Ascii)

        val ENTRIES: List<CatalogEntry> = listOf(
            // EXIF IFD0
            exif(ExifIfd.Primary, ExifTags.MAKE, ascii(), ExifEncoding.Ascii),
            exif(ExifIfd.Primary, ExifTags.MODEL, ascii(), ExifEncoding.Ascii),
            exif(ExifIfd.Primary, ExifTags.SOFTWARE, ascii(), ExifEncoding.Ascii),
            exif(ExifIfd.Primary, ExifTags.ARTIST, text(), ExifEncoding.Ascii, sensitivity = SensitivityCategory.PersonName, common = true, author = AuthorRole.Artist),
            exif(ExifIfd.Primary, ExifTags.COPYRIGHT, text(), ExifEncoding.Ascii, sensitivity = SensitivityCategory.PersonName, common = true, author = AuthorRole.Copyright),
            exif(
                ExifIfd.Primary, ExifTags.IMAGE_DESCRIPTION, ascii(multiline = true), ExifEncoding.Ascii,
                syncs = listOf(XMP_DESCRIPTION, Storage.Iptc(IptcTags.CAPTION)),
            ),
            exif(ExifIfd.Primary, ExifTags.ORIENTATION, ORIENTATION, ExifEncoding.Short, common = true),
            exif(ExifIfd.Primary, ExifTags.DATE_TIME, FieldKind.DateTime, ExifEncoding.Date),
            // Exif IFD
            exif(ExifIfd.Exif, ExifTags.DATE_TIME_ORIGINAL, FieldKind.DateTime, ExifEncoding.Date, common = true),
            exif(ExifIfd.Exif, ExifTags.DATE_TIME_DIGITIZED, FieldKind.DateTime, ExifEncoding.Date),
            exif(ExifIfd.Exif, ExifTags.OFFSET_TIME, OFFSETS, ExifEncoding.Offset),
            exif(ExifIfd.Exif, ExifTags.OFFSET_TIME_ORIGINAL, OFFSETS, ExifEncoding.Offset),
            exif(ExifIfd.Exif, ExifTags.OFFSET_TIME_DIGITIZED, OFFSETS, ExifEncoding.Offset),
            exif(ExifIfd.Exif, ExifTags.EXPOSURE_TIME, FieldKind.Number(min = 0.000001, max = 86_400.0), ExifEncoding.Rational, NumberStyle.ExposureTime),
            exif(ExifIfd.Exif, ExifTags.F_NUMBER, FieldKind.Number(min = 0.5, max = 256.0), ExifEncoding.Rational),
            exif(ExifIfd.Exif, ExifTags.ISO, FieldKind.Integer(1, 65_535), ExifEncoding.Short),
            exif(ExifIfd.Exif, ExifTags.EXPOSURE_PROGRAM, EXPOSURE_PROGRAM, ExifEncoding.Short),
            exif(ExifIfd.Exif, ExifTags.EXPOSURE_BIAS, FieldKind.Number(min = -99.0, max = 99.0, signed = true), ExifEncoding.SignedRational),
            exif(ExifIfd.Exif, ExifTags.METERING_MODE, METERING_MODE, ExifEncoding.Short),
            exif(ExifIfd.Exif, ExifTags.FLASH, FLASH, ExifEncoding.Short),
            exif(ExifIfd.Exif, ExifTags.FOCAL_LENGTH, FieldKind.Number(min = 0.01, max = 100_000.0), ExifEncoding.Rational),
            exif(ExifIfd.Exif, ExifTags.FOCAL_LENGTH_35MM, FieldKind.Integer(1, 65_535), ExifEncoding.Short),
            exif(ExifIfd.Exif, ExifTags.WHITE_BALANCE, WHITE_BALANCE, ExifEncoding.Short),
            exif(ExifIfd.Exif, ExifTags.LENS_MAKE, ascii(), ExifEncoding.Ascii),
            exif(ExifIfd.Exif, ExifTags.LENS_MODEL, ascii(), ExifEncoding.Ascii),
            exif(ExifIfd.Exif, ExifTags.BODY_SERIAL_NUMBER, ascii(), ExifEncoding.Ascii, sensitivity = SensitivityCategory.SerialNumber),
            exif(ExifIfd.Exif, ExifTags.LENS_SERIAL_NUMBER, ascii(), ExifEncoding.Ascii, sensitivity = SensitivityCategory.SerialNumber),
            exif(ExifIfd.Exif, ExifTags.CAMERA_OWNER_NAME, ascii(), ExifEncoding.Ascii, sensitivity = SensitivityCategory.PersonName),
            exif(ExifIfd.Exif, ExifTags.USER_COMMENT, text(multiline = true), ExifEncoding.Comment),
            exif(ExifIfd.Exif, ExifTags.IMAGE_UNIQUE_ID, ascii(maxLength = 32), ExifEncoding.Ascii, sensitivity = SensitivityCategory.DeviceIdentifier),
            // XMP
            xmp(XmpNs.DC, "title", text(), XmpShape.LangAlt, common = true, syncs = listOf(Storage.Iptc(IptcTags.OBJECT_NAME))),
            xmp(
                XmpNs.DC, "description", text(multiline = true), XmpShape.LangAlt, common = true,
                syncs = listOf(Storage.Iptc(IptcTags.CAPTION), EXIF_DESCRIPTION),
            ),
            xmp(XmpNs.DC, "creator", text(), XmpShape.Seq, SensitivityCategory.PersonName, author = AuthorRole.Artist),
            xmp(XmpNs.DC, "rights", text(), XmpShape.LangAlt, SensitivityCategory.PersonName, author = AuthorRole.Copyright),
            xmp(XmpNs.DC, "subject", FieldKind.TextList, XmpShape.Bag, common = true, syncs = listOf(Storage.Iptc(IptcTags.KEYWORDS))),
            xmp(XmpNs.XMP, "Rating", FieldKind.Integer(0, 5), XmpShape.Simple, common = true),
            xmp(XmpNs.PHOTOSHOP, "Headline", text(), XmpShape.Simple, syncs = listOf(Storage.Iptc(IptcTags.HEADLINE))),
            xmp(XmpNs.PHOTOSHOP, "City", text(), XmpShape.Simple, SensitivityCategory.Location, syncs = listOf(Storage.Iptc(IptcTags.CITY))),
            xmp(XmpNs.PHOTOSHOP, "State", text(), XmpShape.Simple, SensitivityCategory.Location, syncs = listOf(Storage.Iptc(IptcTags.PROVINCE_STATE))),
            xmp(XmpNs.PHOTOSHOP, "Country", text(), XmpShape.Simple, SensitivityCategory.Location, syncs = listOf(Storage.Iptc(IptcTags.COUNTRY_NAME))),
            // IPTC
            iptc(IptcTags.OBJECT_NAME, iptcText(IptcTags.OBJECT_NAME), syncs = listOf(XMP_TITLE)),
            iptc(IptcTags.CAPTION, iptcText(IptcTags.CAPTION, multiline = true), syncs = listOf(XMP_DESCRIPTION, EXIF_DESCRIPTION)),
            iptc(IptcTags.KEYWORDS, FieldKind.TextList, syncs = listOf(XMP_SUBJECT)),
            iptc(IptcTags.BY_LINE, iptcText(IptcTags.BY_LINE), SensitivityCategory.PersonName, author = AuthorRole.Artist),
            iptc(IptcTags.COPYRIGHT_NOTICE, iptcText(IptcTags.COPYRIGHT_NOTICE), SensitivityCategory.PersonName, author = AuthorRole.Copyright),
            iptc(IptcTags.CITY, iptcText(IptcTags.CITY), SensitivityCategory.Location, syncs = listOf(XMP_CITY)),
            iptc(IptcTags.COUNTRY_NAME, iptcText(IptcTags.COUNTRY_NAME), SensitivityCategory.Location, syncs = listOf(XMP_COUNTRY)),
        )
    }
}

/** Ids of the fields in [DefaultTagCatalog] that the editor features directly. */
object FieldIds {
    const val TITLE = "xmp:dc:title"
    const val DESCRIPTION = "xmp:dc:description"
    const val KEYWORDS = "xmp:dc:subject"
    const val RATING = "xmp:xmp:Rating"
    const val ARTIST = "exif:ifd0:0x013B"
    const val COPYRIGHT = "exif:ifd0:0x8298"
    const val ORIENTATION = "exif:ifd0:0x0112"
    const val DATE_TAKEN = "exif:subifd:0x9003"
}
