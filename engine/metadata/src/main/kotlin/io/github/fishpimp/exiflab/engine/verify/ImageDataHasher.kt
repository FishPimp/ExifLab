package io.github.fishpimp.exiflab.engine.verify

import io.github.fishpimp.exiflab.engine.io.SeekableSource
import io.github.fishpimp.exiflab.model.ImageFormat

/**
 * SHA-256 over the image data only (JPEG scan data, PNG IDAT, WebP bitstream, HEIF image items, TIFF
 * strips/tiles), equivalent in spirit to exiftool's ImageDataHash. Null if the format has no hashable data.
 */
internal object ImageDataHasher {
    fun hash(source: SeekableSource, format: ImageFormat): String? = TODO("ImageDataHasher.hash")
}
