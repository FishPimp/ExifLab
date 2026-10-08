package io.github.fishpimp.exiflab.engine

/** Every failure the engine reports to callers. The app maps [reason] to a user-facing string. */
public class EngineException(
    public val reason: Reason,
    message: String,
    cause: Throwable? = null,
) : Exception(message, cause) {
    public enum class Reason {
        /** The bytes are not a format ExifLab understands. */
        UNSUPPORTED_FORMAT,

        /** The file is damaged in a way that makes a safe write impossible. */
        CORRUPT_FILE,

        /** The format is supported but this file's structure is not (e.g. HEIF Exif stored in several extents). */
        UNSUPPORTED_LAYOUT,

        /** A metadata block would exceed a hard container limit (e.g. 64 KiB JPEG APP1). */
        BLOCK_TOO_LARGE,

        /** In-file writing was requested for a format that only supports sidecars. */
        NOT_WRITABLE_IN_FILE,

        /** A user-supplied value could not be encoded. */
        INVALID_VALUE,

        /** Post-write verification found a difference that was not planned. */
        VERIFICATION_FAILED,
    }
}

internal fun unsupportedLayout(message: String): Nothing = throw EngineException(EngineException.Reason.UNSUPPORTED_LAYOUT, message)
internal fun corrupt(message: String, cause: Throwable? = null): Nothing = throw EngineException(EngineException.Reason.CORRUPT_FILE, message, cause)
