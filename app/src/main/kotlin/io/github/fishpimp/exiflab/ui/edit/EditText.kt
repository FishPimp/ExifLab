package io.github.fishpimp.exiflab.ui.edit

import androidx.annotation.StringRes
import androidx.compose.runtime.Composable
import androidx.compose.ui.platform.LocalLocale
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import io.github.fishpimp.exiflab.R
import io.github.fishpimp.exiflab.metadata.ImageFormat
import io.github.fishpimp.exiflab.metadata.edit.AuthorField
import io.github.fishpimp.exiflab.metadata.edit.DateAction
import io.github.fishpimp.exiflab.metadata.edit.EditOperation
import io.github.fishpimp.exiflab.metadata.edit.EditableField
import io.github.fishpimp.exiflab.metadata.edit.FieldKind
import io.github.fishpimp.exiflab.metadata.edit.PlanWarning
import io.github.fishpimp.exiflab.metadata.edit.ValidationError
import io.github.fishpimp.exiflab.metadata.write.WriteSkip
import io.github.fishpimp.exiflab.ui.edit.time.hoursPart
import io.github.fishpimp.exiflab.ui.edit.time.minutesPart
import io.github.fishpimp.exiflab.ui.edit.time.secondsPart
import io.github.fishpimp.exiflab.ui.format.CaptureTimeFormat
import io.github.fishpimp.exiflab.ui.format.ExposureFormat
import io.github.fishpimp.exiflab.ui.map.LatLng
import io.github.fishpimp.exiflab.ui.map.formatted
import java.text.NumberFormat
import java.time.Duration
import java.time.LocalDateTime
import java.time.ZoneId
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle
import java.util.Locale

/** The short, localized text of a queued edit, as shown on its chip. */
@Composable
fun editSummary(edit: PendingEdit): String {
    val locale = LocalLocale.current.platformLocale
    return when (val op = edit.operation) {
        is EditOperation.SetCaptureTime -> stringResource(R.string.edit_summary_set_time, dateTimeText(op.local, op.offset, locale))
        is EditOperation.ShiftCaptureTime -> stringResource(R.string.edit_summary_shift_time, durationText(op.by))
        is EditOperation.ChangeTimeZone -> stringResource(R.string.edit_summary_time_zone, zoneName(op.cameraZone), zoneName(op.actualZone))
        is EditOperation.SetUtcOffset -> stringResource(R.string.edit_summary_offset, utcText(op.offset))
        is EditOperation.SetLocation -> stringResource(R.string.edit_summary_location, LatLng(op.latitude, op.longitude).formatted())
        is EditOperation.RemoveLocation -> stringResource(
            if (op.includePlaceNames) R.string.edit_summary_remove_location_places else R.string.edit_summary_remove_location,
        )
        is EditOperation.SetAuthorship -> when {
            op.artist == null && op.copyright == null -> stringResource(R.string.edit_summary_author_cleared)
            op.artist != null -> stringResource(R.string.edit_summary_author, op.artist!!)
            else -> stringResource(R.string.edit_summary_copyright, op.copyright!!)
        }
        else -> fieldSummary(edit)
    }
}

@Composable
private fun fieldSummary(edit: PendingEdit): String {
    val label = edit.fieldLabel
    val op = edit.operation
    return when {
        label != null && edit.fieldValue != null -> stringResource(R.string.edit_summary_field, label, edit.fieldValue.take(SUMMARY_VALUE_LENGTH))
        label != null -> stringResource(R.string.edit_summary_field_removed, label)
        op is EditOperation.Raw -> op.description
        op is EditOperation.SetExifTag -> stringResource(R.string.edit_summary_tag, "0x%04X".format(op.tag))
        op is EditOperation.RemoveExifTag -> stringResource(R.string.edit_summary_tag_removed, "0x%04X".format(op.tag))
        else -> stringResource(R.string.edit_summary_strip)
    }
}

/** "UTC+02:00". */
@Composable
fun utcText(offset: ZoneOffset): String = stringResource(R.string.edit_utc_offset, CaptureTimeFormat.utcOffset(offset))

/** "14 Jun 2026, 19:42:07 UTC+02:00", or without the offset when it is unknown. */
@Composable
fun dateTimeText(local: LocalDateTime, offset: ZoneOffset?, locale: Locale = LocalLocale.current.platformLocale): String {
    val text = DateTimeFormatter.ofLocalizedDateTime(FormatStyle.MEDIUM, FormatStyle.MEDIUM).withLocale(locale).format(local)
    return if (offset != null) "$text ${utcText(offset)}" else text
}

/** "Stockholm" for Europe/Stockholm, "UTC+02:00" for a plain offset. */
@Composable
fun zoneName(zone: ZoneId): String =
    if (zone is ZoneOffset) utcText(zone) else zone.id.substringAfterLast('/').replace('_', ' ')

/** "+1 d 2 h 30 min", "−45 s": the signed parts of a shift that are not zero. */
@Composable
fun durationText(duration: Duration): String {
    val sign = if (duration.isNegative) ExposureFormat.MINUS else "+"
    val total = duration.abs()
    val parts = buildList {
        if (total.toDays() > 0) add(stringResource(R.string.edit_unit_days_short, total.toDays()))
        if (total.hoursPart() > 0) add(stringResource(R.string.edit_unit_hours_short, total.hoursPart()))
        if (total.minutesPart() > 0) add(stringResource(R.string.edit_unit_minutes_short, total.minutesPart()))
        if (total.secondsPart() > 0 || isEmpty()) add(stringResource(R.string.edit_unit_seconds_short, total.secondsPart()))
    }
    return sign + parts.joinToString(" ")
}

/** The localized message for a rejected field value. */
@Composable
fun validationMessage(error: ValidationError, field: EditableField?): String = when (error) {
    ValidationError.Empty -> stringResource(R.string.edit_error_empty)
    ValidationError.NotANumber -> stringResource(R.string.edit_error_not_a_number)
    ValidationError.OutOfRange -> when (val kind = field?.kind) {
        is FieldKind.Integer -> stringResource(
            R.string.edit_error_out_of_range_between,
            localizedNumber(kind.min.toDouble(), LocalLocale.current.platformLocale),
            localizedNumber(kind.max.toDouble(), LocalLocale.current.platformLocale),
        )
        is FieldKind.Number -> {
            val min = kind.min
            val max = kind.max
            if (min != null && max != null) {
                val locale = LocalLocale.current.platformLocale
                stringResource(R.string.edit_error_out_of_range_between, localizedNumber(min, locale), localizedNumber(max, locale))
            } else {
                stringResource(R.string.edit_error_out_of_range)
            }
        }
        else -> stringResource(R.string.edit_error_out_of_range)
    }
    ValidationError.TooLong -> (field?.kind as? FieldKind.Text)?.maxLength?.let { pluralStringResource(R.plurals.edit_error_too_long_max, it, it) }
        ?: stringResource(R.string.edit_error_too_long)
    ValidationError.NotAscii -> stringResource(R.string.edit_error_not_ascii)
    ValidationError.InvalidDate -> stringResource(R.string.edit_error_invalid_date, DATE_TIME_EXAMPLE)
    ValidationError.InvalidChoice -> stringResource(R.string.edit_error_invalid_choice)
}

/**
 * [value] in the locale's own form ("2,8" in Swedish), without grouping or an exponent and with
 * at most six decimals, so range limits and warnings read like the numbers the user types.
 */
fun localizedNumber(value: Double, locale: Locale): String =
    NumberFormat.getNumberInstance(locale).apply {
        isGroupingUsed = false
        maximumFractionDigits = 6
    }.format(value)

/** The localized text of a planner warning. */
@Composable
fun planWarningText(warning: PlanWarning): String {
    val locale = LocalLocale.current.platformLocale
    return when (warning) {
        is PlanWarning.NoCaptureDate -> stringResource(
            when (warning.action) {
                DateAction.Shift -> R.string.plan_warning_no_date_shift
                DateAction.Convert -> R.string.plan_warning_no_date_convert
                DateAction.SetZone -> R.string.plan_warning_no_date_zone
            },
        )
        is PlanWarning.LatitudeOutOfRange -> stringResource(R.string.plan_warning_latitude, localizedNumber(warning.value, locale))
        is PlanWarning.LongitudeOutOfRange -> stringResource(R.string.plan_warning_longitude, localizedNumber(warning.value, locale))
        is PlanWarning.AltitudeImplausible -> stringResource(R.string.plan_warning_altitude, localizedNumber(warning.value, locale))
        is PlanWarning.NonAsciiField -> stringResource(
            when (warning.field) {
                AuthorField.Artist -> if (warning.alsoIptc) R.string.plan_warning_author_not_ascii_iptc else R.string.plan_warning_author_not_ascii
                AuthorField.Copyright -> if (warning.alsoIptc) R.string.plan_warning_copyright_not_ascii_iptc else R.string.plan_warning_copyright_not_ascii
            },
        )
        is PlanWarning.IptcTooLong -> pluralStringResource(
            when (warning.field) {
                AuthorField.Artist -> R.plurals.plan_warning_author_iptc_too_long
                AuthorField.Copyright -> R.plurals.plan_warning_copyright_iptc_too_long
            },
            warning.maxBytes,
            warning.maxBytes,
        )
    }
}

/** The localized text of something a writer could not store, or had to repair, while saving. */
@Composable
fun writeSkipText(skip: WriteSkip): String = when (skip) {
    is WriteSkip.IptcUnsupported -> pluralStringResource(
        R.plurals.write_skip_iptc_unsupported,
        skip.count,
        skip.count,
        skip.container.takeIf { it != ImageFormat.Unknown }?.displayName ?: stringResource(R.string.write_skip_this_file_type),
    )
    WriteSkip.HeifColorProfileKept -> stringResource(R.string.write_skip_heif_color_profile)
    is WriteSkip.StructuralTag -> stringResource(R.string.write_skip_structural_tag, "0x%04X".format(Locale.ROOT, skip.tag))
    is WriteSkip.IptcDatasetRejected -> stringResource(
        if (skip.tooLong) R.string.write_skip_iptc_too_long else R.string.write_skip_iptc_invalid,
        "%d:%03d".format(Locale.ROOT, skip.record, skip.dataset),
    )
    WriteSkip.MakerNoteMoved -> stringResource(R.string.write_skip_maker_note_moved)
    is WriteSkip.ExifDataDropped -> stringResource(R.string.write_skip_exif_dropped)
}

/** How dates are typed in date-time fields, matching the EXIF layout. */
const val DATE_TIME_EXAMPLE = "2026-06-14 17:42:07"

/** EXIF-style "yyyy-MM-dd HH:mm:ss", the text form of date-time fields. */
val FieldDateTimeFormat: DateTimeFormatter = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss", Locale.ROOT)

/** EXIF's own "yyyy:MM:dd HH:mm:ss", accepted too. */
private val ExifDateTimeFormat: DateTimeFormatter = DateTimeFormatter.ofPattern("yyyy:MM:dd HH:mm:ss", Locale.ROOT)

/** A date-time field's text as a time, or null when it is in neither form. */
fun parseFieldDateTime(text: String): LocalDateTime? {
    val trimmed = text.trim()
    return listOf(FieldDateTimeFormat, ExifDateTimeFormat).firstNotNullOfOrNull { format ->
        runCatching { LocalDateTime.parse(trimmed, format) }.getOrNull()
    }
}

@get:StringRes
val SaveMode.labelRes: Int
    get() = when (this) {
        SaveMode.InPlace -> R.string.edit_save_in_place
        SaveMode.Copy -> R.string.edit_save_copy
        SaveMode.Sidecar -> R.string.edit_save_sidecar
    }

private const val SUMMARY_VALUE_LENGTH = 40
