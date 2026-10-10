package io.github.fishpimp.exiflab.metadata.write

import io.github.fishpimp.exiflab.metadata.CorruptImageException
import io.github.fishpimp.exiflab.metadata.ImageFormat
import io.github.fishpimp.exiflab.metadata.ImageSource
import io.github.fishpimp.exiflab.metadata.io.SeekableSource
import io.github.fishpimp.exiflab.metadata.io.SourceAccessException
import io.github.fishpimp.exiflab.metadata.write.digest.ImageDigestSink
import io.github.fishpimp.exiflab.metadata.write.jpeg.JpegWriter
import io.github.fishpimp.exiflab.metadata.write.png.PngWriter
import io.github.fishpimp.exiflab.metadata.write.webp.WebpWriter
import java.io.BufferedOutputStream
import java.io.OutputStream

/**
 * Lossless metadata writer for JPEG, PNG and WebP. Each write streams the source once to digest
 * its image data and once to produce the output, digesting the output as it is written. The
 * result is only returned when both digests match.
 *
 * Within [MetadataChanges], block removals are applied before tag changes, so a change set can
 * strip a block and write a few fresh values into it in one go.
 *
 * @throws UnsupportedEditException for formats it cannot write (HEIF, AVIF, TIFF, RAW) and for
 *   metadata structures too unusual or damaged to rewrite safely.
 * @throws CorruptImageException when the file is truncated or its container structure is broken.
 * @throws ImageDataMismatchException when the output's image data would differ from the source.
 * @throws java.io.IOException (or the source's own exception) when the source or output fails.
 */
class DefaultMetadataWriter : MetadataWriter {
    override fun canWrite(format: ImageFormat): Boolean = format in WRITABLE

    override fun write(source: ImageSource, changes: MetadataChanges, output: OutputStream): WriteResult = try {
        SeekableSource(source).use { seekable ->
            val header = seekable.readUpTo(0, ImageFormat.SNIFF_LENGTH)
            if (header.isEmpty()) throw CorruptImageException("The file is empty")
            val format = ImageFormat.sniff(header, source.fileName)
            if (!canWrite(format)) {
                val name = if (format == ImageFormat.Unknown) "This file type" else "${format.displayName} files"
                throw UnsupportedEditException("$name cannot be edited in place")
            }
            val sourceDigest = DefaultImageDataDigest().digest(source)
            val sink = ImageDigestSink.forFormat(format)!!
            val buffered = BufferedOutputStream(output, COPY_BUFFER)
            val tee = TeeOutputStream(buffered, sink)
            val skipped = mutableListOf<String>()
            when (format) {
                ImageFormat.Jpeg -> JpegWriter().write(seekable, changes, tee, skipped)
                ImageFormat.Png -> PngWriter().write(seekable, changes, tee, skipped)
                ImageFormat.WebP -> WebpWriter().write(seekable, changes, tee, skipped)
                else -> error("Unreachable: $format")
            }
            tee.flush()
            val outputDigest = sink.hex()
            if (outputDigest != sourceDigest) {
                throw ImageDataMismatchException("The image data would change (source $sourceDigest, output $outputDigest); the output must be discarded")
            }
            WriteResult(format, tee.count, sourceDigest, outputDigest, skipped.distinct())
        }
    } catch (e: SourceAccessException) {
        throw e.failure
    }

    private companion object {
        val WRITABLE = setOf(ImageFormat.Jpeg, ImageFormat.Png, ImageFormat.WebP)
    }
}

/**
 * SHA-256 over the image-carrying parts of JPEG, PNG and WebP files, see [ImageDataDigest] and
 * [ImageDigestSink]. Streams the source once; nothing is held in memory.
 *
 * @throws UnsupportedEditException for formats without an image-data digest yet.
 */
class DefaultImageDataDigest : ImageDataDigest {
    override fun digest(source: ImageSource): String = source.open().use { input ->
        val header = input.readNBytes(ImageFormat.SNIFF_LENGTH)
        val format = ImageFormat.sniff(header, source.fileName)
        val sink = ImageDigestSink.forFormat(format)
            ?: throw UnsupportedEditException("No image data digest for ${if (format == ImageFormat.Unknown) "this file type" else format.displayName}")
        sink.write(header)
        input.copyTo(sink, COPY_BUFFER)
        sink.hex()
    }
}
