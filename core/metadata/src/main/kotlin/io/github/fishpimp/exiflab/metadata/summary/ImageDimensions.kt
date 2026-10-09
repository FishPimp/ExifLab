package io.github.fishpimp.exiflab.metadata.summary

import com.drew.imaging.png.PngChunkType
import com.drew.metadata.Directory
import com.drew.metadata.exif.ExifIFD0Directory
import com.drew.metadata.exif.ExifImageDirectory
import com.drew.metadata.exif.ExifSubIFDDirectory
import com.drew.metadata.exif.PanasonicRawIFD0Directory
import com.drew.metadata.heif.HeifDirectory
import com.drew.metadata.jpeg.JpegDirectory
import com.drew.metadata.png.PngDirectory
import com.drew.metadata.webp.WebpDirectory
import io.github.fishpimp.exiflab.metadata.ImageFormat
import io.github.fishpimp.exiflab.metadata.container.ContainerDirectory
import io.github.fishpimp.exiflab.metadata.container.RafReader
import io.github.fishpimp.exiflab.metadata.mapping.DirectoryCatalog

/**
 * Pixel dimensions of the main image. Actual image structures win over Exif PixelX/YDimension,
 * which editors often leave stale: the JPEG frame header, PNG IHDR, WebP header, the HEIF primary
 * item, or the RAW sensor area.
 */
internal object ImageDimensions {
    fun of(lookup: MetadataLookup, format: ImageFormat): Pair<Int, Int>? {
        val structural = when (format) {
            ImageFormat.Jpeg -> lookup.all<JpegDirectory>().firstNotNullOfOrNull {
                pair(it, JpegDirectory.TAG_IMAGE_WIDTH, JpegDirectory.TAG_IMAGE_HEIGHT)
            }
            ImageFormat.Png -> lookup.all<PngDirectory>().filter { it.pngChunkType == PngChunkType.IHDR }
                .firstNotNullOfOrNull { pair(it, PngDirectory.TAG_IMAGE_WIDTH, PngDirectory.TAG_IMAGE_HEIGHT) }
            ImageFormat.WebP -> lookup.all<WebpDirectory>().firstNotNullOfOrNull {
                pair(it, WebpDirectory.TAG_IMAGE_WIDTH, WebpDirectory.TAG_IMAGE_HEIGHT)
            }
            ImageFormat.Heif, ImageFormat.Avif -> lookup.all<HeifDirectory>().firstNotNullOfOrNull {
                pair(it, HeifDirectory.TAG_IMAGE_WIDTH, HeifDirectory.TAG_IMAGE_HEIGHT)
            }
            ImageFormat.Raf -> rafSize(lookup)
            ImageFormat.Rw2 -> panasonicSensorSize(lookup)
            ImageFormat.Cr3, ImageFormat.Unknown -> null
            ImageFormat.Tiff, ImageFormat.Dng, ImageFormat.Cr2, ImageFormat.Nef, ImageFormat.Arw,
            ImageFormat.Orf, ImageFormat.Pef, ImageFormat.Srw -> tiffImageSize(lookup)
        }
        return structural ?: exifSize(lookup) ?: xmpSize(lookup)
    }

    /** RAF tag directory: cropped size, else full sensor size, stored as (height, width). */
    private fun rafSize(lookup: MetadataLookup): Pair<Int, Int>? {
        val raf = lookup.all<ContainerDirectory>().firstOrNull { it.id == "raf" } ?: return null
        return listOf(RafReader.TAG_RAW_CROPPED_SIZE, RafReader.TAG_RAW_FULL_SIZE).firstNotNullOfOrNull { tag ->
            raf.ints(tag)?.takeIf { it.size == 2 }?.let { positive(it[1], it[0]) }
        }
    }

    /** RW2 IFD0: sensor borders (top, left, bottom, right), else SensorWidth/SensorHeight. */
    private fun panasonicSensorSize(lookup: MetadataLookup): Pair<Int, Int>? {
        val ifd0 = lookup.all<PanasonicRawIFD0Directory>().firstOrNull() ?: return null
        val top = ifd0.int(0x0004)
        val left = ifd0.int(0x0005)
        val bottom = ifd0.int(0x0006)
        val right = ifd0.int(0x0007)
        if (top != null && left != null && bottom != null && right != null) positive(right - left, bottom - top)?.let { return it }
        return pair(ifd0, 0x0002, 0x0003)
    }

    /**
     * TIFF-based RAW: the largest full-resolution IFD (NewSubfileType 0 or absent) among IFD0 and
     * the image SubIFDs; for DNG its DefaultCropSize, the size of the developed image.
     */
    private fun tiffImageSize(lookup: MetadataLookup): Pair<Int, Int>? {
        val imageIfds: List<Directory> = lookup.all<ExifIFD0Directory>() +
            lookup.all<ExifSubIFDDirectory>().filter(DirectoryCatalog::isImageSubIfd) +
            lookup.all<ExifImageDirectory>()
        return imageIfds
            .filter { (it.int(NEW_SUBFILE_TYPE) ?: 0) == 0 }
            .mapNotNull { ifd -> ifd.ints(DEFAULT_CROP_SIZE)?.takeIf { it.size == 2 }?.let { positive(it[0], it[1]) } ?: pair(ifd, IMAGE_WIDTH, IMAGE_LENGTH) }
            .maxByOrNull { (width, height) -> width.toLong() * height }
    }

    private fun exifSize(lookup: MetadataLookup): Pair<Int, Int>? {
        val width = lookup.exif(PIXEL_X_DIMENSION)?.int(PIXEL_X_DIMENSION)
        val height = lookup.exif(PIXEL_Y_DIMENSION)?.int(PIXEL_Y_DIMENSION)
        if (width != null && height != null) positive(width, height)?.let { return it }
        return lookup.all<ExifIFD0Directory>().firstNotNullOfOrNull { pair(it, IMAGE_WIDTH, IMAGE_LENGTH) }
    }

    private fun xmpSize(lookup: MetadataLookup): Pair<Int, Int>? {
        val width = lookup.xmp("exif:PixelXDimension") ?: lookup.xmp("tiff:ImageWidth")
        val height = lookup.xmp("exif:PixelYDimension") ?: lookup.xmp("tiff:ImageLength")
        return positive(width?.toIntOrNull() ?: return null, height?.toIntOrNull() ?: return null)
    }

    private fun pair(directory: Directory, widthTag: Int, heightTag: Int): Pair<Int, Int>? =
        positive(directory.int(widthTag) ?: return null, directory.int(heightTag) ?: return null)

    private fun positive(width: Int, height: Int): Pair<Int, Int>? = if (width > 0 && height > 0) width to height else null

    private const val NEW_SUBFILE_TYPE = 0x00FE
    private const val IMAGE_WIDTH = 0x0100
    private const val IMAGE_LENGTH = 0x0101
    private const val DEFAULT_CROP_SIZE = 0xC620
    private const val PIXEL_X_DIMENSION = 0xA002
    private const val PIXEL_Y_DIMENSION = 0xA003
}
