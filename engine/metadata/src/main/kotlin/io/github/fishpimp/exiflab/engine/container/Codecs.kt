package io.github.fishpimp.exiflab.engine.container

import io.github.fishpimp.exiflab.model.ImageFormat

internal object Codecs {
    /** Codec for reading/hashing a format, or null if unsupported (RAF/CR3 have dedicated readers). */
    fun forFormat(format: ImageFormat): ContainerCodec? = when (format) {
        ImageFormat.JPEG -> JpegCodec
        ImageFormat.PNG -> PngCodec
        ImageFormat.WEBP -> WebpCodec
        ImageFormat.HEIF, ImageFormat.AVIF -> HeifCodec
        ImageFormat.TIFF, ImageFormat.DNG, ImageFormat.CR2, ImageFormat.NEF, ImageFormat.NRW, ImageFormat.ARW,
        ImageFormat.ORF, ImageFormat.RW2, ImageFormat.PEF, ImageFormat.SRW,
        -> TiffFileCodec
        else -> null
    }
}
