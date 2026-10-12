package io.github.fishpimp.exiflab.data.shareclean

import com.google.common.truth.Truth.assertThat
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.IOException

class SharedFilesStoreTest {
    @get:Rule val folder = TemporaryFolder()

    private val now = 1_760_000_000_000L
    private val day = SharedFilesStore.MAX_AGE_MILLIS

    @Test
    fun `every copy gets its own new directory under the shared root`() {
        val names = ArrayDeque(listOf("a", "a", "b"))
        val store = SharedFilesStore(folder.root.resolve("shared"), clock = { now }, newName = { names.removeFirst() })

        val first = store.newDirectory()
        val second = store.newDirectory()

        assertThat(first.name).isEqualTo("a")
        // "a" is taken, so the next name is used.
        assertThat(second.name).isEqualTo("b")
        assertThat(first.parentFile).isEqualTo(store.root)
    }

    @Test(expected = IOException::class)
    fun `a directory that cannot be created fails instead of looping`() {
        val store = SharedFilesStore(folder.root.resolve("shared"), clock = { now }, newName = { "same" })
        store.newDirectory()
        store.newDirectory()
    }

    @Test
    fun `copies older than a day are deleted, newer ones stay`() {
        val store = SharedFilesStore(folder.root.resolve("shared"), clock = { now })
        val old = store.newDirectory().also { dir ->
            dir.resolve("IMG_1.jpg").writeText("old")
            dir.resolve("IMG_1.jpg").setLastModified(now - day - 60_000)
            dir.setLastModified(now - day - 60_000)
        }
        val fresh = store.newDirectory().also { dir ->
            dir.resolve("IMG_2.jpg").writeText("new")
            dir.setLastModified(now - day + 60_000)
        }

        assertThat(store.deleteOlderThan()).isEqualTo(1)
        assertThat(old.exists()).isFalse()
        assertThat(fresh.resolve("IMG_2.jpg").exists()).isTrue()
    }

    @Test
    fun `cleaning up before anything was shared does nothing`() {
        val store = SharedFilesStore(folder.root.resolve("never-created"), clock = { now })
        assertThat(store.deleteOlderThan()).isEqualTo(0)
    }
}
