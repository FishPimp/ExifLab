package io.github.fishpimp.exiflab.metadata.edit

import io.github.fishpimp.exiflab.metadata.model.DirectoryGroup
import io.github.fishpimp.exiflab.metadata.model.MetadataReport
import io.github.fishpimp.exiflab.metadata.model.SensitivityCategory

/** One choice of a [FieldKind.Choice] field, e.g. Orientation 6 = "Rotate 90 CW". */
data class FieldOption(val value: String, val label: String)

/** How a field's value is entered and validated. */
sealed interface FieldKind {
    /** Free text. [asciiOnly] for EXIF ASCII tags; [multiline] for descriptions. */
    data class Text(val maxLength: Int? = null, val asciiOnly: Boolean = false, val multiline: Boolean = false) : FieldKind

    /** Whole number within [min]..[max]. */
    data class Integer(val min: Long, val max: Long) : FieldKind

    /** Decimal or fraction input ("1/250", "2.8"), stored as a (signed) rational. */
    data class Number(val min: Double? = null, val max: Double? = null, val signed: Boolean = false) : FieldKind

    /** Local date and time ("2026-06-14 17:42:07"). */
    data object DateTime : FieldKind

    /** One of [options]. */
    data class Choice(val options: List<FieldOption>) : FieldKind

    /** A list of short strings, e.g. keywords. */
    data object TextList : FieldKind
}

/** A field the tag editor can set or remove. */
data class EditableField(
    /** Stable id, e.g. "exif:ifd0:0x013B", "xmp:dc:title", "iptc:2:25". */
    val id: String,
    /** English label, shown like tag names in the viewer. */
    val label: String,
    val group: DirectoryGroup,
    val kind: FieldKind,
    val sensitivity: SensitivityCategory? = null,
    /** Common fields shown first in the editor (title, description, artist, copyright, keywords...). */
    val common: Boolean = false,
)

/** Why an input was rejected. The UI maps these to localized messages. */
enum class ValidationError { Empty, NotANumber, OutOfRange, TooLong, NotAscii, InvalidDate, InvalidChoice }

sealed interface Validation {
    /** [normalized] is the value as it will be written, e.g. "1/250" for "0.004". */
    data class Valid(val normalized: String) : Validation

    data class Invalid(val error: ValidationError) : Validation
}

/** The editable fields and how values map to operations. Pure and stateless. */
interface TagCatalog {
    val fields: List<EditableField>

    fun field(id: String): EditableField?

    /** The field's current value in [report] as editable text, or null when absent. */
    fun currentValue(field: EditableField, report: MetadataReport): String?

    fun validate(field: EditableField, input: String): Validation

    /** The operation that sets [field] to the validated [input], or removes it when [input] is null. */
    fun operationFor(field: EditableField, input: String?): EditOperation
}

/** Entry point for the app; implementations live in this module. */
object Editing {
    val planner: ChangePlanner get() = TODO("Implemented in M4 planner")
    val catalog: TagCatalog get() = TODO("Implemented in M4 planner")
}
