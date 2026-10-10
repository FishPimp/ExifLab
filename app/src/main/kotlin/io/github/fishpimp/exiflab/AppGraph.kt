package io.github.fishpimp.exiflab

import android.content.Context
import coil3.ImageLoader
import io.github.fishpimp.exiflab.data.backup.BackupManager
import io.github.fishpimp.exiflab.data.backup.BackupStore
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
import io.github.fishpimp.exiflab.data.history.EditHistoryDao
import io.github.fishpimp.exiflab.data.history.EditHistoryDatabase
import io.github.fishpimp.exiflab.data.places.PlaceSearchRepository
import io.github.fishpimp.exiflab.data.save.ContentResolverDocuments
import io.github.fishpimp.exiflab.data.save.PhotoSaver
import io.github.fishpimp.exiflab.data.save.WriteEngine
import io.github.fishpimp.exiflab.data.settings.AppLanguage
import io.github.fishpimp.exiflab.data.settings.SettingsRepository
import io.github.fishpimp.exiflab.data.store.JsonListStore
import io.github.fishpimp.exiflab.data.store.libraryPreferences
import io.github.fishpimp.exiflab.metadata.Metadata
import io.github.fishpimp.exiflab.metadata.MetadataReader
import io.github.fishpimp.exiflab.thumbnails.photoImageLoader
import io.github.fishpimp.exiflab.ui.map.MapRuntime
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
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

    private val editHistoryDatabase: EditHistoryDatabase by lazy { EditHistoryDatabase.create(appContext) }

    /** The write journal and edit history. */
    val editHistoryDao: EditHistoryDao get() = editHistoryDatabase.dao()

    private val backupStore: BackupStore by lazy { BackupStore(appContext.filesDir.resolve(BackupStore.DIRECTORY_NAME)) }

    /** Pruning, usage and deletion of the backups taken before in-place edits. */
    val backupManager: BackupManager by lazy {
        BackupManager(
            dao = editHistoryDao,
            store = backupStore,
            retention = { settingsRepository.settings.first().backupRetention },
        )
    }

    /** Saves edits (in place, as a copy or to a sidecar) and restores originals; see [PhotoSaver]. */
    val photoSaver: PhotoSaver by lazy {
        PhotoSaver(
            dao = editHistoryDao,
            backups = backupStore,
            documents = ContentResolverDocuments(appContext.contentResolver),
            engine = WriteEngine.default(),
            tempDirectory = appContext.cacheDir.resolve(SAVE_TEMP_DIRECTORY),
            sidecarNaming = { settingsRepository.settings.first().sidecarNaming },
            afterSave = ::requestBackupPrune,
        )
    }

    private var pruneJob: Job? = null

    /** Prunes backups shortly, once for a burst of saves. */
    fun requestBackupPrune() {
        synchronized(this) {
            if (pruneJob?.isActive == true) return
            pruneJob = appScope.launch(Dispatchers.IO) {
                delay(PRUNE_DELAY_MS)
                runCatching { backupManager.prune() }
            }
        }
    }

    /**
     * Start-up maintenance, off the main thread: resolves saves a crash interrupted, then prunes
     * backups. Skipped entirely while nothing was ever saved, so a fresh start opens no database.
     */
    fun startMaintenance() {
        appScope.launch(Dispatchers.IO) {
            val hasHistory = appContext.getDatabasePath(EditHistoryDatabase.NAME).exists()
            val hasBackups = appContext.filesDir.resolve(BackupStore.DIRECTORY_NAME).exists()
            if (!hasHistory && !hasBackups) return@launch
            runCatching { photoSaver.recoverInterrupted() }
            runCatching { backupManager.prune() }
        }
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
private const val SAVE_TEMP_DIRECTORY = "saving"
private const val PRUNE_DELAY_MS = 2_000L

val Context.appGraph: AppGraph
    get() = (applicationContext as ExifLabApplication).graph
