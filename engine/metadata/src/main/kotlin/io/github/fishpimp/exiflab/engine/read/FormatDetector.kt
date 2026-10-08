package io.github.fishpimp.exiflab.engine.read

import io.github.fishpimp.exiflab.engine.io.SeekableSource
import io.github.fishpimp.exiflab.model.ImageFormat

/** Magic-byte detection, including RAW flavours of TIFF (DNG/CR2/NEF/ARW/ORF/RW2/PEF/SRW) and ISOBMFF brands. */
internal object FormatDetector {
    fun detect(source: SeekableSource): ImageFormat = TODO("FormatDetector.detect")
}
