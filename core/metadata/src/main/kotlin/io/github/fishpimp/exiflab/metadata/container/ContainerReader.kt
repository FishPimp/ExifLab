package io.github.fishpimp.exiflab.metadata.container

import com.drew.imaging.ImageProcessingException
import com.drew.imaging.tiff.TiffProcessingException
import com.drew.imaging.tiff.TiffReader
import com.drew.imaging.webp.WebpMetadataReader
import com.drew.metadata.Metadata
import com.drew.metadata.exif.ExifTiffHandler
import io.github.fishpimp.exiflab.metadata.CorruptImageException
import io.github.fishpimp.exiflab.metadata.ImageFormat
import io.github.fishpimp.exiflab.metadata.ImageReadException
import io.github.fishpimp.exiflab.metadata.UnsupportedImageException
import io.github.fishpimp.exiflab.metadata.io.SeekableSource
import io.github.fishpimp.exiflab.metadata.io.SourceAccessException
import io.github.fishpimp.exiflab.metadata.io.SourceInputStream
import io.github.fishpimp.exiflab.metadata.io.SourceRandomAccessReader
import java.io.IOException

/**
 * Runs the right container parser for a sniffed [ImageFormat] and returns metadata-extractor's
 * directories. Recoverable problems become [warnings]; a [CorruptImageException] is thrown only
 * when the container yields nothing at all.
 */
internal object ContainerReader {
    fun read(format: ImageFormat, source: SeekableSource, warnings: MutableList<String>): Metadata {
        var failure: Exception? = null
        val metadata = try {
            when (format) {
                ImageFormat.Jpeg -> Metadata().also { metadata ->
                    if (!JpegSegments.read(source, 0, metadata, warnings)) throw CorruptImageException("Missing JPEG start marker")
                }
                ImageFormat.Png -> PngChunks.read(source, warnings)
                ImageFormat.WebP -> WebpMetadataReader.readMetadata(SourceInputStream(source))
                ImageFormat.Heif, ImageFormat.Avif -> HeifReader.read(source, warnings)
                ImageFormat.Raf -> RafReader.read(source, warnings)
                ImageFormat.Cr3 -> Cr3Reader.read(source, warnings)
                ImageFormat.Tiff, ImageFormat.Dng, ImageFormat.Cr2, ImageFormat.Nef, ImageFormat.Arw,
                ImageFormat.Orf, ImageFormat.Rw2, ImageFormat.Pef, ImageFormat.Srw -> readTiff(source, warnings)
                ImageFormat.Unknown -> throw UnsupportedImageException("Unrecognized file format")
            }
        } catch (e: SourceAccessException) {
            throw e
        } catch (e: ImageReadException) {
            throw e
        } catch (e: ImageProcessingException) {
            failure = e
            null
        } catch (e: IOException) {
            failure = e
            null
        } catch (e: RuntimeException) {
            // Untrusted input: metadata-extractor can fail on malformed structures in unexpected ways.
            failure = e
            null
        }
        if (metadata == null || metadata.directories.none { it.tagCount > 0 }) {
            val reason = failure?.message ?: warnings.firstOrNull() ?: "no metadata structures found"
            throw CorruptImageException("Unreadable ${format.displayName} file: $reason", failure)
        }
        failure?.let { warnings += "${format.displayName} parsing stopped early: ${it.message}" }
        return metadata
    }

    /** TIFF and TIFF-based RAW. The source-backed reader keeps memory flat even for huge files. */
    private fun readTiff(source: SeekableSource, warnings: MutableList<String>): Metadata {
        val metadata = Metadata()
        try {
            TiffReader().processTiff(SourceRandomAccessReader(source), ExifTiffHandler(metadata, null, 0), 0)
        } catch (e: SourceAccessException) {
            throw e
        } catch (e: TiffProcessingException) {
            warnings += "TIFF structure could not be read: ${e.message}"
        } catch (e: IOException) {
            warnings += "TIFF data ends early or is damaged: ${e.message}"
        }
        return metadata
    }
}
