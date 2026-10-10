package io.github.fishpimp.exiflab.ui.photo

import androidx.lifecycle.SavedStateHandle
import com.google.common.truth.Truth.assertThat
import io.github.fishpimp.exiflab.data.history.EditMode
import io.github.fishpimp.exiflab.data.history.SaveError
import io.github.fishpimp.exiflab.data.save.SaveOutcome
import io.github.fishpimp.exiflab.data.save.SavedEditNotice
import io.github.fishpimp.exiflab.data.save.SavedEditNotices
import io.github.fishpimp.exiflab.screenshots.FakeReports
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/** The viewer's side of a save: it re-reads the photo, announces the save once and can undo it. */
@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
class PhotoSavedEditTest {
    private val dispatcher = UnconfinedTestDispatcher()
    private val ref = FakeReports.phoneRef
    private val notices = SavedEditNotices()
    private var reads = 0
    private val restored = mutableListOf<String>()
    private var restoreOutcome: SaveOutcome = SaveOutcome.Saved("restore", EditMode.InPlace, ref.uri)

    @Before fun setUp() = Dispatchers.setMain(dispatcher)

    @After fun tearDown() = Dispatchers.resetMain()

    private fun viewModel() = PhotoViewModel(
        ref = ref,
        savedState = SavedStateHandle(),
        readReport = {
            reads++
            FakeReports.phoneJpeg
        },
        saveCopy = { photo, _ -> photo },
        openDocument = { ref },
        computeDispatcher = dispatcher,
        savedEdits = notices.forPhoto(ref.uri),
        consumeSavedEdit = notices::consume,
        restoreOriginal = { id ->
            restored += id
            restoreOutcome
        },
    )

    private fun TestScope.observe(vm: PhotoViewModel): () -> PhotoUiState {
        backgroundScope.launch { vm.state.collect { } }
        return { vm.state.value }
    }

    private fun TestScope.events(vm: PhotoViewModel): List<PhotoEvent> {
        val list = mutableListOf<PhotoEvent>()
        backgroundScope.launch { vm.events.collect { list += it } }
        return list
    }

    @Test
    fun `a save to this photo reloads it and is announced once`() = runTest(dispatcher) {
        val vm = viewModel()
        val state = observe(vm)
        assertThat(reads).isEqualTo(1)
        assertThat(state().savedNotice).isNull()

        val saved = SavedEditNotice("record-1", ref.uri, EditMode.InPlace)
        notices.post(saved)
        assertThat(reads).isEqualTo(2)
        assertThat(state().savedNotice).isEqualTo(saved)
        assertThat(state().load).isInstanceOf(PhotoLoad.Loaded::class.java)

        vm.onSavedNoticeShown(saved)
        assertThat(state().savedNotice).isNull()
        assertThat(notices.forPhoto(ref.uri).first()).isNull()
        assertThat(reads).isEqualTo(2)
    }

    @Test
    fun `saves to other photos are ignored`() = runTest(dispatcher) {
        val vm = viewModel()
        val state = observe(vm)
        notices.post(SavedEditNotice("record-2", "content://other", EditMode.Copy))
        assertThat(state().savedNotice).isNull()
        assertThat(reads).isEqualTo(1)
    }

    @Test
    fun `undo restores the original and reads the photo again`() = runTest(dispatcher) {
        val vm = viewModel()
        observe(vm)
        val events = events(vm)
        vm.undoEdit("record-1")
        assertThat(restored).containsExactly("record-1")
        assertThat(events).containsExactly(PhotoEvent.Undone)
        assertThat(reads).isEqualTo(2)

        restoreOutcome = SaveOutcome.Failed("restore-2", SaveError.BackupDamaged)
        vm.undoEdit("record-1")
        assertThat(events.last()).isEqualTo(PhotoEvent.UndoFailed)
    }
}
