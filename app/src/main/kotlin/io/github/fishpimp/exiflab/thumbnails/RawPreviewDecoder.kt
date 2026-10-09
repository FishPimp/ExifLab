package io.github.fishpimp.exiflab.thumbnails

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Matrix
import androidx.exifinterface.media.ExifInterface
import coil3.ImageLoader
import coil3.asImage
import coil3.decode.DecodeResult
import coil3.decode.DecodeUtils
import coil3.decode.Decoder
import coil3.decode.ImageSource
import coil3.fetch.SourceFetchResult
import coil3.request.Options
import coil3.size.pxOrElse
import kotlinx.coroutines.runInterruptible
import java.io.ByteArrayInputStream

/**
 * Marks an [ImageSource] holding a RAW file's embedded JPEG preview.
 *
 * @property orientation EXIF orientation (1-8) from the RAW container, which the preview JPEG
 *   itself usually lacks; null when unknown, in which case the JPEG's own EXIF is used.
 */
internal class RawPreviewMetadata(val orientation: Int?) : ImageSource.Metadata()

/**
 * Decodes a RAW preview produced by [PhotoRefFetcher]: subsamples it to the requested size and
 * applies the orientation, since BitmapFactory alone would show sideways portraits.
 */
internal class RawPreviewDecoder(
    private val source: ImageSource,
    private val metadata: RawPreviewMetadata,
    private val options: Options,
) : Decoder {

    override suspend fun decode(): DecodeResult = runInterruptible {
        val bytes = source.source().readByteArray()
        val orientation = metadata.orientation ?: jpegOrientation(bytes)

        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
        check(bounds.outWidth > 0 && bounds.outHeight > 0) { "Embedded preview is not a decodable JPEG" }

        val swapsAxes = orientation in SWAPPING_ORIENTATIONS
        val width = if (swapsAxes) bounds.outHeight else bounds.outWidth
        val height = if (swapsAxes) bounds.outWidth else bounds.outHeight
        val sampleSize = DecodeUtils.calculateInSampleSize(
            srcWidth = width,
            srcHeight = height,
            dstWidth = options.size.width.pxOrElse { width },
            dstHeight = options.size.height.pxOrElse { height },
            scale = options.scale,
        )
        val decoded = BitmapFactory.decodeByteArray(bytes, 0, bytes.size, BitmapFactory.Options().apply { inSampleSize = sampleSize })
            ?: error("Embedded preview could not be decoded")
        DecodeResult(image = decoded.oriented(orientation).asImage(), isSampled = sampleSize > 1)
    }

    /** Creates a [RawPreviewDecoder] only for sources tagged with [RawPreviewMetadata]. */
    class Factory : Decoder.Factory {
        override fun create(result: SourceFetchResult, options: Options, imageLoader: ImageLoader): Decoder? {
            val metadata = result.source.metadata as? RawPreviewMetadata ?: return null
            return RawPreviewDecoder(result.source, metadata, options)
        }
    }

    private companion object {
        /** Orientations 5-8 rotate by 90 degrees, so width and height trade places. */
        val SWAPPING_ORIENTATIONS = 5..8

        fun jpegOrientation(bytes: ByteArray): Int = runCatching {
            ExifInterface(ByteArrayInputStream(bytes))
                .getAttributeInt(ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_NORMAL)
        }.getOrDefault(ExifInterface.ORIENTATION_NORMAL)
    }
}

/** Returns this bitmap rotated and/or mirrored as EXIF [orientation] (1-8) prescribes. */
internal fun Bitmap.oriented(orientation: Int): Bitmap {
    val matrix = orientationMatrix(orientation) ?: return this
    val result = Bitmap.createBitmap(this, 0, 0, width, height, matrix, true)
    if (result !== this) recycle()
    return result
}

/** The transform for an EXIF orientation, or null for "normal" and unknown values. */
internal fun orientationMatrix(orientation: Int): Matrix? = when (orientation) {
    ExifInterface.ORIENTATION_FLIP_HORIZONTAL -> Matrix().apply { setScale(-1f, 1f) }
    ExifInterface.ORIENTATION_ROTATE_180 -> Matrix().apply { setRotate(180f) }
    ExifInterface.ORIENTATION_FLIP_VERTICAL -> Matrix().apply { setScale(1f, -1f) }
    ExifInterface.ORIENTATION_TRANSPOSE -> Matrix().apply {
        setRotate(90f)
        postScale(-1f, 1f)
    }
    ExifInterface.ORIENTATION_ROTATE_90 -> Matrix().apply { setRotate(90f) }
    ExifInterface.ORIENTATION_TRANSVERSE -> Matrix().apply {
        setRotate(-90f)
        postScale(-1f, 1f)
    }
    ExifInterface.ORIENTATION_ROTATE_270 -> Matrix().apply { setRotate(-90f) }
    else -> null
}
