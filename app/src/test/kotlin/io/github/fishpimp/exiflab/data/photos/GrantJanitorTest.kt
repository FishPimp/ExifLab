package io.github.fishpimp.exiflab.data.photos

import android.provider.DocumentsContract
import com.google.common.truth.Truth.assertThat
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class GrantJanitorTest {
    private val authority = "com.android.externalstorage.documents"
    private val now = 1_800_000_000_000L
    private val old = now - 30L * 24 * 60 * 60 * 1000

    private fun tree(id: String) = DocumentsContract.buildTreeDocumentUri(authority, id)

    @Test
    fun `only grants nothing needs are released, never library folders or recent ones`() {
        val library = tree("primary:DCIM").toString()
        val copyFolder = tree("primary:Pictures/Edited")
        val finishedCopyFolder = tree("primary:Pictures/Old").toString()
        val runningCopyFolder = DocumentsContract.buildDocumentUriUsingTree(copyFolder, "primary:Pictures/Edited").toString()
        val recent = "content://media/picker/0/com.android.providers.media.photopicker/media/12"
        val forgotten = "content://media/picker/0/com.android.providers.media.photopicker/media/13"
        val justPicked = "content://media/picker/0/com.android.providers.media.photopicker/media/14"
        val editedDocument = "content://com.android.providers.media.documents/document/image%3A99"
        val grants = listOf(
            PersistedGrant(library, old),
            PersistedGrant(copyFolder.toString(), old),
            PersistedGrant(finishedCopyFolder, old),
            PersistedGrant(recent, old),
            PersistedGrant(forgotten, old),
            PersistedGrant(justPicked, now - 60_000),
            PersistedGrant(editedDocument, old),
        )

        val released = GrantJanitor.toRelease(
            grants,
            libraryTrees = setOf(library),
            // A recent photo, a document History can still restore, and a running job's copy folder.
            needed = setOf(recent, editedDocument, runningCopyFolder),
            now = now,
        )

        assertThat(released).containsExactly(finishedCopyFolder, forgotten)
    }
}
