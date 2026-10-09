package io.github.fishpimp.exiflab.metadata

import java.io.IOException

/**
 * Base type for files the metadata engine cannot read at all. Damaged files that still yield some
 * metadata do not throw; their problems are listed in
 * [io.github.fishpimp.exiflab.metadata.model.MetadataReport.warnings] instead. Plain I/O failures
 * of the [ImageSource] itself propagate unchanged as [IOException].
 */
open class ImageReadException(message: String, cause: Throwable? = null) : IOException(message, cause)

/** The file is not in a format ExifLab supports (unknown signature or container). */
class UnsupportedImageException(message: String, cause: Throwable? = null) : ImageReadException(message, cause)

/** The file looks like a supported format but is too damaged for any metadata to be read. */
class CorruptImageException(message: String, cause: Throwable? = null) : ImageReadException(message, cause)
