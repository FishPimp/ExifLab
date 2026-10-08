package io.github.fishpimp.exiflab.core.ui

import androidx.annotation.DrawableRes
import androidx.annotation.StringRes
import androidx.compose.runtime.Composable
import androidx.compose.runtime.ReadOnlyComposable
import io.github.fishpimp.exiflab.core.data.api.LocationHint
import io.github.fishpimp.exiflab.core.data.api.ReadOnlyReason
import io.github.fishpimp.exiflab.core.data.api.WriteError
import io.github.fishpimp.exiflab.core.designsystem.icon.ExifIcons
import io.github.fishpimp.exiflab.core.designsystem.theme.ColorPair
import io.github.fishpimp.exiflab.core.designsystem.theme.ExifLabTheme
import io.github.fishpimp.exiflab.model.ChangeKind
import io.github.fishpimp.exiflab.model.CommonField
import io.github.fishpimp.exiflab.model.DateField
import io.github.fishpimp.exiflab.model.DirectoryCategory
import io.github.fishpimp.exiflab.model.PrivacyCategory
import io.github.fishpimp.exiflab.model.RemovalCategory
import io.github.fishpimp.exiflab.model.StripPreset

@StringRes
public fun WriteError.messageRes(): Int = when (this) {
    WriteError.NO_CHANGES -> R.string.error_no_changes
    WriteError.PERMISSION_DENIED -> R.string.error_permission_denied
    WriteError.WRITE_CONSENT_REQUIRED -> R.string.error_write_consent_required
    WriteError.SOURCE_GONE -> R.string.error_source_gone
    WriteError.SOURCE_CHANGED -> R.string.error_source_changed
    WriteError.UNSUPPORTED_FORMAT -> R.string.error_unsupported_format
    WriteError.UNSUPPORTED_LAYOUT -> R.string.error_unsupported_layout
    WriteError.CORRUPT_FILE -> R.string.error_corrupt_file
    WriteError.BLOCK_TOO_LARGE -> R.string.error_block_too_large
    WriteError.INVALID_VALUE -> R.string.error_invalid_value
    WriteError.VERIFICATION_FAILED -> R.string.error_verification_failed
    WriteError.SIDECAR_FOLDER_ACCESS_NEEDED -> R.string.error_sidecar_folder_access
    WriteError.STORAGE_FULL -> R.string.error_storage_full
    WriteError.IO_ERROR -> R.string.error_io
}

@StringRes
public fun PrivacyCategory.labelRes(): Int = when (this) {
    PrivacyCategory.LOCATION -> R.string.privacy_location
    PrivacyCategory.SERIAL_NUMBER -> R.string.privacy_serial
    PrivacyCategory.PERSON -> R.string.privacy_person
    PrivacyCategory.DEVICE -> R.string.privacy_device
    PrivacyCategory.SOFTWARE -> R.string.privacy_software
    PrivacyCategory.UNIQUE_ID -> R.string.privacy_unique_id
}

@DrawableRes
public fun PrivacyCategory.icon(): Int = when (this) {
    PrivacyCategory.LOCATION -> ExifIcons.LocationOn
    PrivacyCategory.SERIAL_NUMBER -> ExifIcons.Badge
    PrivacyCategory.PERSON -> ExifIcons.Person
    PrivacyCategory.DEVICE -> ExifIcons.PhotoCamera
    PrivacyCategory.SOFTWARE -> ExifIcons.Code
    PrivacyCategory.UNIQUE_ID -> ExifIcons.Fingerprint
}

@StringRes
public fun DirectoryCategory.labelRes(): Int = when (this) {
    DirectoryCategory.FILE -> R.string.category_file
    DirectoryCategory.EXIF -> R.string.category_exif
    DirectoryCategory.GPS -> R.string.category_gps
    DirectoryCategory.MAKER_NOTES -> R.string.category_makernotes
    DirectoryCategory.XMP -> R.string.category_xmp
    DirectoryCategory.XMP_SIDECAR -> R.string.category_xmp_sidecar
    DirectoryCategory.IPTC -> R.string.category_iptc
    DirectoryCategory.ICC -> R.string.category_icc
    DirectoryCategory.CONTAINER -> R.string.category_container
    DirectoryCategory.OTHER -> R.string.category_other
}

@DrawableRes
public fun DirectoryCategory.icon(): Int = when (this) {
    DirectoryCategory.FILE -> ExifIcons.Description
    DirectoryCategory.EXIF -> ExifIcons.PhotoCamera
    DirectoryCategory.GPS -> ExifIcons.LocationOn
    DirectoryCategory.MAKER_NOTES -> ExifIcons.Memory
    DirectoryCategory.XMP, DirectoryCategory.XMP_SIDECAR -> ExifIcons.DataObject
    DirectoryCategory.IPTC -> ExifIcons.Sell
    DirectoryCategory.ICC -> ExifIcons.Palette
    DirectoryCategory.CONTAINER -> ExifIcons.Layers
    DirectoryCategory.OTHER -> ExifIcons.Info
}

/** Accent colours per directory category. */
@Composable
@ReadOnlyComposable
public fun DirectoryCategory.colors(): ColorPair {
    val c = ExifLabTheme.extendedColors
    return when (this) {
        DirectoryCategory.FILE -> c.file
        DirectoryCategory.EXIF -> c.exif
        DirectoryCategory.GPS -> c.gps
        DirectoryCategory.MAKER_NOTES -> c.makerNotes
        DirectoryCategory.XMP, DirectoryCategory.XMP_SIDECAR -> c.xmp
        DirectoryCategory.IPTC -> c.iptc
        DirectoryCategory.ICC -> c.icc
        DirectoryCategory.CONTAINER, DirectoryCategory.OTHER -> c.container
    }
}

@StringRes
public fun ReadOnlyReason.messageRes(): Int = when (this) {
    ReadOnlyReason.SHARED_READ_ONLY -> R.string.readonly_shared
    ReadOnlyReason.PICKER_READ_ONLY -> R.string.readonly_picker
    ReadOnlyReason.PROVIDER_READ_ONLY -> R.string.readonly_provider
    ReadOnlyReason.UNSUPPORTED_FORMAT -> R.string.readonly_format
}

/** Null for [LocationHint.NONE]. */
@StringRes
public fun LocationHint.messageRes(): Int? = when (this) {
    LocationHint.NONE -> null
    LocationHint.PERMISSION_MISSING -> R.string.location_hint_permission
    LocationHint.PICKER_REDACTED -> R.string.location_hint_picker
    LocationHint.SENDER_MAY_HAVE_REMOVED -> R.string.location_hint_sender
}

@StringRes
public fun RemovalCategory.labelRes(): Int = when (this) {
    RemovalCategory.LOCATION -> R.string.removal_location
    RemovalCategory.SERIAL_NUMBERS -> R.string.removal_serials
    RemovalCategory.PEOPLE -> R.string.removal_people
    RemovalCategory.DEVICE -> R.string.removal_device
    RemovalCategory.SOFTWARE -> R.string.removal_software
    RemovalCategory.DATES -> R.string.removal_dates
    RemovalCategory.COMMENTS -> R.string.removal_comments
    RemovalCategory.MAKER_NOTES -> R.string.removal_makernotes
    RemovalCategory.THUMBNAIL -> R.string.removal_thumbnail
    RemovalCategory.XMP -> R.string.removal_xmp
    RemovalCategory.IPTC -> R.string.removal_iptc
    RemovalCategory.EVERYTHING -> R.string.removal_everything
}

@StringRes
public fun StripPreset.labelRes(): Int = when (this) {
    StripPreset.LOCATION -> R.string.preset_location
    StripPreset.LOCATION_DEVICE_SERIALS -> R.string.preset_location_device
    StripPreset.EVERYTHING -> R.string.preset_everything
}

@StringRes
public fun StripPreset.descriptionRes(): Int = when (this) {
    StripPreset.LOCATION -> R.string.preset_location_body
    StripPreset.LOCATION_DEVICE_SERIALS -> R.string.preset_location_device_body
    StripPreset.EVERYTHING -> R.string.preset_everything_body
}

@StringRes
public fun CommonField.labelRes(): Int = when (this) {
    CommonField.ARTIST -> R.string.field_artist
    CommonField.COPYRIGHT -> R.string.field_copyright
    CommonField.DESCRIPTION -> R.string.field_description
    CommonField.TITLE -> R.string.field_title
    CommonField.KEYWORDS -> R.string.field_keywords
    CommonField.CAMERA_OWNER -> R.string.field_camera_owner
    CommonField.RATING -> R.string.field_rating
}

@StringRes
public fun DateField.labelRes(): Int = when (this) {
    DateField.DATE_TIME_ORIGINAL -> R.string.date_original
    DateField.CREATE_DATE -> R.string.date_create
    DateField.MODIFY_DATE -> R.string.date_modify
}

@StringRes
public fun ChangeKind.labelRes(): Int = when (this) {
    ChangeKind.ADD -> R.string.change_add
    ChangeKind.MODIFY -> R.string.change_modify
    ChangeKind.REMOVE -> R.string.change_remove
}
