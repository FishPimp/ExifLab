package io.github.fishpimp.exiflab.data.save

import android.content.ContentResolver
import android.database.Cursor
import android.net.Uri
import android.os.ParcelFileDescriptor
import android.provider.DocumentsContract
import android.provider.OpenableColumns
import androidx.core.net.toUri
import java.io.File
import java.io.FileNotFoundException
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.io.SyncFailedException

/** Thrown when a target could not be opened for writing. Nothing was changed. */
class TargetUnavailableException(message: String, cause: Throwable? = null) : IOException(message, cause)

/**
 * The file operations the save pipeline needs, behind an interface so tests can inject failures.
 * URIs are strings (`content://` or, in tests, `file://`). Blocking.
 */
interface DocumentAccess {
    /** Opens [uri] for reading. Throws [FileNotFoundException] when it does not exist. */
    fun openInput(uri: String): InputStream

    /**
     * Replaces the whole content of [uri] with what [write] produces, then flushes it to the
     * storage device. Throws [TargetUnavailableException] when [uri] cannot be opened, in which
     * case it is unchanged; any other exception means it may be partially written.
     */
    fun overwrite(uri: String, write: (OutputStream) -> Unit)

    /** Deletes the document; true when it no longer exists afterwards. */
    fun delete(uri: String): Boolean

    /** The display name the provider reports, when it reports one. */
    fun displayName(uri: String): String?

    /** The child named [name] of the folder [parentDocumentUri] (a tree document URI), or null. */
    fun findChild(parentDocumentUri: String, name: String): String?

    /** Creates an empty document named [name] in [parentDocumentUri]; returns its URI. */
    fun createChild(parentDocumentUri: String, name: String, mimeType: String): String
}

/** [DocumentAccess] through the [ContentResolver]; `file://` URIs work too. */
class ContentResolverDocuments(private val resolver: ContentResolver) : DocumentAccess {
    override fun openInput(uri: String): InputStream {
        val parsed = uri.toUri()
        if (parsed.scheme == ContentResolver.SCHEME_FILE) return File(requireNotNull(parsed.path)).inputStream()
        return resolver.openInputStream(parsed) ?: throw FileNotFoundException("No stream for $uri")
    }

    override fun overwrite(uri: String, write: (OutputStream) -> Unit) {
        val descriptor = try {
            openForOverwrite(uri.toUri())
        } catch (e: IOException) {
            throw TargetUnavailableException("Cannot open $uri for writing", e)
        } catch (e: SecurityException) {
            throw TargetUnavailableException("No write access to $uri", e)
        } catch (e: IllegalArgumentException) {
            throw TargetUnavailableException("Cannot open $uri for writing", e)
        } catch (e: UnsupportedOperationException) {
            throw TargetUnavailableException("Cannot open $uri for writing", e)
        } ?: throw TargetUnavailableException("No descriptor for $uri")
        ParcelFileDescriptor.AutoCloseOutputStream(descriptor).use { out ->
            var written = 0L
            val counting = object : OutputStream() {
                override fun write(b: Int) {
                    out.write(b)
                    written++
                }

                override fun write(b: ByteArray, off: Int, len: Int) {
                    out.write(b, off, len)
                    written += len
                }
            }
            write(counting)
            out.flush()
            // "rwt" truncates on open; trimming again guards against providers that ignore it.
            runCatching { out.channel.truncate(written) }
            try {
                out.fd.sync()
            } catch (_: SyncFailedException) {
                // Pipes from cloud providers cannot be synced; the read-back check still runs.
            }
        }
    }

    private fun openForOverwrite(uri: Uri) = try {
        resolver.openFileDescriptor(uri, "rwt")
    } catch (e: IllegalArgumentException) {
        // Some providers only accept the plain write modes.
        resolver.openFileDescriptor(uri, "wt") ?: throw e
    } catch (e: UnsupportedOperationException) {
        resolver.openFileDescriptor(uri, "wt") ?: throw e
    }

    override fun delete(uri: String): Boolean {
        val parsed = uri.toUri()
        if (parsed.scheme == ContentResolver.SCHEME_FILE) {
            val file = File(requireNotNull(parsed.path))
            return !file.exists() || file.delete()
        }
        return try {
            DocumentsContract.deleteDocument(resolver, parsed)
        } catch (_: FileNotFoundException) {
            true
        } catch (_: Exception) {
            false
        }
    }

    override fun displayName(uri: String): String? {
        val parsed = uri.toUri()
        if (parsed.scheme == ContentResolver.SCHEME_FILE) return parsed.lastPathSegment
        return runCatching {
            resolver.query(parsed, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { cursor ->
                if (cursor.moveToFirst()) cursor.stringAt(OpenableColumns.DISPLAY_NAME) else null
            }
        }.getOrNull()
    }

    override fun findChild(parentDocumentUri: String, name: String): String? {
        val parent = parentDocumentUri.toUri()
        if (parent.scheme == ContentResolver.SCHEME_FILE) {
            return File(requireNotNull(parent.path), name).takeIf { it.isFile }?.toUri()?.toString()
        }
        val children = DocumentsContract.buildChildDocumentsUriUsingTree(parent, DocumentsContract.getDocumentId(parent))
        val projection = arrayOf(DocumentsContract.Document.COLUMN_DOCUMENT_ID, DocumentsContract.Document.COLUMN_DISPLAY_NAME)
        return resolver.query(children, projection, null, null, null)?.use { cursor ->
            while (cursor.moveToNext()) {
                if (cursor.stringAt(DocumentsContract.Document.COLUMN_DISPLAY_NAME) == name) {
                    val id = cursor.stringAt(DocumentsContract.Document.COLUMN_DOCUMENT_ID) ?: continue
                    return@use DocumentsContract.buildDocumentUriUsingTree(parent, id).toString()
                }
            }
            null
        }
    }

    override fun createChild(parentDocumentUri: String, name: String, mimeType: String): String {
        val parent = parentDocumentUri.toUri()
        if (parent.scheme == ContentResolver.SCHEME_FILE) {
            val file = File(requireNotNull(parent.path), name)
            if (!file.exists() && !file.createNewFile()) throw IOException("Cannot create $name")
            return file.toUri().toString()
        }
        val created = try {
            DocumentsContract.createDocument(resolver, parent, mimeType, name)
        } catch (e: Exception) {
            throw TargetUnavailableException("Cannot create $name", e)
        }
        return created?.toString() ?: throw TargetUnavailableException("Provider did not create $name")
    }

    private fun Cursor.stringAt(column: String): String? =
        getColumnIndex(column).takeIf { it >= 0 && !isNull(it) }?.let(::getString)
}
