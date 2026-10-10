package io.github.fishpimp.exiflab.data.backup

import io.github.fishpimp.exiflab.data.history.EditHistoryDao
import io.github.fishpimp.exiflab.data.history.EditState
import io.github.fishpimp.exiflab.data.settings.BackupRetention
import io.github.fishpimp.exiflab.data.settings.DEFAULT_BACKUP_SIZE_CAP_BYTES
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

/** What a prune or delete-all run removed. */
data class PruneResult(val deletedFiles: Int, val freedBytes: Long)

/**
 * Keeps the backup folder in line with the edit journal and the user's retention setting:
 * prunes by age and size (see [BackupPruner]), removes files no record needs, and deletes
 * backups on request. Backups of saves in progress are never touched.
 */
class BackupManager(
    private val dao: EditHistoryDao,
    private val store: BackupStore,
    private val retention: suspend () -> BackupRetention,
    private val sizeCapBytes: Long = DEFAULT_BACKUP_SIZE_CAP_BYTES,
    private val clock: () -> Long = System::currentTimeMillis,
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO,
) {
    private val mutex = Mutex()

    /** Bytes the backups take, or null until measured. */
    val usedBytes: StateFlow<Long?> get() = store.usedBytes

    /** The size cap pruning keeps the backups under. */
    val sizeCap: Long get() = sizeCapBytes

    suspend fun refreshUsage(): Long = withContext(ioDispatcher) { store.refreshUsage() }

    /** Size of the backup at [path], or null when it is gone. */
    suspend fun backupSize(path: String): Long? = withContext(ioDispatcher) {
        runCatching { store.file(path).takeIf { it.isFile }?.length() }.getOrNull()
    }

    /** Applies the retention and size cap, and removes leftovers no record refers to. */
    suspend fun prune(): PruneResult = mutex.withLock {
        withContext(ioDispatcher) {
            // List files before reading the journal: a backup created after this listing is not
            // seen at all, and one created before it belongs to a record that is already stored.
            val files = store.files()
            val partials = store.partialFiles()
            val records = dao.recordsWithBackup()
            val pendingIds = dao.pendingRecordIds().toSet()
            val byPath = records.associateBy { it.backupPath!! }
            val sizes = files.associate { it.path to it.size }

            var deleted = 0
            var freed = 0L
            fun remove(path: String, size: Long) {
                if (store.delete(path)) {
                    deleted++
                    freed += size
                }
            }

            files.filter { it.path !in byPath && store.idOf(it.path) !in pendingIds }.forEach { remove(it.path, it.size) }
            partials.filter { store.idOf(it.path) !in pendingIds }.forEach { remove(it.path, it.size) }

            // Records whose backup vanished from disk can no longer restore; forget the path.
            val missing = records.filter { it.state != EditState.Pending && it.backupPath!! !in sizes }.map { it.backupPath!! }

            val candidates = records.mapNotNull { record ->
                val path = record.backupPath!!
                val size = sizes[path] ?: return@mapNotNull null
                PruneCandidate(
                    path = path,
                    size = size,
                    createdAt = record.createdAt,
                    targetKey = record.destinationUri,
                    protected = record.state == EditState.Pending || record.needsRestore,
                )
            }
            val selected = BackupPruner.select(candidates, retention(), sizeCapBytes, clock())
            selected.forEach { remove(it, sizes[it] ?: 0L) }
            dao.clearBackupPaths(selected + missing)
            store.refreshUsage()
            PruneResult(deleted, freed)
        }
    }

    /**
     * Deletes every backup except those of saves in progress. Restoring originals of the edits
     * made so far is no longer possible afterwards.
     */
    suspend fun deleteAll(): PruneResult = mutex.withLock {
        withContext(ioDispatcher) {
            val files = store.files() + store.partialFiles()
            val pendingIds = dao.pendingRecordIds().toSet()
            val records = dao.recordsWithBackup().filter { it.state != EditState.Pending }
            var deleted = 0
            var freed = 0L
            files.filter { store.idOf(it.path) !in pendingIds }.forEach { file ->
                if (store.delete(file.path)) {
                    deleted++
                    freed += file.size
                }
            }
            dao.clearBackupPaths(records.map { it.backupPath!! })
            store.refreshUsage()
            PruneResult(deleted, freed)
        }
    }

    /** Deletes the backup of one record. False when the record is saving right now or unknown. */
    suspend fun deleteBackup(recordId: String): Boolean = mutex.withLock {
        withContext(ioDispatcher) {
            val record = dao.record(recordId) ?: return@withContext false
            if (record.state == EditState.Pending) return@withContext false
            val path = record.backupPath ?: return@withContext true
            val gone = store.delete(path)
            if (gone) dao.updateRecord(record.copy(backupPath = null))
            gone
        }
    }
}
