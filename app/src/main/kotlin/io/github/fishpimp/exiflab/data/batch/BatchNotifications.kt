package io.github.fishpimp.exiflab.data.batch

import android.Manifest
import android.app.Notification
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.os.Build
import androidx.core.app.NotificationChannelCompat
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import androidx.work.ForegroundInfo
import io.github.fishpimp.exiflab.MainActivity
import io.github.fishpimp.exiflab.R

/**
 * The batch notifications: a determinate progress notification (photo count, Cancel) while a
 * job runs as a foreground worker, and a short result once it ends. Tapping either opens the
 * job's progress or summary. Nothing is posted without the notification permission; the job
 * runs either way and the app shows its progress.
 */
class BatchNotifications(private val context: Context) {
    private val manager = NotificationManagerCompat.from(context)

    init {
        manager.createNotificationChannel(
            NotificationChannelCompat.Builder(CHANNEL_ID, NotificationManagerCompat.IMPORTANCE_LOW)
                .setName(context.getString(R.string.batch_notification_channel))
                .setDescription(context.getString(R.string.batch_notification_channel_description))
                .setShowBadge(false)
                .build(),
        )
    }

    /** The foreground notification for the worker, typed as data sync. */
    fun foregroundInfo(job: BatchJob, progress: BatchProgress): ForegroundInfo {
        return ForegroundInfo(progressId(job.id), progressNotification(job, progress), ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
    }

    fun showProgress(job: BatchJob, progress: BatchProgress, cancelling: Boolean) {
        post(progressId(job.id), progressNotification(job, progress, cancelling))
    }

    fun showFinished(job: BatchJob, progress: BatchProgress) {
        manager.cancel(progressId(job.id))
        val written = progress.done - progress.failed - progress.skipped
        val parts = listOfNotNull(
            context.resources.getQuantityString(R.plurals.batch_count_written, written, written),
            progress.withNotes.takeIf { it > 0 }?.let { context.resources.getQuantityString(R.plurals.batch_count_with_notes, it, it) },
            context.resources.getQuantityString(R.plurals.batch_count_skipped, progress.skipped, progress.skipped),
            context.resources.getQuantityString(R.plurals.batch_count_failed, progress.failed, progress.failed),
        )
        val title = if (job.state == BatchJobState.Cancelled) R.string.batch_notification_cancelled else R.string.batch_notification_finished
        val notification = NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_notification_batch)
            .setContentTitle(context.getString(title))
            .setContentText(parts.joinToString(", "))
            .setSubText(job.title.ifBlank { null })
            .setContentIntent(openJob(job.id))
            .setAutoCancel(true)
            .setCategory(NotificationCompat.CATEGORY_STATUS)
            .build()
        post(resultId(job.id), notification)
    }

    private fun progressNotification(job: BatchJob, progress: BatchProgress, cancelling: Boolean = false): Notification {
        val text = if (cancelling) {
            context.getString(R.string.batch_notification_cancelling)
        } else {
            context.resources.getQuantityString(R.plurals.batch_notification_progress, progress.total, progress.done, progress.total)
        }
        return NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_notification_batch)
            .setContentTitle(job.title.ifBlank { context.getString(R.string.batch_notification_channel) })
            .setContentText(text)
            .setProgress(progress.total.coerceAtLeast(1), progress.done, false)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setSilent(true)
            .setCategory(NotificationCompat.CATEGORY_PROGRESS)
            .setForegroundServiceBehavior(NotificationCompat.FOREGROUND_SERVICE_IMMEDIATE)
            .setContentIntent(openJob(job.id))
            .apply {
                if (!cancelling) {
                    addAction(R.drawable.ic_notification_batch, context.getString(R.string.batch_cancel), cancelJob(job.id))
                }
            }
            .build()
    }

    private fun post(id: Int, notification: Notification) {
        val allowed = Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU ||
            ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED
        if (!allowed) return
        try {
            manager.notify(id, notification)
        } catch (_: SecurityException) {
            // The permission was revoked in between; the app still shows the job.
        }
    }

    private fun openJob(jobId: String): PendingIntent {
        val intent = Intent(context, MainActivity::class.java)
            .setAction(ACTION_OPEN_BATCH)
            .putExtra(EXTRA_BATCH_JOB_ID, jobId)
            .addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP)
        return PendingIntent.getActivity(context, progressId(jobId), intent, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
    }

    private fun cancelJob(jobId: String): PendingIntent {
        val intent = Intent(context, BatchCancelReceiver::class.java)
            .setAction(BatchCancelReceiver.ACTION_CANCEL)
            .putExtra(EXTRA_BATCH_JOB_ID, jobId)
        return PendingIntent.getBroadcast(context, progressId(jobId), intent, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
    }

    companion object {
        const val CHANNEL_ID = "batch_edits"
        const val ACTION_OPEN_BATCH = "io.github.fishpimp.exiflab.action.OPEN_BATCH"
        const val EXTRA_BATCH_JOB_ID = "io.github.fishpimp.exiflab.extra.BATCH_JOB_ID"

        private const val PROGRESS_ID_BASE = 0x1000_0000
        private const val RESULT_ID_BASE = 0x2000_0000
        private const val ID_MASK = 0x0FFF_FFFF

        fun progressId(jobId: String): Int = PROGRESS_ID_BASE or (jobId.hashCode() and ID_MASK)
        private fun resultId(jobId: String): Int = RESULT_ID_BASE or (jobId.hashCode() and ID_MASK)
    }
}
