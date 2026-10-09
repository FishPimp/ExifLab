package io.github.fishpimp.exiflab

import android.content.Context
import coil3.ImageLoader
import io.github.fishpimp.exiflab.data.folders.FolderRepository
import io.github.fishpimp.exiflab.data.folders.GrantedFolder
import io.github.fishpimp.exiflab.data.photos.GrantSnapshot
import io.github.fishpimp.exiflab.data.photos.PhotoRef
import io.github.fishpimp.exiflab.data.photos.PhotoRepository
import io.github.fishpimp.exiflab.data.photos.RecentPhotosRepository
import io.github.fishpimp.exiflab.data.settings.SettingsRepository
import io.github.fishpimp.exiflab.data.store.JsonListStore
import io.github.fishpimp.exiflab.data.store.libraryPreferences
import io.github.fishpimp.exiflab.metadata.Metadata
import io.github.fishpimp.exiflab.metadata.MetadataReader
import io.github.fishpimp.exiflab.thumbnails.photoImageLoader

/**
 * Manual dependency container. One instance lives in [ExifLabApplication]; screens reach it
 * through [appGraph]. Keep construction lazy so app start stays cheap.
 */
class AppGraph(context: Context) {
    private val appContext = context.applicationContext

    private val readGrants: () -> GrantSnapshot = { GrantSnapshot.read(appContext.contentResolver) }

    val settingsRepository: SettingsRepository by lazy { SettingsRepository(appContext) }

    val photoRepository: PhotoRepository by lazy { PhotoRepository(appContext) }

    /** Parses photo metadata. Stateless and thread-safe; blocking, so call it off the main thread. */
    val metadataReader: MetadataReader get() = Metadata.reader

    val folderRepository: FolderRepository by lazy {
        FolderRepository(
            context = appContext,
            store = JsonListStore(appContext.libraryPreferences, "granted_folders", GrantedFolder.serializer()),
            readGrants = readGrants,
        )
    }

    val recentPhotosRepository: RecentPhotosRepository by lazy {
        RecentPhotosRepository(
            store = JsonListStore(appContext.libraryPreferences, "recent_photos", PhotoRef.serializer()),
            readGrants = readGrants,
        )
    }

    /**
     * The one image loader for thumbnails and previews. RAW previews come from the metadata
     * engine; any failure there (including an engine that is not available yet) falls back to
     * the platform decoders.
     */
    val imageLoader: ImageLoader by lazy {
        photoImageLoader(appContext) { ref ->
            runCatching { Metadata.previewExtractor.extract(photoRepository.imageSource(ref)) }.getOrNull()
        }
    }
}

val Context.appGraph: AppGraph
    get() = (applicationContext as ExifLabApplication).graph
