package io.github.fishpimp.exiflab.ui.components

import androidx.annotation.StringRes
import io.github.fishpimp.exiflab.R
import io.github.fishpimp.exiflab.data.photos.PhotoAccessError

/** The user-facing message for a [PhotoAccessError]. */
@get:StringRes
val PhotoAccessError.messageRes: Int
    get() = when (this) {
        PhotoAccessError.NotFound -> R.string.photo_error_not_found
        PhotoAccessError.PermissionDenied -> R.string.photo_error_permission
        PhotoAccessError.Unsupported -> R.string.photo_error_unsupported
        PhotoAccessError.ReadFailed -> R.string.photo_error_read
        PhotoAccessError.WriteFailed -> R.string.photo_error_write
    }
