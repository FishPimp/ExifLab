package io.github.fishpimp.exiflab

import android.content.ContentResolver
import android.content.Context
import coil3.ImageLoader
import io.github.fishpimp.exiflab.data.backup.BackupManager
import io.github.fishpimp.exiflab.data.batch.BatchJobDao
import io.github.fishpimp.exiflab.data.batch.BatchJobRepository
import io.github.fishpimp.exiflab.data.batch.BatchRunner
import io.github.fishpimp.exiflab.data.batch.BatchScheduling
import io.github.fishpimp.exiflab.data.batch.DocumentBatchDestinations
import io.github.fishpimp.exiflab.data.batch.PhotoSaverBatchSaving
import io.github.fishpimp.exiflab.data.batch.WorkManagerBatchScheduler
import io.github.fishpimp.exiflab.data.backup.BackupStore
import io.github.fishpimp.exiflab.data.export.MetadataExporter
import io.github.fishpimp.exiflab.data.export.ReportReader
import io.github.fishpimp.exiflab.data.export.pdf.PdfReportWriter
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
import io.github.fishpimp.exiflab.data.save.SavedEditNotices
import io.github.fishpimp.exiflab.data.save.WriteEngine
import io.github.fishpimp.exiflab.data.shareclean.ShareCleanChoices
import io.github.fishpimp.exiflab.data.shareclean.ShareCleanPreferences
import io.github.fishpimp.exiflab.data.shareclean.ShareCleaner
import io.github.fishpimp.exiflab.data.shareclean.ShareCleaning
import io.github.fishpimp.exiflab.data.shareclean.SharedFilesStore
import io.github.fishpimp.exiflab.data.settings.AppLanguage
import io.github.fishpimp.exiflab.data.settings.SettingsRepository
import io.github.fishpimp.exiflab.data.store.JsonListStore
import io.github.fishpimp.exiflab.data.store.libraryPreferences
import io.github.fishpimp.exiflab.metadata.Metadata
import io.github.fishpimp.exiflab.metadata.MetadataReader
import io.github.fishpimp.exiflab.metadata.edit.Editing
import io.github.fishpimp.exiflab.metadata.write.MetadataWriting
import io.github.fishpimp.exiflab.thumbnails.photoImageLoader
import io.github.fishpimp.exiflab.ui.edit.EditSaving
import io.github.fishpimp.exiflab.ui.edit.PhotoSaverEditSaving
import io.github.fishpimp.exiflab.ui.export.SnapshotPdfMaps
import io.github.fishpimp.exiflab.ui.export.exportThumbnail
import io.github.fishpimp.exiflab.ui.map.MapRuntime
import io.github.fishpimp.exiflab.ui.map.MapSnapshots
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
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

    /** Cleaned copies waiting to be shared, in the app's cache only. */
    val sharedFilesStore: SharedFilesStore by lazy {
        SharedFilesStore(appContext.cacheDir.resolve(SharedFilesStore.DIRECTORY_NAME))
    }

    /** The last Share clean preset. */
    val shareCleanChoices: ShareCleanChoices by lazy { ShareCleanPreferences(appContext) }

    /** Makes verified, cleaned copies of photos for sharing; see [ShareCleaner]. */
    val shareCleaner: ShareCleaning by lazy {
        ShareCleaner(
            open = photoRepository::imageSource,
            reader = { metadataReader },
            planner = { Editing.planner },
            writer = { MetadataWriting.writer },
            previews = { Metadata.previewExtractor },
            store = sharedFilesStore,
        )
    }

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

    /** The metadata writers as the save pipeline sees them. */
    val writeEngine: WriteEngine by lazy { WriteEngine.default() }

    /** Saves edits (in place, as a copy or to a sidecar) and restores originals; see [PhotoSaver]. */
    val photoSaver: PhotoSaver by lazy {
        PhotoSaver(
            dao = editHistoryDao,
            backups = backupStore,
            documents = ContentResolverDocuments(appContext.contentResolver),
            engine = writeEngine,
            tempDirectory = appContext.cacheDir.resolve(SAVE_TEMP_DIRECTORY),
            sidecarNaming = { settingsRepository.settings.first().sidecarNaming },
            afterSave = ::requestBackupPrune,
        )
    }

    /** The editor's view of the save pipeline. */
    val editSaving: EditSaving by lazy { PhotoSaverEditSaving(saver = { photoSaver }, engine = { writeEngine }) }

    /** Batch jobs and their per-photo status. */
    val batchJobDao: BatchJobDao get() = editHistoryDatabase.batchJobs()

    private val batchScheduler: BatchScheduling by lazy { WorkManagerBatchScheduler(appContext) }

    /** Drafts, starting, cancelling and observing batch edits. */
    val batchJobs: BatchJobRepository by lazy { BatchJobRepository(dao = { batchJobDao }, scheduler = batchScheduler) }

    /** What the background worker runs; see [BatchRunner]. */
    val batchRunner: BatchRunner by lazy {
        BatchRunner(
            jobs = batchJobDao,
            saving = PhotoSaverBatchSaving(saver = { photoSaver }, engine = { writeEngine }, history = { editHistoryDao }),
            newDestinations = {
                DocumentBatchDestinations(appContext.contentResolver, ContentResolverDocuments(appContext.contentResolver))
            },
            readReport = { ref -> withContext(Dispatchers.IO) { metadataReader.read(photoRepository.imageSource(ref)) } },
            planner = { Editing.planner },
        )
    }

    val contentResolver: ContentResolver get() = appContext.contentResolver

    /** Still map images for exported reports, through the same allowlisted client as every map. */
    val mapSnapshots: MapSnapshots by lazy { MapSnapshots(appContext, mapRuntime, offlineMode, networkMonitor) }

    /** CSV, JSON and PDF exports of photo metadata. */
    val metadataExporter: MetadataExporter by lazy {
        val readReport: ReportReader = { ref -> withContext(Dispatchers.IO) { metadataReader.read(photoRepository.imageSource(ref)) } }
        MetadataExporter(
            readReport = readReport,
            pdf = PdfReportWriter(
                context = appContext,
                readReport = readReport,
                thumbnails = { ref, maxPixels -> exportThumbnail(appContext, imageLoader, ref, maxPixels) },
                maps = SnapshotPdfMaps(mapSnapshots),
            ),
            appVersion = BuildConfig.VERSION_NAME,
        )
    }

    /** Saves the editor finished, waiting for the photo's viewer to reload and offer Undo. */
    val savedEditNotices: SavedEditNotices by lazy { SavedEditNotices() }

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
     * Start-up maintenance, off the main thread: deletes shared copies older than a day, resolves
     * saves a crash interrupted, resumes batch jobs that were cut short, then prunes backups. The
     * save maintenance is skipped while nothing was ever saved, so a fresh start opens no database.
     */
    fun startMaintenance() {
        // Shared copies older than a day have been picked up by now.
        appScope.launch(Dispatchers.IO) { sharedFilesStore.deleteOlderThan() }
        appScope.launch(Dispatchers.IO) {
            val hasHistory = appContext.getDatabasePath(EditHistoryDatabase.NAME).exists()
            val hasBackups = appContext.filesDir.resolve(BackupStore.DIRECTORY_NAME).exists()
            if (!hasHistory && !hasBackups) return@launch
            runCatching { photoSaver.recoverInterrupted() }
            if (hasHistory) runCatching { batchJobs.resumeUnfinished() }
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
