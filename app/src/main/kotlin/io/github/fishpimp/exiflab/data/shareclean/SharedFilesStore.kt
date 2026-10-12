package io.github.fishpimp.exiflab.data.shareclean

import java.io.File
import java.io.IOException
import java.util.UUID

/**
 * The cleaned copies waiting to be picked up by the app the user shares to, in the app's cache
 * (`cache/shared/<random>/<original name>`), so each copy keeps its file name and nothing else
 * can see it. A day is long enough for any receiving app to read them, so older ones are deleted.
 *
 * @param root the `shared` directory, which the FileProvider exposes (see `res/xml/shareclean_paths.xml`).
 */
class SharedFilesStore(
    val root: File,
    private val clock: () -> Long = System::currentTimeMillis,
    private val newName: () -> String = { UUID.randomUUID().toString() },
) {
    /** A new, empty directory for one cleaned copy; throws [IOException] when none can be created (storage full). */
    fun newDirectory(): File {
        root.mkdirs()
        repeat(MAX_ATTEMPTS) {
            val directory = File(root, newName())
            if (directory.mkdir()) return directory
        }
        throw IOException("No room for shared copies")
    }

    /** Deletes shared copies older than [maxAgeMillis]. Never throws; returns how many were deleted. */
    fun deleteOlderThan(maxAgeMillis: Long = MAX_AGE_MILLIS): Int {
        val cutoff = clock() - maxAgeMillis
        val entries = root.listFiles() ?: return 0
        return entries.count { entry -> entry.lastModified() < cutoff && runCatching { entry.deleteRecursively() }.getOrDefault(false) }
    }

    companion object {
        /** Name of the directory under the cache directory. */
        const val DIRECTORY_NAME = "shared"
        const val MAX_AGE_MILLIS = 24L * 60 * 60 * 1000
        private const val MAX_ATTEMPTS = 3
    }
}
