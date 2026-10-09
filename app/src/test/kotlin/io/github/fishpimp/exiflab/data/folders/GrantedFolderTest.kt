package io.github.fishpimp.exiflab.data.folders

import com.google.common.truth.Truth.assertThat
import org.junit.Test

class GrantedFolderTest {
    private fun folder(tree: String, name: String = tree, addedAt: Long = 1) = GrantedFolder(tree, name, addedAt)

    @Test
    fun `a new folder goes first`() {
        val library = listOf(folder("a"), folder("b"))
        assertThat(library.withGranted(folder("c")).map { it.treeUri }).containsExactly("c", "a", "b").inOrder()
    }

    @Test
    fun `granting a folder again updates it in place and keeps when it was added`() {
        val library = listOf(folder("a"), folder("b", name = "Old", addedAt = 5))
        val updated = library.withGranted(folder("b", name = "New", addedAt = 99))
        assertThat(updated.map { it.treeUri }).containsExactly("a", "b").inOrder()
        assertThat(updated[1]).isEqualTo(folder("b", name = "New", addedAt = 5))
    }

    @Test
    fun `re-granting with a different pick replaces the lost folder in its place`() {
        val library = listOf(folder("a"), folder("lost"), folder("c"))
        val updated = library.withGranted(folder("moved", addedAt = 7), replacing = "lost")
        assertThat(updated.map { it.treeUri }).containsExactly("a", "moved", "c").inOrder()
    }
}
