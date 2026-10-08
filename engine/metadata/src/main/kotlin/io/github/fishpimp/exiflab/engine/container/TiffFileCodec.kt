package io.github.fishpimp.exiflab.engine.container

import io.github.fishpimp.exiflab.engine.io.SeekableSource
import java.io.File

/**
 * TIFF-based files (DNG, TIFF, and TIFF-based RAW for reading/hashing). The whole file is the TIFF block;
 * XMP lives in IFD0 tag 700 and IPTC in tag 33723. Writing is done by MetadataWriter via the append-only
 * updater on a copy of the file, so [write] only supports the copy step.
 */
internal object TiffFileCodec : ContainerCodec {
    override fun scan(source: SeekableSource): ContainerLayout = TODO("TiffFileCodec.scan")
    override fun readXmp(source: SeekableSource, layout: ContainerLayout): String? = TODO("TiffFileCodec.readXmp")
    override fun write(source: SeekableSource, layout: ContainerLayout, changes: ContainerChanges, output: File): Unit =
        TODO("TiffFileCodec.write")
}
