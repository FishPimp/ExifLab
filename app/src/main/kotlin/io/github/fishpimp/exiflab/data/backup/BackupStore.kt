package io.github.fishpimp.exiflab.data.backup

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.io.InputStream

/** A backup file as written by [BackupStore.create]. [path] is its name inside the backup folder. */
data class StoredBackup(val path: String, val hash: ContentHash)

/** A file found in the backup folder. */
data class BackupFile(val path: String, val size: Long, val lastModified: Long)

/**
 * Byte-exact copies of files taken before ExifLab overwrites them, kept in app-private storage
 * (`files/backups`). Nothing here is ever shared or uploaded; Android backup and device transfer
 * exclude the app's files. Paths handed out are bare file names, so moving the app's data folder
 * keeps them valid. Blocking; call off the main thread.
 */
class BackupStore(val directory: File) {
    private val usage = MutableStateFlow<Long?>(null)

    /** Bytes the backup folder takes, or null until it was first measured. */
    val usedBytes: StateFlow<Long?> = usage.asStateFlow()

    /**
     * Copies [input] (not closed) into a new backup named after [id], flushing it to disk before
     * returning. A failed copy leaves nothing behind.
     */
    fun create(id: String, input: InputStream): StoredBackup {
        require(id.isNotEmpty() && id.none { it == '/' || it == '\\' } && id != "." && id != "..") { "Bad backup id" }
        if (!directory.isDirectory && !directory.mkdirs()) throw IOException("Cannot create the backup folder")
        val name = id + EXTENSION
        val partial = File(directory, name + PARTIAL_SUFFIX)
        try {
            val hash = FileOutputStream(partial).use { out ->
                Sha256.copy(input, out).also {
                    out.flush()
                    out.fd.sync()
                }
            }
            val target = File(directory, name)
            if (!partial.renameTo(target)) throw IOException("Cannot finish the backup")
            return StoredBackup(name, hash)
        } catch (e: Throwable) {
            partial.delete()
            throw e
        } finally {
            refreshUsage()
        }
    }

    /** The file behind [path]. Throws [IllegalArgumentException] for anything but a plain name. */
    fun file(path: String): File {
        require(path.isNotEmpty() && path.none { it == '/' || it == '\\' } && path != "." && path != "..") { "Bad backup path" }
        return File(directory, path)
    }

    fun exists(path: String): Boolean = file(path).isFile

    /** Deletes the backup at [path]; true when it is gone afterwards. */
    fun delete(path: String): Boolean {
        val file = file(path)
        val gone = !file.exists() || file.delete()
        refreshUsage()
        return gone
    }

    /** Completed backups in the folder. */
    fun files(): List<BackupFile> = listFiles { !it.name.endsWith(PARTIAL_SUFFIX) }

    /** Backups that were being written when the app stopped. */
    fun partialFiles(): List<BackupFile> = listFiles { it.name.endsWith(PARTIAL_SUFFIX) }

    /** The record id a backup or partial backup file belongs to. */
    fun idOf(path: String): String = path.removeSuffix(PARTIAL_SUFFIX).removeSuffix(EXTENSION)

    /** Measures the folder again and publishes the result to [usedBytes]. */
    fun refreshUsage(): Long {
        val total = directory.listFiles()?.filter { it.isFile }?.sumOf { it.length() } ?: 0L
        usage.value = total
        return total
    }

    private fun listFiles(filter: (File) -> Boolean): List<BackupFile> =
        directory.listFiles()
            ?.filter { it.isFile && filter(it) }
            ?.map { BackupFile(it.name, it.length(), it.lastModified()) }
            .orEmpty()

    companion object {
        const val DIRECTORY_NAME = "backups"
        private const val EXTENSION = ".bak"
        private const val PARTIAL_SUFFIX = ".partial"
    }
}
