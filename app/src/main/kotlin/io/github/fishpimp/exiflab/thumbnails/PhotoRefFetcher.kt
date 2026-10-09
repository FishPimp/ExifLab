package io.github.fishpimp.exiflab.thumbnails

import coil3.ImageLoader
import coil3.decode.DataSource
import coil3.decode.ImageSource
import coil3.disk.DiskCache
import coil3.fetch.FetchResult
import coil3.fetch.Fetcher
import coil3.fetch.SourceFetchResult
import coil3.request.Options
import coil3.toUri
import io.github.fishpimp.exiflab.data.photos.PhotoRef
import io.github.fishpimp.exiflab.data.photos.isRaw
import io.github.fishpimp.exiflab.metadata.EmbeddedPreview
import okio.Buffer

/**
 * Loads a [PhotoRef]. RAW files go through [extractPreview] (the metadata engine's preview
 * extractor) and the result is kept in Coil's disk cache, because finding the preview means
 * parsing the whole container. Everything else, and RAW files without a usable preview, is
 * handed to Coil's own content URI fetcher and decoders (ImageDecoder decodes HEIF, and the
 * platform can often decode common RAW formats itself).
 */
internal class PhotoRefFetcher(
    private val ref: PhotoRef,
    private val options: Options,
    private val imageLoader: ImageLoader,
    private val extractPreview: (PhotoRef) -> EmbeddedPreview?,
) : Fetcher {

    override suspend fun fetch(): FetchResult? {
        if (ref.isRaw) rawPreview()?.let { return it }
        val fetcher = imageLoader.components.newFetcher(ref.uri.toUri(), options, imageLoader)?.first ?: return null
        return fetcher.fetch()
    }

    private fun rawPreview(): FetchResult? {
        val diskCache = imageLoader.diskCache
        val key = diskCacheKey(ref)
        if (diskCache != null && options.diskCachePolicy.readEnabled) {
            diskCache.openSnapshot(key)?.let { return it.toResult(diskCache, key) }
        }
        val preview = extractPreview(ref) ?: return null
        if (diskCache != null && options.diskCachePolicy.writeEnabled) {
            diskCache.write(key, preview)?.let { return it.toResult(diskCache, key) }
        }
        return SourceFetchResult(
            source = ImageSource(Buffer().write(preview.jpegBytes), options.fileSystem, RawPreviewMetadata(preview.orientation)),
            mimeType = JPEG_MIME_TYPE,
            dataSource = DataSource.DISK,
        )
    }

    /** Stores the preview bytes as data and its orientation as metadata; null if the entry is busy. */
    private fun DiskCache.write(key: String, preview: EmbeddedPreview): DiskCache.Snapshot? {
        val editor = openEditor(key) ?: return null
        return try {
            fileSystem.write(editor.data) { write(preview.jpegBytes) }
            fileSystem.write(editor.metadata) { writeUtf8(preview.orientation?.toString().orEmpty()) }
            editor.commitAndOpenSnapshot()
        } catch (_: Exception) {
            editor.abort()
            null
        }
    }

    private fun DiskCache.Snapshot.toResult(diskCache: DiskCache, key: String): SourceFetchResult {
        val orientation = runCatching { diskCache.fileSystem.read(metadata) { readUtf8() }.trim().toIntOrNull() }.getOrNull()
        return SourceFetchResult(
            source = ImageSource(data, diskCache.fileSystem, key, this, RawPreviewMetadata(orientation)),
            mimeType = JPEG_MIME_TYPE,
            dataSource = DataSource.DISK,
        )
    }

    /** Creates a [PhotoRefFetcher] for every [PhotoRef] model. */
    class Factory(private val extractPreview: (PhotoRef) -> EmbeddedPreview?) : Fetcher.Factory<PhotoRef> {
        override fun create(data: PhotoRef, options: Options, imageLoader: ImageLoader): Fetcher =
            PhotoRefFetcher(data, options, imageLoader, extractPreview)
    }

    private companion object {
        const val JPEG_MIME_TYPE = "image/jpeg"

        /** Changes when the file does, so an edited file never shows a stale preview. */
        fun diskCacheKey(ref: PhotoRef) = "raw-preview:v1:${ref.uri}:${ref.lastModified}:${ref.size}"
    }
}
