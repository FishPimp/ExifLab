package io.github.fishpimp.exiflab.data.photos

import java.io.FileNotFoundException
import java.io.IOException

/** Why a photo or folder could not be read or written. The UI maps each reason to a message. */
enum class PhotoAccessError {
    /** The file or folder no longer exists, or the provider does not know it. */
    NotFound,

    /** ExifLab's access was revoked or never granted. */
    PermissionDenied,

    /** The file is not an image ExifLab can open. */
    Unsupported,

    /** Reading failed for another reason (I/O error, provider crash). */
    ReadFailed,

    /** Writing failed (destination full, read-only or gone). */
    WriteFailed,
}

/** Thrown by the photo and folder repositories; [error] says what went wrong in user terms. */
class PhotoAccessException(val error: PhotoAccessError, cause: Throwable? = null) :
    IOException("Photo access failed: $error", cause)

/**
 * Runs [block] and converts platform failures into [PhotoAccessException]. Content providers
 * throw [SecurityException] when a grant is gone, [FileNotFoundException] for missing files and
 * [IllegalArgumentException] for URIs they do not recognise.
 */
internal inline fun <T> mapAccessErrors(fallback: PhotoAccessError, block: () -> T): T = try {
    block()
} catch (e: PhotoAccessException) {
    throw e
} catch (e: SecurityException) {
    throw PhotoAccessException(PhotoAccessError.PermissionDenied, e)
} catch (e: FileNotFoundException) {
    throw PhotoAccessException(PhotoAccessError.NotFound, e)
} catch (e: IllegalArgumentException) {
    throw PhotoAccessException(PhotoAccessError.NotFound, e)
} catch (e: IOException) {
    throw PhotoAccessException(fallback, e)
}
