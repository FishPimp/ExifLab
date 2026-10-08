package io.github.fishpimp.exiflab.core.data.api

import android.app.PendingIntent
import android.net.Uri
import io.github.fishpimp.exiflab.model.DngWriteMode
import io.github.fishpimp.exiflab.model.EditOperation
import io.github.fishpimp.exiflab.model.GeoPoint
import io.github.fishpimp.exiflab.model.SidecarNaming
import io.github.fishpimp.exiflab.model.StripPreset
import kotlinx.coroutines.flow.Flow

/** The device photo library (MediaStore). */
public interface MediaRepository {
    public fun access(): MediaAccess

    /** Images newest first; [bucketId] null means all. */
    public suspend fun images(offset: Int, limit: Int, bucketId: String? = null): List<MediaItem>

    public suspend fun buckets(): List<MediaBucket>

    /** Emits whenever MediaStore changes (new photos, edits by other apps). */
    public fun changes(): Flow<Unit>

    /** Turns URIs received from the Photo Picker, SAF or another app into [ImageRef]s (queries name, size, MIME). */
    public suspend fun resolve(uris: List<Uri>, origin: ImageOrigin, treeUri: Uri? = null): List<ImageRef>

    /** Images (any supported format, incl. RAW) inside a SAF folder tree the user granted, newest first. */
    public suspend fun listFolder(treeUri: Uri): List<ImageRef>

    /** Persists read/write access to a folder tree chosen with ACTION_OPEN_DOCUMENT_TREE. */
    public fun persistFolderAccess(treeUri: Uri)

    public fun persistedFolders(): List<Uri>

    /**
     * A documents-UI URI pointing at the folder that contains [ref] (for EXTRA_INITIAL_URI when asking for folder
     * access to write a sidecar), or null if unknown.
     */
    public fun folderHint(ref: ImageRef): Uri?
}

/** Reading metadata. */
public interface MetadataRepository {
    /** Reads and decodes metadata. Throws nothing for damaged metadata; returns [Result.failure] for unreadable files. */
    public suspend fun load(ref: ImageRef): Result<LoadedImage>

    /** Embedded JPEG preview (RAW thumbnails), or null. */
    public suspend fun embeddedPreview(ref: ImageRef, maxBytes: Int = 4 * 1024 * 1024): ByteArray?
}

/** Planning and saving edits, with backup, verification and history. */
public interface EditRepository {
    /** Plans [operations] against a fresh read of [ref]. */
    public suspend fun preview(ref: ImageRef, operations: List<EditOperation>, mode: SaveMode = SaveMode.IN_PLACE): Result<EditPreview>

    /**
     * Returns a system dialog the user must confirm before [refs] can be modified in place, or null if no
     * confirmation is needed (already writable, or nothing to ask for). Android 11+ MediaStore only.
     */
    public fun writeConsentRequest(refs: List<ImageRef>): PendingIntent?

    /**
     * Saves [operations]: backup, write to a temp file, verify, replace, re-verify, record history.
     * For [SaveMode.COPY] the edited file is stored in Pictures/ExifLab and the original is not touched.
     */
    public suspend fun save(
        ref: ImageRef,
        operations: List<EditOperation>,
        mode: SaveMode,
        batchId: String? = null,
    ): WriteOutcome

    /** Completes or rolls back writes interrupted by a crash or process death. Called once at startup. */
    public suspend fun recoverInterruptedWrites(): Int
}

public interface HistoryRepository {
    public fun observe(): Flow<List<HistoryEntry>>

    public suspend fun entry(id: Long): HistoryEntry?

    /** Restores the original bytes (or previous sidecar) for each entry. Needs write consent like a save. */
    public suspend fun restore(ids: List<Long>): List<Pair<Long, WriteError?>>

    public fun observeUsage(): Flow<BackupUsage>

    /** Deletes backups (entries stay, marked not restorable). */
    public suspend fun deleteBackups(ids: List<Long>)

    /** Applies the retention settings now. */
    public suspend fun applyRetention()

    public suspend fun clearAll()
}

public interface BatchRepository {
    /** Persists a batch and starts it in the background. Returns the batch id. */
    public suspend fun start(title: String, items: List<BatchItemSpec>): String

    public fun observe(batchId: String): Flow<BatchStatus?>

    public fun observeAll(): Flow<List<BatchStatus>>

    public suspend fun cancel(batchId: String)

    /** Re-runs the failed items of a finished batch. */
    public suspend fun retryFailed(batchId: String)
}

public interface ShareCleanRepository {
    /** Strips metadata into app-private files and returns FileProvider URIs for the share sheet. */
    public suspend fun prepare(refs: List<ImageRef>, preset: StripPreset): StripResult

    /** Deletes temporary share files older than [olderThanMillis] (0 = all). */
    public suspend fun cleanup(olderThanMillis: Long = 0)
}

public interface ExportRepository {
    /** Writes CSV or JSON for [refs] to [destination] (a SAF document URI). */
    public suspend fun exportText(refs: List<ImageRef>, format: ExportFormat, destination: Uri): Result<Unit>

    /** Writes CSV or JSON to a cache file and returns a FileProvider URI for sharing. */
    public suspend fun exportTextForSharing(refs: List<ImageRef>, format: ExportFormat): Result<Uri>
}

/** Ephemeral selection handed between screens (survives process death via a small file). */
public interface SessionRepository {
    public suspend fun create(refs: List<ImageRef>): String

    public suspend fun get(sessionId: String): List<ImageRef>?
}

public enum class AppThemeMode { SYSTEM, LIGHT, DARK }

public data class AppSettings(
    val themeMode: AppThemeMode = AppThemeMode.SYSTEM,
    val dynamicColor: Boolean = false,
    val showRawValues: Boolean = false,
    val dngWriteMode: DngWriteMode = DngWriteMode.IN_FILE,
    val sidecarNaming: SidecarNaming = SidecarNaming.BASENAME,
    /** Delete backups older than this many days (0 = keep forever). */
    val backupRetentionDays: Int = 30,
    /** Delete oldest backups beyond this size (0 = unlimited). */
    val backupMaxBytes: Long = 2L * 1024 * 1024 * 1024,
    val syncFileModifiedTime: Boolean = false,
    val lastStripPreset: StripPreset = StripPreset.LOCATION,
    /** XYZ raster tile URL template with {z}/{x}/{y}. */
    val tileUrlTemplate: String = DEFAULT_TILE_URL,
    val tileAttribution: String = DEFAULT_TILE_ATTRIBUTION,
    /** Nominatim-compatible search endpoint (base URL). */
    val searchEndpoint: String = DEFAULT_SEARCH_ENDPOINT,
    /** Optional contact (email or URL) added to the User-Agent, as the OSM policies request. */
    val networkContact: String = DEFAULT_CONTACT,
    val lastMapCenter: GeoPoint? = null,
    val onboardingDone: Boolean = false,
) {
    public companion object {
        public const val DEFAULT_TILE_URL: String = "https://tile.openstreetmap.org/{z}/{x}/{y}.png"
        public const val DEFAULT_TILE_ATTRIBUTION: String = "© OpenStreetMap contributors"
        public const val DEFAULT_SEARCH_ENDPOINT: String = "https://nominatim.openstreetmap.org"
        public const val DEFAULT_CONTACT: String = "https://github.com/FishPimp/ExifLab"
    }
}

public interface SettingsRepository {
    public val settings: Flow<AppSettings>

    public suspend fun update(transform: (AppSettings) -> AppSettings)
}
