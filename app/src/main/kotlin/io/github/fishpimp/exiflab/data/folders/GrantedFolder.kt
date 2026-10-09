package io.github.fishpimp.exiflab.data.folders

import android.provider.DocumentsContract
import androidx.core.net.toUri
import io.github.fishpimp.exiflab.data.photos.PhotoRef
import kotlinx.serialization.Serializable

/**
 * A folder the user granted ExifLab access to through the system folder picker.
 *
 * @property treeUri The tree URI returned by the picker; the persisted grant is keyed on it.
 * @property displayName Folder name at the time it was added.
 * @property addedAt When it was added, in epoch milliseconds.
 */
@Serializable
data class GrantedFolder(
    val treeUri: String,
    val displayName: String,
    val addedAt: Long,
)

/** Whether ExifLab can still use a [GrantedFolder]. */
enum class FolderAccess {
    ReadWrite,
    ReadOnly,

    /** The grant is gone (revoked in settings, app data cleared, storage removed). */
    Lost,
}

/** A granted folder together with its current [access]. */
data class LibraryFolder(val folder: GrantedFolder, val access: FolderAccess)

/**
 * The library after granting [folder]: an entry for the same tree (or the one being
 * [replacing]) is updated where it stands and keeps its original `addedAt`; a new folder goes
 * first.
 */
internal fun List<GrantedFolder>.withGranted(folder: GrantedFolder, replacing: String? = null): List<GrantedFolder> {
    val slot = indexOfFirst { it.treeUri == (replacing ?: folder.treeUri) }
        .takeIf { it >= 0 } ?: indexOfFirst { it.treeUri == folder.treeUri }
    val entry = firstOrNull { it.treeUri == folder.treeUri }?.let { folder.copy(addedAt = it.addedAt) } ?: folder
    val rest = filterNot { it.treeUri == folder.treeUri || it.treeUri == replacing }
    return if (slot < 0) listOf(entry) + rest else rest.toMutableList().apply { add(slot.coerceAtMost(size), entry) }
}

/** The document URI of the folder itself, the starting point for browsing it. */
val GrantedFolder.rootDocumentUri: String
    get() {
        val tree = treeUri.toUri()
        return DocumentsContract.buildDocumentUriUsingTree(tree, DocumentsContract.getTreeDocumentId(tree)).toString()
    }

/** A subfolder shown while browsing; [documentUri] is a tree-based document URI. */
data class Subfolder(val documentUri: String, val name: String)

/**
 * The browsable content of one folder: subfolders sorted by name, then photos newest first.
 *
 * @property isLoading True while a (typically cloud) provider is still fetching more entries.
 */
data class FolderListing(
    val subfolders: List<Subfolder>,
    val photos: List<PhotoRef>,
    val isLoading: Boolean = false,
) {
    val isEmpty: Boolean get() = subfolders.isEmpty() && photos.isEmpty()
}
