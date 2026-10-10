package io.github.fishpimp.exiflab.screenshots

import io.github.fishpimp.exiflab.data.history.EditMode
import io.github.fishpimp.exiflab.data.photos.PhotoRef
import io.github.fishpimp.exiflab.data.save.SaveOutcome
import io.github.fishpimp.exiflab.data.save.SaveTarget
import io.github.fishpimp.exiflab.metadata.ImageFormat
import io.github.fishpimp.exiflab.metadata.edit.ChangePlanner
import io.github.fishpimp.exiflab.metadata.edit.EditOperation
import io.github.fishpimp.exiflab.metadata.edit.EditPlan
import io.github.fishpimp.exiflab.metadata.edit.EditableField
import io.github.fishpimp.exiflab.metadata.edit.FieldDiff
import io.github.fishpimp.exiflab.metadata.edit.FieldKind
import io.github.fishpimp.exiflab.metadata.edit.FieldOption
import io.github.fishpimp.exiflab.metadata.edit.TagCatalog
import io.github.fishpimp.exiflab.metadata.edit.Validation
import io.github.fishpimp.exiflab.metadata.edit.ValidationError
import io.github.fishpimp.exiflab.metadata.model.DirectoryGroup
import io.github.fishpimp.exiflab.metadata.model.MetadataReport
import io.github.fishpimp.exiflab.metadata.model.SensitivityCategory
import io.github.fishpimp.exiflab.metadata.write.ExifIfd
import io.github.fishpimp.exiflab.metadata.write.ExifValue
import io.github.fishpimp.exiflab.metadata.write.MetadataChanges
import io.github.fishpimp.exiflab.metadata.write.XmpChange
import io.github.fishpimp.exiflab.ui.edit.EditSaving
import java.time.format.DateTimeFormatter
import java.util.Locale

/**
 * A small tag catalog shaped like the real one: common fields (title, description, artist,
 * copyright, keywords, rating) and typed EXIF, XMP and IPTC fields. Values are read from the
 * fake reports by tag id or XMP name.
 */
internal class FakeCatalog : TagCatalog {
    private class Source(val directoryId: String, val tagId: Int? = null, val name: String? = null)

    private val sources = mutableMapOf<String, Source>()

    private fun field(
        id: String,
        label: String,
        group: DirectoryGroup,
        kind: FieldKind,
        source: Source?,
        sensitivity: SensitivityCategory? = null,
        common: Boolean = false,
    ): EditableField {
        source?.let { sources[id] = it }
        return EditableField(id, label, group, kind, sensitivity, common)
    }

    override val fields: List<EditableField> = listOf(
        field("xmp:dc:title", "Title", DirectoryGroup.Xmp, FieldKind.Text(maxLength = 200), Source("xmp", name = "dc:title"), common = true),
        field("exif:ifd0:0x010E", "Image Description", DirectoryGroup.Exif, FieldKind.Text(multiline = true), Source("exif-ifd0", 0x010E), common = true),
        field(
            "exif:ifd0:0x013B", "Artist", DirectoryGroup.Exif, FieldKind.Text(maxLength = 64, asciiOnly = true),
            Source("exif-ifd0", 0x013B), SensitivityCategory.PersonName, common = true,
        ),
        field(
            "exif:ifd0:0x8298", "Copyright", DirectoryGroup.Exif, FieldKind.Text(asciiOnly = true),
            Source("exif-ifd0", 0x8298), SensitivityCategory.PersonName, common = true,
        ),
        field("xmp:dc:subject", "Keywords", DirectoryGroup.Xmp, FieldKind.TextList, Source("xmp", name = "dc:subject"), common = true),
        field("xmp:xmp:Rating", "Rating", DirectoryGroup.Xmp, FieldKind.Integer(0, 5), Source("xmp", name = "xmp:Rating"), common = true),
        field(
            "exif:ifd0:0x0112", "Orientation", DirectoryGroup.Exif,
            FieldKind.Choice(
                listOf(
                    FieldOption("1", "Horizontal (normal)"),
                    FieldOption("3", "Rotate 180"),
                    FieldOption("6", "Rotate 90 CW"),
                    FieldOption("8", "Rotate 270 CW"),
                ),
            ),
            Source("exif-ifd0", 0x0112),
        ),
        field("exif:ifd0:0x010F", "Make", DirectoryGroup.Exif, FieldKind.Text(asciiOnly = true), Source("exif-ifd0", 0x010F)),
        field("exif:ifd0:0x0110", "Model", DirectoryGroup.Exif, FieldKind.Text(asciiOnly = true), Source("exif-ifd0", 0x0110)),
        field("exif:ifd0:0x0131", "Software", DirectoryGroup.Exif, FieldKind.Text(asciiOnly = true), Source("exif-ifd0", 0x0131)),
        field("exif:exif:0x829D", "F-Number", DirectoryGroup.Exif, FieldKind.Number(min = 0.5, max = 64.0), Source("exif-subifd", 0x829D)),
        field("exif:exif:0x829A", "Exposure Time", DirectoryGroup.Exif, FieldKind.Number(min = 0.0, max = 3600.0), Source("exif-subifd", 0x829A)),
        field("exif:exif:0x8827", "ISO Speed", DirectoryGroup.Exif, FieldKind.Integer(1, 409_600), Source("exif-subifd", 0x8827)),
        field("exif:exif:0x9003", "Date/Time Original", DirectoryGroup.Exif, FieldKind.DateTime, Source("exif-subifd", 0x9003)),
        field("exif:exif:0xA434", "Lens Model", DirectoryGroup.Exif, FieldKind.Text(asciiOnly = true), Source("exif-subifd", 0xA434)),
        field(
            "exif:exif:0xA431", "Body Serial Number", DirectoryGroup.Exif, FieldKind.Text(asciiOnly = true),
            Source("exif-subifd", 0xA431), SensitivityCategory.SerialNumber,
        ),
        field("exif:exif:0x9286", "User Comment", DirectoryGroup.Exif, FieldKind.Text(multiline = true), Source("exif-subifd", 0x9286)),
        field("xmp:photoshop:Headline", "Headline", DirectoryGroup.Xmp, FieldKind.Text(), Source("xmp", name = "photoshop:Headline")),
        field("iptc:2:90", "City", DirectoryGroup.Iptc, FieldKind.Text(maxLength = 32), Source("iptc", 90), SensitivityCategory.Location),
        field("iptc:2:101", "Country", DirectoryGroup.Iptc, FieldKind.Text(maxLength = 64), Source("iptc", 101), SensitivityCategory.Location),
    )

    override fun field(id: String): EditableField? = fields.firstOrNull { it.id == id }

    override fun currentValue(field: EditableField, report: MetadataReport): String? {
        val source = sources[field.id] ?: return null
        val directory = report.directories.firstOrNull { it.id == source.directoryId } ?: return null
        val tag = directory.tags.firstOrNull { tag ->
            (source.tagId != null && tag.id == source.tagId) || (source.name != null && (tag.name == source.name || tag.name.startsWith(source.name + "[")))
        } ?: return null
        return when (field.kind) {
            FieldKind.DateTime -> tag.rawValue.replaceFirst(':', '-').replaceFirst(':', '-')
            is FieldKind.Choice -> tag.rawValue
            else -> tag.rawValue.ifEmpty { tag.displayValue }
        }
    }

    override fun validate(field: EditableField, input: String): Validation {
        val text = input.trim()
        if (text.isEmpty()) return Validation.Invalid(ValidationError.Empty)
        return when (val kind = field.kind) {
            is FieldKind.Text -> when {
                kind.maxLength != null && text.length > kind.maxLength!! -> Validation.Invalid(ValidationError.TooLong)
                kind.asciiOnly && text.any { it.code > 0x7E || it.code < 0x20 } -> Validation.Invalid(ValidationError.NotAscii)
                else -> Validation.Valid(text)
            }
            is FieldKind.Integer -> {
                val value = text.toLongOrNull() ?: return Validation.Invalid(ValidationError.NotANumber)
                if (value !in kind.min..kind.max) Validation.Invalid(ValidationError.OutOfRange) else Validation.Valid(value.toString())
            }
            is FieldKind.Number -> {
                val value = parseNumber(text) ?: return Validation.Invalid(ValidationError.NotANumber)
                val min = kind.min
                val max = kind.max
                if ((min != null && value < min) || (max != null && value > max)) Validation.Invalid(ValidationError.OutOfRange) else Validation.Valid(text)
            }
            FieldKind.DateTime -> runCatching { java.time.LocalDateTime.parse(text, DATE_TIME) }
                .fold({ Validation.Valid(DATE_TIME.format(it)) }, { Validation.Invalid(ValidationError.InvalidDate) })
            is FieldKind.Choice -> if (kind.options.any { it.value == text }) Validation.Valid(text) else Validation.Invalid(ValidationError.InvalidChoice)
            FieldKind.TextList -> Validation.Valid(text.split(',').map { it.trim() }.filter { it.isNotEmpty() }.joinToString(", "))
        }
    }

    override fun operationFor(field: EditableField, input: String?): EditOperation {
        val exif = Regex("""exif:(ifd0|exif):0x([0-9A-F]{4})""").matchEntire(field.id)
        if (exif != null) {
            val ifd = if (exif.groupValues[1] == "ifd0") ExifIfd.Primary else ExifIfd.Exif
            val tag = exif.groupValues[2].toInt(16)
            return if (input == null) EditOperation.RemoveExifTag(ifd, tag) else EditOperation.SetExifTag(ifd, tag, ExifValue.Ascii(input))
        }
        val changes = MetadataChanges(
            xmp = listOf(if (input == null) XmpChange.Remove(NS, field.id) else XmpChange.SetProperty(NS, "x", field.id, input)),
        )
        return EditOperation.Raw(changes, if (input == null) "Remove ${field.label}" else "${field.label}: $input")
    }

    private fun parseNumber(text: String): Double? {
        val parts = text.split('/')
        return when (parts.size) {
            1 -> text.toDoubleOrNull()
            2 -> {
                val n = parts[0].trim().toDoubleOrNull()
                val d = parts[1].trim().toDoubleOrNull()
                if (n == null || d == null || d == 0.0) null else n / d
            }
            else -> null
        }
    }

    companion object {
        private const val NS = "http://example.com/fake/"
        val DATE_TIME: DateTimeFormatter = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss", Locale.ROOT)
    }
}

/**
 * Plans operations into readable diffs, the way the real planner reports them, with a
 * non-empty change set per operation so plans can be saved.
 */
internal class FakePlanner(
    private val catalog: TagCatalog = FakeCatalog(),
    private val warnings: List<String> = emptyList(),
) : ChangePlanner {
    val calls = mutableListOf<List<EditOperation>>()

    override fun plan(report: MetadataReport, operations: List<EditOperation>): EditPlan {
        calls += operations
        val diff = operations.flatMap { diffFor(report, it) }
        val changes = MetadataChanges(xmp = operations.mapIndexed { index, op -> XmpChange.SetProperty(NS, "x", "op$index", op.toString()) })
        return EditPlan(changes, diff, warnings)
    }

    private fun diffFor(report: MetadataReport, op: EditOperation): List<FieldDiff> {
        val time = report.summary.capturedAt?.let { EXIF_TIME.format(it) }
        val offset = report.summary.utcOffset?.id
        return when (op) {
            is EditOperation.SetCaptureTime -> listOf(
                FieldDiff("Date/Time Original", DirectoryGroup.Exif, time, EXIF_TIME.format(op.local)),
                FieldDiff("Create Date", DirectoryGroup.Exif, time, EXIF_TIME.format(op.local)),
            )
            is EditOperation.ShiftCaptureTime -> {
                val after = report.summary.capturedAt?.plus(op.by)?.let { EXIF_TIME.format(it) }
                listOf(
                    FieldDiff("Date/Time Original", DirectoryGroup.Exif, time, after),
                    FieldDiff("Create Date", DirectoryGroup.Exif, time, after),
                    FieldDiff("xmp:CreateDate", DirectoryGroup.Xmp, time, after),
                )
            }
            is EditOperation.ChangeTimeZone -> {
                val corrected = report.summary.capturedAt?.atZone(op.cameraZone)?.withZoneSameInstant(op.actualZone)
                listOf(
                    FieldDiff("Date/Time Original", DirectoryGroup.Exif, time, corrected?.let { EXIF_TIME.format(it) }),
                    FieldDiff("Offset Time Original", DirectoryGroup.Exif, offset, corrected?.offset?.id),
                )
            }
            is EditOperation.SetUtcOffset -> listOf(FieldDiff("Offset Time Original", DirectoryGroup.Exif, offset, op.offset.id))
            is EditOperation.SetLocation -> {
                val before = report.location
                listOfNotNull(
                    FieldDiff("GPS Latitude", DirectoryGroup.Gps, before?.latitude?.let(::degrees), degrees(op.latitude)),
                    FieldDiff("GPS Longitude", DirectoryGroup.Gps, before?.longitude?.let(::degrees), degrees(op.longitude)),
                    op.altitudeMeters?.let { FieldDiff("GPS Altitude", DirectoryGroup.Gps, before?.altitudeMeters?.let { "$it m" }, "$it m") },
                )
            }
            is EditOperation.RemoveLocation -> {
                val before = report.location
                listOfNotNull(
                    FieldDiff("GPS Latitude", DirectoryGroup.Gps, before?.latitude?.let(::degrees) ?: "0.0", null),
                    FieldDiff("GPS Longitude", DirectoryGroup.Gps, before?.longitude?.let(::degrees) ?: "0.0", null),
                    before?.altitudeMeters?.let { FieldDiff("GPS Altitude", DirectoryGroup.Gps, "$it m", null) },
                    if (op.includePlaceNames) FieldDiff("City", DirectoryGroup.Iptc, "Stockholm", null) else null,
                )
            }
            is EditOperation.SetAuthorship -> listOfNotNull(
                op.artist?.let { FieldDiff("Artist", DirectoryGroup.Exif, null, it) },
                op.artist?.let { FieldDiff("dc:creator", DirectoryGroup.Xmp, null, it) },
                op.copyright?.let { FieldDiff("Copyright", DirectoryGroup.Exif, null, it) },
                op.copyright?.let { FieldDiff("dc:rights", DirectoryGroup.Xmp, null, it) },
            )
            is EditOperation.SetExifTag -> {
                val field = catalog.fields.firstOrNull { it.id.endsWith("0x%04X".format(op.tag)) }
                val label = field?.label ?: "Tag 0x%04X".format(op.tag)
                listOf(FieldDiff(label, DirectoryGroup.Exif, field?.let { catalog.currentValue(it, report) }, (op.value as? ExifValue.Ascii)?.text))
            }
            is EditOperation.RemoveExifTag -> {
                val field = catalog.fields.firstOrNull { it.id.endsWith("0x%04X".format(op.tag)) }
                listOf(FieldDiff(field?.label ?: "Tag", DirectoryGroup.Exif, field?.let { catalog.currentValue(it, report) } ?: "?", null))
            }
            is EditOperation.Raw -> listOf(FieldDiff(op.description.substringBefore(':'), DirectoryGroup.Xmp, null, op.description.substringAfter(": ", "")))
            is EditOperation.Strip -> emptyList()
        }
    }

    private fun degrees(value: Double) = String.format(Locale.ROOT, "%.6f", value)

    companion object {
        private const val NS = "http://example.com/plan/"
        private val EXIF_TIME: DateTimeFormatter = DateTimeFormatter.ofPattern("yyyy:MM:dd HH:mm:ss", Locale.ROOT)
    }
}

/** Records saves and answers with [outcome]; formats in [writable] count as writable. */
internal class FakeSaving(
    private val writable: Set<ImageFormat> = setOf(ImageFormat.Jpeg, ImageFormat.Png, ImageFormat.WebP),
) : EditSaving {
    val saves = mutableListOf<SaveTarget>()
    val undone = mutableListOf<String>()
    var outcome: (PhotoRef, SaveTarget) -> SaveOutcome = { ref, target ->
        when (target) {
            SaveTarget.InPlace -> SaveOutcome.Saved("record-${saves.size}", EditMode.InPlace, ref.uri)
            is SaveTarget.Copy -> SaveOutcome.Saved("record-${saves.size}", EditMode.Copy, target.destinationUri)
            is SaveTarget.Sidecar -> SaveOutcome.Saved("record-${saves.size}", EditMode.Sidecar, target.destinationUri ?: "${ref.uri}.xmp")
        }
    }
    var undoOutcome: SaveOutcome = SaveOutcome.Saved("restore-1", EditMode.InPlace, "content://restored")

    override fun canWrite(format: ImageFormat): Boolean = format in writable

    override suspend fun save(ref: PhotoRef, plan: EditPlan, target: SaveTarget): SaveOutcome {
        saves += target
        return outcome(ref, target)
    }

    override suspend fun undo(recordId: String): SaveOutcome {
        undone += recordId
        return undoOutcome
    }
}
