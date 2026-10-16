package io.github.fishpimp.exiflab.ui.shareclean

import android.content.res.Resources
import androidx.annotation.StringRes
import io.github.fishpimp.exiflab.R
import io.github.fishpimp.exiflab.data.shareclean.MetadataInventory
import io.github.fishpimp.exiflab.data.shareclean.MetadataKind
import io.github.fishpimp.exiflab.data.shareclean.RefusalReason
import io.github.fishpimp.exiflab.data.shareclean.ShareCleanPreset
import io.github.fishpimp.exiflab.metadata.edit.StripCategory
import io.github.fishpimp.exiflab.metadata.model.MetadataReport

@get:StringRes
val ShareCleanPreset.titleRes: Int
    get() = when (this) {
        ShareCleanPreset.LocationOnly -> R.string.share_clean_preset_location
        ShareCleanPreset.LocationAndDevice -> R.string.share_clean_preset_device
        ShareCleanPreset.Everything -> R.string.share_clean_preset_everything
        ShareCleanPreset.Custom -> R.string.share_clean_preset_custom
    }

@get:StringRes
val ShareCleanPreset.bodyRes: Int
    get() = when (this) {
        ShareCleanPreset.LocationOnly -> R.string.share_clean_preset_location_body
        ShareCleanPreset.LocationAndDevice -> R.string.share_clean_preset_device_body
        ShareCleanPreset.Everything -> R.string.share_clean_preset_everything_body
        ShareCleanPreset.Custom -> R.string.share_clean_preset_custom_body
    }

/** "location", "2 serial numbers": [count] of [this] kind, for a list in a sentence. */
fun MetadataKind.phrase(resources: Resources, count: Int): String = when (this) {
    MetadataKind.Location -> resources.getString(R.string.share_clean_kind_location)
    MetadataKind.SerialNumbers -> resources.getQuantityString(R.plurals.share_clean_kind_serials, count, count)
    MetadataKind.UniqueIds -> resources.getQuantityString(R.plurals.share_clean_kind_ids, count, count)
    MetadataKind.CameraDetails -> resources.getString(R.string.share_clean_kind_camera)
    MetadataKind.Names -> resources.getString(R.string.share_clean_kind_names)
    MetadataKind.People -> resources.getString(R.string.share_clean_kind_people)
    MetadataKind.Dates -> resources.getString(R.string.share_clean_kind_dates)
    MetadataKind.MakerNotes -> resources.getString(R.string.share_clean_kind_maker_notes)
    MetadataKind.Thumbnail -> resources.getString(R.string.share_clean_kind_thumbnail)
    MetadataKind.EditHistory -> resources.getString(R.string.share_clean_kind_edit_history)
    MetadataKind.OtherMetadata -> resources.getQuantityString(R.plurals.share_clean_kind_other, count, count)
}

/** A kind without a count, for what was left in a refused copy: "serial numbers" reads oddly with a number there. */
private fun MetadataKind.plainPhrase(resources: Resources): String = when (this) {
    MetadataKind.SerialNumbers -> resources.getString(R.string.share_clean_kind_serials_plain)
    MetadataKind.UniqueIds -> resources.getString(R.string.share_clean_kind_ids_plain)
    MetadataKind.OtherMetadata -> resources.getString(R.string.share_clean_kind_other_plain)
    else -> phrase(resources, 1)
}

/** "location, serial numbers and unique IDs, ...": what [categories] remove, for several photos. */
fun categoriesPhrase(resources: Resources, categories: Set<StripCategory>): String {
    if (StripCategory.All in categories) return resources.getString(R.string.share_clean_category_all)
    return StripCategory.entries.filter { it in categories }.joinToString(LIST_SEPARATOR) { category ->
        when (category) {
            StripCategory.Location -> resources.getString(R.string.share_clean_kind_location)
            StripCategory.DeviceIdentifiers -> resources.getString(R.string.share_clean_category_device_ids)
            StripCategory.DeviceInfo -> resources.getString(R.string.share_clean_kind_camera)
            StripCategory.PersonNames -> resources.getString(R.string.share_clean_kind_names)
            StripCategory.People -> resources.getString(R.string.share_clean_kind_people)
            StripCategory.Dates -> resources.getString(R.string.share_clean_kind_dates)
            StripCategory.MakerNotes -> resources.getString(R.string.share_clean_kind_maker_notes)
            StripCategory.Thumbnail -> resources.getString(R.string.share_clean_kind_thumbnail)
            StripCategory.EditHistory -> resources.getString(R.string.share_clean_kind_edit_history)
            StripCategory.All -> resources.getString(R.string.share_clean_category_all)
        }
    }
}

/** What a single photo loses and (of the private kinds) keeps with [categories]. */
class RemovalSummary(val removes: String?, val keeps: String?)

/** The private kinds the summary mentions as kept when the choice leaves them in. */
private val PrivateCategories = setOf(
    StripCategory.Location,
    StripCategory.DeviceIdentifiers,
    StripCategory.PersonNames,
    StripCategory.People,
    StripCategory.MakerNotes,
)

fun removalSummary(resources: Resources, report: MetadataReport, categories: Set<StripCategory>): RemovalSummary {
    val removed = MetadataInventory.of(report, categories)
    val kept = MetadataInventory.of(report, PrivateCategories)
    fun MetadataInventory.list(kinds: List<MetadataKind>) = kinds.joinToString(LIST_SEPARATOR) { it.phrase(resources, count(it)) }
    return RemovalSummary(
        removes = removed.kinds.takeIf { it.isNotEmpty() }?.let { removed.list(it) },
        keeps = kept.without(removed).takeIf { it.isNotEmpty() }?.let { kept.list(it) },
    )
}

/** Why a photo was left out, as a sentence fragment for its row. */
fun RefusalReason.message(resources: Resources): String = when (this) {
    RefusalReason.RawLeftOut -> resources.getString(R.string.share_clean_reason_raw_left_out)
    RefusalReason.NoRawPreview -> resources.getString(R.string.share_clean_reason_no_preview)
    is RefusalReason.Unsupported ->
        formatName?.let { resources.getString(R.string.share_clean_reason_unsupported, it) }
            ?: resources.getString(R.string.share_clean_reason_unsupported_unknown)
    RefusalReason.Unreadable -> resources.getString(R.string.share_clean_reason_unreadable)
    RefusalReason.WriteFailed -> resources.getString(R.string.share_clean_reason_write_failed)
    RefusalReason.EmbeddedExtras -> resources.getString(R.string.share_clean_reason_embedded_extras)
    is RefusalReason.StillContains -> resources.getString(
        R.string.share_clean_reason_still_contains,
        kinds.joinToString(LIST_SEPARATOR) { it.plainPhrase(resources) },
    )
}

private const val LIST_SEPARATOR = ", "
