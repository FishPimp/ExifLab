package io.github.fishpimp.exiflab.engine.preview

import io.github.fishpimp.exiflab.engine.io.SeekableSource
import io.github.fishpimp.exiflab.model.ImageFormat

/** Largest embedded JPEG preview not exceeding [maxBytes] (RAW previews, EXIF thumbnail, CR3 PRVW/THMB, RAF). */
internal object PreviewExtractor {
    fun extract(source: SeekableSource, format: ImageFormat, maxBytes: Int): ByteArray? = TODO("PreviewExtractor.extract")
}
