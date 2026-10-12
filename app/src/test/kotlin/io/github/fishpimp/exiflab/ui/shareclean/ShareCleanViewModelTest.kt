package io.github.fishpimp.exiflab.ui.shareclean

import android.net.Uri
import com.google.common.truth.Truth.assertThat
import io.github.fishpimp.exiflab.data.photos.OpenResult
import io.github.fishpimp.exiflab.data.photos.PhotoAccessError
import io.github.fishpimp.exiflab.data.shareclean.CleanedFile
import io.github.fishpimp.exiflab.data.shareclean.Refusal
import io.github.fishpimp.exiflab.data.shareclean.RefusalReason
import io.github.fishpimp.exiflab.data.shareclean.ShareCleanChoice
import io.github.fishpimp.exiflab.data.shareclean.ShareCleanChoices
import io.github.fishpimp.exiflab.data.shareclean.ShareCleanPreset
import io.github.fishpimp.exiflab.data.shareclean.ShareCleanResult
import io.github.fishpimp.exiflab.data.shareclean.ShareCleaning
import io.github.fishpimp.exiflab.metadata.edit.StripCategory
import io.github.fishpimp.exiflab.screenshots.FakeReports
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.io.File

/** The Share clean sheet's flow: last preset preselected, summary, cleaning, share or report. */
@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
class ShareCleanViewModelTest {
    private val saved = MutableStateFlow(ShareCleanChoice(ShareCleanPreset.Everything))
    private val choices = object : ShareCleanChoices {
        override val choice = saved
        override suspend fun save(choice: ShareCleanChoice) {
            saved.value = choice
        }
    }
    private var cleaned: Pair<List<String>, ShareCleanChoice>? = null
    private var result = ShareCleanResult(listOf(CleanedFile(File("IMG_1.jpg"), "image/jpeg", "IMG_1.jpg")), emptyList())
    private var gate: CompletableDeferred<Unit>? = null
    private val cleaner = ShareCleaning { photos, choice, onProgress ->
        cleaned = photos.map { it.uri } to choice
        onProgress(1, photos.size)
        gate?.await()
        result
    }
    private var resolved: OpenResult = OpenResult.Opened(listOf(FakeReports.phoneRef))

    private fun viewModel() = ShareCleanViewModel(
        cleaner = cleaner,
        choices = choices,
        readReport = { FakeReports.phoneJpeg },
        resolve = { resolved },
    )

    @Before fun setUp() = Dispatchers.setMain(UnconfinedTestDispatcher())

    @After fun tearDown() = Dispatchers.resetMain()

    @Test
    fun `opening one photo preselects the last preset and loads its summary`() {
        val vm = viewModel()
        vm.open(listOf(FakeReports.phoneRef))

        val state = vm.state.value
        assertThat(state.isOpen).isTrue()
        assertThat(state.choice.preset).isEqualTo(ShareCleanPreset.Everything)
        assertThat(state.summary).isEqualTo(SummarySource.Ready(FakeReports.phoneJpeg))
        assertThat(state.rawCount).isEqualTo(0)
        assertThat(state.canShare).isTrue()
    }

    @Test
    fun `several photos count their RAW files and need something to share`() {
        val vm = viewModel()
        vm.open(listOf(FakeReports.phoneRef, FakeReports.rawRef))
        assertThat(vm.state.value.summary).isEqualTo(SummarySource.Several)
        assertThat(vm.state.value.rawCount).isEqualTo(1)

        vm.open(listOf(FakeReports.rawRef, FakeReports.rawRef.copy(uri = "content://raw/2")))
        vm.setIncludeRawPreviews(false)
        assertThat(vm.state.value.canShare).isFalse()
        vm.setIncludeRawPreviews(true)
        assertThat(vm.state.value.canShare).isTrue()
    }

    @Test
    fun `an empty custom choice cannot be shared`() {
        val vm = viewModel()
        vm.open(listOf(FakeReports.phoneRef))
        vm.selectPreset(ShareCleanPreset.Custom)
        assertThat(vm.state.value.choice.categories).containsExactly(StripCategory.Location)
        vm.toggleCustom(StripCategory.Location)
        assertThat(vm.state.value.canShare).isFalse()
        vm.toggleCustom(StripCategory.Dates)
        assertThat(vm.state.value.choice.categories).containsExactly(StripCategory.Dates)
        assertThat(vm.state.value.canShare).isTrue()
    }

    @Test
    fun `share remembers the choice, cleans with progress and hands the copies on`() = runBlocking {
        gate = CompletableDeferred()
        val vm = viewModel()
        vm.open(listOf(FakeReports.phoneRef))
        vm.selectPreset(ShareCleanPreset.LocationOnly)

        vm.share()
        assertThat(vm.state.value.step).isEqualTo(ShareCleanStep.Cleaning(1, 1))
        assertThat(saved.value.preset).isEqualTo(ShareCleanPreset.LocationOnly)
        assertThat(cleaned!!.second.categories).containsExactly(StripCategory.Location)

        gate!!.complete(Unit)
        val event = withTimeout(5_000) { vm.events.first() }
        assertThat(event).isEqualTo(ShareCleanEvent.Share(result.files))
        assertThat(vm.state.value.isOpen).isFalse()
    }

    @Test
    fun `photos left out are reported, and the rest can still be shared`() = runBlocking {
        val refusal = Refusal("DSC_0001.NEF", RefusalReason.NoRawPreview)
        result = result.copy(refusals = listOf(refusal))
        val vm = viewModel()
        vm.open(listOf(FakeReports.phoneRef, FakeReports.rawRef))

        vm.share()
        val step = vm.state.value.step as ShareCleanStep.Finished
        assertThat(step.result.refusals).containsExactly(refusal)

        vm.shareCleaned()
        assertThat(withTimeout(5_000) { vm.events.first() }).isEqualTo(ShareCleanEvent.Share(result.files))
        assertThat(vm.state.value.isOpen).isFalse()
    }

    @Test
    fun `cancelling cleaning goes back to the choices, dismissing closes`() {
        gate = CompletableDeferred()
        val vm = viewModel()
        vm.open(listOf(FakeReports.phoneRef))
        vm.share()
        vm.cancelCleaning()
        assertThat(vm.state.value.step).isEqualTo(ShareCleanStep.Choosing)
        assertThat(vm.state.value.isOpen).isTrue()
        vm.dismiss()
        assertThat(vm.state.value.isOpen).isFalse()
    }

    @Test
    fun `picked photos open the sheet, and a failed pick says why`() = runBlocking {
        val vm = viewModel()
        vm.openUris(listOf(Uri.parse("content://media/picker/1")))
        assertThat(vm.state.value.photos).containsExactly(FakeReports.phoneRef)

        resolved = OpenResult.Failed(PhotoAccessError.Unsupported)
        vm.dismiss()
        vm.openUris(listOf(Uri.parse("content://media/picker/2")))
        assertThat(withTimeout(5_000) { vm.events.first() }).isEqualTo(ShareCleanEvent.OpenFailed(PhotoAccessError.Unsupported))
        assertThat(vm.state.value.isOpen).isFalse()
    }
}
