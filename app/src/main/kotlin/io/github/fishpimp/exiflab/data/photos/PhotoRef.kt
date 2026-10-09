package io.github.fishpimp.exiflab.data.photos

import kotlinx.serialization.Serializable

/**
 * A photo the user has opened, identified by its content URI plus what the provider reported
 * about it when it was resolved. Serializable so it can travel in navigation keys and be
 * persisted in the recents list.
 *
 * @property uri Content URI as a string (`content://...`).
 * @property displayName File name reported by the provider, when known.
 * @property mimeType MIME type reported by the provider, or guessed from the file extension.
 * @property size Size in bytes, when known.
 * @property lastModified Last modification time in epoch milliseconds, when known.
 * @property origin How the photo reached ExifLab; decides whether access can outlive the session.
 * @property writable True when ExifLab may write to the file in place. Picker and shared photos
 *   are never writable; documents and folder files are writable when the provider allows it.
 */
@Serializable
data class PhotoRef(
    val uri: String,
    val displayName: String?,
    val mimeType: String?,
    val size: Long?,
    val lastModified: Long?,
    val origin: PhotoOrigin,
    val writable: Boolean,
)

/** Where a [PhotoRef] came from. */
@Serializable
enum class PhotoOrigin {
    /** The system photo picker. Read-only. */
    Picker,

    /** A single document opened through the system file picker (Storage Access Framework). */
    Document,

    /** A file inside a folder the user granted access to. */
    Folder,

    /** Shared to ExifLab from another app. Read-only and usually only readable for the session. */
    Share,
}
