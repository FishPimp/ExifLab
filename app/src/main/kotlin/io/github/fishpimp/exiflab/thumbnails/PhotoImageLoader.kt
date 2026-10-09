package io.github.fishpimp.exiflab.thumbnails

import android.content.Context
import android.provider.Settings
import coil3.ImageLoader
import coil3.disk.DiskCache
import coil3.key.Keyer
import coil3.memory.MemoryCache
import coil3.request.Options
import coil3.request.crossfade
import io.github.fishpimp.exiflab.data.photos.PhotoRef
import io.github.fishpimp.exiflab.metadata.EmbeddedPreview
import okio.Path.Companion.toOkioPath

/**
 * Builds the app's single [ImageLoader] for photo thumbnails and previews. Requests use a
 * [PhotoRef] as their model. There is no network fetcher: images only ever come from content
 * URIs on this device.
 *
 * @param extractPreview returns a RAW file's embedded preview, or null when there is none.
 */
fun photoImageLoader(context: Context, extractPreview: (PhotoRef) -> EmbeddedPreview?): ImageLoader {
    val appContext = context.applicationContext
    return ImageLoader.Builder(appContext)
        .components {
            add(PhotoRefKeyer())
            add(PhotoRefFetcher.Factory(extractPreview))
            add(RawPreviewDecoder.Factory())
        }
        .memoryCache { MemoryCache.Builder().maxSizePercent(appContext, MEMORY_CACHE_PERCENT).build() }
        .diskCache {
            DiskCache.Builder()
                .directory(appContext.cacheDir.resolve(DISK_CACHE_DIRECTORY).toOkioPath())
                .maxSizeBytes(DISK_CACHE_BYTES)
                .build()
        }
        .crossfade(animationsEnabled(appContext))
        .build()
}

/**
 * Memory cache key for a [PhotoRef]: the URI plus modification time and size, so a file that
 * changes on disk gets a fresh thumbnail. Coil appends the requested size itself.
 */
internal class PhotoRefKeyer : Keyer<PhotoRef> {
    override fun key(data: PhotoRef, options: Options): String = "${data.uri}#${data.lastModified}#${data.size}"
}

private fun animationsEnabled(context: Context): Boolean =
    Settings.Global.getFloat(context.contentResolver, Settings.Global.ANIMATOR_DURATION_SCALE, 1f) != 0f

private const val MEMORY_CACHE_PERCENT = 0.25
private const val DISK_CACHE_DIRECTORY = "photo_previews"
private const val DISK_CACHE_BYTES = 256L * 1024 * 1024
