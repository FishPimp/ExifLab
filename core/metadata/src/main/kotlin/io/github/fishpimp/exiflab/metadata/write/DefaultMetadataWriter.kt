package io.github.fishpimp.exiflab.metadata.write

import io.github.fishpimp.exiflab.metadata.CorruptImageException
import io.github.fishpimp.exiflab.metadata.ImageFormat
import io.github.fishpimp.exiflab.metadata.ImageSource
import io.github.fishpimp.exiflab.metadata.io.SeekableSource
import io.github.fishpimp.exiflab.metadata.io.SourceAccessException
import io.github.fishpimp.exiflab.metadata.write.digest.HeifDigestSink
import io.github.fishpimp.exiflab.metadata.write.digest.ImageDigestSink
import io.github.fishpimp.exiflab.metadata.write.heif.HeifStructure
import io.github.fishpimp.exiflab.metadata.write.heif.HeifWriter
import io.github.fishpimp.exiflab.metadata.write.jpeg.JpegWriter
import io.github.fishpimp.exiflab.metadata.write.png.PngWriter
import io.github.fishpimp.exiflab.metadata.write.webp.WebpWriter
import java.io.BufferedOutputStream
import java.io.OutputStream

/**
 * Lossless metadata writer for JPEG, PNG, WebP, HEIF and AVIF. Each write streams the source once
 * to digest its image data and once to produce the output, digesting the output as it is written.
 * The result is only returned when both digests match. For HEIF/AVIF the output digest follows
 * the output's own (re-parsed) `meta` and `moov` boxes, so moved item data is verified where it
 * actually lands.
 *
 * Within [MetadataChanges], block removals are applied before tag changes, so a change set can
 * strip a block and write a few fresh values into it in one go.
 *
 * @throws UnsupportedEditException for formats it cannot write (TIFF, RAW) and for metadata or
 *   container structures too unusual or damaged to rewrite safely.
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
            // A copy without the data after the image can only match the image up to its end.
            val imageEnd = if (MetadataBlock.EmbeddedMedia in changes.removeBlocks) {
                when (format) {
                    ImageFormat.Jpeg -> JpegWriter.primaryEnd(seekable)
                    ImageFormat.Png -> PngWriter.imageEnd(seekable)
                    else -> null
                }
            } else {
                null
            }
            val sourceDigest = DefaultImageDataDigest().digest(imageEnd?.let { PrefixImageSource(source, it) } ?: source)
            val skipped = mutableListOf<WriteSkip>()
            val heif = if (format in HEIF_FORMATS) HeifWriter().prepare(seekable, changes, skipped) else null
            val sink = heif?.let { HeifDigestSink(it.digestPlan) } ?: ImageDigestSink.forFormat(format)!!
            val buffered = BufferedOutputStream(output, COPY_BUFFER)
            val tee = TeeOutputStream(buffered, sink)
            when (format) {
                ImageFormat.Jpeg -> JpegWriter().write(seekable, changes, tee, skipped)
                ImageFormat.Png -> PngWriter().write(seekable, changes, tee, skipped)
                ImageFormat.WebP -> WebpWriter().write(seekable, changes, tee, skipped)
                ImageFormat.Heif, ImageFormat.Avif -> heif!!.write(seekable, tee)
                else -> error("Unreachable: $format")
            }
            tee.flush()
            val outputDigest = sink.hex()
            if (outputDigest != sourceDigest) {
                throw ImageDataMismatchException("The image data would change (source $sourceDigest, output $outputDigest); the output must be discarded")
            }
            // The HEIF writer handles AVIF too; name the file's own format.
            val named = skipped.map { if (it is WriteSkip.IptcUnsupported) it.copy(container = format) else it }
            WriteResult(format, tee.count, sourceDigest, outputDigest, named.distinct())
        }
    } catch (e: SourceAccessException) {
        throw e.failure
    }

    private companion object {
        val WRITABLE = setOf(ImageFormat.Jpeg, ImageFormat.Png, ImageFormat.WebP, ImageFormat.Heif, ImageFormat.Avif)
    }
}

private val HEIF_FORMATS = setOf(ImageFormat.Heif, ImageFormat.Avif)

/**
 * SHA-256 over the image-carrying parts of JPEG, PNG, WebP, HEIF and AVIF files, see
 * [ImageDataDigest] and [ImageDigestSink]. Streams the source once (HEIF/AVIF first read their
 * `meta` and `moov` boxes to learn where the image items and samples are); nothing else is held
 * in memory.
 *
 * @throws UnsupportedEditException for formats without an image-data digest yet, and for HEIF
 *   layouts the writer would refuse too (item data in other files, fragmented sequences).
 * @throws CorruptImageException when a HEIF file is truncated or its boxes are broken.
 */
class DefaultImageDataDigest : ImageDataDigest {
    override fun digest(source: ImageSource): String = try {
        source.open().use { input ->
            val header = input.readNBytes(ImageFormat.SNIFF_LENGTH)
            val format = ImageFormat.sniff(header, source.fileName)
            val sink = if (format in HEIF_FORMATS) {
                // Where the image items are comes from the meta box, which may follow the image data.
                HeifDigestSink(SeekableSource(source).use { HeifStructure.read(it).digestPlan() })
            } else {
                ImageDigestSink.forFormat(format)
                    ?: throw UnsupportedEditException("No image data digest for ${if (format == ImageFormat.Unknown) "this file type" else format.displayName}")
            }
            sink.write(header)
            input.copyTo(sink, COPY_BUFFER)
            sink.hex()
        }
    } catch (e: SourceAccessException) {
        throw e.failure
    }
}
