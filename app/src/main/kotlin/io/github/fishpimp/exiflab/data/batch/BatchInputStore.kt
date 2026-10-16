package io.github.fishpimp.exiflab.data.batch

import androidx.core.net.toUri
import io.github.fishpimp.exiflab.data.photos.PhotoOrigin
import java.io.File
import java.io.IOException
import java.io.InputStream

/**
 * Private copies of shared photos for batch jobs, in app storage ([root], under `files/`).
 *
 * A photo shared with ExifLab can be read only while the screen that received the share exists:
 * Android revokes the share's grant when that activity is destroyed. A batch job runs in the
 * background, and resumes after a restart, so the photos a job got by sharing are copied here
 * when it starts and read from here. Each copy is deleted once its photo is done, and a job's
 * folder when the job ends. Blocking; call off the main thread.
 */
class BatchInputStore(
    private val root: File,
    private val openInput: (uri: String) -> InputStream,
) {
    /**
     * The [items] of job [jobId], with every shared photo pointing at a private copy. A photo that
     * cannot be copied keeps its shared URI and is read from there while that still works.
     */
    fun stage(jobId: String, items: List<BatchJobItem>): List<BatchJobItem> {
        val directory = File(root, safe(jobId))
        return items.map { item ->
            if (item.origin != PhotoOrigin.Share || isStaged(item.photoUri)) return@map item
            val copy = File(directory, "${item.position}")
            try {
                directory.mkdirs()
                openInput(item.photoUri).use { input -> copy.outputStream().use { input.copyTo(it) } }
                item.copy(photoUri = copy.toUri().toString(), size = copy.length())
            } catch (e: IOException) {
                copy.delete()
                item
            } catch (e: SecurityException) {
                copy.delete()
                item
            }
        }
    }

    /** Deletes the copy behind [photoUri] when it is one of the store's. */
    fun release(photoUri: String) {
        if (isStaged(photoUri)) photoUri.toUri().path?.let(::File)?.delete()
    }

    /** Deletes every copy of job [jobId]. */
    fun releaseJob(jobId: String) {
        File(root, safe(jobId)).deleteRecursively()
    }

    /** Deletes the copies of jobs for which [keep] is false, e.g. jobs that ended or no longer exist. */
    suspend fun releaseJobsUnless(keep: suspend (jobId: String) -> Boolean) {
        root.listFiles()?.forEach { directory -> if (!keep(directory.name)) directory.deleteRecursively() }
    }

    /** True when [photoUri] is a copy in this store. */
    fun isStaged(photoUri: String): Boolean {
        val uri = photoUri.toUri()
        if (uri.scheme != "file") return false
        val path = uri.path ?: return false
        return File(path).canonicalPath.startsWith(root.canonicalPath + File.separator)
    }

    /** Job ids are UUIDs; anything else is kept from reaching outside [root]. */
    private fun safe(jobId: String) = jobId.replace(Regex("[^A-Za-z0-9-]"), "_")

    companion object {
        const val DIRECTORY_NAME = "batch-inputs"
    }
}
