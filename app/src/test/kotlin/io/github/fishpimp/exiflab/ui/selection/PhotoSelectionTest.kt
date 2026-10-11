package io.github.fishpimp.exiflab.ui.selection

import com.google.common.truth.Truth.assertThat
import io.github.fishpimp.exiflab.data.batch.BatchPhotos
import org.junit.Test

/** Multi-select state transitions. Pure JVM. */
class PhotoSelectionTest {
    private val a = BatchPhotos.folder("a.jpg")
    private val b = BatchPhotos.folder("b.jpg")
    private val c = BatchPhotos.folder("c.jpg")
    private val photos = listOf(a, b, c)

    @Test
    fun `long press enters selection mode and taps toggle`() {
        val started = PhotoSelection().begin(b.uri)
        assertThat(started.active).isTrue()
        assertThat(started.selected).containsExactly(b.uri)
        // A second long press on a selected photo keeps it selected.
        assertThat(started.begin(b.uri).selected).containsExactly(b.uri)

        val toggled = started.toggle(a.uri).toggle(b.uri)
        assertThat(toggled.selected).containsExactly(a.uri)
        // Deselecting everything keeps selection mode until it is left.
        val empty = toggled.toggle(a.uri)
        assertThat(empty.active).isTrue()
        assertThat(empty.count).isEqualTo(0)
        assertThat(empty.exit()).isEqualTo(PhotoSelection())
    }

    @Test
    fun `select all, clear and the selected photos in grid order`() {
        val all = PhotoSelection().enter().selectAll(photos.map { it.uri })
        assertThat(all.count).isEqualTo(3)
        assertThat(all.clear().selected).isEmpty()
        assertThat(all.clear().active).isTrue()
        assertThat(PhotoSelection(true, setOf(c.uri, a.uri)).selectedIn(photos)).containsExactly(a, c).inOrder()
    }

    @Test
    fun `drag ranges grow and shrink around what was selected before`() {
        val base = setOf(c.uri)
        val wide = PhotoSelection(true, base).withRange(base, listOf(a.uri, b.uri))
        assertThat(wide.selected).containsExactly(a.uri, b.uri, c.uri)
        val narrow = wide.withRange(base, listOf(a.uri))
        assertThat(narrow.selected).containsExactly(a.uri, c.uri)
    }

    @Test
    fun `photos that disappear from the folder leave the selection`() {
        val selection = PhotoSelection(true, setOf(a.uri, b.uri))
        assertThat(selection.retain(setOf(a.uri, b.uri, c.uri))).isSameInstanceAs(selection)
        assertThat(selection.retain(setOf(b.uri)).selected).containsExactly(b.uri)
    }

    @Test
    fun `the holder's actions update its state`() {
        val holder = PhotoSelectionHolder()
        val actions = holder.actions { photos.map { it.uri } }
        actions.onBegin(a.uri)
        actions.onToggle(b.uri)
        assertThat(holder.state.value.selected).containsExactly(a.uri, b.uri)
        actions.onSelectAll()
        assertThat(holder.state.value.count).isEqualTo(3)
        actions.onClear()
        assertThat(holder.state.value.count).isEqualTo(0)
        actions.onExit()
        assertThat(holder.state.value.active).isFalse()
        actions.onEnter()
        assertThat(holder.state.value.active).isTrue()
    }
}
