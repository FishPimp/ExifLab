package io.github.fishpimp.exiflab.engine.read

import io.github.fishpimp.exiflab.engine.ReadOptions
import io.github.fishpimp.exiflab.engine.io.SeekableSource
import io.github.fishpimp.exiflab.model.ImageFormat
import io.github.fishpimp.exiflab.model.MetadataDocument

/**
 * Builds a [MetadataDocument]: metadata-extractor for decoding, plus the gap readers in `read.gaps`.
 * Never throws for damaged metadata - problems become [MetadataDocument.warnings].
 */
internal object DocumentReader {
    fun read(source: SeekableSource, format: ImageFormat, options: ReadOptions): MetadataDocument = TODO("DocumentReader.read")
}
