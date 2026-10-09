package io.github.fishpimp.exiflab.ui.navigation

import io.github.fishpimp.exiflab.data.photos.PhotoRef

/**
 * Photos another app asked ExifLab to show (the share sheet), already resolved.
 *
 * @property id Unique per request, so the shell handles each request exactly once even across
 *   configuration changes and process death.
 * @property refs The photos to show; never empty.
 */
data class ExternalOpen(val id: Long, val refs: List<PhotoRef>)
