package io.github.fishpimp.exiflab.engine.tiff

/** Parses the TIFF header and the standard IFDs (IFD0, Exif, GPS, Interop, IFD1). Owned by the TIFF agent. */
internal object TiffParser {
    /** @throws io.github.fishpimp.exiflab.engine.EngineException CORRUPT_FILE when the header or IFD0 is unreadable. */
    fun parse(buffer: TiffBuffer, base: Long = 0): TiffStructure = TODO("TiffParser.parse")
}
