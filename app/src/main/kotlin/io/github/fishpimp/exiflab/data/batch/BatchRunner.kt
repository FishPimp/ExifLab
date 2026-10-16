package io.github.fishpimp.exiflab.data.batch

import io.github.fishpimp.exiflab.data.history.EditRecord
import io.github.fishpimp.exiflab.data.history.EditState
import io.github.fishpimp.exiflab.data.history.SaveError
import io.github.fishpimp.exiflab.data.photos.PhotoRef
import io.github.fishpimp.exiflab.data.photos.format
import io.github.fishpimp.exiflab.data.save.SaveOutcome
import io.github.fishpimp.exiflab.data.save.SaveRejection
import io.github.fishpimp.exiflab.data.save.SaveTarget
import io.github.fishpimp.exiflab.metadata.ImageFormat
import io.github.fishpimp.exiflab.metadata.edit.ChangePlanner
import io.github.fishpimp.exiflab.metadata.edit.EditPlan
import io.github.fishpimp.exiflab.metadata.edit.PlanWarning
import io.github.fishpimp.exiflab.metadata.model.MetadataReport
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

/** What the batch runner needs from the save pipeline and History; the app's [io.github.fishpimp.exiflab.data.save.PhotoSaver] or a fake. */
interface BatchSaving {
    fun canWrite(format: ImageFormat): Boolean

    /** Creates the History batch the saves join. */
    suspend fun startBatch(title: String, total: Int): String

    suspend fun save(ref: PhotoRef, plan: EditPlan, target: SaveTarget, batchId: String): SaveOutcome

    /** Resolves saves a crash left half-way (see [io.github.fishpimp.exiflab.data.save.PhotoSaver.recoverInterrupted]). */
    suspend fun recoverInterrupted()

    /** The newest History record of [photoUri] in [batchId], if any. */
    suspend fun latestRecord(batchId: String, photoUri: String): EditRecord?

    /** Counts a failure the save pipeline did not journal (it counts its own). */
    suspend fun countFailed(batchId: String)

    /** Takes back a failure, for a photo written again after an interruption. */
    suspend fun uncountFailed(batchId: String)

    suspend fun countCancelled(batchId: String, count: Int)
}

/** Creates documents in the folder chosen for copies. Blocking. */
interface BatchDestinations {
    /** Creates an empty document called [name] in [folderUri], renamed ("name (1).jpg") when the name is taken. */
    fun createCopy(folderUri: String, name: String, mimeType: String): String

    /** Finds or creates the sidecar called [name] in [folderUri]. */
    fun sidecarIn(folderUri: String, name: String): String

    /** Deletes [uri] when it exists and is still empty: a copy an interrupted run created but never wrote. */
    fun deleteIfEmpty(uri: String)
}

/**
 * Counts of a running job, for the notification and the worker's progress. [withNotes] counts
 * written photos whose file type could not hold every change.
 */
data class BatchProgress(val total: Int, val done: Int, val failed: Int, val skipped: Int, val withNotes: Int = 0) {
    companion object {
        fun of(total: Int, counts: List<BatchStatusCount>): BatchProgress {
            val byStatus = counts.associate { it.status to it.count }
            return BatchProgress(
                total = total,
                done = counts.filter { it.status.isFinal }.sumOf { it.count },
                failed = byStatus[BatchItemStatus.Failed] ?: 0,
                skipped = byStatus[BatchItemStatus.Skipped] ?: 0,
            )
        }
    }
}

/**
 * Runs a [BatchJob]: plans the operation against each photo's own metadata and saves it through
 * the journaled save pipeline, [concurrency] photos at a time. Every item's status is written to
 * Room as it changes, so:
 * - a job interrupted by the system or a crash resumes with the items that did not finish (an
 *   item caught mid-save is settled from the write journal: committed saves count as done);
 * - Cancel ([BatchJobDao.requestCancel]) lets the photos in progress finish and skips the rest.
 * Only the items' small rows are held in memory; photos are streamed by the reader and the saver.
 */
class BatchRunner(
    private val jobs: BatchJobDao,
    private val saving: BatchSaving,
    private val newDestinations: () -> BatchDestinations,
    private val readReport: suspend (PhotoRef) -> MetadataReport,
    private val planner: () -> ChangePlanner,
    /** The private copies of shared photos; each is deleted once its photo is done, see [BatchInputStore]. */
    private val inputs: BatchInputStore? = null,
    private val concurrency: Int = DEFAULT_CONCURRENCY,
    private val clock: () -> Long = System::currentTimeMillis,
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO,
) {
    /**
     * Processes the job's remaining items. Returns the job as it ended, or null when it does not
     * exist. Drafts and finished jobs are returned unchanged.
     */
    suspend fun run(jobId: String, onProgress: suspend (BatchProgress) -> Unit = {}): BatchJob? {
        val job = jobs.job(jobId) ?: return null
        if (job.state == BatchJobState.Draft || job.state.isDone) return job
        val batchId = job.editBatchId ?: saving.startBatch(job.title, job.total)
        jobs.markRunning(jobId, batchId, clock())
        settleInterrupted(jobId, batchId, newDestinations())

        val operation = job.operation
        if (operation == null) {
            // Nothing sensible can be done with an operation this version cannot read.
            jobs.skipPending(jobId, BatchItemReason.PlanFailed, clock())
            jobs.finish(jobId, BatchJobState.Finished, clock())
            return jobs.job(jobId)
        }

        val destinations = newDestinations()
        val queue = ArrayDeque(jobs.itemsWithStatus(jobId, BatchItemStatus.Pending))
        val queueLock = Mutex()
        var stopped = false
        onProgress(progress(job))
        coroutineScope {
            repeat(concurrency.coerceAtLeast(1)) {
                launch {
                    while (true) {
                        val item = queueLock.withLock {
                            if (!stopped && jobs.isCancelRequested(jobId) == true) stopped = true
                            if (stopped) null else queue.removeFirstOrNull()
                        } ?: break
                        process(item, operation, batchId, job.copyFolderUri, destinations)
                        onProgress(progress(job))
                    }
                }
            }
        }

        val cancelled = stopped || jobs.isCancelRequested(jobId) == true
        val now = clock()
        if (cancelled) {
            val skipped = jobs.skipPending(jobId, BatchItemReason.Cancelled, now)
            if (skipped > 0) saving.countCancelled(batchId, skipped)
        }
        jobs.finish(jobId, if (cancelled) BatchJobState.Cancelled else BatchJobState.Finished, now)
        inputs?.let { withContext(ioDispatcher) { it.releaseJob(jobId) } }
        onProgress(progress(job))
        return jobs.job(jobId)
    }

    private suspend fun progress(job: BatchJob) = BatchProgress.of(job.total, jobs.counts(job.id))

    /**
     * Items left [BatchItemStatus.Running] by an interruption: a committed save is done; one that
     * never started, or that recovery put back, runs again.
     */
    private suspend fun settleInterrupted(jobId: String, batchId: String, destinations: BatchDestinations) {
        val interrupted = jobs.itemsWithStatus(jobId, BatchItemStatus.Running)
        if (interrupted.isEmpty()) return
        saving.recoverInterrupted()
        for (item in interrupted) {
            val record = saving.latestRecord(batchId, item.photoUri)
            val settled = when {
                record == null -> {
                    item.targetUri?.let { withContext(ioDispatcher) { runCatching { destinations.deleteIfEmpty(it) } } }
                    item.copy(status = BatchItemStatus.Pending, targetUri = null)
                }
                record.state == EditState.Committed || record.state == EditState.Restored -> item.copy(
                    status = BatchItemStatus.of(record.mode),
                    recordId = record.id,
                    targetUri = record.targetUri,
                    finishedAt = record.completedAt ?: clock(),
                )
                record.needsRestore -> item.copy(
                    status = BatchItemStatus.Failed,
                    reason = BatchItemReason.SaveFailed,
                    saveError = record.saveError,
                    recordId = record.id,
                    finishedAt = clock(),
                )
                else -> {
                    // Nothing was changed; the failure the pipeline counted is replaced by the new attempt.
                    saving.uncountFailed(batchId)
                    item.copy(status = BatchItemStatus.Pending, targetUri = null)
                }
            }
            jobs.updateItem(settled)
        }
    }

    private suspend fun process(
        item: BatchJobItem,
        operation: BatchOperation,
        batchId: String,
        copyFolder: String?,
        destinations: BatchDestinations,
    ) {
        val running = item.copy(status = BatchItemStatus.Running)
        jobs.updateItem(running)
        val result = try {
            outcomeFor(running, operation, batchId, copyFolder, destinations)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            saving.countFailed(batchId)
            ItemResult.failed(BatchItemReason.SaveFailed, SaveError.Unknown)
        }
        jobs.updateItem(result.applyTo(running, clock()))
        inputs?.let { withContext(ioDispatcher) { it.release(item.photoUri) } }
    }

    private suspend fun outcomeFor(
        item: BatchJobItem,
        operation: BatchOperation,
        batchId: String,
        copyFolder: String?,
        destinations: BatchDestinations,
    ): ItemResult {
        val ref = item.photoRef()
        val report = try {
            readReport(ref)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            saving.countFailed(batchId)
            return ItemResult.failed(BatchItemReason.Unreadable)
        }
        val plan = try {
            planner().plan(report, listOf(operation.toEditOperation()))
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            saving.countFailed(batchId)
            return ItemResult.failed(BatchItemReason.PlanFailed)
        }
        if (plan.changes.isEmpty) return ItemResult.skipped(unchangedReason(plan))

        val decision = BatchRouting.route(ref, report.format, operation.kind, saving::canWrite, copyFolder != null)
        return when (decision.route) {
            BatchRoute.Skip -> ItemResult.skipped(decision.reason ?: BatchItemReason.NothingToChange)
            BatchRoute.InPlace -> resultOf(saving.save(ref, plan, SaveTarget.InPlace, batchId), batchId)
            BatchRoute.Copy -> saveCopy(item, report, plan, batchId, checkNotNull(copyFolder), destinations)
            BatchRoute.Sidecar -> saveSidecar(ref, plan, batchId, copyFolder, destinations)
        }
    }

    private fun unchangedReason(plan: EditPlan): BatchItemReason =
        if (PlanWarning.OtherUtcOffset in plan.warnings) BatchItemReason.AlreadyHasOtherZone else BatchItemReason.NothingToChange

    private suspend fun saveCopy(
        item: BatchJobItem,
        report: MetadataReport,
        plan: EditPlan,
        batchId: String,
        folder: String,
        destinations: BatchDestinations,
    ): ItemResult {
        val ref = item.photoRef()
        val format = ref.format.takeIf { it != ImageFormat.Unknown } ?: report.format
        val name = ref.displayName?.takeIf { it.isNotBlank() } ?: "photo.${format.extensions.firstOrNull() ?: "jpg"}"
        val mimeType = ref.mimeType ?: format.mimeType
        val destination = try {
            withContext(ioDispatcher) { destinations.createCopy(folder, name, mimeType) }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            saving.countFailed(batchId)
            return ItemResult.failed(BatchItemReason.CopyFolderUnavailable)
        }
        // Remembered before writing, so an interrupted run can remove a copy that stayed empty.
        jobs.updateItem(item.copy(targetUri = destination))
        val outcome = saving.save(ref, plan, SaveTarget.Copy(destination), batchId)
        if (outcome is SaveOutcome.Rejected) withContext(ioDispatcher) { runCatching { destinations.deleteIfEmpty(destination) } }
        return resultOf(outcome, batchId)
    }

    private suspend fun saveSidecar(
        ref: PhotoRef,
        plan: EditPlan,
        batchId: String,
        folder: String?,
        destinations: BatchDestinations,
    ): ItemResult {
        val outcome = saving.save(ref, plan, SaveTarget.Sidecar(), batchId)
        if (outcome !is SaveOutcome.NeedsSidecarDestination) return resultOf(outcome, batchId)
        if (folder == null) return ItemResult.skipped(BatchItemReason.NoSidecarPlace)
        val destination = try {
            withContext(ioDispatcher) { destinations.sidecarIn(folder, outcome.suggestedName) }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            saving.countFailed(batchId)
            return ItemResult.failed(BatchItemReason.CopyFolderUnavailable)
        }
        return resultOf(saving.save(ref, plan, SaveTarget.Sidecar(destination), batchId), batchId)
    }

    private suspend fun resultOf(outcome: SaveOutcome, batchId: String): ItemResult = when (outcome) {
        is SaveOutcome.Saved -> ItemResult(
            BatchItemStatus.of(outcome.mode),
            recordId = outcome.recordId,
            targetUri = outcome.targetUri,
            notes = WriteSkipCodec.encode(outcome.skipped),
        )
        is SaveOutcome.RolledBack -> ItemResult.failed(BatchItemReason.SaveFailed, outcome.error, outcome.recordId)
        is SaveOutcome.Failed -> {
            if (outcome.recordId == null) saving.countFailed(batchId)
            ItemResult.failed(BatchItemReason.SaveFailed, outcome.error, outcome.recordId)
        }
        is SaveOutcome.NeedsSidecarDestination -> ItemResult.skipped(BatchItemReason.NoSidecarPlace)
        is SaveOutcome.Rejected -> when (outcome.reason) {
            SaveRejection.ReadOnly -> ItemResult.skipped(BatchItemReason.ReadOnly)
            SaveRejection.UnsupportedFormat, SaveRejection.UseSidecar -> ItemResult.skipped(BatchItemReason.UnsupportedFormat)
            SaveRejection.SidecarHasDates -> ItemResult.skipped(BatchItemReason.SidecarHasDates)
            SaveRejection.NotRestorable, SaveRejection.NotFound -> {
                saving.countFailed(batchId)
                ItemResult.failed(BatchItemReason.SaveFailed, SaveError.Unknown)
            }
        }
    }

    private data class ItemResult(
        val status: BatchItemStatus,
        val reason: BatchItemReason? = null,
        val saveError: SaveError? = null,
        val recordId: String? = null,
        val targetUri: String? = null,
        val notes: String? = null,
    ) {
        fun applyTo(item: BatchJobItem, now: Long) = item.copy(
            status = status,
            reason = reason,
            saveError = saveError,
            recordId = recordId,
            targetUri = targetUri,
            finishedAt = now,
            notes = notes,
        )

        companion object {
            fun skipped(reason: BatchItemReason) = ItemResult(BatchItemStatus.Skipped, reason)
            fun failed(reason: BatchItemReason, error: SaveError? = null, recordId: String? = null) =
                ItemResult(BatchItemStatus.Failed, reason, error, recordId)
        }
    }

    companion object {
        /** Two photos at a time: enough to overlap reading and writing, gentle on storage and memory. */
        const val DEFAULT_CONCURRENCY = 2
    }
}
