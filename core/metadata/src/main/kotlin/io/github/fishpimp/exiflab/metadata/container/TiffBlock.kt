package io.github.fishpimp.exiflab.metadata.container

import com.drew.imaging.tiff.TiffProcessingException
import com.drew.imaging.tiff.TiffReader
import com.drew.lang.ByteArrayReader
import com.drew.metadata.Directory
import com.drew.metadata.Metadata
import com.drew.metadata.exif.ExifTiffHandler
import java.io.IOException

/**
 * Parses a standalone TIFF structure whose first IFD is not IFD0 but a known directory, such as
 * the CMT2 (Exif SubIFD), CMT3 (Canon MakerNote) and CMT4 (GPS) blocks of a CR3 file. Everything
 * else (sub-IFDs, MakerNote decoding, descriptors) is metadata-extractor's regular Exif handling.
 */
internal object TiffBlock {
    fun parse(bytes: ByteArray, root: Directory, metadata: Metadata, warnings: MutableList<String>) {
        try {
            TiffReader().processTiff(ByteArrayReader(bytes), RootedTiffHandler(metadata, root), 0)
        } catch (e: TiffProcessingException) {
            warnings += "${root.name}: ${e.message}"
        } catch (e: IOException) {
            warnings += "${root.name}: data ends early (${e.message})"
        }
    }

    private class RootedTiffHandler(metadata: Metadata, private val root: Directory) : ExifTiffHandler(metadata, null, 0) {
        override fun setTiffMarker(marker: Int) = pushDirectory(root)
    }
}
