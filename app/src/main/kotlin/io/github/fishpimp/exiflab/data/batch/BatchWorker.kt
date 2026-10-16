package io.github.fishpimp.exiflab.data.batch

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.SystemClock
import androidx.work.CoroutineWorker
import androidx.work.ExistingWorkPolicy
import androidx.work.ForegroundInfo
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.OutOfQuotaPolicy
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import androidx.work.workDataOf
import io.github.fishpimp.exiflab.appGraph
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

/**
 * Runs one [BatchJob] in the background as an expedited, foreground (data sync) worker with a
 * progress notification. All state lives in Room, so a worker the system stops simply runs again
 * later and the [BatchRunner] continues with the items that did not finish.
 */
class BatchWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {
    override suspend fun doWork(): Result {
        val jobId = inputData.getString(KEY_JOB_ID) ?: return Result.failure()
        val graph = applicationContext.appGraph
        val job = graph.batchJobs.job(jobId) ?: return Result.success()
        if (job.state.isDone) return Result.success()
        val notifications = BatchNotifications(applicationContext)
        try {
            setForeground(notifications.foregroundInfo(job, BatchProgress(job.total, 0, 0, 0)))
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            // Starting a foreground service from the background is not always allowed (e.g. when
            // the job resumes after a restart); the work still runs, without the ongoing notification.
        }

        var lastNotified = 0L
        val ended = graph.batchRunner.run(jobId) { progress ->
            setProgress(workDataOf(KEY_DONE to progress.done, KEY_TOTAL to progress.total))
            val now = SystemClock.elapsedRealtime()
            if (now - lastNotified >= NOTIFICATION_THROTTLE_MS) {
                lastNotified = now
                val cancelling = graph.batchJobs.job(jobId)?.cancelRequested == true
                notifications.showProgress(job, progress, cancelling)
            }
        } ?: return Result.success()
        if (ended.state.isDone) {
            val counts = BatchProgress.of(ended.total, graph.batchJobDao.counts(jobId))
            notifications.showFinished(ended, counts.copy(withNotes = graph.batchJobDao.countWithNotes(jobId)))
        }
        return Result.success()
    }

    override suspend fun getForegroundInfo(): ForegroundInfo {
        val jobId = inputData.getString(KEY_JOB_ID).orEmpty()
        val job = applicationContext.appGraph.batchJobs.job(jobId)
            ?: BatchJob(id = jobId, state = BatchJobState.Queued, operationJson = null, title = "", total = 0, createdAt = 0)
        return BatchNotifications(applicationContext).foregroundInfo(job, BatchProgress(job.total, 0, 0, 0))
    }

    companion object {
        const val KEY_JOB_ID = "job_id"
        const val KEY_DONE = "done"
        const val KEY_TOTAL = "total"
        const val TAG = "batch"
        private const val NOTIFICATION_THROTTLE_MS = 500L

        fun uniqueName(jobId: String) = "batch-$jobId"
    }
}

/** Enqueues [BatchWorker]s; one unique work per job, so a job is never run twice at once. */
class WorkManagerBatchScheduler(private val context: Context) : BatchScheduling {
    override fun enqueue(jobId: String) {
        val request = OneTimeWorkRequestBuilder<BatchWorker>()
            .setExpedited(OutOfQuotaPolicy.RUN_AS_NON_EXPEDITED_WORK_REQUEST)
            .setInputData(workDataOf(BatchWorker.KEY_JOB_ID to jobId))
            .addTag(BatchWorker.TAG)
            .build()
        WorkManager.getInstance(context).enqueueUniqueWork(BatchWorker.uniqueName(jobId), ExistingWorkPolicy.KEEP, request)
    }
}

/** The notification's Cancel action: asks the job to stop after the photos in progress. */
class BatchCancelReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != ACTION_CANCEL) return
        val jobId = intent.getStringExtra(BatchNotifications.EXTRA_BATCH_JOB_ID) ?: return
        val pending = goAsync()
        val graph = context.appGraph
        CoroutineScope(SupervisorJob() + Dispatchers.IO).launch {
            try {
                graph.batchJobs.cancel(jobId)
            } finally {
                pending.finish()
            }
        }
    }

    companion object {
        const val ACTION_CANCEL = "io.github.fishpimp.exiflab.action.CANCEL_BATCH"
    }
}
