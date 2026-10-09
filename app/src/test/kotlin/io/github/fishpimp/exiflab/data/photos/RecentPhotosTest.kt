package io.github.fishpimp.exiflab.data.photos

import com.google.common.truth.Truth.assertThat
import org.junit.Test

class RecentPhotosTest {
    @Test
    fun `newest first without duplicates`() {
        val current = listOf(ref("a"), ref("b"), ref("c"))
        val updated = RecentPhotos.record(current, ref("b", lastModified = 99)) { true }
        assertThat(updated.map { it.uri }).containsExactly("b", "a", "c").inOrder()
        assertThat(updated.first().lastModified).isEqualTo(99)
    }

    @Test
    fun `list is capped`() {
        val current = (1..RecentPhotos.LIMIT).map { ref("old$it") }
        val updated = RecentPhotos.record(current, ref("new")) { true }
        assertThat(updated).hasSize(RecentPhotos.LIMIT)
        assertThat(updated.first().uri).isEqualTo("new")
        assertThat(updated.map { it.uri }).doesNotContain("old${RecentPhotos.LIMIT}")
    }

    @Test
    fun `entries without access are dropped, the new one included`() {
        val current = listOf(ref("kept"), ref("revoked"))
        val updated = RecentPhotos.record(current, ref("shared")) { it.uri == "kept" }
        assertThat(updated.map { it.uri }).containsExactly("kept")
    }

    private fun ref(uri: String, lastModified: Long = 1) = PhotoRef(
        uri = uri,
        displayName = "$uri.jpg",
        mimeType = "image/jpeg",
        size = 10,
        lastModified = lastModified,
        origin = PhotoOrigin.Picker,
        writable = false,
    )
}
