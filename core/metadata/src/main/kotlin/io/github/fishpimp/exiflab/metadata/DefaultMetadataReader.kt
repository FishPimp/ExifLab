package io.github.fishpimp.exiflab.metadata

import io.github.fishpimp.exiflab.metadata.container.ContainerReader
import io.github.fishpimp.exiflab.metadata.io.SeekableSource
import io.github.fishpimp.exiflab.metadata.io.SourceAccessException
import io.github.fishpimp.exiflab.metadata.model.MetadataReport
import io.github.fishpimp.exiflab.metadata.report.ReportAssembler

/**
 * Reads JPEG, PNG, WebP, HEIF/AVIF, TIFF and RAW files (DNG, CR2, CR3, NEF, ARW, RAF, ORF, RW2,
 * PEF, SRW) with metadata-extractor and ExifLab's own container readers.
 *
 * Files are read through seeks and skips, never loaded whole. Damaged files produce a report with
 * [MetadataReport.warnings] as long as anything is readable.
 *
 * @throws UnsupportedImageException when the file is not a supported image format.
 * @throws CorruptImageException when the file is recognized but nothing in it can be read.
 * @throws java.io.IOException (or the source's own exception) when [ImageSource] itself fails.
 */
class DefaultMetadataReader : MetadataReader {
    override fun read(source: ImageSource): MetadataReport = try {
        SeekableSource(source).use { seekable ->
            val header = seekable.readUpTo(0, ImageFormat.SNIFF_LENGTH)
            if (header.isEmpty()) throw CorruptImageException("The file is empty")
            val format = ImageFormat.sniff(header, source.fileName)
            if (format == ImageFormat.Unknown) throw UnsupportedImageException("Not a supported image format")
            val warnings = mutableListOf<String>()
            val metadata = ContainerReader.read(format, seekable, warnings)
            ReportAssembler(format, source.fileName, source.length, metadata, warnings).assemble()
        }
    } catch (e: SourceAccessException) {
        throw e.failure
    }
}
