package io.github.fishpimp.exiflab.data.photos

import android.content.ClipData
import android.content.Intent
import android.net.Uri
import android.provider.DocumentsContract
import androidx.core.net.toUri
import com.google.common.truth.Truth.assertThat
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class ShareIntentsTest {
    private val first = "content://com.example.photos/media/1".toUri()
    private val second = "content://com.example.photos/media/2".toUri()

    @Test
    fun `single share reads EXTRA_STREAM`() {
        val intent = Intent(Intent.ACTION_SEND).setType("image/jpeg").putExtra(Intent.EXTRA_STREAM, first)
        assertThat(intent.sharedStreamUris()).containsExactly(first)
    }

    @Test
    fun `multiple share reads the stream list`() {
        val intent = Intent(Intent.ACTION_SEND_MULTIPLE)
            .setType("image/*")
            .putParcelableArrayListExtra(Intent.EXTRA_STREAM, arrayListOf(first, second))
        assertThat(intent.sharedStreamUris()).containsExactly(first, second).inOrder()
    }

    @Test
    fun `clip data is read and merged without duplicates`() {
        val clip = ClipData.newRawUri(null, first).apply { addItem(ClipData.Item(second)) }
        val intent = Intent(Intent.ACTION_SEND).setType("image/jpeg").putExtra(Intent.EXTRA_STREAM, first)
        intent.clipData = clip
        assertThat(intent.sharedStreamUris()).containsExactly(first, second).inOrder()
    }

    @Test
    fun `only content URIs from share actions are accepted`() {
        val file = Uri.fromFile(java.io.File("/data/data/io.github.fishpimp.exiflab/files/secret"))
        val withFile = Intent(Intent.ACTION_SEND).putExtra(Intent.EXTRA_STREAM, file)
        assertThat(withFile.sharedStreamUris()).isEmpty()
        val view = Intent(Intent.ACTION_VIEW).putExtra(Intent.EXTRA_STREAM, first)
        assertThat(view.sharedStreamUris()).isEmpty()
    }

    @Test
    fun `grant snapshot covers documents inside a granted tree`() {
        val authority = "com.android.externalstorage.documents"
        val tree = DocumentsContract.buildTreeDocumentUri(authority, "primary:DCIM")
        val inside = DocumentsContract.buildDocumentUriUsingTree(tree, "primary:DCIM/Camera/IMG_1.jpg")
        val elsewhere = DocumentsContract.buildDocumentUriUsingTree(
            DocumentsContract.buildTreeDocumentUri(authority, "primary:Pictures"),
            "primary:Pictures/IMG_2.jpg",
        )
        val grants = GrantSnapshot(readable = setOf(tree.toString(), first.toString()), writable = setOf(tree.toString()))

        assertThat(grants.canRead(inside.toString())).isTrue()
        assertThat(grants.canWrite(inside.toString())).isTrue()
        assertThat(grants.canRead(elsewhere.toString())).isFalse()
        assertThat(grants.canRead(first.toString())).isTrue()
        assertThat(grants.canWrite(first.toString())).isFalse()
        assertThat(grants.canRead(second.toString())).isFalse()
    }
}
