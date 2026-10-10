package io.github.fishpimp.exiflab.data.save

import io.github.fishpimp.exiflab.data.backup.BackupStore
import io.github.fishpimp.exiflab.data.backup.ContentHash
import io.github.fishpimp.exiflab.data.backup.Sha256
import io.github.fishpimp.exiflab.data.history.EditBatch
import io.github.fishpimp.exiflab.data.history.EditHistoryDao
import io.github.fishpimp.exiflab.data.history.EditMode
import io.github.fishpimp.exiflab.data.history.EditRecord
import io.github.fishpimp.exiflab.data.history.EditState
import io.github.fishpimp.exiflab.data.history.FieldDiffCodec
import io.github.fishpimp.exiflab.data.history.SaveError
import io.github.fishpimp.exiflab.data.photos.PhotoRef
import io.github.fishpimp.exiflab.data.photos.format
import io.github.fishpimp.exiflab.data.settings.SidecarNaming
import io.github.fishpimp.exiflab.metadata.edit.FieldDiff
import io.github.fishpimp.exiflab.metadata.write.ImageDataMismatchException
import io.github.fishpimp.exiflab.metadata.write.MetadataChanges
import io.github.fishpimp.exiflab.metadata.write.UnsupportedEditException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileNotFoundException
import java.io.IOException
import java.util.UUID

/**
 * Saves metadata edits safely, and puts originals back.
 *
 * Every write goes through one journaled pipeline:
 * 1. hash the target as it is and journal a [EditState.Pending] [EditRecord];
 * 2. back up the target (when it has content) into the [BackupStore], checking the copy's hash;
 * 3. produce the new content in a temp file (the metadata writer verifies image data; the
 *    result is re-read) and record its hash as [EditRecord.newSha256];
 * 4. overwrite the target (`"rwt"`, flushed and synced) and read it back: the hash must match;
 * 5. mark the record [EditState.Committed].
 * A failure once the target was opened writes the backup back and verifies it
 * ([EditState.RolledBack]); if even that fails the record is [EditState.Failed] with
 * [SaveError.RollbackFailed] and the backup is kept for History. Temp files are always deleted.
 * Once journaled, a save runs to the end even when its coroutine is cancelled.
 *
 * One save or restore runs at a time per photo URI. Safe to call from any thread or worker.
 *
 * @param afterSave called after every committed save or restore, e.g. to prune old backups.
 */
class PhotoSaver(
    private val dao: EditHistoryDao,
    private val backups: BackupStore,
    private val documents: DocumentAccess,
    private val engine: WriteEngine,
    private val tempDirectory: File,
    private val sidecarNaming: suspend () -> SidecarNaming,
    private val afterSave: () -> Unit = {},
    private val clock: () -> Long = System::currentTimeMillis,
    private val newId: () -> String = { UUID.randomUUID().toString() },
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO,
) {
    private val photoLocks = HashMap<String, Mutex>()

    /**
     * Applies [changes] to [ref] and writes the result to [target], journaled in History with
     * [diff] (what the user sees changing) and [summary] (defaults to the changed field names).
     * With a [batchId] the batch's success and failure counts are updated.
     */
    suspend fun save(
        ref: PhotoRef,
        changes: MetadataChanges,
        diff: List<FieldDiff>,
        target: SaveTarget,
        batchId: String? = null,
        summary: String? = null,
    ): SaveOutcome = withContext(ioDispatcher) {
        val draft = Draft(ref, diff, summary ?: summarize(diff), batchId)
        val outcome = when (target) {
            SaveTarget.InPlace -> saveInPlace(draft, changes)
            is SaveTarget.Copy -> saveCopy(draft, changes, target.destinationUri)
            is SaveTarget.Sidecar -> saveSidecar(draft, changes, target.destinationUri)
        }
        if (outcome is SaveOutcome.Saved) afterSave()
        outcome
    }

    /**
     * Writes the backup of [recordId] back to its target, through the same journaled, verified
     * pipeline. The target's current state is backed up first, so the restore is itself a record
     * that can be restored (undone). On success [recordId] becomes [EditState.Restored].
     */
    suspend fun restoreOriginal(recordId: String, batchId: String? = null): SaveOutcome = withContext(ioDispatcher) {
        val photoUri = dao.record(recordId)?.photoUri ?: return@withContext SaveOutcome.Rejected(SaveRejection.NotFound)
        val outcome = withPhotoLock(photoUri) { restoreLocked(recordId, batchId) }
        if (outcome is SaveOutcome.Saved) afterSave()
        outcome
    }

    /** Restores every restorable photo of a batch, as a new batch that can itself be undone. */
    suspend fun restoreBatch(batchId: String): BatchRestoreResult = withContext(ioDispatcher) {
        val batch = dao.batch(batchId) ?: return@withContext BatchRestoreResult(null, 0, 0)
        val records = dao.recordsInBatch(batchId).filter { it.canRestore }
        if (records.isEmpty()) return@withContext BatchRestoreResult(null, 0, 0)
        val restoreBatch = EditBatch(
            id = newId(),
            title = batch.title,
            createdAt = clock(),
            total = records.size,
            restoresBatchId = batch.id,
        )
        dao.insertBatch(restoreBatch)
        var restored = 0
        var failed = 0
        for (record in records) {
            when (val outcome = restoreOriginal(record.id, restoreBatch.id)) {
                is SaveOutcome.Saved -> restored++
                else -> {
                    failed++
                    // Outcomes without a journaled record are not counted by the pipeline.
                    val journaled = outcome is SaveOutcome.RolledBack || (outcome is SaveOutcome.Failed && outcome.recordId != null)
                    if (!journaled) dao.countFailed(restoreBatch.id)
                }
            }
        }
        BatchRestoreResult(restoreBatch.id, restored, failed)
    }

    /** Creates a batch that later [save] calls can join. */
    suspend fun startBatch(title: String, total: Int): String {
        val id = newId()
        dao.insertBatch(EditBatch(id = id, title = title, createdAt = clock(), total = total))
        return id
    }

    /**
     * Resolves records left [EditState.Pending] by a crash: a target that holds the new content
     * is [EditState.Committed]; one still holding the original is [EditState.RolledBack]; anything
     * else gets its backup written back ([EditState.RolledBack]) or, when that is impossible, is
     * [EditState.Failed] with the backup kept. Returns how many records were resolved.
     */
    suspend fun recoverInterrupted(): Int = withContext(ioDispatcher + NonCancellable) {
        val pending = dao.pendingRecords()
        for (stale in pending) {
            withPhotoLock(stale.photoUri) {
                val record = dao.record(stale.id)
                if (record != null && record.state == EditState.Pending) recover(record)
            }
        }
        pending.size
    }

    private suspend fun saveInPlace(draft: Draft, changes: MetadataChanges): SaveOutcome {
        val ref = draft.ref
        val format = ref.format
        if (format.isRaw) return SaveOutcome.Rejected(SaveRejection.UseSidecar)
        if (!ref.writable) return SaveOutcome.Rejected(SaveRejection.ReadOnly)
        if (!engine.canWrite(format)) return SaveOutcome.Rejected(SaveRejection.UnsupportedFormat)
        return withPhotoLock(ref.uri) {
            var skipped = emptyList<String>()
            val outcome = journaled(newRecord(draft, EditMode.InPlace, targetUri = null, targetName = null), requireBackup = true) { original, temp ->
                val source = FileImageSource(checkNotNull(original), ref.displayName)
                val result = temp.outputStream().use { out -> engine.write(source, changes, out) }
                skipped = result.skipped
                // The writer sniffs the container, so its format wins over the file extension.
                engine.verifyReadable(FileImageSource(temp, ref.displayName), result.format)
                NewContent.Written
            }
            outcome.withSkipped(skipped)
        }
    }

    private suspend fun saveCopy(draft: Draft, changes: MetadataChanges, destinationUri: String): SaveOutcome {
        val ref = draft.ref
        val format = ref.format
        if (format.isRaw) return SaveOutcome.Rejected(SaveRejection.UseSidecar)
        if (!engine.canWrite(format)) return SaveOutcome.Rejected(SaveRejection.UnsupportedFormat)
        return withPhotoLock(ref.uri) {
            val targetName = runCatching { documents.displayName(destinationUri) }.getOrNull()
            var skipped = emptyList<String>()
            val record = newRecord(draft, EditMode.Copy, targetUri = destinationUri, targetName = targetName)
            val outcome = journaled(record, requireBackup = false) { _, temp ->
                val source = DocumentImageSource(documents, ref.uri, ref.displayName, ref.size)
                val result = temp.outputStream().use { out -> engine.write(source, changes, out) }
                skipped = result.skipped
                engine.verifyReadable(FileImageSource(temp, targetName ?: ref.displayName), result.format)
                NewContent.Written
            }
            outcome.withSkipped(skipped)
        }
    }

    private suspend fun saveSidecar(draft: Draft, changes: MetadataChanges, destinationUri: String?): SaveOutcome {
        val ref = draft.ref
        val name = sidecarNaming().sidecarName(ref.displayName?.takeIf { it.isNotBlank() } ?: DEFAULT_PHOTO_NAME)
        return withPhotoLock(ref.uri) {
            val target = destinationUri ?: run {
                val parent = ref.parentDocumentUri ?: return@withPhotoLock SaveOutcome.NeedsSidecarDestination(name)
                try {
                    documents.findChild(parent, name) ?: documents.createChild(parent, name, SIDECAR_MIME_TYPE)
                } catch (_: Exception) {
                    // A read-only folder or a provider without create support: let the user pick a place.
                    return@withPhotoLock SaveOutcome.NeedsSidecarDestination(name)
                }
            }
            val targetName = runCatching { documents.displayName(target) }.getOrNull() ?: name
            journaled(newRecord(draft, EditMode.Sidecar, targetUri = target, targetName = targetName), requireBackup = false) { original, temp ->
                val existing = original?.readBytes()
                temp.outputStream().use { out -> engine.writeSidecar(existing, changes, out) }
                if (temp.length() == 0L) throw WrittenFileCheckException("The sidecar writer produced nothing")
                NewContent.Written
            }
        }
    }

    private suspend fun restoreLocked(recordId: String, batchId: String?): SaveOutcome {
        val record = dao.record(recordId) ?: return SaveOutcome.Rejected(SaveRejection.NotFound)
        if (!record.canRestore) return SaveOutcome.Rejected(SaveRejection.NotRestorable)
        val backupFile = record.backupPath?.let(backups::file)
        if (backupFile != null) {
            val intact = runCatching { Sha256.of(backupFile) }.getOrNull()?.sha256 == record.originalSha256
            if (!intact) return SaveOutcome.Failed(recordId = null, error = SaveError.BackupDamaged, backupKept = backupFile.isFile)
        }
        val target = when {
            // A sidecar that a restore removed is created again to undo that restore.
            backupFile != null && record.mode == EditMode.Sidecar && !targetExists(record.destinationUri) ->
                recreateSidecar(record) ?: return SaveOutcome.Failed(recordId = null, error = SaveError.WriteFailed)
            else -> record.destinationUri
        }
        val restore = record.copy(
            id = newId(),
            batchId = batchId,
            targetUri = if (record.mode == EditMode.InPlace) null else target,
            state = EditState.Pending,
            backupPath = null,
            originalSize = 0,
            originalSha256 = Sha256.EMPTY,
            newSha256 = null,
            diffJson = FieldDiffCodec.encode(FieldDiffCodec.invert(FieldDiffCodec.decode(record.diffJson))),
            createdAt = clock(),
            completedAt = null,
            error = null,
            restoresRecordId = record.id,
        )
        return journaled(restore, requireBackup = false, restores = record) { _, temp ->
            if (backupFile == null) {
                NewContent.Absent
            } else {
                backupFile.inputStream().use { input -> temp.outputStream().use { input.copyTo(it) } }
                NewContent.Written
            }
        }
    }

    private fun recreateSidecar(record: EditRecord): String? {
        val parent = record.parentDocumentUri ?: return null
        val name = record.targetName ?: return null
        return runCatching { documents.findChild(parent, name) ?: documents.createChild(parent, name, SIDECAR_MIME_TYPE) }.getOrNull()
    }

    /**
     * The journaled write pipeline described on the class. [produce] writes the new content into
     * the temp file (or asks for the target to be removed) given the verified backup of the target.
     *
     * @param requireBackup fail unless the target exists with content (in-place edits).
     * @param restores the record a restore puts back; marked [EditState.Restored] on commit.
     */
    private suspend fun journaled(
        record: EditRecord,
        requireBackup: Boolean,
        restores: EditRecord? = null,
        produce: (original: File?, temp: File) -> NewContent,
    ): SaveOutcome = withContext(NonCancellable) {
        val destination = record.destinationUri
        val before = try {
            hashTarget(destination)
        } catch (_: Exception) {
            return@withContext SaveOutcome.Failed(recordId = null, error = SaveError.ReadFailed)
        }
        if (requireBackup && (before == null || before.size == 0L)) {
            return@withContext SaveOutcome.Failed(recordId = null, error = SaveError.ReadFailed)
        }
        val pre = before ?: ContentHash.Empty
        val journal = Journal(record.copy(originalSize = pre.size, originalSha256 = pre.sha256), pre)
        dao.insertRecord(journal.record)

        val temp = try {
            tempDirectory.mkdirs()
            File.createTempFile("save-", ".tmp", tempDirectory)
        } catch (_: IOException) {
            return@withContext fail(journal, SaveError.WriteFailed)
        }
        try {
            // Back up the target, checking that the copy is what was hashed above.
            if (pre.size > 0) {
                val stored = try {
                    documents.openInput(destination).use { input -> backups.create(journal.record.id, input) }
                } catch (_: Exception) {
                    return@withContext fail(journal, SaveError.BackupFailed)
                }
                journal.backup = backups.file(stored.path)
                journal.update(journal.record.copy(backupPath = stored.path))
                if (stored.hash != pre) return@withContext fail(journal, SaveError.SourceChanged)
            }

            val content = try {
                produce(journal.backup, temp)
            } catch (e: Throwable) {
                if (e is VirtualMachineError) throw e
                return@withContext fail(journal, errorFor(e))
            }
            val expected = try {
                if (content == NewContent.Absent) ContentHash.Empty else Sha256.of(temp)
            } catch (_: IOException) {
                return@withContext fail(journal, SaveError.WriteFailed)
            }
            journal.update(journal.record.copy(newSha256 = expected.sha256))

            try {
                when (content) {
                    NewContent.Absent -> if (!documents.delete(destination)) return@withContext fail(journal, SaveError.WriteFailed)
                    NewContent.Written -> documents.overwrite(destination) { out -> temp.inputStream().use { it.copyTo(out) } }
                }
            } catch (_: TargetUnavailableException) {
                return@withContext fail(journal, SaveError.WriteFailed)
            } catch (_: Exception) {
                return@withContext rollback(journal, SaveError.WriteFailed)
            }

            val after = try {
                hashTarget(destination) ?: ContentHash.Empty
            } catch (_: Exception) {
                return@withContext rollback(journal, SaveError.VerificationFailed)
            }
            if (after != expected) return@withContext rollback(journal, SaveError.VerificationFailed)

            val committed = journal.record.copy(state = EditState.Committed, completedAt = clock())
            val restored = restores?.let { dao.record(it.id) }?.copy(state = EditState.Restored, completedAt = clock())
            dao.updateRecords(committed, restored)
            countBatch(committed, success = true)
            SaveOutcome.Saved(committed.id, committed.mode, destination)
        } finally {
            temp.delete()
        }
    }

    /** Nothing was changed: the record fails and its backup, now pointless, is deleted. */
    private suspend fun fail(journal: Journal, error: SaveError): SaveOutcome {
        val record = journal.record
        // A copy or new sidecar that is still empty would only be clutter.
        if (record.mode != EditMode.InPlace && journal.pre.size == 0L) runCatching { documents.delete(record.destinationUri) }
        discardBackup(record)
        val failed = record.copy(state = EditState.Failed, error = error.name, completedAt = clock(), backupPath = null)
        dao.updateRecord(failed)
        countBatch(failed, success = false)
        return SaveOutcome.Failed(failed.id, error, backupKept = false)
    }

    /** The target may be partially written: put it back as it was and verify that. */
    private suspend fun rollback(journal: Journal, error: SaveError): SaveOutcome {
        val record = journal.record
        val putBack = runCatching { putBack(record.destinationUri, journal.backup, journal.pre) }.getOrDefault(false)
        val finished = if (putBack) {
            discardBackup(record)
            record.copy(state = EditState.RolledBack, error = error.name, completedAt = clock(), backupPath = null)
        } else {
            record.copy(state = EditState.Failed, error = SaveError.RollbackFailed.name, completedAt = clock())
        }
        dao.updateRecord(finished)
        countBatch(finished, success = false)
        return if (putBack) {
            SaveOutcome.RolledBack(finished.id, error)
        } else {
            SaveOutcome.Failed(finished.id, SaveError.RollbackFailed, backupKept = finished.backupPath != null)
        }
    }

    /** Writes [backup] (or nothing, for a target that was empty) back to [uri] and checks it matches [pre]. */
    private fun putBack(uri: String, backup: File?, pre: ContentHash): Boolean {
        if (pre.size == 0L) {
            if (!documents.delete(uri)) documents.overwrite(uri) { }
            return (hashTarget(uri) ?: ContentHash.Empty) == ContentHash.Empty
        }
        if (backup == null || !backup.isFile) return false
        documents.overwrite(uri) { out -> backup.inputStream().use { it.copyTo(out) } }
        return hashTarget(uri) == pre
    }

    private suspend fun recover(record: EditRecord) {
        val destination = record.destinationUri
        val pre = ContentHash(record.originalSize, record.originalSha256)
        val now = try {
            hashTarget(destination) ?: ContentHash.Empty
        } catch (_: Exception) {
            null
        }
        val backup = record.backupPath?.let(backups::file)?.takeIf { it.isFile }
        when {
            now == null -> finishRecovered(record, EditState.Failed, SaveError.RecoveryFailed)
            record.newSha256 != null && now.sha256 == record.newSha256 -> {
                val committed = record.copy(state = EditState.Committed, completedAt = clock())
                val restored = record.restoresRecordId?.let { dao.record(it) }?.copy(state = EditState.Restored, completedAt = clock())
                dao.updateRecords(committed, restored)
                countBatch(committed, success = true)
            }
            now == pre -> finishRecovered(record, EditState.RolledBack, SaveError.Interrupted)
            runCatching { putBack(destination, backup, pre) }.getOrDefault(false) ->
                finishRecovered(record, EditState.RolledBack, SaveError.Interrupted)
            else -> finishRecovered(record, EditState.Failed, SaveError.RecoveryFailed)
        }
    }

    private suspend fun finishRecovered(record: EditRecord, state: EditState, error: SaveError) {
        val keepBackup = state == EditState.Failed
        if (!keepBackup) discardBackup(record)
        val finished = record.copy(
            state = state,
            error = error.name,
            completedAt = clock(),
            backupPath = if (keepBackup) record.backupPath else null,
        )
        dao.updateRecord(finished)
        countBatch(finished, success = false)
    }

    private fun discardBackup(record: EditRecord) {
        record.backupPath?.let { runCatching { backups.delete(it) } }
    }

    private suspend fun countBatch(record: EditRecord, success: Boolean) {
        val batchId = record.batchId ?: return
        if (success) dao.countSucceeded(batchId) else dao.countFailed(batchId)
    }

    /** Size and hash of what [uri] holds now; null when it does not exist. */
    private fun hashTarget(uri: String): ContentHash? = try {
        documents.openInput(uri).use(Sha256::of)
    } catch (_: FileNotFoundException) {
        null
    }

    private fun targetExists(uri: String): Boolean = runCatching { hashTarget(uri) != null }.getOrDefault(true)

    private fun newRecord(draft: Draft, mode: EditMode, targetUri: String?, targetName: String?): EditRecord {
        val ref = draft.ref
        return EditRecord(
            id = newId(),
            batchId = draft.batchId,
            photoUri = ref.uri,
            displayName = ref.displayName,
            mimeType = ref.mimeType,
            photoOrigin = ref.origin,
            parentDocumentUri = ref.parentDocumentUri,
            mode = mode,
            targetUri = targetUri,
            targetName = targetName,
            state = EditState.Pending,
            backupPath = null,
            originalSize = 0,
            originalSha256 = Sha256.EMPTY,
            newSha256 = null,
            diffJson = FieldDiffCodec.encode(draft.diff),
            summary = draft.summary,
            createdAt = clock(),
            completedAt = null,
            error = null,
            restoresRecordId = null,
        )
    }

    private suspend inline fun <T> withPhotoLock(uri: String, block: () -> T): T {
        val mutex = synchronized(photoLocks) { photoLocks.getOrPut(uri) { Mutex() } }
        return mutex.withLock { block() }
    }

    /** The record of a pipeline run, updated in the journal as the run progresses. */
    private inner class Journal(var record: EditRecord, val pre: ContentHash) {
        var backup: File? = null

        suspend fun update(next: EditRecord) {
            record = next
            dao.updateRecord(next)
        }
    }

    private class Draft(val ref: PhotoRef, val diff: List<FieldDiff>, val summary: String, val batchId: String?)

    private enum class NewContent { Written, Absent }

    private companion object {
        const val DEFAULT_PHOTO_NAME = "photo"
        const val SUMMARY_LABELS = 4

        fun summarize(diff: List<FieldDiff>): String {
            val labels = diff.map { it.label }.distinct()
            val shown = labels.take(SUMMARY_LABELS).joinToString(", ")
            return if (labels.size > SUMMARY_LABELS) "$shown, +${labels.size - SUMMARY_LABELS}" else shown
        }

        fun errorFor(e: Throwable): SaveError = when (e) {
            is ImageDataMismatchException -> SaveError.ImageDataChanged
            is UnsupportedEditException -> SaveError.UnsupportedEdit
            is WrittenFileCheckException -> SaveError.CheckFailed
            is IOException -> SaveError.ReadFailed
            else -> SaveError.Unknown
        }

        fun SaveOutcome.withSkipped(skipped: List<String>): SaveOutcome =
            if (this is SaveOutcome.Saved && skipped.isNotEmpty()) copy(skipped = skipped) else this
    }
}
