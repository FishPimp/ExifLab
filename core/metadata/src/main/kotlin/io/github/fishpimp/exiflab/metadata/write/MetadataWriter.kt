package io.github.fishpimp.exiflab.metadata.write

import io.github.fishpimp.exiflab.metadata.ImageFormat
import io.github.fishpimp.exiflab.metadata.ImageSource
import java.io.OutputStream

/** Outcome of a successful write. */
data class WriteResult(
    val format: ImageFormat,
    val bytesWritten: Long,
    /** Digest of the image data in the source, see [ImageDataDigest]. */
    val sourceImageDigest: String,
    /** Digest of the image data in the output. Always equal to [sourceImageDigest]. */
    val outputImageDigest: String,
    /** Changes the container cannot hold (e.g. IPTC in PNG), in English, for logs and the diff preview. */
    val skipped: List<String> = emptyList(),
)

/**
 * Rewrites the metadata of an image without touching its compressed image data. Implementations
 * stream [source] to [output]; they never decode pixels. Before returning they verify that the
 * image data of the output is byte-identical to the source and throw [ImageDataMismatchException]
 * otherwise, so a caller can rely on a returned [WriteResult] being lossless.
 */
interface MetadataWriter {
    /** Formats this writer can modify in place. RAW formats are never among them. */
    fun canWrite(format: ImageFormat): Boolean

    fun write(source: ImageSource, changes: MetadataChanges, output: OutputStream): WriteResult
}

/**
 * Computes a SHA-256 digest over the parts of a file that carry image data: JPEG frame/scan data
 * and tables, PNG critical and image chunks, WebP image chunks, HEIF image items. Metadata blocks
 * are excluded, so the digest of a file stays the same across metadata edits.
 */
interface ImageDataDigest {
    fun digest(source: ImageSource): String
}

/**
 * Writes an XMP sidecar (`IMG_1234.xmp`) for files that are never modified, such as RAW. EXIF
 * changes are mapped to their XMP equivalents (`exif:`, `tiff:`, `exifEX:` and `photoshop:` schemas)
 * and merged into [existingSidecar] when one is present.
 */
interface SidecarWriter {
    fun write(existingSidecar: ByteArray?, changes: MetadataChanges, output: OutputStream)
}

/** Thrown when a write would change image data; the output must be discarded. */
class ImageDataMismatchException(message: String) : IllegalStateException(message)

/** Thrown when a file's structure is too unusual to rewrite safely (e.g. a HEIF layout the writer does not handle). */
class UnsupportedEditException(message: String) : IllegalArgumentException(message)

/** Entry point for the app; implementations live in this module. */
object MetadataWriting {
    val writer: MetadataWriter get() = TODO("Implemented in M3 write engine")
    val digest: ImageDataDigest get() = TODO("Implemented in M3 write engine")
    val sidecar: SidecarWriter get() = TODO("Implemented in M3 write engine")
}
