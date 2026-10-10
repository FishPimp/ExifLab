package io.github.fishpimp.exiflab

import android.content.Context
import coil3.ImageLoader
import io.github.fishpimp.exiflab.data.folders.FolderRepository
import io.github.fishpimp.exiflab.data.folders.GrantedFolder
import io.github.fishpimp.exiflab.data.network.NetworkMonitor
import io.github.fishpimp.exiflab.data.network.NetworkPolicy
import io.github.fishpimp.exiflab.data.network.OfflineMode
import io.github.fishpimp.exiflab.data.network.createHttpClient
import io.github.fishpimp.exiflab.data.network.exifLabUserAgent
import io.github.fishpimp.exiflab.data.photos.GrantSnapshot
import io.github.fishpimp.exiflab.data.photos.PhotoRef
import io.github.fishpimp.exiflab.data.photos.PhotoRepository
import io.github.fishpimp.exiflab.data.photos.RecentPhotosRepository
import io.github.fishpimp.exiflab.data.places.PlaceSearchRepository
import io.github.fishpimp.exiflab.data.settings.AppLanguage
import io.github.fishpimp.exiflab.data.settings.SettingsRepository
import io.github.fishpimp.exiflab.data.store.JsonListStore
import io.github.fishpimp.exiflab.data.store.libraryPreferences
import io.github.fishpimp.exiflab.metadata.Metadata
import io.github.fishpimp.exiflab.thumbnails.photoImageLoader
import io.github.fishpimp.exiflab.ui.map.MapRuntime
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import okhttp3.OkHttpClient

/**
 * Manual dependency container. One instance lives in [ExifLabApplication]; screens reach it
 * through [appGraph]. Keep construction lazy so app start stays cheap.
 */
class AppGraph(context: Context) {
    private val appContext = context.applicationContext

    private val readGrants: () -> GrantSnapshot = { GrantSnapshot.read(appContext.contentResolver) }

    /** Lives as long as the process, for state shared by the whole app. */
    private val appScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    val settingsRepository: SettingsRepository by lazy { SettingsRepository(appContext) }

    val offlineMode: OfflineMode by lazy { OfflineMode(settingsRepository.offlineMode, appScope) }

    val networkMonitor: NetworkMonitor by lazy { NetworkMonitor(appContext) }

    /**
     * The only way ExifLab reaches the internet: allowlisted hosts (map tiles and place search),
     * HTTPS only, nothing at all while offline mode is on. MapLibre uses it too.
     */
    val httpClient: OkHttpClient by lazy {
        createHttpClient(
            policy = NetworkPolicy(isOffline = offlineMode::currentBlocking),
            userAgent = exifLabUserAgent(BuildConfig.VERSION_NAME),
            cacheDirectory = appContext.cacheDir.resolve(HTTP_CACHE_DIRECTORY),
        )
    }

    val placeSearchRepository: PlaceSearchRepository by lazy {
        PlaceSearchRepository(
            client = httpClient,
            isOffline = offlineMode::current,
            appLanguage = AppLanguage::resolvedLanguageCode,
        )
    }

    val mapRuntime: MapRuntime by lazy {
        MapRuntime(context = appContext, httpClient = { httpClient }, offlineMode = offlineMode, scope = appScope)
    }

    val photoRepository: PhotoRepository by lazy { PhotoRepository(appContext) }

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

private const val HTTP_CACHE_DIRECTORY = "http"

val Context.appGraph: AppGraph
    get() = (applicationContext as ExifLabApplication).graph
