package io.github.fishpimp.exiflab.data.batch

import android.content.ContentResolver
import android.provider.DocumentsContract
import androidx.core.net.toUri
import io.github.fishpimp.exiflab.data.history.EditHistoryDao
import io.github.fishpimp.exiflab.data.history.EditRecord
import io.github.fishpimp.exiflab.data.photos.PhotoRef
import io.github.fishpimp.exiflab.data.save.DocumentAccess
import io.github.fishpimp.exiflab.data.save.PhotoSaver
import io.github.fishpimp.exiflab.data.save.SIDECAR_MIME_TYPE
import io.github.fishpimp.exiflab.data.save.SaveOutcome
import io.github.fishpimp.exiflab.data.save.SaveTarget
import io.github.fishpimp.exiflab.data.save.WriteEngine
import io.github.fishpimp.exiflab.metadata.ImageFormat
import io.github.fishpimp.exiflab.metadata.edit.EditPlan
import java.io.File
import java.io.FileNotFoundException

/** [BatchSaving] backed by the app's journaled [PhotoSaver] and the History tables. */
class PhotoSaverBatchSaving(
    private val saver: () -> PhotoSaver,
    private val engine: () -> WriteEngine,
    private val history: () -> EditHistoryDao,
) : BatchSaving {
    override fun canWrite(format: ImageFormat): Boolean = engine().canWrite(format)

    override suspend fun startBatch(title: String, total: Int): String = saver().startBatch(title, total)

    override suspend fun save(ref: PhotoRef, plan: EditPlan, target: SaveTarget, batchId: String): SaveOutcome =
        saver().save(ref = ref, changes = plan.changes, diff = plan.diff, target = target, batchId = batchId)

    override suspend fun recoverInterrupted() {
        saver().recoverInterrupted()
    }

    override suspend fun latestRecord(batchId: String, photoUri: String): EditRecord? = history().latestRecordInBatch(batchId, photoUri)

    override suspend fun countFailed(batchId: String) = history().countFailed(batchId)

    override suspend fun uncountFailed(batchId: String) = history().uncountFailed(batchId)

    override suspend fun countCancelled(batchId: String, count: Int) = history().countCancelled(batchId, count)
}

/**
 * [BatchDestinations] in a folder from `ACTION_OPEN_DOCUMENT_TREE` (as a tree document URI).
 * The folder's names are listed once per folder and remembered, so hundreds of copies do not
 * re-list it each time; a copy never replaces an existing file.
 */
class DocumentBatchDestinations(
    private val resolver: ContentResolver,
    private val documents: DocumentAccess,
) : BatchDestinations {
    private val takenNames = HashMap<String, MutableSet<String>>()

    override fun createCopy(folderUri: String, name: String, mimeType: String): String {
        val unique = synchronized(takenNames) {
            val taken = takenNames.getOrPut(folderUri) { listNames(folderUri).toMutableSet() }
            CopyNames.unique(name) { it.lowercase() in taken }.also { taken += it.lowercase() }
        }
        return documents.createChild(folderUri, unique, mimeType)
    }

    override fun sidecarIn(folderUri: String, name: String): String =
        documents.findChild(folderUri, name) ?: documents.createChild(folderUri, name, SIDECAR_MIME_TYPE)

    override fun deleteIfEmpty(uri: String) {
        val empty = try {
            documents.openInput(uri).use { it.read() == -1 }
        } catch (_: FileNotFoundException) {
            return
        }
        if (empty) documents.delete(uri)
    }

    private fun listNames(folderUri: String): Set<String> {
        val parent = folderUri.toUri()
        if (parent.scheme == ContentResolver.SCHEME_FILE) {
            return File(requireNotNull(parent.path)).list()?.map { it.lowercase() }?.toSet().orEmpty()
        }
        val children = DocumentsContract.buildChildDocumentsUriUsingTree(parent, DocumentsContract.getDocumentId(parent))
        val projection = arrayOf(DocumentsContract.Document.COLUMN_DISPLAY_NAME)
        return runCatching {
            resolver.query(children, projection, null, null, null)?.use { cursor ->
                buildSet {
                    while (cursor.moveToNext()) cursor.getString(0)?.let { add(it.lowercase()) }
                }
            }
        }.getOrNull().orEmpty()
    }
}

/** Names for copies that must not clash with files already in the folder. */
object CopyNames {
    /** [name] itself when free, otherwise "IMG_1234 (1).jpg", "IMG_1234 (2).jpg" and so on. */
    fun unique(name: String, isTaken: (String) -> Boolean): String {
        if (!isTaken(name)) return name
        val dot = name.lastIndexOf('.').takeIf { it > 0 }
        val base = if (dot != null) name.substring(0, dot) else name
        val extension = if (dot != null) name.substring(dot) else ""
        var counter = 1
        while (true) {
            val candidate = "$base ($counter)$extension"
            if (!isTaken(candidate)) return candidate
            counter++
        }
    }
}
