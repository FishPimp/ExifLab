package io.github.fishpimp.exiflab.data.shareclean

import io.github.fishpimp.exiflab.metadata.edit.StripCategory

/** The ready-made choices of what "Share clean" removes, plus [Custom]. */
enum class ShareCleanPreset {
    /** GPS position and place names. */
    LocationOnly,

    /** Location plus everything that ties a photo to one camera or phone. */
    LocationAndDevice,

    /** Everything except orientation and the color profile, which change how the photo looks. */
    Everything,

    /** The categories the user ticked. */
    Custom,
    ;

    /** What this preset removes; [custom] is used for [Custom] only. */
    fun categories(custom: Set<StripCategory>): Set<StripCategory> = when (this) {
        LocationOnly -> LOCATION_ONLY
        LocationAndDevice -> LOCATION_AND_DEVICE
        Everything -> EVERYTHING
        Custom -> custom
    }

    companion object {
        val LOCATION_ONLY: Set<StripCategory> = setOf(StripCategory.Location)
        val LOCATION_AND_DEVICE: Set<StripCategory> = setOf(
            StripCategory.Location,
            StripCategory.DeviceIdentifiers,
            StripCategory.DeviceInfo,
            StripCategory.MakerNotes,
            StripCategory.Thumbnail,
        )
        val EVERYTHING: Set<StripCategory> = setOf(StripCategory.All)
    }
}

/**
 * What the user chose in the Share clean sheet; the last one used is remembered.
 *
 * @property custom the categories ticked under [ShareCleanPreset.Custom], kept even while another
 *   preset is selected so switching back restores them.
 * @property includeRawPreviews share the JPEG preview inside RAW files (which cannot be cleaned)
 *   instead of leaving them out.
 */
data class ShareCleanChoice(
    val preset: ShareCleanPreset = ShareCleanPreset.LocationAndDevice,
    val custom: Set<StripCategory> = ShareCleanPreset.LOCATION_ONLY,
    val includeRawPreviews: Boolean = true,
) {
    /** What will be removed. Never empty for a choice that can be shared, see [canShare]. */
    val categories: Set<StripCategory> get() = preset.categories(custom)

    /** False only for a custom choice with nothing ticked. */
    val canShare: Boolean get() = categories.isNotEmpty()
}
