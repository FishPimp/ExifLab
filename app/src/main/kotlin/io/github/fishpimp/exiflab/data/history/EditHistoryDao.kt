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

    /** Stores [record] and, for a restore, the [restored] record it put back, in one transaction. */
    @Transaction
    suspend fun updateRecords(record: EditRecord, restored: EditRecord?) {
        updateRecord(record)
        if (restored != null) updateRecord(restored)
    }

    @Query("UPDATE edit_records SET backupPath = NULL WHERE backupPath IN (:paths)")
    suspend fun clearBackupPathsChunk(paths: List<String>)

    /** Forgets [paths] on every record that points at them, e.g. after pruning. */
    @Transaction
    suspend fun clearBackupPaths(paths: Collection<String>) {
        paths.chunked(SQL_VARIABLE_CHUNK).forEach { clearBackupPathsChunk(it) }
    }

    @Query("UPDATE edit_batches SET succeeded = succeeded + 1 WHERE id = :batchId")
    suspend fun countSucceeded(batchId: String)

    @Query("UPDATE edit_batches SET failed = failed + 1 WHERE id = :batchId")
    suspend fun countFailed(batchId: String)

    @Query("UPDATE edit_batches SET cancelled = cancelled + :count WHERE id = :batchId")
    suspend fun countCancelled(batchId: String, count: Int)
}

/** Stays well below SQLite's default limit of 999 bound variables. */
private const val SQL_VARIABLE_CHUNK = 500
