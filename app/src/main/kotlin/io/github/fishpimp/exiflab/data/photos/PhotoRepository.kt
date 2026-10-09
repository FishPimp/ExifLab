package io.github.fishpimp.exiflab.data.photos

import android.content.ContentResolver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.database.Cursor
import android.net.Uri
import android.provider.DocumentsContract
import android.provider.OpenableColumns
import androidx.core.net.toUri
import io.github.fishpimp.exiflab.metadata.ImageSource
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.FileNotFoundException
import java.io.OutputStream

/**
 * Turns content URIs into [PhotoRef]s and gives access to their bytes. All provider calls run
 * on [ioDispatcher]; failures surface as [PhotoAccessException].
 */
class PhotoRepository(
    context: Context,
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO,
) {
    private val appContext = context.applicationContext
    private val resolver: ContentResolver = appContext.contentResolver

    /**
     * Describes [uri] as a [PhotoRef]. Picker and document grants are persisted when the
     * provider allows it, so recents keep working after a restart. Picker and shared photos are
     * never writable; documents and folder files are writable when the provider supports writing
     * and ExifLab holds a write grant.
     */
    suspend fun resolve(uri: Uri, origin: PhotoOrigin): PhotoRef = withContext(ioDispatcher) {
        mapAccessErrors(PhotoAccessError.ReadFailed) {
            when (origin) {
                PhotoOrigin.Picker -> resolver.tryPersistAccess(uri, includeWrite = false)
                PhotoOrigin.Document -> resolver.tryPersistAccess(uri, includeWrite = true)
                // Folder files are covered by the tree grant; shared URIs are not persistable.
                PhotoOrigin.Folder, PhotoOrigin.Share -> Unit
            }
            val isDocument = DocumentsContract.isDocumentUri(appContext, uri)
            val info = if (isDocument) queryDocument(uri) else queryOpenable(uri)
            val reportedType = resolver.getType(uri)?.takeUnless { it == GENERIC_MIME_TYPE }
            val canWriteInPlace = when (origin) {
                PhotoOrigin.Picker, PhotoOrigin.Share -> false
                PhotoOrigin.Document, PhotoOrigin.Folder ->
                    isDocument && info.supportsWrite && hasWriteGrant(uri)
            }
            PhotoRef(
                uri = uri.toString(),
                displayName = info.displayName,
                mimeType = reportedType ?: PhotoFormats.mimeTypeFromName(info.displayName),
                size = info.size,
                lastModified = info.lastModified,
                origin = origin,
                writable = canWriteInPlace,
            )
        }
    }

    /**
     * Resolves up to [MAX_PHOTOS_PER_OPEN] URIs from one pick or share. Files that fail or are
     * not images are skipped; the result fails only when nothing could be opened.
     */
    suspend fun open(uris: List<Uri>, origin: PhotoOrigin): OpenResult {
        var firstError: PhotoAccessError? = null
        val refs = uris.distinct().take(MAX_PHOTOS_PER_OPEN).mapNotNull { uri ->
            try {
                val ref = resolve(uri, origin)
                if (PhotoFormats.isSupportedImage(ref.mimeType, ref.displayName)) {
                    ref
                } else {
                    firstError = firstError ?: PhotoAccessError.Unsupported
                    null
                }
            } catch (e: PhotoAccessException) {
                firstError = firstError ?: e.error
                null
            }
        }
        return if (refs.isNotEmpty()) OpenResult.Opened(refs) else OpenResult.Failed(firstError ?: PhotoAccessError.NotFound)
    }

    /** A re-openable byte source for the metadata engine, backed by the provider. */
    fun imageSource(ref: PhotoRef): ImageSource =
        ContentImageSource(resolver, ref.uri.toUri(), ref.displayName, ref.size)

    /**
     * Copies [ref] byte for byte into [destination] (a document created by the system file
     * picker). Pixels and metadata are untouched. A partially written copy is deleted.
     *
     * @return the copy as a [PhotoOrigin.Document] ref, writable when the provider allows it.
     */
    suspend fun saveCopy(ref: PhotoRef, destination: Uri): PhotoRef = withContext(ioDispatcher) {
        val input = mapAccessErrors(PhotoAccessError.ReadFailed) {
            resolver.openInputStream(ref.uri.toUri()) ?: throw FileNotFoundException(ref.uri)
        }
        try {
            val copied = mapAccessErrors(PhotoAccessError.WriteFailed) {
                input.use { source -> openForWriting(destination).use { target -> source.copyTo(target, COPY_BUFFER_BYTES) } }
            }
            if (ref.size != null && ref.size > 0 && copied != ref.size) {
                throw PhotoAccessException(PhotoAccessError.WriteFailed)
            }
        } catch (e: PhotoAccessException) {
            runCatching { DocumentsContract.deleteDocument(resolver, destination) }
            throw e
        }
        resolve(destination, PhotoOrigin.Document)
    }

    private fun openForWriting(uri: Uri): OutputStream {
        // "wt" truncates; a few providers only accept plain "w", which is fine for a new document.
        val stream = try {
            resolver.openOutputStream(uri, "wt")
        } catch (_: IllegalArgumentException) {
            resolver.openOutputStream(uri, "w")
        } catch (_: UnsupportedOperationException) {
            resolver.openOutputStream(uri, "w")
        }
        return stream ?: throw FileNotFoundException(uri.toString())
    }

    private fun hasWriteGrant(uri: Uri): Boolean =
        appContext.checkCallingOrSelfUriPermission(uri, Intent.FLAG_GRANT_WRITE_URI_PERMISSION) ==
            PackageManager.PERMISSION_GRANTED

    private fun queryDocument(uri: Uri): FileInfo = query(
        uri,
        arrayOf(
            DocumentsContract.Document.COLUMN_DISPLAY_NAME,
            DocumentsContract.Document.COLUMN_SIZE,
            DocumentsContract.Document.COLUMN_LAST_MODIFIED,
            DocumentsContract.Document.COLUMN_FLAGS,
        ),
    )

    private fun queryOpenable(uri: Uri): FileInfo =
        query(uri, arrayOf(OpenableColumns.DISPLAY_NAME, OpenableColumns.SIZE))

    /** One provider query; a provider that answers with no cursor at all yields an empty [FileInfo]. */
    private fun query(uri: Uri, projection: Array<String>): FileInfo {
        val cursor = resolver.query(uri, projection, null, null, null) ?: return FileInfo()
        return cursor.use { c ->
            if (!c.moveToFirst()) throw FileNotFoundException(uri.toString())
            val flags = c.intOrNull(DocumentsContract.Document.COLUMN_FLAGS) ?: 0
            FileInfo(
                displayName = c.stringOrNull(OpenableColumns.DISPLAY_NAME),
                size = c.longOrNull(OpenableColumns.SIZE),
                lastModified = c.longOrNull(DocumentsContract.Document.COLUMN_LAST_MODIFIED)?.takeIf { it > 0 },
                supportsWrite = (flags and DocumentsContract.Document.FLAG_SUPPORTS_WRITE) != 0,
            )
        }
    }

    private class FileInfo(
        val displayName: String? = null,
        val size: Long? = null,
        val lastModified: Long? = null,
        val supportsWrite: Boolean = false,
    )

    private companion object {
        const val GENERIC_MIME_TYPE = "application/octet-stream"
        const val COPY_BUFFER_BYTES = 64 * 1024
    }
}

/** Most photos opened at once, matching the system photo picker's limit. */
const val MAX_PHOTOS_PER_OPEN = 100

/** Outcome of [PhotoRepository.open]. */
sealed interface OpenResult {
    /** At least one photo opened; [refs] is never empty. */
    data class Opened(val refs: List<PhotoRef>) : OpenResult

    /** Nothing could be opened; [error] is the first problem met. */
    data class Failed(val error: PhotoAccessError) : OpenResult
}

internal fun Cursor.stringOrNull(column: String): String? =
    getColumnIndex(column).takeIf { it >= 0 && !isNull(it) }?.let(::getString)

internal fun Cursor.longOrNull(column: String): Long? =
    getColumnIndex(column).takeIf { it >= 0 && !isNull(it) }?.let(::getLong)

internal fun Cursor.intOrNull(column: String): Int? =
    getColumnIndex(column).takeIf { it >= 0 && !isNull(it) }?.let(::getInt)
