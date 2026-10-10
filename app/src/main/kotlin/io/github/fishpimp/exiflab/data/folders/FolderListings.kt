package io.github.fishpimp.exiflab.data.folders

import android.provider.DocumentsContract
import io.github.fishpimp.exiflab.data.photos.PhotoFormats
import io.github.fishpimp.exiflab.data.photos.PhotoOrigin
import io.github.fishpimp.exiflab.data.photos.PhotoRef
import java.text.Collator

/** One row of a child-documents query, before filtering. */
internal data class DocumentRow(
    val documentId: String,
    val name: String,
    val mimeType: String?,
    val size: Long?,
    val lastModified: Long?,
    val flags: Int,
)

/** Turns raw child-document rows into a [FolderListing]. Pure, so it is unit-tested on the JVM. */
internal object FolderListings {
    private const val GENERIC_MIME_TYPE = "application/octet-stream"

    /**
     * Keeps subfolders and images (by MIME type or a known extension, which covers RAW), skips
     * hidden entries, and sorts folders by name and photos newest first.
     *
     * @param documentUri builds the tree-based document URI for a document id.
     * @param canWrite whether ExifLab holds a write grant for the folder tree.
     * @param parentDocumentUri the folder being listed, recorded on each photo so its sidecar can be found.
     */
    fun build(
        rows: List<DocumentRow>,
        documentUri: (String) -> String,
        canWrite: Boolean,
        isLoading: Boolean = false,
        parentDocumentUri: String? = null,
        collator: Comparator<in String> = Collator.getInstance(),
    ): FolderListing {
        val subfolders = ArrayList<Subfolder>()
        val photos = ArrayList<PhotoRef>()
        for (row in rows) {
            if (row.name.startsWith('.')) continue
            when {
                row.mimeType == DocumentsContract.Document.MIME_TYPE_DIR ->
                    subfolders += Subfolder(documentUri(row.documentId), row.name)
                PhotoFormats.isSupportedImage(row.mimeType, row.name) -> photos += PhotoRef(
                    uri = documentUri(row.documentId),
                    displayName = row.name,
                    mimeType = row.mimeType?.takeUnless { it == GENERIC_MIME_TYPE } ?: PhotoFormats.mimeTypeFromName(row.name),
                    size = row.size,
                    lastModified = row.lastModified,
                    origin = PhotoOrigin.Folder,
                    writable = canWrite && (row.flags and DocumentsContract.Document.FLAG_SUPPORTS_WRITE) != 0,
                    parentDocumentUri = parentDocumentUri,
                )
            }
        }
        subfolders.sortWith(compareBy(collator) { it.name })
        photos.sortWith(
            compareByDescending<PhotoRef> { it.lastModified ?: Long.MIN_VALUE }
                .thenBy(collator) { it.displayName.orEmpty() },
        )
        return FolderListing(subfolders, photos, isLoading)
    }
}
