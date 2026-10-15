package io.github.fishpimp.exiflab.metadata.write

import io.github.fishpimp.exiflab.metadata.ImageFormat
import io.github.fishpimp.exiflab.metadata.ImageSource
import io.github.fishpimp.exiflab.metadata.io.SeekableSource
import io.github.fishpimp.exiflab.metadata.io.SourceAccessException
import io.github.fishpimp.exiflab.metadata.write.heif.HeifStructure
import io.github.fishpimp.exiflab.metadata.write.jpeg.JpegWriter
import io.github.fishpimp.exiflab.metadata.write.png.PngWriter
import java.io.InputStream

/** Data a photo carries besides its primary image, where metadata readers do not look. */
enum class EmbeddedExtra {
    /** Bytes after the image's end (JPEG EOI, PNG IEND, the RIFF chunk, the last HEIF box): Motion Photo video, vendor trailers. */
    TrailingData,

    /** JPEG images listed in an MPF index besides the primary one: Ultra HDR gain maps, MPO views. */
    SecondaryImages,

    /** HEIF top-level boxes the image does not need, such as an embedded Motion Photo video. */
    ExtraBoxes,
}

/**
 * Finds [EmbeddedExtra]s: a Motion Photo's video, a vendor trailer or an MPF secondary image can
 * hold their own location and dates (an MP4 location atom, a second Exif block), which the
 * metadata reader never sees because it stops at the primary image. Removing metadata from the
 * primary image alone therefore does not make such a file clean.
 */
object EmbeddedExtras {
    /**
     * The extras in [source]; empty when there are none, or for formats without such places
     * (RAW, TIFF). Zero or 0xFF padding after the image does not count.
     *
     * @throws io.github.fishpimp.exiflab.metadata.CorruptImageException when the container is too damaged to tell.
     */
    fun find(source: ImageSource): Set<EmbeddedExtra> = try {
        SeekableSource(source).use { seekable ->
            val header = seekable.readUpTo(0, ImageFormat.SNIFF_LENGTH)
            when (ImageFormat.sniff(header, source.fileName)) {
                ImageFormat.Jpeg -> JpegWriter.extras(seekable)
                ImageFormat.Png -> if (hasData(seekable, PngWriter.imageEnd(seekable))) setOf(EmbeddedExtra.TrailingData) else emptySet()
                ImageFormat.WebP -> if (hasData(seekable, webpEnd(header))) setOf(EmbeddedExtra.TrailingData) else emptySet()
                ImageFormat.Heif, ImageFormat.Avif -> heifExtras(seekable)
                else -> emptySet()
            }
        }
    } catch (e: SourceAccessException) {
        throw e.failure
    }

    /**
     * True when the writer can leave [extras] out of a copy of a [format] file, see
     * [MetadataBlock.EmbeddedMedia]: JPEG trailers and MPF images, and bytes after a PNG's IEND.
     */
    fun canDrop(format: ImageFormat, extras: Set<EmbeddedExtra>): Boolean = when (format) {
        ImageFormat.Jpeg -> true
        ImageFormat.Png -> extras.all { it == EmbeddedExtra.TrailingData }
        else -> extras.isEmpty()
    }

    /** The top-level boxes a still HEIF/AVIF image (or image sequence) consists of. */
    private val HEIF_BOXES = setOf("ftyp", "meta", "mdat", "moov", "free", "skip", "wide", "pdin")

    private fun heifExtras(source: SeekableSource): Set<EmbeddedExtra> {
        val structure = HeifStructure.read(source)
        return buildSet {
            if (structure.boxes.any { it.type !in HEIF_BOXES }) add(EmbeddedExtra.ExtraBoxes)
            if (hasData(source, structure.tailStart)) add(EmbeddedExtra.TrailingData)
        }
    }

    /** The end of the RIFF chunk, from the file header; past the end of the file when it is damaged. */
    private fun webpEnd(header: ByteArray): Long {
        if (header.size < 8) return 0
        val size = (header[4].toLong() and 0xFF) or ((header[5].toLong() and 0xFF) shl 8) or
            ((header[6].toLong() and 0xFF) shl 16) or ((header[7].toLong() and 0xFF) shl 24)
        return 8 + size + (size and 1)
    }

    /** True when [source] has a byte other than 0x00 and 0xFF from [from] on. */
    internal fun hasData(source: SeekableSource, from: Long): Boolean {
        val buffer = ByteArray(COPY_BUFFER)
        var position = from
        while (true) {
            val read = source.read(position, buffer)
            if (read <= 0) return false
            for (i in 0 until read) {
                val c = buffer[i].toInt() and 0xFF
                if (c != 0x00 && c != 0xFF) return true
            }
            position += read
        }
    }
}

/** The first [length] bytes of [source]: what the digest of a copy without its trailing data must match. */
internal class PrefixImageSource(private val source: ImageSource, private val limit: Long) : ImageSource {
    override val fileName: String? get() = source.fileName
    override val length: Long get() = limit

    override fun open(): InputStream = object : InputStream() {
        private val input = source.open()
        private var remaining = limit

        override fun read(): Int {
            if (remaining <= 0) return -1
            return input.read().also { if (it >= 0) remaining-- }
        }

        override fun read(b: ByteArray, off: Int, len: Int): Int {
            if (remaining <= 0) return -1
            val n = input.read(b, off, minOf(len.toLong(), remaining).toInt())
            if (n > 0) remaining -= n
            return n
        }

        override fun close() = input.close()
    }
}
