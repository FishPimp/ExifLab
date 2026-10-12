package io.github.fishpimp.exiflab.ui.export

import android.content.Context
import android.graphics.Bitmap
import coil3.ImageLoader
import coil3.request.CachePolicy
import coil3.request.ImageRequest
import coil3.request.SuccessResult
import coil3.request.allowHardware
import coil3.toBitmap
import io.github.fishpimp.exiflab.data.export.pdf.PdfMapMode
import io.github.fishpimp.exiflab.data.export.pdf.PdfMaps
import io.github.fishpimp.exiflab.data.export.pdf.PdfPalette
import io.github.fishpimp.exiflab.data.photos.PhotoRef
import io.github.fishpimp.exiflab.metadata.model.GeoLocation
import io.github.fishpimp.exiflab.ui.map.MapSnapshotAvailability
import io.github.fishpimp.exiflab.ui.map.MapSnapshots

/** Report maps from MapLibre snapshots, in the report's light colors. */
class SnapshotPdfMaps(private val snapshots: MapSnapshots) : PdfMaps {
    override suspend fun mode(): PdfMapMode = when (snapshots.availability()) {
        MapSnapshotAvailability.Available -> PdfMapMode.Map
        MapSnapshotAvailability.Offline -> PdfMapMode.Offline
        MapSnapshotAvailability.Unsupported -> PdfMapMode.Failed
    }

    override suspend fun render(location: GeoLocation, width: Int, height: Int, palette: PdfPalette): Bitmap? =
        snapshots.snapshot(location.latitude, location.longitude, width, height, palette.scheme)
}

/**
 * Report thumbnails through the app's image loader, which already handles RAW previews, HEIF
 * and EXIF orientation. Software bitmaps, since PDF pages are drawn on a software canvas; not
 * kept in the memory cache, which belongs to the screens.
 */
suspend fun exportThumbnail(context: Context, imageLoader: ImageLoader, ref: PhotoRef, maxPixels: Int): Bitmap? {
    val request = ImageRequest.Builder(context)
        .data(ref)
        .size(maxPixels)
        .allowHardware(false)
        .memoryCachePolicy(CachePolicy.DISABLED)
        .build()
    return (imageLoader.execute(request) as? SuccessResult)?.image?.toBitmap()
}
