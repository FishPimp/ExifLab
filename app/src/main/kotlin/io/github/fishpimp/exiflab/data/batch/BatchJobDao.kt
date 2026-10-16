package io.github.fishpimp.exiflab.data.batch

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.Query
import androidx.room.Transaction
import androidx.room.Update
import kotlinx.coroutines.flow.Flow

/** Batch jobs and their items. Flows re-emit whenever the tables change. */
@Dao
interface BatchJobDao {
    @Insert
    suspend fun insertJob(job: BatchJob)

    @Insert
    suspend fun insertItemsChunk(items: List<BatchJobItem>)

    /** A job and all of its items, in one transaction. */
    @Transaction
    suspend fun insertJob(job: BatchJob, items: List<BatchJobItem>) {
        insertJob(job)
        items.chunked(ITEM_INSERT_CHUNK).forEach { insertItemsChunk(it) }
    }

    @Update
    suspend fun updateJob(job: BatchJob)

    @Update
    suspend fun updateItem(item: BatchJobItem)

    @Query("SELECT * FROM batch_jobs WHERE id = :id")
    suspend fun job(id: String): BatchJob?

    @Query("SELECT * FROM batch_jobs WHERE id = :id")
    fun observeJob(id: String): Flow<BatchJob?>

    /** Jobs that are queued or running, oldest first. */
    @Query("SELECT * FROM batch_jobs WHERE state IN ('Queued', 'Running') ORDER BY createdAt")
    suspend fun activeJobs(): List<BatchJob>

    @Query("SELECT * FROM batch_jobs WHERE state IN ('Queued', 'Running') ORDER BY createdAt")
    fun observeActiveJobs(): Flow<List<BatchJob>>

    /** Moves a draft to [BatchJobState.Queued] with its operation; returns 0 when it is not a draft any more. */
    @Query(
        "UPDATE batch_jobs SET state = 'Queued', operationJson = :operationJson, title = :title, copyFolderUri = :copyFolderUri " +
            "WHERE id = :id AND state = 'Draft'",
    )
    suspend fun start(id: String, operationJson: String, title: String, copyFolderUri: String?): Int

    @Query("UPDATE batch_jobs SET state = 'Running', editBatchId = :editBatchId, startedAt = COALESCE(startedAt, :now) WHERE id = :id")
    suspend fun markRunning(id: String, editBatchId: String, now: Long)

    @Query("UPDATE batch_jobs SET state = :state, finishedAt = :now WHERE id = :id")
    suspend fun finish(id: String, state: BatchJobState, now: Long)

    /** Queued and running jobs with how many photos are done, for Home. */
    @Query(
        "SELECT j.id AS id, j.title AS title, j.total AS total, " +
            "(SELECT COUNT(*) FROM batch_job_items i WHERE i.jobId = j.id AND i.status NOT IN ('Pending', 'Running')) AS done " +
            "FROM batch_jobs j WHERE j.state IN ('Queued', 'Running') ORDER BY j.createdAt",
    )
    fun observeActiveProgress(): Flow<List<ActiveBatch>>

    @Query("SELECT cancelRequested FROM batch_jobs WHERE id = :id")
    suspend fun isCancelRequested(id: String): Boolean?

    @Query("UPDATE batch_jobs SET cancelRequested = 1 WHERE id = :id AND state IN ('Queued', 'Running')")
    suspend fun requestCancel(id: String)

    /** Drafts nobody started, e.g. after the editor was left; their items go with them. */
    @Query("DELETE FROM batch_jobs WHERE state = 'Draft' AND createdAt < :before")
    suspend fun deleteDraftsBefore(before: Long): Int

    @Query("SELECT * FROM batch_job_items WHERE jobId = :jobId ORDER BY position")
    suspend fun items(jobId: String): List<BatchJobItem>

    @Query("SELECT * FROM batch_job_items WHERE jobId = :jobId ORDER BY position")
    fun observeItems(jobId: String): Flow<List<BatchJobItem>>

    @Query("SELECT * FROM batch_job_items WHERE jobId = :jobId AND status = :status ORDER BY position")
    suspend fun itemsWithStatus(jobId: String, status: BatchItemStatus): List<BatchJobItem>

    @Query("SELECT status, COUNT(*) AS count FROM batch_job_items WHERE jobId = :jobId GROUP BY status")
    fun observeCounts(jobId: String): Flow<List<BatchStatusCount>>

    /** Names of the photos being written right now. */
    @Query("SELECT displayName FROM batch_job_items WHERE jobId = :jobId AND status = 'Running' ORDER BY position")
    fun observeRunningNames(jobId: String): Flow<List<String?>>

    @Query("SELECT status, COUNT(*) AS count FROM batch_job_items WHERE jobId = :jobId GROUP BY status")
    suspend fun counts(jobId: String): List<BatchStatusCount>

    /** The photos and copy folders of jobs that have not ended, drafts included: they still need their grants. */
    @Query(
        "SELECT photoUri FROM batch_job_items WHERE jobId IN (SELECT id FROM batch_jobs WHERE state IN ('Draft', 'Queued', 'Running')) " +
            "UNION SELECT copyFolderUri FROM batch_jobs WHERE copyFolderUri IS NOT NULL AND state IN ('Draft', 'Queued', 'Running')",
    )
    suspend fun urisOfUnfinishedJobs(): List<String>

    /** Written photos whose file type could not hold every change. */
    @Query("SELECT COUNT(*) FROM batch_job_items WHERE jobId = :jobId AND notes IS NOT NULL")
    suspend fun countWithNotes(jobId: String): Int

    /** Skips every item that has not started; returns how many. */
    @Query(
        "UPDATE batch_job_items SET status = 'Skipped', reason = :reason, finishedAt = :now " +
            "WHERE jobId = :jobId AND status = 'Pending'",
    )
    suspend fun skipPending(jobId: String, reason: BatchItemReason, now: Long): Int
}

/** Rows per insert statement; each row binds 16 values, well below SQLite's variable limit. */
private const val ITEM_INSERT_CHUNK = 50
