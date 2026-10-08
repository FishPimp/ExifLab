package io.github.fishpimp.exiflab.engine.read.gaps

import io.github.fishpimp.exiflab.engine.io.SeekableSource

/** One TIFF block found in a CR3 file. [kind] tells which IFD the block's first directory represents. */
internal data class Cr3TiffBlock(val kind: Kind, val bytes: ByteArray) {
    enum class Kind { IFD0, EXIF, MAKERNOTE_CANON, GPS }
}

/** Readers for structures metadata-extractor does not handle (see docs/spike-format-matrix.md). */
internal object GapReaders {
    /** CR3: TIFF blocks from the CMT1..CMT4 boxes inside the Canon uuid box. */
    fun cr3TiffBlocks(source: SeekableSource): List<Cr3TiffBlock> = TODO("GapReaders.cr3TiffBlocks")

    /** HEIF/AVIF: the XMP packet stored as a `mime` item with content type application/rdf+xml. */
    fun heifXmp(source: SeekableSource): String? = TODO("GapReaders.heifXmp")

    /** JPEG: the merged Extended XMP (GUID-addressed APP1 chunks), or null when there is none. */
    fun jpegExtendedXmp(source: SeekableSource): String? = TODO("GapReaders.jpegExtendedXmp")

    /** Container extras: MPF images, Ultra HDR gain map, Motion Photo video, trailing data. Human-readable. */
    fun jpegExtras(source: SeekableSource): List<String> = TODO("GapReaders.jpegExtras")
}
