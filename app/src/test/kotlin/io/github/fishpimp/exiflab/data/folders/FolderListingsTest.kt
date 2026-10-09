package io.github.fishpimp.exiflab.data.folders

import android.provider.DocumentsContract.Document
import com.google.common.truth.Truth.assertThat
import io.github.fishpimp.exiflab.data.photos.PhotoOrigin
import org.junit.Test

class FolderListingsTest {
    private fun row(name: String, mime: String?, modified: Long? = null, flags: Int = 0) =
        DocumentRow(documentId = "id:$name", name = name, mimeType = mime, size = 100, lastModified = modified, flags = flags)

    private fun build(rows: List<DocumentRow>, canWrite: Boolean = true) =
        FolderListings.build(rows, documentUri = { "content://tree/doc/$it" }, canWrite = canWrite, collator = String.CASE_INSENSITIVE_ORDER)

    @Test
    fun `keeps folders and images, RAW by extension included`() {
        val listing = build(
            listOf(
                row("Camera", Document.MIME_TYPE_DIR),
                row("IMG_1.jpg", "image/jpeg", modified = 1),
                row("DSCF2.RAF", "application/octet-stream", modified = 2),
                row("notes.txt", "text/plain"),
                row("video.mp4", "video/mp4"),
                row(".thumbnails", Document.MIME_TYPE_DIR),
                row(".hidden.jpg", "image/jpeg"),
            ),
        )
        assertThat(listing.subfolders.map { it.name }).containsExactly("Camera")
        assertThat(listing.photos.map { it.displayName }).containsExactly("DSCF2.RAF", "IMG_1.jpg").inOrder()
        assertThat(listing.photos.first().mimeType).isEqualTo("image/x-fuji-raf")
        assertThat(listing.photos.all { it.origin == PhotoOrigin.Folder }).isTrue()
        assertThat(listing.subfolders.single().documentUri).isEqualTo("content://tree/doc/id:Camera")
    }

    @Test
    fun `folders sort by name, photos newest first then by name`() {
        val listing = build(
            listOf(
                row("b", Document.MIME_TYPE_DIR),
                row("A", Document.MIME_TYPE_DIR),
                row("old.jpg", "image/jpeg", modified = 10),
                row("undated.jpg", "image/jpeg", modified = null),
                row("new-b.jpg", "image/jpeg", modified = 20),
                row("new-a.jpg", "image/jpeg", modified = 20),
            ),
        )
        assertThat(listing.subfolders.map { it.name }).containsExactly("A", "b").inOrder()
        assertThat(listing.photos.map { it.displayName })
            .containsExactly("new-a.jpg", "new-b.jpg", "old.jpg", "undated.jpg").inOrder()
    }

    @Test
    fun `writable needs both a write grant and provider support`() {
        val rows = listOf(
            row("yes.jpg", "image/jpeg", flags = Document.FLAG_SUPPORTS_WRITE),
            row("no.jpg", "image/jpeg", flags = 0),
        )
        val granted = build(rows, canWrite = true).photos.associate { it.displayName to it.writable }
        assertThat(granted).containsExactly("yes.jpg", true, "no.jpg", false)
        assertThat(build(rows, canWrite = false).photos.none { it.writable }).isTrue()
    }

    @Test
    fun `thousands of entries stay a single pass`() {
        val rows = (0 until 5_000).map { row("IMG_$it.jpg", "image/jpeg", modified = it.toLong()) }
        val listing = build(rows)
        assertThat(listing.photos).hasSize(5_000)
        assertThat(listing.photos.first().displayName).isEqualTo("IMG_4999.jpg")
    }
}
