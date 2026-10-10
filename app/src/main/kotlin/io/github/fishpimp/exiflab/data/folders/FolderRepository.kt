package io.github.fishpimp.exiflab.data.folders

import android.content.ContentResolver
import android.content.Context
import android.content.Intent
import android.database.ContentObserver
import android.database.Cursor
import android.net.Uri
import android.provider.DocumentsContract
import android.provider.DocumentsContract.Document
import androidx.core.net.toUri
import io.github.fishpimp.exiflab.data.photos.GrantSnapshot
import io.github.fishpimp.exiflab.data.photos.PhotoAccessError
import io.github.fishpimp.exiflab.data.photos.PhotoAccessException
import io.github.fishpimp.exiflab.data.photos.longOrNull
import io.github.fishpimp.exiflab.data.photos.mapAccessErrors
import io.github.fishpimp.exiflab.data.photos.stringOrNull
import io.github.fishpimp.exiflab.data.photos.tryPersistAccess
import io.github.fishpimp.exiflab.data.store.JsonListStore
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.mapLatest
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.withContext

/**
 * Folders granted through the system folder picker, stored in DataStore, plus browsing their
 * content. Access is checked against the persisted grants, so a folder whose grant was revoked
 * shows up as [FolderAccess.Lost] instead of disappearing.
 *
 * @param readGrants reads the persisted URI grants; blocking, called on [ioDispatcher].
 */
class FolderRepository(
    context: Context,
    private val store: JsonListStore<GrantedFolder>,
    private val readGrants: () -> GrantSnapshot,
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO,
) {
    private val resolver: ContentResolver = context.applicationContext.contentResolver
    private val refreshes = MutableStateFlow(0)

    /** Granted folders, newest first, with their current access. Re-checked on [refresh]. */
    @OptIn(ExperimentalCoroutinesApi::class)
    val folders: Flow<List<LibraryFolder>> = combine(store.items, refreshes) { items, _ -> items }
        .mapLatest { items ->
            val grants = readGrants()
            items.map { LibraryFolder(it, grants.accessTo(it.treeUri)) }
        }
        .flowOn(ioDispatcher)

    /** Re-checks access, e.g. when the user returns from system settings. */
    fun refresh() {
        refreshes.update { it + 1 }
    }

    /**
     * Persists read and write access to [treeUri] (read only when the provider does not allow
     * writing) and adds it to the library. Adding a folder that is already there updates it.
     */
    suspend fun addFolder(treeUri: Uri): GrantedFolder = grant(treeUri, replacing = null)

    /**
     * Restores access to the folder stored under [previousTreeUri] with a fresh grant for
     * [treeUri]. Picking the same folder again restores it; picking another one replaces it.
     */
    suspend fun regrant(previousTreeUri: String, treeUri: Uri): GrantedFolder = grant(treeUri, replacing = previousTreeUri)

    /** Removes [folder] from the library and releases its grant. Files are not touched. */
    suspend fun removeFolder(folder: GrantedFolder) = withContext(ioDispatcher) {
        try {
            resolver.releasePersistableUriPermission(
                folder.treeUri.toUri(),
                Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION,
            )
        } catch (_: SecurityException) {
            // The grant was already gone; removing the entry is all that is left to do.
        }
        store.update { current -> current.filterNot { it.treeUri == folder.treeUri } }
        refresh()
    }

    /**
     * Subfolders and photos directly inside [documentUri] (a folder in the tree [folderTreeUri]).
     * One query reads every row; the listing is re-read when the provider reports a change, at
     * most twice a second, so cloud providers that load in pages and new files show up live.
     *
     * @throws PhotoAccessException when the grant is gone or the folder no longer exists.
     */
    fun listChildren(folderTreeUri: String, documentUri: String): Flow<FolderListing> = flow {
        val tree = folderTreeUri.toUri()
        val childrenUri = mapAccessErrors(PhotoAccessError.NotFound) {
            DocumentsContract.buildChildDocumentsUriUsingTree(tree, DocumentsContract.getDocumentId(documentUri.toUri()))
        }
        val changes = Channel<Unit>(Channel.CONFLATED)
        val observer = object : ContentObserver(null) {
            override fun onChange(selfChange: Boolean) {
                changes.trySend(Unit)
            }
        }
        changes.trySend(Unit)
        var cursor: Cursor? = null
        try {
            while (true) {
                changes.receive()
                // Closing the previous cursor also unregisters the observer from it.
                cursor?.close()
                val canWrite = readGrants().canWrite(folderTreeUri)
                val next = mapAccessErrors(PhotoAccessError.ReadFailed) {
                    resolver.query(childrenUri, CHILD_PROJECTION, null, null, null)
                } ?: throw PhotoAccessException(PhotoAccessError.NotFound)
                cursor = next
                next.registerContentObserver(observer)
                val isLoading = next.extras?.getBoolean(DocumentsContract.EXTRA_LOADING) == true
                emit(
                    FolderListings.build(
                        rows = next.readRows(),
                        documentUri = { DocumentsContract.buildDocumentUriUsingTree(tree, it).toString() },
                        canWrite = canWrite,
                        isLoading = isLoading,
                        parentDocumentUri = documentUri,
                    ),
                )
                delay(REQUERY_THROTTLE_MS)
            }
        } finally {
            cursor?.close()
        }
    }.flowOn(ioDispatcher)

    private suspend fun grant(treeUri: Uri, replacing: String?): GrantedFolder = withContext(ioDispatcher) {
        val folder = mapAccessErrors(PhotoAccessError.ReadFailed) {
            if (!resolver.tryPersistAccess(treeUri, includeWrite = true)) {
                throw PhotoAccessException(PhotoAccessError.PermissionDenied)
            }
            GrantedFolder(
                treeUri = treeUri.toString(),
                displayName = queryFolderName(treeUri) ?: fallbackName(treeUri),
                addedAt = System.currentTimeMillis(),
            )
        }
        store.update { current -> current.withGranted(folder, replacing = replacing) }
        refresh()
        folder
    }

    private fun queryFolderName(treeUri: Uri): String? {
        val root = DocumentsContract.buildDocumentUriUsingTree(treeUri, DocumentsContract.getTreeDocumentId(treeUri))
        return resolver.query(root, arrayOf(Document.COLUMN_DISPLAY_NAME), null, null, null)?.use { cursor ->
            if (cursor.moveToFirst()) cursor.stringOrNull(Document.COLUMN_DISPLAY_NAME) else null
        }
    }

    /** "primary:DCIM/Camera" becomes "Camera"; used when the provider reports no name. */
    private fun fallbackName(treeUri: Uri): String {
        val id = DocumentsContract.getTreeDocumentId(treeUri)
        return id.substringAfter(':').substringAfterLast('/').ifEmpty { id }
    }

    private fun Cursor.readRows(): List<DocumentRow> {
        val idColumn = getColumnIndexOrThrow(Document.COLUMN_DOCUMENT_ID)
        val nameColumn = getColumnIndex(Document.COLUMN_DISPLAY_NAME)
        val mimeColumn = getColumnIndex(Document.COLUMN_MIME_TYPE)
        val flagsColumn = getColumnIndex(Document.COLUMN_FLAGS)
        val rows = ArrayList<DocumentRow>(count.coerceAtLeast(0))
        while (moveToNext()) {
            rows += DocumentRow(
                documentId = getString(idColumn),
                name = if (nameColumn >= 0) getString(nameColumn).orEmpty() else "",
                mimeType = if (mimeColumn >= 0) getString(mimeColumn) else null,
                size = longOrNull(Document.COLUMN_SIZE),
                lastModified = longOrNull(Document.COLUMN_LAST_MODIFIED)?.takeIf { it > 0 },
                flags = if (flagsColumn >= 0 && !isNull(flagsColumn)) getInt(flagsColumn) else 0,
            )
        }
        return rows
    }

    private companion object {
        const val REQUERY_THROTTLE_MS = 500L

        val CHILD_PROJECTION = arrayOf(
            Document.COLUMN_DOCUMENT_ID,
            Document.COLUMN_DISPLAY_NAME,
            Document.COLUMN_MIME_TYPE,
            Document.COLUMN_SIZE,
            Document.COLUMN_LAST_MODIFIED,
            Document.COLUMN_FLAGS,
        )
    }
}

private fun GrantSnapshot.accessTo(treeUri: String): FolderAccess = when {
    !canRead(treeUri) -> FolderAccess.Lost
    canWrite(treeUri) -> FolderAccess.ReadWrite
    else -> FolderAccess.ReadOnly
}
