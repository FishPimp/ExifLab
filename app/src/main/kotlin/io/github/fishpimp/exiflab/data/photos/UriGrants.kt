package io.github.fishpimp.exiflab.data.photos

import android.content.ContentResolver
import android.content.Intent
import android.net.Uri
import android.provider.DocumentsContract
import androidx.core.net.toUri

/**
 * The URI permissions ExifLab holds across restarts, captured at one point in time. Reading
 * them is a binder call, so take one snapshot per refresh instead of querying per item.
 */
class GrantSnapshot(
    private val readable: Set<String>,
    private val writable: Set<String>,
) {
    /** True when [uri] (a document, picker URI or folder tree) has a persisted read grant. */
    fun canRead(uri: String): Boolean = uri in readable || treeOf(uri)?.let { it in readable } == true

    /** True when [uri] has a persisted write grant, directly or through its folder tree. */
    fun canWrite(uri: String): Boolean = uri in writable || treeOf(uri)?.let { it in writable } == true

    private fun treeOf(uri: String): String? = treeUriOf(uri.toUri())?.toString()

    companion object {
        /** Reads the persisted grants from [resolver]. Blocking; call off the main thread. */
        fun read(resolver: ContentResolver): GrantSnapshot {
            val permissions = resolver.persistedUriPermissions
            return GrantSnapshot(
                readable = permissions.filter { it.isReadPermission }.mapTo(HashSet()) { it.uri.toString() },
                writable = permissions.filter { it.isWritePermission }.mapTo(HashSet()) { it.uri.toString() },
            )
        }
    }
}

/** For a document URI inside a granted tree (`.../tree/<id>/document/<id>`), the tree URI; else null. */
internal fun treeUriOf(uri: Uri): Uri? {
    if (!DocumentsContract.isTreeUri(uri)) return null
    val treeId = runCatching { DocumentsContract.getTreeDocumentId(uri) }.getOrNull() ?: return null
    return DocumentsContract.buildTreeDocumentUri(uri.authority, treeId)
}

/**
 * Tries to keep access to [uri] after the app restarts. Read and write are attempted first,
 * then read only; providers refuse with [SecurityException] when the grant is not persistable.
 *
 * @return true when at least read access was persisted.
 */
internal fun ContentResolver.tryPersistAccess(uri: Uri, includeWrite: Boolean): Boolean {
    val read = Intent.FLAG_GRANT_READ_URI_PERMISSION
    val attempts = if (includeWrite) listOf(read or Intent.FLAG_GRANT_WRITE_URI_PERMISSION, read) else listOf(read)
    return attempts.any { flags ->
        try {
            takePersistableUriPermission(uri, flags)
            true
        } catch (_: SecurityException) {
            false
        }
    }
}
