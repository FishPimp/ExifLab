package io.github.fishpimp.exiflab.metadata

import io.github.fishpimp.exiflab.metadata.io.SeekableSource
import io.github.fishpimp.exiflab.metadata.io.SourceAccessException
import io.github.fishpimp.exiflab.metadata.preview.ContainerPreviews
import io.github.fishpimp.exiflab.metadata.preview.JpegFrame
import io.github.fishpimp.exiflab.metadata.preview.PreviewScan
import io.github.fishpimp.exiflab.metadata.preview.TiffPreviewScanner
import java.io.IOException

/**
 * Finds the largest displayable JPEG embedded in a RAW file: TIFF-based RAW (DNG, CR2, NEF, ARW,
 * ORF, PEF, SRW, TIFF) via IFDs, SubIFDs and Olympus/Nikon MakerNotes; RW2 via JpgFromRaw; RAF via
 * its header; CR3 via the PRVW and THMB boxes.
 *
 * Returns null for JPEG, PNG, WebP, HEIF and AVIF (the platform decodes them directly), and when no
 * valid preview within [PreviewExtractor.extract]'s `maxBytes` exists. Only the chosen preview is
 * read into memory. Failures of the [ImageSource] itself propagate.
 */
class DefaultPreviewExtractor : PreviewExtractor {
    override fun extract(source: ImageSource, maxBytes: Int): EmbeddedPreview? = try {
        SeekableSource(source).use { seekable ->
            val format = ImageFormat.sniff(seekable.readUpTo(0, ImageFormat.SNIFF_LENGTH), source.fileName)
            val scan = when (format) {
                ImageFormat.Raf -> ContainerPreviews.raf(seekable)
                ImageFormat.Cr3 -> ContainerPreviews.cr3(seekable)
                ImageFormat.Tiff, ImageFormat.Dng, ImageFormat.Cr2, ImageFormat.Nef, ImageFormat.Arw,
                ImageFormat.Orf, ImageFormat.Rw2, ImageFormat.Pef, ImageFormat.Srw -> TiffPreviewScanner(seekable).scan()
                ImageFormat.Jpeg, ImageFormat.Png, ImageFormat.WebP, ImageFormat.Heif, ImageFormat.Avif,
                ImageFormat.Unknown -> PreviewScan.EMPTY
            }
            largest(seekable, scan, maxBytes)
        }
    } catch (e: SourceAccessException) {
        throw e.failure
    } catch (_: IOException) {
        // Damaged structures: no usable preview.
        null
    }

    private fun largest(source: SeekableSource, scan: PreviewScan, maxBytes: Int): EmbeddedPreview? {
        val fileLength = source.length
        val (candidate, frame) = scan.candidates
            .filter { it.length <= maxBytes && it.offset + it.length <= fileLength }
            .mapNotNull { candidate -> JpegFrame.read(source, candidate.offset, candidate.length)?.let { candidate to it } }
            .maxWithOrNull(compareBy({ it.second.area }, { it.first.length }))
            ?: return null
        val bytes = source.readFully(candidate.offset, candidate.length.toInt())
        return EmbeddedPreview(bytes, frame.width, frame.height, scan.orientation)
    }
}
