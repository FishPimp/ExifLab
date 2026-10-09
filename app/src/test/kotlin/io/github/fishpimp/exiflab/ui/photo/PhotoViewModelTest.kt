package io.github.fishpimp.exiflab.ui.photo

import android.net.Uri
import androidx.lifecycle.SavedStateHandle
import com.google.common.truth.Truth.assertThat
import io.github.fishpimp.exiflab.data.photos.PhotoAccessError
import io.github.fishpimp.exiflab.data.photos.PhotoAccessException
import io.github.fishpimp.exiflab.data.photos.PhotoOrigin
import io.github.fishpimp.exiflab.data.photos.PhotoRef
import io.github.fishpimp.exiflab.metadata.CorruptImageException
import io.github.fishpimp.exiflab.metadata.UnsupportedImageException
import io.github.fishpimp.exiflab.metadata.model.MetadataReport
import io.github.fishpimp.exiflab.metadata.model.SensitivityCategory
import io.github.fishpimp.exiflab.screenshots.FakeReports
import java.io.FileNotFoundException
import java.io.IOException
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

@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
class PhotoViewModelTest {
    private val dispatcher = UnconfinedTestDispatcher()
    private val ref = FakeReports.rawRef

    @Before fun setUp() = Dispatchers.setMain(dispatcher)

    @After fun tearDown() = Dispatchers.resetMain()

    private fun viewModel(
        savedState: SavedStateHandle = SavedStateHandle(),
        read: suspend (PhotoRef) -> MetadataReport = { FakeReports.cameraRaw },
        saveCopy: suspend (PhotoRef, Uri) -> PhotoRef = { photo, _ -> photo.copy(uri = "content://copy", origin = PhotoOrigin.Document, writable = true) },
        openDocument: suspend (Uri) -> PhotoRef = { uri -> ref.copy(uri = uri.toString(), origin = PhotoOrigin.Document) },
    ) = PhotoViewModel(ref, savedState, read, saveCopy, openDocument, computeDispatcher = dispatcher)

    /** Keeps [PhotoViewModel.state] subscribed for the test and returns its latest value on demand. */
    private fun TestScope.observe(vm: PhotoViewModel): () -> PhotoUiState {
        backgroundScope.launch { vm.state.collect { } }
        return { vm.state.value }
    }

    @Test
    fun `loads the report and collapses large MakerNotes by default`() = runTest(dispatcher) {
        val vm = viewModel()
        val state = observe(vm)
        val loaded = state()
        assertThat((loaded.load as PhotoLoad.Loaded).report).isEqualTo(FakeReports.cameraRaw)
        val browser = loaded.browser!!
        assertThat(browser.totalTags).isEqualTo(FakeReports.cameraRaw.tagCount)
        val makerNote = browser.rows.filterIsInstance<SectionHeaderRow>().single { it.sectionId == "makernote-fujifilm" }
        assertThat(makerNote.expanded).isFalse()
        assertThat(loaded.mode).isEqualTo(ValueMode.Readable)
    }

    @Test
    fun `search, mode and filter rebuild the browser`() = runTest(dispatcher) {
        val vm = viewModel()
        val state = observe(vm)

        vm.onQueryChange("serial")
        assertThat(vm.query).isEqualTo("serial")
        // Searching opens every section, the collapsed MakerNote included.
        assertThat(state().browser!!.rows.filterIsInstance<TagRow>().map { it.tag.name })
            .containsExactly("Body Serial Number", "Lens Serial Number", "Serial Number")

        vm.setMode(ValueMode.Raw)
        assertThat(state().mode).isEqualTo(ValueMode.Raw)
        assertThat(state().browser!!.rows.filterIsInstance<TagRow>().all { it.showId }).isTrue()

        vm.onQueryChange("")
        vm.toggleFilter(SensitivityCategory.PersonName)
        assertThat(state().filter).isEqualTo(SensitivityCategory.PersonName)
        assertThat(state().browser!!.matchingTags).isEqualTo(4)

        vm.toggleFilter(SensitivityCategory.PersonName)
        assertThat(state().filter).isNull()
        assertThat(state().browser!!.isFiltering).isFalse()
    }

    @Test
    fun `sections collapse and expand, one at a time or all together`() = runTest(dispatcher) {
        val vm = viewModel()
        val state = observe(vm)
        fun expanded(id: String) = state().browser!!.rows.filterIsInstance<SectionHeaderRow>().single { it.sectionId == id }.expanded

        vm.toggleSection("exif-ifd0")
        assertThat(expanded("exif-ifd0")).isFalse()
        vm.toggleSection("makernote-fujifilm")
        assertThat(expanded("makernote-fujifilm")).isTrue()

        vm.setAllExpanded(false)
        assertThat(state().browser!!.anyExpanded).isFalse()
        assertThat(state().browser!!.rows.filterIsInstance<TagRow>()).isEmpty()
        vm.setAllExpanded(true)
        assertThat(state().browser!!.rows.filterIsInstance<TagRow>()).hasSize(FakeReports.cameraRaw.tagCount)
    }

    @Test
    fun `collapsing while searching does not change the normal collapse state`() = runTest(dispatcher) {
        val vm = viewModel()
        val state = observe(vm)
        vm.onQueryChange("lens")
        vm.toggleSection("exif-subifd")
        assertThat(state().browser!!.rows.filterIsInstance<SectionHeaderRow>().single { it.sectionId == "exif-subifd" }.expanded).isFalse()
        vm.onQueryChange("")
        val sections = state().browser!!.rows.filterIsInstance<SectionHeaderRow>()
        assertThat(sections.single { it.sectionId == "exif-subifd" }.expanded).isTrue()
        assertThat(sections.single { it.sectionId == "makernote-fujifilm" }.expanded).isFalse()
    }

    @Test
    fun `viewer state survives in the saved state handle`() = runTest(dispatcher) {
        val saved = SavedStateHandle()
        val first = viewModel(saved)
        observe(first)
        first.onQueryChange("lens")
        first.setMode(ValueMode.Raw)
        first.toggleFilter(SensitivityCategory.SerialNumber)

        // A new view model on the same handle, as after process death.
        val restored = viewModel(saved)
        val state = observe(restored)
        assertThat(restored.query).isEqualTo("lens")
        assertThat(state().mode).isEqualTo(ValueMode.Raw)
        assertThat(state().filter).isEqualTo(SensitivityCategory.SerialNumber)
        assertThat(state().browser!!.rows.filterIsInstance<TagRow>().map { it.tag.name }).containsExactly("Lens Serial Number")
    }

    @Test
    fun `read failures map to user-facing errors and retry reloads`() = runTest(dispatcher) {
        val failures = mapOf(
            UnsupportedImageException("nope") to PhotoLoadError.Unsupported,
            CorruptImageException("broken") to PhotoLoadError.Damaged,
            SecurityException("revoked") to PhotoLoadError.AccessLost,
            FileNotFoundException("gone") to PhotoLoadError.NotFound,
            PhotoAccessException(PhotoAccessError.PermissionDenied) to PhotoLoadError.AccessLost,
            IOException("disk") to PhotoLoadError.ReadFailed,
            IllegalStateException("bug") to PhotoLoadError.ReadFailed,
        )
        for ((error, expected) in failures) {
            val vm = viewModel(read = { throw error })
            assertThat(observe(vm)().load).isEqualTo(PhotoLoad.Failed(expected))
        }
        assertThat(PhotoLoadError.Unsupported.canRetry).isFalse()
        assertThat(PhotoLoadError.AccessLost.canRetry).isTrue()

        var attempts = 0
        val vm = viewModel(read = { if (attempts++ == 0) throw IOException("flaky") else FakeReports.cameraRaw })
        val state = observe(vm)
        assertThat(state().load).isEqualTo(PhotoLoad.Failed(PhotoLoadError.ReadFailed))
        vm.reload()
        assertThat(state().load).isInstanceOf(PhotoLoad.Loaded::class.java)
    }

    @Test
    fun `saving a copy opens it, and failures are reported`() = runTest(dispatcher) {
        val vm = viewModel()
        observe(vm)
        vm.saveCopy(Uri.parse("content://destination/copy.raf"))
        val opened = vm.events.first() as PhotoEvent.OpenPhoto
        assertThat(opened.ref.writable).isTrue()

        val failing = viewModel(saveCopy = { _, _ -> throw PhotoAccessException(PhotoAccessError.WriteFailed) })
        observe(failing)
        failing.saveCopy(Uri.parse("content://destination/copy.raf"))
        assertThat(failing.events.first()).isEqualTo(PhotoEvent.CopyFailed(PhotoAccessError.WriteFailed))
        assertThat(failing.state.value.isWorking).isFalse()
    }

    @Test
    fun `opening the original resolves the picked document`() = runTest(dispatcher) {
        val vm = viewModel()
        observe(vm)
        vm.openOriginal(Uri.parse("content://documents/original.raf"))
        val opened = vm.events.first() as PhotoEvent.OpenPhoto
        assertThat(opened.ref.uri).isEqualTo("content://documents/original.raf")

        val failing = viewModel(openDocument = { throw PhotoAccessException(PhotoAccessError.Unsupported) })
        observe(failing)
        failing.openOriginal(Uri.parse("content://documents/notes.txt"))
        assertThat(failing.events.first()).isEqualTo(PhotoEvent.OpenFailed(PhotoAccessError.Unsupported))
    }
}
