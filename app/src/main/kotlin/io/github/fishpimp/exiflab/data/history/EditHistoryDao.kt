package io.github.fishpimp.exiflab.data.history

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.Query
import androidx.room.Transaction
import androidx.room.Update
import kotlinx.coroutines.flow.Flow

/** Access to the edit journal. Flows re-emit whenever the tables change. */
@Dao
interface EditHistoryDao {
    /** Every record, newest first. */
    @Query("SELECT * FROM edit_records ORDER BY createdAt DESC, id DESC")
    fun observeRecords(): Flow<List<EditRecord>>

    /** Every batch, newest first. */
    @Query("SELECT * FROM edit_batches ORDER BY createdAt DESC, id DESC")
    fun observeBatches(): Flow<List<EditBatch>>

    @Query("SELECT * FROM edit_records WHERE id = :id")
    fun observeRecord(id: String): Flow<EditRecord?>

    @Query("SELECT * FROM edit_records WHERE id = :id")
    suspend fun record(id: String): EditRecord?

    @Query("SELECT * FROM edit_batches WHERE id = :id")
    suspend fun batch(id: String): EditBatch?

    @Query("SELECT * FROM edit_records WHERE batchId = :batchId ORDER BY createdAt, id")
    suspend fun recordsInBatch(batchId: String): List<EditRecord>

    /** The newest record of [photoUri] in [batchId]; settles a batch item a crash interrupted. */
    @Query("SELECT * FROM edit_records WHERE batchId = :batchId AND photoUri = :photoUri ORDER BY createdAt DESC, id DESC LIMIT 1")
    suspend fun latestRecordInBatch(batchId: String, photoUri: String): EditRecord?

    @Query("SELECT * FROM edit_records WHERE state = 'Pending' ORDER BY createdAt")
    suspend fun pendingRecords(): List<EditRecord>

    @Query("SELECT * FROM edit_records WHERE backupPath IS NOT NULL")
    suspend fun recordsWithBackup(): List<EditRecord>

    @Query("SELECT id FROM edit_records WHERE state = 'Pending'")
    suspend fun pendingRecordIds(): List<String>

    @Insert
    suspend fun insertRecord(record: EditRecord)

    @Insert
    suspend fun insertBatch(batch: EditBatch)

    @Update
    suspend fun updateRecord(record: EditRecord)

    /** Stores [record] and, for a restore, the [restored] records it put back, in one transaction. */
    @Transaction
    suspend fun updateRecords(record: EditRecord, restored: List<EditRecord>) {
        updateRecord(record)
        restored.forEach { updateRecord(it) }
    }

    /**
     * Committed edits of the file [destinationUri] (the photo, copy or sidecar) made after
     * [after], other than [exceptId]: what restoring an earlier edit of that file undoes too.
     */
    @Query(
        "SELECT * FROM edit_records WHERE state = 'Committed' AND createdAt > :after AND id != :exceptId " +
            "AND COALESCE(targetUri, photoUri) = :destinationUri ORDER BY createdAt",
    )
    suspend fun laterCommittedRecords(destinationUri: String, after: Long, exceptId: String): List<EditRecord>

    @Query("UPDATE edit_records SET backupPath = NULL WHERE backupPath IN (:paths)")
    suspend fun clearBackupPathsChunk(paths: List<String>)

    /** Forgets [paths] on every record that points at them, e.g. after pruning. */
    @Transaction
    suspend fun clearBackupPaths(paths: Collection<String>) {
        paths.chunked(SQL_VARIABLE_CHUNK).forEach { clearBackupPathsChunk(it) }
    }

    /**
     * Forgets finished edits from before [before] that no longer have a backup, with the values
     * they changed (a removed position, a serial number). Saves in progress and failed saves whose
     * photo only its backup can fix are kept.
     */
    @Query(
        "DELETE FROM edit_records WHERE createdAt < :before AND backupPath IS NULL AND state != 'Pending' " +
            "AND NOT (state = 'Failed' AND error IN ('RollbackFailed', 'RecoveryFailed'))",
    )
    suspend fun deleteSettledRecordsBefore(before: Long): Int

    /** Forgets every edit except saves in progress, see [deleteSettledRecordsBefore]. */
    @Query("DELETE FROM edit_records WHERE state != 'Pending'")
    suspend fun deleteAllSettledRecords(): Int

    /** Batches from before [before] that no record and no running batch job refers to any more. */
    @Query(
        "DELETE FROM edit_batches WHERE createdAt < :before " +
            "AND id NOT IN (SELECT batchId FROM edit_records WHERE batchId IS NOT NULL) " +
            "AND id NOT IN (SELECT editBatchId FROM batch_jobs WHERE editBatchId IS NOT NULL AND state IN ('Queued', 'Running'))",
    )
    suspend fun deleteEmptyBatchesBefore(before: Long): Int

    /**
     * Files History may still write to: the photos and sidecars of edits that are saving, in
     * effect or failed half-way (restoring them needs access). Copies never need it again.
     */
    @Query(
        "SELECT photoUri FROM edit_records WHERE mode != 'Copy' AND state IN ('Pending', 'Committed', 'Failed') " +
            "UNION SELECT targetUri FROM edit_records WHERE targetUri IS NOT NULL AND mode != 'Copy' " +
            "AND state IN ('Pending', 'Committed', 'Failed')",
    )
    suspend fun urisHistoryMayWrite(): List<String>

    @Query("UPDATE edit_batches SET succeeded = succeeded + 1 WHERE id = :batchId")
    suspend fun countSucceeded(batchId: String)

    @Query("UPDATE edit_batches SET failed = failed + 1 WHERE id = :batchId")
    suspend fun countFailed(batchId: String)

    /** Takes back one failure, for a photo an interrupted batch writes again. */
    @Query("UPDATE edit_batches SET failed = failed - 1 WHERE id = :batchId AND failed > 0")
    suspend fun uncountFailed(batchId: String)

    @Query("UPDATE edit_batches SET cancelled = cancelled + :count WHERE id = :batchId")
    suspend fun countCancelled(batchId: String, count: Int)
}

/** Stays well below SQLite's default limit of 999 bound variables. */
private const val SQL_VARIABLE_CHUNK = 500
