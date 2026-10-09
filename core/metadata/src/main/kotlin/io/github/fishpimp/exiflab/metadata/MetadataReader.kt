package io.github.fishpimp.exiflab.metadata

import io.github.fishpimp.exiflab.metadata.model.MetadataReport
import java.io.InputStream

/** A re-openable byte source for an image (a content URI, a file, a byte array in tests). */
interface ImageSource {
    val fileName: String?
    /** Total size in bytes when known. */
    val length: Long?
    /** Opens a fresh stream positioned at the start. Callers close it. */
    fun open(): InputStream
}

/** Parses every metadata block of an image into a [MetadataReport]. Blocking; call off the main thread. */
interface MetadataReader {
    fun read(source: ImageSource): MetadataReport
}

/** An embedded JPEG preview, typically from a RAW file. */
class EmbeddedPreview(
    val jpegBytes: ByteArray,
    val width: Int?,
    val height: Int?,
    /** EXIF orientation 1-8 that applies to the preview, when known. */
    val orientation: Int?,
)

/** Finds the largest embedded JPEG preview (RAW files, JPEG thumbnails). Blocking. */
interface PreviewExtractor {
    fun extract(source: ImageSource, maxBytes: Int = 24 * 1024 * 1024): EmbeddedPreview?
}

/** Entry point for the app; implementations live in this module. */
object Metadata {
    val reader: MetadataReader get() = TODO("Implemented in M1 read engine")
    val previewExtractor: PreviewExtractor get() = TODO("Implemented in M1 read engine")
}
