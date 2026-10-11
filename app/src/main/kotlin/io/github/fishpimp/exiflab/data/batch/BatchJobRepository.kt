package io.github.fishpimp.exiflab.data.batch

import io.github.fishpimp.exiflab.data.photos.PhotoRef
import kotlinx.coroutines.flow.Flow
import java.util.UUID

/** Starts background processing of a job; WorkManager in the app, a fake in tests. */
fun interface BatchScheduling {
    fun enqueue(jobId: String)
}

/**
 * Batch jobs as the UI sees them: a selection becomes a [BatchJobState.Draft] (so navigation only
 * carries its id), the editor starts it, and the progress and summary screens observe it.
 */
class BatchJobRepository(
    private val dao: () -> BatchJobDao,
    private val scheduler: BatchScheduling,
    private val clock: () -> Long = System::currentTimeMillis,
    private val newId: () -> String = { UUID.randomUUID().toString() },
) {
    /** Stores [refs] (duplicates dropped, order kept) as a new draft and returns its id. */
    suspend fun createDraft(refs: List<PhotoRef>): String {
        val now = clock()
        dao().deleteDraftsBefore(now - DRAFT_LIFETIME_MS)
        val photos = refs.distinctBy { it.uri }
        val id = newId()
        val job = BatchJob(id = id, state = BatchJobState.Draft, operationJson = null, title = "", total = photos.size, createdAt = now)
        dao().insertJob(job, photos.mapIndexed { index, ref -> BatchJobItem.of(id, index, ref) })
        return id
    }

    suspend fun job(id: String): BatchJob? = dao().job(id)

    suspend fun items(id: String): List<BatchJobItem> = dao().items(id)

    fun observeJob(id: String): Flow<BatchJob?> = dao().observeJob(id)

    fun observeItems(id: String): Flow<List<BatchJobItem>> = dao().observeItems(id)

    fun observeCounts(id: String): Flow<List<BatchStatusCount>> = dao().observeCounts(id)

    fun observeRunningNames(id: String): Flow<List<String?>> = dao().observeRunningNames(id)

    fun observeActiveJobs(): Flow<List<BatchJob>> = dao().observeActiveJobs()

    /**
     * Starts draft [id] with [operation], saving copies of read-only photos to [copyFolderUri]
     * (or skipping them without one). False when the job was already started.
     */
    suspend fun start(id: String, operation: BatchOperation, title: String, copyFolderUri: String?): Boolean {
        val started = dao().start(id, BatchOperation.encode(operation), title, copyFolderUri) > 0
        if (started) scheduler.enqueue(id)
        return started
    }

    /** Asks the worker to stop after the photos in progress. */
    suspend fun cancel(id: String) = dao().requestCancel(id)

    /** Re-enqueues jobs a crash or update left queued or running; WorkManager keeps work it still has. */
    suspend fun resumeUnfinished() {
        dao().activeJobs().forEach { scheduler.enqueue(it.id) }
    }

    private companion object {
        /** Drafts are only needed while their editor is open; a day covers process death on the way. */
        const val DRAFT_LIFETIME_MS = 24L * 60 * 60 * 1000
    }
}
